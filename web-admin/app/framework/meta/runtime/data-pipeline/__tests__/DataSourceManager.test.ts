import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { waitFor } from '@testing-library/react';
import { fetchResult } from '~/shared/services/http-client';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { canonicalizePageSchemaDto } from '~/framework/meta/utils/canonicalizePageDsl';
import { mergeDataSources } from '~/framework/meta/utils/extractDataSourcesFromSchema';
import { readServerTablePage } from '~/framework/meta/rendering/blocks/serverTablePage';
import { ScopedStateManager } from '~/framework/meta/runtime/state/scoped-state';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

const mockedFetchResult = vi.mocked(fetchResult);

describe('DataSourceManager', () => {
  it('preserves real paging metadata through the native statistics configuration and manager defaults', async () => {
    const pages = JSON.parse(readFileSync(resolve(import.meta.dirname, '../../../../../../../plugins/core-announcement/config/pages.json'), 'utf8'));
    const page = canonicalizePageSchemaDto(pages.find((candidate: any) => candidate.pageKey === 'announcement_statistics_detail'));
    const config = mergeDataSources(page).recipients;
    const context = createExpressionContext({} as any);
    (context as any).$page = { recordPid: 'capture-fixture' };
    const manager = new DataSourceManager(context);
    const records = Array.from({ length: 3 }, (_, i) => ({ pid: `recipient-${i}`, displayName: `Person ${i}` }));
    const wire = { records, total: 3, pageNum: 1, pageSize: 20, asOf: '2026-10-25T00:00:00Z' };
    mockedFetchResult.mockResolvedValueOnce({ code: '0', data: wire } as any);
    manager.register('recipients', { ...config, autoFetch: false });
    await manager.fetch('recipients');
    expect(mockedFetchResult).toHaveBeenCalledExactlyOnceWith('/api/announcements/statistics/snapshots/capture-fixture/recipients', {
      method: 'get', params: { pageNum: 1, pageSize: 20 },
    });
    expect(manager.getData('recipients')).toEqual(wire);
    expect(readServerTablePage(manager.getData('recipients'))).toEqual({ current: 1, pageSize: 20, total: 3 });
    manager.clear();
  });

  beforeEach(() => {
    mockedFetchResult.mockReset();
  });

  const commandTableManager = () => {
    const manager = new DataSourceManager(
      createExpressionContext({
        form: { billNo: 'REQ-100' },
        state: { routeContext: { taskPid: 'task-1' } },
      } as any),
    );
    manager.register('candidates', {
      type: 'api',
      method: 'post',
      autoFetch: false,
      adaptor: 'commandTable',
      endpoint: '/api/meta/commands/execute/example:read_candidates',
      params: {
        targetRecordPid: '${state.routeContext.taskPid}',
        payload: { billNo: '${form.billNo}', pageSize: 20 },
      },
    });
    return manager;
  };

  it('adapts completed command pages and preserves evaluated command input', async () => {
    const records = [{ pid: 'candidate-1', quantity: 30 }];
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: {
        phaseReached: 'completed',
        data: { records, total: 1, current: 1, size: 20 },
      },
    } as any);
    const manager = commandTableManager();
    expect(await manager.fetch('candidates')).toEqual({
      records,
      total: 1,
      current: 1,
      pageSize: 20,
    });
    expect(mockedFetchResult).toHaveBeenCalledExactlyOnceWith(
      '/api/meta/commands/execute/example:read_candidates',
      {
        method: 'post',
        params: { targetRecordPid: 'task-1', payload: { billNo: 'REQ-100', pageSize: 20 } },
      },
    );
    expect(manager.getState('candidates')?.error).toBeNull();
  });

  it('accepts an explicitly completed empty command page', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: {
        phaseReached: 'completed',
        data: { records: [], total: 0, current: 1, size: 1 },
      },
    } as any);
    expect(await commandTableManager().fetch('candidates')).toEqual({
      records: [],
      total: 0,
      current: 1,
      pageSize: 1,
    });
  });

  it.each([
    { phaseReached: 'handler', data: { records: [], total: 0, current: 1, size: 1 } },
    { phaseReached: 'completed', records: [], total: 0, current: 1, size: 1 },
    { phaseReached: 'completed', data: { list: [], total: 0, current: 1, size: 1 } },
    ...[
      { total: '1' },
      { total: -1 },
      { total: 0 },
      { current: 0 },
      { current: 1.5 },
      { size: 0 },
      { size: '20' },
      { records: [null] },
      { records: [['nested']] },
      { records: [{}, {}], size: 1 },
    ].map((invalid) => ({
      phaseReached: 'completed',
      data: {
        records: [{ pid: 'candidate-1' }],
        total: 1,
        current: 1,
        size: 20,
        ...invalid,
      },
    })),
  ])('reports invalid command pages instead of presenting an empty table: %j', async (data) => {
    mockedFetchResult.mockResolvedValueOnce({ code: '0', data } as any);
    const manager = commandTableManager();
    expect(await manager.fetch('candidates')).toBeNull();
    expect(manager.getState('candidates')?.error?.message).toBe(
      'Invalid completed command table response',
    );
    expect(mockedFetchResult).toHaveBeenCalledTimes(1);
  });

  it('preserves permission errors before command table adaptation', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '403',
      message: 'Forbidden',
      data: null,
    } as any);
    const manager = commandTableManager();
    expect(await manager.fetch('candidates')).toBeNull();
    expect(manager.getState('candidates')?.error).not.toBeNull();
    expect(mockedFetchResult).toHaveBeenCalledTimes(1);
  });

  it('extracts exact completed command rows without changing decimal strings', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: {
        phaseReached: 'completed',
        data: { lines: [{ pid: 'a', quantity: '100.000000000000001', included: false }] },
      },
    } as any);
    const manager = commandTableManager();
    manager.register('lines', {
      type: 'api',
      method: 'post',
      endpoint: '/api/meta/commands/execute/example:view',
      adaptor: 'commandArray',
      dataPath: 'lines',
      params: { targetRecordPid: 'a' },
    } as any);
    expect(await manager.fetch('lines')).toEqual([
      { pid: 'a', quantity: '100.000000000000001', included: false },
    ]);
    expect(mockedFetchResult.mock.calls[0][1]?.method).toBe('post');
  });

  it.each(['handler', 'failed'])('refuses incomplete command arrays (%s)', async (phaseReached) => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { phaseReached, data: { lines: [] } },
    } as any);
    const manager = commandTableManager();
    manager.register('lines', {
      type: 'api',
      endpoint: '/x',
      adaptor: 'commandArray',
      dataPath: 'lines',
    } as any);
    expect(await manager.fetch('lines')).toBeNull();
    expect(manager.getState('lines')?.error).not.toBeNull();
  });

  it('refuses a truncated command options collection', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: {
        phaseReached: 'completed',
        data: { items: [{ pid: 'a', label: 'A', selectable: true }], total: 2 },
      },
    } as any);
    const manager = commandTableManager();
    manager.register('options', {
      type: 'api',
      endpoint: '/x',
      adaptor: 'commandOptions',
      dataPath: 'items',
      valueField: 'pid',
      labelField: 'label',
      selectableField: 'selectable',
    } as any);
    expect(await manager.fetch('options')).toBeNull();
  });

  it('retains typed permission denial after real manager option reload', async () => {
    const manager = commandTableManager();
    manager.register('options', {
      type: 'api',
      method: 'post',
      endpoint: '/x',
      autoFetch: false,
      adaptor: 'commandOptions',
      dataPath: 'items',
      valueField: 'pid',
      labelField: 'label',
      selectableField: 'selectable',
    });
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: {
        phaseReached: 'completed',
        data: { items: [{ pid: 'p', label: 'P', selectable: false }], total: 1 },
      },
    } as any);
    expect(await manager.fetch('options')).toEqual([{ value: 'p', label: 'P', disabled: true }]);
    mockedFetchResult.mockResolvedValueOnce({
      code: '403',
      data: null,
      message: 'Forbidden',
    } as any);
    await manager.reload('options');
    expect(manager.getState('options')?.data).toEqual([{value:'p',label:'P',disabled:true}]);
    expect((manager.getState('options')?.error as any)?.code).toBe('403');
    expect(mockedFetchResult).toHaveBeenCalledTimes(2);
  });

  it('defers detail child data sources until form.pid is available', async () => {
    mockedFetchResult.mockResolvedValue({
      code: '0',
      data: { records: [], total: 0, current: 1, pageSize: 500 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({ form: {}, state: {} } as any),
    );
    manager.register('standardLines', {
      type: 'api',
      endpoint: '/api/dynamic/bom_standard_line_pcba/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
      params: {
        pageNum: 1,
        pageSize: 500,
        filters: [
          {
            fieldName: 'bom_std_task_id',
            operator: 'EQ',
            value: '${form.pid}',
          },
        ],
      },
      dependOn: ['form.pid'],
    });

    await manager.fetch('standardLines');
    expect(mockedFetchResult).not.toHaveBeenCalled();

    manager.updateContext(
      createExpressionContext({ form: { pid: 'task-1' }, state: {} } as any),
    );
    await manager.fetch('standardLines');

    expect(mockedFetchResult).toHaveBeenCalledTimes(1);
    const params = mockedFetchResult.mock.calls[0][1]?.params as Record<string, any>;
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'bom_std_task_id', operator: 'EQ', value: 'task-1' },
    ]);
  });

  it('evaluates object params against runtime record and state context', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { records: [], total: 0, current: 1, pageSize: 100 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        record: { pid: 'task-1' },
        state: { selectedLine: { pid: 'line-1' } },
      } as any),
    );
    manager.register('canonicalLines', {
      type: 'api',
      endpoint: '/api/dynamic/req_requirement_line_pcba_bom/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
      params: {
        pageNum: 1,
        pageSize: 100,
        bom_cl_task_id: '${record.pid}',
        selectedLineId: '${state.selectedLine.pid}',
      },
    });

    await manager.fetch('canonicalLines');

    expect(mockedFetchResult).toHaveBeenCalledWith(
      '/api/dynamic/req_requirement_line_pcba_bom/list',
      expect.objectContaining({
        method: 'get',
        params: expect.objectContaining({
          pageNum: 1,
          pageSize: 100,
          selectedLineId: 'line-1',
        }),
      }),
    );
    const params = mockedFetchResult.mock.calls[0][1]?.params as Record<string, any>;
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'bom_cl_task_id', operator: 'EQ', value: 'task-1' },
    ]);
  });

  it('evaluates API endpoints against nested runtime state', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { metrics: { http_requests: 12 }, cost: { total: 3.7 } },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        state: { selectedUsageJob: { pid: 'job-pid-1' } },
      } as any),
    );
    manager.register('selectedJobUsage', {
      type: 'api',
      endpoint: '/api/ext/cr/jobs/${state.selectedUsageJob.pid}/usage',
      method: 'get',
      adaptor: 'object',
      autoFetch: false,
      dependOn: ['state.selectedUsageJob.pid'],
    });

    await manager.fetch('selectedJobUsage');

    expect(mockedFetchResult).toHaveBeenCalledWith(
      '/api/ext/cr/jobs/job-pid-1/usage',
      expect.objectContaining({ method: 'get' }),
    );
  });

  it('accepts raw JSON objects from custom API data sources', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '',
      desc: '',
      message: '',
      success: false,
      data: null,
      context: null,
      metrics: { http_requests: 1842, render_requests: 316 },
      cost: { total: 50.28, currency: 'CNY' },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        state: { selectedUsageJob: { pid: 'job-pid-1' } },
      } as any),
    );
    manager.register('selectedJobUsage', {
      type: 'api',
      endpoint: '/api/ext/cr/jobs/${state.selectedUsageJob.pid}/usage',
      method: 'get',
      adaptor: 'object',
      autoFetch: false,
      dependOn: ['state.selectedUsageJob.pid'],
    });

    await manager.fetch('selectedJobUsage');

    expect(manager.getData('selectedJobUsage')).toEqual({
      metrics: { http_requests: 1842, render_requests: 316 },
      cost: { total: 50.28, currency: 'CNY' },
    });
  });

  it('table adaptor treats a plain top-level array as records (custom REST endpoint returning data:[...])', async () => {
    // A custom endpoint (e.g. GET /api/qr) returns ResultData with `data` being a plain array, not a
    // paginated { records } object. The `table` adaptor must surface those rows so a DSL table column
    // binds row[field]; otherwise the page falls back to the default optionList adaptor → {value,label}
    // → every column renders "-".
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: [
        { pid: 'a', shortCode: 'X1', title: 'Alpha' },
        { pid: 'b', shortCode: 'X2', title: 'Beta' },
      ],
    } as any);

    const manager = new DataSourceManager(createExpressionContext({} as any));
    manager.register('qrList', {
      type: 'api',
      endpoint: '/api/qr',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
    });

    await manager.fetch('qrList');

    const data = manager.getData('qrList');
    expect(data.records).toHaveLength(2);
    expect(data.total).toBe(2);
    expect(data.records[0].shortCode).toBe('X1');
    expect(data.records[1].title).toBe('Beta');
  });

  it('passes format=records for namedQuery sources so metric-strip gets raw aggregate rows', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { list: [{ total_devices: 5, online_devices: 2 }], total: 1 },
    } as any);

    const manager = new DataSourceManager(createExpressionContext({} as any));
    manager.register('fleetKpi', {
      type: 'namedQuery',
      queryCode: 'iot_dashboard_kpi',
      format: 'records',
      autoFetch: false,
    });

    await manager.fetch('fleetKpi');

    expect(mockedFetchResult).toHaveBeenCalledWith(
      '/api/datasource/list',
      expect.objectContaining({
        method: 'get',
        params: expect.objectContaining({
          datasourceId: 'nq:iot_dashboard_kpi',
          format: 'records',
        }),
      }),
    );
  });

  it('evaluates namedQuery params against detail record/form/state context', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { records: [{ material: 'RC0603FR-0710KL' }], total: 1 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        form: { pid: 'quote-1' },
        record: { pid: 'quote-1' },
        state: { selectedSource: 'kingdee' },
      } as any),
    );
    manager.register('bomPriceWaterfall', {
      type: 'namedQuery',
      queryCode: 'qo_quote_bom_price_waterfall',
      format: 'records',
      adaptor: 'table',
      autoFetch: false,
      params: {
        quoteId: '${form.pid}',
        recordPid: '${record.pid}',
        source: '${state.selectedSource}',
      },
    });

    await manager.fetch('bomPriceWaterfall');

    expect(mockedFetchResult).toHaveBeenCalledWith(
      '/api/datasource/list',
      expect.objectContaining({
        method: 'get',
        params: expect.objectContaining({
          datasourceId: 'nq:qo_quote_bom_price_waterfall',
          format: 'records',
          quoteId: 'quote-1',
          recordPid: 'quote-1',
          source: 'kingdee',
        }),
      }),
    );
  });

  it('coalesces concurrent requests for the same evaluated data source', async () => {
    let resolveFetch: ((value: any) => void) | undefined;
    const pendingFetch = new Promise<any>((resolve) => {
      resolveFetch = resolve;
    });
    mockedFetchResult.mockReturnValueOnce(pendingFetch);

    const manager = new DataSourceManager(
      createExpressionContext({
        form: { pid: 'quote-1' },
      } as any),
    );
    manager.register('bomPriceMetrics', {
      type: 'namedQuery',
      queryCode: 'qo_quote_bom_price_metrics',
      format: 'records',
      adaptor: 'table',
      autoFetch: false,
      params: {
        quoteId: '${form.pid}',
      },
    });

    const first = manager.fetch('bomPriceMetrics');
    const second = manager.fetch('bomPriceMetrics');

    expect(mockedFetchResult).toHaveBeenCalledTimes(1);

    resolveFetch?.({
      code: '0',
      data: { records: [{ total_lines: 7 }], total: 1 },
    });
    await expect(Promise.all([first, second])).resolves.toEqual([
      { records: [{ total_lines: 7 }], total: 1, current: 1, pageSize: 10 },
      { records: [{ total_lines: 7 }], total: 1, current: 1, pageSize: 10 },
    ]);
  });

  it('bypasses min fetch interval for explicit reloads', async () => {
    mockedFetchResult
      .mockResolvedValueOnce({
        code: '0',
        data: { records: [{ pid: 'line-1', status: 'pending' }], total: 1 },
      } as any)
      .mockResolvedValueOnce({
        code: '0',
        data: { records: [{ pid: 'line-1', status: 'confirmed' }], total: 1 },
      } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        form: { pid: 'quote-1' },
      } as any),
    );
    manager.register('bomPriceWaterfall', {
      type: 'namedQuery',
      queryCode: 'qo_quote_bom_price_waterfall',
      format: 'records',
      adaptor: 'table',
      autoFetch: false,
      minFetchIntervalMs: 1500,
      params: {
        quoteId: '${form.pid}',
      },
    } as any);

    await manager.fetch('bomPriceWaterfall');
    await manager.reload('bomPriceWaterfall');

    expect(mockedFetchResult).toHaveBeenCalledTimes(2);
    expect(manager.getData('bomPriceWaterfall')?.records).toEqual([
      { pid: 'line-1', status: 'confirmed' },
    ]);
  });

  it('starts a fresh reload when a matching request is still in flight', async () => {
    let resolveInitial: ((value: any) => void) | undefined;
    const initialFetch = new Promise<any>((resolve) => {
      resolveInitial = resolve;
    });
    mockedFetchResult.mockReturnValueOnce(initialFetch).mockResolvedValueOnce({
      code: '0',
      data: { records: [{ pid: 'line-1', status: 'confirmed' }], total: 1 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        form: { pid: 'quote-1' },
      } as any),
    );
    manager.register('bomPriceWaterfall', {
      type: 'namedQuery',
      queryCode: 'qo_quote_bom_price_waterfall',
      format: 'records',
      adaptor: 'table',
      autoFetch: false,
      params: {
        quoteId: '${form.pid}',
      },
    });

    const staleFetch = manager.fetch('bomPriceWaterfall');
    await manager.reload('bomPriceWaterfall');

    expect(mockedFetchResult).toHaveBeenCalledTimes(2);
    expect(manager.getData('bomPriceWaterfall')?.records).toEqual([
      { pid: 'line-1', status: 'confirmed' },
    ]);

    resolveInitial?.({
      code: '0',
      data: { records: [{ pid: 'line-1', status: 'pending' }], total: 1 },
    });
    await staleFetch;

    expect(manager.getData('bomPriceWaterfall')?.records).toEqual([
      { pid: 'line-1', status: 'confirmed' },
    ]);
  });

  it('keeps cached namedQuery data during rate-limit backoff', async () => {
    mockedFetchResult
      .mockResolvedValueOnce({
        code: '0',
        data: { records: [{ total_lines: 7 }], total: 1 },
      } as any)
      .mockResolvedValueOnce({
        code: '500',
        desc: 'Rate limit exceeded for query: qo_quote_bom_price_metrics (max 60 per minute)',
        data: null,
      } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        form: { pid: 'quote-1' },
      } as any),
    );
    manager.register('bomPriceMetrics', {
      type: 'namedQuery',
      queryCode: 'qo_quote_bom_price_metrics',
      format: 'records',
      adaptor: 'table',
      autoFetch: false,
      rateLimitBackoffMs: 30_000,
      params: {
        quoteId: '${form.pid}',
      },
    } as any);

    await manager.fetch('bomPriceMetrics');
    const rateLimited = await manager.fetch('bomPriceMetrics');
    const skipped = await manager.fetch('bomPriceMetrics');

    expect(rateLimited).toEqual({
      records: [{ total_lines: 7 }],
      total: 1,
      current: 1,
      pageSize: 10,
    });
    expect(skipped).toEqual(rateLimited);
    expect(manager.getState('bomPriceMetrics')?.error).toBeNull();
    expect(mockedFetchResult).toHaveBeenCalledTimes(2);
  });

  it('does not hammer a namedQuery that is rate-limited from the first fetch under repeated forced reloads', async () => {
    // Always rate-limited: the data source NEVER succeeds, so its data stays null.
    // Reproduces the prod loop where a quote price chart (bomPriceMetrics/waterfall)
    // is repeatedly reload()'d (notifyStateChanged → reload → force) and hammers the
    // backend 60/min rate limit because force bypassed the rate-limit backoff.
    mockedFetchResult.mockResolvedValue({
      code: '500',
      desc: 'Rate limit exceeded for query: qo_quote_bom_price_metrics (max 60 per minute)',
      data: null,
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        form: { pid: 'quote-1' },
      } as any),
    );
    manager.register('bomPriceMetrics', {
      type: 'namedQuery',
      queryCode: 'qo_quote_bom_price_metrics',
      format: 'records',
      adaptor: 'table',
      autoFetch: false,
      minFetchIntervalMs: 1500,
      rateLimitBackoffMs: 30_000,
      params: {
        quoteId: '${form.pid}',
      },
    } as any);

    // First fetch is rate-limited → applies the 30s backoff.
    await manager.fetch('bomPriceMetrics');
    // The trigger loop issues repeated forced reloads. These MUST honor the
    // rate-limit backoff — a forced reload is allowed to bypass the soft
    // min-interval throttle, but never the hard backend rate-limit backoff.
    await manager.reload('bomPriceMetrics');
    await manager.reload('bomPriceMetrics');
    await manager.reload('bomPriceMetrics');

    // Only the initial fetch should have hit the backend; the reloads are
    // suppressed by the active backoff (before the fix this was 4 — a hammer loop).
    expect(mockedFetchResult).toHaveBeenCalledTimes(1);
  });

  it('preserves HTTP status metadata on API data-source errors', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '503',
      desc: 'temporary dependency outage',
      success: false,
      data: null,
    } as any);
    const manager = new DataSourceManager(createExpressionContext({} as any));
    manager.register('batchJobList', {
      type: 'api',
      endpoint: '/api/dynamic/iot_dps_batch_onboarding_job_list/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
    });

    await manager.fetch('batchJobList');

    expect(manager.getState('batchJobList')?.error).toMatchObject({
      message: 'temporary dependency outage',
      status: 503,
      statusCode: 503,
      code: '503',
    });
  });

  it('retains human error detail and actual HTTP status separately from business code', async () => {
    for (const httpStatus of [400, 403]) {
      for (const context of ['分页参数无效', { detail: '分页参数无效', messageKey: 'private-key', cause: 'private-cause' }]) {
        mockedFetchResult.mockResolvedValueOnce({
          code: '35000', httpStatus, status: 500, statusCode: 502,
          desc: 'Bad parameter', message: 'Bad parameter', context, success: false, data: null,
        } as any);
        const manager = new DataSourceManager(createExpressionContext({} as any));
        manager.register('offers', { type: 'api', endpoint: '/api/ext/qoe/procurement/quote-lines',
          method: 'get', autoFetch: false });
        await manager.fetch('offers');
        expect(manager.getState('offers')?.error).toMatchObject({
          message: httpStatus === 403 ? 'Bad parameter' : '分页参数无效', status: httpStatus, statusCode: httpStatus, code: '35000',
        });
        expect(manager.getState('offers')?.data).toBeNull();
        expect(manager.getState('offers')?.loading).toBe(false);
      }
    }
  });

  it('does not turn diagnostic objects into user-visible data-source text', async () => {
    for (const context of [{ detail: { private: 'secret' } }, { detail: '  ', messageKey: 'private-key' }]) {
      mockedFetchResult.mockResolvedValueOnce({ code: '35000', httpStatus: 400,
        desc: 'Original failure', context, success: false, data: null } as any);
      const manager = new DataSourceManager(createExpressionContext({} as any));
      manager.register('offers', { type: 'api', endpoint: '/api/ext/qoe/procurement/quote-lines',
        method: 'get', autoFetch: false });
      await manager.fetch('offers');
      expect(manager.getState('offers')?.error?.message).toBe('Original failure');
    }
  });

  it('does not promote generic Core diagnostics but retains actual status and business identity', async () => {
    for (const result of [
      { code: '1', httpStatus: 500, desc: 'Internal system error', context: {
        exception: 'IllegalStateException', detail: 'SELECT secret FROM private_model; PID secret', cause: 'private-cause' } },
      { code: '403', httpStatus: 403, desc: 'Access forbidden', context: 'private permission' },
      { code: '35000', httpStatus: 400, desc: 'Bad parameter', context: {
        exception: 'IllegalStateException', detail: 'private stored snapshot' } },
    ]) {
      mockedFetchResult.mockResolvedValueOnce({ ...result, success: false, data: null } as any);
      const manager = new DataSourceManager(createExpressionContext({} as any));
      manager.register('read', { type: 'api', endpoint: '/api/meta/command-phases', method: 'get', autoFetch: false });
      await manager.fetch('read');
      expect(manager.getState('read')?.error).toMatchObject({
        message: result.desc, status: result.httpStatus, statusCode: result.httpStatus, code: result.code,
      });
      expect(manager.getState('read')?.data).toBeNull();
    }
  });

  it('omits format for namedQuery sources by default (option/dropdown format)', async () => {
    mockedFetchResult.mockResolvedValueOnce({ code: '0', data: [] } as any);

    const manager = new DataSourceManager(createExpressionContext({} as any));
    manager.register('statusOptions', {
      type: 'namedQuery',
      queryCode: 'iot_dashboard_device_status',
      autoFetch: false,
    });

    await manager.fetch('statusOptions');

    const params = mockedFetchResult.mock.calls[0][1]?.params as Record<string, any>;
    expect(params.datasourceId).toBe('nq:iot_dashboard_device_status');
    expect(params.format).toBeUndefined();
  });

  it('keeps updated page context available after binding a schema state manager', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { records: [], total: 0, current: 1, pageSize: 100 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        form: {},
        record: {},
      } as any),
    );
    const stateManager = new ScopedStateManager({
      locale: 'zh-CN',
      theme: 'light',
      user: undefined,
      tenant: undefined,
      t: (key: string) => key,
    } as any);
    stateManager.createScope('scope-1', { state: {} });
    manager.bindStateManager(stateManager, 'scope-1');
    manager.updateContext(
      createExpressionContext({
        form: { pid: 'task-2' },
        record: { pid: 'task-2' },
      } as any),
    );
    manager.register('taskSummary', {
      type: 'api',
      endpoint: '/api/dynamic/bom_conversion_task_pcba/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
      params: {
        pidFromForm: '${form.pid}',
        pidFromRecord: '${record.pid}',
      },
    });

    await manager.fetch('taskSummary');

    expect(mockedFetchResult).toHaveBeenCalledWith('/api/dynamic/bom_conversion_task_pcba/list', {
      method: 'get',
      params: {
        pidFromForm: 'task-2',
        pidFromRecord: 'task-2',
      },
    });
  });

  it('rebuilds dependOn subscriptions when a state manager is bound after registration', async () => {
    mockedFetchResult
      .mockResolvedValueOnce({
        code: '0',
        data: { records: [], total: 0, current: 1, pageSize: 50 },
      } as any)
      .mockResolvedValueOnce({
        code: '0',
        data: { records: [{ pid: 'me-1' }], total: 1, current: 1, pageSize: 50 },
      } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        state: {},
      } as any),
    );
    manager.register('matchEvidence', {
      type: 'api',
      endpoint: '/api/dynamic/bom_match_evidence/list',
      method: 'get',
      adaptor: 'table',
      params: {
        bom_me_canonical_line_id: '${state.selectedBomLine.pid}',
      },
      dependOn: ['state.selectedBomLine.pid'],
    });

    const stateManager = new ScopedStateManager({
      locale: 'zh-CN',
      theme: 'light',
      user: undefined,
      tenant: undefined,
      t: (key: string) => key,
    } as any);
    stateManager.createScope('scope-1', { state: {} });
    manager.bindStateManager(stateManager, 'scope-1');

    stateManager.updateState('scope-1', 'selectedBomLine', { pid: 'std-1' });

    await waitFor(() => {
      expect(mockedFetchResult).toHaveBeenCalledWith(
        '/api/dynamic/bom_match_evidence/list',
        expect.objectContaining({ method: 'get' }),
      );
    });
    const params = mockedFetchResult.mock.calls.at(-1)?.[1]?.params as Record<string, any>;
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'bom_me_canonical_line_id', operator: 'EQ', value: 'std-1' },
    ]);
  });

  it('skips nested state-dependent auto-fetch while parent selection is missing', async () => {
    const consoleWarn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    mockedFetchResult.mockResolvedValue({
      code: '0',
      data: { records: [{ pid: 'me-1' }], total: 1, current: 1, pageSize: 50 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        state: {},
      } as any),
    );
    const stateManager = new ScopedStateManager({
      locale: 'zh-CN',
      theme: 'light',
      user: undefined,
      tenant: undefined,
      t: (key: string) => key,
    } as any);
    stateManager.createScope('scope-1', { state: {} });
    manager.bindStateManager(stateManager, 'scope-1');

    manager.register('matchEvidence', {
      type: 'api',
      endpoint: '/api/dynamic/bom_match_evidence/list',
      method: 'get',
      adaptor: 'table',
      params: {
        bom_me_canonical_line_id: '${state.selectedBomLine.pid}',
      },
      dependOn: ['state.selectedBomLine.pid'],
    });

    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mockedFetchResult).not.toHaveBeenCalled();
    expect(consoleWarn).not.toHaveBeenCalledWith(
      expect.stringContaining('[ExpressionParser] 尝试访问 null 或 undefined 的属性'),
    );

    stateManager.updateState('scope-1', 'selectedBomLine', { pid: 'std-1' });

    await waitFor(() => {
      expect(mockedFetchResult).toHaveBeenCalledWith(
        '/api/dynamic/bom_match_evidence/list',
        expect.objectContaining({ method: 'get' }),
      );
    });
    const params = mockedFetchResult.mock.calls.at(-1)?.[1]?.params as Record<string, any>;
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'bom_me_canonical_line_id', operator: 'EQ', value: 'std-1' },
    ]);

    consoleWarn.mockRestore();
  });

  it('reloads nested state dependents when a root state key is notified', async () => {
    mockedFetchResult.mockResolvedValue({
      code: '0',
      data: { records: [], total: 0, current: 1, pageSize: 50 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        state: {},
      } as any),
    );
    manager.register('matchEvidence', {
      type: 'api',
      endpoint: '/api/dynamic/bom_match_evidence/list',
      method: 'get',
      adaptor: 'table',
      params: {
        bom_me_canonical_line_id: '${state.selectedBomLine.pid}',
      },
      autoFetch: false,
      dependOn: ['state.selectedBomLine.pid'],
    });

    manager.updateContext(
      createExpressionContext({
        state: {
          selectedBomLine: { pid: 'std-1' },
        },
      } as any),
    );

    await manager.notifyStateChanged('selectedBomLine');

    expect(mockedFetchResult).toHaveBeenCalledWith(
      '/api/dynamic/bom_match_evidence/list',
      expect.objectContaining({ method: 'get' }),
    );
    const params = mockedFetchResult.mock.calls.at(-1)?.[1]?.params as Record<string, any>;
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'bom_me_canonical_line_id', operator: 'EQ', value: 'std-1' },
    ]);
  });

  it('clears nested state-dependent data instead of fetching without a leaf value', async () => {
    mockedFetchResult.mockResolvedValue({
      code: '0',
      data: { records: [{ pid: 'shadow-1' }], total: 1, current: 1, pageSize: 1 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        state: {
          selectedDevice: { iot_d_device_code: 'RF-01-TC-CTRL' },
        },
      } as any),
    );
    manager.register('selectedDeviceShadow', {
      type: 'api',
      endpoint: '/api/dynamic/iot_device_shadow_list/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
      params: {
        pageNum: 1,
        pageSize: 1,
        filters: [
          {
            fieldName: 'iot_sh_device_code',
            operator: 'EQ',
            value: '${state.selectedDevice.iot_d_device_code}',
          },
        ],
      },
      dependOn: ['state.selectedDevice.iot_d_device_code'],
    });

    await manager.fetch('selectedDeviceShadow');

    expect(mockedFetchResult).toHaveBeenCalledTimes(1);
    expect(manager.getData('selectedDeviceShadow')?.records).toEqual([{ pid: 'shadow-1' }]);

    manager.updateContext(
      createExpressionContext({
        state: {
          selectedDevice: {},
        },
      } as any),
    );

    await manager.notifyStateChanged('selectedDevice');

    expect(mockedFetchResult).toHaveBeenCalledTimes(1);
    expect(manager.getData('selectedDeviceShadow')).toBeNull();
  });

  it('converts dynamic list field params into filters and skips blank optional values', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { records: [], total: 0, current: 1, pageSize: 500 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        form: { pid: 'task-1' },
        state: { reasonFilter: '' },
      } as any),
    );
    manager.register('standardLines', {
      type: 'api',
      endpoint: '/api/dynamic/bom_standard_line_pcba/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
      params: {
        pageNum: 1,
        pageSize: 500,
        bom_std_task_id: '${form.pid}',
        bom_std_reason_code: '${state.reasonFilter}',
        sortField: 'bom_std_row_no',
        sortOrder: 'asc',
      },
    });

    await manager.fetch('standardLines');

    const params = mockedFetchResult.mock.calls.at(-1)?.[1]?.params as Record<string, any>;
    expect(params).toMatchObject({
      pageNum: 1,
      pageSize: 500,
      sortField: 'bom_std_row_no',
      sortOrder: 'asc',
    });
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'bom_std_task_id', operator: 'EQ', value: 'task-1' },
    ]);
    expect(params).not.toHaveProperty('bom_std_task_id');
    expect(params).not.toHaveProperty('bom_std_reason_code');
  });

  it('skips blank optional filter-array entries after expression evaluation', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { records: [], total: 0, current: 1, pageSize: 500 },
    } as any);

    const manager = new DataSourceManager(
      createExpressionContext({
        form: { pid: 'task-1' },
        state: { reasonFilterCodes: [] },
      } as any),
    );
    manager.register('standardLines', {
      type: 'api',
      endpoint: '/api/dynamic/bom_standard_line_pcba/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
      params: {
        pageNum: 1,
        pageSize: 500,
        filters: [
          { fieldName: 'bom_std_task_id', operator: 'EQ', value: '${form.pid}' },
          {
            fieldName: 'bom_std_reason_code',
            operator: 'IN',
            value: '${state.reasonFilterCodes}',
          },
        ],
      },
    });

    await manager.fetch('standardLines');

    const params = mockedFetchResult.mock.calls.at(-1)?.[1]?.params as Record<string, any>;
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'bom_std_task_id', operator: 'EQ', value: 'task-1' },
    ]);
  });

  it('resolves dependent params from previously loaded data source rows', async () => {
    mockedFetchResult.mockResolvedValueOnce({
      code: '0',
      data: { records: [{ pid: 'url-1' }], total: 1, current: 1, pageSize: 100 },
    } as any);

    const manager = new DataSourceManager(createExpressionContext({} as any));
    manager.register('jobSummary', {
      type: 'api',
      endpoint: '/api/dynamic/cr_crawl_job/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: false,
      params: {
        filters: [{ fieldName: 'pid', operator: 'EQ', value: '${form.pid}' }],
      },
    });
    manager.register('runUrls', {
      type: 'api',
      endpoint: '/api/dynamic/cr_crawl_url/list',
      method: 'get',
      adaptor: 'table',
      params: {
        pageNum: 1,
        pageSize: 100,
        filters: [{ fieldName: 'cr_cu_job_id', operator: 'EQ', value: '${data.jobSummary.id}' }],
      },
      dependOn: ['data.jobSummary.id'],
    });

    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mockedFetchResult).not.toHaveBeenCalled();

    manager.setData('jobSummary', {
      records: [{ id: 42, pid: 'job-1', cr_cj_name: 'Current job' }],
      total: 1,
      current: 1,
      pageSize: 1,
    });

    await waitFor(() => {
      expect(mockedFetchResult).toHaveBeenCalledWith(
        '/api/dynamic/cr_crawl_url/list',
        expect.objectContaining({ method: 'get' }),
      );
    });
    const params = mockedFetchResult.mock.calls.at(-1)?.[1]?.params as Record<string, any>;
    expect(JSON.parse(params.filters)).toEqual([
      { fieldName: 'cr_cu_job_id', operator: 'EQ', value: 42 },
    ]);
  });
});

// Regression for the MES-M10 rework / WMS shipment workbenches: queue data
// sources declaring `dependOn` on quick-filter state keys (initially undefined)
// never fired their first request, so the table stayed empty until the user
// clicked a filter. dependOn must gate re-fetches, not the initial fetch —
// undefined filter leaves are dropped downstream (removeBlankFilters /
// normalizeApiParams), so the first load is safe.
describe('dependOn gates re-fetches, not the initial fetch', () => {
  beforeEach(() => {
    mockedFetchResult.mockReset();
    mockedFetchResult.mockResolvedValue({
      code: '0',
      data: { records: [], total: 0, current: 1, pageSize: 50 },
    } as any);
  });

  const bindScope = (manager: DataSourceManager, scopeId: string) => {
    const stateManager = new ScopedStateManager({
      locale: 'zh-CN',
      theme: 'light',
      user: undefined,
      tenant: undefined,
      t: (key: string) => key,
    } as any);
    stateManager.createScope(scopeId, { state: {} });
    manager.bindStateManager(stateManager, scopeId);
    return stateManager;
  };

  it('fetches once at register for a pagination dependOn source with undefined dependency keys, then refetches when a dependency is set', async () => {
    const manager = new DataSourceManager(createExpressionContext({ state: {} } as any));
    const stateManager = bindScope(manager, 'scope-rework');

    manager.register('reworkQueue', {
      type: 'api',
      method: 'get',
      endpoint: '/api/dynamic/mfg_rework_order_pcba_execution_list/list',
      adaptor: 'table',
      pagination: true,
      params: {
        pageNum: 1,
        pageSize: 50,
        sortField: 'mfg_rwo_priority',
        filters: [
          { fieldName: 'mfg_rwo_status', operator: 'IN', value: '${state.statusFilter}' },
          { fieldName: 'mfg_rwo_priority', operator: 'IN', value: '${state.priorityFilter}' },
        ],
        keyword: '${state.searchKeyword}',
      },
      dependOn: ['state.statusFilter', 'state.priorityFilter', 'state.searchKeyword'],
    });

    await waitFor(() => expect(mockedFetchResult).toHaveBeenCalledTimes(1));
    expect(mockedFetchResult.mock.calls[0][0]).toBe(
      '/api/dynamic/mfg_rework_order_pcba_execution_list/list',
    );
    const initialParams = mockedFetchResult.mock.calls[0][1]?.params as Record<string, any>;
    // Undefined filter leaves are dropped by removeBlankFilters + normalizeApiParams:
    // the first load goes out with paging controls only, no filter entries.
    expect(initialParams.pageNum).toBe(1);
    expect(initialParams.pageSize).toBe(50);
    expect(initialParams.filters).toBeUndefined();
    expect(initialParams.keyword).toBeUndefined();

    stateManager.updateState('scope-rework', 'statusFilter', ['open']);

    await waitFor(() => expect(mockedFetchResult).toHaveBeenCalledTimes(2));
    const refetchParams = mockedFetchResult.mock.calls[1][1]?.params as Record<string, any>;
    expect(JSON.parse(refetchParams.filters)).toEqual([
      { fieldName: 'mfg_rwo_status', operator: 'IN', value: ['open'] },
    ]);

    manager.clear();
  });

  it('fetches once at register for a plain dependOn source, then refetches once a dependency is set', async () => {
    const manager = new DataSourceManager(createExpressionContext({ state: {} } as any));
    const stateManager = bindScope(manager, 'scope-faq');

    manager.register('faqList', {
      type: 'api',
      method: 'get',
      endpoint: '/api/dynamic/faq_candidate_list/list',
      adaptor: 'table',
      dependOn: ['state.statusFilter', 'state.searchKeyword'],
    });

    await waitFor(() => expect(mockedFetchResult).toHaveBeenCalledTimes(1));

    stateManager.updateState('scope-faq', 'statusFilter', 'pending');

    await waitFor(() => expect(mockedFetchResult).toHaveBeenCalledTimes(2));

    manager.clear();
  });

  it('still defers the initial fetch while a dependency parent object is unresolved', async () => {
    const manager = new DataSourceManager(createExpressionContext({ state: {} } as any));

    manager.register('selectedRework', {
      type: 'api',
      method: 'get',
      endpoint: '/api/dynamic/mfg_rework_order_pcba_execution_list/list',
      adaptor: 'table',
      params: {
        pageNum: 1,
        pageSize: 1,
        filters: [
          { fieldName: 'mfg_rwo_code', operator: 'EQ', value: '${state.selectedOrder.mfg_rwo_code}' },
        ],
      },
      dependOn: ['state.selectedOrder.mfg_rwo_code'],
    });

    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(mockedFetchResult).not.toHaveBeenCalled();

    manager.clear();
  });
});
