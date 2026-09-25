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

package com.netflix.spinnaker.clouddriver.ecs.deploy.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.netflix.spinnaker.clouddriver.aws.security.AmazonCredentials;
import com.netflix.spinnaker.clouddriver.data.task.Task;
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository;
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.CreateServerGroupDescription;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeCreateServerGroupDescription;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ministack.testcontainers.MiniStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
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
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.Service;

/**
 * Exercises {@link EcsNativeCreateServerGroupAtomicOperation}'s in-place-update happy path (the
 * {@code ROLLING} deployment strategy) against a real MiniStack-backed {@link EcsClient}, instead
 * of the fully-mocked {@code ecs} client used by {@code
 * EcsNativeCreateServerGroupAtomicOperationSpec} (the Spock unit spec in {@code src/test}). That
 * confirms the production {@code registerTaskDefinition()}/{@code buildDeploymentConfiguration()}
 * code builds requests a real(-ish) ECS control plane actually accepts and completes a rollout for
 * -- not just that it builds the right-shaped request object.
 *
 * <p>Lives in the {@code integration} source set (run by the {@code integrationTest} Gradle task,
 * not the plain {@code test} task) since it pulls a Docker image and does real network I/O against
 * it -- slower and more network-dependent than the fast unit-test suite it would otherwise be
 * bundled into. Plain JUnit5 + Mockito rather than Spock/Groovy: unlike {@code src/test}, this
 * source set has no {@code groovy} directory configured (see {@code clouddriver-ecs.gradle}), and
 * every existing integration spec here is already plain Java.
 *
 * <p>Only {@code getAmazonEcsClient()}, {@code getCredentials()} and {@code resolveTaskRoleArn()}
 * are stubbed on the operation (the same three collaborators the unit spec's in-place-update test
 * stubs) -- everything else, including {@code registerTaskDefinition()}'s request-building and the
 * actual {@code UpdateService} call, runs for real against MiniStack.
 *
 * <p>Uses the CI-built preview image for ministackorg/ministack#1779 ("fix(ecs): drain completed
 * service deployments"), not yet merged/released: MiniStack's released {@code 1.5.10} never drains
 * the old deployment out of the list once a rollout completes, which this test's assertion on a
 * single collapsed deployment depends on. Swap to a released {@code MiniStackContainer("<tag>")}
 * once #1779 ships. (Confirmed both the drain fix and the underlying real-container-execution
 * behavior empirically first -- see the throwaway {@code MiniStackEcsRolloutSpikeTest} spike, not
 * part of this branch, for that investigation.)
 */
@Testcontainers
class EcsNativeCreateServerGroupAtomicOperationMiniStackSpec {

  private static final DockerImageName MINISTACK_PR_1779_PREVIEW_IMAGE =
      DockerImageName.parse("ministackorg/ministack-preview-build:pr-1779-51b00d6c")
          .asCompatibleSubstituteFor("ministackorg/ministack");

  private static final String REGION = "us-east-1";
  private static final String CLUSTER_NAME = "ecs-native-happy-path-cluster";
  // ecs-native uses a fixed, unversioned service name equal to the moniker family name
  // (app-stack-detail, no -vNNN suffix). The application/stack/detail set on the description below
  // must resolve to exactly this name so operate() finds the pre-created service and rolls it in
  // place via UpdateService.
  private static final String APPLICATION = "mygreatapp";
  private static final String STACK = "stack1";
  private static final String FREE_FORM_DETAILS = "details2";
  private static final String SERVICE_NAME = APPLICATION + "-" + STACK + "-" + FREE_FORM_DETAILS;
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(120);
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

  @Container
  static final MiniStackContainer ministack =
      new MiniStackContainer(MINISTACK_PR_1779_PREVIEW_IMAGE).withRealInfrastructure();

  private static EcsClient ecs;

  @BeforeAll
  static void setupOnce() {
    // operate() calls updateTaskStatus(), which reads this thread-local -- unset, that's an
    // immediate NPE regardless of which branch of operate() runs.
    TaskRepository.threadLocalTask.set(mock(Task.class));

    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ministack.getAccessKey(), ministack.getSecretKey()));
    URI endpoint = URI.create(ministack.getEndpoint());

    ecs =
        EcsClient.builder()
            .endpointOverride(endpoint)
            .credentialsProvider(credentials)
            .region(Region.of(REGION))
            .build();

    Ec2Client ec2 =
        Ec2Client.builder()
            .endpointOverride(endpoint)
            .credentialsProvider(credentials)
            .region(Region.of(REGION))
            .build();

    String vpcId =
        ec2.createVpc(CreateVpcRequest.builder().cidrBlock("10.0.0.0/16").build()).vpc().vpcId();
    String subnetId =
        ec2.createSubnet(
                CreateSubnetRequest.builder().vpcId(vpcId).cidrBlock("10.0.1.0/24").build())
            .subnet()
            .subnetId();

    ecs.createCluster(CreateClusterRequest.builder().clusterName(CLUSTER_NAME).build());

    // Bootstrap the durable service this operation will roll in place, the way an operator's
    // first ecs-native deploy would have created it (outside the scope of this test).
    String initialTaskDefArn =
        registerRawTaskDefinition("ecs-native-happy-path-bootstrap-task", "sleep 600");
    ecs.createService(
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
            .build());

    pollUntil(svc -> svc.runningCount() >= 1);
  }

  @AfterAll
  static void teardownOnce() {
    ministack.stop();
  }

  private static String registerRawTaskDefinition(String family, String sleepCommand) {
    return ecs.registerTaskDefinition(
            RegisterTaskDefinitionRequest.builder()
                .family(family)
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
                .build())
        .taskDefinition()
        .taskDefinitionArn();
  }

  private static Service pollUntil(Predicate<Service> done) {
    Instant deadline = Instant.now().plus(POLL_TIMEOUT);
    Service last = null;
    while (Instant.now().isBefore(deadline)) {
      List<Service> services =
          ecs.describeServices(
                  DescribeServicesRequest.builder()
                      .cluster(CLUSTER_NAME)
                      .services(SERVICE_NAME)
                      .build())
              .services();
      last = services.isEmpty() ? null : services.get(0);
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
        "timed out after " + POLL_TIMEOUT + " waiting for condition; last observed state: " + last);
  }

  @Test
  void rollingEcsNativeInPlaceUpdateAgainstARealEcsCompatibleBackendActuallyCompletes() {
    EcsNativeCreateServerGroupDescription description = new EcsNativeCreateServerGroupDescription();
    // ecs-native is always in-place: operate() computes the fixed service name from the moniker
    // (app-stack-detail) and rolls the pre-created service via UpdateService -- no inPlaceUpdate
    // flag, no source block needed for detection.
    description.setApplication(APPLICATION);
    description.setStack(STACK);
    description.setFreeFormDetails(FREE_FORM_DETAILS);
    description.setEcsClusterName(CLUSTER_NAME);
    description.setDockerImageAddress("busybox:latest");
    description.setLaunchType("FARGATE");
    description.setNetworkMode("awsvpc");
    description.setComputeUnits(256);
    description.setReservedMemory(512);
    description.setDeploymentStrategy("ROLLING");
    description.setMinimumHealthyPercent(100);
    description.setMaximumPercent(200);
    // No availability-zone map is set here, so getRegion() falls back to the source region; that's
    // the only reason the source is present.
    CreateServerGroupDescription.Source source = new CreateServerGroupDescription.Source();
    source.setRegion(REGION);
    description.setSource(source);

    EcsNativeCreateServerGroupAtomicOperation operation =
        spy(new EcsNativeCreateServerGroupAtomicOperation(description));
    doReturn(ecs).when(operation).getAmazonEcsClient();
    doReturn(mock(AmazonCredentials.class)).when(operation).getCredentials();
    doReturn("arn:aws:iam::123456789012:role/ecsRole").when(operation).resolveTaskRoleArn(any());

    DeploymentResult result = operation.operate(Collections.emptyList());

    assertThat(result.getServerGroupNameByRegion()).containsEntry(REGION, SERVICE_NAME);

    Service finalState =
        pollUntil(
            svc ->
                svc.deployments().size() == 1
                    && "COMPLETED".equals(svc.deployments().get(0).rolloutStateAsString()));

    assertThat(finalState.deployments().get(0).desiredCount()).isEqualTo(1);
    assertThat(finalState.deployments().get(0).runningCount()).isEqualTo(1);
  }
}
