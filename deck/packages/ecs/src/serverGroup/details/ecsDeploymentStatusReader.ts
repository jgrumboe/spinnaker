import { REST } from '@spinnaker/core';

/**
 * ECS's own native deployment/rollout status for a service's PRIMARY deployment, as returned live
 * by clouddriver's {@code /ecs-native/serverGroups/.../deploymentStatus} endpoint (a live
 * DescribeServices call, the same source the orca wait task polls). Mirrors clouddriver's
 * EcsServiceDeploymentStatus model.
 */
export interface IEcsDeploymentStatus {
  serviceName: string;
  clusterArn: string;
  deploymentId: string;
  rolloutState: string;
  rolloutStateReason: string;
  status: string;
  desiredCount: number;
  runningCount: number;
  pendingCount: number;
  failedTasks: number;
  createdAt: number;
  updatedAt: number;
}

export class EcsDeploymentStatusReader {
  /**
   * Fetches the current ECS rollout status for an ecs-native server group. Read live rather than
   * from the cached server group so the details pane reflects the true in-flight rollout state
   * (the cached clusters payload can lag a deploy by a caching cycle). Returns null on 404 (e.g. a
   * classic ecs service, or before the PRIMARY deployment exists right after create).
   */
  public static getDeploymentStatus(
    account: string,
    region: string,
    serverGroupName: string,
  ): Promise<IEcsDeploymentStatus | null> {
    return REST('/ecs-native/serverGroups')
      .path(account, region, serverGroupName, 'deploymentStatus')
      .get()
      .catch(() => null);
  }
}
