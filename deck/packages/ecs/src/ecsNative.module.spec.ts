import { CloudProviderRegistry, DeploymentStrategyRegistry, SETTINGS } from '@spinnaker/core';

import { registerEcsNativeProvider } from './ecsNative.module';
import { EcsCloneServerGroupModal } from './serverGroup/configure/wizard/EcsCloneServerGroupModal';
import { EcsServerGroupTransformer } from './serverGroup/serverGroup.transformer';

describe('registerEcsNativeProvider', () => {
  it('sets SETTINGS.providers[ecs-native] so CloudProviderRegistry accepts the registration, even if it was unset', () => {
    delete SETTINGS.providers['ecs-native'];

    registerEcsNativeProvider();

    expect(SETTINGS.providers['ecs-native']).toBeTruthy();
  });

  it('registers ecs-native as a clone of the ecs provider config, with its own name', () => {
    registerEcsNativeProvider();

    const ecsConfig = CloudProviderRegistry.getProvider('ecs');
    const ecsNativeConfig = CloudProviderRegistry.getProvider('ecs-native');

    expect(ecsNativeConfig).toBeTruthy();
    expect(ecsNativeConfig.name).toBe('EC2 Container Service (Native)');
    expect(ecsNativeConfig.name).not.toBe(ecsConfig.name);
    expect(ecsNativeConfig.logo.path).toBeTruthy();
  });

  it('reuses every other ecs component reference as-is (only the name differs)', () => {
    registerEcsNativeProvider();

    const ecsNativeConfig = CloudProviderRegistry.getProvider('ecs-native');

    expect(ecsNativeConfig.serverGroup.transformer).toBe(EcsServerGroupTransformer);
    expect(ecsNativeConfig.serverGroup.CloneServerGroupModal).toEqual(EcsCloneServerGroupModal);
  });

  it('does not add ecs-native to any provider-restricted deployment strategy, so redblack (offered for ecs) is excluded', () => {
    const ecsStrategies = DeploymentStrategyRegistry.listStrategies('ecs').map((s) => s.key);
    const ecsNativeStrategies = DeploymentStrategyRegistry.listStrategies('ecs-native').map((s) => s.key);

    expect(ecsStrategies).toContain('redblack');
    expect(ecsNativeStrategies).not.toContain('redblack');
  });
});
