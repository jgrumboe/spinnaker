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

import com.amazonaws.services.ecs.AmazonECS
import com.amazonaws.services.ecs.model.DescribeServicesResult
import com.amazonaws.services.ecs.model.Deployment
import com.amazonaws.services.ecs.model.Service as AmazonEcsService
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAmazonCredentials
import com.netflix.spinnaker.clouddriver.ecs.cache.client.ServiceCacheClient
import com.netflix.spinnaker.clouddriver.ecs.cache.model.Service
import com.netflix.spinnaker.clouddriver.ecs.security.NetflixECSCredentials
import com.netflix.spinnaker.credentials.CredentialsRepository
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Subject

class EcsNativeServiceDeploymentControllerSpec extends Specification {

  def credentialsRepository = Mock(CredentialsRepository)
  def amazonClientProvider = Mock(AmazonClientProvider)
  def serviceCacheClient = Mock(ServiceCacheClient)
  def ecs = Mock(AmazonECS)

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

  def 'returns 404 when the service is not in the cache'() {
    given:
    credentialsRepository.getOne('test') >> Mock(NetflixAmazonCredentials)
    serviceCacheClient.getAll('test', 'us-west-2') >> []

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp-v001')

    then:
    response.statusCode == HttpStatus.NOT_FOUND
  }

  def 'returns 404 when ECS has no PRIMARY deployment for the service'() {
    given:
    credentialsRepository.getOne('test') >> Mock(NetflixAmazonCredentials)
    def cachedService = new Service(serviceName: 'myapp-v001', clusterArn: 'cluster-arn')
    serviceCacheClient.getAll('test', 'us-west-2') >> [cachedService]
    amazonClientProvider.getAmazonEcs(_, 'us-west-2', true) >> ecs

    def inactiveDeployment = new Deployment().withStatus('INACTIVE')
    ecs.describeServices(_) >> new DescribeServicesResult().withServices(
      new AmazonEcsService().withDeployments(inactiveDeployment))

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp-v001')

    then:
    response.statusCode == HttpStatus.NOT_FOUND
  }

  def 'maps the PRIMARY deployment rollout state onto the response'() {
    given:
    credentialsRepository.getOne('test') >> Mock(NetflixAmazonCredentials)
    def cachedService = new Service(serviceName: 'myapp-v001', clusterArn: 'cluster-arn')
    serviceCacheClient.getAll('test', 'us-west-2') >> [cachedService]
    amazonClientProvider.getAmazonEcs(_, 'us-west-2', true) >> ecs

    def primaryDeployment = new Deployment()
      .withId('ecs-svc/deployment-1')
      .withStatus('PRIMARY')
      .withRolloutState('IN_PROGRESS')
      .withRolloutStateReason('ECS deployment ecs-svc/deployment-1 in progress.')
      .withDesiredCount(3)
      .withRunningCount(2)
      .withPendingCount(1)
      .withFailedTasks(0)
    ecs.describeServices(_) >> new DescribeServicesResult().withServices(
      new AmazonEcsService().withDeployments(primaryDeployment))

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
