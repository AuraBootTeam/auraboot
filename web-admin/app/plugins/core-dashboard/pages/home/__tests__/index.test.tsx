import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { renderToString } from 'react-dom/server';
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
  useI18n: () => ({ t: (k: string) => k }),
}));

function renderPage() {
  return render(
    <MemoryRouter>
      <WorkbenchPage />
    </MemoryRouter>,
  );
}

describe('WorkbenchPage header', () => {
  it('keeps the server-rendered subline deterministic for hydration', () => {
    const html = renderToString(
      <MemoryRouter>
        <WorkbenchPage />
      </MemoryRouter>,
    );

    expect(html).toContain('data-testid="workbench-subline"');
    expect(html).toContain('>workbench.subline</div>');
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
    expect(screen.getByRole('button', { name: /workbench\.export/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /workbench\.new/ })).toBeInTheDocument();
  });

  it('passes hideWidgetActions to DashboardViewer so per-widget kebab is suppressed', async () => {
    dashboardViewerSpy.mockClear();
    renderPage();
    await screen.findByTestId('dashboard-viewer');
    const lastCall = dashboardViewerSpy.mock.calls.at(-1)?.[0];
    expect(lastCall?.hideWidgetActions).toBe(true);
  });
});
