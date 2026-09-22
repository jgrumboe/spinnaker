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

import com.netflix.spinnaker.clouddriver.aws.security.AmazonCredentials;
import com.netflix.spinnaker.clouddriver.aws.security.AssumeRoleAmazonCredentials;
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAssumeRoleAmazonCredentials;
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.CreateServerGroupDescription;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeCreateServerGroupDescription;
import com.netflix.spinnaker.clouddriver.ecs.names.EcsResource;
import com.netflix.spinnaker.clouddriver.ecs.names.EcsServerGroupName;
import com.netflix.spinnaker.clouddriver.ecs.security.NetflixAssumeRoleEcsCredentials;
import com.netflix.spinnaker.moniker.Moniker;
import com.netflix.spinnaker.moniker.Namer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AdvancedConfiguration;
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest;
import software.amazon.awssdk.services.ecs.model.DeploymentAlarms;
import software.amazon.awssdk.services.ecs.model.DeploymentCircuitBreaker;
import software.amazon.awssdk.services.ecs.model.DeploymentConfiguration;
import software.amazon.awssdk.services.ecs.model.LoadBalancer;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

/**
 * Create-server-group operation for the opt-in {@code ecs-native} provider.
 *
 * <p>It reuses all of {@link CreateServerGroupAtomicOperation}'s task-definition, load-balancer,
 * networking, scaling and tagging logic, and diverges in two ways:
 *
 * <ol>
 *   <li>The native ECS {@link DeploymentConfiguration} (rolling bounds, circuit-breaker rollback,
 *       deployment alarms, and the {@code ROLLING}/{@code BLUE_GREEN} strategy with its optional
 *       ALB traffic-shift config) is configurable rather than hard-coded (see {@link
 *       #makeServiceRequest}).
 *   <li>When {@link EcsNativeCreateServerGroupDescription#isInPlaceUpdate()} is set and a source
 *       server group exists, the redeploy rolls that durable service in place via a native {@code
 *       UpdateService} instead of creating a new versioned service (see {@link #operate}).
 *   <li>The initial deploy creates a service with a fixed, unversioned name (the cluster/family
 *       name, e.g. {@code app-stack-detail}) with no {@code -vNNN} suffix, since ecs-native keeps a
 *       single durable service and rolls new revisions in place (see {@link
 *       #buildEcsServerGroupName}).
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

  /**
   * ecs-native uses a fixed, unversioned service name (the cluster/family name, e.g. {@code
   * app-stack-detail}) rather than the resolver's next {@code -vNNN} slot. There is one durable
   * service per cluster that is rolled in place, so a per-deploy version sequence has no meaning;
   * the moniker sequence is left null (revision visibility comes from the deployed image /
   * task-definition detail, not the name). Overriding here keeps the shared {@link
   * CreateServerGroupAtomicOperation} — and the classic {@code ecs} provider's versioned naming —
   * untouched.
   */
  @Override
  protected EcsServerGroupName buildEcsServerGroupName(EcsClient ecs, Namer<EcsResource> namer) {
    Moniker moniker = description.getMoniker();
    if (moniker == null) {
      moniker =
          Moniker.builder()
              .app(description.getApplication())
              .stack(description.getStack())
              .detail(description.getFreeFormDetails())
              .build();
    } else {
      // Defensively drop any sequence the caller supplied: a fixed-name service has no version.
      moniker =
          Moniker.builder()
              .app(moniker.getApp())
              .cluster(moniker.getCluster())
              .stack(moniker.getStack())
              .detail(moniker.getDetail())
              .build();
    }
    return new EcsServerGroupName(moniker, true);
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

    EcsClient ecs = getAmazonEcsClient();
    String taskRoleArn = resolveTaskRoleArn(getCredentials());

    // Register a new revision under the same family as the existing service.
    EcsServerGroupName serverGroupName = new EcsServerGroupName(existingServiceName);
    TaskDefinition taskDefinition = registerTaskDefinition(ecs, taskRoleArn, serverGroupName);

    UpdateServiceRequest.Builder requestBuilder =
        UpdateServiceRequest.builder()
            .cluster(description.getEcsClusterName())
            .service(existingServiceName)
            .taskDefinition(taskDefinition.taskDefinitionArn())
            .forceNewDeployment(true);

    DeploymentConfiguration deploymentConfiguration = buildDeploymentConfiguration();
    if (deploymentConfiguration != null) {
      requestBuilder.deploymentConfiguration(deploymentConfiguration);
    }

    Service service = ecs.updateService(requestBuilder.build()).service();
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

    DeploymentConfiguration.Builder deploymentConfigBuilder =
        request.deploymentConfiguration() != null
            ? request.deploymentConfiguration().toBuilder()
            : DeploymentConfiguration.builder();

    if (nativeDescription.getMinimumHealthyPercent() != null) {
      deploymentConfigBuilder.minimumHealthyPercent(nativeDescription.getMinimumHealthyPercent());
    }
    if (nativeDescription.getMaximumPercent() != null) {
      deploymentConfigBuilder.maximumPercent(nativeDescription.getMaximumPercent());
    }
    deploymentConfigBuilder.deploymentCircuitBreaker(
        DeploymentCircuitBreaker.builder()
            .enable(nativeDescription.isEnableDeploymentCircuitBreaker())
            .rollback(nativeDescription.isDeploymentCircuitBreakerRollback())
            .build());

    DeploymentAlarms alarms = buildDeploymentAlarms(nativeDescription);
    if (alarms != null) {
      deploymentConfigBuilder.alarms(alarms);
    }
    if (StringUtils.isNotBlank(nativeDescription.getDeploymentStrategy())) {
      deploymentConfigBuilder.strategy(nativeDescription.getDeploymentStrategy());
    }
    if (nativeDescription.getBakeTimeInMinutes() != null) {
      deploymentConfigBuilder.bakeTimeInMinutes(nativeDescription.getBakeTimeInMinutes());
    }

    CreateServiceRequest.Builder requestBuilder =
        request.toBuilder().deploymentConfiguration(deploymentConfigBuilder.build());

    AdvancedConfiguration advancedConfiguration = buildAdvancedConfiguration(nativeDescription);
    if (advancedConfiguration != null) {
      requestBuilder.loadBalancers(
          withAdvancedConfiguration(request.loadBalancers(), advancedConfiguration));
    }

    return requestBuilder.build();
  }

  /**
   * Builds the ALB traffic-shift config for a {@code BLUE_GREEN} deployment, or {@code null} when
   * the deploy doesn't use one -- {@code BLUE_GREEN} works without it (ECS still stands up a new
   * task set and swaps it into the existing target group). The four fields are all-or-nothing: they
   * only make sense together, so a partial set is a configuration mistake, not a valid "no swap"
   * deploy.
   */
  private static AdvancedConfiguration buildAdvancedConfiguration(
      EcsNativeCreateServerGroupDescription nativeDescription) {
    String alternateTargetGroupArn = nativeDescription.getAlternateTargetGroupArn();
    String productionListenerRule = nativeDescription.getProductionListenerRule();
    String testListenerRule = nativeDescription.getTestListenerRule();
    String roleArn = nativeDescription.getBlueGreenRoleArn();

    boolean anySet =
        StringUtils.isNotBlank(alternateTargetGroupArn)
            || StringUtils.isNotBlank(productionListenerRule)
            || StringUtils.isNotBlank(testListenerRule)
            || StringUtils.isNotBlank(roleArn);
    if (!anySet) {
      return null;
    }
    boolean allSet =
        StringUtils.isNotBlank(alternateTargetGroupArn)
            && StringUtils.isNotBlank(productionListenerRule)
            && StringUtils.isNotBlank(testListenerRule)
            && StringUtils.isNotBlank(roleArn);
    if (!allSet) {
      throw new IllegalArgumentException(
          "alternateTargetGroupArn, productionListenerRule, testListenerRule and"
              + " blueGreenRoleArn must all be set together to configure a blue/green ALB"
              + " traffic shift, or all left unset to skip it.");
    }
    return AdvancedConfiguration.builder()
        .alternateTargetGroupArn(alternateTargetGroupArn)
        .productionListenerRule(productionListenerRule)
        .testListenerRule(testListenerRule)
        .roleArn(roleArn)
        .build();
  }

  /**
   * Attaches the blue/green traffic-shift config to the service's load balancer entry. Scoped to
   * exactly one entry: {@code AdvancedConfiguration} applies to a single target-group mapping, and
   * this operation has no way to tell which of several mappings a caller means, so it refuses
   * rather than guessing.
   */
  private static List<LoadBalancer> withAdvancedConfiguration(
      List<LoadBalancer> loadBalancers, AdvancedConfiguration advancedConfiguration) {
    if (loadBalancers == null || loadBalancers.size() != 1) {
      throw new IllegalArgumentException(
          "A blue/green ALB traffic shift requires exactly one target-group mapping; found "
              + (loadBalancers == null ? 0 : loadBalancers.size())
              + ".");
    }
    List<LoadBalancer> updated = new ArrayList<>(loadBalancers.size());
    updated.add(
        loadBalancers.get(0).toBuilder().advancedConfiguration(advancedConfiguration).build());
    return updated;
  }

  /**
   * Builds a {@link DeploymentConfiguration} only when the description specifies one, so an
   * in-place update that only changes the task definition does not overwrite the service's existing
   * rolling bounds.
   */
  private DeploymentConfiguration buildDeploymentConfiguration() {
    EcsNativeCreateServerGroupDescription nativeDescription = nativeDescription();
    DeploymentAlarms alarms = buildDeploymentAlarms(nativeDescription);
    boolean hasConfig =
        nativeDescription.getMinimumHealthyPercent() != null
            || nativeDescription.getMaximumPercent() != null
            || nativeDescription.isEnableDeploymentCircuitBreaker()
            || nativeDescription.isDeploymentCircuitBreakerRollback()
            || alarms != null
            || StringUtils.isNotBlank(nativeDescription.getDeploymentStrategy())
            || nativeDescription.getBakeTimeInMinutes() != null;
    if (!hasConfig) {
      return null;
    }

    DeploymentConfiguration.Builder builder = DeploymentConfiguration.builder();
    if (nativeDescription.getMinimumHealthyPercent() != null) {
      builder.minimumHealthyPercent(nativeDescription.getMinimumHealthyPercent());
    }
    if (nativeDescription.getMaximumPercent() != null) {
      builder.maximumPercent(nativeDescription.getMaximumPercent());
    }
    builder.deploymentCircuitBreaker(
        DeploymentCircuitBreaker.builder()
            .enable(nativeDescription.isEnableDeploymentCircuitBreaker())
            .rollback(nativeDescription.isDeploymentCircuitBreakerRollback())
            .build());
    if (alarms != null) {
      builder.alarms(alarms);
    }
    if (StringUtils.isNotBlank(nativeDescription.getDeploymentStrategy())) {
      builder.strategy(nativeDescription.getDeploymentStrategy());
    }
    if (nativeDescription.getBakeTimeInMinutes() != null) {
      builder.bakeTimeInMinutes(nativeDescription.getBakeTimeInMinutes());
    }
    return builder.build();
  }

  /**
   * Builds a {@link DeploymentAlarms} only when the description actually names alarms or opts in,
   * so a deploy that doesn't use them doesn't send an empty/disabled alarms block.
   */
  private static DeploymentAlarms buildDeploymentAlarms(
      EcsNativeCreateServerGroupDescription nativeDescription) {
    boolean hasAlarmNames =
        nativeDescription.getAlarmNames() != null && !nativeDescription.getAlarmNames().isEmpty();
    if (!hasAlarmNames && !nativeDescription.isEnableDeploymentAlarms()) {
      return null;
    }
    return DeploymentAlarms.builder()
        .alarmNames(nativeDescription.getAlarmNames())
        .enable(true)
        .rollback(nativeDescription.isDeploymentAlarmsRollback())
        .build();
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

  /**
   * The base {@code CreateServerGroupDescription} overrides {@code getRegion()} to derive it from
   * {@code getAvailabilityZones()} unconditionally -- there's no way to reach the plain {@code
   * region} field on {@code AbstractECSDescription} once that override is in the hierarchy, since
   * every {@code getRegion()} call (however it's dispatched) resolves to the same override. That's
   * fine for a normal create, but the in-place-update path here never touches availability zones,
   * so that map is typically unset and the override throws a {@code NullPointerException}.
   *
   * <p>Scoped strictly to {@link EcsNativeCreateServerGroupDescription#isInPlaceUpdate()}: the
   * source block's region ({@code source.asgName}/{@code source.region}) is always populated
   * whenever {@link #resolveExistingServiceName()} finds an existing service, since both come from
   * the same deploy-stage source block. Deliberately not applied to normal clone flows (in-place or
   * not), where {@code source.region} can legitimately differ from the destination {@code
   * availabilityZones} region (e.g. a cross-region clone) -- only {@code inPlaceUpdate} changes the
   * semantics enough to justify preferring the source's region.
   */
  @Override
  protected String getRegion() {
    EcsNativeCreateServerGroupDescription nativeDescription = nativeDescription();
    if (nativeDescription.isInPlaceUpdate()
        && description.getSource() != null
        && StringUtils.isNotBlank(description.getSource().getRegion())) {
      return description.getSource().getRegion();
    }
    return super.getRegion();
  }

  private DeploymentResult buildDeploymentResult(Service service) {
    Map<String, String> namesByRegion = new HashMap<>();
    namesByRegion.put(getRegion(), service.serviceName());

    DeploymentResult result = new DeploymentResult();
    result.setServerGroupNames(
        Collections.singletonList(getRegion() + ":" + service.serviceName()));
    result.setServerGroupNameByRegion(namesByRegion);
    return result;
  }

  private EcsNativeCreateServerGroupDescription nativeDescription() {
    return (EcsNativeCreateServerGroupDescription) description;
  }
}
