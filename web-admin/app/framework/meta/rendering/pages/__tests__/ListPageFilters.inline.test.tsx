import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderInlineListFilters } from '../list/ListPageFilters';
describe('inline list filter controls', () => {
  it('uses existing field rendering and explicit query/reset handlers', () => {
    const field = { field: 'created_at', component: 'SmartDateRange' };
    const search = vi.fn(), reset = vi.fn(), renderer = vi.fn(() => <input aria-label="Created date" />);
    render(<>{renderInlineListFilters({ schemaExtension: { filterLayout: 'inline' }, filterBlock: { fields: [field] },
      renderSmartField: renderer, handleSearch: search, handleReset: reset, t: (key: string) => key } as any)}</>);
    expect(renderer).toHaveBeenCalledWith(field);
    fireEvent.click(screen.getByTestId('filter-search'));expect(search).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(screen.getByLabelText('Created date'), { key: 'Enter' });expect(search).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByTestId('filter-reset'));expect(reset).toHaveBeenCalledTimes(1);
  });
  it('does not introduce controls for pages without inline layout or fields', () => {
    const base = { schemaExtension: {}, filterBlock: { fields: [{ field: 'date' }] } } as any;
    expect(renderInlineListFilters(base)).toBeUndefined();
    expect(renderInlineListFilters({ ...base, schemaExtension: { filterLayout: 'inline' }, filterBlock: { fields: [] } })).toBeUndefined();
  });
});
