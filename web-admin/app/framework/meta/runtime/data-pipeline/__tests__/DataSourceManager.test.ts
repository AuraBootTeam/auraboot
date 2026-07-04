import { beforeEach, describe, expect, it, vi } from 'vitest';
import { waitFor } from '@testing-library/react';
import { fetchResult } from '~/shared/services/http-client';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { ScopedStateManager } from '~/framework/meta/runtime/state/scoped-state';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

const mockedFetchResult = vi.mocked(fetchResult);

describe('DataSourceManager', () => {
  beforeEach(() => {
    mockedFetchResult.mockReset();
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
