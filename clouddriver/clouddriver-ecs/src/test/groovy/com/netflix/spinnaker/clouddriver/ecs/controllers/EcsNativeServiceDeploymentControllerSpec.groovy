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
import java.time.Instant
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Subject
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.DescribeServiceDeploymentsResponse
import software.amazon.awssdk.services.ecs.model.DescribeServiceRevisionsResponse
import software.amazon.awssdk.services.ecs.model.ListServiceDeploymentsResponse
import software.amazon.awssdk.services.ecs.model.ServiceDeployment
import software.amazon.awssdk.services.ecs.model.ServiceDeploymentBrief
import software.amazon.awssdk.services.ecs.model.ServiceRevision
import software.amazon.awssdk.services.ecs.model.ServiceRevisionSummary

class EcsNativeServiceDeploymentControllerSpec extends Specification {

  def credentialsRepository = Mock(CredentialsRepository)
  def amazonClientProvider = Mock(AmazonClientProvider)
  def serviceCacheClient = Mock(ServiceCacheClient)
  def ecs = Mock(EcsClient)

  @Subject
  def controller = new EcsNativeServiceDeploymentController(
    credentialsRepository, amazonClientProvider, serviceCacheClient)

  def 'returns 400 when deployment identity is missing'() {
    given:
    credentialsRepository.getOne('test') >> Mock(NetflixECSCredentials) { getName() >> 'test' }

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp', null)

    then:
    response.statusCode == HttpStatus.BAD_REQUEST
  }

  def 'returns 400 when the account is not an ECS account'() {
    given:
    credentialsRepository.getOne('test') >> null

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp', 'task-def-arn')

    then:
    response.statusCode == HttpStatus.BAD_REQUEST
  }

  def 'resolves the account case-insensitively when the exact case is not registered'() {
    given:
    credentialsRepository.getOne('FGATE-PS-PRODUCTION') >> null
    credentialsRepository.getAll() >> [Mock(NetflixECSCredentials) { getName() >> 'fgate-ps-production' }]
    serviceCacheClient.getAll('fgate-ps-production', 'eu-central-1') >> []

    when:
    def response = controller.getDeploymentStatus(
      'FGATE-PS-PRODUCTION', 'eu-central-1', 'myapp', 'task-def-arn')

    then:
    response.statusCode == HttpStatus.NOT_FOUND
  }

  def 'returns 404 when the service is not in the cache'() {
    given:
    credentialsRepository.getOne('test') >> Mock(NetflixECSCredentials) { getName() >> 'test' }
    serviceCacheClient.getAll('test', 'us-west-2') >> []

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp', 'task-def-arn')

    then:
    response.statusCode == HttpStatus.NOT_FOUND
  }

  def 'returns 404 when ECS has no service deployments for the service'() {
    given:
    credentialsRepository.getOne('test') >> Mock(NetflixECSCredentials) { getName() >> 'test' }
    serviceCacheClient.getAll('test', 'us-west-2') >> [new Service(serviceName: 'myapp', clusterArn: 'cluster-arn')]
    amazonClientProvider.getAmazonEcsV2(_, 'us-west-2') >> ecs
    ecs.listServiceDeployments(_) >> ListServiceDeploymentsResponse.builder().build()

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp', 'task-def-arn')

    then:
    response.statusCode == HttpStatus.NOT_FOUND
  }

  def 'maps the matching service deployment lifecycle state onto the response'() {
    given:
    credentialsRepository.getOne('test') >> Mock(NetflixECSCredentials) { getName() >> 'test' }
    serviceCacheClient.getAll('test', 'us-west-2') >> [new Service(serviceName: 'myapp', clusterArn: 'cluster-arn')]
    amazonClientProvider.getAmazonEcsV2(_, 'us-west-2') >> ecs

    def deploymentArn = 'arn:aws:ecs:us-west-2:123:service-deployment/myapp/1'
    def revisionArn = 'arn:aws:ecs:us-west-2:123:service-revision/myapp/2'
    def revision = ServiceRevision.builder()
      .serviceRevisionArn(revisionArn)
      .taskDefinition('task-def-arn')
      .build()
    def revisionSummary = ServiceRevisionSummary.builder().arn(revisionArn).build()
    def deployment = ServiceDeployment.builder()
      .serviceDeploymentArn(deploymentArn)
      .clusterArn('cluster-arn')
      .status('IN_PROGRESS')
      .statusReason('ECS deployment is in progress.')
      .lifecycleStage('SCALE_UP')
      .targetServiceRevision(revisionSummary)
      .createdAt(Instant.parse('2026-09-24T10:00:00Z'))
      .updatedAt(Instant.parse('2026-09-24T10:01:00Z'))
      .build()
    ecs.listServiceDeployments(_) >> ListServiceDeploymentsResponse.builder()
      .serviceDeployments(ServiceDeploymentBrief.builder()
        .serviceDeploymentArn(deploymentArn)
        .targetServiceRevisionArn(revisionArn)
        .build())
      .build()
    ecs.describeServiceDeployments(_) >> DescribeServiceDeploymentsResponse.builder()
      .serviceDeployments(deployment)
      .build()
    ecs.describeServiceRevisions(_) >> DescribeServiceRevisionsResponse.builder()
      .serviceRevisions(revision)
      .build()

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp', 'task-def-arn')

    then:
    response.statusCode == HttpStatus.OK
    with(response.body) {
      serviceName == 'myapp'
      clusterArn == 'cluster-arn'
      serviceDeploymentArn == deploymentArn
      targetServiceRevisionArn == 'arn:aws:ecs:us-west-2:123:service-revision/myapp/2'
      targetTaskDefinition == 'task-def-arn'
      status == 'IN_PROGRESS'
      lifecycleStage == 'SCALE_UP'
      rolloutState == 'IN_PROGRESS'
      statusReason == 'ECS deployment is in progress.'
    }
  }

  def 'does not return a rollback deployment for a different task definition'() {
    given:
    credentialsRepository.getOne('test') >> Mock(NetflixECSCredentials) { getName() >> 'test' }
    serviceCacheClient.getAll('test', 'us-west-2') >> [new Service(serviceName: 'myapp', clusterArn: 'cluster-arn')]
    amazonClientProvider.getAmazonEcsV2(_, 'us-west-2') >> ecs
    def deploymentArn = 'arn:aws:ecs:us-west-2:123:service-deployment/myapp/rollback'
    def revisionArn = 'arn:aws:ecs:us-west-2:123:service-revision/myapp/rollback'
    ecs.listServiceDeployments(_) >> ListServiceDeploymentsResponse.builder()
      .serviceDeployments(ServiceDeploymentBrief.builder()
        .serviceDeploymentArn(deploymentArn)
        .targetServiceRevisionArn(revisionArn)
        .build())
      .build()
    def oldRevision = ServiceRevision.builder()
      .serviceRevisionArn(revisionArn)
      .taskDefinition('old-task-def')
      .build()
    def oldRevisionSummary = ServiceRevisionSummary.builder().arn(revisionArn).build()
    ecs.describeServiceDeployments(_) >> DescribeServiceDeploymentsResponse.builder()
      .serviceDeployments(ServiceDeployment.builder()
        .serviceDeploymentArn(deploymentArn)
        .status('SUCCESSFUL')
        .targetServiceRevision(oldRevisionSummary)
        .build())
      .build()
    ecs.describeServiceRevisions(_) >> DescribeServiceRevisionsResponse.builder()
      .serviceRevisions(oldRevision)
      .build()

    when:
    def response = controller.getDeploymentStatus('test', 'us-west-2', 'myapp', 'new-task-def')

    then:
    response.statusCode == HttpStatus.NOT_FOUND
  }
}
