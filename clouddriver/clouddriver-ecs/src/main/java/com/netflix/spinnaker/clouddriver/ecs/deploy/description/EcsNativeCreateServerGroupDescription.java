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

package com.netflix.spinnaker.clouddriver.ecs.deploy.description;

import javax.annotation.Nullable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Deploy description for the opt-in {@code ecs-native} provider. It extends the existing ECS
 * create-server-group contract and adds the native ECS deployment-configuration knobs that the
 * original provider hard-codes: the minimum-healthy / maximum-percent bounds and whether the
 * deployment circuit breaker should automatically roll back on failure.
 *
 * <p>All fields are optional. When left unset the behavior matches the original {@code ecs}
 * provider (100/200 percent, circuit-breaker rollback disabled), so an {@code ecs-native} deploy
 * with no extra configuration is a no-op change relative to today.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EcsNativeCreateServerGroupDescription extends CreateServerGroupDescription {

  /** Lower bound (percent) of healthy tasks ECS keeps running during a deployment. */
  @Nullable Integer minimumHealthyPercent;

  /** Upper bound (percent) of tasks ECS may run during a deployment. */
  @Nullable Integer maximumPercent;

  /**
   * When {@code true} (and {@link #isEnableDeploymentCircuitBreaker()} is enabled), a failed
   * deployment is automatically rolled back by ECS to the last completed deployment.
   */
  boolean deploymentCircuitBreakerRollback;
}
