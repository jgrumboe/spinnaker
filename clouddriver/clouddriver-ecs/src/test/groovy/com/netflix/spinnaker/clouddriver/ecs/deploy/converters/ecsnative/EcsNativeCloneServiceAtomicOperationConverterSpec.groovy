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

package com.netflix.spinnaker.clouddriver.ecs.deploy.converters.ecsnative

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.CloneServiceDescription
import com.netflix.spinnaker.clouddriver.ecs.deploy.ops.CloneServiceAtomicOperation
import com.netflix.spinnaker.clouddriver.security.AbstractAtomicOperationsCredentialsSupport

class EcsNativeCloneServiceAtomicOperationConverterSpec
    extends EcsNativeCredentialsOnlyAtomicOperationConverterSpec<CloneServiceDescription, CloneServiceAtomicOperation> {
  @Override
  AbstractAtomicOperationsCredentialsSupport getConverter() {
    new EcsNativeCloneServiceAtomicOperationConverter(objectMapper: new ObjectMapper())
  }

  @Override
  Class<CloneServiceDescription> getDescriptionType() {
    CloneServiceDescription
  }

  @Override
  Class<CloneServiceAtomicOperation> getOperationType() {
    CloneServiceAtomicOperation
  }
}
