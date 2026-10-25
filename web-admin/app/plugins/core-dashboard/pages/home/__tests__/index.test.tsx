import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { renderToString } from 'react-dom/server';
import { hydrateRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import WorkbenchPage, { loader } from '../index';
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';

const loaderData = vi.hoisted(() => ({ current: { dashboard: { id: 'wb', widgets: [{ id: 'w1' }] } as unknown, loadError: null as string | null, accessDenied: false } }));

vi.mock('~/utils/i18n', () => ({ useSmartText: () => (value: { zh: string; en: string }) => value.zh }));

vi.mock('react-router', async importOriginal => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useLoaderData: () => loaderData.current,
}));

vi.mock('~/plugins/core-dashboard/services/dashboardService', () => ({
  normalizeDashboard: (raw: unknown) => raw,
  dashboardService: {
    getWorkbench: vi.fn(async () => ({ id: 'wb', widgets: [{ id: 'w1' }] })),
  },
}));

const dashboardViewerSpy = vi.fn();
vi.mock('~/plugins/core-dashboard/components/DashboardViewer', () => ({
  DashboardViewer: (props: Record<string, unknown>) => {
    dashboardViewerSpy(props);
    return <div data-testid="dashboard-viewer" />;
  },
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: 'zh-CN' }),
}));

const toastSpy = { showSuccessToast: vi.fn(), showErrorToast: vi.fn() };
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => toastSpy,
}));

const getMock = vi.fn(async (_url: string, _params?: Record<string, string>) => ({
  code: '0',
  data: { openTasks: 3 },
}));
vi.mock('~/shared/services/http-client', () => ({
  get: (url: string, params?: Record<string, string>) => getMock(url, params),
  fetchResult: vi.fn(async () => ({ code: '0', data: { id: 'wb', widgets: [{ id: 'w1' }] } })),
}));

const listInboxMock = vi.fn(async (_params?: { pageSize?: number }) => ({
  records: [
    { itemType: 'approval', title: '审批A', subtitle: '', priority: 'high', status: 'pending' },
  ],
  total: 1,
}));
vi.mock('~/shared/services/inboxService', () => ({
  listInboxItems: (params?: { pageSize?: number }) => listInboxMock(params),
}));

const recentVisitsMock = vi.fn(async (_limit?: number) => [
  { title: '商品列表', path: '/prod/product', visitedAt: '2026-09-04T00:00:00Z' },
]);
vi.mock('~/plugins/core-dashboard/widgets/workbench/useRecentVisits', () => ({
  fetchRecentVisits: (limit?: number) => recentVisitsMock(limit),
}));

const jsonToSheetSpy = vi.fn((rows: unknown[]) => ({ rows }));
const appendSheetSpy = vi.fn((_wb: unknown, _ws: unknown, _name: string) => {});
const writeSpy = vi.fn(() => new Uint8Array([1, 2, 3]));
vi.mock('xlsx', () => ({
  utils: {
    book_new: () => ({}),
    json_to_sheet: (rows: unknown[]) => jsonToSheetSpy(rows),
    book_append_sheet: (wb: unknown, ws: unknown, name: string) => appendSheetSpy(wb, ws, name),
  },
  write: (_wb: unknown, _opts: unknown) => writeSpy(),
}));

function renderPage() {
  return render(
    <MemoryRouter>
      <WorkbenchPage />
    </MemoryRouter>,
  );
}

describe('WorkbenchPage header', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    loaderData.current = { dashboard: { id: 'wb', widgets: [{ id: 'w1' }] }, loadError: null, accessDenied: false };
  });

  it('renders the loader workbench immediately without a second browser request', async () => {
    renderPage();
    expect(screen.getByTestId('dashboard-viewer')).toBeInTheDocument();
    await act(async () => {});
    expect(dashboardService.getWorkbench).not.toHaveBeenCalled();
  });

  it('loads workbench configuration with the incoming SSR request', async () => {
    const { fetchResult } = await import('~/shared/services/http-client');
    const request = new Request('http://localhost/home');
    const result = await loader({ request } as any);
    expect(fetchResult).toHaveBeenCalledWith('/api/dashboards/workbench', {}, request);
    expect(result.dashboard?.widgets).toHaveLength(1);
    expect(result.loadError).toBeNull();
  });

  it('renders a page title and dated subline', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'workbench.title' })).toBeInTheDocument();
    expect(screen.getByTestId('workbench-subline').textContent).toMatch(/\d/);
  });

  it('hydrates without a date mismatch when server and browser calendar days differ', async () => {
    const dateLabel = vi.spyOn(Date.prototype, 'toLocaleDateString').mockReturnValue('服务端10月11日');
    const page = <MemoryRouter><WorkbenchPage /></MemoryRouter>;
    const host = document.createElement('div');
    host.innerHTML = renderToString(page);
    const errors: unknown[] = [];
    let root: ReturnType<typeof hydrateRoot> | undefined;
    try {
      expect(host.querySelector('[data-testid="workbench-subline"]')?.textContent).not.toContain('服务端10月11日');
      dateLabel.mockReturnValue('客户端10月10日');
      await act(async () => { root = hydrateRoot(host, page, { onRecoverableError: error => errors.push(error) }); });
      await waitFor(() => expect(host.querySelector('[data-testid="workbench-subline"]')?.textContent).toContain('客户端10月10日'));
      expect(errors).toEqual([]);
    } finally {
      if (root) await act(async () => root?.unmount());
      dateLabel.mockRestore();
    }
  });

  it('renders Open-in-Dashboard / Export / New actions', async () => {
    renderPage();
    await screen.findByRole('heading', { name: 'workbench.title' });
    const openLink = screen.getByTestId('workbench-open-in-dashboard');
    expect(openLink).toBeInTheDocument();
    expect(openLink.getAttribute('href')).toBe('/home/settings');
    expect(screen.getByTestId('workbench-export')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /workbench\.new/ })).toBeInTheDocument();
  });

  it('passes hideWidgetActions to DashboardViewer so per-widget kebab is suppressed', async () => {
    dashboardViewerSpy.mockClear();
    renderPage();
    await screen.findByTestId('dashboard-viewer');
    const lastCall = dashboardViewerSpy.mock.calls.at(-1)?.[0];
    expect(lastCall?.hideWidgetActions).toBe(true);
  });

  it('exports a workbench .xlsx download with real widget data', async () => {
    const createObjectURL = vi.fn(() => 'blob:mock');
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const created: HTMLAnchorElement[] = [];
    const origCreate = document.createElement.bind(document);
    const createSpy = vi
      .spyOn(document, 'createElement')
      .mockImplementation(((tag: string) => {
        const el = origCreate(tag);
        if (tag === 'a') created.push(el as HTMLAnchorElement);
        return el;
      }) as typeof document.createElement);

    renderPage();
    await screen.findByTestId('dashboard-viewer');
    fireEvent.click(screen.getByTestId('workbench-export'));

    await waitFor(() => {
      expect(createObjectURL).toHaveBeenCalled();
    });

    // sheets: widgets (from dashboard) + stats + inbox + recent
    const sheetNames = appendSheetSpy.mock.calls.map((c) => c[2]);
    expect(sheetNames).toEqual(['widgets', 'stats', 'inbox', 'recent']);
    // widget inventory rows come from the loaded dashboard
    const widgetRows = jsonToSheetSpy.mock.calls[0]?.[0] as Array<Record<string, unknown>>;
    expect(widgetRows).toEqual([{ id: 'w1', type: undefined, title: 'w1' }]);
    // stats endpoint was queried and its rows added
    expect(getMock.mock.calls[0]?.[0]).toBe('/api/workbench/stats');
    expect(writeSpy).toHaveBeenCalled();
    // a real download anchor was produced with an .xlsx filename
    const anchor = created.find((el) => /\.xlsx$/.test(el.download));
    expect(anchor, 'xlsx download anchor').toBeTruthy();
    expect(clickSpy).toHaveBeenCalled();
    expect(revokeObjectURL).toHaveBeenCalled();
    expect(toastSpy.showSuccessToast).toHaveBeenCalled();

    createSpy.mockRestore();
    clickSpy.mockRestore();
  });
});


it('distinguishes a forbidden workbench from a service failure', async () => {
  const { fetchResult } = await import('~/shared/services/http-client');
  vi.mocked(fetchResult).mockResolvedValueOnce({ code: '40300', message: 'Forbidden', httpStatus: 403 } as never);
  const result = await loader({ request: new Request('http://localhost/home') } as never);
  expect(result).toMatchObject({ dashboard: null, accessDenied: true });
});

it('keeps server failures visible and retryable', async () => {
  const { fetchResult } = await import('~/shared/services/http-client');
  vi.mocked(fetchResult).mockResolvedValueOnce({ code: '500', message: 'Unavailable', httpStatus: 500 } as never);
  const result = await loader({ request: new Request('http://localhost/home') } as never);
  expect(result).toMatchObject({ dashboard: null, accessDenied: false, loadError: 'Unavailable' });
});

it('guides a viewer to permitted navigation without exposing workbench actions', async () => {
  loaderData.current = { dashboard: null, loadError: 'Forbidden', accessDenied: true };
  renderPage();
  expect(screen.getByText('当前账号未开通个人工作台')).toBeInTheDocument();
  expect(screen.getByText('请从左侧菜单访问已授权的业务功能。如需个人工作台，请联系管理员。')).toBeInTheDocument();
  expect(screen.queryByTestId('workbench-open-in-dashboard')).not.toBeInTheDocument();
  expect(screen.queryByTestId('workbench-export')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /workbench\.new/ })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'common.retry' })).not.toBeInTheDocument();
});
