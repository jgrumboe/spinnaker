import React from 'react';

import type { IAccount, IStageConfigProps } from '@spinnaker/core';
import { AccountSelectInput, AccountService, PipelineConfigService, StageConfigField } from '@spinnaker/core';

const ECS_NATIVE = 'ecs-native';

export function EcsNativeServiceDeploymentStageConfig({ application, pipeline, stage, updateStageField }: IStageConfigProps) {
  const [accounts, setAccounts] = React.useState<IAccount[]>([]);
  const upstreamDeployStages = PipelineConfigService.getAllUpstreamDependencies(pipeline, stage).filter(
    (candidate) => candidate.type === 'createServerGroup' && candidate.cloudProvider === ECS_NATIVE,
  );
  const deploymentStageRefId = stage.deploymentStageRefId || stage.requisiteStageRefIds?.[0] || '';

  React.useEffect(() => {
    let active = true;
    AccountService.listAccounts('ecs').then((loadedAccounts) => active && setAccounts(loadedAccounts));
    return () => {
      active = false;
    };
  }, []);

  React.useEffect(() => {
    const changes: Record<string, any> = { cloudProvider: ECS_NATIVE };
    const defaultCredentials = application.defaultCredentials?.ecs;
    const defaultRegion = application.defaultRegions?.ecs;
    if (!stage.credentials && defaultCredentials) {
      changes.credentials = defaultCredentials;
    }
    if (!stage.region && defaultRegion) {
      changes.region = defaultRegion;
    }
    updateStageField(changes);
  }, []);

  const selectDeploymentStage = (refId: string) => {
    updateStageField({
      deploymentStageRefId: refId,
      requisiteStageRefIds: refId ? [refId] : [],
    });
  };

  return (
    <div className="container-fluid form-horizontal">
      <StageConfigField label="Account">
        <AccountSelectInput
          accounts={accounts}
          name="credentials"
          onChange={(event: React.ChangeEvent<HTMLSelectElement>) =>
            updateStageField({ credentials: event.target.value })
          }
          provider="ecs"
          value={stage.credentials || ''}
        />
      </StageConfigField>
      <StageConfigField label="Region">
        <input
          className="form-control input-sm"
          name="region"
          onChange={(event) => updateStageField({ region: event.target.value })}
          required
          type="text"
          value={stage.region || ''}
        />
      </StageConfigField>
      <StageConfigField label="Server group name">
        <input
          className="form-control input-sm"
          name="serverGroupName"
          onChange={(event) => updateStageField({ serverGroupName: event.target.value })}
          required
          type="text"
          value={stage.serverGroupName || ''}
        />
      </StageConfigField>
      <StageConfigField label="Deployment stage">
        <select
          className="form-control input-sm"
          name="deploymentStageRefId"
          onChange={(event) => selectDeploymentStage(event.target.value)}
          value={deploymentStageRefId}
        >
          <option value="">Select a preceding native deploy stage</option>
          {upstreamDeployStages.map((deployStage) => (
            <option key={deployStage.refId} value={deployStage.refId}>
              {deployStage.name || deployStage.refId}
            </option>
          ))}
        </select>
        <span className="help-block">The deploy stage supplies the exact ECS service-deployment ARN.</span>
      </StageConfigField>
      <StageConfigField label="Expected service deployment ARN (legacy fallback)">
        <input
          className="form-control input-sm"
          name="ecsNativeExpectedServiceDeploymentArn"
          onChange={(event) => updateStageField({ ecsNativeExpectedServiceDeploymentArn: event.target.value })}
          type="text"
          value={stage.ecsNativeExpectedServiceDeploymentArn || ''}
        />
      </StageConfigField>
    </div>
  );
}
