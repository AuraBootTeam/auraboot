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
import { act, render, fireEvent, waitFor } from '@testing-library/react';

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
const hasPermissionSpy = vi.fn<(code?: string) => boolean>(() => true);

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
import { readServerTablePage, ServerTablePageError } from '../serverTablePage';
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
  it('filters form buttons by permissionCode before rendering', () => {
    hasPermissionSpy.mockImplementation((code?: string) => code !== 'qtr.impact.compute');
    const runtime = makeRuntime();
    const block = {
      type: 'form-buttons',
      buttons: [
        {
          code: 'compute',
          label: 'Compute impact',
          permissionCode: 'qtr.impact.compute',
          action: { type: 'command', command: 'qtr:request_impact_snapshot' },
        },
        { code: 'cancel', label: 'Cancel', action: { type: 'builtin', name: 'back' } },
      ],
    };

    const { getByTestId, queryByTestId } = render(
      <FormButtonsBlockRenderer block={block as any} runtime={runtime} />,
    );

    expect(queryByTestId('form-btn-compute')).toBeNull();
    expect(getByTestId('form-btn-cancel')).toBeInTheDocument();
  });

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
  it('filters toolbar buttons by permissionCode before rendering', () => {
    hasPermissionSpy.mockImplementation((code?: string) => code !== 'model.test_model.update');
    const runtime = makeRuntime();
    const block = {
      type: 'toolbar',
      buttons: [
        {
          code: 'edit',
          label: 'Edit',
          permissionCode: 'model.test_model.update',
          action: { type: 'navigate', to: 'test_model_form' },
        },
        {
          code: 'view-audit',
          label: 'View audit',
          permissionCode: 'model.test_model.read',
          action: { type: 'navigate', to: 'test_model_audit' },
        },
        {
          code: 'back',
          label: 'Back',
          action: { type: 'navigate', to: 'test_model_list' },
        },
      ],
    };

    const { getByTestId, queryByTestId } = render(
      <ToolbarBlockRenderer block={block as any} runtime={runtime} />,
    );

    expect(queryByTestId('toolbar-btn-edit')).toBeNull();
    expect(getByTestId('toolbar-btn-view-audit')).toBeInTheDocument();
    expect(getByTestId('toolbar-btn-back')).toBeInTheDocument();
  });

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

  it('passes the current detail record to the shared action context', () => {
    const detailRecord = { pid: 'DETAIL-1', account_id: 'ACCOUNT-1' };
    const runtime = makeRuntime({
      getContext: () => ({
        locale: 'zh-CN',
        t: (key: string) => key,
        record: detailRecord,
        row: detailRecord,
        form: detailRecord,
        state: {},
        global: {},
      }),
    });

    render(
      <ToolbarBlockRenderer block={{ type: 'toolbar', buttons: [] } as any} runtime={runtime} />,
    );

    const options = useActionHandlerOptionsSpy.mock.calls.at(-1)?.[0];
    expect(options?.context?.record).toBe(detailRecord);
  });

  it('does not pass an empty early form as a detail record', () => {
    const runtime = makeRuntime();

    render(
      <ToolbarBlockRenderer block={{ type: 'toolbar', buttons: [] } as any} runtime={runtime} />,
    );

    const options = useActionHandlerOptionsSpy.mock.calls.at(-1)?.[0];
    expect(options?.context?.record).toBeUndefined();
  });

  it('prefers a detail-bound toolbar record over unsynchronized runtime context', () => {
    const detailRecord = { pid: 'DETAIL-BOUND-1', crm_opp_account_id: 'ACCOUNT-BOUND-1' };
    const runtime = makeRuntime();

    render(
      <ToolbarBlockRenderer
        block={{ type: 'toolbar', record: detailRecord, buttons: [] } as any}
        runtime={runtime}
      />,
    );

    const options = useActionHandlerOptionsSpy.mock.calls.at(-1)?.[0];
    expect(options?.context?.record).toBe(detailRecord);
  });

  it('evaluates visibility against the detail-bound parent record', () => {
    const evaluateCondition = vi.fn(() => true);
    const detailRecord = { pid: 'OPP-PARENT-1', crm_opp_stage: 'proposal' };
    const runtime = makeRuntime({
      getEvaluator: () => ({
        evaluateCondition,
        evaluateTemplate: (template: string) => template,
      }),
    });

    render(
      <ToolbarBlockRenderer
        block={
          {
            type: 'toolbar',
            record: detailRecord,
            buttons: [
              { code: 'add', label: 'Add', visibleWhen: "record.crm_opp_stage == 'proposal'" },
            ],
          } as any
        }
        runtime={runtime}
      />,
    );

    expect(evaluateCondition).toHaveBeenCalledWith(
      "record.crm_opp_stage == 'proposal'",
      expect.objectContaining({
        record: detailRecord,
        row: detailRecord,
        form: detailRecord,
      }),
    );
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

  function makeRuntimeWithRows(rows: Array<Record<string, unknown>>) {
    return makeRuntime({
      getDataSourceManager: () => ({
        getData: () => rows,
        has: () => true,
        register: vi.fn(),
      }),
    });
  }

  function externalState(runtime: any) {
    const listeners = new Set<() => void>();
    runtime.subscribeContext = (listener: () => void) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    };
    return (values: Record<string, unknown>) => {
      Object.assign(runtime.getContext().state, values);
      listeners.forEach((listener) => listener());
    };
  }

  it.each([[], null, undefined])('clears bound checkboxes and derived ids on external reset %j without reviving rows', async (reset) => {
    const rows = [
      { pid: 'first', name: 'First' },
      { pid: 'second', name: 'Second' },
    ];
    const runtime = makeRuntimeWithRows(rows) as any;
    const publish = externalState(runtime);
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: { mode: 'multiple', bind: 'chosen', idsBind: 'chosenIds' },
        columns: baseColumns,
      },
    };
    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('table-select-row-first'));
    expect(getByTestId('table-select-row-first')).toBeChecked();
    expect(runtime.getContext().state.chosenIds).toEqual(['first']);
    act(() => publish({ chosen: reset }));
    await waitFor(() => expect(getByTestId('table-select-row-first')).not.toBeChecked());
    expect(runtime.getContext().state.chosenIds).toEqual([]);
    fireEvent.click(getByTestId('table-select-row-second'));
    expect(runtime.getContext().state.chosen).toEqual([rows[1]]);
    expect(runtime.getContext().state.chosenIds).toEqual(['second']);
    expect(getByTestId('table-select-row-first')).not.toBeChecked();
  });

  it('uses a replacement external row selection instead of stale local keys', async () => {
    const rows = [
      { pid: 'first', name: 'First' },
      { pid: 'second', name: 'Second' },
    ];
    const runtime = makeRuntimeWithRows(rows) as any;
    const publish = externalState(runtime);
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: { mode: 'multiple', bind: 'chosen' },
        columns: baseColumns,
      },
    };
    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('table-select-row-first'));
    act(() => publish({ chosen: [rows[1]] }));
    await waitFor(() => expect(getByTestId('table-select-row-second')).toBeChecked());
    expect(getByTestId('table-select-row-first')).not.toBeChecked();
    fireEvent.click(getByTestId('table-select-row-first'));
    expect(runtime.getContext().state.chosen).toEqual([rows[1], rows[0]]);
  });

  it('retains choices on other pages while replacing exactly one exclusive group', () => {
    const first = { tupleKey: 'app-a-v1', applicationPid: 'a', name: 'A first' };
    const other = { tupleKey: 'app-b-v1', applicationPid: 'b', name: 'B' };
    const replacement = { tupleKey: 'app-a-v2', applicationPid: 'a', name: 'A second' };
    const visible = [first, other];
    const runtime = makeRuntimeWithRows(visible) as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'tupleKey',
        selection: { mode: 'multiple', bind: 'chosen', exclusiveBy: 'applicationPid' },
        columns: baseColumns,
      },
    };
    const view = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(view.getByTestId('table-select-row-app-a-v1'));
    fireEvent.click(view.getByTestId('table-select-row-app-b-v1'));
    visible.splice(0, visible.length, replacement);
    view.rerender(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(view.getByTestId('table-select-row-app-a-v2'));
    expect(runtime.getContext().state.chosen).toEqual([other, replacement]);
    visible.splice(0, visible.length, first, other);
    view.rerender(<TableBlockRenderer block={block as any} runtime={runtime} />);
    expect(view.getByTestId('table-select-row-app-a-v1')).not.toBeChecked();
    expect(view.getByTestId('table-select-row-app-b-v1')).toBeChecked();
  });

  it('preserves off-page grouped choices and replaces the same group by value', async () => {
    const first = { tupleKey: 'a-first', applicationPid: 'a', name: 'A first' };
    const other = { tupleKey: 'b-first', applicationPid: 'b', name: 'B' };
    const replacement = { tupleKey: 'a-second', applicationPid: 'a', name: 'A second' };
    let page = { records: [first, other], total: 3, current: 1, pageSize: 2 };
    const runtime = makeRuntime({ getDataSourceManager: () => ({ getData: () => page, has: () => true, register: vi.fn() }) }) as any;
    const block = { type: 'table', dataSource: 'list', table: { rowKey: 'tupleKey', pagination: { mode: 'server', pageField: 'current' },
      selection: { mode: 'multiple', bind: 'chosen', exclusiveBy: 'applicationPid', presentation: 'grouped-radio', optionLabelField: 'name' }, columns: baseColumns } };
    const view = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(view.getByTestId('table-select-row-a-first'));
    fireEvent.click(view.getByTestId('table-select-row-b-first'));
    page = { records: [replacement], total: 3, current: 2, pageSize: 2 };
    view.rerender(<TableBlockRenderer block={block as any} runtime={runtime} />);
    await waitFor(() => expect(runtime.getContext().state.chosen).toEqual([first, other]));
    fireEvent.click(view.getByTestId('table-select-row-a-second'));
    expect(runtime.getContext().state.chosen).toEqual([other, replacement]);
    expect(view.getByTestId('table-select-row-a-second')).toBeChecked();
    page = { records: [first, other], total: 3, current: 1, pageSize: 2 };
    view.rerender(<TableBlockRenderer block={block as any} runtime={runtime} />);
    expect(view.getByTestId('table-select-row-a-first')).not.toBeChecked();
    expect(view.getByTestId('table-select-row-b-first')).toBeChecked();
  });

  it('does not infer a unique safe recommendation from a partial server page', () => {
    const row = { pid: 'one-visible', issueId: 'a', name: 'First page', recommended: true, safe: true };
    const runtime = makeRuntime({ getDataSourceManager: () => ({ getData: () => ({ records: [row], total: 2, current: 1, pageSize: 1 }), has: () => true, register: vi.fn() }) }) as any;
    const block = { type: 'table', dataSource: 'list', table: { rowKey: 'pid', pagination: { mode: 'server', pageField: 'current' },
      selection: { mode: 'multiple', bind: 'chosen', exclusiveBy: 'issueId', presentation: 'grouped-radio', optionLabelField: 'name', recommendedField: 'recommended', safeField: 'safe' }, columns: baseColumns } };
    const view = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    expect(runtime.getContext().state.chosen).toBeUndefined();
    expect(view.getByTestId('table-select-row-one-visible')).not.toBeChecked();
  });

  it('reflects an externally cleared single selection without a datasource reload', async () => {
    const rows = [{ pid: 'first', name: 'First' }];
    const runtime = makeRuntimeWithRows(rows) as any;
    const publish = externalState(runtime);
    const block = {
      type: 'table',
      dataSource: 'list',
      table: { rowKey: 'pid', selection: { mode: 'single', bind: 'chosen' }, columns: baseColumns },
    };
    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('table-row-first'));
    expect(getByTestId('table-row-first')).toHaveClass('bg-accent-weak');
    act(() => publish({ chosen: null }));
    await waitFor(() => expect(getByTestId('table-row-first')).not.toHaveClass('bg-accent-weak'));
    expect(runtime.getContext().state.chosen).toBeNull();
  });

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
    hasPermissionSpy.mockImplementation((code?: string) => code !== 'iot.alarm.ack');
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

    const { getByTestId, queryByTestId } = render(
      <TableBlockRenderer block={block as any} runtime={runtime} />,
    );

    expect(queryByTestId('row-action-ack')).toBeNull();
    expect(getByTestId('row-action-simulate')).toBeInTheDocument();
    expect(queryByTestId('row-action-view')).toBeNull();
    fireEvent.click(getByTestId('row-action-more'));
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

  it('writes multiple selected rows and derived ids when table.selection.mode is multiple', () => {
    const rows = [
      { id: 'row-1', pid: 'row-1', name: 'Alpha' },
      { id: 'row-2', pid: 'row-2', name: 'Beta' },
    ];
    const runtime = makeRuntimeWithRows(rows) as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: {
          mode: 'multiple',
          bind: 'selectedLines',
          detailBind: 'selectedLine',
          idsBind: 'selectedLineIds',
          idField: 'pid',
        },
        columns: baseColumns,
      },
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(getByTestId('table-select-row-row-1'));
    fireEvent.click(getByTestId('table-select-row-row-2'));

    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedLines', [rows[0]]);
    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedLine', rows[0]);
    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedLineIds', ['row-1']);
    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedLines', rows);
    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedLine', rows[1]);
    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedLineIds', [
      'row-1',
      'row-2',
    ]);
  });

  it('keeps exactly one selected row per configured exclusive group', () => {
    const rows = [
      { pid: 'issue-a-keep', issueId: 'issue-a', action: 'keep' },
      { pid: 'issue-a-return', issueId: 'issue-a', action: 'return' },
      { pid: 'issue-b-keep', issueId: 'issue-b', action: 'keep' },
    ];
    const runtime = makeRuntimeWithRows(rows) as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: {
          mode: 'multiple',
          bind: 'selectedDecisions',
          exclusiveBy: 'issueId',
        },
        columns: [{ field: 'action', label: 'Action' }],
      },
    };

    const { getByTestId, queryByTestId } = render(
      <TableBlockRenderer block={block as any} runtime={runtime} />,
    );
    expect(queryByTestId('table-select-all')).not.toBeInTheDocument();

    fireEvent.click(getByTestId('table-select-row-issue-a-keep'));
    fireEvent.click(getByTestId('table-select-row-issue-a-return'));
    fireEvent.click(getByTestId('table-select-row-issue-b-keep'));

    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedDecisions', [rows[0]]);
    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedDecisions', [rows[1]]);
    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedDecisions', [
      rows[1],
      rows[2],
    ]);
  });

  it('renders grouped-radio decisions without defaults and preserves choices in other groups', () => {
    const rows = [
      {
        pid: 'issue-a-keep',
        issueId: 'issue-a',
        line: '52',
        issue: 'Quantity differs from reference count',
        actionLabel: 'Keep source quantity',
      },
      {
        pid: 'issue-a-recalculate',
        issueId: 'issue-a',
        line: '52',
        issue: 'Quantity differs from reference count',
        actionLabel: 'Recalculate quantity',
      },
      {
        pid: 'issue-b-keep',
        issueId: 'issue-b',
        line: '81',
        issue: 'Package needs confirmation',
        actionLabel: 'Keep source package',
      },
      {
        pid: 'issue-b-return',
        issueId: 'issue-b',
        line: '81',
        issue: 'Package needs confirmation',
        actionLabel: 'Return to source file',
      },
    ];
    const runtime = makeRuntimeWithRows(rows) as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: {
          mode: 'multiple',
          bind: 'selectedDecisions',
          presentation: 'grouped-radio',
          exclusiveBy: 'issueId',
          optionLabelField: 'actionLabel',
        },
        columns: [
          { field: 'line', label: 'Source line' },
          { field: 'issue', label: 'Issue' },
          { field: 'actionLabel', label: 'Action' },
        ],
      },
    };

    const { getAllByRole, getByTestId, queryByTestId } = render(
      <TableBlockRenderer block={block as any} runtime={runtime} />,
    );

    expect(getAllByRole('radiogroup')).toHaveLength(2);
    expect(getAllByRole('radio')).toHaveLength(4);
    expect(
      getAllByRole('radio').every((radio: HTMLElement) => !(radio as HTMLInputElement).checked),
    ).toBe(true);
    expect(queryByTestId('table-select-all')).not.toBeInTheDocument();
    expect(getByTestId('table-selection-group-0-issue-a-field-line')).toHaveTextContent('52');
    expect(getByTestId('table-selection-group-1-issue-b-field-line')).toHaveTextContent('81');

    fireEvent.click(getByTestId('table-select-row-issue-a-keep'));
    fireEvent.click(getByTestId('table-select-row-issue-b-return'));
    fireEvent.click(getByTestId('table-select-row-issue-a-recalculate'));

    const finalRows = runtime.__updateState.mock.calls
      .filter((call: any[]) => call[1] === 'selectedDecisions')
      .at(-1)?.[2];
    expect(finalRows).toEqual([rows[1], rows[3]]);
    expect(getByTestId('table-select-row-issue-a-recalculate')).toBeChecked();
    expect(getByTestId('table-select-row-issue-b-return')).toBeChecked();
  });

  it('preselects only one explicitly recommended and safe option per group', async () => {
    const rows = [
      {
        pid: 'safe-keep',
        issueId: 'safe',
        actionLabel: 'Keep source',
        recommended: true,
        safe: true,
      },
      {
        pid: 'safe-return',
        issueId: 'safe',
        actionLabel: 'Return to source',
        recommended: false,
        safe: false,
      },
      {
        pid: 'unsafe-recommendation',
        issueId: 'unsafe',
        actionLabel: 'Rewrite value',
        recommended: true,
        safe: false,
      },
      {
        pid: 'duplicate-a',
        issueId: 'duplicate',
        actionLabel: 'First recommendation',
        recommended: true,
        safe: true,
      },
      {
        pid: 'duplicate-b',
        issueId: 'duplicate',
        actionLabel: 'Second recommendation',
        recommended: true,
        safe: true,
      },
      {
        pid: 'string-flags',
        issueId: 'non-boolean',
        actionLabel: 'String flags',
        recommended: 'true',
        safe: 'true',
      },
      {
        pid: 'missing-group',
        actionLabel: 'Missing group identity',
        recommended: true,
        safe: true,
      },
    ];
    const runtime = makeRuntimeWithRows(rows) as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: {
          mode: 'multiple',
          bind: 'selectedDecisions',
          presentation: 'grouped-radio',
          exclusiveBy: 'issueId',
          optionLabelField: 'actionLabel',
          recommendedField: 'recommended',
          safeField: 'safe',
        },
        columns: [{ field: 'actionLabel', label: 'Action' }],
      },
    };

    const view = render(<TableBlockRenderer block={block as any} runtime={runtime} />);

    await waitFor(() => {
      expect(runtime.getContext().state.selectedDecisions).toEqual([rows[0]]);
    });
    expect(view.getByTestId('table-select-row-safe-keep')).toBeChecked();
    expect(view.getByTestId('table-select-row-unsafe-recommendation')).not.toBeChecked();
    expect(view.getByTestId('table-select-row-duplicate-a')).not.toBeChecked();
    expect(view.getByTestId('table-select-row-duplicate-b')).not.toBeChecked();
    expect(view.getByTestId('table-select-row-string-flags')).not.toBeChecked();
    expect(view.getByTestId('table-select-row-missing-group')).not.toBeChecked();
  });

  it('does not preselect when grouped-radio lacks an explicit stable row identity', () => {
    const rows = [
      {
        pid: 'inferred-but-not-contracted',
        issueId: 'issue-a',
        actionLabel: 'Keep source',
        recommended: true,
        safe: true,
      },
    ];
    const runtime = makeRuntimeWithRows(rows) as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        selection: {
          mode: 'multiple',
          bind: 'selectedDecisions',
          presentation: 'grouped-radio',
          exclusiveBy: 'issueId',
          optionLabelField: 'actionLabel',
          recommendedField: 'recommended',
          safeField: 'safe',
        },
        columns: [{ field: 'actionLabel', label: 'Action' }],
      },
    };

    const view = render(<TableBlockRenderer block={block as any} runtime={runtime} />);

    expect(runtime.getContext().state.selectedDecisions).toBeUndefined();
    expect(view.getByTestId('table-select-row-inferred-but-not-contracted')).not.toBeChecked();
  });

  it('preserves user choices across refresh and safely defaults only new unselected groups', async () => {
    type DecisionRow = {
      pid: string;
      issueId: string;
      actionLabel: string;
      recommended: boolean;
      safe: boolean;
      snapshot?: string;
    };
    let rows: DecisionRow[] = [
      {
        pid: 'issue-a-keep',
        issueId: 'issue-a',
        actionLabel: 'Keep source',
        recommended: true,
        safe: true,
      },
      {
        pid: 'issue-a-return',
        issueId: 'issue-a',
        actionLabel: 'Return to source',
        recommended: false,
        safe: false,
      },
      {
        pid: 'issue-b-keep',
        issueId: 'issue-b',
        actionLabel: 'Keep source',
        recommended: false,
        safe: false,
      },
      {
        pid: 'issue-b-return',
        issueId: 'issue-b',
        actionLabel: 'Return to source',
        recommended: false,
        safe: false,
      },
    ];
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => rows,
        has: () => true,
        register: vi.fn(),
      }),
    }) as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: {
          mode: 'multiple',
          bind: 'selectedDecisions',
          presentation: 'grouped-radio',
          exclusiveBy: 'issueId',
          optionLabelField: 'actionLabel',
          recommendedField: 'recommended',
          safeField: 'safe',
        },
        columns: [{ field: 'actionLabel', label: 'Action' }],
      },
    };
    const view = render(<TableBlockRenderer block={block as any} runtime={runtime} />);

    await waitFor(() => {
      expect(runtime.getContext().state.selectedDecisions).toEqual([rows[0]]);
    });
    fireEvent.click(view.getByTestId('table-select-row-issue-a-return'));
    fireEvent.click(view.getByTestId('table-select-row-issue-b-return'));

    rows = [
      { ...rows[0], recommended: true, safe: true, snapshot: 'refreshed' },
      { ...rows[1], recommended: false, safe: false, snapshot: 'refreshed' },
      { ...rows[2], recommended: true, safe: true, snapshot: 'refreshed' },
      { ...rows[3], snapshot: 'refreshed' },
      {
        pid: 'issue-c-keep',
        issueId: 'issue-c',
        actionLabel: 'Keep source',
        recommended: true,
        safe: true,
        snapshot: 'refreshed',
      },
      {
        pid: 'issue-c-return',
        issueId: 'issue-c',
        actionLabel: 'Return to source',
        recommended: false,
        safe: false,
        snapshot: 'refreshed',
      },
    ];
    view.rerender(<TableBlockRenderer block={block as any} runtime={runtime} />);

    await waitFor(() => {
      expect(runtime.getContext().state.selectedDecisions).toEqual([rows[1], rows[3], rows[4]]);
    });
    expect(view.getByTestId('table-select-row-issue-a-return')).toBeChecked();
    expect(view.getByTestId('table-select-row-issue-b-return')).toBeChecked();
    expect(view.getByTestId('table-select-row-issue-c-keep')).toBeChecked();
  });

  it('moves grouped-radio selection with arrow keys inside one group', () => {
    const rows = [
      { pid: 'issue-a-keep', issueId: 'issue-a', actionLabel: 'Keep source' },
      { pid: 'issue-a-return', issueId: 'issue-a', actionLabel: 'Return to source' },
    ];
    const runtime = makeRuntimeWithRows(rows) as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: {
          mode: 'multiple',
          bind: 'selectedDecisions',
          presentation: 'grouped-radio',
          exclusiveBy: 'issueId',
          optionLabelField: 'actionLabel',
        },
        columns: [{ field: 'actionLabel', label: 'Action' }],
      },
    };

    const { getByTestId } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    const firstRadio = getByTestId('table-select-row-issue-a-keep');
    firstRadio.focus();
    fireEvent.keyDown(firstRadio, { key: 'ArrowDown' });

    expect(getByTestId('table-select-row-issue-a-return')).toBeChecked();
    expect(document.activeElement).toBe(getByTestId('table-select-row-issue-a-return'));
    expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedDecisions', [rows[1]]);
  });

  it('drops grouped-radio choices that no longer exist after a data-source reload', async () => {
    let rows = [
      { pid: 'old-issue-keep', issueId: 'old-issue', actionLabel: 'Keep source' },
      { pid: 'old-issue-return', issueId: 'old-issue', actionLabel: 'Return to source' },
    ];
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => rows,
        has: () => true,
        register: vi.fn(),
      }),
    }) as any;
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: {
          mode: 'multiple',
          bind: 'selectedDecisions',
          presentation: 'grouped-radio',
          exclusiveBy: 'issueId',
          optionLabelField: 'actionLabel',
        },
        columns: [{ field: 'actionLabel', label: 'Action' }],
      },
    };

    const view = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    fireEvent.click(view.getByTestId('table-select-row-old-issue-keep'));
    expect(runtime.getContext().state.selectedDecisions).toEqual([rows[0]]);

    rows = [
      { pid: 'new-issue-keep', issueId: 'new-issue', actionLabel: 'Keep source' },
      { pid: 'new-issue-return', issueId: 'new-issue', actionLabel: 'Return to source' },
    ];
    view.rerender(<TableBlockRenderer block={block as any} runtime={runtime} />);

    await waitFor(() => {
      expect(runtime.getContext().state.selectedDecisions).toEqual([]);
    });
    expect(
      view
        .getAllByRole('radio')
        .every((radio: HTMLElement) => !(radio as HTMLInputElement).checked),
    ).toBe(true);
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

  it.each([true, false])(
    'refreshes the bound selected row with defaultFirst=%s',
    async (defaultFirst) => {
      const rows = {
        current: [{ id: 'row-1', pid: 'row-1', name: 'Alpha', status: 'draft' }],
      };
      const runtime = makeRuntime({
        getDataSourceManager: () => ({
          getData: () => rows.current,
          has: () => true,
          register: vi.fn(),
        }),
      }) as any;
      runtime.getContext().state.selectedLine = rows.current[0];
      const block = {
        type: 'table',
        dataSource: 'list',
        table: {
          rowKey: 'pid',
          selection: { mode: 'single', bind: 'selectedLine', defaultFirst },
          columns: baseColumns,
        },
      };

      const { rerender } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
      const refreshedRow = { id: 'row-1', pid: 'row-1', name: 'Alpha', status: 'released' };
      rows.current = [refreshedRow];
      rerender(<TableBlockRenderer block={block as any} runtime={runtime} />);

      await waitFor(() => {
        expect(runtime.__updateState).toHaveBeenCalledWith('scope-1', 'selectedLine', refreshedRow);
      });
    },
  );

  it('clears a removed explicit selection without choosing another visible row', async () => {
    const rows = { current: [{ pid: 'selected', name: 'Selected' }] };
    const runtime = makeRuntime({
      getDataSourceManager: () => ({
        getData: () => rows.current,
        has: () => true,
        register: vi.fn(),
      }),
    }) as any;
    runtime.getContext().state.selectedLine = rows.current[0];
    const block = {
      type: 'table',
      dataSource: 'list',
      table: {
        rowKey: 'pid',
        selection: { mode: 'single', bind: 'selectedLine' },
        columns: baseColumns,
      },
    };
    const { rerender } = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    rows.current = [{ pid: 'other', name: 'Other' }];
    rerender(<TableBlockRenderer block={block as any} runtime={runtime} />);
    await waitFor(() => expect(runtime.getContext().state.selectedLine).toBeNull());
    expect(runtime.__updateState).not.toHaveBeenCalledWith(
      'scope-1',
      'selectedLine',
      rows.current[0],
    );
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


describe('server-paged table contract', () => {
  it('shows configured business explanations for empty array classifications', () => {
    const manager = { getData: () => [{ pid: 'person-1', departments: [], roles: [] }], has: () => true, register: vi.fn() };
    const runtime = makeRuntime({ getDataSourceManager: () => manager });
    const view = render(<TableBlockRenderer block={{ type: 'table', dataSource: 'people', columns: [
      { field: 'departments', label: 'Departments', emptyText: { 'zh-CN': '无可显示部门', 'en-US': 'No visible department' } },
      { field: 'roles', label: 'Matched roles', emptyText: { 'zh-CN': '无可显示角色', 'en-US': 'No visible roles' } },
    ] } as any} runtime={runtime} />);
    expect(view.getByText('No visible department')).toBeInTheDocument();expect(view.getByText('No visible roles')).toBeInTheDocument();
  });

  const rows = (start: number, count: number) => Array.from({ length: count }, (_, i) => ({ pid: `row-${start + i}`, name: `Person ${start + i}` }));
  const block = { type: 'table', dataSource: 'recipients', table: { columns: [{ field: 'name', label: 'Name' }], pagination: { mode: 'server' } } };
  const fixture = () => {
    let page: unknown = { records: rows(1, 20), pageNum: 1, pageSize: 20, total: 45 };
    let state: { loading: boolean; error: unknown } = { loading: false, error: null };
    const fetch = vi.fn().mockResolvedValue(undefined);
    const reload = vi.fn();
    const manager = { getData: () => page, getState: () => state, has: () => true, register: vi.fn(), fetch, reload };
    const runtime = makeRuntime({ getDataSourceManager: () => manager });
    const view = render(<TableBlockRenderer block={block as any} runtime={runtime} />);
    return { ...view, fetch, reload, update: (next: unknown, nextState = { loading: false, error: null } as typeof state) => {
      page = next; state = nextState; view.rerender(<TableBlockRenderer block={block as any} runtime={runtime} />);
    } };
  };

  it('requests the selected page and accepts navigation only from server metadata', () => {
    const view = fixture();
    fireEvent.click(view.getByRole('button', { name: '2', exact: true }));
    expect(view.fetch).toHaveBeenLastCalledWith('recipients', { pageNum: 2, pageSize: 20 }, { force: false });
    expect(view.getByText('Person 1')).toBeInTheDocument();
    expect(view.queryByText('Person 21')).not.toBeInTheDocument();
    view.update({ records: rows(21, 20), pageNum: 2, pageSize: 20, total: 45 });
    expect(view.queryByText('Person 1')).not.toBeInTheDocument();
    expect(view.getByText('Person 21')).toBeInTheDocument();
    fireEvent.change(view.getByRole('combobox'), { target: { value: '50' } });
    expect(view.fetch).toHaveBeenLastCalledWith('recipients', { pageNum: 1, pageSize: 50 }, { force: false });
  });

  it('disables navigation while a page request is loading', () => {
    const view = fixture();
    view.update({ records: rows(1, 20), pageNum: 1, pageSize: 20, total: 45 }, { loading: true, error: null });
    expect(view.getByRole('button', { name: '2', exact: true })).toBeDisabled();
    expect(view.getByRole('combobox')).toBeDisabled();
    expect(view.getByTestId('table-server-pagination')).toHaveAttribute('aria-busy', 'true');
  });

  it('preserves a transiently failed page and retries the exact attempted parameters', () => {
    const view = fixture();
    fireEvent.click(view.getByRole('button', { name: '3', exact: true }));
    view.update({ records: rows(1, 20), pageNum: 1, pageSize: 20, total: 45 }, { loading: false, error: { statusCode: 503, message: 'Temporarily unavailable' } });
    expect(view.getByText('Person 1')).toBeInTheDocument();
    expect(view.getByTestId('table-stale-state')).toBeInTheDocument();
    fireEvent.click(view.getByTestId('table-stale-retry'));
    expect(view.fetch).toHaveBeenLastCalledWith('recipients', { pageNum: 3, pageSize: 20 }, { force: true });
    expect(view.reload).not.toHaveBeenCalled();
  });

  it.each([401, 403])('hides preserved rows and pagination after access failure %s', statusCode => {
    const view = fixture();
    view.update({ records: rows(1, 20), pageNum: 1, pageSize: 20, total: 45 }, { loading: false, error: { statusCode, message: 'Access denied' } });
    expect(view.getByTestId(`table-error-state-${statusCode}`)).toBeInTheDocument();
    expect(view.queryByText('Person 1')).not.toBeInTheDocument();
    expect(view.queryByTestId('table-server-pagination')).not.toBeInTheDocument();
  });

  it('rejects a fabricated empty page instead of displaying a successful empty state', () => {
    const view = fixture();
    view.update({ records: [], pageNum: 1, pageSize: 20, total: 45 });
    expect(view.getByTestId('table-page-contract-error')).toBeInTheDocument();
    expect(view.queryByTestId('table-server-pagination')).not.toBeInTheDocument();
    expect(view.queryByText('Person 1')).not.toBeInTheDocument();
  });

  it('accepts an explicitly empty result and the partial last page', () => {
    expect(readServerTablePage({ records: [], pageNum: 1, pageSize: 20, total: 0 })).toEqual({ current: 1, pageSize: 20, total: 0 });
    expect(readServerTablePage({ records: rows(41, 5), pageNum: 3, pageSize: 20, total: 45 })).toEqual({ current: 3, pageSize: 20, total: 45 });
    expect(readServerTablePage({ records: rows(1, 1), current: 1, pageSize: 20, total: 1 }, 'current').total).toBe(1);
  });

  it.each([
    [], { records: [], pageNum: 0, pageSize: 20, total: 0 },
    { records: [], pageNum: 2, pageSize: 20, total: 0 },
    { records: [null], pageNum: 1, pageSize: 20, total: 1 },
    { records: [], pageNum: 1, pageSize: 20, total: '0' },
  ])('rejects malformed or out-of-range server metadata: %j', page => {
    expect(() => readServerTablePage(page)).toThrow(ServerTablePageError);
  });
});
