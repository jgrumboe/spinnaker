import type { IServerGroup } from '@spinnaker/core';
import { REST } from '@spinnaker/core';

/**
 * One task-definition revision of an ecs-native service's family, as returned live by clouddriver's
 * {@code GET /ecs-native/serverGroups/{account}/{region}/{serverGroupName}/taskDefinitions}. These
 * are the candidates the ecs-native rollback picker offers: unlike the classic ECS provider (which
 * rolls back by re-enabling a disabled versioned server group), an ecs-native service is a single
 * durable service, so rolling back means pointing it at an earlier revision of the same family.
 */
export interface IEcsTaskDefinitionRevision {
  taskDefinitionArn: string;
  family: string;
  revision: number;
  containerImages: string[];
  current: boolean;
}

export class EcsNativeTaskDefinitionReader {
  /**
   * Lists the ACTIVE task-definition revisions for the server group's family, newest first. Live
   * (not cache-backed): the caching agent only holds revisions currently referenced by a service,
   * so it cannot enumerate a family's history — which is exactly what a rollback picker needs.
   */
  public static listTaskDefinitionRevisions(serverGroup: IServerGroup): Promise<IEcsTaskDefinitionRevision[]> {
    return REST('/ecs-native/serverGroups')
      .path(serverGroup.account, serverGroup.region, serverGroup.name, 'taskDefinitions')
      .get();
  }
}
