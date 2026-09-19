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
import com.netflix.spinnaker.clouddriver.ecs.names.EcsDefaultNamer
import com.netflix.spinnaker.clouddriver.ecs.names.EcsServerGroupName
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest
import software.amazon.awssdk.services.ecs.model.Service
import software.amazon.awssdk.services.ecs.model.TaskDefinition
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest
import software.amazon.awssdk.services.ecs.model.UpdateServiceResponse

class EcsNativeCreateServerGroupAtomicOperationSpec extends CommonAtomicOperation {

  def 'should apply configurable deployment configuration and circuit-breaker rollback'() {
    given:
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null
    description.getMinimumHealthyPercent() >> 50
    description.getMaximumPercent() >> 150
    description.isEnableDeploymentCircuitBreaker() >> true
    description.isDeploymentCircuitBreakerRollback() >> true

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    CreateServiceRequest request = operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), false)

    then:
    request.deploymentConfiguration().minimumHealthyPercent() == 50
    request.deploymentConfiguration().maximumPercent() == 150
    request.deploymentConfiguration().deploymentCircuitBreaker().enable() == true
    request.deploymentConfiguration().deploymentCircuitBreaker().rollback() == true
  }

  def 'should preserve the original ecs defaults when native fields are unset'() {
    given:
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null
    description.getMinimumHealthyPercent() >> null
    description.getMaximumPercent() >> null
    description.isEnableDeploymentCircuitBreaker() >> false
    description.isDeploymentCircuitBreakerRollback() >> false

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    CreateServiceRequest request = operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), false)

    then:
    request.deploymentConfiguration().minimumHealthyPercent() == 100
    request.deploymentConfiguration().maximumPercent() == 200
    request.deploymentConfiguration().deploymentCircuitBreaker().enable() == false
    request.deploymentConfiguration().deploymentCircuitBreaker().rollback() == false
  }

  def 'should send deployment alarms when alarm names are configured'() {
    given:
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null
    description.getAlarmNames() >> ['myapp-high-error-rate']
    description.isDeploymentAlarmsRollback() >> true

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    CreateServiceRequest request = operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), false)

    then:
    request.deploymentConfiguration().alarms().alarmNames() == ['myapp-high-error-rate']
    request.deploymentConfiguration().alarms().enable() == true
    request.deploymentConfiguration().alarms().rollback() == true
  }

  def 'should not send deployment alarms when none are configured'() {
    given:
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    CreateServiceRequest request = operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), false)

    then:
    request.deploymentConfiguration().alarms() == null
  }

  def 'resolveExistingServiceName returns the source service when present, otherwise null'() {
    given:
    def withSource = new EcsNativeCreateServerGroupDescription(inPlaceUpdate: true)
    withSource.setSource(new CreateServerGroupDescription.Source(asgName: 'myapp-stack-v003'))
    def withoutSource = new EcsNativeCreateServerGroupDescription(inPlaceUpdate: true)

    expect:
    new EcsNativeCreateServerGroupAtomicOperation(withSource).resolveExistingServiceName() == 'myapp-stack-v003'
    new EcsNativeCreateServerGroupAtomicOperation(withoutSource).resolveExistingServiceName() == null
  }

  def 'should roll the existing service in place when inPlaceUpdate is set and a source exists'() {
    given:
    def serviceName = 'mygreatapp-stack1-details2-v011'
    def description = new EcsNativeCreateServerGroupDescription(
        inPlaceUpdate: true,
        ecsClusterName: 'my-cluster',
        minimumHealthyPercent: 50,
        maximumPercent: 150,
        enableDeploymentCircuitBreaker: true,
        deploymentCircuitBreakerRollback: true)
    description.setSource(new CreateServerGroupDescription.Source(asgName: serviceName, region: 'us-west-1'))

    def operation = Spy(EcsNativeCreateServerGroupAtomicOperation, constructorArgs: [description])
    operation.getAmazonEcsClient() >> ecs
    operation.getCredentials() >> Mock(AmazonCredentials)
    operation.resolveTaskRoleArn(_) >> 'arn:aws:iam::123456789012:role/ecsRole'
    operation.registerTaskDefinition(ecs, _, _) >> TaskDefinition.builder().taskDefinitionArn('new-task-def-arn').build()

    when:
    def result = operation.operate([])

    then:
    1 * ecs.updateService({ UpdateServiceRequest req ->
      req.cluster() == 'my-cluster' &&
          req.service() == serviceName &&
          req.taskDefinition() == 'new-task-def-arn' &&
          req.forceNewDeployment() == true &&
          req.deploymentConfiguration().minimumHealthyPercent() == 50 &&
          req.deploymentConfiguration().maximumPercent() == 150 &&
          req.deploymentConfiguration().deploymentCircuitBreaker().enable() == true &&
          req.deploymentConfiguration().deploymentCircuitBreaker().rollback() == true
    } as UpdateServiceRequest) >> UpdateServiceResponse.builder()
        .service(Service.builder().serviceName(serviceName).build())
        .build()
    0 * ecs.createService(_)
    result.serverGroupNameByRegion == ['us-west-1': serviceName]
  }

  def 'getRegion resolves from the source region during in-place update, without touching availabilityZones'() {
    given:
    def description = new EcsNativeCreateServerGroupDescription(inPlaceUpdate: true)
    description.setSource(new CreateServerGroupDescription.Source(asgName: 'myapp-v001', region: 'eu-west-1'))
    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    expect:
    operation.getRegion() == 'eu-west-1'
  }
}
