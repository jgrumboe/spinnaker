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

import com.netflix.spinnaker.clouddriver.ecs.EcsNativeServiceTag
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeServiceDeploymentDescription
import com.netflix.spinnaker.clouddriver.ecs.security.NetflixECSCredentials
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider
import com.netflix.spinnaker.clouddriver.ecs.TestCredential
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.ContinueServiceDeploymentRequest
import software.amazon.awssdk.services.ecs.model.DeploymentLifecycleHookAction
import software.amazon.awssdk.services.ecs.model.DeploymentLifecycleHookDetail
import software.amazon.awssdk.services.ecs.model.DeploymentLifecycleHookStatus
import software.amazon.awssdk.services.ecs.model.DescribeServiceDeploymentsResponse
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse
import software.amazon.awssdk.services.ecs.model.ServiceDeployment
import software.amazon.awssdk.services.ecs.model.StopServiceDeploymentStopType
import software.amazon.awssdk.services.ecs.model.Service
import software.amazon.awssdk.services.ecs.model.StopServiceDeploymentRequest
import spock.lang.Specification
import spock.lang.Unroll

class EcsNativeServiceDeploymentAtomicOperationSpec extends Specification {

  def 'stops only the exact requested service deployment ARN'() {
    given:
    def credentials = TestCredential.named('test', [:])
    def ecs = Mock(EcsClient)
    def provider = Mock(AmazonClientProvider)
    def operation = new EcsNativeStopServiceDeploymentAtomicOperation(new EcsNativeServiceDeploymentDescription(
      credentials: credentials,
      region: 'us-west-2',
      ecsClusterName: 'cluster-arn',
      serverGroupName: 'service',
      ecsNativeExpectedServiceDeploymentArn: 'arn:deployment:7'
    ))
    operation.amazonClientProvider = provider
    provider.getAmazonEcsV2(_, 'us-west-2') >> ecs
    ecs.describeServices(_) >> DescribeServicesResponse.builder()
      .services(Service.builder().serviceName('service').tags(EcsNativeServiceTag.tag()).build()).build()

    when:
    operation.operate([])

    then:
    1 * ecs.stopServiceDeployment({ StopServiceDeploymentRequest request ->
      request.serviceDeploymentArn() == 'arn:deployment:7' &&
        request.stopType() == StopServiceDeploymentStopType.ROLLBACK
    } as StopServiceDeploymentRequest)
  }

  @Unroll
  def 'continue sends the awaiting hook id and action #expectedAction (lifecycleAction=#lifecycleAction)'() {
    given:
    def ecs = Mock(EcsClient)
    def operation = lifecycleOperation(new EcsNativeContinueServiceDeploymentAtomicOperation(
      description(lifecycleAction: lifecycleAction)), ecs)

    when:
    operation.operate([])

    then:
    1 * ecs.describeServiceDeployments(_) >> DescribeServiceDeploymentsResponse.builder()
      .serviceDeployments(ServiceDeployment.builder()
        .serviceDeploymentArn('arn:deployment:7')
        .lifecycleHookDetails(
          DeploymentLifecycleHookDetail.builder().hookId('done-hook').status(DeploymentLifecycleHookStatus.SUCCEEDED).build(),
          DeploymentLifecycleHookDetail.builder().hookId('pause-hook').status(DeploymentLifecycleHookStatus.AWAITING_ACTION).build())
        .build())
      .build()
    1 * ecs.continueServiceDeployment({ ContinueServiceDeploymentRequest request ->
      request.serviceDeploymentArn() == 'arn:deployment:7' &&
        request.hookId() == 'pause-hook' &&
        request.action() == expectedAction
    } as ContinueServiceDeploymentRequest)

    where:
    lifecycleAction | expectedAction
    null            | DeploymentLifecycleHookAction.CONTINUE
    'continue'      | DeploymentLifecycleHookAction.CONTINUE
    'ROLLBACK'      | DeploymentLifecycleHookAction.ROLLBACK
  }

  def 'continue fails closed when no hook awaits action'() {
    given:
    def ecs = Mock(EcsClient)
    def operation = lifecycleOperation(new EcsNativeContinueServiceDeploymentAtomicOperation(description([:])), ecs)

    when:
    operation.operate([])

    then:
    1 * ecs.describeServiceDeployments(_) >> DescribeServiceDeploymentsResponse.builder()
      .serviceDeployments(ServiceDeployment.builder().serviceDeploymentArn('arn:deployment:7').build())
      .build()
    def e = thrown(IllegalStateException)
    e.message.contains('no lifecycle hook awaiting action')
    0 * ecs.continueServiceDeployment(_)
  }

  def 'polls DescribeServices until ECS attaches the new service deployment'() {
    given:
    def ecs = Mock(EcsClient)
    def operation = lifecycleOperation(new EcsNativeContinueServiceDeploymentAtomicOperation(description([:])), ecs)
    operation.serviceDeploymentPollMillis = 0
    def bare = Service.builder().serviceName('service').build()
    def attached = Service.builder().serviceName('service').currentServiceDeployment('arn:deployment:9').build()

    when:
    def arn = operation.resolveCurrentServiceDeployment(bare, 'cluster-arn')

    then:
    2 * ecs.describeServices(_) >>> [
      DescribeServicesResponse.builder().services(bare).build(),
      DescribeServicesResponse.builder().services(attached).build()]
    arn == 'arn:deployment:9'
  }

  def 'returns the deployment ARN straight from the service when present'() {
    given:
    def ecs = Mock(EcsClient)
    def operation = lifecycleOperation(new EcsNativeContinueServiceDeploymentAtomicOperation(description([:])), ecs)

    expect:
    operation.resolveCurrentServiceDeployment(
      Service.builder().serviceName('service').currentServiceDeployment('arn:deployment:1').build(), 'cluster-arn') == 'arn:deployment:1'
  }

  private static EcsNativeServiceDeploymentDescription description(Map overrides) {
    new EcsNativeServiceDeploymentDescription([
      credentials: TestCredential.named('test', [:]),
      region: 'us-west-2',
      ecsClusterName: 'cluster-arn',
      serverGroupName: 'service',
      ecsNativeExpectedServiceDeploymentArn: 'arn:deployment:7'
    ] + overrides)
  }

  private lifecycleOperation(AbstractEcsNativeServiceDeploymentAtomicOperation operation, EcsClient ecs) {
    def provider = Mock(AmazonClientProvider)
    operation.amazonClientProvider = provider
    provider.getAmazonEcsV2(_, 'us-west-2') >> ecs
    ecs.describeServices(_) >> DescribeServicesResponse.builder()
      .services(Service.builder().serviceName('service').tags(EcsNativeServiceTag.tag()).build()).build()
    operation
  }

  def 'rejects a lifecycle request without the pinned ARN before an AWS write'() {
    given:
    def operation = new EcsNativeStopServiceDeploymentAtomicOperation(new EcsNativeServiceDeploymentDescription(
      credentials: TestCredential.named('test', [:]),
      region: 'us-west-2',
      ecsClusterName: 'cluster-arn',
      serverGroupName: 'service'
    ))

    when:
    operation.operate([])

    then:
    thrown(IllegalArgumentException)
    0 * _
  }
}
