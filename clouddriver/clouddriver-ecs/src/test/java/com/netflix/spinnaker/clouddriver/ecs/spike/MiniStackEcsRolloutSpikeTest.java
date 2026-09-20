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
import software.amazon.awssdk.services.ecs.model.Deployment;
import software.amazon.awssdk.services.ecs.model.DeploymentCircuitBreaker;
import software.amazon.awssdk.services.ecs.model.DeploymentConfiguration;
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
 * <p>Round 1 (against the released {@code 1.5.10} image) found that MiniStack simulates
 * per-deployment {@code rolloutState} correctly, but never drains/removes the old deployment from
 * the list -- see ministackorg/ministack#1779, which claims to fix exactly that. Round 2, against
 * that PR's CI-built preview image, confirmed the fix: the deployments list now collapses to one
 * entry on the new revision once the rollout completes.
 *
 * <p>Round 3 (this one) asks the remaining open question: does MiniStack simulate ECS's deployment
 * circuit breaker at all -- a new deployment that never reaches healthy either reporting a {@code
 * FAILED} rolloutState, or (with {@code rollback=true}) actually reverting the service back to the
 * last healthy task definition -- or does a permanently-crashing revision just hang forever with no
 * failure signal? {@code WaitForEcsNativeServiceDeploymentTask} needs to observe a rollback as a
 * terminal (failed) stage outcome for ecs-native's circuit-breaker-rollback feature to be testable
 * against MiniStack at all.
 *
 * <p>This test is deliberately verbose (see the {@code System.out.println} calls) so that whatever
 * happens -- pass, fail, or an exception from an AWS API MiniStack doesn't support -- the raw CI
 * job log is self-explanatory without needing to attach a debugger.
 */
@Testcontainers
class MiniStackEcsRolloutSpikeTest {

  /**
   * CI-built preview image for ministackorg/ministack#1779 ("fix(ecs): drain completed service
   * deployments"), published by that PR's own GitHub Actions run -- not yet merged/released. Lives
   * under a different image repository than the released {@code ministackorg/ministack} image, so
   * this is wrapped with {@code asCompatibleSubstituteFor} below rather than passed as a plain tag.
   * Swap back to a released {@code MiniStackContainer("<tag>")} once #1779 merges and ships.
   */
  private static final DockerImageName MINISTACK_PR_1779_PREVIEW_IMAGE =
      DockerImageName.parse("ministackorg/ministack-preview-build:pr-1779-51b00d6c")
          .asCompatibleSubstituteFor("ministackorg/ministack");

  private static final String REGION = "us-east-1";
  private static final String CLUSTER_NAME = "spike-cluster";
  private static final String SERVICE_NAME = "spike-service";
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(120);
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

  @Container
  static final MiniStackContainer ministack =
      new MiniStackContainer(MINISTACK_PR_1779_PREVIEW_IMAGE).withRealInfrastructure();

  private static EcsClient ecsClient;
  private static String subnetId;

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
    subnetId =
        ec2Client
            .createSubnet(
                CreateSubnetRequest.builder().vpcId(vpcId).cidrBlock("10.0.1.0/24").build())
            .subnet()
            .subnetId();
    System.out.println("[spike] created vpc=" + vpcId + " subnet=" + subnetId);

    ecsClient.createCluster(CreateClusterRequest.builder().clusterName(CLUSTER_NAME).build());
    System.out.println("[spike] created cluster=" + CLUSTER_NAME);

    String initialTaskDefArn = registerTaskDefinition("spike-task", "sh", "-c", "sleep 600");
    System.out.println("[spike] registered initial task definition=" + initialTaskDefArn);

    Service service = createFargateService(SERVICE_NAME, initialTaskDefArn, null);
    System.out.println("[spike] createService returned: " + service);
  }

  private static String registerTaskDefinition(String sleepCommand) {
    return registerTaskDefinition("spike-task", "sh", "-c", sleepCommand);
  }

  private static String registerTaskDefinition(String family, String... command) {
    RegisterTaskDefinitionResponse response =
        ecsClient.registerTaskDefinition(
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
                        .command(command)
                        .build())
                .build());
    return response.taskDefinition().taskDefinitionArn();
  }

  private static Service createFargateService(
      String serviceName, String taskDefArn, DeploymentConfiguration deploymentConfiguration) {
    CreateServiceRequest.Builder request =
        CreateServiceRequest.builder()
            .cluster(CLUSTER_NAME)
            .serviceName(serviceName)
            .taskDefinition(taskDefArn)
            .desiredCount(1)
            .launchType(LaunchType.FARGATE)
            .networkConfiguration(
                NetworkConfiguration.builder()
                    .awsvpcConfiguration(
                        AwsVpcConfiguration.builder()
                            .subnets(subnetId)
                            .assignPublicIp(AssignPublicIp.ENABLED)
                            .build())
                    .build());
    if (deploymentConfiguration != null) {
      request.deploymentConfiguration(deploymentConfiguration);
    }
    return ecsClient.createService(request.build()).service();
  }

  /**
   * Phase 1: does a freshly created service ever reach its desired running count? This is the "does
   * MiniStack really run a Docker container for RunTask" half of the question -- necessary
   * scaffolding for phase 2, and informative on its own if it fails or times out.
   */
  @Test
  void serviceReachesDesiredRunningCountViaRealContainerExecution() {
    Service finalState =
        pollUntil(SERVICE_NAME, "initial rollout to running", s -> s.runningCount() >= 1);

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
    pollUntil(SERVICE_NAME, "pre-update settle", s -> s.runningCount() >= 1);

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
            SERVICE_NAME,
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

  /**
   * Phase 3: does MiniStack simulate the ECS deployment circuit breaker at all? Creates its own
   * service (independent of phases 1-2, but sharing the cluster/vpc/subnet from {@code
   * setupClients()}) on a healthy revision, then updates it to a revision whose only container
   * exits immediately -- with {@code deploymentCircuitBreaker.rollback=true} -- and watches for
   * either a {@code FAILED} rolloutState or an actual rollback to the healthy revision. A timeout
   * here (no failure signal, ever) is itself the answer: circuit-breaker rollback isn't simulated.
   */
  @Test
  void updateServiceToACrashingRevisionWithCircuitBreakerEitherFailsOrRollsBack() {
    String serviceName = "spike-service-circuit-breaker";
    String healthyTaskDefArn = registerTaskDefinition("spike-cb-task", "sh", "-c", "sleep 600");
    System.out.println("[spike][cb] registered healthy task definition=" + healthyTaskDefArn);

    Service created = createFargateService(serviceName, healthyTaskDefArn, null);
    System.out.println("[spike][cb] createService returned: " + created);
    pollUntil(serviceName, "cb pre-update settle", s -> s.runningCount() >= 1);

    String crashingTaskDefArn = registerTaskDefinition("spike-cb-task", "sh", "-c", "exit 1");
    System.out.println("[spike][cb] registered crashing task definition=" + crashingTaskDefArn);

    ecsClient.updateService(
        UpdateServiceRequest.builder()
            .cluster(CLUSTER_NAME)
            .service(serviceName)
            .taskDefinition(crashingTaskDefArn)
            .deploymentConfiguration(
                DeploymentConfiguration.builder()
                    .deploymentCircuitBreaker(
                        DeploymentCircuitBreaker.builder().enable(true).rollback(true).build())
                    .build())
            .build());
    System.out.println("[spike][cb] updateService (crashing revision) issued, polling...");

    Service finalState =
        pollUntil(
            serviceName,
            "cb rollout outcome",
            s ->
                s.deployments().stream().anyMatch(d -> "FAILED".equals(d.rolloutStateAsString()))
                    || (s.deployments().size() == 1
                        && healthyTaskDefArn.equals(s.deployments().get(0).taskDefinition())
                        && "COMPLETED".equals(s.deployments().get(0).rolloutStateAsString())));

    System.out.println("[spike][cb] phase 3 final state: " + finalState);
    boolean reportedFailed =
        finalState.deployments().stream().anyMatch(d -> "FAILED".equals(d.rolloutStateAsString()));
    boolean rolledBack =
        finalState.deployments().size() == 1
            && healthyTaskDefArn.equals(finalState.deployments().get(0).taskDefinition());
    System.out.println(
        "[spike][cb] reportedFailed=" + reportedFailed + " rolledBack=" + rolledBack);
    assertThat(reportedFailed || rolledBack)
        .as("expected either a FAILED rolloutState or an actual rollback to the healthy revision")
        .isTrue();
  }

  private static Service pollUntil(
      String serviceName, String label, java.util.function.Predicate<Service> done) {
    Instant deadline = Instant.now().plus(POLL_TIMEOUT);
    Service last = null;
    while (Instant.now().isBefore(deadline)) {
      List<Service> services =
          ecsClient
              .describeServices(
                  DescribeServicesRequest.builder()
                      .cluster(CLUSTER_NAME)
                      .services(serviceName)
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
