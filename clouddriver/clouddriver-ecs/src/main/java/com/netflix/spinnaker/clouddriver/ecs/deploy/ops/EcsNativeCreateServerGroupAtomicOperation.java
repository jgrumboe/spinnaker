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
import com.amazonaws.services.ecs.model.CreateServiceRequest;
import com.amazonaws.services.ecs.model.DeploymentCircuitBreaker;
import com.amazonaws.services.ecs.model.DeploymentConfiguration;
import com.amazonaws.services.ecs.model.Service;
import com.amazonaws.services.ecs.model.TaskDefinition;
import com.amazonaws.services.ecs.model.UpdateServiceRequest;
import com.netflix.spinnaker.clouddriver.aws.security.AmazonCredentials;
import com.netflix.spinnaker.clouddriver.aws.security.AssumeRoleAmazonCredentials;
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAssumeRoleAmazonCredentials;
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.CreateServerGroupDescription;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeCreateServerGroupDescription;
import com.netflix.spinnaker.clouddriver.ecs.names.EcsResource;
import com.netflix.spinnaker.clouddriver.ecs.names.EcsServerGroupName;
import com.netflix.spinnaker.clouddriver.ecs.security.NetflixAssumeRoleEcsCredentials;
import com.netflix.spinnaker.moniker.Namer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * Create-server-group operation for the opt-in {@code ecs-native} provider.
 *
 * <p>It reuses all of {@link CreateServerGroupAtomicOperation}'s task-definition, load-balancer,
 * networking, scaling and tagging logic, and diverges in two ways:
 *
 * <ol>
 *   <li>The native ECS {@link DeploymentConfiguration} (rolling bounds + circuit-breaker rollback)
 *       is configurable rather than hard-coded (see {@link #makeServiceRequest}).
 *   <li>When {@link EcsNativeCreateServerGroupDescription#isInPlaceUpdate()} is set and a source
 *       server group exists, the redeploy rolls that durable service in place via a native {@code
 *       UpdateService} instead of creating a new versioned service (see {@link #operate}).
 * </ol>
 *
 * <p>The shared {@link CreateServerGroupAtomicOperation} is not modified; this operation is a
 * subclass so the original {@code ecs} provider is unaffected.
 */
public class EcsNativeCreateServerGroupAtomicOperation extends CreateServerGroupAtomicOperation {

  public EcsNativeCreateServerGroupAtomicOperation(
      EcsNativeCreateServerGroupDescription description) {
    super(description);
  }

  @Override
  public DeploymentResult operate(List priorOutputs) {
    EcsNativeCreateServerGroupDescription nativeDescription = nativeDescription();

    if (nativeDescription.isInPlaceUpdate()) {
      String existingServiceName = resolveExistingServiceName();
      if (existingServiceName != null) {
        return updateExistingServiceInPlace(existingServiceName);
      }
      updateTaskStatus(
          "No existing ecs-native service found for in-place update; creating the initial durable service.");
    }

    return super.operate(priorOutputs);
  }

  /** The service to roll in place, taken from the deploy source; {@code null} on first deploy. */
  protected String resolveExistingServiceName() {
    CreateServerGroupDescription.Source source = description.getSource();
    if (source != null && StringUtils.isNotBlank(source.getAsgName())) {
      return source.getAsgName();
    }
    return null;
  }

  private DeploymentResult updateExistingServiceInPlace(String existingServiceName) {
    updateTaskStatus(
        "Rolling ecs-native service "
            + existingServiceName
            + " in place via native UpdateService...");

    AmazonECS ecs = getAmazonEcsClient();
    String taskRoleArn = resolveTaskRoleArn(getCredentials());

    // Register a new revision under the same family as the existing service.
    EcsServerGroupName serverGroupName = new EcsServerGroupName(existingServiceName);
    TaskDefinition taskDefinition = registerTaskDefinition(ecs, taskRoleArn, serverGroupName);

    UpdateServiceRequest request =
        new UpdateServiceRequest()
            .withCluster(description.getEcsClusterName())
            .withService(existingServiceName)
            .withTaskDefinition(taskDefinition.getTaskDefinitionArn())
            .withForceNewDeployment(true);

    DeploymentConfiguration deploymentConfiguration = buildDeploymentConfiguration();
    if (deploymentConfiguration != null) {
      request.setDeploymentConfiguration(deploymentConfiguration);
    }

    Service service = ecs.updateService(request).getService();
    updateTaskStatus("Done rolling ecs-native service " + existingServiceName + " in place.");

    return buildDeploymentResult(service);
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

    EcsNativeCreateServerGroupDescription nativeDescription = nativeDescription();

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

  /**
   * Builds a {@link DeploymentConfiguration} only when the description specifies one, so an in-place
   * update that only changes the task definition does not overwrite the service's existing rolling
   * bounds.
   */
  private DeploymentConfiguration buildDeploymentConfiguration() {
    EcsNativeCreateServerGroupDescription nativeDescription = nativeDescription();
    boolean hasConfig =
        nativeDescription.getMinimumHealthyPercent() != null
            || nativeDescription.getMaximumPercent() != null
            || nativeDescription.isEnableDeploymentCircuitBreaker()
            || nativeDescription.isDeploymentCircuitBreakerRollback();
    if (!hasConfig) {
      return null;
    }

    DeploymentConfiguration deploymentConfiguration = new DeploymentConfiguration();
    if (nativeDescription.getMinimumHealthyPercent() != null) {
      deploymentConfiguration.setMinimumHealthyPercent(
          nativeDescription.getMinimumHealthyPercent());
    }
    if (nativeDescription.getMaximumPercent() != null) {
      deploymentConfiguration.setMaximumPercent(nativeDescription.getMaximumPercent());
    }
    deploymentConfiguration.setDeploymentCircuitBreaker(
        new DeploymentCircuitBreaker()
            .withEnable(nativeDescription.isEnableDeploymentCircuitBreaker())
            .withRollback(nativeDescription.isDeploymentCircuitBreakerRollback()));
    return deploymentConfiguration;
  }

  /**
   * Resolves the task role ARN from the account credentials. Mirrors the (private) inference in the
   * shared create operation; exposed as {@code protected} so it can be overridden in tests.
   */
  protected String resolveTaskRoleArn(AmazonCredentials credentials) {
    String role;
    if (credentials instanceof AssumeRoleAmazonCredentials) {
      role = ((AssumeRoleAmazonCredentials) credentials).getAssumeRole();
    } else if (credentials instanceof NetflixAssumeRoleAmazonCredentials) {
      role = ((NetflixAssumeRoleAmazonCredentials) credentials).getAssumeRole();
    } else if (credentials instanceof NetflixAssumeRoleEcsCredentials) {
      role = ((NetflixAssumeRoleEcsCredentials) credentials).getAssumeRole();
    } else {
      throw new UnsupportedOperationException(
          "The given kind of credentials is not supported for ecs-native in-place updates.");
    }
    if (!role.startsWith("arn:")) {
      return String.format("arn:aws:iam::%s:%s", credentials.getAccountId(), role);
    }
    return role;
  }

  private DeploymentResult buildDeploymentResult(Service service) {
    Map<String, String> namesByRegion = new HashMap<>();
    namesByRegion.put(getRegion(), service.getServiceName());

    DeploymentResult result = new DeploymentResult();
    result.setServerGroupNames(
        Collections.singletonList(getRegion() + ":" + service.getServiceName()));
    result.setServerGroupNameByRegion(namesByRegion);
    return result;
  }

  private EcsNativeCreateServerGroupDescription nativeDescription() {
    return (EcsNativeCreateServerGroupDescription) description;
  }
}
