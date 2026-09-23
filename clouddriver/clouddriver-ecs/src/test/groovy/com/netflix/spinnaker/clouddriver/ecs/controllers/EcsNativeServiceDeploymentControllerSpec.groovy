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

package com.netflix.spinnaker.clouddriver.ecs.controllers

import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider
import com.netflix.spinnaker.clouddriver.ecs.cache.client.ServiceCacheClient
import com.netflix.spinnaker.clouddriver.ecs.cache.model.Service
import com.netflix.spinnaker.clouddriver.ecs.security.NetflixECSCredentials
import com.netflix.spinnaker.credentials.CredentialsRepository
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Subject
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.Deployment
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse
import software.amazon.awssdk.services.ecs.model.Service as AmazonEcsService

class EcsNativeServiceDeploymentControllerSpec extends Specification {

  def credentialsRepository = Mock(CredentialsRepository)
  def amazonClientProvider = Mock(AmazonClientProvider)
  def serviceCacheClient = Mock(ServiceCacheClient)
  def ecs = Mock(EcsClient)

  @Subject
  def controller = new EcsNativeServiceDeploymentController(
    credentialsRepository, amazonClientProvider, serviceCacheClient)

  def 'returns 400 when the account is not an ECS account'() {
    given:
    credentialsRepository.getOne('test') >> null

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp-v001')

    then:
    response.statusCode == HttpStatus.BAD_REQUEST
  }

  def 'resolves the account case-insensitively when the exact case is not registered'() {
    given:
    // Deck may send the account upper-cased (from the moniker/pipeline context) while the ECS
    // credential is registered lower-case; getOne is case-sensitive, so we fall back to a scan.
    credentialsRepository.getOne('FGATE-PS-PRODUCTION') >> null
    credentialsRepository.getAll() >> [Mock(NetflixECSCredentials) { getName() >> 'fgate-ps-production' }]
    // The cache lookup must use the resolved (actual-case) account name.
    serviceCacheClient.getAll('fgate-ps-production', 'eu-central-1') >> []

    when:
    def response = controller.getDeploymentStatus('FGATE-PS-PRODUCTION', 'eu-central-1', 'myapp')

    then:
    // Account resolved (not a 400); 404 only because the (empty) cache has no such service.
    response.statusCode == HttpStatus.NOT_FOUND
  }

  def 'returns 404 when the service is not in the cache'() {
    given:
    credentialsRepository.getOne('test') >> Mock(NetflixECSCredentials) { getName() >> 'test' }
    serviceCacheClient.getAll('test', 'us-west-2') >> []

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp-v001')

    then:
    response.statusCode == HttpStatus.NOT_FOUND
  }

  def 'returns 404 when ECS has no PRIMARY deployment for the service'() {
    given:
    credentialsRepository.getOne('test') >> Mock(NetflixECSCredentials) { getName() >> 'test' }
    def cachedService = new Service(serviceName: 'myapp-v001', clusterArn: 'cluster-arn')
    serviceCacheClient.getAll('test', 'us-west-2') >> [cachedService]
    amazonClientProvider.getAmazonEcsV2(_, 'us-west-2') >> ecs

    def inactiveDeployment = Deployment.builder().status('INACTIVE').build()
    ecs.describeServices(_) >> DescribeServicesResponse.builder()
      .services(AmazonEcsService.builder().deployments(inactiveDeployment).build())
      .build()

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp-v001')

    then:
    response.statusCode == HttpStatus.NOT_FOUND
  }

  def 'maps the PRIMARY deployment rollout state onto the response'() {
    given:
    credentialsRepository.getOne('test') >> Mock(NetflixECSCredentials) { getName() >> 'test' }
    def cachedService = new Service(serviceName: 'myapp-v001', clusterArn: 'cluster-arn')
    serviceCacheClient.getAll('test', 'us-west-2') >> [cachedService]
    amazonClientProvider.getAmazonEcsV2(_, 'us-west-2') >> ecs

    def primaryDeployment = Deployment.builder()
      .id('ecs-svc/deployment-1')
      .status('PRIMARY')
      .rolloutState('IN_PROGRESS')
      .rolloutStateReason('ECS deployment ecs-svc/deployment-1 in progress.')
      .desiredCount(3)
      .runningCount(2)
      .pendingCount(1)
      .failedTasks(0)
      .build()
    ecs.describeServices(_) >> DescribeServicesResponse.builder()
      .services(AmazonEcsService.builder().deployments(primaryDeployment).build())
      .build()

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp-v001')

    then:
    response.statusCode == HttpStatus.OK
    with(response.body) {
      serviceName == 'myapp-v001'
      clusterArn == 'cluster-arn'
      rolloutState == 'IN_PROGRESS'
      rolloutStateReason == 'ECS deployment ecs-svc/deployment-1 in progress.'
      desiredCount == 3
      runningCount == 2
      pendingCount == 1
      failedTasks == 0
    }
  }
}
