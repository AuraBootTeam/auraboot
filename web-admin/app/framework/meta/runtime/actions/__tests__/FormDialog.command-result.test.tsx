import { registerFormDialogScope } from '../formDialogLifecycle';
import { useMemo, useState } from 'react';
import { MemoryRouter } from 'react-router';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import FormDialog, { FormDialogProvider } from '../FormDialog';
import { actionRegistry } from '../ActionRegistry';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { executeSimpleWorkbenchAction } from '~/framework/meta/rendering/blocks/workbenchBlockUtils';
import { useSubTableRowActions } from '~/framework/meta/rendering/blocks/subtable/useSubTableRowActions';
import { useListBulkActions } from '~/framework/meta/rendering/pages/list/useListBulkActions';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

const io = vi.hoisted(() => ({ fetch: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => io.fetch(...args),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'en',
    t: (key: string, _args?: unknown, fallback?: string) => fallback ?? key,
  }),
}));
vi.mock('~/contexts/TimezoneContext', () => ({ useTimezone: () => ({ timezone: 'UTC' }) }));
const t = (key: string, _args?: unknown, fallback?: string) => fallback ?? key;
type RecordRow = { pid: string; title: string; status: string; row_version: number };
const row = (pid = 'A'): RecordRow => ({
  pid,
  title: `Case ${pid}`,
  status: 'investigating',
  row_version: 7,
});
const inputFields = [
  {
    field: 'conclusion',
    label: 'Conclusion',
    type: 'select',
    required: true,
    dataSource: {
      type: 'static',
      data: [
        { value: 'confirmed', label: 'Confirmed' },
        { value: 'insufficient', label: 'Insufficient' },
      ],
    },
  },
  { field: 'note', label: 'Note', type: 'textarea', required: true },
];
const presentation = {
  waitForResult: true,
  width: 600,
  showClose: true,
  summary: {
    columns: 2 as const,
    fields: [
      { field: 'title', label: 'Event' },
      { field: 'status', label: 'Status' },
    ],
  },
};
function button(wait = true): ButtonConfig {
  return {
    code: 'close',
    label: 'Close',
    action: {
      type: 'command',
      command: 'case:close',
      operationType: 'UPDATE',
      includeRecordPayload: false,
      inputFields,
      inputFieldsTitle: 'Close · ${record.title}',
      inputFieldsSubmitLabel: 'Confirm close',
      inputFieldsPresentation: { ...presentation, waitForResult: wait },
    },
  };
}
function runtime(record: RecordRow, refresh: () => void | Promise<void> = vi.fn()): SchemaRuntime {
  const context = {
    record,
    form: record,
    row: record,
    locale: 'en',
    t,
    state: {},
    global: {},
    $page: { recordPid: record.pid },
  };
  return {
    getContext: () => context,
    getSchema: () => ({
      modelCode: 'case',
      extension: {
        commandRequestByCommand: {
          'case:close': {
            idempotent: true,
            versionField: 'row_version',
            inputFieldsOnly: true,
            payloadFields: ['conclusion', 'note'],
          },
        },
      },
    }),
    getShowToast: () => undefined,
    getDataSourceManager: () => ({ reload: refresh }),
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (value: string) => value,
    }),
  } as unknown as SchemaRuntime;
}
function Native({
  record,
  refresh,
  wait = true,
  transition = false,
  toast,
}: {
  record: RecordRow;
  refresh: () => Promise<void>;
  wait?: boolean;
  transition?: boolean;
  toast: (message: string, type: 'success' | 'error' | 'info' | 'warning') => void;
}) {
  const rt = useMemo(() => runtime(record), [record]);
  const handler = useActionHandler({
    runtime: rt,
    navigate: vi.fn() as never,
    tableName: 'case',
    context: { record, loadData: refresh, loadDataForReview: refresh },
    locale: 'en',
    t,
    showToast: toast,
  });
  const config = button(wait);
  if (transition && typeof config.action === 'object' && config.action.type === 'command')
    config.action = { ...config.action, type: 'state_transition' };
  return (
    <>
      <button
        onClick={() => {
          void handler.handleAction(config, record);
        }}
        disabled={handler.loading}
      >
        Open action
      </button>
      {handler.error && (
        <p role="alert" data-testid="native-error">
          {handler.error}
        </p>
      )}
    </>
  );
}
function Root({ children }: { children: React.ReactNode }) {
  return (
    <MemoryRouter>
      <FormDialogProvider>
        <FormDialog />
        {children}
      </FormDialogProvider>
    </MemoryRouter>
  );
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
const conflict = {
  code: '41001',
  success: false,
  httpStatus: 409,
  context: {
    errorCode: 'CAS_VERSION_CONFLICT',
    detail: 'Version conflict',
    modelCode: 'case',
    recordPid: 'A',
  },
};
beforeEach(() => {
  io.fetch.mockReset();
});
async function fill(note = 'Retain this exact note') {
  await screen.findByTestId('form-dialog');
  fireEvent.change(screen.getByTestId('form-dialog-field-conclusion'), {
    target: { value: 'confirmed' },
  });
  fireEvent.change(screen.getByTestId('form-dialog-field-note'), { target: { value: note } });
}
function nativeFixture(
  wait = true,
  refresh = vi.fn().mockResolvedValue(undefined),
  transition = false,
) {
  const record = row(),
    pending = deferred<unknown>(),
    requests: Record<string, any>[] = [],
    toast = vi.fn();
  io.fetch.mockImplementation(
    async (url: string, options: { method: string; params?: Record<string, any> }) => {
      if (url === '/api/dynamic/case/A') return { code: '0', data: { ...record } };
      if (url === '/api/meta/commands/execute/case:close') {
        requests.push(structuredClone(options.params!));
        return pending.promise;
      }
      throw new Error(`Unexpected transport: ${url}`);
    },
  );
  const view = render(
    <Root>
      <Native record={record} refresh={refresh} wait={wait} transition={transition} toast={toast} />
    </Root>,
  );
  return { record, pending, requests, refresh, toast, view };
}
describe('actual FormDialog and native executor result handshake', () => {
  it('keeps the exact draft and frozen summary after a real-shaped 409; no retry or metadata replacement', async () => {
    const fixture = nativeFixture();
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await fill();
    expect(screen.getByLabelText('Conclusion')).toBe(
      screen.getByRole('combobox', { name: 'Conclusion' }),
    );
    expect(screen.getByRole('combobox', { name: 'Conclusion' })).toHaveAttribute(
      'aria-required',
      'true',
    );
    expect(screen.getByRole('textbox', { name: 'Note' })).toHaveAttribute('aria-required', 'true');
    fixture.record.row_version = 9;
    fixture.record.title = 'Unrelated live title';
    const submit = screen.getByTestId('form-dialog-submit');
    fireEvent.click(submit);
    fireEvent.click(submit);
    await waitFor(() => expect(fixture.requests).toHaveLength(1));
    expect(submit).toBeDisabled();
    expect(screen.getByTestId('form-dialog')).toHaveAttribute('aria-busy', 'true');
    const request = structuredClone(fixture.requests[0]);
    expect(request).toMatchObject({
      targetRecordPid: 'A',
      expectedVersion: 7,
      operationType: 'UPDATE',
      payload: { conclusion: 'confirmed', note: 'Retain this exact note' },
    });
    expect(request.clientRequestId).toEqual(expect.any(String));
    expect(Object.keys(request.payload).sort()).toEqual(['conclusion', 'note']);
    const feedback = screen.getByTestId('form-dialog-submit-feedback');
    const scrollFeedback = vi.fn();
    feedback.scrollIntoView = scrollFeedback;
    await act(async () => {
      fixture.pending.resolve(conflict);
    });
    await waitFor(() =>
      expect(screen.getByTestId('form-dialog-submit-feedback')).toHaveAttribute(
        'data-http-status',
        '409',
      ),
    );
    expect(screen.getByRole('dialog', { name: 'Close · Case A' })).toBeInTheDocument();
    expect(feedback).toHaveFocus();
    expect(scrollFeedback).toHaveBeenCalledExactlyOnceWith({ block: 'start', inline: 'nearest' });
    expect(screen.getByTestId('form-dialog-field-note')).toHaveValue('Retain this exact note');
    expect(screen.getByTestId('form-dialog-field-conclusion')).toHaveValue('confirmed');
    expect(screen.getByTestId('form-dialog-summary')).toHaveTextContent('Case A');
    expect(screen.getByTestId('form-dialog-submit')).toBeDisabled();
    expect(fixture.refresh).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(fixture.requests).toEqual([request]);
    fireEvent.click(screen.getByTestId('form-dialog-close-refresh'));
    await waitFor(() => expect(screen.queryByTestId('form-dialog')).not.toBeInTheDocument());
    expect(fixture.refresh).toHaveBeenCalledOnce();
    expect(fixture.requests).toEqual([request]);
  });
  it.each([
    { success: false, applied: false, reason: 'Not applied' },
    {
      commandCode: 'case:close',
      phaseReached: 'completed',
      data: { success: false, applied: false, reason: 'Not applied' },
    },
  ])('keeps a code-zero native business rejection draft: %j', async (data) => {
    const fixture = nativeFixture();
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await fill();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await waitFor(() => expect(fixture.requests).toHaveLength(1));
    await act(async () => {
      fixture.pending.resolve({ code: '0', data });
    });
    await waitFor(() =>
      expect(screen.getByTestId('form-dialog-submit-feedback')).toHaveAttribute(
        'data-submit-state',
        'failed',
      ),
    );
    expect(screen.getByTestId('form-dialog-field-note')).toHaveValue('Retain this exact note');
    expect(screen.getByTestId('form-dialog-submit')).toBeDisabled();
    expect(fixture.refresh).not.toHaveBeenCalled();
    expect(fixture.requests).toHaveLength(1);
  });
  it('also acknowledges the native state_transition prompt without changing its dispatch parameters', async () => {
    const fixture = nativeFixture(true, vi.fn().mockResolvedValue(undefined), true);
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await fill();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await waitFor(() => expect(fixture.requests).toHaveLength(1));
    const original = structuredClone(fixture.requests[0]);
    await act(async () => {
      fixture.pending.resolve({
        code: '403',
        httpStatus: 403,
        success: false,
        context: { detail: 'Access denied' },
      });
    });
    await waitFor(() =>
      expect(screen.getByTestId('form-dialog-submit-feedback')).toHaveAttribute(
        'data-http-status',
        '403',
      ),
    );
    expect(screen.getByTestId('form-dialog-field-note')).toHaveValue('Retain this exact note');
    expect(screen.getByTestId('form-dialog-submit')).toBeDisabled();
    expect(fixture.requests).toEqual([original]);
  });
  it('closes on authoritative success before a later refresh fails', async () => {
    let closedBeforeRead = false;
    const refresh = vi.fn(async () => {
      closedBeforeRead = screen.queryByTestId('form-dialog') === null;
      throw new Error('Read failed after saved command');
    });
    const fixture = nativeFixture(true, refresh);
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await fill();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await waitFor(() => expect(fixture.requests).toHaveLength(1));
    await act(async () => {
      fixture.pending.resolve({ code: '0', data: {} });
    });
    await screen.findByTestId('native-error');
    expect(screen.queryByTestId('form-dialog')).not.toBeInTheDocument();
    expect(refresh).toHaveBeenCalledOnce();
    expect(closedBeforeRead).toBe(true);
    expect(fixture.requests).toHaveLength(1);
  });
  it('cancels before submit without sending a command', async () => {
    const fixture = nativeFixture();
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await fill();
    fireEvent.click(screen.getByTestId('form-dialog-cancel'));
    await waitFor(() => expect(screen.queryByTestId('form-dialog')).not.toBeInTheDocument());
    expect(fixture.requests).toEqual([]);
    expect(fixture.refresh).not.toHaveBeenCalled();
  });
  it('keeps the draft if the explicit close-and-refresh read fails without retrying the write', async () => {
    const refresh = vi.fn().mockRejectedValue(new Error('Read unavailable')),
      fixture = nativeFixture(true, refresh);
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await fill();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await waitFor(() => expect(fixture.requests).toHaveLength(1));
    await act(async () => {
      fixture.pending.resolve(conflict);
    });
    await screen.findByTestId('form-dialog-close-refresh');
    fireEvent.click(screen.getByTestId('form-dialog-close-refresh'));
    await screen.findByText(
      'Refresh failed. Your inputs are retained. Try Close and refresh again.',
    );
    expect(screen.getByTestId('form-dialog-field-note')).toHaveValue('Retain this exact note');
    expect(screen.getByTestId('form-dialog-submit')).toBeDisabled();
    expect(refresh).toHaveBeenCalledOnce();
    expect(fixture.requests).toHaveLength(1);
  });
  it('preserves collect-close when waitForResult is omitted/false', async () => {
    const fixture = nativeFixture(false);
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await fill();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await waitFor(() => expect(fixture.requests).toHaveLength(1));
    expect(screen.queryByTestId('form-dialog')).not.toBeInTheDocument();
    await act(async () => {
      fixture.pending.resolve({ code: '0', data: {} });
    });
  });
  it('does not let an old A response alter B or show an old action toast', async () => {
    const pendingA = deferred<unknown>(),
      refresh = vi.fn().mockResolvedValue(undefined),
      toast = vi.fn();
    io.fetch.mockImplementation(
      async (url: string, options: { params?: { targetRecordPid?: string } }) => {
        if (url.startsWith('/api/dynamic/case/'))
          return { code: '0', data: row(url.split('/').at(-1)) };
        if (options.params?.targetRecordPid === 'A') return pendingA.promise;
        throw new Error('Unexpected B write');
      },
    );
    const view = render(
      <Root>
        <Native key="A" record={row('A')} refresh={refresh} toast={toast} />
      </Root>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await fill('A note');
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await waitFor(() =>
      expect(io.fetch.mock.calls.some((call) => String(call[0]).includes('/execute/'))).toBe(true),
    );
    view.rerender(
      <Root>
        <Native key="B" record={row('B')} refresh={refresh} toast={toast} />
      </Root>,
    );
    await waitFor(() => expect(screen.queryByTestId('form-dialog')).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await screen.findByRole('dialog', { name: 'Close · Case B' });
    await act(async () => {
      pendingA.resolve(conflict);
    });
    expect(screen.getByRole('dialog', { name: 'Close · Case B' })).toBeInTheDocument();
    expect(screen.getByTestId('form-dialog-field-note')).toHaveValue('');
    expect(screen.getByTestId('form-dialog-submit')).toBeEnabled();
    expect(toast).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
  });
  it('ignores a late result when the ambient owner scope expires without unmounting the action hook', async () => {
    const fixture = nativeFixture(),
      owner = new AbortController();
    const unregister = registerFormDialogScope(owner.signal, 3);
    try {
      fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
      await fill();
      fireEvent.click(screen.getByTestId('form-dialog-submit'));
      await waitFor(() => expect(fixture.requests).toHaveLength(1));
      act(() => {
        owner.abort();
        unregister();
      });
      await waitFor(() => expect(screen.queryByTestId('form-dialog')).not.toBeInTheDocument());
      await act(async () => {
        fixture.pending.resolve(conflict);
      });
      expect(fixture.toast).not.toHaveBeenCalled();
      expect(fixture.refresh).not.toHaveBeenCalled();
      expect(screen.queryByTestId('native-error')).not.toBeInTheDocument();
    } finally {
      unregister();
    }
  });
  it('keeps dialog.form as a collector even if its presentation contains waitForResult', async () => {
    render(
      <Root>
        <span>Source</span>
      </Root>,
    );
    let pending!: Promise<unknown>;
    act(() => {
      pending = actionRegistry.execute('dialog.form', {
        args: { title: 'Collect', fields: inputFields, presentation },
        t,
        locale: 'en',
      });
    });
    await fill();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await pending;
    expect(screen.queryByTestId('form-dialog')).not.toBeInTheDocument();
    expect(io.fetch).not.toHaveBeenCalled();
  });
});

function Registry({ refresh }: { refresh: () => Promise<void> }) {
  const [error, setError] = useState('');
  return (
    <>
      <button
        onClick={() => {
          void actionRegistry
            .execute('command.execute', {
              args: {
                command: 'case:close',
                targetRecordPid: 'A',
                expectedVersion: 7,
                operationType: 'UPDATE',
                idempotent: true,
                inputFields,
                inputFieldsTitle: 'Registry',
                inputFieldsPresentation: presentation,
              },
              fetchResult: io.fetch,
              expressionContext: { record: row() },
              loadData: refresh,
              t,
              locale: 'en',
            })
            .catch((failure) => setError(String(failure)));
        }}
      >
        Open action
      </button>
      <p>{error}</p>
    </>
  );
}
function Workbench({ refresh }: { refresh: () => Promise<void> }) {
  const rt = useMemo(() => runtime(row(), refresh), [refresh]),
    [error, setError] = useState('');
  return (
    <>
      <button
        onClick={() => {
          void executeSimpleWorkbenchAction(rt, {
            action: 'command.execute',
            args: {
              command: 'case:close',
              targetRecordPid: 'A',
              expectedVersion: 7,
              operationType: 'UPDATE',
              inputFields,
              inputFieldsTitle: 'Workbench',
              inputFieldsPresentation: presentation,
              reload: ['list'],
            },
          }).catch((failure) => setError(String(failure)));
        }}
      >
        Open action
      </button>
      <p>{error}</p>
    </>
  );
}
function Subtable({ refresh }: { refresh: () => Promise<void> }) {
  const action = useSubTableRowActions({
    locale: 'en',
    t,
    parentRecordData: {},
    token: undefined,
    onDataChange: vi.fn(),
    hasPermission: () => true,
    navigate: vi.fn() as never,
    setEditErrors: vi.fn(),
    setRowActionLoadingKey: vi.fn(),
    reloadRows: refresh,
  });
  const config = button();
  if (typeof config.action === 'object' && config.action.type === 'command')
    config.action.expectedVersion = 7;
  return (
    <button
      onClick={() => {
        void action.handleRowAction(config, row(), 0);
      }}
    >
      Open action
    </button>
  );
}
function Bulk({ refresh, ids = ['A'] }: { refresh: () => Promise<void>; ids?: string[] }) {
  const actions = useListBulkActions({
    setImportOpen: vi.fn(),
    loadData: refresh,
    pagination: { current: 1, pageSize: 20, total: ids.length },
    filters: {},
    setSelectionState: vi.fn(),
    pageRowIds: ids,
    schema: { modelCode: 'case' },
    tableName: 'case',
    resolveButtonLabel: () => 'Bulk close',
    locale: 'en',
    data: ids.map(row),
    token: undefined,
    t,
    showToast: vi.fn(),
    setBulkActionResult: vi.fn(),
    canUseButton: () => true,
    setBulkFieldCommand: vi.fn(),
    modelCode: 'case',
    bulkFieldCommand: null,
    setBulkEditOpen: vi.fn(),
  });
  const [error, setError] = useState('');
  const config = {
    code: 'bulk-close',
    action: {
      type: 'bulk_state_transition',
      command: 'case:close',
      inputFields,
      inputFieldsTitle: 'Bulk',
      inputFieldsPresentation: presentation,
    },
  } as ButtonConfig;
  return (
    <>
      <button
        onClick={() => {
          void actions.handleBulkAction(config, ids).catch((failure) => setError(String(failure)));
        }}
      >
        Open action
      </button>
      <p>{error}</p>
    </>
  );
}
describe('all native command prompt consumers acknowledge the same UI protocol', () => {
  it.each(
    [Registry, Workbench, Subtable, Bulk].map((Component) => ({ name: Component.name, Component })),
  )(
    '$name retains inputs and reports structured failure rather than leaving a spinner',
    async ({ Component }) => {
      const refresh = vi.fn().mockResolvedValue(undefined);
      io.fetch.mockResolvedValue(conflict);
      render(
        <Root>
          <Component refresh={refresh} />
        </Root>,
      );
      fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
      await fill();
      fireEvent.click(screen.getByTestId('form-dialog-submit'));
      await waitFor(() =>
        expect(screen.getByTestId('form-dialog-submit-feedback')).toHaveAttribute(
          'data-http-status',
          '409',
        ),
      );
      expect(screen.getByTestId('form-dialog-field-note')).toHaveValue('Retain this exact note');
      expect(screen.getByTestId('form-dialog-submit')).toBeDisabled();
      expect(io.fetch).toHaveBeenCalledTimes(1);
      expect(refresh).not.toHaveBeenCalled();
      // These generic loaders have no verified read-result contract. Do not promise a refresh.
      expect(screen.queryByTestId('form-dialog-close-refresh')).not.toBeInTheDocument();
      fireEvent.click(screen.getByTestId('form-dialog-cancel'));
      await waitFor(() => expect(screen.queryByTestId('form-dialog')).not.toBeInTheDocument());
      expect(refresh).not.toHaveBeenCalled();
      expect(io.fetch).toHaveBeenCalledTimes(1);
    },
  );
  it.each(
    [Registry, Workbench, Subtable, Bulk].map((Component) => ({ name: Component.name, Component })),
  )('$name retains a wrapped code-zero business rejection', async ({ Component }) => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    io.fetch.mockResolvedValue({
      code: '0',
      httpStatus: 200,
      data: {
        commandCode: 'case:close',
        phaseReached: 'completed',
        data: { success: false, applied: false, reason: 'Not applied' },
      },
    });
    render(
      <Root>
        <Component refresh={refresh} />
      </Root>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await fill();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await waitFor(() =>
      expect(screen.getByTestId('form-dialog-submit-feedback')).toHaveAttribute(
        'data-submit-state',
        'failed',
      ),
    );
    expect(screen.getByTestId('form-dialog-field-note')).toHaveValue('Retain this exact note');
    expect(screen.getByTestId('form-dialog-submit')).toBeDisabled();
    expect(io.fetch).toHaveBeenCalledOnce();
  });
  it.each([Registry, Subtable, Bulk].map((Component) => ({ name: Component.name, Component })))(
    '$name distinguishes an async receipt from terminal completion without adding a poll',
    async ({ Component }) => {
      const refresh = vi.fn().mockResolvedValue(undefined);
      io.fetch.mockResolvedValue({ code: '0', data: { async: true, taskCode: 'accepted-task' } });
      render(
        <Root>
          <Component refresh={refresh} />
        </Root>,
      );
      fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
      await fill();
      fireEvent.click(screen.getByTestId('form-dialog-submit'));
      await waitFor(() =>
        expect(screen.getByTestId('form-dialog-submit-feedback')).toHaveAttribute(
          'data-submit-state',
          'accepted',
        ),
      );
      expect(screen.getByTestId('form-dialog')).not.toHaveAttribute('aria-busy', 'true');
      expect(screen.getByTestId('form-dialog-submit')).toBeDisabled();
      expect(screen.getByTestId('form-dialog-field-note')).toHaveValue('Retain this exact note');
      expect(io.fetch).toHaveBeenCalledOnce();
    },
  );
  it('reports a targeted bulk partial result before its existing list read', async () => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    io.fetch.mockImplementation(
      async (_url: string, options: { params: { targetRecordPid: string } }) =>
        options.params.targetRecordPid === 'A'
          ? { code: '0', data: {} }
          : { ...conflict, context: { ...conflict.context, recordPid: 'B' } },
    );
    render(
      <Root>
        <Bulk refresh={refresh} ids={['A', 'B']} />
      </Root>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await fill();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await waitFor(() =>
      expect(screen.getByTestId('form-dialog-submit-feedback')).toHaveAttribute(
        'data-http-status',
        '409',
      ),
    );
    expect(screen.getByTestId('form-dialog-submit-feedback')).toHaveTextContent(
      '1 requests accepted',
    );
    expect(screen.getByTestId('form-dialog-field-note')).toHaveValue('Retain this exact note');
    expect(io.fetch).toHaveBeenCalledTimes(2);
    expect(refresh).toHaveBeenCalledOnce();
    expect(screen.getByTestId('form-dialog-submit')).toBeDisabled();
  });
  it('acknowledges an actual workbench poll terminal before the terminal read can fail', async () => {
    const closedBeforeRead: boolean[] = [];
    const refresh = vi.fn(async () => {
      closedBeforeRead.push(screen.queryByTestId('form-dialog') === null);
      throw new Error('Terminal read failed');
    });
    io.fetch.mockImplementation(async (url: string) =>
      url.includes('/api/async-tasks/')
        ? { code: '0', data: { status: 'completed', resultData: {} } }
        : { code: '0', data: { async: true, taskCode: 'accepted-task' } },
    );
    render(
      <Root>
        <Workbench refresh={refresh} />
      </Root>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
    await fill();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
    expect(screen.queryByTestId('form-dialog')).not.toBeInTheDocument();
    expect(closedBeforeRead).toEqual([true]);
    expect(io.fetch).toHaveBeenCalledTimes(2);
  });
  it.each([Workbench, Bulk].map((Component) => ({ name: Component.name, Component })))(
    '$name closes after success even if its existing read loader fails',
    async ({ Component }) => {
      const closedBeforeRead: boolean[] = [];
      const refresh = vi.fn(async () => {
        closedBeforeRead.push(screen.queryByTestId('form-dialog') === null);
        throw new Error('Read failed after command success');
      });
      io.fetch.mockResolvedValue({ code: '0', data: {} });
      render(
        <Root>
          <Component refresh={refresh} />
        </Root>,
      );
      fireEvent.click(screen.getByRole('button', { name: 'Open action' }));
      await fill();
      fireEvent.click(screen.getByTestId('form-dialog-submit'));
      await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
      expect(screen.queryByTestId('form-dialog')).not.toBeInTheDocument();
      expect(closedBeforeRead).toEqual([true]);
      expect(io.fetch).toHaveBeenCalledOnce();
    },
  );
});
