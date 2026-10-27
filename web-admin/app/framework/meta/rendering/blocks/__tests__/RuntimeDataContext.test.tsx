import React from 'react';
import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { actionRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';
import { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { BlockRenderer as KernelBlockRenderer } from '../../../../../../packages/runtime-kernel/rendering/BlockRenderer';
import { ProfileProvider } from '../../../../../../packages/runtime-kernel/profiles/ProfileContext';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { RecordInspectorBlockRenderer } from '../RecordInspectorBlockRenderer';
import { StageRailBlockRenderer } from '../StageRailBlockRenderer';
import { WorkbenchActionBarBlockRenderer } from '../WorkbenchActionBarBlockRenderer';
const transport = vi.hoisted(() => vi.fn());
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: any[]) => transport(...args),
}));
vi.mock('@auraboot/runtime-kernel', () => ({ BlockRenderer: () => null }));
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ hasPermission: () => true, token: 'fixture' }),
}));
vi.mock('~/contexts/TimezoneContext', () => ({
  useTimezone: () => ({ timezone: 'UTC', formats: {} }),
}));

function fixture(pid = 'A') {
  const manager = new DataSourceManager(createExpressionContext({} as any));
  manager.register('orderDetail', {
    type: 'api',
    autoFetch: false,
    adaptor: 'raw',
    method: 'post',
    endpoint: '/api/meta/commands/execute/example:detail',
    params: { targetRecordPid: '${record.pid}', payload: {} },
  });
  const runtime = new SchemaRuntime({
    dataSourceManager: manager,
    scopeId: 'scope-' + pid,
    schema: {
      id: 'fixture',
      kind: 'detail',
      title: 'Fixture',
      blocks: [],
      layout: { type: 'grid', cols: 1 },
    } as any,
    globalState: { locale: 'en', theme: 'light', t: (key: string) => key },
    initialContext: { record: { pid, state: 'assigned' } } as any,
    skipDataSourceRegistration: true,
  });
  return { manager, runtime };
}
const wire = (name: string) => ({
  code: '0',
  data: { phaseReached: 'completed', data: { workOrder: { name, state: 'assigned' } } },
});
function Blocks({ runtime }: { runtime: SchemaRuntime }) {
  return (
    <>
      <RecordInspectorBlockRenderer
        runtime={runtime}
        block={
          {
            id: 'inspector',
            blockType: 'record-inspector',
            context: '${data.orderDetail.data.workOrder}',
            fields: [{ field: 'name', label: 'Order name' }],
          } as any
        }
      />
      <StageRailBlockRenderer
        runtime={runtime}
        block={
          {
            id: 'stages',
            blockType: 'stage-rail',
            context: '${data.orderDetail.data.workOrder}',
            stageField: 'state',
            stages: [
              { value: 'new', label: 'New' },
              { value: 'assigned', label: 'Assigned' },
            ],
          } as any
        }
      />
      <WorkbenchActionBarBlockRenderer
        runtime={runtime}
        block={
          {
            id: 'actions',
            blockType: 'workbench-action-bar',
            actions: [
              {
                code: 'repair',
                label: 'Record repair',
                visibleWhen: "data.orderDetail.data.workOrder.state == 'assigned'",
              },
            ],
          } as any
        }
      />
    </>
  );
}

describe('Public runtime data-source context', () => {
  it('updates inspector, stage and action visibility after an async named-source response, and hides failed reads', async () => {
    const { manager, runtime } = fixture();
    transport.mockReset();
    transport.mockResolvedValueOnce(wire('Order A'));
    const view = render(<Blocks runtime={runtime} />);
    expect(screen.getByTestId('record-inspector-empty')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Record repair' })).not.toBeInTheDocument();
    await act(async () => {
      await manager.fetch('orderDetail');
    });
    expect(await screen.findByText('Order A')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Record repair' })).toBeVisible();
    expect(screen.getAllByText('Assigned')).toHaveLength(2);
    const context = runtime.getContext() as any;
    expect(context.data.orderDetail.data.workOrder.name).toBe('Order A');
    expect(context.dataSource.orderDetail).toEqual(context.dataSources.orderDetail);
    transport.mockResolvedValueOnce({
      code: '403',
      message: 'Parent READ revoked',
      httpStatus: 403,
    });
    await act(async () => {
      await manager.reload('orderDetail');
    });
    expect(screen.getByTestId('record-inspector-empty')).toBeVisible();
    expect(screen.queryByText('Order A')).not.toBeInTheDocument();
    expect(screen.getAllByText('Assigned')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Record repair' })).not.toBeInTheDocument();
    (manager.getConfig('orderDetail') as any).minFetchIntervalMs = 10000;
    await act(async () => { await manager.fetch('orderDetail'); });
    expect((runtime.getContext() as any).data.orderDetail).toBeUndefined();
    expect(screen.queryByRole('button', { name:'Record repair' })).not.toBeInTheDocument();
    expect(transport).toHaveBeenCalledTimes(2);
    expect(manager.getData('orderDetail')).toBeTruthy(); // Table stale diagnostics remain separate from active bindings.
    view.unmount();
    runtime.destroy();
    manager.clear();
  });
  it('clears old-record bindings before the replacement fetch and ignores its late response', async () => {
    const { manager, runtime } = fixture();
    transport.mockReset();
    transport.mockResolvedValueOnce(wire('Order A'));
    await manager.fetch('orderDetail');
    let finishOld!: (value: any) => void;
    transport.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOld = resolve;
        }),
    );
    const old = manager.reload('orderDetail');
    runtime.getStateManager().updateScope(runtime.getScopeId(), { record: { pid: 'B' } });
    expect((runtime.getContext() as any).data.orderDetail).toBeUndefined();
    transport.mockResolvedValueOnce(wire('Order B'));
    await manager.reload('orderDetail');
    finishOld(wire('Old A response'));
    await old;
    expect((runtime.getContext() as any).data.orderDetail.data.workOrder.name).toBe('Order B');
    runtime.destroy();
    manager.clear();
  });
  it('keeps equal source IDs isolated across runtime scopes and cleans subscriptions on unmount', async () => {
    const a = fixture('A'),
      b = fixture('B');
    transport.mockReset();
    transport.mockResolvedValueOnce(wire('Only A'));
    await a.manager.fetch('orderDetail');
    expect((b.runtime.getContext() as any).data?.orderDetail).toBeUndefined();
    const listener = vi.fn();
    const unsubscribe = (a.runtime as any).subscribeContext(listener);
    a.manager.setData('orderDetail', { data: { workOrder: { name: 'Updated A' } } });
    expect(listener).toHaveBeenCalled();
    unsubscribe();
    listener.mockClear();
    a.manager.setData('orderDetail', { data: { workOrder: { name: 'Later A' } } });
    expect(listener).not.toHaveBeenCalled();
    a.runtime.destroy();
    b.runtime.destroy();
    a.manager.clear();
    b.manager.clear();
  });
  it('updates the actual kernel visibleWhen boundary when a named source succeeds or is revoked', async () => {
    const { manager, runtime } = fixture();
    transport.mockReset();
    transport.mockResolvedValueOnce(wire('A'));
    const profile = {
      blockRenderers: new Map([['fixture-label', () => <div>Conditional source header</div>]]),
    } as any;
    const block = {
      id: 'conditional',
      blockType: 'fixture-label',
      visibleWhen: "data.orderDetail.data.workOrder.state == 'assigned'",
    } as any;
    const view = render(
      <ProfileProvider value={profile}>
        <KernelBlockRenderer block={block} runtime={runtime} />
      </ProfileProvider>,
    );
    expect(screen.queryByText('Conditional source header')).not.toBeInTheDocument();
    await act(async () => {
      await manager.fetch('orderDetail');
    });
    expect(await screen.findByText('Conditional source header')).toBeVisible();
    await act(async () => {
      manager.setError('orderDetail', Object.assign(new Error('Revoked'), { status: 403 }));
    });
    expect(screen.queryByText('Conditional source header')).not.toBeInTheDocument();
    view.unmount();
    runtime.destroy();
    manager.clear();
  });
  it('fails closed if a manager is rebound to another owner, with old replies unable to seed the new scope', async () => {
    const a = fixture('A');
    transport.mockReset();
    let finish!: (value: any) => void;
    transport.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const pending = a.manager.fetch('orderDetail');
    const b = new SchemaRuntime({
      dataSourceManager: a.manager,
      scopeId: 'scope-B',
      schema: {
        id: 'B',
        kind: 'detail',
        title: 'B',
        blocks: [],
        layout: { type: 'grid', cols: 1 },
      } as any,
      globalState: { locale: 'en', theme: 'light', t: (key: string) => key },
      initialContext: { record: { pid: 'B' } } as any,
      skipDataSourceRegistration: true,
    });
    expect(() => a.runtime.getContext()).toThrow('another runtime scope');
    finish(wire('Secret A'));
    await pending;
    expect((b.getContext() as any).data.orderDetail).toBeUndefined();
    a.runtime.destroy();
    b.destroy();
    a.manager.clear();
  });
  it('refreshes source bindings between flow steps instead of retaining the handler-start snapshot', async () => {
    const { manager, runtime } = fixture();
    transport.mockReset();
    transport.mockResolvedValueOnce(wire('Before')).mockResolvedValueOnce(wire('After'));
    await manager.fetch('orderDetail');
    const captured: string[] = [];
    const action = 'test.runtime-context.capture';
    actionRegistry.register(action, ({ expressionContext }) => {
      captured.push((expressionContext as any).data.orderDetail.data.workOrder.name);
    });
    try {
      await runtime.getFlowRunner().run(
        [
          { action, args: {} },
          { action: 'dataSource.reload', args: { target: 'orderDetail' } },
          { action, args: {} },
        ],
        runtime.getContext(),
      );
      expect(captured).toEqual(['Before', 'After']);
    } finally {
      actionRegistry.unregister(action);
      runtime.destroy();
      manager.clear();
    }
  });
  it('does not let a destroyed former runtime unregister the new owner source', () => {
    const manager = new DataSourceManager(createExpressionContext({} as any));
    const config = {
      dataSourceManager: manager,
      globalState: { locale: 'en', theme: 'light', t: (key: string) => key },
      disableAutoFetch: true,
      schema: {
        id: 'owner',
        kind: 'detail',
        title: 'Owner',
        blocks: [],
        layout: { type: 'grid', cols: 1 },
        dataSources: { owned: { type: 'api', endpoint: '/api/owned', autoFetch: false } },
      },
    } as any;
    const old = new SchemaRuntime({ ...config, scopeId: 'old' });
    const current = new SchemaRuntime({ ...config, scopeId: 'current' });
    old.destroy();
    expect(manager.has('owned')).toBe(true);
    current.destroy();
    manager.clear();
  });
  it('keeps an explicit caller query result active while invalidating changed runtime-bound inputs', async () => {
    const { manager, runtime } = fixture();
    transport.mockReset();
    transport.mockResolvedValueOnce(wire('User query'));
    await manager.fetch('orderDetail', { keyword: 'typed search' });
    expect((runtime.getContext() as any).data.orderDetail.data.workOrder.name).toBe('User query');
    runtime.syncContext({ record: { pid: 'B' } } as any);
    expect((runtime.getContext() as any).data.orderDetail).toBeUndefined();
    runtime.destroy();
    manager.clear();
  });
});
