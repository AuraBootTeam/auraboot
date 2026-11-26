import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DashboardDesigner } from '../DashboardDesigner';

const mocks = vi.hoisted(() => ({
  hasPermission: vi.fn(), load: vi.fn(), create: vi.fn(), save: vi.fn(), reset: vi.fn(),
  updateMeta: vi.fn(), dashboard: { pid: 'dashboard-1', title: 'Dashboard', scope: 'personal' },
}));
vi.mock('~/contexts/AuthContext', () => ({
  usePermissions: () => ({ hasPermission: mocks.hasPermission }),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'zh-CN',
    t: (key: string, _params: unknown, fallback?: string) =>
      key === 'page.crm_dashboard.title' ? 'CRM 仪表盘' : fallback ?? key,
  }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToast: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn(), showWarningToast: vi.fn(), showInfoToast: vi.fn() }),
}));
vi.mock('~/hooks/useHydrated', () => ({ useHydrated: () => true }));
vi.mock('~/shared/services/teamService', () => ({ fetchCurrentUserTeams: async () => [] }));
vi.mock('~/shared/versioning', () => ({
  useVersioning: () => ({ versions: [], refreshVersions: vi.fn() }),
  VersionHistoryPanel: () => null, dashboardVersionService: {},
}));
vi.mock('../widgets/widgetRegistry', () => ({ widgetRegistry: {} }));
vi.mock('../utils/createWidgetDraft', () => ({ createWidgetDraft: vi.fn() }));
vi.mock('../components', () => ({
  DesignerToolbar: ({ displayTitle, onSettings, onSave }: {
    displayTitle: string;
    onSettings: () => void;
    onSave: () => void;
  }) => (
    <>
      <h1>{displayTitle}</h1>
      <button onClick={onSettings}>Dashboard settings</button>
      <button onClick={onSave}>Save dashboard</button>
    </>
  ),
  WidgetPalette: () => <div>Palette content</div>, DesignerCanvas: () => null,
  WidgetPropertyPanel: () => null, BigScreenMode: () => null,
}));
vi.mock('../store/useDashboardStore', () => ({
  useDashboardStore: () => ({
    dashboard: mocks.dashboard,
    isDirty: true, isLoading: false, isSaving: false,
    loadDashboard: mocks.load, createDashboard: mocks.create, saveDashboard: mocks.save,
    reset: mocks.reset, updateDashboardMeta: mocks.updateMeta, validate: () => ({ valid: true, errors: [] }),
  }),
}));

describe('DashboardDesigner write permission boundary', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    mocks.dashboard.title = 'Dashboard';
    mocks.hasPermission.mockImplementation(code => code === 'dashboard.update');
    mocks.save.mockResolvedValue(undefined);
  });
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it('rejects a read-only direct entry before loading or creating editor state', async () => {
    mocks.hasPermission.mockImplementation(code => code === 'dashboard.read');
    render(<DashboardDesigner dashboardId="dashboard-1" />);
    expect(screen.getByRole('heading', { name: '无权编辑仪表盘' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Save dashboard' })).not.toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(31_000); });
    expect(mocks.load).not.toHaveBeenCalled();
    expect(mocks.create).not.toHaveBeenCalled();
    expect(mocks.save).not.toHaveBeenCalled();
  });

  it('keeps tool panes closed when the actual workspace cannot fit both panes and metric cards', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
    vi.stubGlobal('ResizeObserver', class {
      constructor(private callback: ResizeObserverCallback) {}
      observe() { this.callback([{ contentRect: { width: 1024 } } as ResizeObserverEntry], this as unknown as ResizeObserver); }
      disconnect() {}
    });
    render(<DashboardDesigner dashboardId="dashboard-1" />);
    expect(screen.getByTestId('dashboard-designer-workspace')).toHaveAttribute('data-layout', 'compact');
    expect(screen.getByTestId('dashboard-palette-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('dashboard-inspector-shell')).toHaveAttribute('data-open', 'false');
  });

  it('retains the existing editor and auto-save for a permitted writer', async () => {
    render(<DashboardDesigner dashboardId="dashboard-1" />);
    expect(mocks.load).toHaveBeenCalledWith('dashboard-1');
    expect(screen.getByRole('button', { name: 'Save dashboard' })).toBeVisible();
    await act(async () => { await vi.advanceTimersByTimeAsync(31_000); });
    expect(mocks.save).toHaveBeenCalledTimes(1);
  });

  it('displays the translated title and preserves its key until the title is renamed', () => {
    const storedTitle = '$i18n:page.crm_dashboard.title';
    mocks.dashboard.title = storedTitle;
    render(<DashboardDesigner dashboardId="dashboard-1" />);
    expect(screen.getByRole('heading', { name: 'CRM 仪表盘' })).toBeVisible();

    const settings = screen.getByRole('button', { name: 'Dashboard settings' });
    fireEvent.click(settings);
    expect(screen.getByRole('textbox', { name: /common.title/ })).toHaveValue('CRM 仪表盘');
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }));
    expect(mocks.updateMeta).toHaveBeenLastCalledWith(expect.objectContaining({ title: storedTitle }));

    fireEvent.click(settings);
    fireEvent.change(screen.getByRole('textbox', { name: /common.title/ }), {
      target: { value: '销售仪表盘' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }));
    expect(mocks.updateMeta).toHaveBeenLastCalledWith(expect.objectContaining({ title: '销售仪表盘' }));
  });

  it('unmounts editor state and cancels pending auto-save when write access is revoked', async () => {
    const view = render(<DashboardDesigner dashboardId="dashboard-1" />);
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
    mocks.hasPermission.mockReturnValue(false);
    view.rerender(<DashboardDesigner dashboardId="dashboard-1" />);
    expect(screen.queryByRole('button', { name: 'Save dashboard' })).not.toBeInTheDocument();
    expect(mocks.reset).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(31_000); });
    expect(mocks.save).not.toHaveBeenCalled();
  });
});
