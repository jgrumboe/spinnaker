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

import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeDeploymentSettings;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.services.ecs.model.DeploymentAlarms;
import software.amazon.awssdk.services.ecs.model.DeploymentCircuitBreaker;
import software.amazon.awssdk.services.ecs.model.DeploymentConfiguration;

/** Builds native ECS deployment settings consistently for create and update requests. */
final class EcsNativeDeploymentConfiguration {

  private EcsNativeDeploymentConfiguration() {}

  static DeploymentConfiguration forCreate(
      EcsNativeDeploymentSettings settings, DeploymentConfiguration defaults) {
    DeploymentConfiguration.Builder builder =
        defaults == null ? DeploymentConfiguration.builder() : defaults.toBuilder();
    applyCommonSettings(builder, settings);
    builder.deploymentCircuitBreaker(
        DeploymentCircuitBreaker.builder()
            .enable(Boolean.TRUE.equals(settings.getEnableDeploymentCircuitBreaker()))
            .rollback(Boolean.TRUE.equals(settings.getDeploymentCircuitBreakerRollback()))
            .build());
    return builder.build();
  }

  static DeploymentConfiguration forUpdate(
      EcsNativeDeploymentSettings settings, DeploymentConfiguration existingConfiguration) {
    if (!hasConfiguration(settings)) {
      return null;
    }

    DeploymentConfiguration.Builder builder =
        existingConfiguration == null
            ? DeploymentConfiguration.builder()
            : existingConfiguration.toBuilder();
    applyCommonSettings(builder, settings);
    if (settings.getEnableDeploymentCircuitBreaker() != null
        || settings.getDeploymentCircuitBreakerRollback() != null) {
      builder.deploymentCircuitBreaker(
          DeploymentCircuitBreaker.builder()
              .enable(Boolean.TRUE.equals(settings.getEnableDeploymentCircuitBreaker()))
              .rollback(Boolean.TRUE.equals(settings.getDeploymentCircuitBreakerRollback()))
              .build());
    }
    return builder.build();
  }

  static void validateLifecycleSupport(EcsNativeDeploymentSettings settings) {
    validateLifecycleSupport(settings, null);
  }

  static void validateLifecycleSupport(
      EcsNativeDeploymentSettings settings, DeploymentConfiguration existingConfiguration) {
    String effectiveStrategy = settings.getDeploymentStrategy();
    if (StringUtils.isBlank(effectiveStrategy) && existingConfiguration != null) {
      effectiveStrategy = existingConfiguration.strategyAsString();
    }
    if (StringUtils.equalsIgnoreCase(effectiveStrategy, "BLUE_GREEN")) {
      throw new UnsupportedOperationException(
          "ecs-native BLUE_GREEN deployments are unsupported until Orca and Deck model "
              + "StopServiceDeployment and ContinueServiceDeployment lifecycle actions; use ROLLING.");
    }
  }

  static boolean hasConfiguration(EcsNativeDeploymentSettings settings) {
    return settings.getMinimumHealthyPercent() != null
        || settings.getMaximumPercent() != null
        || settings.getEnableDeploymentCircuitBreaker() != null
        || settings.getDeploymentCircuitBreakerRollback() != null
        || hasDeploymentAlarms(settings)
        || StringUtils.isNotBlank(settings.getDeploymentStrategy())
        || settings.getBakeTimeInMinutes() != null;
  }

  private static void applyCommonSettings(
      DeploymentConfiguration.Builder builder, EcsNativeDeploymentSettings settings) {
    if (settings.getMinimumHealthyPercent() != null) {
      builder.minimumHealthyPercent(settings.getMinimumHealthyPercent());
    }
    if (settings.getMaximumPercent() != null) {
      builder.maximumPercent(settings.getMaximumPercent());
    }
    DeploymentAlarms alarms = buildDeploymentAlarms(settings);
    if (alarms != null) {
      builder.alarms(alarms);
    }
    if (StringUtils.isNotBlank(settings.getDeploymentStrategy())) {
      builder.strategy(settings.getDeploymentStrategy());
    }
    if (settings.getBakeTimeInMinutes() != null) {
      builder.bakeTimeInMinutes(settings.getBakeTimeInMinutes());
    }
  }

  private static boolean hasDeploymentAlarms(EcsNativeDeploymentSettings settings) {
    return (settings.getAlarmNames() != null && !settings.getAlarmNames().isEmpty())
        || settings.isEnableDeploymentAlarms();
  }

  private static DeploymentAlarms buildDeploymentAlarms(EcsNativeDeploymentSettings settings) {
    if (!hasDeploymentAlarms(settings)) {
      return null;
    }
    List<String> alarmNames = settings.getAlarmNames();
    return DeploymentAlarms.builder()
        .alarmNames(alarmNames)
        .enable(true)
        .rollback(settings.isDeploymentAlarmsRollback())
        .build();
  }
}
