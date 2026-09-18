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
 * Description for an in-place update of an existing ECS service under the opt-in {@code ecs-native}
 * provider.
 *
 * <p>Unlike the original provider, which creates a new versioned service per deploy, this drives a
 * native ECS {@code UpdateService} against the durable service named by {@link
 * #getServerGroupName()}: optionally rolling it to a new task definition, applying the native
 * deployment configuration (rolling bounds + circuit-breaker rollback), and/or forcing a fresh
 * rolling deployment.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EcsNativeUpdateServiceDescription extends ModifyServiceDescription {

  /** ECS cluster the service runs in. Resolved from the service name when omitted. */
  @Nullable String ecsClusterName;

  /** Task definition (ARN or {@code family:revision}) to roll the service to. Optional. */
  @Nullable String taskDefinition;

  /** Lower bound (percent) of healthy tasks ECS keeps running during the deployment. */
  @Nullable Integer minimumHealthyPercent;

  /** Upper bound (percent) of tasks ECS may run during the deployment. */
  @Nullable Integer maximumPercent;

  /** Enable the ECS deployment circuit breaker for this deployment. */
  boolean enableDeploymentCircuitBreaker;

  /** When the circuit breaker is enabled, automatically roll back a failed deployment. */
  boolean deploymentCircuitBreakerRollback;

  /** Force a new deployment even when the task definition is unchanged. */
  boolean forceNewDeployment;
}
