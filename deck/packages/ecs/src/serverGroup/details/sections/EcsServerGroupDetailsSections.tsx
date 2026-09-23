import React from 'react';

import type { IServerGroupDetailsSectionProps } from '@spinnaker/core';
import { CollapsibleSection, FirewallLabels, HealthCounts } from '@spinnaker/core';

import type { IEcsDeploymentStatus } from '../ecsDeploymentStatusReader';
import { EcsDeploymentStatusReader } from '../ecsDeploymentStatusReader';

export function EcsDeploymentSection({ serverGroup }: IServerGroupDetailsSectionProps) {
  const sg = serverGroup as any;
  const taskDefinitionRevision = sg.taskDefinitionRevision;

  // The rollout state is fetched LIVE (not read from the cached server group) so the details pane
  // reflects the true in-flight rollout: clouddriver's cached clusters payload can lag a deploy by
  // a caching cycle, which would otherwise show a stale COMPLETED mid-rollout or a stale IN_PROGRESS
  // just after it settles. The task-definition revision comes from the (stable) cached server group.
  const [status, setStatus] = React.useState<IEcsDeploymentStatus | null>(null);
  const [loading, setLoading] = React.useState(true);

  React.useEffect(() => {
    let cancelled = false;
    setLoading(true);
    EcsDeploymentStatusReader.getDeploymentStatus(serverGroup.account, serverGroup.region, serverGroup.name)
      .then((result) => {
        if (!cancelled) {
          setStatus(result);
          setLoading(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setStatus(null);
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [serverGroup.account, serverGroup.region, serverGroup.name]);

  // Omit the section entirely when there's nothing ECS-deployment-specific to show (e.g. a classic
  // ecs service with no revision and no live rollout status).
  if (taskDefinitionRevision == null && !loading && status == null) {
    return null;
  }

  return (
    <CollapsibleSection heading="Deployment (ECS)" defaultExpanded={true}>
      <dl className="dl-horizontal dl-narrow">
        {taskDefinitionRevision != null && (
          <>
            <dt>Task Def Revision</dt>
            <dd>{taskDefinitionRevision}</dd>
          </>
        )}
        {loading && (
          <>
            <dt>Rollout State</dt>
            <dd>Loading...</dd>
          </>
        )}
        {!loading && status != null && (
          <>
            <dt>Rollout State</dt>
            <dd>{status.rolloutState}</dd>
            {status.rolloutStateReason != null && (
              <>
                <dt>Reason</dt>
                <dd>{status.rolloutStateReason}</dd>
              </>
            )}
            <dt>Tasks</dt>
            <dd>
              {status.runningCount} running, {status.pendingCount} pending
              {status.failedTasks > 0 ? `, ${status.failedTasks} failed` : ''} / {status.desiredCount} desired
            </dd>
            {status.deploymentId != null && (
              <>
                <dt>Deployment ID</dt>
                <dd>{status.deploymentId}</dd>
              </>
            )}
          </>
        )}
      </dl>
    </CollapsibleSection>
  );
}

export function EcsTaskDefinitionSection({ serverGroup }: IServerGroupDetailsSectionProps) {
  const taskDefinition = (serverGroup as any).taskDefinition || {};
  return (
    <CollapsibleSection heading="Task Definition" defaultExpanded={true}>
      <dl className="dl-horizontal dl-narrow">
        <dt>Task Name</dt>
        <dd>{taskDefinition.taskName}</dd>
        <dt>Container URL</dt>
        <dd>{taskDefinition.containerImage}</dd>
        <dt>Container IAM Profile</dt>
        <dd>{taskDefinition.iamRole}</dd>
        {taskDefinition.containerPort != null && (
          <>
            <dt>Container Port</dt>
            <dd>{taskDefinition.containerPort}</dd>
          </>
        )}
        <dt>Container CPU Units</dt>
        <dd>{taskDefinition.cpuUnits}</dd>
        {taskDefinition.memoryReservation != null && (
          <>
            <dt>Container Reserved Memory</dt>
            <dd>{taskDefinition.memoryReservation} MB</dd>
          </>
        )}
        {taskDefinition.memoryLimit != null && (
          <>
            <dt>Container Memory Limit</dt>
            <dd>{taskDefinition.memoryLimit} MB</dd>
          </>
        )}
      </dl>
    </CollapsibleSection>
  );
}

export function EcsEnvironmentVariablesSection({ serverGroup }: IServerGroupDetailsSectionProps) {
  const environmentVariables = (serverGroup as any).taskDefinition?.environmentVariables || [];
  return (
    <CollapsibleSection heading="Environment Variables">
      {environmentVariables.length === 0 ? (
        <div>This server group has no environment variables</div>
      ) : (
        <dl className="dl-horizontal dl-narrow">
          {environmentVariables.map((variable: any) => (
            <React.Fragment key={variable.name}>
              <dt>{variable.name}</dt>
              <dd>{variable.value}</dd>
            </React.Fragment>
          ))}
        </dl>
      )}
    </CollapsibleSection>
  );
}

export function EcsHealthSection({ serverGroup }: IServerGroupDetailsSectionProps) {
  if (!serverGroup.instanceCounts || serverGroup.instanceCounts.total <= 0) {
    return null;
  }
  return (
    <CollapsibleSection heading="Health" defaultExpanded={true}>
      <dl className="dl-horizontal dl-narrow">
        <dt>Instances</dt>
        <dd>
          <HealthCounts container={serverGroup.instanceCounts} className="pull-left" />
        </dd>
      </dl>
    </CollapsibleSection>
  );
}

export function EcsFirewallsSection({ serverGroup }: IServerGroupDetailsSectionProps) {
  const securityGroups = (serverGroup as any).securityGroups || [];
  return (
    <CollapsibleSection heading={FirewallLabels.get('Firewalls')}>
      <ul>
        {securityGroups.map((securityGroup: string) => (
          <li key={securityGroup}>{securityGroup}</li>
        ))}
      </ul>
    </CollapsibleSection>
  );
}

export function EcsCapacitySection({ serverGroup }: IServerGroupDetailsSectionProps) {
  const capacity = (serverGroup as any).capacity || {};
  return (
    <CollapsibleSection heading="Capacity">
      <dl className="dl-horizontal dl-narrow">
        <dt>Current</dt>
        <dd>{serverGroup.instances?.length || 0}</dd>
        <dt>Desired</dt>
        <dd>{capacity.desired}</dd>
        <dt>Min</dt>
        <dd>{capacity.min}</dd>
        <dt>Max</dt>
        <dd>{capacity.max}</dd>
      </dl>
    </CollapsibleSection>
  );
}

export function EcsScalingPoliciesSection({ serverGroup }: IServerGroupDetailsSectionProps) {
  const metricAlarms = (serverGroup as any).metricAlarms || [];
  return (
    <CollapsibleSection heading="Scaling Policies">
      {metricAlarms.length ? (
        <ul>
          {metricAlarms.map((alarm: string) => (
            <li key={alarm}>{alarm}</li>
          ))}
        </ul>
      ) : (
        <i>There are no scaling policies assigned.</i>
      )}
    </CollapsibleSection>
  );
}

function getBuildLink(buildInfo: any): string {
  if (buildInfo.buildInfoUrl) {
    return buildInfo.buildInfoUrl;
  }
  if (buildInfo.jenkins) {
    return `${buildInfo.jenkins.host.replace(/\/?$/, '/')}job/${buildInfo.jenkins.name}/${buildInfo.jenkins.number}`;
  }
  return null;
}

export function EcsBuildInfoSection({ serverGroup }: IServerGroupDetailsSectionProps) {
  const buildInfo = (serverGroup as any).buildInfo;
  if (!buildInfo) {
    return null;
  }
  const buildLink = getBuildLink(buildInfo);
  return (
    <CollapsibleSection heading="Build Data">
      <dl className="dl-horizontal dl-narrow">
        {buildInfo.jenkins && (
          <>
            <dt>Job</dt>
            <dd>{buildInfo.jenkins.name}</dd>
            <dt>Build</dt>
            <dd>{buildInfo.jenkins.number}</dd>
          </>
        )}
        <dt>Package</dt>
        <dd>{buildInfo.package_name}</dd>
        <dt>Commit</dt>
        <dd>{buildInfo.commit?.substring(0, 8)}</dd>
        <dt>Version</dt>
        <dd>{buildInfo.version}</dd>
        {buildLink && (
          <>
            <dt>Build Link</dt>
            <dd>
              <a target="_blank" rel="noopener noreferrer" href={buildLink}>
                {buildLink}
              </a>
            </dd>
          </>
        )}
      </dl>
    </CollapsibleSection>
  );
}
