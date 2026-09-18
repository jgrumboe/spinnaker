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

package com.netflix.spinnaker.clouddriver.ecs.provider.view

import com.netflix.spinnaker.clouddriver.ecs.EcsNativeCloudProvider
import com.netflix.spinnaker.clouddriver.ecs.model.EcsServerCluster
import com.netflix.spinnaker.clouddriver.model.ServerGroup
import spock.lang.Specification

class EcsNativeServerClusterProviderSpec extends Specification {

  def delegate = Mock(EcsServerClusterProvider)
  def provider = new EcsNativeServerClusterProvider(delegate)

  def 'reports the ecs-native cloud provider id regardless of the delegate'() {
    expect:
    provider.getCloudProviderId() == EcsNativeCloudProvider.ID
    provider.getCloudProviderId() != delegate.getCloudProviderId()
  }

  def 'forwards every read method to the delegate unchanged'() {
    given:
    def cluster = Mock(EcsServerCluster)
    def serverGroup = Mock(ServerGroup)

    when:
    provider.getClusters()
    provider.getClusterSummaries('myapp')
    provider.getClusterDetails('myapp')
    provider.getClusters('myapp', 'test')
    provider.getClusters('myapp', 'test', true)
    provider.getCluster('myapp', 'test', 'myapp-cluster')
    provider.getCluster('myapp', 'test', 'myapp-cluster', true)
    provider.getServerGroup('test', 'us-east-1', 'myapp-v001')
    provider.getServerGroup('test', 'us-east-1', 'myapp-v001', true)
    provider.supportsMinimalClusters()

    then:
    1 * delegate.getClusters()
    1 * delegate.getClusterSummaries('myapp')
    1 * delegate.getClusterDetails('myapp')
    1 * delegate.getClusters('myapp', 'test')
    1 * delegate.getClusters('myapp', 'test', true)
    1 * delegate.getCluster('myapp', 'test', 'myapp-cluster')
    1 * delegate.getCluster('myapp', 'test', 'myapp-cluster', true)
    1 * delegate.getServerGroup('test', 'us-east-1', 'myapp-v001')
    1 * delegate.getServerGroup('test', 'us-east-1', 'myapp-v001', true)
    1 * delegate.supportsMinimalClusters()
  }
}
