import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchResult } from '~/shared/services/http-client';
import { SchemaRuntime } from '../../../runtime/schema-runtime';
import { DataSourceManager } from '../../../runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '../../../runtime/expression/context';
import type { AuthoritativeSourceConfig, BlockConfig } from '../../../schemas/types';
import { StatCardBlockRenderer } from '../StatCardBlockRenderer';
import { ChartBlockRenderer } from '../ChartBlockRenderer';
import { buildAuthoritativeSeriesOption } from '../AuthoritativeSourceBlock';
import { readSourceIdentity, readSourceView } from '../sourceStateModel';

vi.mock('~/shared/services/http-client', () => ({ fetchResult: vi.fn() }));
vi.mock('~/framework/smart/components/charts/EChartsHost', () => ({
  default: ({ option }: any) => (
    <div data-testid="strict-chart">{JSON.stringify(option.series)}</div>
  ),
}));
const fetch = vi.mocked(fetchResult);
const owners: Array<{ runtime: SchemaRuntime; manager: DataSourceManager }> = [];
const config: AuthoritativeSourceConfig = {
  mode: 'authoritative',
  identity: [
    { responsePath: 'source', contextPath: 'state.source' },
    { responsePath: 'version', contextPath: 'state.version' },
  ],
  scopePaths: ['state.range'],
  valuePath: 'value',
  unitPath: 'unit',
  states: [
    {
      path: 'availability',
      label: '时效',
      values: {
        FRESH: { label: '本次读取有效', tone: 'success' },
        STALE: { label: '已过期，非当前值', tone: 'warning' },
        MISSING: { label: '缺测', tone: 'warning', hideValue: true },
      },
    },
  ],
  metadata: [
    { path: 'sourceName', label: '来源' },
    { path: 'at', label: '样本时间', format: 'datetime' },
  ],
};
// Generic hermetic input, not a captured K/domain/native response.
const payload = (extra: Record<string, unknown> = {}) => ({
  source: 'A',
  version: 'v1',
  value: 7.5,
  unit: 'm/s',
  availability: 'FRESH',
  sourceName: '受控来源甲',
  at: '2026-10-27T00:00:00Z',
  ...extra,
});
const completed = (value: unknown) =>
  ({ code: '0', data: { phaseReached: 'completed', data: value } }) as any;
function owner(state: Record<string, unknown> = {}) {
  const globalState = {
    user: { id: 'user-1', name: 'Observer', email: '', roles: ['observer'], permissions: ['read'] },
    tenant: { id: 'tenant-1', name: 'Test' },
    locale: 'zh-CN',
    theme: 'light',
  };
  const manager = new DataSourceManager(createExpressionContext({ global: globalState }));
  const runtime = new SchemaRuntime({
    schema: { id: 'strict-source', blocks: [] } as any,
    globalState,
    initialContext: { state: { source: 'A', version: 'v1', range: 'window-1', ...state } },
    dataSourceManager: manager,
    skipDataSourceRegistration: true,
  });
  manager.register('reading', {
    type: 'api',
    method: 'post',
    autoFetch: false,
    adaptor: 'commandObject',
    endpoint: '/api/meta/commands/execute/example:read_snapshot',
    params: {
      targetRecordPid: '${state.source}',
      payload: { version: '${state.version}', range: '${state.range}' },
    },
  });
  owners.push({ runtime, manager });
  return { runtime, manager };
}
const block = (binding: AuthoritativeSourceConfig = config): BlockConfig => ({
  id: 'value',
  blockType: 'stat-card',
  title: '实时读数',
  dataSource: 'reading',
  statCard: { value: 999 },
  props: { sourceState: binding },
});
function deferred() {
  let resolve!: (value: any) => void;
  const promise = new Promise<any>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
beforeEach(() => {
  fetch.mockReset();
  vi.spyOn(console, 'error').mockImplementation(() => {});
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  for (const { runtime, manager } of owners.splice(0)) {
    runtime.destroy();
    manager.clear();
  }
  vi.restoreAllMocks();
});

describe('Authoritative named-source blocks', () => {
  it('renders one actual DMS result with its own value, state and metadata', async () => {
    fetch.mockResolvedValueOnce(completed(payload()));
    const { runtime } = owner();
    render(<StatCardBlockRenderer block={block()} runtime={runtime} />);
    expect(await screen.findByTestId('authoritative-value')).toHaveTextContent('7.5');
    expect(screen.getByText('受控来源甲')).toBeVisible();
    expect(screen.getByText('本次读取有效')).toBeVisible();
    expect(screen.queryByText('999')).not.toBeInTheDocument();
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it('does not fetch an unresolved selection or show an inline value', () => {
    const { runtime } = owner({ source: undefined });
    render(<StatCardBlockRenderer block={block()} runtime={runtime} />);
    expect(screen.getByTestId('authoritative-source')).toHaveAttribute('data-phase', 'pending');
    expect(screen.queryByText('999')).not.toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });
  it('refuses the inline value on an actual denied named-source read', async () => {
    fetch.mockResolvedValueOnce({ code: '403', message: 'Denied', data: null } as any);
    const { runtime } = owner();
    render(<StatCardBlockRenderer block={block()} runtime={runtime} />);
    expect(screen.queryByText('999')).not.toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('无权读取');
    expect(screen.queryByTestId('authoritative-value')).not.toBeInTheDocument();
  });
  it('hides the previous value and metadata during refresh and after a transport failure', async () => {
    const next = deferred();
    fetch.mockResolvedValueOnce(completed(payload())).mockReturnValueOnce(next.promise);
    const { runtime } = owner();
    render(<StatCardBlockRenderer block={block()} runtime={runtime} />);
    await screen.findByTestId('authoritative-value');
    fireEvent.click(screen.getByRole('button', { name: '重新读取' }));
    await waitFor(() =>
      expect(screen.getByTestId('authoritative-source')).toHaveAttribute('data-phase', 'loading'),
    );
    expect(screen.queryByText('7.5')).not.toBeInTheDocument();
    expect(screen.queryByText('受控来源甲')).not.toBeInTheDocument();
    await act(async () => {
      next.resolve({ code: '503', message: 'Unavailable', data: null });
    });
    expect(await screen.findByRole('alert')).toHaveTextContent('数据读取失败');
    expect(screen.queryByText('7.5')).not.toBeInTheDocument();
  });
  it('isolates a late real-DMS response after a record selection changes', async () => {
    const first = deferred();
    fetch
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(
        completed(payload({ source: 'B', value: 22, sourceName: '受控来源乙' })),
      );
    const { runtime } = owner();
    render(<StatCardBlockRenderer block={block()} runtime={runtime} />);
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    act(() => runtime.getStateManager().updateState(runtime.getScopeId(), 'source', 'B'));
    expect(await screen.findByTestId('authoritative-value')).toHaveTextContent('22');
    await act(async () => {
      first.resolve(completed(payload()));
    });
    expect(screen.getByTestId('authoritative-value')).toHaveTextContent('22');
    expect(screen.queryByText('受控来源甲')).not.toBeInTheDocument();
  });
  it('rereads a new range and refuses a mismatched source version', async () => {
    fetch
      .mockResolvedValueOnce(completed(payload()))
      .mockResolvedValueOnce(completed(payload()))
      .mockResolvedValueOnce(completed(payload()));
    const { runtime } = owner();
    render(<StatCardBlockRenderer block={block()} runtime={runtime} />);
    await screen.findByTestId('authoritative-value');
    act(() => runtime.getStateManager().updateState(runtime.getScopeId(), 'range', 'window-2'));
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
    expect(fetch.mock.calls[1][1]).toMatchObject({ params: { payload: { range: 'window-2' } } });
    await waitFor(() =>
      expect(screen.getByTestId('authoritative-source')).toHaveAttribute('data-phase', 'ready'),
    );
    act(() => runtime.getStateManager().updateState(runtime.getScopeId(), 'version', 'v2'));
    expect(await screen.findByRole('alert')).toHaveTextContent('返回数据与当前选择不一致');
    expect(screen.queryByTestId('authoritative-value')).not.toBeInTheDocument();
  });
  it('does not relabel a backoff-cached result as a fresh successful read', async () => {
    fetch
      .mockResolvedValueOnce(completed(payload()))
      .mockResolvedValueOnce({ code: '429', message: 'Too Many Requests', data: null } as any);
    const { runtime } = owner();
    render(<StatCardBlockRenderer block={block()} runtime={runtime} />);
    await screen.findByTestId('authoritative-value');
    fireEvent.click(screen.getByRole('button', { name: '重新读取' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('尚未取得本次读取的新数据');
    expect(screen.queryByTestId('authoritative-value')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '重新读取' }));
    await waitFor(() => expect(screen.getByRole('alert')).toBeVisible());
    expect(fetch).toHaveBeenCalledTimes(2);
  });
  it.each(['STALE', 'MISSING'])(
    'renders explicit %s business state without a fabricated normal value',
    async (availability) => {
      fetch.mockResolvedValueOnce(
        completed(
          payload({ availability, ...(availability === 'MISSING' ? { value: null } : {}) }),
        ),
      );
      const { runtime } = owner();
      render(<StatCardBlockRenderer block={block()} runtime={runtime} />);
      const value = await screen.findByTestId('authoritative-value');
      expect(value).toHaveTextContent(availability === 'MISSING' ? '缺测' : '7.5');
      expect(screen.queryByText('999')).not.toBeInTheDocument();
    },
  );
  it('renders unconfigured risk without reading a missing level as zero or normal', async () => {
    const risk: AuthoritativeSourceConfig = {
      mode: 'authoritative',
      identity: config.identity,
      valuePath: 'level',
      states: [
        {
          path: 'status',
          label: '风险',
          values: {
            UNCONFIGURED: { label: '未评估：阈值未配置', tone: 'warning', hideValue: true },
          },
        },
      ],
    };
    fetch.mockResolvedValueOnce(completed({ source: 'A', version: 'v1', status: 'UNCONFIGURED' }));
    const { runtime } = owner();
    render(<StatCardBlockRenderer block={block(risk)} runtime={runtime} />);
    expect(await screen.findByTestId('authoritative-value')).toHaveTextContent(
      '未评估：阈值未配置',
    );
    expect(screen.queryByText('正常')).not.toBeInTheDocument();
  });
  it('shows suspect quality explicitly instead of a normal numeric value', async () => {
    const binding: AuthoritativeSourceConfig = {
      ...config,
      states: [
        ...config.states!,
        {
          path: 'quality',
          label: '质量',
          values: {
            GOOD: { label: '已验证' },
            SUSPECT: { label: '质量存疑', tone: 'warning', hideValue: true },
          },
        },
      ],
    };
    fetch.mockResolvedValueOnce(completed(payload({ quality: 'SUSPECT' })));
    const { runtime } = owner();
    render(<StatCardBlockRenderer block={block(binding)} runtime={runtime} />);
    expect(await screen.findByTestId('authoritative-value')).toHaveTextContent('质量存疑');
    expect(screen.queryByText('7.5')).not.toBeInTheDocument();
  });
  it('clears the old frame when the owning authenticated runtime is replaced', async () => {
    const second = deferred();
    fetch.mockResolvedValueOnce(completed(payload())).mockReturnValueOnce(second.promise);
    const first = owner();
    const shown = render(<StatCardBlockRenderer block={block()} runtime={first.runtime} />);
    await screen.findByTestId('authoritative-value');
    const next = owner();
    shown.rerender(<StatCardBlockRenderer block={block()} runtime={next.runtime} />);
    expect(screen.queryByText('7.5')).not.toBeInTheDocument();
    await act(async () => {
      second.resolve(completed(payload({ value: 22 })));
    });
    expect(await screen.findByTestId('authoritative-value')).toHaveTextContent('22');
  });
  it('uses the existing chart host with exact null gaps and no second query', async () => {
    const binding: AuthoritativeSourceConfig = {
      ...config,
      series: {
        pointsPath: 'points',
        timePath: 'at',
        valuePath: 'value',
        kind: 'line',
        identity: [{ pointPath: 'source', responsePath: 'source' }],
      },
    };
    const points = [2, null, 4].map((value, i) => ({
      source: 'A',
      at: `2026-10-27T00:0${i}:00Z`,
      value,
    }));
    fetch.mockResolvedValueOnce(completed(payload({ points })));
    const { runtime } = owner();
    render(
      <ChartBlockRenderer block={{ ...block(binding), blockType: 'chart' }} runtime={runtime} />,
    );
    const chart = await screen.findByTestId('strict-chart');
    const series = JSON.parse(chart.textContent!)[0];
    expect(series.data.map((item: any) => item[1])).toEqual([2, null, 4]);
    expect(series.connectNulls).toBe(false);
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it('keeps legacy inline cards opt-in compatible', () => {
    const { runtime } = owner();
    render(
      <StatCardBlockRenderer
        block={{ id: 'legacy', blockType: 'stat-card', statCard: { value: 999 } }}
        runtime={runtime}
      />,
    );
    expect(screen.getByTestId('stat-card-value')).toHaveTextContent('999');
    expect(screen.queryByTestId('authoritative-source')).not.toBeInTheDocument();
  });
});

describe('Authoritative point interpretation', () => {
  it('keeps circular directions as discrete points and rejects cross-source points', () => {
    const binding: AuthoritativeSourceConfig = {
      ...config,
      series: {
        pointsPath: 'points',
        timePath: 'at',
        valuePath: 'value',
        kind: 'scatter',
        domain: [0, 360],
        identity: [{ pointPath: 'source', responsePath: 'source' }],
      },
    };
    const { runtime } = owner();
    const identity = readSourceIdentity(binding, runtime.getContext())!;
    const rows = [359, 1].map((value, i) => ({
      source: 'A',
      at: `2026-10-27T00:0${i}:00Z`,
      value,
    }));
    const view = readSourceView(
      payload({ points: rows }),
      binding,
      identity,
      'zh-CN',
      (key) => key,
    );
    const option = buildAuthoritativeSeriesOption(view, binding.series!, '方向');
    expect((option.series as any)[0]).toMatchObject({
      type: 'scatter',
      data: [
        [Date.parse(rows[0].at), 359],
        [Date.parse(rows[1].at), 1],
      ],
    });
    expect(() =>
      readSourceView(
        payload({ points: [{ ...rows[0], source: 'B' }] }),
        binding,
        identity,
        'zh-CN',
        (key) => key,
      ),
    ).toThrow();
  });
  it('rejects unknown state codes instead of displaying raw codes', () => {
    const { runtime } = owner();
    const identity = readSourceIdentity(config, runtime.getContext())!;
    expect(() =>
      readSourceView(
        payload({ availability: 'UNRECOGNIZED_CODE' }),
        config,
        identity,
        'zh-CN',
        (key) => key,
      ),
    ).toThrow();
  });
  it('requires explicit known quality before accepting an omitted measurement value', () => {
    const binding: AuthoritativeSourceConfig = {
      ...config,
      series: {
        pointsPath: 'points',
        timePath: 'at',
        valuePath: 'value',
        kind: 'line',
        gapWhen: [{ path: 'quality', values: ['MISSING'], knownValues: ['VALID', 'MISSING'] }],
      },
    };
    const { runtime } = owner();
    const identity = readSourceIdentity(binding, runtime.getContext())!;
    const interpret = (quality: string, value?: number) =>
      readSourceView(
        payload({
          points: [
            { at: '2026-10-27T00:00:00Z', quality, ...(value === undefined ? {} : { value }) },
          ],
        }),
        binding,
        identity,
        'zh-CN',
        (key) => key,
      );
    expect(interpret('MISSING').points?.[0][1]).toBeNull();
    expect(() => interpret('VALID')).toThrow();
    expect(() => interpret('UNKNOWN', 7)).toThrow();
    expect(interpret('VALID', 0).points?.[0][1]).toBe(0);
  });
});
