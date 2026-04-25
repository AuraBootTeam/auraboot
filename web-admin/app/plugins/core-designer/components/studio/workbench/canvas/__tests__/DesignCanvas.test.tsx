import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { DesignerCanvas } from '~/plugins/core-designer/components/studio/workbench/canvas/DesignerCanvas';

vi.mock(
  '~/plugins/core-designer/components/studio/workbench/canvas/GridContainer',
  () => ({
    GridContainer: () => <div data-testid="grid-container" />,
  }),
);

vi.mock('~/plugins/core-designer/components/studio/services/managers', () => ({
  eventDomainManager: {
    registerDomain: vi.fn(),
    unregisterDomain: vi.fn(),
  },
  globalShortcutManager: {
    registerDomain: vi.fn(),
    unregisterDomain: vi.fn(),
  },
}));

describe('DesignerCanvas (studio)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('renders the canvas shell and grid container', () => {
    const { container, getByTestId } = render(
      <DesignerCanvas
        components={[]}
        selectedComponents={[]}
        onComponentClick={() => {}}
        onComponentDelete={() => {}}
      />,
    );

    expect(container.querySelector('[data-domain="canvas"]')).toBeInTheDocument();
    expect(getByTestId('grid-container')).toBeInTheDocument();
  });
});
