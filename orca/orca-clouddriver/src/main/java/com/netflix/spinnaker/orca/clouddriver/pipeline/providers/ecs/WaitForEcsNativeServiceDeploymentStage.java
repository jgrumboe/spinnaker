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

package com.netflix.spinnaker.orca.clouddriver.pipeline.providers.ecs;

import com.netflix.spinnaker.orca.api.pipeline.graph.StageDefinitionBuilder;
import com.netflix.spinnaker.orca.api.pipeline.graph.TaskNode;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.clouddriver.tasks.providers.ecs.WaitForEcsNativeServiceDeploymentTask;
import javax.annotation.Nonnull;
import org.springframework.stereotype.Component;

/**
 * A standalone stage that waits on ECS's own native deployment/rollout state (see {@link
 * WaitForEcsNativeServiceDeploymentTask}). Add it after an {@code ecs-native} deploy/clone stage in
 * a pipeline to gate on ECS's rollout completing (or being rolled back by the deployment circuit
 * breaker) instead of only on instance health.
 *
 * <p>Deliberately not wired into the shared deploy stage's task graph, so no other provider or
 * existing pipeline is affected; users add it explicitly, the same way {@code ecs-native} itself is
 * currently selected by setting {@code cloudProvider} directly in pipeline JSON until deck support
 * lands.
 */
@Component
public class WaitForEcsNativeServiceDeploymentStage implements StageDefinitionBuilder {
  public static final String STAGE_TYPE = "waitForEcsNativeServiceDeployment";

  @Override
  public void taskGraph(@Nonnull StageExecution stage, @Nonnull TaskNode.Builder builder) {
    builder.withTask("waitForEcsNativeServiceDeployment", WaitForEcsNativeServiceDeploymentTask.class);
  }
}
