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
import com.netflix.spinnaker.clouddriver.test.TestCredential
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.ContinueServiceDeploymentRequest
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse
import software.amazon.awssdk.services.ecs.model.Service
import software.amazon.awssdk.services.ecs.model.StopServiceDeploymentRequest
import spock.lang.Specification

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
      request.serviceDeploymentArn() == 'arn:deployment:7'
    } as StopServiceDeploymentRequest)
  }

  def 'continues only the exact requested service deployment ARN'() {
    given:
    def credentials = TestCredential.named('test', [:])
    def ecs = Mock(EcsClient)
    def provider = Mock(AmazonClientProvider)
    def operation = new EcsNativeContinueServiceDeploymentAtomicOperation(new EcsNativeServiceDeploymentDescription(
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
    1 * ecs.continueServiceDeployment({ ContinueServiceDeploymentRequest request ->
      request.serviceDeploymentArn() == 'arn:deployment:7'
    } as ContinueServiceDeploymentRequest)
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
