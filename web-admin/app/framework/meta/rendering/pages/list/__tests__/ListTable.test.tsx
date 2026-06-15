import { render, screen, waitFor } from '@testing-library/react';
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

  it('applies configured column widths to the table body and truncates long cell content', () => {
    const longName = 'DecisionOps policy name that should not expand the whole table layout';
    const { container } = renderListTable({
      columnWidths: { name: 180 },
      data: [{ pid: 'row-1', name: longName, kind: '列表' }],
    });

    const table = container.querySelector('table');
    expect(table).toHaveClass('table-fixed');
    expect(table).toHaveStyle({ minWidth: '290px' });

    const cols = container.querySelectorAll('col');
    expect(cols).toHaveLength(2);
    expect(cols[0]).toHaveStyle({ width: '180px' });
    expect(cols[1]).toHaveStyle({ width: '110px' });

    const nameCell = screen.getByTestId('table-cell-0-name');
    expect(nameCell).toHaveStyle({ width: '180px', maxWidth: '180px' });
    const truncationWrapper = nameCell.querySelector('div.truncate');
    expect(truncationWrapper).toBeInTheDocument();
    expect(truncationWrapper).toHaveAttribute('title', longName);
  });

  it('keeps row actions in a stable fixed-width column', () => {
    const { container } = renderListTable({
      enableSelection: true,
      columns: [
        ...columns,
        {
          field: '_actions',
          label: '操作',
          isActionColumn: true,
          buttons: [
            { code: 'view', label: '查看' },
            { code: 'copy', label: '复制' },
          ],
        } as any,
      ],
    });

    const cols = container.querySelectorAll('col');
    expect(cols).toHaveLength(4);
    expect(cols[0]).toHaveStyle({ width: '40px' });
    expect(cols[3]).toHaveStyle({ width: '112px' });

    const actionCell = screen.getByTestId('table-cell-0-actions');
    expect(actionCell).toHaveStyle({ width: '112px', maxWidth: '112px' });
    expect(actionCell).not.toHaveClass('w-px');
  });

  it('fills spare container width with flexible data columns', async () => {
    const clientWidthDescriptor = Object.getOwnPropertyDescriptor(
      HTMLElement.prototype,
      'clientWidth',
    );
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
      configurable: true,
      get: () => 1000,
    });

    try {
      const { container } = renderListTable({
        columns: [
          { field: 'code', label: '报价单编号', width: 180 },
          { field: 'customer', label: '客户信息', width: 180 },
          {
            field: 'status',
            label: '状态',
            width: 120,
            renderType: 'tag',
            dictCode: 'quote_status',
          },
          {
            field: 'actions',
            label: '操作',
            isActionColumn: true,
            width: 128,
            buttons: [{ code: 'view', label: '查看' }],
          } as any,
        ],
        data: [{ pid: 'row-1', code: 'QO-1', customer: '客户 A', status: 'draft' }],
      });

      await waitFor(() => {
        expect(container.querySelector('table')).toHaveStyle({ minWidth: '1000px' });
      });

      const cols = container.querySelectorAll('col');
      expect(cols).toHaveLength(4);
      expect(cols[0]).toHaveStyle({ width: '376px' });
      expect(cols[1]).toHaveStyle({ width: '376px' });
      expect(cols[2]).toHaveStyle({ width: '120px' });
      expect(cols[3]).toHaveStyle({ width: '128px' });
    } finally {
      if (clientWidthDescriptor) {
        Object.defineProperty(HTMLElement.prototype, 'clientWidth', clientWidthDescriptor);
      } else {
        delete (HTMLElement.prototype as any).clientWidth;
      }
    }
  });
});
