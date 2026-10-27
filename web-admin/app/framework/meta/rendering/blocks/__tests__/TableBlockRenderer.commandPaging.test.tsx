import React from 'react';
import { createServer } from 'node:http';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { TableBlockRenderer } from '../TableBlockRenderer';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
const transport = vi.hoisted(() => vi.fn());
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: any[]) => transport(...args),
}));
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: 'fixture', hasPermission: () => true }),
}));
vi.mock('~/contexts/TimezoneContext', () => ({
  useTimezone: () => ({ timezone: 'UTC', formats: {} }),
}));

const mapping = { pageNumPath: ['payload', 'pageNum'], pageSizePath: ['payload', 'pageSize'] };
function prepare() {
  let context = createExpressionContext({
    locale: 'en',
    t: (key: string) => key,
    record: { pid: 'parent-A' },
  } as any);
  const manager = new DataSourceManager(context);
  const params = {
    targetRecordPid: '${record.pid}',
    payload: { pageNum: 1, pageSize: 2, state: 'open' },
  };
  manager.register('history', {
    type: 'api',
    autoFetch: false,
    method: 'post',
    adaptor: 'commandTable',
    modelCode: 'history',
    endpoint: '/api/meta/commands/execute/example:history',
    params,
    paginationRequest: mapping,
  } as any);
  const runtime = {
    getContext: () => context,
    getSchema: () => ({ modelCode: 'parent_model' }),
    getDataSourceManager: () => manager,
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (v: any) => v,
      evaluateObject: (v: any) => v,
    }),
    getStateManager: () => ({ getContext: () => context, updateState: vi.fn() }),
    getScopeId: () => 'fixture',
  } as any;
  const block = {
    id: 'history',
    blockType: 'table',
    dataSource: 'history',
    columns: [{ field: 'name', label: 'Name' }],
    table: {
      pagination: {
        mode: 'server',
        pageField: 'current',
        pageSizeOptions: [2, 3],
        showSizeChanger: true,
      },
    },
  } as any;
  return {
    manager,
    runtime,
    block,
    params,
    setParent: (pid: string) => {
      context = createExpressionContext({ ...context, record: { pid } } as any);
      manager.updateContext(context);
    },
  };
}

describe('Table command pagination HTTP boundary', () => {
  it('requests page two and size changes inside payload, preserves the parent READ target and resets on parent change', async () => {
    const calls: any[] = [];
    let denied = false;
    const server = createServer(async (req, res) => {
      const chunks = [];
      for await (const part of req) chunks.push(part);
      const params = JSON.parse(Buffer.concat(chunks).toString());
      calls.push({ url: req.url, params });
      res.setHeader('Content-Type', 'application/json');
      if (denied) {
        res.statusCode = 403;
        res.end(JSON.stringify({ code: '403', message: 'Parent READ denied' }));
        return;
      }
      const { pageNum, pageSize } = params.payload;
      const rows = Array.from({ length: 5 }, (_, i) => ({
        pid: params.targetRecordPid + '-' + (i + 1),
        name: params.targetRecordPid + ' row ' + (i + 1),
      }));
      res.end(
        JSON.stringify({
          code: '0',
          data: {
            phaseReached: 'completed',
            data: {
              records: rows.slice((pageNum - 1) * pageSize, pageNum * pageSize),
              total: rows.length,
              current: pageNum,
              size: pageSize,
            },
          },
        }),
      );
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const base = 'http://127.0.0.1:' + (server.address() as any).port;
    transport.mockReset();
    transport.mockImplementation(async (url, options) => {
      const response = await fetch(base + url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(options.params),
      });
      return { ...(await response.json()), httpStatus: response.status };
    });
    const { manager, runtime, block, params, setParent } = prepare();
    try {
      await manager.fetch('history');
      const view = render(
        <MemoryRouter>
          <TableBlockRenderer block={block} runtime={runtime} />
        </MemoryRouter>,
      );
      expect(screen.getByText('parent-A row 1')).toBeVisible();
      fireEvent.click(screen.getByRole('button', { name: '2', exact: true }));
      expect(await screen.findByText('parent-A row 3')).toBeVisible();
      expect(calls[1].params).toEqual({
        targetRecordPid: 'parent-A',
        payload: { pageNum: 2, pageSize: 2, state: 'open' },
      });
      fireEvent.change(screen.getByTestId('pagination-page-size'), { target: { value: '3' } });
      await waitFor(() => expect(calls).toHaveLength(3));
      expect(calls[2].params).toEqual({
        targetRecordPid: 'parent-A',
        payload: { pageNum: 1, pageSize: 3, state: 'open' },
      });
      expect(await screen.findByText('parent-A row 1')).toBeVisible();
      await act(async () => {
        await manager.reload('history');
      });
      expect(await screen.findByText('parent-A row 1')).toBeVisible();
      expect(screen.queryByTestId('table-page-contract-error')).not.toBeInTheDocument();
      expect(calls[3].params.payload).toEqual({ pageNum: 1, pageSize: 2, state: 'open' });
      await act(async () => {
        setParent('parent-B');
        await manager.reload('history');
      });
      view.rerender(
        <MemoryRouter>
          <TableBlockRenderer block={block} runtime={runtime} />
        </MemoryRouter>,
      );
      expect(await screen.findByText('parent-B row 1')).toBeVisible();
      expect(screen.queryByText('parent-A row 1')).not.toBeInTheDocument();
      expect(calls[4].params).toEqual({
        targetRecordPid: 'parent-B',
        payload: { pageNum: 1, pageSize: 2, state: 'open' },
      });
      denied = true;
      fireEvent.click(screen.getByRole('button', { name: '2', exact: true }));
      expect(await screen.findByTestId('table-error-state-403')).toHaveTextContent(
        'Parent READ denied',
      );
      expect(screen.queryByText('parent-B row 1')).not.toBeInTheDocument();
      expect(calls).toHaveLength(6);
      expect(calls.every((call) => call.url === '/api/meta/commands/execute/example:history')).toBe(
        true,
      );
      expect(params).toEqual({
        targetRecordPid: '${record.pid}',
        payload: { pageNum: 1, pageSize: 2, state: 'open' },
      });
    } finally {
      manager.clear();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });
  it('rejects a stale page response and retries the exact attempted page without relabeling rows', async () => {
    const { manager, runtime, block } = prepare();
    let mismatch = true;
    transport.mockReset();
    transport.mockImplementation(async (_url, options) => {
      const requested = options.params.payload.pageNum;
      const current = mismatch ? 1 : requested;
      return {
        code: '0',
        data: {
          phaseReached: 'completed',
          data: {
            records: [1, 2].map((i) => ({
              pid: 'row-' + ((current - 1) * 2 + i),
              name: 'Page ' + current + ' row ' + i,
            })),
            total: 5,
            current,
            size: 2,
          },
        },
      };
    });
    await manager.fetch('history');
    render(
      <MemoryRouter>
        <TableBlockRenderer block={block} runtime={runtime} />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole('button', { name: '2', exact: true }));
    expect(await screen.findByTestId('table-page-contract-error')).toBeVisible();
    expect(screen.queryByText('Page 2 row 1')).not.toBeInTheDocument();
    mismatch = false;
    fireEvent.click(screen.getByRole('button', { name: 'Retry', exact: true }));
    expect(await screen.findByText('Page 2 row 1')).toBeVisible();
    expect(transport.mock.calls.map((call) => call[1].params.payload.pageNum)).toEqual([1, 2, 2]);
    manager.clear();
  });
});
