import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ProfileProvider } from '@auraboot/runtime-kernel';
import { adminProfile } from '~/framework/meta/profiles/admin';
import { ListPageContent, collectListMiscBlocks } from '../ListPageContent';
import { DataSourceManager } from '../../../runtime/data-pipeline/DataSourceManager';
import { ScopedStateManager } from '../../../runtime/state/scoped-state';
import { ExpressionEvaluator } from '../../../runtime/expression/evaluator';
import { createExpressionContext } from '../../../runtime/expression/context';
const owned = vi.hoisted(() => ({
  runtime: null as any,
  manager: null as any,
  transport: vi.fn(),
  pageLoader: null as null | ((params?: any) => Promise<void>),
}));
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: any[]) => owned.transport(...args),
}));
vi.mock('~/contexts/AuthContext', async () => ({
  ...(await vi.importActual<any>('~/contexts/AuthContext')),
  useAuth: () => ({
    token: 'fixture',
    user: { id: '7' },
    hasPermission: (code: string) => ['qo.srm.use', 'page.page.read'].includes(code),
  }),
  usePermission: () => true,
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
    showWarningToast: vi.fn(),
    showInfoToast: vi.fn(),
  }),
}));
vi.mock('~/contexts/TimezoneContext', () => ({
  useTimezone: () => ({ formats: {}, timezone: 'UTC' }),
}));
vi.mock('../hooks/usePageRuntime', async () => ({
  ...(await vi.importActual<any>('../hooks/usePageRuntime')),
  usePageRuntime: (_schema: any, options: any) => {
    owned.pageLoader = options?.loadData;
    return {
      runtime: owned.runtime,
      dataSourceManager: owned.manager,
      t: (key: string) => key,
      locale: 'en',
      navigate: vi.fn(),
    };
  },
}));
vi.mock('~/framework/smart/hooks/useSavedViews', () => ({
  useSavedViews: () => ({
    views: [],
    accessibleViews: [],
    currentView: null,
    loading: false,
    selectDefaultView: vi.fn(),
  }),
}));
vi.mock('~/framework/smart/hooks/useAutoSaveView', () => ({
  useAutoSaveView: () => ({ autoSave: vi.fn() }),
}));
const source = {
  type: 'api',
  method: 'post',
  endpoint: '/api/meta/commands/execute/example:list_mine',
  adaptor: 'commandTable',
  autoFetch: false,
};
const table = {
  id: 'owned-table',
  blockType: 'table',
  dataSource: 'ownedRows',
  table: { rowKey: 'pid', columns: [{ field: 'title', label: 'Title' }] },
};
const toolbar = {
  id: 'queues',
  blockType: 'toolbar',
  buttons: [
    {
      code: 'pending',
      label: 'Pending',
      permissionCode: 'qo.srm.use',
      action: { type: 'flow', steps: [{ action: 'state.set', args: { bucket: 'pending' } }] },
    },
  ],
};
const schema = {
  pageKey: 'owned-workspace',
  modelCode: 'native_protected_model',
  kind: 'list',
  title: 'Owned workspace',
  extension: {
    customOnly: true,
    skipListData: true,
    skipFieldMeta: true,
    skipDynamicFieldMeta: true,
    hideSavedViews: true,
    showCreate: false,
    showEdit: false,
    showDelete: false,
    showExport: false,
  },
  dataSources: { ownedRows: source },
  blocks: [toolbar, table],
} as any;
function prepareRuntime() {
  const state = new ScopedStateManager({ locale: 'en', theme: 'light' } as any);
  state.createScope('list', {});
  const evaluator = new ExpressionEvaluator();
  const manager = new DataSourceManager(createExpressionContext({} as any));
  manager.register('ownedRows', source as any);
  owned.transport.mockReset();
  owned.transport.mockImplementation(async () => ({
    code: '0',
    data: {
      phaseReached: 'completed',
      data: {
        records: [{ pid: 'owned-123', title: 'Bound supplier row' }],
        total: 1,
        current: 1,
        size: 20,
      },
    },
  }));
  owned.manager = manager;
  owned.runtime = {
    getContext: () => ({ ...state.getContext('list'), locale: 'en', t: (key: string) => key }),
    getEvaluator: () => evaluator,
    getDataSourceManager: () => manager,
    getStateManager: () => state,
    getScopeId: () => 'list',
    getSchema: () => schema,
  };
  return manager;
}
function mount(pageSchema: any = schema) {
  return render(
    <MemoryRouter>
      <ProfileProvider value={adminProfile}>
        <ListPageContent schema={pageSchema} tableName="owned" token="fixture" />
      </ProfileProvider>
    </MemoryRouter>,
  );
}
describe('Actual ListPageContent→BlockRenderer external custom-only table', () => {
  it('provides the latest page-owned list loader to runtime flow refresh actions', async () => {
    prepareRuntime();
    let title = 'Initial contact phone';
    owned.transport.mockImplementation(async () => ({
      code: '0',
      data: { records: [{ pid: 'owned-123', title }], total: 1, current: 1, size: 20 },
    }));
    const normal = {
      ...schema,
      dataSources: {},
      blocks: [{ ...table, dataSource: undefined }],
      extension: {
        ...schema.extension,
        customOnly: false,
        skipListData: false,
        dataSource: { type: 'api', method: 'get', endpoint: '/api/example/contacts' },
      },
    };
    mount(normal);
    expect(await screen.findByText('Initial contact phone')).toBeVisible();
    expect(owned.pageLoader).toBeTypeOf('function');
    title = 'Persisted updated contact phone';
    await act(async () => {
      await owned.pageLoader!();
    });
    expect(await screen.findByText('Persisted updated contact phone')).toBeVisible();
    expect(screen.queryByText('Initial contact phone')).not.toBeInTheDocument();
  });

  it('renders exact owned rows once, without native model/field reads or duplicate toolbar', async () => {
    const manager = prepareRuntime();
    await manager.reload('ownedRows');
    mount();
    expect(await screen.findByTestId('table-row-owned-123')).toHaveTextContent(
      'Bound supplier row',
    );
    expect(screen.getAllByRole('button', { name: 'Pending', exact: true })).toHaveLength(1);
    expect(owned.transport).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(owned.transport.mock.calls)).not.toMatch(
      /field-meta|\/api\/dynamic|native_protected_model/,
    );
  });
  it('renders loading and exact error state rather than silently losing the table', async () => {
    const manager = prepareRuntime();
    let finish!: (value: any) => void;
    owned.transport.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const loading = manager.reload('ownedRows');
    mount();
    expect(await screen.findByTestId('table-loading-state')).toBeVisible();
    await act(async () => {
      finish({ success: false, code: '403', message: 'Forbidden', httpStatus: 403 });
      await loading;
    });
    expect(await screen.findByTestId('table-error-state-403')).toBeVisible();
    expect(screen.queryByTestId('table-row-owned-123')).not.toBeInTheDocument();
  });
  it('keeps ordinary table specialization and rejects undefined, native and malformed sources', () => {
    expect(collectListMiscBlocks(schema, false)).toEqual([]);
    for (const config of [
      undefined,
      { type: 'dynamic', endpoint: '/api/dynamic/model' },
      { type: 'api' },
      { type: 'api', endpoint: '' },
    ])
      expect(
        collectListMiscBlocks({ ...schema, dataSources: { ownedRows: config } }, true),
      ).toEqual([]);
    expect(
      collectListMiscBlocks({ ...schema, blocks: [{ ...table, dataSource: undefined }] }, true),
    ).toEqual([]);
    expect(collectListMiscBlocks(schema, true)).toEqual([table]);
  });
});

function mountQueryContract(source?: Record<string, unknown>, failFirst = false) {
  prepareRuntime();
  const dynamicSchema = {
    ...schema,
    pageKey: 'owned_rows_list',
    modelCode: 'owned_rows',
    dataSource: source,
    extension: { ...schema.extension, customOnly: false, skipListData: false },
    dataSources: {},
    blocks: [{ ...table, dataSource: undefined }],
  };
  owned.runtime.getSchema = () => dynamicSchema;
  const success = {
    code: '0',
    data: { records: [{ pid: 'owned-row', title: 'Owned row' }], total: 1 },
  };
  owned.transport.mockResolvedValue(success);
  if (failFirst)
    owned.transport.mockResolvedValueOnce({ code: '35000', message: 'Read rejected', data: null });
  render(
    <MemoryRouter initialEntries={['/p/owned_rows']}>
      <ProfileProvider value={adminProfile}>
        <ListPageContent schema={dynamicSchema} tableName="owned_rows" token="fixture" />
      </ProfileProvider>
    </MemoryRouter>,
  );
}

async function submitOwnedKeyword() {
  await waitFor(() =>
    expect(screen.getByTestId('dynamic-list')).toHaveAttribute('data-ready', 'true'),
  );
  const input = screen.getByTestId('list-search-input');
  fireEvent.change(input, { target: { value: 'needle' } });
  fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
}

describe('Actual ListPageContent ordinary query and retry contract', () => {
  it('forwards the submitted keyword on an ordinary dynamic list request', async () => {
    mountQueryContract();
    await submitOwnedKeyword();
    await waitFor(() =>
      expect(owned.transport).toHaveBeenCalledWith(
        '/api/dynamic/owned_rows/list',
        expect.objectContaining({ params: expect.objectContaining({ keyword: 'needle' }) }),
      ),
    );
  });
  it('forwards the named query code and submitted keyword together', async () => {
    mountQueryContract({ type: 'namedQuery', queryCode: 'owned_query' });
    await submitOwnedKeyword();
    await waitFor(() =>
      expect(owned.transport).toHaveBeenCalledWith(
        '/api/dynamic/owned_rows/list',
        expect.objectContaining({
          params: expect.objectContaining({ queryCode: 'owned_query', keyword: 'needle' }),
        }),
      ),
    );
  });
  it('forwards submitted keyword with API page and size', async () => {
    mountQueryContract({ type: 'api', endpoint: '/api/owned-list', method: 'get' });
    await submitOwnedKeyword();
    await waitFor(() =>
      expect(owned.transport).toHaveBeenCalledWith(
        '/api/owned-list',
        expect.objectContaining({
          params: expect.objectContaining({ keyword: 'needle', page: 0, size: 20 }),
        }),
      ),
    );
  });
  it('replays the exact failed API parameters through the real error-state button', async () => {
    mountQueryContract({ type: 'api', endpoint: '/api/owned-list', method: 'get' });
    await waitFor(() =>
      expect(screen.getByTestId('dynamic-list')).toHaveAttribute('data-ready', 'true'),
    );
    owned.transport.mockResolvedValueOnce({ code: '35000', message: 'Read rejected', data: null });
    await submitOwnedKeyword();
    const retry = await screen.findByRole('button', { name: 'Retry', exact: true });
    const reads = () =>
      owned.transport.mock.calls.filter(([endpoint]) => endpoint === '/api/owned-list');
    const first = structuredClone(reads().at(-1));
    const prior = reads().length;
    fireEvent.click(retry);
    await waitFor(() => expect(reads()).toHaveLength(prior + 1));
    expect(reads().at(-1)).toEqual(first);
  });
});
