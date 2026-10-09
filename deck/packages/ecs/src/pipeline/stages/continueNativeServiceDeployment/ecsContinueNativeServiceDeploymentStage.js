import { Registry } from '@spinnaker/core';

import { EcsNativeServiceDeploymentStageConfig } from '../common/EcsNativeServiceDeploymentStageConfig';

export function registerEcsNativeContinueServiceDeploymentStage() {
  Registry.pipeline.registerStage({
    key: 'ecsNativeContinueServiceDeployment',
    label: 'Continue ECS Native Service Deployment',
    description: 'Continues or rolls back a native ECS blue/green deployment paused at its lifecycle hook',
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
