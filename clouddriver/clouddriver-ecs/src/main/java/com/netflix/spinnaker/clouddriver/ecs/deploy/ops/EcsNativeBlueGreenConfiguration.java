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

import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeCreateServerGroupDescription;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.services.ecs.model.AdvancedConfiguration;
import software.amazon.awssdk.services.ecs.model.LoadBalancer;

/** Validates and builds the ALB traffic-shift settings for native blue/green deployments. */
final class EcsNativeBlueGreenConfiguration {

  private static final String BLUE_GREEN_STRATEGY = "BLUE_GREEN";

  private EcsNativeBlueGreenConfiguration() {}

  static void validateLoadBalancerConfig(
      EcsNativeCreateServerGroupDescription description, List<LoadBalancer> loadBalancers) {
    boolean isBlueGreen = isBlueGreen(description.getDeploymentStrategy());
    boolean hasLoadBalancer = loadBalancers != null && !loadBalancers.isEmpty();
    if (isBlueGreen && hasLoadBalancer && !hasAllTrafficShiftFields(description)) {
      throw missingTrafficShiftConfiguration();
    }
  }

  static void validateInPlaceConfig(EcsNativeCreateServerGroupDescription description) {
    if (isBlueGreen(description.getDeploymentStrategy())
        && descriptionDeclaresLoadBalancer(description)
        && !hasAllTrafficShiftFields(description)) {
      throw missingTrafficShiftConfiguration();
    }
  }

  static AdvancedConfiguration buildAdvancedConfiguration(
      EcsNativeCreateServerGroupDescription description) {
    boolean anySet =
        StringUtils.isNotBlank(description.getAlternateTargetGroupArn())
            || StringUtils.isNotBlank(description.getProductionListenerRule())
            || StringUtils.isNotBlank(description.getTestListenerRule())
            || StringUtils.isNotBlank(description.getBlueGreenRoleArn());
    if (!anySet) {
      return null;
    }
    if (!hasAllTrafficShiftFields(description)) {
      throw new IllegalArgumentException(
          "alternateTargetGroupArn, productionListenerRule, testListenerRule and"
              + " blueGreenRoleArn must all be set together to configure a blue/green ALB"
              + " traffic shift, or all left unset to skip it.");
    }
    return AdvancedConfiguration.builder()
        .alternateTargetGroupArn(description.getAlternateTargetGroupArn())
        .productionListenerRule(description.getProductionListenerRule())
        .testListenerRule(description.getTestListenerRule())
        .roleArn(description.getBlueGreenRoleArn())
        .build();
  }

  static List<LoadBalancer> withAdvancedConfiguration(
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

  private static boolean isBlueGreen(String strategy) {
    return BLUE_GREEN_STRATEGY.equalsIgnoreCase(StringUtils.trimToEmpty(strategy));
  }

  private static boolean descriptionDeclaresLoadBalancer(
      EcsNativeCreateServerGroupDescription description) {
    return StringUtils.isNotBlank(description.getTargetGroup())
        || (description.getTargetGroupMappings() != null
            && !description.getTargetGroupMappings().isEmpty());
  }

  private static boolean hasAllTrafficShiftFields(
      EcsNativeCreateServerGroupDescription description) {
    return StringUtils.isNotBlank(description.getAlternateTargetGroupArn())
        && StringUtils.isNotBlank(description.getProductionListenerRule())
        && StringUtils.isNotBlank(description.getTestListenerRule())
        && StringUtils.isNotBlank(description.getBlueGreenRoleArn());
  }

  private static IllegalArgumentException missingTrafficShiftConfiguration() {
    return new IllegalArgumentException(
        "The Blue/Green deployment strategy on a load-balanced ECS service requires the ALB"
            + " traffic-shift config: alternateTargetGroupArn, productionListenerRule,"
            + " testListenerRule and blueGreenRoleArn must all be set. AWS ECS rejects a"
            + " Blue/Green deploy whose load balancers have no advancedConfiguration. Provide all"
            + " four ARNs, or use the Rolling strategy for an in-place, single-target-group"
            + " deploy.");
  }
}
