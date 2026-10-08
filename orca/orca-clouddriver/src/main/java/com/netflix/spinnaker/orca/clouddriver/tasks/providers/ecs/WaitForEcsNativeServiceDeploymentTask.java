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
import java.util.Collection;
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
  public static final String EXPECTED_SERVICE_DEPLOYMENT_ARN =
      "ecsNativeExpectedServiceDeploymentArn";

  private static final long BACKOFF_PERIOD = TimeUnit.SECONDS.toMillis(10);
  private static final long TIMEOUT = TimeUnit.HOURS.toMillis(1);
  private static final String WAIT_FOR_LIFECYCLE_GATE = "ecsNativeWaitForLifecycleGate";
  private static final String WAIT_FOR_STOPPED = "ecsNativeWaitForStopped";
  private static final String ACCEPT_ROLLBACK = "ecsNativeAcceptRollback";
  private static final String LIFECYCLE_GATE_STATUS = "IN_PROGRESS";
  private static final String HOOK_AWAITING_ACTION = "AWAITING_ACTION";
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
    String expectedServiceDeploymentArn = resolveExpectedServiceDeploymentArn(stage);

    if (account == null || region == null || serverGroupName == null) {
      throw new IllegalArgumentException(
          "waitForEcsNativeServiceDeployment requires account, region, and serverGroupName "
              + "(either directly in the stage context, or via a preceding deploy stage's "
              + "deploy.server.groups output)");
    }
    if (expectedServiceDeploymentArn == null || expectedServiceDeploymentArn.isBlank()) {
      throw new IllegalArgumentException(
          "waitForEcsNativeServiceDeployment requires ecsNativeExpectedServiceDeploymentArn; "
              + "deployment status cannot safely select an unpinned service deployment");
    }

    try {
      EcsServiceDeploymentStatus status =
          Retrofit2SyncCall.execute(
              ecsNativeService.getServiceDeploymentStatus(
                  account, region, serverGroupName, expectedServiceDeploymentArn));

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

      if (!expectedServiceDeploymentArn.equals(status.getServiceDeploymentArn())) {
        log.warn(
            "ecs-native service deployment response identified {}, expected {}; treating as terminal",
            status.getServiceDeploymentArn(),
            expectedServiceDeploymentArn);
        return TaskResult.builder(ExecutionStatus.TERMINAL)
            .context("ecsNativeDeploymentStatus", status)
            .build();
      }

      if ("SUCCESSFUL".equals(serviceDeploymentStatus)) {
        return TaskResult.builder(ExecutionStatus.SUCCEEDED)
            .context("ecsNativeDeploymentStatus", status)
            .build();
      }
      // A configured PAUSE lifecycle hook is the only point where ECS waits for a Continue. The
      // deploy stage is done once the hook awaits action; ECS applies the hook's timeout action
      // if nobody acts, so a downstream Continue stage must exist.
      if (Boolean.TRUE.equals(context.get(WAIT_FOR_LIFECYCLE_GATE))
          && LIFECYCLE_GATE_STATUS.equals(serviceDeploymentStatus)
          && hasHookAwaitingAction(status)) {
        return TaskResult.builder(ExecutionStatus.SUCCEEDED)
            .context("ecsNativeDeploymentStatus", status)
            .build();
      }
      // A user-requested rollback (Continue with ROLLBACK, Stop) legitimately ends in
      // ROLLBACK_SUCCESSFUL.
      if ("ROLLBACK_SUCCESSFUL".equals(serviceDeploymentStatus)
          && Boolean.TRUE.equals(context.get(ACCEPT_ROLLBACK))) {
        return TaskResult.builder(ExecutionStatus.SUCCEEDED)
            .context("ecsNativeDeploymentStatus", status)
            .build();
      }
      if (Boolean.TRUE.equals(context.get(WAIT_FOR_STOPPED))
          && "STOPPED".equals(serviceDeploymentStatus)) {
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

  private static boolean hasHookAwaitingAction(EcsServiceDeploymentStatus status) {
    return status.getLifecycleHookDetails() != null
        && status.getLifecycleHookDetails().stream()
            .anyMatch(hook -> HOOK_AWAITING_ACTION.equals(hook.getStatus()));
  }

  public static String resolveExpectedServiceDeploymentArn(StageExecution stage) {
    Map<String, Object> context = stage.getContext();
    Collection<String> references = stage.getRequisiteStageRefIds();
    Object contextReferences = stage.getContext().get("requisiteStageRefIds");
    if (contextReferences instanceof Collection) {
      references = ((Collection<?>) contextReferences).stream().map(Object::toString).toList();
    }
    Object explicitReference = context.get("deploymentStageRefId");
    if (explicitReference instanceof String && !((String) explicitReference).isBlank()) {
      references = List.of((String) explicitReference);
    }
    if (references != null) {
      for (String reference : references) {
        Optional<StageExecution> precedingStage =
            stage.getExecution().getStages().stream()
                .filter(candidate -> reference.equals(candidate.getRefId()))
                .findFirst();
        if (precedingStage.isPresent()) {
          String resolved = deploymentArn(precedingStage.get().getOutputs());
          if (resolved == null) {
            resolved = deploymentArn(precedingStage.get().getContext());
          }
          if (resolved != null) {
            return resolved;
          }
        }
      }
    }
    return deploymentArn(context);
  }

  private static String deploymentArn(Map<String, Object> values) {
    Object expected = values.get(EXPECTED_SERVICE_DEPLOYMENT_ARN);
    if (expected == null) {
      expected = values.get("expectedServiceDeploymentArn");
    }
    return expected instanceof String && !((String) expected).isBlank() ? (String) expected : null;
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
