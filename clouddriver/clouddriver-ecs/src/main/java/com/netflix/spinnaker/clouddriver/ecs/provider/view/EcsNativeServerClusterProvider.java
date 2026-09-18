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

package com.netflix.spinnaker.clouddriver.ecs.provider.view;

import com.netflix.spinnaker.clouddriver.ecs.EcsNativeCloudProvider;
import com.netflix.spinnaker.clouddriver.ecs.model.EcsServerCluster;
import com.netflix.spinnaker.clouddriver.model.ClusterProvider;
import com.netflix.spinnaker.clouddriver.model.ServerGroup;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Exposes the same ECS clusters/server groups/service views as {@link EcsServerClusterProvider}
 * under the {@code ecs-native} cloud provider id.
 *
 * <p>The {@code ecs-native} provider deliberately reuses the original ECS caching agents and read
 * views (the running resources are the same ECS services regardless of which provider deployed
 * them) — only the write path differs. But clouddriver's read APIs (e.g. {@code ClusterController})
 * and orca's polling calls ({@code OortService#getServerGroupFromCluster}, {@code
 * #getTargetServerGroup}, etc.) select a {@link ClusterProvider} by an exact match on {@link
 * ClusterProvider#getCloudProviderId()}. Without this class, a stage declaring {@code
 * cloudProvider: "ecs-native"} would deploy successfully but then fail to resolve its own server
 * group afterwards, since the only registered ECS {@link ClusterProvider} answers to {@code "ecs"}.
 *
 * <p>This class changes no behavior of the original provider: it is a pure delegate that forwards
 * every call to the existing {@link EcsServerClusterProvider} bean.
 */
@Component
public class EcsNativeServerClusterProvider implements ClusterProvider<EcsServerCluster> {

  private final EcsServerClusterProvider delegate;

  @Autowired
  public EcsNativeServerClusterProvider(EcsServerClusterProvider delegate) {
    this.delegate = delegate;
  }

  @Override
  public Map<String, Set<EcsServerCluster>> getClusters() {
    return delegate.getClusters();
  }

  @Override
  public Map<String, Set<EcsServerCluster>> getClusterSummaries(String application) {
    return delegate.getClusterSummaries(application);
  }

  @Override
  public Map<String, Set<EcsServerCluster>> getClusterDetails(String application) {
    return delegate.getClusterDetails(application);
  }

  @Override
  public Set<EcsServerCluster> getClusters(String application, String account) {
    return delegate.getClusters(application, account);
  }

  @Override
  public Set<EcsServerCluster> getClusters(
      String application, String account, boolean includeDetails) {
    return delegate.getClusters(application, account, includeDetails);
  }

  @Nullable
  @Override
  public EcsServerCluster getCluster(String application, String account, String name) {
    return delegate.getCluster(application, account, name);
  }

  @Nullable
  @Override
  public EcsServerCluster getCluster(
      String application, String account, String name, boolean includeDetails) {
    return delegate.getCluster(application, account, name, includeDetails);
  }

  @Nullable
  @Override
  public ServerGroup getServerGroup(
      String account, String region, String name, boolean includeDetails) {
    return delegate.getServerGroup(account, region, name, includeDetails);
  }

  @Nullable
  @Override
  public ServerGroup getServerGroup(String account, String region, String name) {
    return delegate.getServerGroup(account, region, name);
  }

  @Override
  public String getCloudProviderId() {
    return EcsNativeCloudProvider.ID;
  }

  @Override
  public boolean supportsMinimalClusters() {
    return delegate.supportsMinimalClusters();
  }
}
