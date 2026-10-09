import { Registry } from '@spinnaker/core';

import { EcsNativeServiceDeploymentStageConfig } from '../common/EcsNativeServiceDeploymentStageConfig';

export function registerEcsNativeStopServiceDeploymentStage() {
  Registry.pipeline.registerStage({
    key: 'ecsNativeStopServiceDeployment',
    label: 'Stop ECS Native Service Deployment',
    description: 'Stops a native ECS service deployment and rolls it back',
    cloudProvider: 'ecs',
    component: EcsNativeServiceDeploymentStageConfig,
    accountExtractor: (stage) => [stage.context.credentials],
    configAccountExtractor: (stage) => [stage.credentials],
    validators: [
      { type: 'requiredField', fieldName: 'credentials', fieldLabel: 'account' },
      { type: 'requiredField', fieldName: 'region' },
      { type: 'requiredField', fieldName: 'serverGroupName' },
    ],
  });
}
