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

import com.amazonaws.services.ecs.model.CreateServiceRequest;
import com.amazonaws.services.ecs.model.DeploymentCircuitBreaker;
import com.amazonaws.services.ecs.model.DeploymentConfiguration;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeCreateServerGroupDescription;
import com.netflix.spinnaker.clouddriver.ecs.names.EcsResource;
import com.netflix.spinnaker.clouddriver.ecs.names.EcsServerGroupName;
import com.netflix.spinnaker.moniker.Namer;

/**
 * Create-server-group operation for the opt-in {@code ecs-native} provider.
 *
 * <p>Phase 2a: it reuses all of {@link CreateServerGroupAtomicOperation}'s task-definition,
 * load-balancer, networking, scaling and tagging logic, and diverges only in the native ECS {@link
 * DeploymentConfiguration}: the original provider hard-codes {@code minimumHealthyPercent=100},
 * {@code maximumPercent=200} and {@code circuitBreaker.rollback=false}. Here those come from the
 * {@link EcsNativeCreateServerGroupDescription}, so a native deploy can tune the rolling bounds and
 * opt into automatic ECS rollback on a failed deployment.
 *
 * <p>In-place {@code UpdateService} against a durable service (rather than creating a new versioned
 * service per deploy) is a later phase.
 */
public class EcsNativeCreateServerGroupAtomicOperation extends CreateServerGroupAtomicOperation {

  public EcsNativeCreateServerGroupAtomicOperation(
      EcsNativeCreateServerGroupDescription description) {
    super(description);
  }

  @Override
  protected CreateServiceRequest makeServiceRequest(
      String taskDefinitionArn,
      EcsServerGroupName newServerGroupName,
      Integer desiredCount,
      Namer<EcsResource> namer,
      boolean taggingEnabled) {

    CreateServiceRequest request =
        super.makeServiceRequest(
            taskDefinitionArn, newServerGroupName, desiredCount, namer, taggingEnabled);

    // description is the package-private field on AbstractEcsAtomicOperation; the converter always
    // builds the native subtype for this operation.
    EcsNativeCreateServerGroupDescription nativeDescription =
        (EcsNativeCreateServerGroupDescription) description;

    DeploymentConfiguration deploymentConfiguration = request.getDeploymentConfiguration();
    if (deploymentConfiguration == null) {
      deploymentConfiguration = new DeploymentConfiguration();
    }

    if (nativeDescription.getMinimumHealthyPercent() != null) {
      deploymentConfiguration.setMinimumHealthyPercent(
          nativeDescription.getMinimumHealthyPercent());
    }
    if (nativeDescription.getMaximumPercent() != null) {
      deploymentConfiguration.setMaximumPercent(nativeDescription.getMaximumPercent());
    }

    DeploymentCircuitBreaker circuitBreaker = deploymentConfiguration.getDeploymentCircuitBreaker();
    if (circuitBreaker == null) {
      circuitBreaker = new DeploymentCircuitBreaker();
      deploymentConfiguration.setDeploymentCircuitBreaker(circuitBreaker);
    }
    circuitBreaker.setEnable(nativeDescription.isEnableDeploymentCircuitBreaker());
    circuitBreaker.setRollback(nativeDescription.isDeploymentCircuitBreakerRollback());

    request.setDeploymentConfiguration(deploymentConfiguration);
    return request;
  }
}
