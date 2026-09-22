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

import com.netflix.spinnaker.kork.artifacts.model.Artifact
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType
import com.netflix.spinnaker.orca.clouddriver.OortService
import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl
import com.netflix.spinnaker.orca.pipeline.util.ArtifactUtils
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor
import okhttp3.MediaType
import okhttp3.ResponseBody
import retrofit2.mock.Calls
import spock.lang.Specification

import static com.netflix.spinnaker.orca.test.model.ExecutionBuilder.stage

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

  // Regression: getOperations()'s evaluateTaskDefinitionArtifactExpressions path dereferences the
  // inherited oortService field inside the fetchAndParseArtifact closure. When that field was
  // `private` on EcsServerGroupCreator, running from this subclass threw
  // "MissingPropertyException: No such property: oortService for class: EcsNativeServerGroupCreator"
  // because Groovy resolves closure property access against the runtime class, which can't see a
  // private superclass field. This exercises the full path through the subclass to guard the fix.
  def "resolves task definition artifact from the subclass without a MissingPropertyException"() {
    given:
    ArtifactUtils mockResolver = Stub(ArtifactUtils)
    OortService oortService = Mock()
    def creator = new EcsNativeServerGroupCreator(
        mockResolver, oortService, new ContextParameterProcessor(), new RetrySupport())

    def testArtifactId = "aaaa-bbbb-cccc-dddd"
    def taskDefArtifact = [artifactId: testArtifactId]
    Artifact resolvedArtifact = Artifact.builder().type('s3/object').name('s3://testfile.json').build()
    mockResolver.getBoundArtifactForStage(_, testArtifactId, null) >> resolvedArtifact

    def testDescription = [fromTrigger: "true", registry: "myregistry.io", repository: "myrepo", tag: "latest"]
    def testMappings = [
        [containerName: "web", imageDescription: testDescription],
        [containerName: "logs", imageDescription: testDescription],
    ]

    def stage = stage {}
    stage.execution = new PipelineExecutionImpl(ExecutionType.PIPELINE, 'ecs')
    stage.context.credentials = "testUser"
    stage.context.application = "ecs"
    stage.context.useTaskDefinitionArtifact = true
    stage.context.evaluateTaskDefinitionArtifactExpressions = true
    stage.context.taskDefinitionArtifact = taskDefArtifact
    stage.context.containerMappings = testMappings
    stage.execution.trigger.parameters.put("tg", "bar")

    when:
    def operations = creator.getOperations(stage)

    then:
    1 * oortService.fetchArtifact(*_) >> Calls.response(
        ResponseBody.create(MediaType.parse("application/json"), '{"foo": "${ parameters[\'tg\'] }"}'))
    0 * oortService._
    operations[0].createServerGroup.spelProcessedTaskDefinitionArtifact.toString() == "[foo:bar]"
  }
}
