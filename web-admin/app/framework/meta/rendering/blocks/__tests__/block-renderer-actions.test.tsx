/**
 * block-renderer-actions.test.tsx
 *
 * Verifies that FormButtonsBlockRenderer, ToolbarBlockRenderer and TableBlockRenderer
 * route button clicks through the unified `useActionHandler.handleAction` dispatcher
 * (refactor from direct `runtime.executeHandler` calls).
 *
 * Coverage per renderer:
 * 1. New-format button (`button.action: { type: 'command', command }`) reaches handleAction.
 * 2. Legacy button (`button.events.onClick.handler` for FormButtons, bare `button.handler`
 *    for Toolbar/Table) also reaches handleAction.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent, waitFor } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks — must be declared BEFORE importing the SUT so the hook mock is in place
// when the renderer module is evaluated.
// ---------------------------------------------------------------------------

const handleActionSpy = vi.fn();
const useActionHandlerOptionsSpy = vi.fn();
const showSuccessToastSpy = vi.fn();
const showErrorToastSpy = vi.fn();
const showWarningToastSpy = vi.fn();
const showInfoToastSpy = vi.fn();
const hasPermissionSpy = vi.fn(() => true);

vi.mock('~/framework/meta/hooks/useActionHandler', () => ({
  useActionHandler: (options: any) => ({
    ...(useActionHandlerOptionsSpy(options) ?? {}),
    handleAction: handleActionSpy,
    loading: false,
    error: null,
    setError: vi.fn(),
  }),
}));

vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: 'test-token', hasPermission: hasPermissionSpy }),
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: showSuccessToastSpy,
    showErrorToast: showErrorToastSpy,
    showWarningToast: showWarningToastSpy,
    showInfoToast: showInfoToastSpy,
  }),
}));

// Avoid loading heavy tree-data hook internals.
vi.mock('~/framework/meta/hooks/useTreeData', () => ({
  useTreeData: (rows: any[]) => ({ visibleRows: rows, toggleExpand: vi.fn() }),
}));

// ---------------------------------------------------------------------------
// SUT imports (after mocks).
// ---------------------------------------------------------------------------

import { FormButtonsBlockRenderer } from '../FormButtonsBlockRenderer';
import { ToolbarBlockRenderer } from '../ToolbarBlockRenderer';
import { TableBlockRenderer } from '../TableBlockRenderer';
import { SelectionInfoBlockRenderer } from '../SelectionInfoBlockRenderer';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

// ---------------------------------------------------------------------------
// Runtime stub — only the methods the block renderers call.
// ---------------------------------------------------------------------------

function makeRuntime(overrides: Partial<any> = {}): SchemaRuntime {
  const context: Record<string, any> = {
    locale: 'en-US',
    t: (k: string) => k,
    form: {},
    global: {},
    state: {},
  };
  const updateState = vi.fn((scopeId: string, key: string, value: any) => {
    context.state[key] = value;
  });
  const stub = {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (tpl: string) => tpl,
    }),
    getSchema: () => ({ id: 'test_schema', modelCode: 'test_model' }),
    getDataSourceManager: () => ({
      getData: () => [],
      has: () => false,
      register: vi.fn(),
    }),
    getStateManager: () => ({
      updateState,
      getContext: () => context,
    }),
    getScopeId: () => 'scope-1',
    __updateState: updateState,
    ...overrides,
  };
  return stub as unknown as SchemaRuntime;
}

beforeEach(() => {
  handleActionSpy.mockReset();
  useActionHandlerOptionsSpy.mockReset();
  showSuccessToastSpy.mockReset();
  showErrorToastSpy.mockReset();
  showWarningToastSpy.mockReset();
  showInfoToastSpy.mockReset();
  hasPermissionSpy.mockReset();
  hasPermissionSpy.mockReturnValue(true);
});

// ---------------------------------------------------------------------------
// FormButtonsBlockRenderer
// ---------------------------------------------------------------------------

describe('FormButtonsBlockRenderer', () => {
  it('dispatches new-format action through useActionHandler', () => {
    const runtime = makeRuntime();
    const block = {
      type: 'form-buttons',
      buttons: [
        {
          code: 'save',
          label: 'Save',
          action: { type: 'command', command: 'saveOrder' },
        },
      ],
    };

    const { getByTestId } = render(
      <FormButtonsBlockRenderer block={block as any} runtime={runtime} />,
    );
    fireEvent.click(getByTestId('form-btn-save'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('save');
    expect(passedButton.action).toEqual({ type: 'command', command: 'saveOrder' });
  });

  it('dispatches legacy events.onClick.handler through useActionHandler', () => {
    const runtime = makeRuntime();
    const block = {
      type: 'form-buttons',
      buttons: [
        {
          code: 'submit',
          label: 'Submit',
          events: { onClick: { handler: 'submitForm' } },
        },
      ],
    };

    const { getByTestId } = render(
      <FormButtonsBlockRenderer block={block as any} runtime={runtime} />,
    );
    fireEvent.click(getByTestId('form-btn-submit'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('submit');
    expect(passedButton.events?.onClick?.handler).toBe('submitForm');
  });

  it('dispatches builtin refresh buttons without explicit action config', () => {
    const runtime = makeRuntime();
    const block = {
      type: 'form-buttons',
      buttons: [
        {
          code: 'refresh',
          label: 'Refresh',
        },
      ],
    };

    const { getByTestId } = render(
      <FormButtonsBlockRenderer block={block as any} runtime={runtime} />,
    );
    fireEvent.click(getByTestId('form-btn-refresh'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('refresh');
  });
});

// ---------------------------------------------------------------------------
// ToolbarBlockRenderer
// ---------------------------------------------------------------------------

describe('ToolbarBlockRenderer', () => {
  it('dispatches new-format action with record=undefined (toolbar has no row)', () => {
    const runtime = makeRuntime();
    const block = {
      type: 'toolbar',
      buttons: [
        {
          code: 'create',
          label: 'Create',
          action: { type: 'navigate', to: 'test_model_form' },
        },
      ],
    };

    const { getByTestId } = render(<ToolbarBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('toolbar-btn-create'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton, passedRecord] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('create');
    expect(passedButton.action).toEqual({ type: 'navigate', to: 'test_model_form' });
    // Toolbar buttons have no row context.
    expect(passedRecord).toBeUndefined();
  });

  it('disables the button while its action is in flight and ignores re-entrant double-clicks', async () => {
    const runtime = makeRuntime();
    let resolveAction: () => void = () => {};
    handleActionSpy.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          resolveAction = resolve;
        }),
    );
    const block = {
      type: 'toolbar',
      buttons: [
        {
          code: 'sync',
          label: 'Sync',
          action: { type: 'command', command: 'bom:sync_material_reconcile_now' },
        },
      ],
    };

    const { getByTestId } = render(<ToolbarBlockRenderer block={block as any} runtime={runtime} />);
    const btn = getByTestId('toolbar-btn-sync') as HTMLButtonElement;

    // First click starts the action; the button must disable while it is in flight.
    fireEvent.click(btn);
    await waitFor(() => expect(btn.disabled).toBe(true));

    // Rapid extra clicks while in flight must NOT fire the command again — this is
    // the front-line guard against double-triggering a sync into a duplicate insert.
    fireEvent.click(btn);
    fireEvent.click(btn);
    expect(handleActionSpy).toHaveBeenCalledTimes(1);

    // Once the action settles the button re-enables and can fire again.
    resolveAction();
    await waitFor(() => expect(btn.disabled).toBe(false));
    fireEvent.click(btn);
    expect(handleActionSpy).toHaveBeenCalledTimes(2);
  });

  it('dispatches legacy bare button.handler through useActionHandler (normalized to events.onClick.handler)', () => {
    const runtime = makeRuntime();
    const block = {
      type: 'toolbar',
      buttons: [
        {
          code: 'refresh',
          label: 'Refresh',
          handler: 'reloadList',
        },
      ],
    };

    const { getByTestId } = render(<ToolbarBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('toolbar-btn-refresh'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('refresh');
    // The renderer synthesises events.onClick.handler from legacy bare button.handler
    // so that useActionHandler's normalizeAction recognises it.
    expect(passedButton.events?.onClick?.handler).toBe('reloadList');
  });

  it('passes the page toast bridge to useActionHandler for upload feedback', () => {
    const runtime = makeRuntime();
    const block = {
      type: 'toolbar',
      buttons: [],
    };

    render(<ToolbarBlockRenderer block={block as any} runtime={runtime} />);

    const options = useActionHandlerOptionsSpy.mock.calls.at(-1)?.[0];
    expect(options?.showToast).toEqual(expect.any(Function));

    options.showToast('Imported', 'success');
    options.showToast('Failed', 'error');
    options.showToast('Check warnings', 'warning');
    options.showToast('Uploading', 'info');

    expect(showSuccessToastSpy).toHaveBeenCalledWith('Imported');
    expect(showErrorToastSpy).toHaveBeenCalledWith('Failed');
    expect(showWarningToastSpy).toHaveBeenCalledWith('Check warnings');
    expect(showInfoToastSpy).toHaveBeenCalledWith('Uploading');
  });
});

// ---------------------------------------------------------------------------
// TableBlockRenderer
// ---------------------------------------------------------------------------

describe('TableBlockRenderer', () => {
  const baseColumns = [{ field: 'name', label: 'Name' }];
  const baseRow = { id: 'row-1', pid: 'row-1', name: 'Alpha' };

  function makeRuntimeWithData(row: Record<string, unknown> = baseRow) {
    return makeRuntime({
      getDataSourceManager: () => ({
        getData: () => [row],
        has: () => true,
        register: vi.fn(),
      }),
    });
  }

  it('renders rows from table-adaptor data source records', () => {
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => ({ records: [baseRow], total: 1, current: 1, pageSize: 10 }),
        has: () => true,
        register: vi.fn(),
      }),
    });
    const block = {
      type: 'table',
      dataSource: 'list',
      columns: baseColumns,
    };

    const { getByText } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);

    expect(getByText('Alpha')).toBeInTheDocument();
  });

  it('renders link columns through authenticated file download URLs when a file id is present', () => {
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => [
          {
            pid: 'doc-1',
            qo_qd_url: '/01KV2GTQTNJR89R8EZQ1FT9TSQ.xlsx',
            qo_qd_file_id: '01KV2GTQTNJJ8QSYZS8R5A0YDC',
          },
        ],
        has: () => true,
        register: vi.fn(),
      }),
    });
    const block = {
      type: 'table',
      dataSource: 'list',
      columns: [
        {
          field: 'qo_qd_url',
          label: 'Download',
          valueType: 'link',
          render: { text: 'Download Quote', fileIdField: 'qo_qd_file_id' },
        },
      ],
    };

    const { getByRole } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);

    expect(getByRole('link', { name: 'Download Quote' })).toHaveAttribute(
      'href',
      '/api/file/download/01KV2GTQTNJJ8QSYZS8R5A0YDC',
    );
  });

  it('dispatches new-format row action with the row as record', () => {
    const runtime = makeRuntimeWithData();
    const block = {
      type: 'table',
      dataSource: 'list',
      columns: baseColumns,
      rowActions: [
        {
          code: 'edit',
          label: 'Edit',
          action: { type: 'navigate', to: 'test_model_form' },
        },
      ],
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('row-action-edit'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton, passedRecord] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('edit');
    expect(passedButton.action).toEqual({ type: 'navigate', to: 'test_model_form' });
    expect(passedRecord).toEqual(baseRow);
  });

  it('filters table row actions by permissionCode before rendering', () => {
    hasPermissionSpy.mockImplementation((code: string) => code !== 'iot.alarm.ack');
    const runtime = makeRuntimeWithData();
    const block = {
      type: 'table',
      dataSource: 'list',
      columns: baseColumns,
      rowActions: [
        {
          code: 'ack',
          label: 'Acknowledge',
          permissionCode: 'iot.alarm.ack',
          action: { type: 'command', command: 'iot_alarm_event:ack' },
        },
        {
          code: 'simulate',
          label: 'Simulate',
          permissionCode: 'iot.rule.read',
          action: { type: 'command', command: 'iot_rule:simulate' },
        },
        {
          code: 'view',
          label: 'View',
          action: { type: 'navigate', to: 'test_model_detail' },
        },
      ],
    };

    const { getByTestId, queryByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);

    expect(queryByTestId('row-action-ack')).toBeNull();
    expect(getByTestId('row-action-simulate')).toBeInTheDocument();
    expect(getByTestId('row-action-view')).toBeInTheDocument();
  });

  it('dispatches legacy bare button.handler through useActionHandler (normalized to events.onClick.handler)', () => {
    const runtime = makeRuntimeWithData();
    const block = {
      type: 'table',
      dataSource: 'list',
      columns: baseColumns,
      rowActions: [
        {
          code: 'delete',
          label: 'Delete',
          handler: 'deleteRow',
        },
      ],
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('row-action-delete'));

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton, passedRecord] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('delete');
    expect(passedButton.events?.onClick?.handler).toBe('deleteRow');
    expect(passedRecord).toEqual(baseRow);
  });

  it('dispatches action-column buttons from table.columns with disabled gates', () => {
    const runtime = makeRuntimeWithData();
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        columns: [
          ...baseColumns,
          {
            field: 'actions',
            label: 'Actions',
            isActionColumn: true,
            buttons: [
              {
                code: 'delete_source_attachment',
                label: 'Delete',
                danger: true,
                action: { type: 'command', command: 'qo_rfq_source_attachment_common:delete' },
              },
              {
                code: 'confirm_line_exclusion',
                label: 'Confirm Exclusion',
                disabledWhen: "record.status !== 'pending_exclusion'",
                action: { type: 'command', command: 'bom:set_standard_line_exclusion' },
              },
            ],
          },
        ],
      },
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);

    const deleteButton = getByTestId('row-action-delete_source_attachment');
    fireEvent.click(deleteButton);

    expect(handleActionSpy).toHaveBeenCalledTimes(1);
    const [passedButton, passedRecord] = handleActionSpy.mock.calls[0];
    expect(passedButton.code).toBe('delete_source_attachment');
    expect(passedButton.action).toEqual({
      type: 'command',
      command: 'qo_rfq_source_attachment_common:delete',
    });
    expect(passedRecord).toEqual(baseRow);
    expect(getByTestId('row-action-confirm_line_exclusion')).toBeDisabled();
  });

  it('applies configured rowClassRules with record/row aliases', () => {
    const row = { id: 'row-1', pid: 'row-1', name: 'Alpha', status: 'confirmed_excluded' };
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => [row],
        has: () => true,
        register: vi.fn(),
      }),
      getEvaluator: () => ({
        evaluateCondition: (expr: string, ctx: any) => ctx.record.status === 'confirmed_excluded',
        evaluateTemplate: (tpl: string) => tpl,
      }),
    });
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowClassRules: [
          {
            when: "record.status === 'confirmed_excluded'",
            className: 'bg-rose-50 text-rose-800',
          },
        ],
        columns: baseColumns,
      },
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);

    expect(getByTestId('table-row-row-1')).toHaveClass('bg-rose-50', 'text-rose-800');
  });

  it('writes clicked row into runtime state when table.selection.bind is configured', () => {
    const runtime = makeRuntimeWithData() as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: { mode: 'single', bind: 'selectedLine' },
        columns: baseColumns,
      },
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('table-row-row-1'));

    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedLine', baseRow);
  });

  it('uses configured rowKey for row identity and highlights the clicked row immediately', () => {
    const row = { lineNo: 'L-001', name: 'Beta' };
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => [row],
        has: () => true,
        register: vi.fn(),
      }),
    }) as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'lineNo',
        selection: { mode: 'single', bind: 'selectedLine' },
        columns: baseColumns,
      },
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    const tableRow = getByTestId('table-row-L-001');
    fireEvent.click(tableRow);

    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedLine', row);
    expect(tableRow.className).toContain('bg-accent-weak');
  });

  it('constrains table overflow when maxHeight is configured', () => {
    const runtime = makeRuntimeWithData();
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        maxHeight: 360,
        columns: baseColumns,
      },
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    const tableBlock = getByTestId('table-block');

    expect(tableBlock).toHaveClass('w-full', 'max-w-full', 'overflow-x-auto', 'overflow-y-auto');
    expect(tableBlock).toHaveStyle({
      maxHeight: '360px',
      width: '100%',
      maxWidth: '100%',
    });
  });

  it('auto-selects the first visible row when selection.defaultFirst is enabled', async () => {
    const runtime = makeRuntimeWithData() as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: { mode: 'single', bind: 'selectedLine', defaultFirst: true },
        columns: baseColumns,
      },
    };

    render(<TableBlockRenderer block={block as any} runtime={runtime} />);

    await waitFor(() => {
      expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedLine', baseRow);
    });
  });

  it('uses compact table density when configured', () => {
    const runtime = makeRuntimeWithData();
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        density: 'compact',
        columns: baseColumns,
      },
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);

    expect(getByTestId('table-th-name')).toHaveClass('px-3', 'py-2');
    expect(getByTestId('table-row-row-1').querySelector('td')).toHaveClass('px-3', 'py-2');
  });

  it('applies body alignment and full-value title for ellipsis cells', () => {
    const runtime = makeRuntimeWithData({
      ...baseRow,
      name: 'C100120003500 / very long material specification that should be truncated',
    });
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        columns: [
          { field: 'name', label: { en: 'Name' }, width: 120, align: 'center', ellipsis: true },
        ],
      },
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    const cell = getByTestId('table-row-row-1').querySelector('td');

    expect(cell).toHaveClass('text-center', 'truncate');
    expect(cell).toHaveAttribute(
      'title',
      'C100120003500 / very long material specification that should be truncated',
    );
  });
});

describe('SelectionInfoBlockRenderer', () => {
  it('renders selection count and row label from configured runtime state binding', () => {
    const runtime = makeRuntime({
      getContext: () => ({
        locale: 'en-US',
        t: (k: string) => k,
        state: {
          selectedLine: { pid: 'row-1', title: 'Copper audit line' },
        },
      }),
    });
    const block = {
      blockType: 'selection-info',
      title: 'Selected record',
      selection: { bind: 'selectedLine' },
    };

    const { getByTestId } = render(
      <SelectionInfoBlockRenderer block={block as any} runtime={runtime} />,
    );

    expect(getByTestId('selection-info-title')).toHaveTextContent('Selected record');
    expect(getByTestId('selection-info-count')).toHaveTextContent('1');
    expect(getByTestId('selection-info-label')).toHaveTextContent('Copper audit line');
  });
});
