import { describe, expect, it, vi } from 'vitest';
import {
  buildPaginationRequestParams,
  validatePaginationRequest,
} from '../../../schemas/paginationRequest';
import { dataSourceConfigSchema } from '../../../validation/schemas/data-source.schema';
import { DataSourceManager } from '../DataSourceManager';
import { createExpressionContext } from '../../expression/context';
const transport = vi.hoisted(() => vi.fn());
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: any[]) => transport(...args),
}));
const mapping = {
  pageNumPath: ['payload', 'pageNum'],
  pageSizePath: ['payload', 'pageSize'],
} as any;
const params = { targetRecordPid: 'parent-A', payload: { pageNum: 1, pageSize: 2, state: 'open' } };
const wire = (name: string) => ({
  code: '0',
  data: {
    phaseReached: 'completed',
    data: { records: [{ pid: name }], total: 1, current: 1, size: 2 },
  },
});

describe('Explicit pagination request mapping', () => {
  it('maps only runtime paging, preserves identity/filters and never mutates the configured payload', () => {
    expect(buildPaginationRequestParams(params, { pageNum: 2, pageSize: 3 }, mapping)).toEqual({
      targetRecordPid: 'parent-A',
      payload: { pageNum: 2, pageSize: 3, state: 'open' },
    });
    expect(params.payload).toEqual({ pageNum: 1, pageSize: 2, state: 'open' });
    expect(buildPaginationRequestParams(params, undefined, mapping)).toEqual(params);
    expect(
      buildPaginationRequestParams({ pageNum: 1, pageSize: 2 }, { pageNum: 2 }, undefined),
    ).toEqual({ pageNum: 2, pageSize: 2 });
  });
  it.each([
    { pageNumPath: [], pageSizePath: ['size'] },
    { pageNumPath: ['payload'], pageSizePath: ['payload', 'size'] },
    { pageNumPath: ['payload', 'page'], pageSizePath: ['payload', 'page'] },
    { pageNumPath: ['__proto__', 'page'], pageSizePath: ['size'] },
    { pageNumPath: ['payload', 'constructor'], pageSizePath: ['size'] },
    { pageNumPath: ['prototype'], pageSizePath: ['size'] },
    { pageNumPath: ['targetRecordPid'], pageSizePath: ['size'] },
    { pageNumPath: ['payload', ' page'], pageSizePath: ['size'] },
    { pageNumPath: ['payload', 'page'], pageSizePath: ['size'], unknown: true },
  ])('rejects malformed or ambiguous mapping %j in both runtime and Zod', (value) => {
    expect(() => validatePaginationRequest(value)).toThrow();
    expect(dataSourceConfigSchema.safeParse({ paginationRequest: value }).success).toBe(false);
  });
  it('rejects scalar/array traversal, missing initial paging, root duplicates and invalid page values', () => {
    for (const payload of [null, [], { pageNum: 1 }, 'scalar'])
      expect(() =>
        buildPaginationRequestParams({ payload }, { pageNum: 2, pageSize: 2 }, mapping),
      ).toThrow();
    expect(() =>
      buildPaginationRequestParams({ ...params, pageNum: 1 }, { pageNum: 2 }, mapping),
    ).toThrow('Conflicting root');
    for (const pageNum of [0, -1, 1.5, '2', NaN])
      expect(() => buildPaginationRequestParams(params, { pageNum }, mapping)).toThrow();
  });
  it('fails unsupported body-only sources before transport rather than silently ignoring body', () => {
    transport.mockReset();
    const manager = new DataSourceManager(createExpressionContext({} as any));
    expect(() =>
      manager.register('bad', {
        type: 'api',
        method: 'post',
        body: { payload: { pageNum: 1 } },
      } as any),
    ).toThrow('body is unsupported');
    expect(dataSourceConfigSchema.safeParse({ type: 'api', body: {} }).success).toBe(false);
    expect(transport).not.toHaveBeenCalled();
    manager.clear();
  });
  it('separates effective nested request keys, coalesces equal requests and discards older page replies', async () => {
    transport.mockReset();
    const pending: Array<(value: any) => void> = [];
    transport.mockImplementation(() => new Promise((resolve) => pending.push(resolve)));
    const manager = new DataSourceManager(createExpressionContext({} as any));
    manager.register('rows', {
      type: 'api',
      autoFetch: false,
      method: 'post',
      endpoint: '/api/meta/commands/execute/example:read',
      adaptor: 'commandTable',
      params,
      paginationRequest: mapping,
    });
    const older = manager.fetch('rows', { pageNum: 2, pageSize: 2 });
    const latest = manager.fetch('rows', { pageNum: 1, pageSize: 3 });
    const coalesced = manager.fetch('rows', { pageNum: 1, pageSize: 3 });
    expect(transport).toHaveBeenCalledTimes(2);
    expect(transport.mock.calls.map((call) => call[1].params.payload)).toEqual([
      { pageNum: 2, pageSize: 2, state: 'open' },
      { pageNum: 1, pageSize: 3, state: 'open' },
    ]);
    pending[1](wire('latest'));
    await latest;
    await coalesced;
    pending[0](wire('older'));
    await older;
    expect(manager.getData('rows').records).toEqual([{ pid: 'latest' }]);
    manager.clear();
  });
  it('clears old-parent rows immediately and does not reuse its soft cache when returning to it', async () => {
    transport.mockReset();
    transport.mockResolvedValue(wire('A'));
    const context = createExpressionContext({ record: { pid: 'parent-A' } } as any);
    const manager = new DataSourceManager(context);
    manager.register('rows', {
      type: 'api',
      autoFetch: false,
      method: 'post',
      endpoint: '/api/meta/commands/execute/example:read',
      adaptor: 'commandTable',
      params: { ...params, targetRecordPid: '${record.pid}' },
      paginationRequest: mapping,
      minFetchIntervalMs: 10000,
    } as any);
    await manager.fetch('rows');
    let finish!: (value: any) => void;
    transport.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    manager.updateContext(createExpressionContext({ record: { pid: 'parent-B' } } as any));
    const loading = manager.fetch('rows');
    expect(manager.getData('rows')).toBeNull();
    finish(wire('B'));
    await loading;
    manager.updateContext(createExpressionContext({ record: { pid: 'parent-A' } } as any));
    await manager.fetch('rows');
    expect(transport).toHaveBeenCalledTimes(3);
    expect(transport.mock.calls.map((call) => call[1].params.targetRecordPid)).toEqual([
      'parent-A',
      'parent-B',
      'parent-A',
    ]);
    manager.clear();
  });
  it('does not coalesce a returning parent with its stale in-flight request', async () => {
    transport.mockReset();
    const pending: Array<(value: any) => void> = [];
    transport.mockImplementation(() => new Promise((resolve) => pending.push(resolve)));
    const manager = new DataSourceManager(createExpressionContext({ record: { pid: 'A' } } as any));
    manager.register('rows', {
      type: 'api',
      autoFetch: false,
      method: 'post',
      endpoint: '/api/meta/commands/execute/example:read',
      adaptor: 'commandTable',
      params: { ...params, targetRecordPid: '${record.pid}' },
      paginationRequest: mapping,
    });
    const oldA = manager.fetch('rows');
    manager.updateContext(createExpressionContext({ record: { pid: 'B' } } as any));
    const requestB = manager.fetch('rows');
    manager.updateContext(createExpressionContext({ record: { pid: 'A' } } as any));
    const newA = manager.fetch('rows');
    expect(transport).toHaveBeenCalledTimes(3);
    pending[2](wire('new-A'));
    await newA;
    pending[0](wire('old-A'));
    pending[1](wire('B'));
    await oldA;
    await requestB;
    expect(manager.getData('rows').records).toEqual([{ pid: 'new-A' }]);
    manager.clear();
  });
});
