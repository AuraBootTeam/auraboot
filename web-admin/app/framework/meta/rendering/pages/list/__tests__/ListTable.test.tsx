import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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

  it('widens the action column for long labels instead of wrapping them', () => {
    // Regression (page_schema): "统一设计器" + the "⋮" trigger needed ~130px but the
    // column was pinned at 112px, so the label wrapped and every row grew to ~65px.
    const { container } = renderListTable({
      enableSelection: true,
      columns: [
        ...columns,
        {
          field: '_actions',
          label: '操作',
          isActionColumn: true,
          buttons: [
            { code: 'edit_unified', label: '统一设计器' },
            { code: 'publish', label: '发布' },
            { code: 'delete', label: '删除' },
          ],
        } as any,
      ],
    });

    const actionCol = container.querySelectorAll('col')[3];
    const width = Number(String((actionCol as HTMLElement).style.width).replace('px', ''));
    expect(width).toBeGreaterThanOrEqual(130);

    const actionCell = screen.getByTestId('table-cell-0-actions');
    // The cell itself must not wrap either.
    expect(actionCell).toHaveClass('whitespace-nowrap');
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

  it('does not render the summary footer when no column declares an aggregate', () => {
    renderListTable();
    expect(screen.queryByTestId('list-summary-row')).not.toBeInTheDocument();
  });

  it('auto-renders the summary footer when a column declares an aggregate', () => {
    renderListTable({
      columns: [
        { field: 'name', label: 'Name', width: 200 },
        { field: 'amount', label: 'Amount', width: 140, aggregate: 'sum', align: 'right' } as any,
      ],
      data: [
        { pid: 'r1', name: 'A', amount: 100 },
        { pid: 'r2', name: 'B', amount: 250 },
      ],
    });
    const footer = screen.getByTestId('list-summary-row');
    expect(footer).toBeInTheDocument();
    // 100 + 250 = 350
    expect(screen.getByTestId('summary-cell-amount')).toHaveTextContent('350');
  });

  it('suppresses the summary footer when showSummaryRow is false even if aggregates exist', () => {
    renderListTable({
      showSummaryRow: false,
      columns: [
        { field: 'name', label: 'Name', width: 200 },
        { field: 'amount', label: 'Amount', width: 140, aggregate: 'sum', align: 'right' } as any,
      ],
      data: [{ pid: 'r1', name: 'A', amount: 100 }],
    });
    expect(screen.queryByTestId('list-summary-row')).not.toBeInTheDocument();
  });

  it('force-renders the summary footer when showSummaryRow is true', () => {
    renderListTable({
      showSummaryRow: true,
      columns: [
        { field: 'name', label: 'Name', width: 200, aggregate: 'count' } as any,
        { field: 'kind', label: 'Kind', width: 110 },
      ],
      data: [
        { pid: 'r1', name: 'A', kind: 'x' },
        { pid: 'r2', name: 'B', kind: 'y' },
      ],
    });
    expect(screen.getByTestId('list-summary-row')).toBeInTheDocument();
    expect(screen.getByTestId('summary-cell-name')).toHaveTextContent('2');
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

describe('ListTable expandable tree rows (T10)', () => {
  const treeColumns = [{ field: 'name', label: 'Name', width: 220 }];
  const treeData = [
    { pid: 'root', name: 'Root', parent_id: null },
    { pid: 'child1', name: 'Child One', parent_id: 'root' },
    { pid: 'child2', name: 'Child Two', parent_id: 'root' },
    { pid: 'grandchild', name: 'Grandchild', parent_id: 'child1' },
    { pid: 'root2', name: 'Root Two', parent_id: null },
  ];

  it('does not render any tree affordance when treeConfig is unset (flat mode)', () => {
    renderListTable({ columns: treeColumns, data: treeData });
    // Flat mode shows ALL rows (no expand state) and no chevron toggles.
    expect(screen.getByText('Grandchild')).toBeInTheDocument();
    expect(screen.queryByTestId('tree-toggle-root')).not.toBeInTheDocument();
    // 5 rows rendered flat.
    expect(screen.getByTestId('table-row-4')).toBeInTheDocument();
  });

  it('renders chevrons only on parent rows and indents by depth when expanded', () => {
    renderListTable({
      columns: treeColumns,
      data: treeData,
      treeConfig: { parentField: 'parent_id' },
    });
    // defaultExpanded → everything visible. Parents have toggles, leaves do not.
    expect(screen.getByTestId('tree-toggle-root')).toBeInTheDocument();
    expect(screen.getByTestId('tree-toggle-child1')).toBeInTheDocument();
    expect(screen.queryByTestId('tree-toggle-child2')).not.toBeInTheDocument();
    expect(screen.queryByTestId('tree-toggle-grandchild')).not.toBeInTheDocument();

    // Indent encoded via data-tree-depth on the row.
    const rows = screen.getAllByTestId(/^table-row-/);
    const depthByName = (name: string) =>
      Number(screen.getByText(name).closest('tr')?.getAttribute('data-tree-depth') ?? 'NaN');
    expect(depthByName('Root')).toBe(0);
    expect(depthByName('Child One')).toBe(1);
    expect(depthByName('Grandchild')).toBe(2);
    expect(depthByName('Root Two')).toBe(0);
    // 5 rows when fully expanded.
    expect(rows).toHaveLength(5);
  });

  it('collapsing a parent hides its subtree; expanding restores it', () => {
    renderListTable({
      columns: treeColumns,
      data: treeData,
      treeConfig: { parentField: 'parent_id' },
    });
    expect(screen.getByText('Grandchild')).toBeInTheDocument();

    // Collapse child1 → grandchild hidden, child2 (sibling) still visible.
    fireEvent.click(screen.getByTestId('tree-toggle-child1'));
    expect(screen.queryByText('Grandchild')).not.toBeInTheDocument();
    expect(screen.getByText('Child Two')).toBeInTheDocument();

    // Collapse root → both children gone, root2 stays.
    fireEvent.click(screen.getByTestId('tree-toggle-root'));
    expect(screen.queryByText('Child One')).not.toBeInTheDocument();
    expect(screen.queryByText('Child Two')).not.toBeInTheDocument();
    expect(screen.getByText('Root Two')).toBeInTheDocument();

    // Re-expand root → direct children return (grandchild stays hidden because
    // child1 is still collapsed).
    fireEvent.click(screen.getByTestId('tree-toggle-root'));
    expect(screen.getByText('Child One')).toBeInTheDocument();
    expect(screen.queryByText('Grandchild')).not.toBeInTheDocument();
  });

  it('starts collapsed (only roots) when defaultExpanded is false', () => {
    renderListTable({
      columns: treeColumns,
      data: treeData,
      treeConfig: { parentField: 'parent_id', defaultExpanded: false },
    });
    expect(screen.getByText('Root')).toBeInTheDocument();
    expect(screen.getByText('Root Two')).toBeInTheDocument();
    expect(screen.queryByText('Child One')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('tree-toggle-root'));
    expect(screen.getByText('Child One')).toBeInTheDocument();
  });

  it('toggle click does not trigger row navigation (stopPropagation)', () => {
    const onRowClick = vi.fn();
    renderListTable({
      columns: treeColumns,
      data: treeData,
      treeConfig: { parentField: 'parent_id' },
      onRowClick,
    });
    fireEvent.click(screen.getByTestId('tree-toggle-root'));
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it('exposes an aria-expanded + aria-label on the chevron toggle', () => {
    renderListTable({
      columns: treeColumns,
      data: treeData,
      treeConfig: { parentField: 'parent_id' },
    });
    const toggle = screen.getByTestId('tree-toggle-root');
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(toggle).toHaveAttribute('aria-label', 'Collapse row');
    fireEvent.click(toggle);
    // After collapse the SAME root toggle still exists (root is still a visible
    // parent) and flips its aria state.
    const toggleAfter = screen.getByTestId('tree-toggle-root');
    expect(toggleAfter).toHaveAttribute('aria-expanded', 'false');
    expect(toggleAfter).toHaveAttribute('aria-label', 'Expand row');
  });
});
