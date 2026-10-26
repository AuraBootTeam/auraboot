import { useMemo } from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider, type LoaderFunction } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DetailModalProvider, useDetailModal } from '../DetailModalProvider';
import { resolveDetailModalTarget } from '../detailModalTarget';
import { useDetailSourceRefreshVersion } from '../DetailSourceRefreshContext';
import { ChartBlockRenderer } from '../../../blocks/ChartBlockRenderer';
import { ScopedStateManager } from '~/framework/meta/runtime/state/scoped-state';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { AggregateQueryRequest } from '~/framework/smart/types/chart';

// Real router, ChartBlockRenderer, SmartNumberCard, useChartData and chartDataService.
// Stub only the unrelated chart registry selection, detail content and HTTP boundary.
vi.mock('react-router', async (original) => original<typeof import('react-router')>());
const io = vi.hoisted(() => ({
  post: vi.fn(),
  fetch: vi.fn(),
  refresh: vi.fn(),
  counts: {} as Record<string, number>,
}));
vi.mock('~/shared/services/http-client', () => ({
  post: (...args: unknown[]) => io.post(...args),
  fetchResult: (...args: unknown[]) => io.fetch(...args),
}));
vi.mock('~/framework/smart/charts/SharedChartFactory', async () => {
  const { SmartNumberCard } = await import('~/framework/smart/components/charts/SmartNumberCard');
  return {
    getChartComponent: () => SmartNumberCard,
    getSupportedChartTypes: () => ['number-card'],
  };
});
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'en-US',
    t: (key: string, _args?: unknown, fallback?: string) => fallback ?? key,
  }),
}));
vi.mock('../../DynamicPageRenderer', () => ({
  default: () => <p>Actual detail fixture boundary</p>,
}));

const states = ['new', 'investigating', 'closed'] as const;
const chartBlocks: BlockConfig[] = states.map((status) => ({
  id: 'count-' + status,
  blockType: 'chart',
  chartType: 'number-card',
  title: status,
  chartConfig: {
    metricField: 'total',
    dataSource: {
      type: 'aggregate',
      modelCode: 'case',
      metrics: [{ field: 'id', aggregation: 'count', alias: 'total' }],
      filters: [{ field: 'status', operator: 'eq', value: status }],
    },
  },
}));
function runtime(): SchemaRuntime {
  const state = new ScopedStateManager({ locale: 'en-US', theme: 'light' });
  state.createScope('source');
  return {
    getStateManager: () => state,
    getScopeId: () => 'source',
    getDataSourceManager: () => ({ getConfig: () => undefined }),
  } as unknown as SchemaRuntime;
}
function Source() {
  const open = useDetailModal()!,
    version = useDetailSourceRefreshVersion();
  const rt = useMemo(runtime, []);
  return (
    <>
      <output data-testid="source-chart-version">{version}</output>
      <input aria-label="Source filter" />
      {chartBlocks.map((block) => (
        <section key={block.id} data-testid={block.id}>
          <ChartBlockRenderer block={block} runtime={rt} />
        </section>
      ))}
      <button onClick={() => open(resolveDetailModalTarget('/p/case/view/A'), () => io.refresh())}>
        Open A
      </button>
      <button onClick={() => open(resolveDetailModalTarget('/p/case/view/B'), () => io.refresh())}>
        Open B
      </button>
    </>
  );
}
const routers: ReturnType<typeof createMemoryRouter>[] = [];
const allowed: LoaderFunction = ({ params }) => ({
  tableName: params.pageKey,
  recordPid: params.recordPid,
  accessError: null,
  httpStatus: 200,
});
function mount() {
  const router = createMemoryRouter(
    [
      {
        path: '/source',
        element: (
          <DetailModalProvider>
            <Source />
          </DetailModalProvider>
        ),
      },
      { path: '/p/:pageKey/view/:recordPid', loader: allowed, element: <div>Normal detail</div> },
    ],
    { initialEntries: ['/source?status=new'] },
  );
  routers.push(router);
  const view = render(<RouterProvider router={router} />);
  return { router, view };
}
function deferred() {
  let resolve!: () => void, reject!: (reason: Error) => void;
  const promise = new Promise<void>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}
async function initialCounts() {
  await waitFor(() => expect(io.post).toHaveBeenCalledTimes(3));
  await waitFor(() =>
    expect(screen.getByTestId('count-new').querySelector('[title="3"]')).toBeVisible(),
  );
  await waitFor(() =>
    expect(screen.getByTestId('count-investigating').querySelector('[title="0"]')).toBeVisible(),
  );
  await waitFor(() =>
    expect(screen.getByTestId('count-closed').querySelector('[title="0"]')).toBeVisible(),
  );
  expect(screen.getByTestId('source-chart-version')).toHaveTextContent('0');
}
async function openAndClose() {
  fireEvent.click(screen.getByRole('button', { name: 'Open A' }));
  await screen.findByRole('dialog');
  // Opening a detail is not a source data invalidation.
  expect(io.post).toHaveBeenCalledTimes(3);
  fireEvent.click(screen.getByTestId('detail-modal-close'));
  await waitFor(() => expect(io.refresh).toHaveBeenCalledOnce());
}
beforeEach(() => {
  io.post.mockReset();
  io.fetch.mockReset();
  io.refresh.mockReset();
  io.refresh.mockResolvedValue(undefined);
  io.counts = { new: 3, investigating: 0, closed: 0 };
  io.post.mockImplementation(async (url: string, request: AggregateQueryRequest) => {
    if (url !== '/api/meta/chart-data') throw new Error('Unexpected chart route: ' + url);
    const status = String(request.filters?.[0]?.value);
    return {
      code: '0',
      data: {
        rows: [{ total: io.counts[status] }],
        summary: {},
        meta: { dimensions: [], metrics: ['total'] },
      },
    };
  });
});
afterEach(() => {
  routers.splice(0).forEach((router) => router.dispose());
});

describe('source modal close refreshes real inline aggregate reads', () => {
  it('awaits the source read then POSTs the same aggregate requests and displays the new counts', async () => {
    const read = deferred();
    io.refresh.mockReturnValue(read.promise);
    mount();
    await initialCounts();
    const first = io.post.mock.calls.map(([url, request]) => ({
      url,
      request: structuredClone(request),
    }));
    await openAndClose();
    expect(io.post).toHaveBeenCalledTimes(3);
    io.counts = { new: 2, investigating: 1, closed: 0 };
    await act(async () => {
      read.resolve();
    });
    await waitFor(() => expect(io.post).toHaveBeenCalledTimes(6));
    await waitFor(() =>
      expect(screen.getByTestId('count-new').querySelector('[title="2"]')).toBeVisible(),
    );
    await waitFor(() =>
      expect(screen.getByTestId('count-investigating').querySelector('[title="1"]')).toBeVisible(),
    );
    expect(screen.getByTestId('source-chart-version')).toHaveTextContent('1');
    expect(io.post.mock.calls.slice(3).map(([url, request]) => ({ url, request }))).toEqual(first);
    expect(io.fetch).not.toHaveBeenCalled();
  });
  it('refreshes charts without stealing deliberate source focus during the read', async () => {
    const read = deferred();
    io.refresh.mockReturnValue(read.promise);
    mount();
    await initialCounts();
    await openAndClose();
    const filter = screen.getByRole('textbox', { name: 'Source filter' });
    fireEvent.pointerDown(filter);
    filter.focus();
    io.counts = { new: 2, investigating: 1, closed: 0 };
    await act(async () => {
      read.resolve();
    });
    await waitFor(() => expect(io.post).toHaveBeenCalledTimes(6));
    expect(filter).toHaveFocus();
    expect(screen.getByTestId('source-chart-version')).toHaveTextContent('1');
  });
  it('does not invalidate charts when the acknowledged source read fails', async () => {
    const read = deferred();
    io.refresh.mockReturnValue(read.promise);
    mount();
    await initialCounts();
    await openAndClose();
    await act(async () => {
      read.reject(new Error('Source read failed'));
    });
    expect(screen.getByTestId('source-chart-version')).toHaveTextContent('0');
    expect(io.post).toHaveBeenCalledTimes(3);
  });
  it('ignores a late read after the source URL changes', async () => {
    const read = deferred();
    io.refresh.mockReturnValue(read.promise);
    const { router } = mount();
    await initialCounts();
    await openAndClose();
    await act(async () => {
      await router.navigate('/source?status=closed');
    });
    await act(async () => {
      read.resolve();
    });
    expect(screen.getByTestId('source-chart-version')).toHaveTextContent('0');
    expect(io.post).toHaveBeenCalledTimes(3);
  });
  it('does not let A close completion refresh charts behind a newly opened B modal', async () => {
    const read = deferred();
    io.refresh.mockReturnValue(read.promise);
    mount();
    await initialCounts();
    await openAndClose();
    fireEvent.click(screen.getByRole('button', { name: 'Open B' }));
    await screen.findByRole('dialog');
    await act(async () => {
      read.resolve();
    });
    expect(screen.getByTestId('source-chart-version')).toHaveTextContent('0');
    expect(io.post).toHaveBeenCalledTimes(3);
  });
  it('uses the original chart error path after a refresh instead of retaining an old successful count', async () => {
    mount();
    await initialCounts();
    io.post.mockRejectedValue(new Error('Chart access denied'));
    await openAndClose();
    await waitFor(() => expect(io.post).toHaveBeenCalledTimes(6));
    await waitFor(() =>
      expect(within(screen.getByTestId('count-new')).getByRole('alert')).toBeVisible(),
    );
    expect(screen.getByTestId('count-new').querySelector('[title="3"]')).not.toBeInTheDocument();
  });
});
