import { CloudProviderRegistry, DeploymentStrategyRegistry, SETTINGS } from '@spinnaker/core';

// Ensures the 'ecs' provider (and its underlying components) is registered first, regardless
// of import order elsewhere -- ecs-native reuses that registration wholesale below.
import './ecs.module';
import { IECSProviderSettings } from './ecs.settings';
import ecsNativeLogo from './logo/ecs.logo.svg';

const ECS_NATIVE = 'ecs-native';

/**
 * Registers 'ecs-native' as a second, Deck-visible identity for the same ECS accounts as 'ecs'.
 *
 * There is no distinct 'ecs-native'-typed account anywhere -- clouddriver reuses the exact same
 * ECS accounts/credentials/caching for both, and only the operation-routing `cloudProvider` field
 * on a create/clone server-group command differs (toggled via the "Use native ECS deployment"
 * checkbox on the Native ECS Deployment wizard page, see EcsServerGroupTransformer). Because no
 * account is typed 'ecs-native', it is never offered by Deck's account-driven provider pickers
 * (the pipeline "Add stage" provider dropdown, ProviderSelectionService's "add new cluster" flow)
 * -- those still only ever see 'ecs'. This registration exists purely so that VIEWING an existing
 * ecs-native server group (details pane, action buttons, load balancer/security group/instance
 * views) resolves correctly: those all look up CloudProviderRegistry by the resource's own
 * `cloudProviderId`, which the backend's ecs-native read-path delegates already report as
 * 'ecs-native'.
 *
 * Reuses every 'ecs' component reference as-is (cloneDeep only copies plain data, not the
 * function/class references), so there is nothing ecs-native-specific to keep in sync here beyond
 * the display name/logo.
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

// The native ECS deployment strategy (rolling/blue-green, configured on the Native ECS Deployment
// wizard page) supersedes Spinnaker's own deployment strategies for this provider identity.
DeploymentStrategyRegistry.registerProvider(ECS_NATIVE, []);
