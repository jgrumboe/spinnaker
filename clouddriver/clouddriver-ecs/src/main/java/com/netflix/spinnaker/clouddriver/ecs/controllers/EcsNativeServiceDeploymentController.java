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

package com.netflix.spinnaker.clouddriver.ecs.controllers;

import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider;
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAmazonCredentials;
import com.netflix.spinnaker.clouddriver.ecs.cache.client.ServiceCacheClient;
import com.netflix.spinnaker.clouddriver.ecs.cache.model.Service;
import com.netflix.spinnaker.clouddriver.ecs.model.EcsServiceDeploymentStatus;
import com.netflix.spinnaker.clouddriver.ecs.model.EcsTaskDefinitionRevision;
import com.netflix.spinnaker.clouddriver.ecs.security.NetflixECSCredentials;
import com.netflix.spinnaker.credentials.CredentialsRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.Deployment;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.ListTaskDefinitionsRequest;
import software.amazon.awssdk.services.ecs.model.ListTaskDefinitionsResponse;
import software.amazon.awssdk.services.ecs.model.SortOrder;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.model.TaskDefinitionStatus;

/**
 * Exposes ECS's own native deployment/rollout state for a service, live from {@code
 * DescribeServices} (not the cache, since a polling wait task needs current state). Standalone
 * addition for {@code ecs-native}; the existing {@link
 * com.netflix.spinnaker.clouddriver.ecs.controllers.servergroup.EcsServerGroupController} and its
 * cache-backed views are untouched. Modeled on that controller's existing live-AWS-call pattern for
 * {@code /events}.
 */
@RestController
@RequestMapping("/ecs-native/serverGroups/{account}/{region}/{serverGroupName}")
public class EcsNativeServiceDeploymentController {

  private final CredentialsRepository<NetflixECSCredentials> credentialsRepository;
  private final AmazonClientProvider amazonClientProvider;
  private final ServiceCacheClient serviceCacheClient;

  @Autowired
  public EcsNativeServiceDeploymentController(
      CredentialsRepository<NetflixECSCredentials> credentialsRepository,
      AmazonClientProvider amazonClientProvider,
      ServiceCacheClient serviceCacheClient) {
    this.credentialsRepository = credentialsRepository;
    this.amazonClientProvider = amazonClientProvider;
    this.serviceCacheClient = serviceCacheClient;
  }

  @RequestMapping(value = "/deploymentStatus", method = RequestMethod.GET)
  ResponseEntity<?> getDeploymentStatus(
      @PathVariable String account,
      @PathVariable String region,
      @PathVariable String serverGroupName) {
    NetflixAmazonCredentials credentials = credentialsRepository.getOne(account);
    if (credentials == null) {
      return new ResponseEntity<>(
          String.format("Account %s is not an ECS account", account), HttpStatus.BAD_REQUEST);
    }

    Optional<Service> cachedService =
        serviceCacheClient.getAll(account, region).stream()
            .filter(service -> service.getServiceName().equals(serverGroupName))
            .findFirst();
    if (cachedService.isEmpty()) {
      return new ResponseEntity<>(
          String.format(
              "Server group %s was not found in account %s / region %s",
              serverGroupName, account, region),
          HttpStatus.NOT_FOUND);
    }

    EcsClient ecs = amazonClientProvider.getAmazonEcsV2(credentials, region);
    DescribeServicesResponse result =
        ecs.describeServices(
            DescribeServicesRequest.builder()
                .services(serverGroupName)
                .cluster(cachedService.get().getClusterArn())
                .build());

    if (result.services().isEmpty()) {
      return new ResponseEntity<>(
          String.format(
              "Server group %s was not found in ECS for account %s / region %s",
              serverGroupName, account, region),
          HttpStatus.NOT_FOUND);
    }

    List<Deployment> deployments = result.services().get(0).deployments();
    Optional<Deployment> primaryDeployment =
        deployments.stream().filter(d -> "PRIMARY".equals(d.status())).findFirst();
    if (primaryDeployment.isEmpty()) {
      return new ResponseEntity<>(
          String.format("No PRIMARY deployment found for server group %s", serverGroupName),
          HttpStatus.NOT_FOUND);
    }

    return new ResponseEntity<>(
        toStatus(serverGroupName, cachedService.get().getClusterArn(), primaryDeployment.get()),
        HttpStatus.OK);
  }

  /**
   * The most task-definition revisions to describe (for their container images) when building the
   * rollback picker. ECS returns revisions newest-first, so this caps how far back the picker
   * looks; older revisions are rarely useful rollback targets and each one costs a {@code
   * DescribeTaskDefinition} call.
   */
  static final int MAX_REVISIONS = 50;

  /**
   * Lists the {@code ACTIVE} task-definition revisions for the service's family, newest first, for
   * the {@code ecs-native} rollback picker. Live from {@code ListTaskDefinitions} + {@code
   * DescribeTaskDefinition} (not the cache): the cache only ever holds revisions currently
   * referenced by a live service and is authoritatively pruned, so it cannot enumerate a family's
   * history.
   */
  @RequestMapping(value = "/taskDefinitions", method = RequestMethod.GET)
  ResponseEntity<?> listTaskDefinitions(
      @PathVariable String account,
      @PathVariable String region,
      @PathVariable String serverGroupName) {
    NetflixAmazonCredentials credentials = credentialsRepository.getOne(account);
    if (credentials == null) {
      return new ResponseEntity<>(
          String.format("Account %s is not an ECS account", account), HttpStatus.BAD_REQUEST);
    }

    Optional<Service> cachedService =
        serviceCacheClient.getAll(account, region).stream()
            .filter(service -> service.getServiceName().equals(serverGroupName))
            .findFirst();
    if (cachedService.isEmpty()) {
      return new ResponseEntity<>(
          String.format(
              "Server group %s was not found in account %s / region %s",
              serverGroupName, account, region),
          HttpStatus.NOT_FOUND);
    }

    String currentTaskDefinitionArn = cachedService.get().getTaskDefinition();
    String family = familyFromTaskDefinitionArn(currentTaskDefinitionArn);
    if (family == null) {
      return new ResponseEntity<>(
          String.format(
              "Could not determine the task-definition family for server group %s (task definition: %s)",
              serverGroupName, currentTaskDefinitionArn),
          HttpStatus.NOT_FOUND);
    }

    EcsClient ecs = amazonClientProvider.getAmazonEcsV2(credentials, region);

    List<String> revisionArns = new ArrayList<>();
    String nextToken = null;
    do {
      ListTaskDefinitionsResponse listed =
          ecs.listTaskDefinitions(
              ListTaskDefinitionsRequest.builder()
                  .familyPrefix(family)
                  .status(TaskDefinitionStatus.ACTIVE)
                  .sort(SortOrder.DESC)
                  .nextToken(nextToken)
                  .build());
      revisionArns.addAll(listed.taskDefinitionArns());
      nextToken = listed.nextToken();
    } while (nextToken != null && revisionArns.size() < MAX_REVISIONS);

    List<EcsTaskDefinitionRevision> revisions = new ArrayList<>();
    for (String arn : revisionArns.subList(0, Math.min(revisionArns.size(), MAX_REVISIONS))) {
      TaskDefinition taskDefinition =
          ecs.describeTaskDefinition(
                  DescribeTaskDefinitionRequest.builder().taskDefinition(arn).build())
              .taskDefinition();
      boolean isCurrent = normalizeArn(arn).equals(normalizeArn(currentTaskDefinitionArn));
      revisions.add(toRevision(taskDefinition, isCurrent));
    }

    return new ResponseEntity<>(revisions, HttpStatus.OK);
  }

  private static EcsTaskDefinitionRevision toRevision(
      TaskDefinition taskDefinition, boolean current) {
    List<String> images = new ArrayList<>();
    for (ContainerDefinition container : taskDefinition.containerDefinitions()) {
      images.add(container.image());
    }
    return EcsTaskDefinitionRevision.builder()
        .taskDefinitionArn(taskDefinition.taskDefinitionArn())
        .family(taskDefinition.family())
        .revision(taskDefinition.revision())
        .containerImages(images)
        .current(current)
        .build();
  }

  /**
   * Extracts the family from a task-definition ARN or {@code family:revision} string. ECS ARNs are
   * {@code arn:aws:ecs:region:account:task-definition/family:revision}; the family is the segment
   * after {@code task-definition/} and before the trailing {@code :revision}. Returns null when the
   * input is blank or has no recognizable family.
   */
  static String familyFromTaskDefinitionArn(String taskDefinitionArn) {
    if (StringUtils.isBlank(taskDefinitionArn)) {
      return null;
    }
    String afterSlash = StringUtils.substringAfterLast(taskDefinitionArn, "/");
    String familyAndRevision = StringUtils.isBlank(afterSlash) ? taskDefinitionArn : afterSlash;
    String family = StringUtils.substringBeforeLast(familyAndRevision, ":");
    return StringUtils.isBlank(family) ? null : family;
  }

  /**
   * The cached current task definition may be stored as {@code family:revision} rather than a full
   * ARN, while {@code ListTaskDefinitions} returns full ARNs. To flag the current revision we
   * compare on {@code family:revision}, so reduce a full ARN to that suffix.
   */
  private static String normalizeArn(String taskDefinitionArn) {
    if (StringUtils.isBlank(taskDefinitionArn)) {
      return taskDefinitionArn;
    }
    String afterSlash = StringUtils.substringAfterLast(taskDefinitionArn, "/");
    return StringUtils.isBlank(afterSlash) ? taskDefinitionArn : afterSlash;
  }

  private static EcsServiceDeploymentStatus toStatus(
      String serviceName, String clusterArn, Deployment deployment) {
    EcsServiceDeploymentStatus status = new EcsServiceDeploymentStatus();
    status.setServiceName(serviceName);
    status.setClusterArn(clusterArn);
    status.setDeploymentId(deployment.id());
    status.setStatus(deployment.status());
    status.setRolloutState(deployment.rolloutStateAsString());
    status.setRolloutStateReason(deployment.rolloutStateReason());
    status.setDesiredCount(deployment.desiredCount());
    status.setRunningCount(deployment.runningCount());
    status.setPendingCount(deployment.pendingCount());
    status.setFailedTasks(deployment.failedTasks());
    status.setCreatedAt(toEpochMillis(deployment.createdAt()));
    status.setUpdatedAt(toEpochMillis(deployment.updatedAt()));
    return status;
  }

  private static Long toEpochMillis(Instant instant) {
    return instant == null ? null : instant.toEpochMilli();
  }
}
