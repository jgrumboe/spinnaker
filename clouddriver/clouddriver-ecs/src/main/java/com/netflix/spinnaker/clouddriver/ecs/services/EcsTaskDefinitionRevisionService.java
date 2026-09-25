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

package com.netflix.spinnaker.clouddriver.ecs.services;

import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider;
import com.netflix.spinnaker.clouddriver.ecs.model.EcsTaskDefinitionRevision;
import com.netflix.spinnaker.clouddriver.ecs.security.NetflixECSCredentials;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.ListTaskDefinitionsRequest;
import software.amazon.awssdk.services.ecs.model.ListTaskDefinitionsResponse;
import software.amazon.awssdk.services.ecs.model.SortOrder;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.model.TaskDefinitionStatus;

/**
 * Lists the {@code ACTIVE} task-definition revisions for an {@code ecs-native} service's family,
 * newest first, to back the Deck rollback picker (roll a durable service back to an earlier
 * revision of the same family).
 *
 * <p>This is a live call ({@code ListTaskDefinitions} + {@code DescribeTaskDefinition}), not
 * cache-backed: the task-definition cache only ever holds revisions currently referenced by a live
 * service and is authoritatively pruned, so it cannot enumerate a family's history. The result is
 * attached to the {@code ecs-native} server group only on the details path (see {@code
 * EcsServerClusterProvider}), so classic {@code ecs} services and the high-volume list/summary
 * calls never trigger it.
 */
@Component
public class EcsTaskDefinitionRevisionService {

  /**
   * The most task-definition revisions to describe (for their container images). ECS returns
   * revisions newest-first, so this caps how far back the picker looks; older revisions are rarely
   * useful rollback targets and each one costs a {@code DescribeTaskDefinition} call.
   */
  static final int MAX_REVISIONS = 50;

  private final AmazonClientProvider amazonClientProvider;

  @Autowired
  public EcsTaskDefinitionRevisionService(AmazonClientProvider amazonClientProvider) {
    this.amazonClientProvider = amazonClientProvider;
  }

  /**
   * Lists the family's ACTIVE revisions (newest first, capped) for the service whose current
   * task-definition ARN is {@code currentTaskDefinitionArn}, flagging that current revision.
   * Returns an empty list when the family can't be derived from the ARN.
   */
  public List<EcsTaskDefinitionRevision> listRevisions(
      NetflixECSCredentials credentials, String region, String currentTaskDefinitionArn) {
    String family = familyFromTaskDefinitionArn(currentTaskDefinitionArn);
    if (family == null) {
      return new ArrayList<>();
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
    return revisions;
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
}
