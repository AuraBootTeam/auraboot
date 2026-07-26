import { render, screen } from '@testing-library/react';
import { MemoryRouter, useParams } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DashboardViewByCode from '../view.$code';

const { findByCode } = vi.hoisted(() => ({ findByCode: vi.fn() }));

vi.mock('~/plugins/core-dashboard/services/dashboardService', () => ({
  dashboardService: { findByCode },
}));

vi.mock('~/plugins/core-dashboard/components/DashboardViewer', () => ({
  DashboardViewer: () => <div data-testid="dashboard-viewer" />,
}));

vi.mock('~/framework/smart/components/data-tools/ExportPdfButton', () => ({
  ExportPdfButton: () => <button type="button">Export</button>,
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn() }),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'zh-CN' }),
}));

describe('DashboardViewByCode responsive header', () => {
  beforeEach(() => {
    vi.mocked(useParams).mockReturnValue({ code: 'billing_dashboard' });
    findByCode.mockResolvedValue({
      pid: 'dashboard-pid',
      code: 'billing_dashboard',
      title: '计费运营驾驶舱',
      description: '一段可能很长、但不应挤压操作区的说明',
      widgets: [],
      layoutConfig: { columns: 12, rowHeight: 80, gap: 16 },
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
