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
  def "maps ECS rolloutState '#rolloutState' to execution status '#expectedStatus'"() {
    given:
    def stage = stageWithContext([account: 'test', region: 'us-west-2', serverGroupName: 'myapp-v001'])
    def status = new EcsServiceDeploymentStatus(rolloutState: rolloutState, rolloutStateReason: 'reason')

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus('test', 'us-west-2', 'myapp-v001') >> Calls.response(status)
    result.status == expectedStatus
    result.context.ecsNativeDeploymentStatus == status

    where:
    rolloutState  | expectedStatus
    'IN_PROGRESS' | ExecutionStatus.RUNNING
    'COMPLETED'   | ExecutionStatus.SUCCEEDED
    'FAILED'      | ExecutionStatus.TERMINAL
  }

  def "resolves region and serverGroupName from a preceding deploy stage's output when not set directly"() {
    given:
    def stage = stageWithContext([
      account                  : 'test',
      'deploy.server.groups'   : ['us-west-2': ['myapp-v002']]
    ])
    def status = new EcsServiceDeploymentStatus(rolloutState: 'IN_PROGRESS')

    when:
    def result = task.execute(stage)

    then:
    1 * ecsNativeService.getServiceDeploymentStatus('test', 'us-west-2', 'myapp-v002') >> Calls.response(status)
    result.status == ExecutionStatus.RUNNING
  }

  def "throws when account, region, or serverGroupName cannot be resolved"() {
    given:
    def stage = stageWithContext([account: 'test'])

    when:
    task.execute(stage)

    then:
    thrown(IllegalArgumentException)
  }
}
