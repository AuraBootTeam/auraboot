import React from 'react';
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { SelectionOverlay } from '~/studio/workbench/components/system/SelectionOverlay';

describe('SelectionOverlay', () => {
  it('renders nothing when no selection', () => {
    const { container } = render(
      <SelectionOverlay
        selectedComponents={[]}
        onCopy={() => {}}
        onDelete={() => {}}
        onDuplicate={() => {}}
        onMove={() => {}}
        onResize={() => {}}
        onOpenProperties={() => {}}
      />,
    );
    expect(container.firstChild).toBeNull();
  });
});
