/*
 * Copyright 2026 Spinnaker contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.providers.ecs

import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.orca.clouddriver.OortService
import com.netflix.spinnaker.orca.pipeline.util.ArtifactUtils
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * ServerGroupCreator for the second, opt-in ECS provider ("ecs-native").
 *
 * <p>It reuses all of the {@link EcsServerGroupCreator} operation/artifact/image-resolution logic
 * and differs only in the cloud-provider id it reports, so that a deploy/clone stage declaring
 * {@code cloudProvider: "ecs-native"} is routed here (and, in turn, to the clouddriver
 * {@code @EcsNativeOperation} converters). The health provider name is inherited as "ecs" because
 * the resulting services are cached and viewed through the existing ECS read stack.
 */
@Slf4j
@Component
class EcsNativeServerGroupCreator extends EcsServerGroupCreator {

  @Autowired
  EcsNativeServerGroupCreator(
      ArtifactUtils artifactUtils,
      OortService oort,
      ContextParameterProcessor contextParameterProcessor,
      RetrySupport retrySupport) {
    super(artifactUtils, oort, contextParameterProcessor, retrySupport)
  }

  @Override
  String getCloudProvider() {
    return "ecs-native"
  }
}
