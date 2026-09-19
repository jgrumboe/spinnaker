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

import com.netflix.spinnaker.clouddriver.ecs.TestCredential
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeUpdateServiceDescription
import software.amazon.awssdk.services.ecs.model.Service
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest
import software.amazon.awssdk.services.ecs.model.UpdateServiceResponse

class EcsNativeUpdateServiceAtomicOperationSpec extends CommonAtomicOperation {

  void 'should update the service in place with native deployment configuration'() {
    given:
    def serviceName = 'myapp-kcats-liated-v007'
    def credentials = TestCredential.named('test', [:])

    def operation = new EcsNativeUpdateServiceAtomicOperation(new EcsNativeUpdateServiceDescription(
      credentials: credentials,
      region: 'us-west-1',
      serverGroupName: serviceName,
      taskDefinition: 'task-def-arn',
      minimumHealthyPercent: 50,
      maximumPercent: 150,
      enableDeploymentCircuitBreaker: true,
      deploymentCircuitBreakerRollback: true,
      forceNewDeployment: true
    ))

    operation.amazonClientProvider = amazonClientProvider
    operation.credentialsRepository = credentialsRepository
    operation.containerInformationService = containerInformationService

    amazonClientProvider.getAmazonEcsV2(_, _) >> ecs
    containerInformationService.getClusterName(_, _, _) >> 'my-cluster'
    credentialsRepository.getOne(_) >> credentials

    when:
    operation.operate([])

    then:
    1 * ecs.updateService({ UpdateServiceRequest req ->
      req.cluster() == 'my-cluster' &&
        req.service() == serviceName &&
        req.taskDefinition() == 'task-def-arn' &&
        req.forceNewDeployment() == true &&
        req.deploymentConfiguration().minimumHealthyPercent() == 50 &&
        req.deploymentConfiguration().maximumPercent() == 150 &&
        req.deploymentConfiguration().deploymentCircuitBreaker().enable() == true &&
        req.deploymentConfiguration().deploymentCircuitBreaker().rollback() == true
    } as UpdateServiceRequest) >> UpdateServiceResponse.builder()
        .service(Service.builder().serviceName(serviceName).build())
        .build()
  }

  void 'should not send a deployment configuration when none is specified'() {
    given:
    def serviceName = 'myapp-kcats-liated-v007'
    def credentials = TestCredential.named('test', [:])

    def operation = new EcsNativeUpdateServiceAtomicOperation(new EcsNativeUpdateServiceDescription(
      credentials: credentials,
      region: 'us-west-1',
      serverGroupName: serviceName,
      taskDefinition: 'task-def-arn'
    ))

    operation.amazonClientProvider = amazonClientProvider
    operation.credentialsRepository = credentialsRepository
    operation.containerInformationService = containerInformationService

    amazonClientProvider.getAmazonEcsV2(_, _) >> ecs
    containerInformationService.getClusterName(_, _, _) >> 'my-cluster'
    credentialsRepository.getOne(_) >> credentials

    when:
    operation.operate([])

    then:
    1 * ecs.updateService({ UpdateServiceRequest req ->
      req.service() == serviceName &&
        req.taskDefinition() == 'task-def-arn' &&
        req.deploymentConfiguration() == null
    } as UpdateServiceRequest) >> UpdateServiceResponse.builder()
        .service(Service.builder().serviceName(serviceName).build())
        .build()
  }

  void 'should apply blue/green strategy and bake time'() {
    given:
    def serviceName = 'myapp-kcats-liated-v007'
    def credentials = TestCredential.named('test', [:])

    def operation = new EcsNativeUpdateServiceAtomicOperation(new EcsNativeUpdateServiceDescription(
      credentials: credentials,
      region: 'us-west-1',
      serverGroupName: serviceName,
      taskDefinition: 'task-def-arn',
      deploymentStrategy: 'BLUE_GREEN',
      bakeTimeInMinutes: 15
    ))

    operation.amazonClientProvider = amazonClientProvider
    operation.credentialsRepository = credentialsRepository
    operation.containerInformationService = containerInformationService

    amazonClientProvider.getAmazonEcsV2(_, _) >> ecs
    containerInformationService.getClusterName(_, _, _) >> 'my-cluster'
    credentialsRepository.getOne(_) >> credentials

    when:
    operation.operate([])

    then:
    1 * ecs.updateService({ UpdateServiceRequest req ->
      req.deploymentConfiguration().strategyAsString() == 'BLUE_GREEN' &&
        req.deploymentConfiguration().bakeTimeInMinutes() == 15
    } as UpdateServiceRequest) >> UpdateServiceResponse.builder()
        .service(Service.builder().serviceName(serviceName).build())
        .build()
  }

  void 'should send deployment alarms when alarm names are configured'() {
    given:
    def serviceName = 'myapp-kcats-liated-v007'
    def credentials = TestCredential.named('test', [:])

    def operation = new EcsNativeUpdateServiceAtomicOperation(new EcsNativeUpdateServiceDescription(
      credentials: credentials,
      region: 'us-west-1',
      serverGroupName: serviceName,
      taskDefinition: 'task-def-arn',
      alarmNames: ['myapp-high-error-rate'],
      deploymentAlarmsRollback: true
    ))

    operation.amazonClientProvider = amazonClientProvider
    operation.credentialsRepository = credentialsRepository
    operation.containerInformationService = containerInformationService

    amazonClientProvider.getAmazonEcsV2(_, _) >> ecs
    containerInformationService.getClusterName(_, _, _) >> 'my-cluster'
    credentialsRepository.getOne(_) >> credentials

    when:
    operation.operate([])

    then:
    1 * ecs.updateService({ UpdateServiceRequest req ->
      req.deploymentConfiguration().alarms().alarmNames() == ['myapp-high-error-rate'] &&
        req.deploymentConfiguration().alarms().enable() == true &&
        req.deploymentConfiguration().alarms().rollback() == true
    } as UpdateServiceRequest) >> UpdateServiceResponse.builder()
        .service(Service.builder().serviceName(serviceName).build())
        .build()
  }
}
