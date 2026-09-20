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

package com.netflix.spinnaker.clouddriver.ecs.deploy.ops

import com.netflix.spinnaker.clouddriver.aws.security.AmazonCredentials
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.CreateServerGroupDescription
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeCreateServerGroupDescription
import org.ministack.testcontainers.MiniStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.AssignPublicIp
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration
import software.amazon.awssdk.services.ecs.model.Compatibility
import software.amazon.awssdk.services.ecs.model.ContainerDefinition
import software.amazon.awssdk.services.ecs.model.CreateClusterRequest
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest
import software.amazon.awssdk.services.ecs.model.LaunchType
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration
import software.amazon.awssdk.services.ecs.model.NetworkMode
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest
import software.amazon.awssdk.services.ecs.model.Service
import spock.lang.Shared
import spock.lang.Specification

import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * Exercises {@link EcsNativeCreateServerGroupAtomicOperation}'s in-place-update happy path (the
 * {@code ROLLING} deployment strategy) against a real MiniStack-backed {@link EcsClient}, instead
 * of the fully-mocked {@code ecs} client used by {@code EcsNativeCreateServerGroupAtomicOperationSpec}.
 * That confirms the production {@code registerTaskDefinition()}/{@code buildDeploymentConfiguration()}
 * code builds requests a real(-ish) ECS control plane actually accepts and completes a rollout
 * for -- not just that it builds the right-shaped request object.
 *
 * <p>Only {@code getAmazonEcsClient()}, {@code getCredentials()} and {@code resolveTaskRoleArn()}
 * are stubbed on the operation (the same three collaborators {@code
 * EcsNativeCreateServerGroupAtomicOperationSpec}'s in-place-update test stubs) -- everything else,
 * including {@code registerTaskDefinition()}'s request-building and the actual {@code
 * UpdateService} call, runs for real against MiniStack.
 *
 * <p>Uses the CI-built preview image for ministackorg/ministack#1779 ("fix(ecs): drain completed
 * service deployments"), not yet merged/released: MiniStack's released {@code 1.5.10} never
 * drains the old deployment out of the list once a rollout completes, which this test's assertion
 * on a single collapsed deployment depends on. Swap to a released {@code MiniStackContainer("<tag>")}
 * once #1779 ships. (Confirmed both the drain fix and the underlying real-container-execution
 * behavior empirically first -- see the throwaway {@code MiniStackEcsRolloutSpikeTest} spike, not
 * part of this branch, for that investigation.)
 */
class EcsNativeCreateServerGroupAtomicOperationMiniStackSpec extends Specification {

  private static final DockerImageName MINISTACK_PR_1779_PREVIEW_IMAGE =
      DockerImageName.parse('ministackorg/ministack-preview-build:pr-1779-51b00d6c')
          .asCompatibleSubstituteFor('ministackorg/ministack')

  private static final String REGION = 'us-east-1'
  private static final String CLUSTER_NAME = 'ecs-native-happy-path-cluster'
  private static final String SERVICE_NAME = 'mygreatapp-stack1-details2-v011'
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(120)
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(3)

  @Shared
  MiniStackContainer ministack = new MiniStackContainer(MINISTACK_PR_1779_PREVIEW_IMAGE).withRealInfrastructure()

  @Shared
  EcsClient ecs

  def setupSpec() {
    ministack.start()

    def credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(ministack.getAccessKey(), ministack.getSecretKey()))
    def endpoint = URI.create(ministack.getEndpoint())

    ecs = EcsClient.builder()
        .endpointOverride(endpoint)
        .credentialsProvider(credentials)
        .region(Region.of(REGION))
        .build()

    def ec2 = Ec2Client.builder()
        .endpointOverride(endpoint)
        .credentialsProvider(credentials)
        .region(Region.of(REGION))
        .build()

    def vpcId = ec2.createVpc(CreateVpcRequest.builder().cidrBlock('10.0.0.0/16').build()).vpc().vpcId()
    def subnetId = ec2.createSubnet(
        CreateSubnetRequest.builder().vpcId(vpcId).cidrBlock('10.0.1.0/24').build()).subnet().subnetId()

    ecs.createCluster(CreateClusterRequest.builder().clusterName(CLUSTER_NAME).build())

    // Bootstrap the durable service this operation will roll in place, the way an operator's
    // first ecs-native deploy would have created it (outside the scope of this test).
    def initialTaskDefArn = registerRawTaskDefinition('sleep 600')
    ecs.createService(CreateServiceRequest.builder()
        .cluster(CLUSTER_NAME)
        .serviceName(SERVICE_NAME)
        .taskDefinition(initialTaskDefArn)
        .desiredCount(1)
        .launchType(LaunchType.FARGATE)
        .networkConfiguration(NetworkConfiguration.builder()
            .awsvpcConfiguration(AwsVpcConfiguration.builder()
                .subnets(subnetId)
                .assignPublicIp(AssignPublicIp.ENABLED)
                .build())
            .build())
        .build())

    pollUntil { Service svc -> svc.runningCount() >= 1 }
  }

  def cleanupSpec() {
    ministack?.stop()
  }

  private String registerRawTaskDefinition(String sleepCommand) {
    ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
        .family('ecs-native-happy-path-bootstrap-task')
        .networkMode(NetworkMode.AWSVPC)
        .requiresCompatibilities(Compatibility.FARGATE)
        .cpu('256')
        .memory('512')
        .containerDefinitions(ContainerDefinition.builder()
            .name('app')
            .image('busybox:latest')
            .command('sh', '-c', sleepCommand)
            .build())
        .build()).taskDefinition().taskDefinitionArn()
  }

  private Service pollUntil(Closure<Boolean> done) {
    Instant deadline = Instant.now().plus(POLL_TIMEOUT)
    Service last = null
    while (Instant.now().isBefore(deadline)) {
      def services = ecs.describeServices(DescribeServicesRequest.builder()
          .cluster(CLUSTER_NAME)
          .services(SERVICE_NAME)
          .build()).services()
      last = services.isEmpty() ? null : services.get(0)
      if (last != null && done(last)) {
        return last
      }
      Thread.sleep(POLL_INTERVAL.toMillis())
    }
    throw new AssertionError(
        "timed out after ${POLL_TIMEOUT} waiting for condition; last observed state: ${last}")
  }

  def 'rolling ecs-native in-place update against a real ECS-compatible backend actually completes'() {
    given: 'a description that rolls the existing service in place with an explicit ROLLING strategy'
    def description = new EcsNativeCreateServerGroupDescription(
        inPlaceUpdate: true,
        ecsClusterName: CLUSTER_NAME,
        dockerImageAddress: 'busybox:latest',
        launchType: 'FARGATE',
        networkMode: 'awsvpc',
        computeUnits: 256,
        reservedMemory: 512,
        deploymentStrategy: 'ROLLING',
        minimumHealthyPercent: 100,
        maximumPercent: 200)
    description.setSource(new CreateServerGroupDescription.Source(asgName: SERVICE_NAME, region: REGION))

    def operation = Spy(EcsNativeCreateServerGroupAtomicOperation, constructorArgs: [description])
    operation.getAmazonEcsClient() >> ecs
    operation.getCredentials() >> Mock(AmazonCredentials)
    operation.resolveTaskRoleArn(_) >> 'arn:aws:iam::123456789012:role/ecsRole'

    when: 'the real production operation rolls the service in place'
    def result = operation.operate([])

    then: 'it reports the same service, in the right region'
    result.serverGroupNameByRegion == [(REGION): SERVICE_NAME]

    and: 'MiniStack actually completes the rollout to a new, healthy task definition revision'
    def finalState = pollUntil { Service svc ->
      svc.deployments().size() == 1 &&
          svc.deployments().get(0).rolloutStateAsString() == 'COMPLETED'
    }
    finalState.deployments().get(0).desiredCount() == 1
    finalState.deployments().get(0).runningCount() == 1
  }
}
