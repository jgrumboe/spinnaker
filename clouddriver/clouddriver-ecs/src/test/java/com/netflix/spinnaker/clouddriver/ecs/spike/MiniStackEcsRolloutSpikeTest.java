/*
 * Copyright 2026 Spinnaker contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.ecs.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ministack.testcontainers.MiniStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration;
import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.CreateClusterRequest;
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest;
import software.amazon.awssdk.services.ecs.model.Deployment;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

/**
 * SPIKE -- not part of the ecs-native feature, and not intended to be merged as-is.
 *
 * <p>Answers one question for the "ecs-native + MiniStack" end-to-end test plan: does MiniStack's
 * ECS emulation actually simulate the service deployment rollout state machine (a {@code
 * DescribeServices} deployment transitioning through {@code rolloutState} {@code IN_PROGRESS} ->
 * {@code COMPLETED}, with the old deployment draining out of the list as the new one becomes {@code
 * PRIMARY}), or does it only accept the write APIs (CreateService/UpdateService) without modelling
 * rollout progression at all?
 *
 * <p>{@code WaitForEcsNativeServiceDeploymentTask} (orca) polls exactly this state today against
 * real AWS. The answer here determines whether a MiniStack-backed e2e test for ecs-native can
 * assert on real rollout behavior, or only on "the service/task definition ended up correct."
 *
 * <p>This test is deliberately verbose (see the {@code System.out.println} calls) so that whatever
 * happens -- pass, fail, or an exception from an AWS API MiniStack doesn't support -- the raw CI
 * job log is self-explanatory without needing to attach a debugger.
 */
@Testcontainers
class MiniStackEcsRolloutSpikeTest {

  /**
   * Same tag already vetted elsewhere in this repo for MiniStack's S3 emulation (see {@code
   * AmazonS3DataProviderMiniStackTest}). Not yet confirmed to include the Docker-socket-backed ECS
   * "real infrastructure" feature at this specific version -- if it doesn't, the container startup
   * or the first ECS call below will fail with an actionable error, which is itself a valid spike
   * result.
   */
  private static final String MINISTACK_IMAGE_TAG = "1.5.10";

  private static final String REGION = "us-east-1";
  private static final String CLUSTER_NAME = "spike-cluster";
  private static final String SERVICE_NAME = "spike-service";
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(120);
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

  @Container
  static final MiniStackContainer ministack =
      new MiniStackContainer(MINISTACK_IMAGE_TAG).withRealInfrastructure();

  private static EcsClient ecsClient;

  @BeforeAll
  static void setupClients() {
    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ministack.getAccessKey(), ministack.getSecretKey()));
    URI endpoint = URI.create(ministack.getEndpoint());

    ecsClient =
        EcsClient.builder()
            .endpointOverride(endpoint)
            .credentialsProvider(credentials)
            .region(Region.of(REGION))
            .build();

    Ec2Client ec2Client =
        Ec2Client.builder()
            .endpointOverride(endpoint)
            .credentialsProvider(credentials)
            .region(Region.of(REGION))
            .build();

    String vpcId =
        ec2Client
            .createVpc(CreateVpcRequest.builder().cidrBlock("10.0.0.0/16").build())
            .vpc()
            .vpcId();
    String subnetId =
        ec2Client
            .createSubnet(
                CreateSubnetRequest.builder().vpcId(vpcId).cidrBlock("10.0.1.0/24").build())
            .subnet()
            .subnetId();
    System.out.println("[spike] created vpc=" + vpcId + " subnet=" + subnetId);

    ecsClient.createCluster(CreateClusterRequest.builder().clusterName(CLUSTER_NAME).build());
    System.out.println("[spike] created cluster=" + CLUSTER_NAME);

    String initialTaskDefArn = registerTaskDefinition("sleep 600");
    System.out.println("[spike] registered initial task definition=" + initialTaskDefArn);

    Service service =
        ecsClient
            .createService(
                CreateServiceRequest.builder()
                    .cluster(CLUSTER_NAME)
                    .serviceName(SERVICE_NAME)
                    .taskDefinition(initialTaskDefArn)
                    .desiredCount(1)
                    .launchType(LaunchType.FARGATE)
                    .networkConfiguration(
                        NetworkConfiguration.builder()
                            .awsvpcConfiguration(
                                AwsVpcConfiguration.builder()
                                    .subnets(subnetId)
                                    .assignPublicIp(AssignPublicIp.ENABLED)
                                    .build())
                            .build())
                    .build())
            .service();
    System.out.println("[spike] createService returned: " + service);
  }

  private static String registerTaskDefinition(String sleepCommand) {
    RegisterTaskDefinitionResponse response =
        ecsClient.registerTaskDefinition(
            RegisterTaskDefinitionRequest.builder()
                .family("spike-task")
                .networkMode(NetworkMode.AWSVPC)
                .requiresCompatibilities(Compatibility.FARGATE)
                .cpu("256")
                .memory("512")
                .containerDefinitions(
                    ContainerDefinition.builder()
                        .name("app")
                        .image("busybox:latest")
                        .command("sh", "-c", sleepCommand)
                        .build())
                .build());
    return response.taskDefinition().taskDefinitionArn();
  }

  /**
   * Phase 1: does a freshly created service ever reach its desired running count? This is the "does
   * MiniStack really run a Docker container for RunTask" half of the question -- necessary
   * scaffolding for phase 2, and informative on its own if it fails or times out.
   */
  @Test
  void serviceReachesDesiredRunningCountViaRealContainerExecution() {
    Service finalState = pollUntil("initial rollout to running", s -> s.runningCount() >= 1);

    System.out.println("[spike] phase 1 final state: " + finalState);
    assertThat(finalState.runningCount())
        .as("runningCount should reach desiredCount if RunTask actually starts a container")
        .isGreaterThanOrEqualTo(1);
  }

  /**
   * Phase 2: after an UpdateService to a new task definition revision, does the deployments list
   * show real rollout progression -- a transient second deployment collapsing back to one, with
   * rolloutState eventually COMPLETED and pointing at the new revision? This is the actual
   * hypothesis under test.
   */
  @Test
  void updateServiceProducesAnObservableRolloutToCompletion() {
    // Make sure phase 1's service exists and has settled before mutating it.
    pollUntil("pre-update settle", s -> s.runningCount() >= 1);

    String newTaskDefArn = registerTaskDefinition("sleep 601");
    System.out.println("[spike] registered updated task definition=" + newTaskDefArn);

    ecsClient.updateService(
        UpdateServiceRequest.builder()
            .cluster(CLUSTER_NAME)
            .service(SERVICE_NAME)
            .taskDefinition(newTaskDefArn)
            .build());
    System.out.println("[spike] updateService issued, polling for rollout...");

    Service finalState =
        pollUntil(
            "post-update rollout",
            s ->
                s.deployments().size() == 1
                    && newTaskDefArn.equals(s.deployments().get(0).taskDefinition())
                    && s.deployments().get(0).rolloutStateAsString() != null
                    && s.deployments().get(0).rolloutStateAsString().equals("COMPLETED"));

    System.out.println("[spike] phase 2 final state: " + finalState);
    assertThat(finalState.deployments())
        .as("expected exactly one deployment once rollout settles, pointing at the new revision")
        .hasSize(1);
    Deployment onlyDeployment = finalState.deployments().get(0);
    assertThat(onlyDeployment.taskDefinition()).isEqualTo(newTaskDefArn);
    assertThat(onlyDeployment.rolloutStateAsString()).isEqualTo("COMPLETED");
  }

  private static Service pollUntil(String label, java.util.function.Predicate<Service> done) {
    Instant deadline = Instant.now().plus(POLL_TIMEOUT);
    Service last = null;
    while (Instant.now().isBefore(deadline)) {
      List<Service> services =
          ecsClient
              .describeServices(
                  DescribeServicesRequest.builder()
                      .cluster(CLUSTER_NAME)
                      .services(SERVICE_NAME)
                      .build())
              .services();
      last = services.isEmpty() ? null : services.get(0);
      System.out.println(
          "[spike][" + label + "] poll at " + Instant.now() + " -> " + describe(last));
      if (last != null && done.test(last)) {
        return last;
      }
      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    throw new AssertionError(
        "["
            + label
            + "] timed out after "
            + POLL_TIMEOUT
            + " waiting for condition; last observed state: "
            + describe(last));
  }

  private static String describe(Service service) {
    if (service == null) {
      return "<no service returned>";
    }
    StringBuilder sb =
        new StringBuilder()
            .append("status=")
            .append(service.status())
            .append(" desired=")
            .append(service.desiredCount())
            .append(" running=")
            .append(service.runningCount())
            .append(" pending=")
            .append(service.pendingCount())
            .append(" deployments=[");
    for (Deployment deployment : service.deployments()) {
      sb.append("{id=")
          .append(deployment.id())
          .append(", status=")
          .append(deployment.status())
          .append(", rolloutState=")
          .append(deployment.rolloutStateAsString())
          .append(", rolloutStateReason=")
          .append(deployment.rolloutStateReason())
          .append(", taskDefinition=")
          .append(deployment.taskDefinition())
          .append(", running=")
          .append(deployment.runningCount())
          .append(", desired=")
          .append(deployment.desiredCount())
          .append("} ");
    }
    sb.append("]");
    return sb.toString();
  }
}
