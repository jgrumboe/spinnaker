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

import java.util.List;
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

  /**
   * When {@code true}, redeploys roll the existing durable service in place via a native ECS {@code
   * UpdateService} (identified by {@link #getSource()}) instead of creating a new versioned service.
   * The first deploy, or a deploy with no source, still creates the service. This is opt-in and must
   * be paired with a no-op / native deployment strategy: it must NOT be combined with a red/black
   * strategy, which would disable and destroy the service that was just updated.
   */
  boolean inPlaceUpdate;

  /**
   * Names of CloudWatch alarms ECS should watch during a deployment. When non-empty (or {@link
   * #isEnableDeploymentAlarms()} is set), ECS's deployment alarms are enabled for the service: if
   * any named alarm is in {@code ALARM} state during a deployment, ECS marks the deployment failed
   * and (when {@link #isDeploymentAlarmsRollback()} is set) automatically rolls it back. Independent
   * of the deployment circuit breaker, which only reacts to task health.
   */
  @Nullable List<String> alarmNames;

  /** Enables deployment alarms for the service. Implied when {@link #getAlarmNames()} is set. */
  boolean enableDeploymentAlarms;

  /** When {@code true}, a deployment that trips a named alarm is automatically rolled back. */
  boolean deploymentAlarmsRollback;
}
