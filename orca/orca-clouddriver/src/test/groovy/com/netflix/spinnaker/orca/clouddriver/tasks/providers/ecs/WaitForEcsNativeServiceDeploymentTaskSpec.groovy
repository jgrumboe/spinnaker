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

package com.netflix.spinnaker.orca.clouddriver.tasks.providers.ecs

import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus
import com.netflix.spinnaker.orca.clouddriver.EcsNativeService
import com.netflix.spinnaker.orca.clouddriver.model.EcsServiceDeploymentStatus
import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl
import com.netflix.spinnaker.orca.pipeline.model.StageExecutionImpl
import retrofit2.mock.Calls
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class WaitForEcsNativeServiceDeploymentTaskSpec extends Specification {

  def ecsNativeService = Mock(EcsNativeService)

  @Subject
  def task = new WaitForEcsNativeServiceDeploymentTask(ecsNativeService: ecsNativeService)

  private StageExecutionImpl stageWithContext(Map context) {
    def pipeline = PipelineExecutionImpl.newPipeline('orca')
    return new StageExecutionImpl(pipeline, 'test', 'test', context)
  }

  @Unroll
  def "maps ECS service-deployment status '#deploymentStatus' to execution status '#expectedStatus'"() {
    given:
    def stage = stageWithContext([
      account: 'test',
      region: 'us-west-2',
      serverGroupName: 'myapp',
      ecsNativeExpectedTaskDefinition: 'task-def-arn'
    ])
    def status = new EcsServiceDeploymentStatus(
      serviceDeploymentArn: 'service-deployment-1',
      status: deploymentStatus,
      targetTaskDefinition: 'task-def-arn',
      statusReason: 'reason'
    )

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'us-west-2', 'myapp', 'task-def-arn') >> Calls.response(status)
    result.status == expectedStatus
    result.context.ecsNativeDeploymentStatus == status

    where:
    deploymentStatus       | expectedStatus
    'PENDING'              | ExecutionStatus.RUNNING
    'IN_PROGRESS'          | ExecutionStatus.RUNNING
    'ROLLBACK_IN_PROGRESS' | ExecutionStatus.RUNNING
    'SUCCESSFUL'           | ExecutionStatus.SUCCEEDED
    'ROLLBACK_SUCCESSFUL'  | ExecutionStatus.TERMINAL
    'ROLLBACK_FAILED'      | ExecutionStatus.TERMINAL
    'STOPPED'              | ExecutionStatus.TERMINAL
  }

  def 'completed different deployment identity is terminal, never success'() {
    given:
    def stage = stageWithContext([
      account: 'test',
      region: 'us-west-2',
      serverGroupName: 'myapp',
      ecsNativeExpectedTaskDefinition: 'task-def-1'
    ])
    def inProgress = new EcsServiceDeploymentStatus(
      serviceDeploymentArn: 'deployment-1',
      status: 'IN_PROGRESS',
      targetTaskDefinition: 'task-def-1'
    )
    def rollbackDeployment = new EcsServiceDeploymentStatus(
      serviceDeploymentArn: 'deployment-2',
      status: 'SUCCESSFUL',
      targetTaskDefinition: 'task-def-previous'
    )

    when:
    def first = task.execute(stage)
    def second = task.execute(stage)

    then:
    2 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'us-west-2', 'myapp', 'task-def-1') >>> [Calls.response(inProgress), Calls.response(rollbackDeployment)]
    first.status == ExecutionStatus.RUNNING
    second.status == ExecutionStatus.TERMINAL
  }

  def "resolves region and serverGroupName from a preceding deploy stage's output when not set directly"() {
    given:
    def stage = stageWithContext([
      account: 'test',
      ecsNativeExpectedTaskDefinition: 'task-def-arn',
      'deploy.server.groups': ['us-west-2': ['myapp']]
    ])
    def status = new EcsServiceDeploymentStatus(status: 'IN_PROGRESS')

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'us-west-2', 'myapp', 'task-def-arn') >> Calls.response(status)
    result.status == ExecutionStatus.RUNNING
  }

  def "falls back to 'credentials' for the account"() {
    given:
    def stage = stageWithContext([
      credentials: 'test',
      region: 'eu-central-1',
      serverGroupName: 'myapp',
      ecsNativeExpectedTaskDefinition: 'task-def-arn'
    ])
    def status = new EcsServiceDeploymentStatus(status: 'SUCCESSFUL')

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'eu-central-1', 'myapp', 'task-def-arn') >> Calls.response(status)
    result.status == ExecutionStatus.SUCCEEDED
  }

  def 'throws when deployment identity cannot be resolved'() {
    given:
    def stage = stageWithContext([account: 'test', region: 'us-west-2', serverGroupName: 'myapp'])

    when:
    task.execute(stage)

    then:
    thrown(IllegalArgumentException)
  }
}
