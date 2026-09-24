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
import com.netflix.spinnaker.clouddriver.ecs.EcsCloudProvider;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeCreateServerGroupDescription;
import com.netflix.spinnaker.clouddriver.ecs.names.EcsResource;
import com.netflix.spinnaker.clouddriver.ecs.names.EcsServerGroupName;
import com.netflix.spinnaker.clouddriver.ecs.security.NetflixAssumeRoleEcsCredentials;
import com.netflix.spinnaker.clouddriver.names.NamerRegistry;
import com.netflix.spinnaker.moniker.Moniker;
import com.netflix.spinnaker.moniker.Namer;
import java.util.ArrayList;
import java.util.Collection;
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
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
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
 *   <li>ecs-native always deploys to a single durable service in place: {@link #operate} computes
 *       the fixed service name and, if that service already exists, rolls it via a native {@code
 *       UpdateService} instead of creating a new versioned service. Only the very first deploy
 *       (service does not exist yet) falls through to {@code CreateService}.
 *   <li>The service uses a fixed, unversioned name (the cluster/family name, e.g. {@code
 *       app-stack-detail}) with no {@code -vNNN} suffix, since ecs-native keeps a single durable
 *       service and rolls new revisions in place (see {@link #buildEcsServerGroupName}).
 * </ol>
 *
 * <p>The shared {@link CreateServerGroupAtomicOperation} is not modified; this operation is a
 * subclass so the original {@code ecs} provider is unaffected.
 */
public class EcsNativeCreateServerGroupAtomicOperation extends CreateServerGroupAtomicOperation {

  /** ECS deployment strategy that shifts traffic to a whole new task set (vs. {@code ROLLING}). */
  private static final String BLUE_GREEN_STRATEGY = "BLUE_GREEN";

  public EcsNativeCreateServerGroupAtomicOperation(
      EcsNativeCreateServerGroupDescription description) {
    super(description);
  }

  @Override
  public DeploymentResult operate(List priorOutputs) {
    // ecs-native is always in-place: there is one durable ECS service per cluster, named by the
    // fixed (unversioned) family name. If that service already exists, roll it via a native
    // UpdateService; only the first-ever deploy (service absent) falls through to CreateService.
    String existingServiceName = resolveExistingServiceName();
    if (existingServiceName != null) {
      return updateExistingServiceInPlace(existingServiceName);
    }
    updateTaskStatus("No existing ecs-native service found; creating the initial durable service.");
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

  /**
   * Returns the name of the existing durable ecs-native service to roll in place, or {@code null}
   * if it does not exist yet (first deploy). ecs-native uses a fixed, unversioned service name, so
   * we compute that name and ask ECS whether such a service is currently ACTIVE/DRAINING in the
   * cluster -- independent of any deploy-stage source block. This is what makes every ecs-native
   * redeploy an in-place UpdateService rather than a colliding CreateService.
   */
  protected String resolveExistingServiceName() {
    EcsClient ecs = getAmazonEcsClient();
    String fixedServiceName = buildEcsServerGroupName(ecs, null).getServiceName();

    DescribeServicesRequest request =
        DescribeServicesRequest.builder()
            .cluster(description.getEcsClusterName())
            .services(fixedServiceName)
            .build();
    DescribeServicesResponse result = ecs.describeServices(request);

    // A service that has been deleted lingers as INACTIVE; treat only ACTIVE/DRAINING as existing.
    boolean exists =
        !result.services().isEmpty() && !"INACTIVE".equals(result.services().get(0).status());
    return exists ? fixedServiceName : null;
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

    UpdateServiceRequest.Builder requestBuilder;
    if (description.getCapacity() != null) {
      requestBuilder =
          buildAuthoritativeUpdateRequest(ecs, taskDefinition.taskDefinitionArn(), serverGroupName);
      // Application Auto Scaling owns min/max capacity, while this native deploy owns the desired
      // count. Re-registering the target makes capacity edits explicit instead of silently ignored.
    } else {
      requestBuilder =
          UpdateServiceRequest.builder()
              .cluster(description.getEcsClusterName())
              .service(existingServiceName)
              .taskDefinition(taskDefinition.taskDefinitionArn())
              .forceNewDeployment(true);

      validateBlueGreenInPlaceConfig(nativeDescription());

      DeploymentConfiguration deploymentConfiguration = buildDeploymentConfiguration();
      if (deploymentConfiguration != null) {
        requestBuilder.deploymentConfiguration(deploymentConfiguration);
      }

      // For a blue/green ALB traffic shift ECS needs the advancedConfiguration on the service's
      // load balancer, and UpdateService only carries it when we also (re)send loadBalancers.
      AdvancedConfiguration advancedConfiguration = buildAdvancedConfiguration(nativeDescription());
      if (advancedConfiguration != null) {
        Collection<LoadBalancer> loadBalancers =
            retrieveLoadBalancers(serverGroupName.getContainerName());
        requestBuilder.loadBalancers(
            withAdvancedConfiguration(new ArrayList<>(loadBalancers), advancedConfiguration));
      }
    }

    Service service = ecs.updateService(requestBuilder.build()).service();
    if (description.getCapacity() != null) {
      registerAutoScalingGroup(getCredentials(), service, null);
    }
    updateTaskStatus("Done rolling ecs-native service " + existingServiceName + " in place.");

    return buildDeploymentResult(service);
  }

  /**
   * Builds an authoritative UpdateService request from the same service-shape contract used by the
   * initial CreateService request. ECS does not support launchType changes through UpdateService,
   * so launchType remains the deliberate exception; capacity provider strategy is the mutable ECS
   * replacement exposed here.
   */
  private UpdateServiceRequest.Builder buildAuthoritativeUpdateRequest(
      EcsClient ecs, String taskDefinitionArn, EcsServerGroupName serverGroupName) {
    Namer<EcsResource> namer =
        NamerRegistry.lookup()
            .withProvider(EcsCloudProvider.ID)
            .withAccount(description.getAccount())
            .withResource(EcsResource.class);
    CreateServiceRequest serviceRequest =
        makeServiceRequest(
            taskDefinitionArn,
            serverGroupName,
            description.getCapacity().getDesired(),
            namer,
            isTaggingEnabled(ecs));

    return UpdateServiceRequest.builder()
        .cluster(serviceRequest.cluster())
        .service(serviceRequest.serviceName())
        .taskDefinition(taskDefinitionArn)
        .desiredCount(serviceRequest.desiredCount())
        .networkConfiguration(serviceRequest.networkConfiguration())
        .serviceRegistries(serviceRequest.serviceRegistries())
        .placementConstraints(serviceRequest.placementConstraints())
        .placementStrategy(serviceRequest.placementStrategy())
        .capacityProviderStrategy(serviceRequest.capacityProviderStrategy())
        .platformVersion(serviceRequest.platformVersion())
        .healthCheckGracePeriodSeconds(serviceRequest.healthCheckGracePeriodSeconds())
        .enableExecuteCommand(serviceRequest.enableExecuteCommand())
        .loadBalancers(serviceRequest.loadBalancers())
        .deploymentConfiguration(serviceRequest.deploymentConfiguration())
        .forceNewDeployment(true);
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

    validateBlueGreenLoadBalancerConfig(nativeDescription, request.loadBalancers());

    AdvancedConfiguration advancedConfiguration = buildAdvancedConfiguration(nativeDescription);
    if (advancedConfiguration != null) {
      requestBuilder.loadBalancers(
          withAdvancedConfiguration(request.loadBalancers(), advancedConfiguration));
    }

    return requestBuilder.build();
  }

  /**
   * Fails fast when a {@code BLUE_GREEN} deploy attaches a load balancer but omits the ALB
   * traffic-shift config. AWS ECS requires an {@code advancedConfiguration} block on every load
   * balancer when the deployment strategy is {@code BLUE_GREEN}; without it the service
   * create/update is rejected with a 400 ({@code "advancedConfiguration field is required for all
   * loadBalancers when using the Blue/green deployment strategy"}). Catching it here turns an
   * opaque AWS-side failure into an actionable configuration error before the request is sent.
   *
   * <p>Only enforced when a load balancer is actually attached: {@code BLUE_GREEN} on a service
   * with no load balancer needs no {@code advancedConfiguration} and is left alone.
   */
  private static void validateBlueGreenLoadBalancerConfig(
      EcsNativeCreateServerGroupDescription nativeDescription, List<LoadBalancer> loadBalancers) {
    boolean isBlueGreen =
        BLUE_GREEN_STRATEGY.equalsIgnoreCase(
            StringUtils.trimToEmpty(nativeDescription.getDeploymentStrategy()));
    boolean hasLoadBalancer = loadBalancers != null && !loadBalancers.isEmpty();
    if (isBlueGreen && hasLoadBalancer && !hasAllAlbTrafficShiftFields(nativeDescription)) {
      throw new IllegalArgumentException(
          "The Blue/Green deployment strategy on a load-balanced ECS service requires the ALB"
              + " traffic-shift config: alternateTargetGroupArn, productionListenerRule,"
              + " testListenerRule and blueGreenRoleArn must all be set. AWS ECS rejects a"
              + " Blue/Green deploy whose load balancers have no advancedConfiguration. Provide all"
              + " four ARNs, or use the Rolling strategy for an in-place, single-target-group"
              + " deploy.");
    }
  }

  /**
   * In-place ({@code UpdateService}) equivalent of {@link #validateBlueGreenLoadBalancerConfig}.
   * The update path does not build the SDK {@link LoadBalancer} list, so this checks the
   * description's declared load-balancer intent (a {@code targetGroup} or any {@code
   * targetGroupMappings}) rather than a resolved list. AWS ECS applies the same rule to {@code
   * UpdateService}: a {@code BLUE_GREEN} strategy on a load-balanced service requires the ALB
   * traffic-shift config.
   */
  private static void validateBlueGreenInPlaceConfig(
      EcsNativeCreateServerGroupDescription nativeDescription) {
    boolean isBlueGreen =
        BLUE_GREEN_STRATEGY.equalsIgnoreCase(
            StringUtils.trimToEmpty(nativeDescription.getDeploymentStrategy()));
    if (isBlueGreen
        && descriptionDeclaresLoadBalancer(nativeDescription)
        && !hasAllAlbTrafficShiftFields(nativeDescription)) {
      throw new IllegalArgumentException(
          "The Blue/Green deployment strategy on a load-balanced ECS service requires the ALB"
              + " traffic-shift config: alternateTargetGroupArn, productionListenerRule,"
              + " testListenerRule and blueGreenRoleArn must all be set. AWS ECS rejects a"
              + " Blue/Green deploy whose load balancers have no advancedConfiguration. Provide all"
              + " four ARNs, or use the Rolling strategy for an in-place, single-target-group"
              + " deploy.");
    }
  }

  /** True when the description attaches a load balancer via {@code targetGroup} or mappings. */
  private static boolean descriptionDeclaresLoadBalancer(
      EcsNativeCreateServerGroupDescription nativeDescription) {
    return StringUtils.isNotBlank(nativeDescription.getTargetGroup())
        || (nativeDescription.getTargetGroupMappings() != null
            && !nativeDescription.getTargetGroupMappings().isEmpty());
  }

  /** True when all four ALB traffic-shift fields are set; false when all are unset. */
  private static boolean hasAllAlbTrafficShiftFields(
      EcsNativeCreateServerGroupDescription nativeDescription) {
    return StringUtils.isNotBlank(nativeDescription.getAlternateTargetGroupArn())
        && StringUtils.isNotBlank(nativeDescription.getProductionListenerRule())
        && StringUtils.isNotBlank(nativeDescription.getTestListenerRule())
        && StringUtils.isNotBlank(nativeDescription.getBlueGreenRoleArn());
  }

  /**
   * Builds the ALB traffic-shift config for a {@code BLUE_GREEN} deployment, or {@code null} when
   * the deploy doesn't use one. {@code BLUE_GREEN} only works without this config when the service
   * has no load balancer attached; ECS then stands up a new task set with no traffic to shift. As
   * soon as a load balancer is present, AWS ECS requires this config on it (enforced by {@link
   * #validateBlueGreenLoadBalancerConfig}). The four fields are all-or-nothing: they only make
   * sense together, so a partial set is a configuration mistake.
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
    if (!hasAllAlbTrafficShiftFields(nativeDescription)) {
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
   * region} field on {@code AbstractECSDescription} once that override is in the hierarchy. That's
   * fine for a normal deploy (availability zones are set), but an in-place redeploy triggered
   * without an availability-zone map would make the base override throw a {@code
   * NullPointerException}.
   *
   * <p>This override is defensive and flag-independent: prefer the availability-zone-derived region
   * (the normal case), and only fall back to the deploy-stage source region when the AZ map is
   * absent -- which is exactly the redeploy-with-source case that would otherwise NPE.
   */
  @Override
  protected String getRegion() {
    boolean hasAvailabilityZones =
        description.getAvailabilityZones() != null && !description.getAvailabilityZones().isEmpty();
    if (!hasAvailabilityZones
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
