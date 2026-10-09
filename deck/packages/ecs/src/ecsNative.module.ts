import { CloudProviderRegistry, DeploymentStrategyRegistry, SETTINGS } from '@spinnaker/core';

// Register the shared ECS components before copying the provider configuration. The native
// identity reuses those component references for resource reads and details views.
import './ecs.module';
import { IECSProviderSettings } from './ecs.settings';
import ecsNativeLogo from './logo/ecs.logo.svg';
import { registerEcsServerGroupHeader } from './serverGroup/EcsServerGroupHeader';

const ECS_NATIVE = 'ecs-native';

/**
 * Registers `ecs-native` as a Deck-visible identity backed by the existing `ecs` provider
 * components.
 *
 * Accounts remain typed `ecs`, so account-driven provider pickers continue to expose `ecs` rather
 * than inventing a second credential type. The `ecs-native` identity is used for provider-scoped
 * stage lookups and exact native delegates; it is not the backend identity stamped onto returned
 * server groups. Backend native resources carry `cloudProvider: "ecs"` and use `isNative` for
 * durable ownership, while exact native delegates keep provider-scoped lookups from falling back
 * to classic resources. Aggregate ECS listings stay owned by `ecs`, preventing every service from
 * appearing twice.
 *
 * The native registration reuses each `ecs` component reference; only the display name and logo
 * differ. Deploy/clone behavior is selected by the stage's `cloudProvider` and is not inferred
 * from an account.
 */
export function registerEcsNativeProvider(): void {
  SETTINGS.providers[ECS_NATIVE] = SETTINGS.providers[ECS_NATIVE] || IECSProviderSettings;

  const ecsConfig = CloudProviderRegistry.getProvider('ecs');
  if (!ecsConfig) {
    return;
  }

  CloudProviderRegistry.registerProvider(ECS_NATIVE, {
    ...ecsConfig,
    name: 'EC2 Container Service (Native)',
    logo: { path: ecsNativeLogo },
  });
}

registerEcsNativeProvider();
registerEcsServerGroupHeader(ECS_NATIVE);

// The native ECS deployment strategy (rolling/blue-green, configured on the Native ECS Deployment
// wizard page) supersedes Spinnaker's own deployment strategies for this provider identity.
DeploymentStrategyRegistry.registerProvider(ECS_NATIVE, []);
