import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import WorkbenchPage from '../index';

vi.mock('~/plugins/core-dashboard/services/dashboardService', () => ({
  dashboardService: {
    getWorkbench: vi.fn(async () => ({ id: 'wb', widgets: [{ id: 'w1' }] })),
  },
}));

vi.mock('~/plugins/core-dashboard/components/DashboardViewer', () => ({
  DashboardViewer: () => <div data-testid="dashboard-viewer" />,
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

describe('WorkbenchPage header', () => {
  it('renders a page title and dated subline', async () => {
    render(<WorkbenchPage />);
    expect(await screen.findByRole('heading', { name: 'workbench.title' })).toBeInTheDocument();
    expect(screen.getByTestId('workbench-subline').textContent).toMatch(/\d/);
  });

  it('renders Export and New buttons', async () => {
    render(<WorkbenchPage />);
    await screen.findByRole('heading', { name: 'workbench.title' });
    expect(screen.getByRole('button', { name: /workbench\.export/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /workbench\.new/ })).toBeInTheDocument();
  });
});
