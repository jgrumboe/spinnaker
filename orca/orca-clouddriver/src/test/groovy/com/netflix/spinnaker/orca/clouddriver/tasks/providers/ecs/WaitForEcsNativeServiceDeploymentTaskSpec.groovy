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

  private StageExecutionImpl stageWithContext(Map context, String deploymentArn = null) {
    def pipeline = PipelineExecutionImpl.newPipeline('orca')
    if (deploymentArn) {
      def deploy = new StageExecutionImpl(pipeline, 'createServerGroup', 'deploy', [refId: 'deploy-ref'])
      deploy.refId = 'deploy-ref'
      deploy.outputs.ecsNativeExpectedServiceDeploymentArn = deploymentArn
      pipeline.stages << deploy
      context.requisiteStageRefIds = ['deploy-ref']
    }
    return new StageExecutionImpl(pipeline, 'test', 'test', context)
  }

  @Unroll
  def "maps ECS service-deployment status '#deploymentStatus' to execution status '#expectedStatus'"() {
    given:
    def stage = stageWithContext([
      account: 'test',
      region: 'us-west-2',
      serverGroupName: 'myapp',
      ecsNativeExpectedServiceDeploymentArn: 'service-deployment-1'
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
      'test', 'us-west-2', 'myapp', 'service-deployment-1') >> Calls.response(status)
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

  def 'resolves ARN from referenced deploy stage output before direct fallback'() {
    given:
    def stage = stageWithContext([
      account: 'test', region: 'us-west-2', serverGroupName: 'myapp',
      ecsNativeExpectedServiceDeploymentArn: 'legacy-arn'
    ], 'service-deployment-1')
    def status = new EcsServiceDeploymentStatus(
      serviceDeploymentArn: 'service-deployment-1', status: 'IN_PROGRESS', lifecycleStage: 'SCALE_UP')

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'us-west-2', 'myapp', 'service-deployment-1') >> Calls.response(status)
    result.status == ExecutionStatus.RUNNING
  }

  private EcsServiceDeploymentStatus statusWithHook(String deploymentStatus, String hookStatus) {
    new EcsServiceDeploymentStatus(
      serviceDeploymentArn: 'service-deployment-1',
      status: deploymentStatus,
      lifecycleStage: 'POST_TEST_TRAFFIC_SHIFT',
      lifecycleHookDetails: [new EcsServiceDeploymentStatus.LifecycleHook(hookId: 'hook-1', status: hookStatus)])
  }

  @Unroll
  def 'gate wait with a #hookStatus PAUSE hook while #deploymentStatus maps to #expected'() {
    given:
    def stage = stageWithContext([
      account: 'test', region: 'us-west-2', serverGroupName: 'myapp',
      ecsNativeExpectedServiceDeploymentArn: 'service-deployment-1',
      ecsNativeWaitForLifecycleGate: true
    ])

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'us-west-2', 'myapp', 'service-deployment-1') >> Calls.response(statusWithHook(deploymentStatus, hookStatus))
    result.status == expected

    where:
    deploymentStatus | hookStatus        || expected
    'IN_PROGRESS'    | 'AWAITING_ACTION' || ExecutionStatus.SUCCEEDED
    'IN_PROGRESS'    | 'IN_PROGRESS'     || ExecutionStatus.RUNNING
    'IN_PROGRESS'    | 'SUCCEEDED'       || ExecutionStatus.RUNNING
  }

  def 'a hook awaiting action is ignored when the stage is not waiting for the gate'() {
    given:
    def stage = stageWithContext([
      account: 'test', region: 'us-west-2', serverGroupName: 'myapp',
      ecsNativeExpectedServiceDeploymentArn: 'service-deployment-1'
    ])

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'us-west-2', 'myapp', 'service-deployment-1') >> Calls.response(statusWithHook('IN_PROGRESS', 'AWAITING_ACTION'))
    result.status == ExecutionStatus.RUNNING
  }

  @Unroll
  def 'rollback outcome with ecsNativeAcceptRollback=#accept is #expected'() {
    given:
    def stage = stageWithContext([
      account: 'test', region: 'us-west-2', serverGroupName: 'myapp',
      ecsNativeExpectedServiceDeploymentArn: 'service-deployment-1',
      ecsNativeAcceptRollback: accept
    ])

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'us-west-2', 'myapp', 'service-deployment-1') >> Calls.response(
        new EcsServiceDeploymentStatus(serviceDeploymentArn: 'service-deployment-1', status: 'ROLLBACK_SUCCESSFUL'))
    result.status == expected

    where:
    accept || expected
    true   || ExecutionStatus.SUCCEEDED
    false  || ExecutionStatus.TERMINAL
  }

  def 'ROLLBACK_FAILED stays terminal even when a rollback was requested'() {
    given:
    def stage = stageWithContext([
      account: 'test', region: 'us-west-2', serverGroupName: 'myapp',
      ecsNativeExpectedServiceDeploymentArn: 'service-deployment-1',
      ecsNativeAcceptRollback: true
    ])

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'us-west-2', 'myapp', 'service-deployment-1') >> Calls.response(
        new EcsServiceDeploymentStatus(serviceDeploymentArn: 'service-deployment-1', status: 'ROLLBACK_FAILED'))
    result.status == ExecutionStatus.TERMINAL
  }

  def 'stop wait succeeds when the exact deployment reaches STOPPED'() {
    given:
    def stage = stageWithContext([
      account: 'test', region: 'us-west-2', serverGroupName: 'myapp',
      ecsNativeExpectedServiceDeploymentArn: 'service-deployment-1',
      ecsNativeWaitForStopped: true
    ])
    def status = new EcsServiceDeploymentStatus(
      serviceDeploymentArn: 'service-deployment-1', status: 'STOPPED')

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'us-west-2', 'myapp', 'service-deployment-1') >> Calls.response(status)
    result.status == ExecutionStatus.SUCCEEDED
  }

  def 'completed different deployment identity is terminal, never success'() {
    given:
    def stage = stageWithContext([
      account: 'test',
      region: 'us-west-2',
      serverGroupName: 'myapp',
      ecsNativeExpectedServiceDeploymentArn: 'deployment-1'
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
      'test', 'us-west-2', 'myapp', 'deployment-1') >>> [Calls.response(inProgress), Calls.response(rollbackDeployment)]
    first.status == ExecutionStatus.RUNNING
    second.status == ExecutionStatus.TERMINAL
  }

  def "resolves region and serverGroupName from a preceding deploy stage's output when not set directly"() {
    given:
    def stage = stageWithContext([
      account: 'test',
      ecsNativeExpectedServiceDeploymentArn: 'service-deployment-1',
      'deploy.server.groups': ['us-west-2': ['myapp']]
    ])
    def status = new EcsServiceDeploymentStatus(
      serviceDeploymentArn: 'service-deployment-1',
      status: 'IN_PROGRESS')

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'us-west-2', 'myapp', 'service-deployment-1') >> Calls.response(status)
    result.status == ExecutionStatus.RUNNING
  }

  def "falls back to 'credentials' for the account"() {
    given:
    def stage = stageWithContext([
      credentials: 'test',
      region: 'eu-central-1',
      serverGroupName: 'myapp',
      ecsNativeExpectedServiceDeploymentArn: 'service-deployment-1'
    ])
    def status = new EcsServiceDeploymentStatus(
      serviceDeploymentArn: 'service-deployment-1',
      status: 'SUCCESSFUL')

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus(
      'test', 'eu-central-1', 'myapp', 'service-deployment-1') >> Calls.response(status)
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
