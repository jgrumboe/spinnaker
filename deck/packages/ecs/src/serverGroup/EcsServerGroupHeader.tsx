import React from 'react';

import {
  Alerts,
  ArtifactIconService,
  CloudProviderIcon,
  Health,
  ImageList,
  LoadBalancers,
  MultiSelectCheckbox,
  NameUtils,
  overridesComponent,
  RunningTasks,
} from '@spinnaker/core';
import type { IServerGroup, IServerGroupHeaderProps } from '@spinnaker/core';

import { EcsRolloutStateBadge } from './EcsRolloutStateBadge';

interface IEcsServerGroup extends IServerGroup {
  rolloutState?: string;
  rolloutStateReason?: string;
  taskDefinitionRevision?: number;
}

export class EcsSequenceAndBuildAndImages extends React.Component<IServerGroupHeaderProps> {
  public render() {
    const { serverGroup, jenkins, images, docker } = this.props;
    const ecsServerGroup = serverGroup as IEcsServerGroup;
    const serverGroupSequence = NameUtils.getSequence(serverGroup.moniker.sequence);
    const taskDefinitionRevision = ecsServerGroup.taskDefinitionRevision;
    const revisionLabel =
      !serverGroupSequence && taskDefinitionRevision != null
        ? `v${String(taskDefinitionRevision).padStart(3, '0')}`
        : null;
    const ciBuild = serverGroup.buildInfo && serverGroup.buildInfo.ciBuild;
    const appArtifact = serverGroup.buildInfo && serverGroup.buildInfo.appArtifact;

    return (
      <div>
        {!!serverGroupSequence && <span className="server-group-sequence"> {serverGroupSequence}</span>}
        {!serverGroupSequence && !!revisionLabel && <span className="server-group-sequence"> {revisionLabel}</span>}
        {!!ecsServerGroup.rolloutState && (
          <EcsRolloutStateBadge
            rolloutState={ecsServerGroup.rolloutState}
            rolloutStateReason={ecsServerGroup.rolloutStateReason}
          />
        )}
        {!!serverGroupSequence && (!!jenkins || !!images) && <span>: </span>}
        {!!jenkins && (
          <a className="build-link sp-margin-xs-right" href={jenkins.href} target="_blank">
            Build: #{jenkins.number}
          </a>
        )}
        {!!docker && (
          <a className="build-link" href={docker.href} target="_blank">
            {docker.image}:{docker.tag || docker.digest}
          </a>
        )}

        {!!appArtifact && !!appArtifact.version ? (
          <>
            &nbsp;&nbsp;&nbsp;&nbsp;
            <img className="artifact-icon" src={ArtifactIconService.getPath('maven/file')} width="18" height="18" />
            {!!appArtifact.url ? (
              <a className="build-link" href={appArtifact.url} target="_blank">
                {appArtifact.version}
              </a>
            ) : (
              <>{appArtifact.version}</>
            )}
          </>
        ) : (
          !!ciBuild &&
          !!ciBuild.jobNumber && (
            <>
              &nbsp;&nbsp;&nbsp;&nbsp;
              <img className="artifact-icon" src={ArtifactIconService.getPath('jenkins/file')} width="18" height="18" />
              {!!ciBuild.jobUrl ? (
                <a className="build-link" href={ciBuild.jobUrl} target="_blank">
                  {ciBuild.jobNumber}
                </a>
              ) : (
                <>{ciBuild.jobNumber}</>
              )}
            </>
          )
        )}
        {!!images && <ImageList {...this.props} />}
      </div>
    );
  }
}

export class EcsServerGroupHeader extends React.Component<IServerGroupHeaderProps> {
  public render() {
    const props = this.props;

    return (
      <div className="horizontal top server-group-title sticky-header-3">
        <div className="horizontal section-title flex-1">
          <MultiSelectCheckbox {...props} />
          <CloudProviderIcon {...props} />
          <EcsSequenceAndBuildAndImages {...props} />
          <Alerts {...props} />
        </div>

        <div className="horizontal center flex-none">
          <RunningTasks {...props} />
          <LoadBalancers {...props} />
          <Health {...props} />
        </div>
      </div>
    );
  }
}

export function registerEcsServerGroupHeader(provider: string): void {
  overridesComponent(EcsServerGroupHeader, 'serverGroups.pod.header', provider);
}
