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
import spock.lang.Specification

/**
 * EcsNativeServerGroupCreator overrides only getCloudProvider(), reusing all of
 * EcsServerGroupCreator's operation/artifact/image-resolution logic (covered by
 * EcsServerGroupCreatorSpec) unchanged. This just confirms the override -- the one thing that
 * makes a deploy/clone stage declaring cloudProvider: "ecs-native" route here instead of to the
 * original ecs creator -- actually took effect.
 */
class EcsNativeServerGroupCreatorSpec extends Specification {

  def "reports cloudProvider ecs-native so ecs-native deploy/clone stages route here"() {
    given:
    def creator = new EcsNativeServerGroupCreator(
        Stub(ArtifactUtils), Mock(OortService), new ContextParameterProcessor(), new RetrySupport())

    expect:
    creator.getCloudProvider() == "ecs-native"
  }

  def "still reports the ecs health provider name, since results are cached and viewed through the existing ECS read stack"() {
    given:
    def creator = new EcsNativeServerGroupCreator(
        Stub(ArtifactUtils), Mock(OortService), new ContextParameterProcessor(), new RetrySupport())

    expect:
    creator.getHealthProviderName() == Optional.of("ecs")
  }
}
