import React from 'react';
import { shallow } from 'enzyme';

import { EcsRolloutStateBadge } from './EcsRolloutStateBadge';
import { EcsSequenceAndBuildAndImages, EcsServerGroupHeader } from './EcsServerGroupHeader';

const baseProps = (serverGroup: any) =>
  ({
    application: {} as any,
    isMultiSelected: false,
    jenkins: undefined as any,
    docker: undefined as any,
    sortFilter: {} as any,
    serverGroup,
  } as any);

describe('EcsSequenceAndBuildAndImages', () => {
  it('shows the task-definition revision when there is no server-group sequence', () => {
    const wrapper = shallow(
      <EcsSequenceAndBuildAndImages {...baseProps({ moniker: {}, taskDefinitionRevision: 42 })} />,
    );
    expect(wrapper.find('.server-group-sequence').text().trim()).toBe('v042');
  });

  it('does not truncate task-definition revisions past three digits', () => {
    const wrapper = shallow(
      <EcsSequenceAndBuildAndImages {...baseProps({ moniker: {}, taskDefinitionRevision: 1234 })} />,
    );
    expect(wrapper.find('.server-group-sequence').text().trim()).toBe('v1234');
  });

  it('renders the rollout badge through the ECS header component', () => {
    const wrapper = shallow(
      <EcsSequenceAndBuildAndImages
        {...baseProps({ moniker: {}, taskDefinitionRevision: 42, rolloutState: 'IN_PROGRESS' })}
      />,
    );
    expect(wrapper.find(EcsRolloutStateBadge).prop('rolloutState')).toBe('IN_PROGRESS');
  });
});

describe('EcsServerGroupHeader', () => {
  it('renders the ECS sequence component in the provider header override', () => {
    const wrapper = shallow(<EcsServerGroupHeader {...baseProps({ moniker: {} })} />);
    expect(wrapper.find(EcsSequenceAndBuildAndImages).length).toBe(1);
  });
});
