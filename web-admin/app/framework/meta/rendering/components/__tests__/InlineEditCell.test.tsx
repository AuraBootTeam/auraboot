import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { InlineEditCell } from '../InlineEditCell';

describe('InlineEditCell', () => {
  it('clips long display values inside the fixed-width table cell before editing', () => {
    render(
      <InlineEditCell
        column={{ field: 'company', editable: true }}
        value="A very long company name"
        record={{ pid: 'record-1' }}
        onSave={vi.fn()}
        editable
      >
        A very long company name
      </InlineEditCell>,
    );

    expect(screen.getByTestId('inline-edit-cell-company')).toHaveClass(
      'max-w-full',
      'min-w-0',
      'truncate',
      'overflow-hidden',
      'whitespace-nowrap',
    );
  });
});
