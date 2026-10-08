import { mount } from 'enzyme';
import React from 'react';

import { AccountSelectInput } from '@spinnaker/core';

import { EcsNativeServiceDeploymentStageConfig } from './EcsNativeServiceDeploymentStageConfig';

describe('EcsNativeServiceDeploymentStageConfig', () => {
  const stage = {
    credentials: 'ecs-account',
    region: 'us-west-2',
    serverGroupName: 'orders',
    deploymentStageRefId: 'deploy-ref',
    requisiteStageRefIds: ['deploy-ref'],
    ecsNativeExpectedServiceDeploymentArn: 'arn:aws:ecs:deployment/orders/7',
  };
  const pipeline = {
    stages: [
      { cloudProvider: 'ecs-native', name: 'Deploy orders', refId: 'deploy-ref', type: 'createServerGroup' },
      { ...stage, name: 'Continue orders', refId: 'continue-ref', type: 'ecsNativeContinueServiceDeployment' },
    ],
  };

  it('references an upstream native deploy and keeps the ARN as an optional legacy fallback', () => {
    const updateStageField = jasmine.createSpy('updateStageField');
    const wrapper = mount(
      <EcsNativeServiceDeploymentStageConfig
        application={{ defaultCredentials: {}, defaultRegions: {} } as any}
        pipeline={pipeline as any}
        stage={stage}
        updateStageField={updateStageField}
        stageFieldUpdated={() => undefined}
      />,
    );

    expect(wrapper.find(AccountSelectInput).prop('value')).toBe('ecs-account');
    expect(wrapper.find('select[name="deploymentStageRefId"]').prop('value')).toBe('deploy-ref');
    expect(wrapper.find('input[name="ecsNativeExpectedServiceDeploymentArn"]').prop('required')).toBeFalsy();
    expect(wrapper.find('input[name="ecsNativeExpectedServiceDeploymentArn"]').prop('value')).toBe(
      'arn:aws:ecs:deployment/orders/7',
    );

    wrapper.find('select[name="deploymentStageRefId"]').simulate('change', { target: { value: 'deploy-ref' } });
    expect(updateStageField).toHaveBeenCalledWith({
      deploymentStageRefId: 'deploy-ref',
      requisiteStageRefIds: ['deploy-ref'],
    });
  });

  describe('lifecycle action', () => {
    const mountConfig = (lifecycleAction: string | undefined, updateStageField = jasmine.createSpy('update')) => {
      const continuePipeline = {
        stages: [
          ...pipeline.stages,
          { name: 'Approve release', refId: 'judge-ref', type: 'manualJudgment', requisiteStageRefIds: ['deploy-ref'] },
        ],
      };
      const continueStage = {
        ...stage,
        type: 'ecsNativeContinueServiceDeployment',
        requisiteStageRefIds: ['judge-ref'],
        lifecycleAction,
      };
      return {
        updateStageField,
        wrapper: mount(
          <EcsNativeServiceDeploymentStageConfig
            application={{ defaultCredentials: {}, defaultRegions: {} } as any}
            pipeline={continuePipeline as any}
            stage={continueStage}
            updateStageField={updateStageField}
            stageFieldUpdated={() => undefined}
          />,
        ),
      };
    };
    const expression = "${ #judgment('Approve release') == 'continue' ? 'CONTINUE' : 'ROLLBACK' }";

    it('shows an existing expression in expression mode with its text', () => {
      const { wrapper } = mountConfig(expression);
      expect(wrapper.find('select[name="lifecycleActionMode"]').prop('value')).toBe('EXPRESSION');
      expect(wrapper.find('input[name="lifecycleAction"]').prop('value')).toBe(expression);
    });

    it('shows fixed actions without an expression field', () => {
      const { wrapper } = mountConfig('ROLLBACK');
      expect(wrapper.find('select[name="lifecycleActionMode"]').prop('value')).toBe('ROLLBACK');
      expect(wrapper.find('input[name="lifecycleAction"]').exists()).toBe(false);
    });

    it('pre-fills a judgment expression when switching to expression mode', () => {
      const { wrapper, updateStageField } = mountConfig('CONTINUE');
      wrapper.find('select[name="lifecycleActionMode"]').simulate('change', { target: { value: 'EXPRESSION' } });
      expect(updateStageField).toHaveBeenCalledWith({ lifecycleAction: expression });
    });

    it('stores the fixed action when switching away from an expression', () => {
      const { wrapper, updateStageField } = mountConfig(expression);
      wrapper.find('select[name="lifecycleActionMode"]').simulate('change', { target: { value: 'ROLLBACK' } });
      expect(updateStageField).toHaveBeenCalledWith({ lifecycleAction: 'ROLLBACK' });
    });
  });
});
