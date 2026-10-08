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

import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.clouddriver.tasks.servergroup.AbstractServerGroupTask
import org.springframework.stereotype.Component

/** Explicitly stops the exact native ECS service deployment supplied by the preceding deploy stage. */
@Component
class EcsNativeStopServiceDeploymentTask extends AbstractServerGroupTask {
  static final String OPERATION = 'stopServiceDeployment'
  static final String ARN = 'ecsNativeExpectedServiceDeploymentArn'
  static final String WAIT_FOR_STOPPED = 'ecsNativeWaitForStopped'

  @Override
  String getServerGroupAction() { OPERATION }

  @Override
  Map<String, Object> getAdditionalContext(StageExecution stage, Map operation) {
    [(WAIT_FOR_STOPPED): true]
  }

  Map convert(StageExecution stage) {
    Map operation = super.convert(stage)
    String expectedArn = WaitForEcsNativeServiceDeploymentTask.resolveExpectedServiceDeploymentArn(stage)
    if (expectedArn) {
      operation[ARN] = expectedArn
    }
    if (!(operation[ARN] instanceof String) || !operation[ARN].trim()) {
      throw new IllegalArgumentException("${OPERATION} requires ${ARN} or a preceding deployment stage reference")
    }
    operation
  }
}
