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

import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult;
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult.Deployment;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeUpdateServiceDescription;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DeploymentAlarms;
import software.amazon.awssdk.services.ecs.model.DeploymentCircuitBreaker;
import software.amazon.awssdk.services.ecs.model.DeploymentConfiguration;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

/**
 * In-place {@code UpdateService} for the opt-in {@code ecs-native} provider.
 *
 * <p>This is the native deployment primitive: instead of creating a new versioned service, it rolls
 * an existing, durable ECS service to a new task definition and/or service shape and lets ECS
 * perform the rolling update with its circuit breaker and optional automatic rollback.
 */
public class EcsNativeUpdateServiceAtomicOperation
    extends AbstractEcsAtomicOperation<EcsNativeUpdateServiceDescription, DeploymentResult>
    implements AtomicOperation<DeploymentResult> {

  public EcsNativeUpdateServiceAtomicOperation(EcsNativeUpdateServiceDescription description) {
    super(description, "UPDATE_ECS_SERVER_GROUP");
  }

  @Override
  public DeploymentResult operate(List priorOutputs) {
    updateTaskStatus("Initializing Update ECS Server Group (native) Operation...");

    EcsClient ecs = getAmazonEcsClient();

    String serviceName = description.getServerGroupName();
    String cluster = description.getEcsClusterName();
    if (StringUtils.isBlank(cluster)) {
      cluster =
          containerInformationService.getClusterName(
              serviceName, description.getAccount(), description.getRegion());
    }

    DeploymentConfiguration existingConfiguration = null;
    if (hasDeploymentConfiguration()) {
      Service existingService =
          ecs
              .describeServices(
                  DescribeServicesRequest.builder().cluster(cluster).services(serviceName).build())
              .services()
              .stream()
              .findFirst()
              .orElse(null);
      if (existingService == null) {
        throw new IllegalStateException(
            String.format("ECS service %s was not found in cluster %s.", serviceName, cluster));
      }
      existingConfiguration = existingService.deploymentConfiguration();
    }

    UpdateServiceRequest.Builder requestBuilder =
        UpdateServiceRequest.builder().cluster(cluster).service(serviceName);

    if (StringUtils.isNotBlank(description.getTaskDefinition())) {
      requestBuilder.taskDefinition(description.getTaskDefinition());
    }
    if (description.getDesiredCount() != null) {
      requestBuilder.desiredCount(description.getDesiredCount());
    }
    if (description.getNetworkConfiguration() != null) {
      requestBuilder.networkConfiguration(description.getNetworkConfiguration());
    }
    if (description.getServiceRegistries() != null) {
      requestBuilder.serviceRegistries(description.getServiceRegistries());
    }
    if (description.getPlacementConstraints() != null) {
      requestBuilder.placementConstraints(description.getPlacementConstraints());
    }
    if (description.getPlacementStrategy() != null) {
      requestBuilder.placementStrategy(description.getPlacementStrategy());
    }
    if (description.getCapacityProviderStrategy() != null) {
      requestBuilder.capacityProviderStrategy(description.getCapacityProviderStrategy());
    }
    if (StringUtils.isNotBlank(description.getPlatformVersion())) {
      requestBuilder.platformVersion(description.getPlatformVersion());
    }
    if (description.getHealthCheckGracePeriodSeconds() != null) {
      requestBuilder.healthCheckGracePeriodSeconds(description.getHealthCheckGracePeriodSeconds());
    }
    if (description.getEnableExecuteCommand() != null) {
      requestBuilder.enableExecuteCommand(description.getEnableExecuteCommand());
    }
    if (description.getLoadBalancers() != null) {
      requestBuilder.loadBalancers(description.getLoadBalancers());
    }

    DeploymentConfiguration deploymentConfiguration =
        buildDeploymentConfiguration(existingConfiguration);
    if (deploymentConfiguration != null) {
      requestBuilder.deploymentConfiguration(deploymentConfiguration);
    }

    requestBuilder.forceNewDeployment(description.isForceNewDeployment());

    updateTaskStatus(String.format("Updating ECS service %s in cluster %s.", serviceName, cluster));
    Service service = ecs.updateService(requestBuilder.build()).service();
    updateTaskStatus(String.format("Done updating ECS service %s.", serviceName));

    return buildDeploymentResult(service);
  }

  private DeploymentResult buildDeploymentResult(Service service) {
    String resolvedServiceName =
        StringUtils.defaultIfBlank(service.serviceName(), description.getServerGroupName());
    Map<String, String> namesByRegion = new HashMap<>();
    namesByRegion.put(description.getRegion(), resolvedServiceName);

    DeploymentResult result = new DeploymentResult();
    result.setServerGroupNames(
        Collections.singletonList(description.getRegion() + ":" + resolvedServiceName));
    result.setServerGroupNameByRegion(namesByRegion);

    if (StringUtils.isNotBlank(service.taskDefinition())) {
      Deployment deployment = new Deployment();
      deployment.setCloudProvider("ecs-native");
      deployment.setAccount(description.getAccount());
      deployment.setLocation(description.getRegion());
      deployment.setServerGroupName(resolvedServiceName);
      deployment.getMetadata().put("ecsNativeExpectedTaskDefinition", service.taskDefinition());
      result.setDeployments(Collections.singleton(deployment));
    }
    return result;
  }

  private boolean hasDeploymentConfiguration() {
    return description.getMinimumHealthyPercent() != null
        || description.getMaximumPercent() != null
        || description.isEnableDeploymentCircuitBreaker()
        || description.isDeploymentCircuitBreakerRollback()
        || (description.getAlarmNames() != null && !description.getAlarmNames().isEmpty())
        || description.isEnableDeploymentAlarms()
        || StringUtils.isNotBlank(description.getDeploymentStrategy())
        || description.getBakeTimeInMinutes() != null;
  }

  /**
   * Builds a {@link DeploymentConfiguration} only when the description actually specifies one, so
   * an update that only changes the task definition does not overwrite the service's existing
   * rolling bounds.
   */
  private DeploymentConfiguration buildDeploymentConfiguration(
      DeploymentConfiguration existingConfiguration) {
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

    DeploymentConfiguration.Builder builder =
        existingConfiguration == null
            ? DeploymentConfiguration.builder()
            : existingConfiguration.toBuilder();
    if (description.getMinimumHealthyPercent() != null) {
      builder.minimumHealthyPercent(description.getMinimumHealthyPercent());
    }
    if (description.getMaximumPercent() != null) {
      builder.maximumPercent(description.getMaximumPercent());
    }
    if (description.isEnableDeploymentCircuitBreaker()
        || description.isDeploymentCircuitBreakerRollback()) {
      builder.deploymentCircuitBreaker(
          DeploymentCircuitBreaker.builder()
              .enable(description.isEnableDeploymentCircuitBreaker())
              .rollback(description.isDeploymentCircuitBreakerRollback())
              .build());
    }
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
