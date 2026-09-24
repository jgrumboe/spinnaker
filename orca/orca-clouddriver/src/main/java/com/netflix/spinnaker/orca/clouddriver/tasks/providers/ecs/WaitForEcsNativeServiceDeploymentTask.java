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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WaitForEcsNativeServiceDeploymentTask implements OverridableTimeoutRetryableTask {

  public static final String TASK_NAME = "waitForEcsNativeServiceDeployment";
  public static final String EXPECTED_TASK_DEFINITION = "ecsNativeExpectedTaskDefinition";

  private static final long BACKOFF_PERIOD = TimeUnit.SECONDS.toMillis(10);
  private static final long TIMEOUT = TimeUnit.HOURS.toMillis(1);
  private static final Set<String> TERMINAL_FAILURE_STATUSES =
      Set.of("ROLLBACK_SUCCESSFUL", "ROLLBACK_FAILED", "STOPPED", "FAILED");

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
    if (account == null) {
      account = (String) context.get("credentials");
    }
    String region = resolveRegion(context);
    String serverGroupName = resolveServerGroupName(context, region);
    String expectedTaskDefinition = resolveExpectedTaskDefinition(context);

    if (account == null || region == null || serverGroupName == null) {
      throw new IllegalArgumentException(
          "waitForEcsNativeServiceDeployment requires account, region, and serverGroupName "
              + "(either directly in the stage context, or via a preceding deploy stage's "
              + "deploy.server.groups output)");
    }
    if (expectedTaskDefinition == null || expectedTaskDefinition.isBlank()) {
      throw new IllegalArgumentException(
          "waitForEcsNativeServiceDeployment requires ecsNativeExpectedTaskDefinition; "
              + "deployment status cannot safely select an unpinned PRIMARY deployment");
    }

    try {
      EcsServiceDeploymentStatus status =
          Retrofit2SyncCall.execute(
              ecsNativeService.getServiceDeploymentStatus(
                  account, region, serverGroupName, expectedTaskDefinition));

      String serviceDeploymentStatus =
          status.getStatus() != null ? status.getStatus() : status.getRolloutState();
      log.info(
          "ecs-native service deployment {} for {}: status={} lifecycleStage={} reason={}",
          status.getServiceDeploymentArn(),
          serverGroupName,
          serviceDeploymentStatus,
          status.getLifecycleStage(),
          status.getStatusReason() != null
              ? status.getStatusReason()
              : status.getRolloutStateReason());

      if (status.getTargetTaskDefinition() != null
          && !expectedTaskDefinition.equals(status.getTargetTaskDefinition())) {
        log.warn(
            "ecs-native service deployment {} targets {}, expected {}; treating as terminal",
            status.getServiceDeploymentArn(),
            status.getTargetTaskDefinition(),
            expectedTaskDefinition);
        return TaskResult.builder(ExecutionStatus.TERMINAL)
            .context("ecsNativeDeploymentStatus", status)
            .build();
      }

      if ("SUCCESSFUL".equals(serviceDeploymentStatus)) {
        return TaskResult.builder(ExecutionStatus.SUCCEEDED)
            .context("ecsNativeDeploymentStatus", status)
            .build();
      }
      if (TERMINAL_FAILURE_STATUSES.contains(serviceDeploymentStatus)) {
        return TaskResult.builder(ExecutionStatus.TERMINAL)
            .context("ecsNativeDeploymentStatus", status)
            .build();
      }
      // PENDING, IN_PROGRESS, ROLLBACK_REQUESTED, ROLLBACK_IN_PROGRESS, STOP_REQUESTED,
      // STOP_IN_PROGRESS, and unknown future states remain running. In particular, a completed
      // rollback is terminal and can never be accepted as success for the requested deployment.
      return TaskResult.builder(ExecutionStatus.RUNNING)
          .context("ecsNativeDeploymentStatus", status)
          .build();
    } catch (SpinnakerHttpException e) {
      if (e.getResponseCode() == HttpStatus.NOT_FOUND.value()) {
        // The service deployment may not be visible immediately after create/update; retry.
        return TaskResult.RUNNING;
      }
      throw e;
    }
  }

  private static String resolveExpectedTaskDefinition(Map<String, Object> context) {
    String expected = (String) context.get(EXPECTED_TASK_DEFINITION);
    return expected != null ? expected : (String) context.get("expectedTaskDefinition");
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
