import React from 'react';
import { shallow } from 'enzyme';

import { EcsRolloutStateBadge } from './pod/EcsRolloutStateBadge';
import { SequenceAndBuildAndImages } from './ServerGroupHeader';

const baseProps = (serverGroup: any) =>
  ({
    application: {} as any,
    isMultiSelected: false,
    jenkins: undefined as any,
    docker: undefined as any,
    sortFilter: {} as any,
    serverGroup,
  } as any);

describe('SequenceAndBuildAndImages', () => {
  it('shows the vNNN sequence when a moniker sequence is present', () => {
    const wrapper = shallow(<SequenceAndBuildAndImages {...baseProps({ moniker: { sequence: 73 } })} />);
    const labels = wrapper.find('.server-group-sequence');
    expect(labels.length).toBe(1);
    expect(labels.at(0).text().trim()).toBe('v073');
  });

  it('falls back to the task-definition revision (vNNN) when there is no sequence', () => {
    const wrapper = shallow(<SequenceAndBuildAndImages {...baseProps({ moniker: {}, taskDefinitionRevision: 42 })} />);
    const labels = wrapper.find('.server-group-sequence');
    expect(labels.length).toBe(1);
    expect(labels.at(0).text().trim()).toBe('v042');
  });

  it('does not truncate revisions past three digits', () => {
    const wrapper = shallow(
      <SequenceAndBuildAndImages {...baseProps({ moniker: {}, taskDefinitionRevision: 1234 })} />,
    );
    expect(wrapper.find('.server-group-sequence').at(0).text().trim()).toBe('v1234');
  });

  it('renders the rollout state badge when a rollout state is present', () => {
    const wrapper = shallow(
      <SequenceAndBuildAndImages
        {...baseProps({ moniker: {}, taskDefinitionRevision: 42, rolloutState: 'IN_PROGRESS' })}
      />,
    );
    expect(wrapper.find(EcsRolloutStateBadge).length).toBe(1);
    expect(wrapper.find(EcsRolloutStateBadge).prop('rolloutState')).toBe('IN_PROGRESS');
  });

  it('renders no revision label and no badge for a plain server group', () => {
    const wrapper = shallow(<SequenceAndBuildAndImages {...baseProps({ moniker: {} })} />);
    expect(wrapper.find('.server-group-sequence').length).toBe(0);
    expect(wrapper.find(EcsRolloutStateBadge).length).toBe(0);
  });
});
