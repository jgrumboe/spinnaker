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
});
