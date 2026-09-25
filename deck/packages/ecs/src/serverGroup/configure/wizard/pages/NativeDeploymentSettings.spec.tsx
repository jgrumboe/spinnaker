import { mount } from 'enzyme';
import React from 'react';

import type { IEcsServerGroupCommand } from '../../serverGroupConfiguration.service';
import { NativeDeploymentSettings } from './NativeDeploymentSettings';

describe('NativeDeploymentSettings', () => {
  const buildCommand = (overrides: Partial<IEcsServerGroupCommand> = {}): IEcsServerGroupCommand =>
    ({
      backingData: { filtered: { metricAlarms: [] } },
      ...overrides,
    } as any);

  it('only shows the toggle when native deployment is off', () => {
    const onFieldChange = jasmine.createSpy('onFieldChange');
    const wrapper = mount(
      <NativeDeploymentSettings
        application={null as any}
        command={buildCommand()}
        configureCommand={() => Promise.resolve()}
        onFieldChange={onFieldChange}
      />,
    );

    expect(wrapper.find('[data-test-id="NativeDeployment.useEcsNative"]').prop('checked')).toBe(false);
    expect(wrapper.find('[data-test-id="NativeDeployment.deploymentStrategy"]').exists()).toBe(false);
  });

  it('toggling the checkbox sets cloudProvider to ecs-native', () => {
    const onFieldChange = jasmine.createSpy('onFieldChange');
    const wrapper = mount(
      <NativeDeploymentSettings
        application={null as any}
        command={buildCommand()}
        configureCommand={() => Promise.resolve()}
        onFieldChange={onFieldChange}
      />,
    );

    wrapper.find('[data-test-id="NativeDeployment.useEcsNative"]').simulate('change', { target: { checked: true } });

    expect(onFieldChange).toHaveBeenCalledWith('cloudProvider', 'ecs-native');
  });

  it('unchecking the checkbox sets cloudProvider back to ecs', () => {
    const onFieldChange = jasmine.createSpy('onFieldChange');
    const wrapper = mount(
      <NativeDeploymentSettings
        application={null as any}
        command={buildCommand({ cloudProvider: 'ecs-native' })}
        configureCommand={() => Promise.resolve()}
        onFieldChange={onFieldChange}
      />,
    );

    wrapper.find('[data-test-id="NativeDeployment.useEcsNative"]').simulate('change', { target: { checked: false } });

    expect(onFieldChange).toHaveBeenCalledWith('cloudProvider', 'ecs');
  });

  it('shows the native fields, defaulting to ROLLING with bake time but no ALB traffic-shift fields, once enabled', () => {
    const wrapper = mount(
      <NativeDeploymentSettings
        application={null as any}
        command={buildCommand({ cloudProvider: 'ecs-native' })}
        configureCommand={() => Promise.resolve()}
        onFieldChange={() => {}}
      />,
    );

    // ecs-native is always in-place now; the "Redeploy in place" toggle was removed.
    expect(wrapper.find('[data-test-id="NativeDeployment.inPlaceUpdate"]').exists()).toBe(false);
    expect(wrapper.find('[data-test-id="NativeDeployment.deploymentStrategy"]').prop('value')).toBe('ROLLING');
    // Bake time is valid for the ECS deployment controller regardless of strategy, so it shows for ROLLING too.
    expect(wrapper.find('[data-test-id="NativeDeployment.bakeTimeInMinutes"]').exists()).toBe(true);
    // The ALB traffic-shift fields remain blue/green-only.
    expect(wrapper.find('[data-test-id="NativeDeployment.alternateTargetGroupArn"]').exists()).toBe(false);
  });

  it('reveals the ALB traffic-shift fields once BLUE_GREEN is selected, with bake time still shown', () => {
    const wrapper = mount(
      <NativeDeploymentSettings
        application={null as any}
        command={buildCommand({ cloudProvider: 'ecs-native', deploymentStrategy: 'BLUE_GREEN' })}
        configureCommand={() => Promise.resolve()}
        onFieldChange={() => {}}
      />,
    );

    expect(wrapper.find('[data-test-id="NativeDeployment.bakeTimeInMinutes"]').exists()).toBe(true);
    expect(wrapper.find('[data-test-id="NativeDeployment.alternateTargetGroupArn"]').exists()).toBe(true);
    expect(wrapper.find('[data-test-id="NativeDeployment.productionListenerRule"]').exists()).toBe(true);
    expect(wrapper.find('[data-test-id="NativeDeployment.testListenerRule"]').exists()).toBe(true);
    expect(wrapper.find('[data-test-id="NativeDeployment.blueGreenRoleArn"]').exists()).toBe(true);
  });

  it('disables the circuit-breaker rollback checkbox until the circuit breaker itself is enabled', () => {
    const wrapper = mount(
      <NativeDeploymentSettings
        application={null as any}
        command={buildCommand({ cloudProvider: 'ecs-native' })}
        configureCommand={() => Promise.resolve()}
        onFieldChange={() => {}}
      />,
    );

    expect(wrapper.find('[data-test-id="NativeDeployment.deploymentCircuitBreakerRollback"]').prop('disabled')).toBe(
      true,
    );

    wrapper.setProps({ command: buildCommand({ cloudProvider: 'ecs-native', enableDeploymentCircuitBreaker: true }) });

    expect(wrapper.find('[data-test-id="NativeDeployment.deploymentCircuitBreakerRollback"]').prop('disabled')).toBe(
      false,
    );
  });

  it('adds an alarm name when its checkbox is checked, and removes it when unchecked', () => {
    const onFieldChange = jasmine.createSpy('onFieldChange');
    const command = buildCommand({
      cloudProvider: 'ecs-native',
      backingData: { filtered: { metricAlarms: [{ alarmName: 'myapp-high-error-rate' }] } } as any,
    });
    const wrapper = mount(
      <NativeDeploymentSettings
        application={null as any}
        command={command}
        configureCommand={() => Promise.resolve()}
        onFieldChange={onFieldChange}
      />,
    );

    wrapper
      .find('input[type="checkbox"]')
      .filterWhere((input) => !input.prop('data-test-id'))
      .first()
      .simulate('change', {
        target: { checked: true },
      });

    expect(onFieldChange).toHaveBeenCalledWith('alarmNames', ['myapp-high-error-rate']);

    onFieldChange.calls.reset();
    wrapper.setProps({
      command: buildCommand({
        cloudProvider: 'ecs-native',
        alarmNames: ['myapp-high-error-rate'],
        backingData: { filtered: { metricAlarms: [{ alarmName: 'myapp-high-error-rate' }] } } as any,
      }),
    });
    wrapper
      .find('input[type="checkbox"]')
      .filterWhere((input) => !input.prop('data-test-id'))
      .first()
      .simulate('change', {
        target: { checked: false },
      });

    expect(onFieldChange).toHaveBeenCalledWith('alarmNames', []);
  });

  it('clears the Spinnaker strategy when native deployment is enabled', () => {
    const onFieldChange = jasmine.createSpy('onFieldChange');
    const wrapper = mount(
      <NativeDeploymentSettings
        application={null as any}
        command={buildCommand({ strategy: 'redblack' })}
        configureCommand={() => Promise.resolve()}
        onFieldChange={onFieldChange}
      />,
    );

    wrapper.find('[data-test-id="NativeDeployment.useEcsNative"]').simulate('change', { target: { checked: true } });

    expect(onFieldChange).toHaveBeenCalledWith('strategy', 'none');
  });

  it('shows minimum and maximum healthy deployment bounds', () => {
    const wrapper = mount(
      <NativeDeploymentSettings
        application={null as any}
        command={buildCommand({ cloudProvider: 'ecs-native' })}
        configureCommand={() => Promise.resolve()}
        onFieldChange={() => {}}
      />,
    );

    expect(wrapper.find('[data-test-id="NativeDeployment.minimumHealthyPercent"]').exists()).toBe(true);
    expect(wrapper.find('[data-test-id="NativeDeployment.maximumPercent"]').exists()).toBe(true);
  });
});
