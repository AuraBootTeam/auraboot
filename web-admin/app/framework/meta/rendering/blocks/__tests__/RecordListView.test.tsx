import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import type { ColumnConfig } from '~/framework/meta/schemas/types';

const fetchResultMock = vi.fn();

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => fetchResultMock(...args),
}));

let mockSearchParams = new URLSearchParams();
const setSearchParamsMock = vi.fn();
vi.mock('react-router', () => ({
  useNavigate: () => vi.fn(),
  useSearchParams: () => [mockSearchParams, setSearchParamsMock] as const,
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: 'en' }),
}));

vi.mock('~/framework/meta/rendering/pages/hooks/useDictCache', () => ({
  useDictCache: () => ({ getDictItems: () => [], getDictLabel: () => undefined, cache: new Map() }),
}));

// Stub the filter chrome — its internals (popovers, geometry) are out of scope;
// we only assert RecordListView's data + query orchestration here.
vi.mock('~/framework/smart/components/view/FilterChipBar', () => ({
  FilterChipBar: () => <div data-testid="filter-chip-bar" />,
}));
vi.mock('~/framework/smart/components/view/FilterFieldPicker', () => ({
  FilterFieldPicker: () => null,
}));
vi.mock('~/framework/smart/components/view/FilterValuePopover', () => ({
  FilterValuePopover: () => null,
}));

// Render a minimal table that exercises renderCellContent so we can assert cells.
vi.mock('~/framework/meta/rendering/pages/list/ListTable', () => ({
  ListTable: ({ data, columns, renderCellContent }: any) => (
    <table data-testid="list-table">
      <tbody>
        {data.map((row: any, ri: number) => (
          <tr key={ri} data-testid="row">
            {columns.map((col: any) => (
              <td key={col.field} data-testid={`cell-${col.field}`}>
                {renderCellContent(row, col, ri)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  ),
}));

vi.mock('~/ui/Pagination', () => ({ Pagination: () => <div data-testid="pagination" /> }));
vi.mock('~/ui/ErrorAlert', () => ({ ErrorAlert: ({ error }: { error: string }) => <div role="alert">{error}</div> }));

import { RecordListView } from '../RecordListView';

const columns: ColumnConfig[] = [
  { field: 'bom_std_material_code', label: 'Material Code' },
  { field: 'bom_std_material_name', label: 'Name' },
];

function lastFilters(): Array<{ fieldName: string; operator: string; value: string }> {
  const call = fetchResultMock.mock.calls.at(-1);
  const params = (call?.[1] as { params?: Record<string, any> })?.params ?? {};
  return params.filters ? JSON.parse(params.filters) : [];
}

afterEach(() => {
  fetchResultMock.mockReset();
  mockSearchParams = new URLSearchParams();
  setSearchParamsMock.mockReset();
});

describe('RecordListView', () => {
  it('fetches with fixedFilters scope and renders rows', async () => {
    fetchResultMock.mockResolvedValue({
      code: 0,
      data: { records: [{ id: '1', bom_std_material_code: 'R100', bom_std_material_name: 'Resistor' }], total: 1, page: 1 },
    });

    render(<RecordListView modelCode="bom_standard_line_pcba" columns={columns} fixedFilters={{ bom_std_task_id: 'T-1' }} />);

    await waitFor(() => expect(screen.getByTestId('list-table')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByText('R100')).toBeInTheDocument());
    expect(screen.getByText('Resistor')).toBeInTheDocument();

    expect(lastFilters()).toContainEqual({ fieldName: 'bom_std_task_id', operator: 'EQ', value: 'T-1' });
  });

  it('renders a "-" placeholder for null cell values', async () => {
    fetchResultMock.mockResolvedValue({
      code: 0,
      data: { records: [{ id: '1', bom_std_material_code: 'R100', bom_std_material_name: null }], total: 1, page: 1 },
    });

    render(<RecordListView modelCode="bom_standard_line_pcba" columns={columns} fixedFilters={{ bom_std_task_id: 'T-1' }} />);

    await waitFor(() => expect(screen.getByText('R100')).toBeInTheDocument());
    const nameCell = screen.getByTestId('cell-bom_std_material_name');
    expect(nameCell.textContent).toBe('-');
  });

  it('typing in the search box triggers a reload carrying the keyword', async () => {
    fetchResultMock.mockResolvedValue({ code: 0, data: { records: [], total: 0, page: 1 } });

    render(<RecordListView modelCode="bom_standard_line_pcba" columns={columns} />);

    await waitFor(() => expect(fetchResultMock).toHaveBeenCalled());
    fireEvent.change(screen.getByTestId('embedded-list-search'), { target: { value: 'cap' } });

    await waitFor(() => {
      const call = fetchResultMock.mock.calls.at(-1);
      const params = (call?.[1] as { params?: Record<string, any> })?.params ?? {};
      expect(params.keyword).toBe('cap');
    });
  });

  it('applies a filter_<field> URL param as a chip filter on the request', async () => {
    mockSearchParams = new URLSearchParams('filter_bom_std_reason_code=missing_critical_field');
    fetchResultMock.mockResolvedValue({ code: 0, data: { records: [], total: 0, page: 1 } });

    render(<RecordListView modelCode="bom_standard_line_pcba" columns={columns} fixedFilters={{ bom_std_task_id: 'T-1' }} />);

    await waitFor(() => {
      const conditions = lastFilters();
      expect(conditions).toContainEqual({
        fieldName: 'bom_std_reason_code',
        operator: 'EQ',
        value: 'missing_critical_field',
      });
      // parent scope still enforced
      expect(conditions).toContainEqual({ fieldName: 'bom_std_task_id', operator: 'EQ', value: 'T-1' });
    });
  });

  it('shows an error alert when the request fails', async () => {
    fetchResultMock.mockResolvedValue({ code: 1, desc: 'boom' });

    render(<RecordListView modelCode="bom_standard_line_pcba" columns={columns} />);

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('boom'));
  });
});
