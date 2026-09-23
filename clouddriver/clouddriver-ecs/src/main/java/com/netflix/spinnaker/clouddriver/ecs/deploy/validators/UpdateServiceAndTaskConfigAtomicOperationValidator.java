/*
 * Copyright 2018 Lookout, Inc.
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

package com.netflix.spinnaker.clouddriver.ecs.deploy.validators;

import com.netflix.spinnaker.clouddriver.deploy.ValidationErrors;
import com.netflix.spinnaker.clouddriver.ecs.EcsNativeOperation;
import com.netflix.spinnaker.clouddriver.ecs.EcsOperation;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeUpdateServiceDescription;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperations;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@EcsOperation(AtomicOperations.UPDATE_LAUNCH_CONFIG)
@EcsNativeOperation(AtomicOperations.UPDATE_LAUNCH_CONFIG)
@Component("updateServiceAndTaskConfigAtomicOperationValidator")
public class UpdateServiceAndTaskConfigAtomicOperationValidator extends CommonValidator {

  public UpdateServiceAndTaskConfigAtomicOperationValidator() {
    super("updateServiceAndTaskConfigDescription");
  }

  @Override
  public void validate(List priorDescriptions, Object description, ValidationErrors errors) {
    if (!(description instanceof EcsNativeUpdateServiceDescription)) {
      return;
    }

    EcsNativeUpdateServiceDescription nativeDescription =
        (EcsNativeUpdateServiceDescription) description;
    boolean validCredentials = validateCredentials(nativeDescription, errors, "credentials");
    if (validCredentials) {
      validateRegions(
          nativeDescription,
          Collections.singleton(nativeDescription.getRegion()),
          errors,
          "region");
    }
    if (StringUtils.isBlank(nativeDescription.getServerGroupName())) {
      rejectValue(errors, "serverGroupName", "not.nullable");
    }

    Integer minimumHealthyPercent = nativeDescription.getMinimumHealthyPercent();
    if (minimumHealthyPercent != null
        && (minimumHealthyPercent < 1 || minimumHealthyPercent > 100)) {
      rejectValue(errors, "minimumHealthyPercent", "invalid");
    }

    Integer maximumPercent = nativeDescription.getMaximumPercent();
    if (maximumPercent != null && maximumPercent < 100) {
      rejectValue(errors, "maximumPercent", "invalid");
    }
    if (minimumHealthyPercent != null
        && maximumPercent != null
        && maximumPercent < minimumHealthyPercent) {
      rejectValue(errors, "maximumPercent", "less.than.minimumHealthyPercent");
    }

    if (nativeDescription.getBakeTimeInMinutes() != null
        && nativeDescription.getBakeTimeInMinutes() < 0) {
      rejectValue(errors, "bakeTimeInMinutes", "invalid");
    }

    if (StringUtils.isNotBlank(nativeDescription.getDeploymentStrategy())
        && !StringUtils.equalsIgnoreCase(nativeDescription.getDeploymentStrategy(), "ROLLING")
        && !StringUtils.equalsIgnoreCase(nativeDescription.getDeploymentStrategy(), "BLUE_GREEN")) {
      rejectValue(errors, "deploymentStrategy", "invalid");
    }

    boolean hasAlarmNames =
        nativeDescription.getAlarmNames() != null
            && nativeDescription.getAlarmNames().stream().anyMatch(StringUtils::isNotBlank);
    if (nativeDescription.isEnableDeploymentAlarms() && !hasAlarmNames) {
      rejectValue(errors, "alarmNames", "not.nullable");
    }
  }
}
