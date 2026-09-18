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

package com.netflix.spinnaker.clouddriver.ecs.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A snapshot of ECS's own native deployment/rollout state for one service, as reported by {@code
 * DescribeServices} (the {@code deployments} list on the service, specifically the {@code PRIMARY}
 * entry). This is the same rollout tracking ECS performs for every service regardless of which
 * Spinnaker provider created it; {@code ecs-native} is the first provider in this codebase to
 * surface it to Orca so a stage can wait on it instead of only on instance health.
 *
 * <p>{@code rolloutState} is one of ECS's own values: {@code IN_PROGRESS}, {@code COMPLETED}, or
 * {@code FAILED} (the last of which includes deployments that were automatically rolled back by the
 * deployment circuit breaker).
 */
@Data
@NoArgsConstructor
public class EcsServiceDeploymentStatus {
  String serviceName;
  String clusterArn;
  String deploymentId;
  String rolloutState;
  String rolloutStateReason;
  String status;
  Integer desiredCount;
  Integer runningCount;
  Integer pendingCount;
  Integer failedTasks;
  Long createdAt;
  Long updatedAt;
}
