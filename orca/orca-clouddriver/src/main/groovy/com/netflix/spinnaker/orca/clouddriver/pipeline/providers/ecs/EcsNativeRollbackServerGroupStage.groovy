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

package com.netflix.spinnaker.orca.clouddriver.pipeline.providers.ecs

import com.netflix.spinnaker.orca.api.pipeline.graph.StageDefinitionBuilder
import com.netflix.spinnaker.orca.api.pipeline.graph.TaskNode
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.clouddriver.tasks.MonitorKatoTask
import com.netflix.spinnaker.orca.clouddriver.tasks.providers.ecs.EcsNativeRollbackServerGroupTask
import com.netflix.spinnaker.orca.clouddriver.tasks.providers.ecs.WaitForEcsNativeServiceDeploymentTask
import com.netflix.spinnaker.orca.clouddriver.tasks.servergroup.ServerGroupCacheForceRefreshTask
import groovy.transform.CompileStatic
import javax.annotation.Nonnull
import org.springframework.stereotype.Component

/**
 * Rolls an {@code ecs-native} server group back to an earlier task-definition revision.
 *
 * <p>Distinct from the classic {@code rollbackServerGroup} stage (which composes enable/disable/
 * resize of separate versioned server groups via {@code ExplicitRollback}); that model does not fit
 * a single durable {@code ecs-native} service. Here the rollback is an in-place native
 * {@code UpdateService} to the chosen prior task definition, after which the stage waits on ECS's
 * own rollout state -- so the rollback is treated as a real deployment (it can trip the deployment
 * circuit breaker) rather than an instantaneous swap.
 */
@Component
@CompileStatic
class EcsNativeRollbackServerGroupStage implements StageDefinitionBuilder {
  public static final String PIPELINE_CONFIG_TYPE = "rollbackEcsNativeServerGroup"

  @Override
  void taskGraph(@Nonnull StageExecution stage, @Nonnull TaskNode.Builder builder) {
    builder
      .withTask("rollbackServerGroup", EcsNativeRollbackServerGroupTask)
      .withTask("monitorServerGroup", MonitorKatoTask)
      .withTask("forceCacheRefresh", ServerGroupCacheForceRefreshTask)
      .withTask(
        WaitForEcsNativeServiceDeploymentTask.TASK_NAME, WaitForEcsNativeServiceDeploymentTask)
  }
}
