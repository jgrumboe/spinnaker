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
import com.netflix.spinnaker.clouddriver.ecs.cache.client.ServiceCacheClient;
import com.netflix.spinnaker.clouddriver.ecs.cache.model.Service;
import com.netflix.spinnaker.clouddriver.ecs.model.EcsServiceDeploymentStatus;
import com.netflix.spinnaker.clouddriver.ecs.security.NetflixECSCredentials;
import com.netflix.spinnaker.credentials.CredentialsRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServiceDeploymentsRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServiceDeploymentsResponse;
import software.amazon.awssdk.services.ecs.model.DescribeServiceRevisionsRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServiceRevisionsResponse;
import software.amazon.awssdk.services.ecs.model.ListServiceDeploymentsRequest;
import software.amazon.awssdk.services.ecs.model.ListServiceDeploymentsResponse;
import software.amazon.awssdk.services.ecs.model.ServiceDeployment;
import software.amazon.awssdk.services.ecs.model.ServiceRevision;

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

  /**
   * Resolves an ECS account by name, tolerating case differences. Deck may send the account in a
   * different case than the credential is registered under.
   */
  private NetflixECSCredentials resolveCredentials(String account) {
    NetflixECSCredentials exact = credentialsRepository.getOne(account);
    if (exact != null) {
      return exact;
    }
    if (account == null) {
      return null;
    }
    Set<? extends NetflixECSCredentials> all = credentialsRepository.getAll();
    if (all == null) {
      return null;
    }
    return all.stream().filter(c -> account.equalsIgnoreCase(c.getName())).findFirst().orElse(null);
  }

  /**
   * Returns the ECS service deployment matching the task definition produced by the write
   * operation. The expected identity is mandatory: selecting the current PRIMARY deployment would
   * allow an ECS rollback deployment to appear successful for the failed deployment that preceded
   * it.
   */
  @RequestMapping(value = "/deploymentStatus", method = RequestMethod.GET)
  ResponseEntity<?> getDeploymentStatus(
      @PathVariable String account,
      @PathVariable String region,
      @PathVariable String serverGroupName,
      @RequestParam(name = "expectedTaskDefinition", required = false)
          String expectedTaskDefinition) {
    if (expectedTaskDefinition == null || expectedTaskDefinition.isBlank()) {
      return new ResponseEntity<>(
          "expectedTaskDefinition is required to identify the ecs-native deployment",
          HttpStatus.BAD_REQUEST);
    }

    NetflixECSCredentials credentials = resolveCredentials(account);
    if (credentials == null) {
      return new ResponseEntity<>(
          String.format("Account %s is not an ECS account", account), HttpStatus.BAD_REQUEST);
    }
    String resolvedAccount = credentials.getName();

    Optional<Service> cachedService =
        serviceCacheClient.getAll(resolvedAccount, region).stream()
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
    ListServiceDeploymentsResponse listed =
        ecs.listServiceDeployments(
            ListServiceDeploymentsRequest.builder()
                .cluster(cachedService.get().getClusterArn())
                .service(serverGroupName)
                .maxResults(100)
                .build());
    if (listed.serviceDeployments().isEmpty()) {
      return notFound(serverGroupName, "No ECS service deployments found");
    }

    List<String> deploymentArns =
        listed.serviceDeployments().stream()
            .map(deployment -> deployment.serviceDeploymentArn())
            .toList();
    DescribeServiceDeploymentsResponse described =
        ecs.describeServiceDeployments(
            DescribeServiceDeploymentsRequest.builder()
                .serviceDeploymentArns(deploymentArns)
                .build());
    List<String> serviceRevisionArns =
        listed.serviceDeployments().stream()
            .map(deployment -> deployment.targetServiceRevisionArn())
            .filter(arn -> arn != null && !arn.isBlank())
            .toList();
    DescribeServiceRevisionsResponse revisions =
        ecs.describeServiceRevisions(
            DescribeServiceRevisionsRequest.builder()
                .serviceRevisionArns(serviceRevisionArns)
                .build());
    Optional<ServiceDeployment> deployment =
        described.serviceDeployments().stream()
            .filter(
                candidate -> {
                  if (candidate.targetServiceRevision() == null) {
                    return false;
                  }
                  Optional<ServiceRevision> targetRevision =
                      revisions.serviceRevisions().stream()
                          .filter(
                              revision ->
                                  candidate
                                      .targetServiceRevision()
                                      .arn()
                                      .equals(revision.serviceRevisionArn()))
                          .findFirst();
                  return targetRevision.isPresent()
                      && expectedTaskDefinition.equals(targetRevision.get().taskDefinition());
                })
            .findFirst();
    if (deployment.isEmpty()) {
      return notFound(
          serverGroupName,
          "ECS has not exposed a service deployment for task definition " + expectedTaskDefinition);
    }

    ServiceDeployment selectedDeployment = deployment.get();
    ServiceRevision selectedRevision =
        revisions.serviceRevisions().stream()
            .filter(
                revision ->
                    selectedDeployment
                        .targetServiceRevision()
                        .arn()
                        .equals(revision.serviceRevisionArn()))
            .findFirst()
            .orElse(null);
    return new ResponseEntity<>(
        toStatus(
            serverGroupName,
            cachedService.get().getClusterArn(),
            selectedDeployment,
            selectedRevision),
        HttpStatus.OK);
  }

  private static ResponseEntity<String> notFound(String serverGroupName, String reason) {
    return new ResponseEntity<>(
        String.format("Server group %s: %s", serverGroupName, reason), HttpStatus.NOT_FOUND);
  }

  private static EcsServiceDeploymentStatus toStatus(
      String serviceName,
      String clusterArn,
      ServiceDeployment deployment,
      ServiceRevision targetRevision) {
    EcsServiceDeploymentStatus status = new EcsServiceDeploymentStatus();
    status.setServiceName(serviceName);
    status.setClusterArn(clusterArn);
    status.setServiceDeploymentArn(deployment.serviceDeploymentArn());
    status.setDeploymentId(deployment.serviceDeploymentArn());
    status.setStatus(deployment.statusAsString());
    status.setStatusReason(deployment.statusReason());
    status.setRolloutState(deployment.statusAsString());
    status.setRolloutStateReason(deployment.statusReason());
    status.setLifecycleStage(deployment.lifecycleStageAsString());
    if (targetRevision != null) {
      status.setTargetServiceRevisionArn(targetRevision.serviceRevisionArn());
      status.setTargetTaskDefinition(targetRevision.taskDefinition());
    }
    status.setCreatedAt(toEpochMillis(deployment.createdAt()));
    status.setStartedAt(toEpochMillis(deployment.startedAt()));
    status.setFinishedAt(toEpochMillis(deployment.finishedAt()));
    status.setUpdatedAt(toEpochMillis(deployment.updatedAt()));
    return status;
  }

  private static Long toEpochMillis(Instant instant) {
    return instant == null ? null : instant.toEpochMilli();
  }
}
