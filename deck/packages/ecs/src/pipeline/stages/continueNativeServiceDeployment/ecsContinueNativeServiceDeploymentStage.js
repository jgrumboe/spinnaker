import { Registry } from '@spinnaker/core';

import { EcsNativeServiceDeploymentStageConfig } from '../common/EcsNativeServiceDeploymentStageConfig';

export function registerEcsNativeContinueServiceDeploymentStage() {
  Registry.pipeline.registerStage({
    provides: 'ecsNativeContinueServiceDeployment',
    cloudProvider: 'ecs-native',
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
