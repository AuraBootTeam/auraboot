import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import WorkbenchPage from '../index';

vi.mock('~/plugins/core-dashboard/services/dashboardService', () => ({
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
  });

  it('renders a page title and dated subline', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'workbench.title' })).toBeInTheDocument();
    expect(screen.getByTestId('workbench-subline').textContent).toMatch(/\d/);
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
