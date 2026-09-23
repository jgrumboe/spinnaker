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

package com.netflix.spinnaker.clouddriver.ecs.deploy.validators;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netflix.spinnaker.clouddriver.deploy.DescriptionValidationErrors;
import com.netflix.spinnaker.clouddriver.deploy.DescriptionValidator;
import com.netflix.spinnaker.clouddriver.ecs.EcsNativeCloudProvider;
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeCreateServerGroupDescription;
import com.netflix.spinnaker.clouddriver.orchestration.AnnotationsBasedAtomicOperationsRegistry;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperations;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

class EcsNativeDescriptionValidationTest {

  @Test
  void nativeCreateUsesNativeValidatorThroughProviderRegistry() {
    try (AnnotationConfigApplicationContext applicationContext =
        new AnnotationConfigApplicationContext()) {
      applicationContext.registerBean(EcsCreateServerGroupDescriptionValidator.class);
      applicationContext.refresh();

      AnnotationsBasedAtomicOperationsRegistry registry =
          new AnnotationsBasedAtomicOperationsRegistry();
      ReflectionTestUtils.setField(registry, "applicationContext", applicationContext);
      ReflectionTestUtils.setField(
          registry, "cloudProviders", Collections.singletonList(new EcsNativeCloudProvider()));

      String validatorName =
          DescriptionValidator.getValidatorName(AtomicOperations.CREATE_SERVER_GROUP);
      DescriptionValidator validator =
          registry.getAtomicOperationDescriptionValidator(validatorName, EcsNativeCloudProvider.ID);
      assertNotNull(validator);

      EcsNativeCreateServerGroupDescription description =
          new EcsNativeCreateServerGroupDescription();
      description.setMinimumHealthyPercent(0);
      DescriptionValidationErrors errors = new DescriptionValidationErrors(description);

      validator.validate(Collections.emptyList(), description, errors);

      assertTrue(
          errors.getFieldErrors().stream()
              .anyMatch(error -> "minimumHealthyPercent".equals(error.getField())));
    }
  }
}
