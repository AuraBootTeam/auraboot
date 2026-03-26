import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { DesignerCanvas } from '~/studio/workbench/canvas/DesignerCanvas';

vi.mock('~/studio/services/managers', () => ({
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

  it('renders layout with title', () => {
    const { getByText } = render(
      <DesignerCanvas
        components={[]}
        selectedComponents={[]}
        onComponentClick={() => {}}
        onComponentDelete={() => {}}
      />,
    );

    expect(getByText('设计画布')).toBeInTheDocument();
  });
});
