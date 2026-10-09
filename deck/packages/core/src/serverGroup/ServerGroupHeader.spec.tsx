import React from 'react';
import { shallow } from 'enzyme';

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

  it('does not render a sequence label for a server group without a sequence', () => {
    const wrapper = shallow(<SequenceAndBuildAndImages {...baseProps({ moniker: {} })} />);
    expect(wrapper.find('.server-group-sequence').length).toBe(0);
  });
});
