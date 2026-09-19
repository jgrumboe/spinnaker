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

package com.netflix.spinnaker.orca.clouddriver.tasks.providers.ecs;

import com.netflix.spinnaker.kork.retrofit.Retrofit2SyncCall;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerHttpException;
import com.netflix.spinnaker.orca.api.pipeline.OverridableTimeoutRetryableTask;
import com.netflix.spinnaker.orca.api.pipeline.TaskResult;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.clouddriver.EcsNativeService;
import com.netflix.spinnaker.orca.clouddriver.model.EcsServiceDeploymentStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Waits on ECS's own native deployment/rollout state for a service (see {@link EcsNativeService}),
 * rather than only on instance health. Meant to be added as an explicit stage after an {@code
 * ecs-native} deploy/clone stage; it doesn't hook into the shared deploy stage's task graph, so
 * providers other than {@code ecs-native} are unaffected and existing pipelines need no changes.
 *
 * <p>Reads {@code account}, {@code region}, and {@code serverGroupName} directly from the stage
 * context; falls back to the {@code deploy.server.groups} output of the preceding deploy stage for
 * {@code serverGroupName}/{@code region} when not set explicitly, following the same convention
 * other post-deploy wait tasks use.
 */
@Slf4j
@Component
public class WaitForEcsNativeServiceDeploymentTask implements OverridableTimeoutRetryableTask {

  public static final String TASK_NAME = "waitForEcsNativeServiceDeployment";

  private static final long BACKOFF_PERIOD = TimeUnit.SECONDS.toMillis(10);
  private static final long TIMEOUT = TimeUnit.HOURS.toMillis(1);

  @Autowired private EcsNativeService ecsNativeService;

  @Override
  public long getBackoffPeriod() {
    return BACKOFF_PERIOD;
  }

  @Override
  public long getTimeout() {
    return TIMEOUT;
  }

  @Nonnull
  @Override
  public TaskResult execute(@Nonnull StageExecution stage) {
    Map<String, Object> context = stage.getContext();
    String account = (String) context.get("account");
    String region = resolveRegion(context);
    String serverGroupName = resolveServerGroupName(context, region);

    if (account == null || region == null || serverGroupName == null) {
      throw new IllegalArgumentException(
          "waitForEcsNativeServiceDeployment requires account, region, and serverGroupName "
              + "(either directly in the stage context, or via a preceding deploy stage's "
              + "deploy.server.groups output)");
    }

    try {
      EcsServiceDeploymentStatus status =
          Retrofit2SyncCall.execute(
              ecsNativeService.getServiceDeploymentStatus(account, region, serverGroupName));

      log.info(
          "ecs-native deployment status for {}: rolloutState={} reason={}",
          serverGroupName,
          status.getRolloutState(),
          status.getRolloutStateReason());

      if ("COMPLETED".equals(status.getRolloutState())) {
        return TaskResult.builder(ExecutionStatus.SUCCEEDED)
            .context("ecsNativeDeploymentStatus", status)
            .build();
      }
      if ("FAILED".equals(status.getRolloutState())) {
        // Covers both a plain failure and a deployment the circuit breaker already rolled back.
        return TaskResult.builder(ExecutionStatus.TERMINAL)
            .context("ecsNativeDeploymentStatus", status)
            .build();
      }
      // IN_PROGRESS, or any future state ECS adds: keep polling.
      return TaskResult.builder(ExecutionStatus.RUNNING)
          .context("ecsNativeDeploymentStatus", status)
          .build();
    } catch (SpinnakerHttpException e) {
      if (e.getResponseCode() == HttpStatus.NOT_FOUND.value()) {
        // The service or its PRIMARY deployment may not exist yet right after create; retry.
        return TaskResult.RUNNING;
      }
      throw e;
    }
  }

  private static String resolveRegion(Map<String, Object> context) {
    String region = (String) context.get("region");
    if (region != null) {
      return region;
    }
    Map<String, List<String>> serverGroupsByRegion = deployServerGroups(context);
    return serverGroupsByRegion == null || serverGroupsByRegion.isEmpty()
        ? null
        : serverGroupsByRegion.keySet().iterator().next();
  }

  private static String resolveServerGroupName(Map<String, Object> context, String region) {
    String serverGroupName = (String) context.get("serverGroupName");
    if (serverGroupName != null) {
      return serverGroupName;
    }
    Map<String, List<String>> serverGroupsByRegion = deployServerGroups(context);
    if (serverGroupsByRegion == null || region == null) {
      return null;
    }
    List<String> serverGroups = serverGroupsByRegion.get(region);
    return serverGroups == null || serverGroups.isEmpty() ? null : serverGroups.get(0);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> deployServerGroups(Map<String, Object> context) {
    return (Map<String, List<String>>)
        Optional.ofNullable(context.get("deploy.server.groups")).orElse(null);
  }
}
