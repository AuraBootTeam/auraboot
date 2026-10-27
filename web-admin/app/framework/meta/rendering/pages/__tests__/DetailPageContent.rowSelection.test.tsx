import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ProfileProvider } from '@auraboot/runtime-kernel';
import { adminProfile } from '~/framework/meta/profiles/admin';
import { DetailPageContent } from '../DetailPageContent';
import { DataSourceManager } from '../../../runtime/data-pipeline/DataSourceManager';
import { ScopedStateManager } from '../../../runtime/state/scoped-state';
import { ExpressionEvaluator } from '../../../runtime/expression/evaluator';
import { businessFunctions } from '../../../runtime/expression/business-functions';
import { createExpressionContext } from '../../../runtime/expression/context';
import { FlowRunner } from '../../../runtime/schema-runtime/FlowRunner';
import { formButtonSourceDependencies } from '../../blocks/FormButtonsBlockRenderer';
import { actionRegistry } from '../../../runtime/actions/ActionRegistry';
const owned = vi.hoisted(() => ({
  runtime: null as any,
  manager: null as any,
  transport: vi.fn(),
}));
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: any[]) => owned.transport(...args),
}));
vi.mock('~/contexts/AuthContext', async () => ({
  ...(await vi.importActual<any>('~/contexts/AuthContext')),
  useAuth: () => ({ token: 'fixture', hasPermission: () => true }),
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
vi.mock('../hooks/useDictCache', () => ({
  useDictCache: () => ({ loaded: true, getDictLabel: () => undefined, getDictItems: () => [] }),
}));
vi.mock('../hooks/usePageRuntime', async () => ({
  ...(await vi.importActual<any>('../hooks/usePageRuntime')),
  usePageRuntime: () => ({
    runtime: owned.runtime,
    dataSourceManager: owned.manager,
    t: (key: string) => key,
    locale: 'en',
    navigate: vi.fn(),
  }),
}));
vi.mock('../../components/ComponentLoader', () => ({
  ComponentLoader: ({ props }: any) => (
    <label>
      {props.label}
      <input
        aria-label={props.label}
        value={props.value ?? ''}
        readOnly={props.readOnly}
        onChange={(e) => props.onChange(e.target.value)}
      />
    </label>
  ),
}));

describe('Actual Detail→canonical BlockRenderer→FormButtons selected-row transport', () => {
  it('subscribes only literal named gate dependencies, not string text or unrelated sources', () => {
    expect(
      formButtonSourceDependencies([
        {
          enableWhen: '${dataSources.lines.length>0}',
          visibleWhen: "${dataSources['visible'].length>0}",
        },
        { disableWhen: '${dataSources.lines.length===0}', content: 'dataSources.unrelated' },
        { enableWhen: "${'dataSources.notAReference' === form.label}" },
        { enableWhen: '${dataSources[form.name].length>0}' },
      ] as any),
    ).toEqual(['lines', 'visible']);
    expect(formButtonSourceDependencies([{ code: 'plain' }] as any)).toEqual([]);
  });
  it('retains actual parent PID and edited form, gates zero/stale rows, and POSTs only selected projection', async () => {
    const state = new ScopedStateManager({ locale: 'en', theme: 'light' } as any);
    state.createScope('detail', {
      record: { pid: 'order', state: 'open' },
      form: { pid: 'order', state: 'open' },
    });
    const evaluator = new ExpressionEvaluator();
    const manager = new DataSourceManager(createExpressionContext({} as any));
    manager.bindStateManager(state, 'detail');
    owned.manager = manager;
    manager.register('shipmentDraftRows', {
      type: 'api',
      endpoint: '/api/example/read-lines',
      method: 'post',
      autoFetch: false,
      adaptor: 'commandArray',
      dataPath: 'lines',
      rowIdentityField: 'orderLinePid',
      rowProjection: {
        orderLinePid: 'pid',
        materialName: 'name',
        remainingQty: 'remainingQty',
        quantity: 'remainingQty',
      },
    });
    const subscriptions: { id: string; stop: ReturnType<typeof vi.fn> }[] = [];
    const actualSubscribe = manager.subscribe.bind(manager);
    vi.spyOn(manager, 'subscribe').mockImplementation((id, callback) => {
      const stop = vi.fn(actualSubscribe(id, callback));
      subscriptions.push({ id, stop });
      return stop;
    });
    let positiveRemaining = '10.000000000000001';
    owned.transport.mockReset().mockImplementation(async (url: string) =>
      url === '/api/example/read-lines'
        ? {
            code: '0',
            data: {
              phaseReached: 'completed',
              data: {
                lines: [
                  { pid: 'p', name: 'Positive', remainingQty: positiveRemaining },
                  { pid: 'z', name: 'Zero', remainingQty: '0' },
                ],
              },
            },
          }
        : url === '/api/example/detail'
          ? {
              code: '0',
              data: { phaseReached: 'completed', data: { order: { pid: 'order', state: 'open' } } },
            }
          : { code: '0', data: { ok: true } },
    );
    await manager.fetch('shipmentDraftRows');
    const projection =
      "${fn.PROJECT_ROWS(fn.SELECT_ROWS(form.shipmentDraftLines,{identityField:'orderLinePid',selectedField:'selected',positiveField:'remainingQty'},dataSources.shipmentDraftRows),{orderLinePid:'orderLinePid',quantity:'quantity'},'orderLinePid')}";
    const enable =
      "${fn.COUNT(fn.SELECT_ROWS(form.shipmentDraftLines,{identityField:'orderLinePid',selectedField:'selected',positiveField:'remainingQty'},dataSources.shipmentDraftRows))>0}";
    const schema = {
      id: 'detail',
      pageKey: 'detail',
      kind: 'detail',
      modelCode: 'example',
      title: 'Owned order',
      extension: {
        skipFieldMeta: true,
        skipDynamicFieldMeta: true,
        dataSource: {
          type: 'api',
          method: 'post',
          endpoint: '/api/example/detail',
          adaptor: 'commandRecord',
          recordPath: 'order',
          params: { targetRecordPid: '${$page.recordPid}', payload: {} },
        },
      },
      blocks: [
        {
          id: 'draft',
          blockType: 'card-grid',
          field: 'shipmentDraftLines',
          dataSource: 'shipmentDraftRows',
          titleField: 'materialName',
          rowSelection: {
            identityField: 'orderLinePid',
            selectedField: 'selected',
            positiveField: 'remainingQty',
            defaultSelected: true,
            label: 'Select',
            emptyText: 'Select a line',
          },
          fields: [{ field: 'quantity', label: 'Quantity', component: 'SmartInput' }],
        },
        {
          id: 'actions',
          blockType: 'form-buttons',
          buttons: [
            {
              code: 'ship',
              content: 'Send',
              enableWhen: enable,
              action: {
                type: 'flow',
                steps: [
                  {
                    action: 'api.request',
                    endpoint: '/api/example/ship',
                    method: 'post',
                    body: { targetRecordPid: '${record.pid}', lines: projection },
                  },
                ],
              },
            },
          ],
        },
      ],
    } as any;
    const flow = new FlowRunner({
      evaluator,
      actionRegistry,
      stateManager: state,
      scopeId: 'detail',
      dataSourceManager: manager,
      schema,
      getAllFormFields: () => [],
    });
    owned.runtime = {
      getContext: () => ({
        ...state.getContext('detail'),
        fn: businessFunctions,
        t: (key: string) => key,
        dataSources: { shipmentDraftRows: manager.getData('shipmentDraftRows') },
      }),
      getEvaluator: () => evaluator,
      getSchema: () => schema,
      getStateManager: () => state,
      getScopeId: () => 'detail',
      getDataSourceManager: () => manager,
      getFlowRunner: () => flow,
      getShowToast: () => vi.fn(),
      triggerFieldLinkage: vi.fn(),
    };
    const view = render(
      <MemoryRouter>
        <ProfileProvider value={adminProfile}>
          <DetailPageContent
            schema={schema}
            tableName="example"
            recordPid="order"
            token="fixture"
          />
        </ProfileProvider>
      </MemoryRouter>,
    );
    await waitFor(() =>
      expect(screen.getByRole('checkbox', { name: 'Select Positive' })).toBeChecked(),
    );
    expect(screen.getByRole('checkbox', { name: 'Select Zero' })).toBeDisabled();
    await waitFor(() => expect(screen.getByTestId('form-btn-ship')).not.toBeDisabled());
    const send = screen.getByTestId('form-btn-ship');
    fireEvent.change(screen.getAllByLabelText('Quantity')[0], {
      target: { value: '5.000000000000001' },
    });
    fireEvent.click(send);
    await waitFor(() =>
      expect(owned.transport.mock.calls.some((call) => call[0] === '/api/example/ship')).toBe(true),
    );
    const sent = owned.transport.mock.calls.filter((call) => call[0] === '/api/example/ship');
    expect(sent).toHaveLength(1);
    expect(sent[0][1]).toMatchObject({
      method: 'post',
      params: {
        targetRecordPid: 'order',
        lines: [{ orderLinePid: 'p', quantity: '5.000000000000001' }],
      },
    });
    expect(subscriptions.length).toBeGreaterThan(0);
    expect(subscriptions.every((item) => item.id === 'shipmentDraftRows')).toBe(true);
    // Fresh gates must also protect the dispatch window before a subscribed render.
    const originalContext = owned.runtime.getContext;
    owned.runtime.getContext = () => ({
      ...originalContext(),
      dataSources: {
        shipmentDraftRows: [
          { orderLinePid: 'p', remainingQty: '0' },
          { orderLinePid: 'z', remainingQty: '0' },
        ],
      },
    });
    fireEvent.click(send);
    expect(
      owned.transport.mock.calls.filter((call) => call[0] === '/api/example/ship'),
    ).toHaveLength(1);
    owned.runtime.getContext = originalContext;
    positiveRemaining = '0';
    await act(async () => {
      await manager.reload('shipmentDraftRows');
    });
    await waitFor(() => expect(screen.getByTestId('form-btn-ship')).toBeDisabled());
    fireEvent.click(screen.getByTestId('form-btn-ship'));
    expect(
      owned.transport.mock.calls.filter((call) => call[0] === '/api/example/ship'),
    ).toHaveLength(1);
    expect(state.getFieldValue('detail', 'shipmentDraftLines')[0].quantity).toBe(
      '5.000000000000001',
    );
    view.unmount();
    expect(subscriptions.every((item) => item.stop.mock.calls.length === 1)).toBe(true);
  });
});
