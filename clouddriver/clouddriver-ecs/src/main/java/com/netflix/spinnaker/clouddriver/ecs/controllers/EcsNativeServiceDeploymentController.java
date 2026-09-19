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
import com.netflix.spinnaker.clouddriver.ecs.security.NetflixECSCredentials;
import com.netflix.spinnaker.credentials.CredentialsRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Deployment;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;

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
