import React from 'react';
import { shallow } from 'enzyme';

import { EcsRolloutStateBadge } from './EcsRolloutStateBadge';
import { Tooltip } from '../../presentation/Tooltip';

describe('EcsRolloutStateBadge', () => {
  it('renders nothing without a rollout state', () => {
    const wrapper = shallow(<EcsRolloutStateBadge rolloutState={undefined as any} />);
    expect(wrapper.isEmptyRender()).toBe(true);
  });

  it('renders a completed dot', () => {
    const wrapper = shallow(<EcsRolloutStateBadge rolloutState="COMPLETED" />);
    expect(wrapper.find('.ecs-rollout-state-dot--completed').length).toBe(1);
  });

  it('renders an in-progress dot', () => {
    const wrapper = shallow(<EcsRolloutStateBadge rolloutState="IN_PROGRESS" />);
    expect(wrapper.find('.ecs-rollout-state-dot--in-progress').length).toBe(1);
  });

  it('escalates a failed rollout to a labeled pill with the reason in a tooltip', () => {
    const wrapper = shallow(
      <EcsRolloutStateBadge rolloutState="FAILED" rolloutStateReason="circuit breaker rolled back" />,
    );
    const pill = wrapper.find('.ecs-rollout-state--failed');
    expect(pill.length).toBe(1);
    expect(pill.text()).toBe('FAILED');
    expect(wrapper.find(Tooltip).prop('value')).toBe('FAILED: circuit breaker rolled back');
  });
});
