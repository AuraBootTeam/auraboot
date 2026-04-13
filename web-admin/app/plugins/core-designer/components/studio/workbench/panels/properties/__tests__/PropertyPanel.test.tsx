import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import PropertyPanel from '~/plugins/core-designer/components/studio/workbench/panels/properties/PropertyPanel/PropertyPanel';

vi.mock('~/plugins/core-designer/components/studio/services/managers', () => ({
  eventDomainManager: {
    registerDomain: vi.fn(),
    unregisterDomain: vi.fn(),
    dispatchEvent: vi.fn(),
  },
}));

describe('PropertyPanel (studio)', () => {
  it('renders panel title', () => {
    const { getByText } = render(
      <PropertyPanel
        selectedComponents={[]}
        onComponentUpdate={() => {}}
        layoutConfig={{ columns: 4, gap: 8 }}
        onLayoutConfigChange={() => {}}
        layoutSettings={{
          columns: 4,
          rows: 6,
          gap: 8,
          autoFlow: 'row',
          densePackingEnabled: false,
          densePackingStrategy: 'first-fit',
          optimizeFor: 'space',
        }}
        onLayoutSettingsChange={() => {}}
      />,
    );

    expect(getByText('属性面板')).toBeInTheDocument();
  });
});
