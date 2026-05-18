import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ListTable, type ListTableProps } from '../ListTable';

const columns = [
  { field: 'name', label: '页面名称', width: 220 },
  { field: 'kind', label: '页面类型', width: 110 },
];

function renderListTable(overrides: Partial<ListTableProps> = {}) {
  const props: ListTableProps = {
    columns,
    data: [{ pid: 'row-1', name: 'Mission Control', kind: '列表' }],
    loading: false,
    activeSorts: [],
    selectedIds: new Set(),
    modelCode: 'page_schema',
    columnOrder: [],
    onColumnReorder: vi.fn(),
    onColumnResize: vi.fn(),
    onToggleSort: vi.fn(),
    onSelectRow: vi.fn(),
    onSelectAll: vi.fn(),
    onRowClick: vi.fn(),
    onContextMenu: vi.fn(),
    renderCellContent: (record, column) => record[column.field],
    evaluateVisibleWhen: () => true,
    resolveButtonLabel: (button) => String(button.label ?? button.code),
    handleAction: vi.fn(),
    resolveColumnLabel: (column) => String(column.label ?? column.field),
    columnWidths: {},
    collapsedGroups: new Set(),
    onToggleGroupCollapse: vi.fn(),
    t: (key) => key,
    enableSelection: false,
    ...overrides,
  };

  return render(<ListTable {...props} />);
}

describe('ListTable selection column layout', () => {
  it('does not render body selection cells when selection is disabled', () => {
    renderListTable();

    expect(screen.queryByTestId('select-all-checkbox')).not.toBeInTheDocument();
    expect(screen.queryByTestId('row-checkbox-0')).not.toBeInTheDocument();
    expect(screen.getByTestId('table-row-0').children).toHaveLength(columns.length);
  });
});
