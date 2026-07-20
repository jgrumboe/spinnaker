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

import com.amazonaws.services.ecs.model.CreateServiceRequest
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeCreateServerGroupDescription
import com.netflix.spinnaker.clouddriver.ecs.names.EcsDefaultNamer
import com.netflix.spinnaker.clouddriver.ecs.names.EcsServerGroupName

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
    request.deploymentConfiguration.minimumHealthyPercent == 50
    request.deploymentConfiguration.maximumPercent == 150
    request.deploymentConfiguration.deploymentCircuitBreaker.enable == true
    request.deploymentConfiguration.deploymentCircuitBreaker.rollback == true
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
    request.deploymentConfiguration.minimumHealthyPercent == 100
    request.deploymentConfiguration.maximumPercent == 200
    request.deploymentConfiguration.deploymentCircuitBreaker.enable == false
    request.deploymentConfiguration.deploymentCircuitBreaker.rollback == false
  }
}
