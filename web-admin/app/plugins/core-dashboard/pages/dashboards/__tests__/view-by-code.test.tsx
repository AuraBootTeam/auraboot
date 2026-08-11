import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useParams, useSearchParams } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DashboardViewByCode from '../view.$code';

const { findByCode, dashboardViewer } = vi.hoisted(() => ({
  findByCode: vi.fn(),
  dashboardViewer: vi.fn(),
}));

vi.mock('~/plugins/core-dashboard/services/dashboardService', () => ({
  dashboardService: { findByCode },
}));

vi.mock('~/plugins/core-dashboard/components/DashboardViewer', () => ({
  DashboardViewer: (props: unknown) => {
    dashboardViewer(props);
    return <div data-testid="dashboard-viewer" />;
  },
}));

vi.mock('~/framework/smart/components/data-tools/ExportPdfButton', () => ({
  ExportPdfButton: () => <button type="button">Export</button>,
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn() }),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'zh-CN',
    t: (key: string) => ({
      'page.crm_account_360.title': '客户360°视图',
      'page.crm_account_360.description': '客户关键指标、近期商机与活动全景',
    })[key] ?? key,
  }),
}));

describe('DashboardViewByCode responsive header', () => {
  beforeEach(() => {
    dashboardViewer.mockReset();
    vi.mocked(useParams).mockReturnValue({ code: 'billing_dashboard' });
    vi.mocked(useSearchParams).mockReturnValue([new URLSearchParams(), vi.fn()]);
    findByCode.mockResolvedValue({
      pid: 'dashboard-pid',
      code: 'billing_dashboard',
      title: '计费运营驾驶舱',
      description: '一段可能很长、但不应挤压操作区的说明',
      widgets: [],
      layoutConfig: { columns: 12, rowHeight: 80, gap: 16 },
    });
  });

  it('materializes record-scoped widget data and drill-down values from the URL', async () => {
    vi.mocked(useSearchParams).mockReturnValue([
      new URLSearchParams('recordPid=ACC-001'),
      vi.fn(),
    ]);
    findByCode.mockResolvedValue({
      pid: 'account-dashboard-pid',
      code: 'crm_account_360',
      title: '$i18n:page.crm_account_360.title',
      description: '$i18n:page.crm_account_360.description',
      widgets: [
        {
          id: 'contacts',
          config: {
            dataSource: { parameters: { accountId: '${recordPid}' } },
            drillDown: { filters: [{ value: '${recordPid}' }] },
          },
        },
      ],
      layoutConfig: { columns: 12, rowHeight: 80, gap: 16 },
    });

    render(
      <MemoryRouter initialEntries={['/dashboards/view/crm_account_360?recordPid=ACC-001']}>
        <DashboardViewByCode />
      </MemoryRouter>,
    );

    await screen.findByTestId('dashboard-viewer');
    expect(screen.getByRole('heading', { name: '客户360°视图' })).toBeVisible();
    expect(screen.getByText('客户关键指标、近期商机与活动全景')).toBeVisible();
    await waitFor(() => {
      expect(dashboardViewer).toHaveBeenLastCalledWith(
        expect.objectContaining({
          title: '客户360°视图',
          hideWidgetActions: true,
          widgets: [
            expect.objectContaining({
              config: {
                dataSource: { parameters: { accountId: 'ACC-001' } },
                drillDown: { filters: [{ value: 'ACC-001' }] },
              },
            }),
          ],
        }),
      );
    });
  });

  it('keeps refresh action on one line when title space is constrained', async () => {
    render(
      <MemoryRouter>
        <DashboardViewByCode />
      </MemoryRouter>,
    );

    const refresh = await screen.findByRole('button', { name: /刷新/ });
    expect(refresh).toHaveClass('shrink-0', 'whitespace-nowrap');
    expect(refresh.parentElement).toHaveClass('shrink-0');
  });
});
