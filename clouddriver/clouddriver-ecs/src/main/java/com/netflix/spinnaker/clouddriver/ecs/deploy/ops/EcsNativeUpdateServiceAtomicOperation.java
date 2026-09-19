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

package com.netflix.spinnaker.clouddriver.ecs.deploy.ops;

import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeUpdateServiceDescription;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DeploymentAlarms;
import software.amazon.awssdk.services.ecs.model.DeploymentCircuitBreaker;
import software.amazon.awssdk.services.ecs.model.DeploymentConfiguration;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

/**
 * In-place {@code UpdateService} for the opt-in {@code ecs-native} provider.
 *
 * <p>This is the native deployment primitive: instead of creating a new versioned service, it rolls
 * an existing, durable ECS service to a new task definition and/or deployment configuration and
 * lets ECS perform the rolling update (with its circuit breaker and optional automatic rollback).
 */
public class EcsNativeUpdateServiceAtomicOperation
    extends AbstractEcsAtomicOperation<EcsNativeUpdateServiceDescription, Void>
    implements AtomicOperation<Void> {

  public EcsNativeUpdateServiceAtomicOperation(EcsNativeUpdateServiceDescription description) {
    super(description, "UPDATE_ECS_SERVER_GROUP");
  }

  @Override
  public Void operate(List priorOutputs) {
    updateTaskStatus("Initializing Update ECS Server Group (native) Operation...");

    EcsClient ecs = getAmazonEcsClient();

    String serviceName = description.getServerGroupName();
    String cluster = description.getEcsClusterName();
    if (StringUtils.isBlank(cluster)) {
      cluster =
          containerInformationService.getClusterName(
              serviceName, description.getAccount(), description.getRegion());
    }

    UpdateServiceRequest.Builder requestBuilder =
        UpdateServiceRequest.builder().cluster(cluster).service(serviceName);

    if (StringUtils.isNotBlank(description.getTaskDefinition())) {
      requestBuilder.taskDefinition(description.getTaskDefinition());
    }

    DeploymentConfiguration deploymentConfiguration = buildDeploymentConfiguration();
    if (deploymentConfiguration != null) {
      requestBuilder.deploymentConfiguration(deploymentConfiguration);
    }

    requestBuilder.forceNewDeployment(description.isForceNewDeployment());

    updateTaskStatus(String.format("Updating ECS service %s in cluster %s.", serviceName, cluster));
    ecs.updateService(requestBuilder.build());
    updateTaskStatus(String.format("Done updating ECS service %s.", serviceName));

    return null;
  }

  /**
   * Builds a {@link DeploymentConfiguration} only when the description actually specifies one, so
   * an update that only changes the task definition does not overwrite the service's existing
   * rolling bounds.
   */
  private DeploymentConfiguration buildDeploymentConfiguration() {
    DeploymentAlarms alarms = buildDeploymentAlarms();
    boolean hasConfig =
        description.getMinimumHealthyPercent() != null
            || description.getMaximumPercent() != null
            || description.isEnableDeploymentCircuitBreaker()
            || description.isDeploymentCircuitBreakerRollback()
            || alarms != null
            || StringUtils.isNotBlank(description.getDeploymentStrategy())
            || description.getBakeTimeInMinutes() != null;
    if (!hasConfig) {
      return null;
    }

    DeploymentConfiguration.Builder builder = DeploymentConfiguration.builder();
    if (description.getMinimumHealthyPercent() != null) {
      builder.minimumHealthyPercent(description.getMinimumHealthyPercent());
    }
    if (description.getMaximumPercent() != null) {
      builder.maximumPercent(description.getMaximumPercent());
    }
    builder.deploymentCircuitBreaker(
        DeploymentCircuitBreaker.builder()
            .enable(description.isEnableDeploymentCircuitBreaker())
            .rollback(description.isDeploymentCircuitBreakerRollback())
            .build());
    if (alarms != null) {
      builder.alarms(alarms);
    }
    if (StringUtils.isNotBlank(description.getDeploymentStrategy())) {
      builder.strategy(description.getDeploymentStrategy());
    }
    if (description.getBakeTimeInMinutes() != null) {
      builder.bakeTimeInMinutes(description.getBakeTimeInMinutes());
    }
    return builder.build();
  }

  /**
   * Builds a {@link DeploymentAlarms} only when the description actually names alarms or opts in,
   * so an update that doesn't use them doesn't send an empty/disabled alarms block.
   */
  private DeploymentAlarms buildDeploymentAlarms() {
    boolean hasAlarmNames =
        description.getAlarmNames() != null && !description.getAlarmNames().isEmpty();
    if (!hasAlarmNames && !description.isEnableDeploymentAlarms()) {
      return null;
    }
    return DeploymentAlarms.builder()
        .alarmNames(description.getAlarmNames())
        .enable(true)
        .rollback(description.isDeploymentAlarmsRollback())
        .build();
  }
}
