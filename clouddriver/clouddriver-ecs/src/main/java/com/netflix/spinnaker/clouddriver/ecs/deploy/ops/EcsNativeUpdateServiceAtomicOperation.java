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

import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.model.DeploymentCircuitBreaker;
import com.amazonaws.services.ecs.model.DeploymentConfiguration;
import com.amazonaws.services.ecs.model.UpdateServiceRequest;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeUpdateServiceDescription;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

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

    AmazonECS ecs = getAmazonEcsClient();

    String serviceName = description.getServerGroupName();
    String cluster = description.getEcsClusterName();
    if (StringUtils.isBlank(cluster)) {
      cluster =
          containerInformationService.getClusterName(
              serviceName, description.getAccount(), description.getRegion());
    }

    UpdateServiceRequest request =
        new UpdateServiceRequest().withCluster(cluster).withService(serviceName);

    if (StringUtils.isNotBlank(description.getTaskDefinition())) {
      request.setTaskDefinition(description.getTaskDefinition());
    }

    DeploymentConfiguration deploymentConfiguration = buildDeploymentConfiguration();
    if (deploymentConfiguration != null) {
      request.setDeploymentConfiguration(deploymentConfiguration);
    }

    request.setForceNewDeployment(description.isForceNewDeployment());

    updateTaskStatus(
        String.format("Updating ECS service %s in cluster %s.", serviceName, cluster));
    ecs.updateService(request);
    updateTaskStatus(String.format("Done updating ECS service %s.", serviceName));

    return null;
  }

  /**
   * Builds a {@link DeploymentConfiguration} only when the description actually specifies one, so an
   * update that only changes the task definition does not overwrite the service's existing rolling
   * bounds.
   */
  private DeploymentConfiguration buildDeploymentConfiguration() {
    boolean hasConfig =
        description.getMinimumHealthyPercent() != null
            || description.getMaximumPercent() != null
            || description.isEnableDeploymentCircuitBreaker()
            || description.isDeploymentCircuitBreakerRollback();
    if (!hasConfig) {
      return null;
    }

    DeploymentConfiguration deploymentConfiguration = new DeploymentConfiguration();
    if (description.getMinimumHealthyPercent() != null) {
      deploymentConfiguration.setMinimumHealthyPercent(description.getMinimumHealthyPercent());
    }
    if (description.getMaximumPercent() != null) {
      deploymentConfiguration.setMaximumPercent(description.getMaximumPercent());
    }
    deploymentConfiguration.setDeploymentCircuitBreaker(
        new DeploymentCircuitBreaker()
            .withEnable(description.isEnableDeploymentCircuitBreaker())
            .withRollback(description.isDeploymentCircuitBreakerRollback()));
    return deploymentConfiguration;
  }
}
