import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const stableToastContext = {
  showErrorToast: vi.fn(),
};

vi.mock('react-router', () => ({
  useNavigate: () => vi.fn(),
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => stableToastContext,
}));

vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (key: string, fallback?: string) => fallback ?? key,
}));

vi.mock('~/plugins/core-bpm/services/slaService', () => ({
  getDashboard: vi.fn(),
  listSlaRecords: vi.fn(),
}));

import { SlaMonitorPanel } from '../SlaMonitorPanel';
import * as slaService from '~/plugins/core-bpm/services/slaService';

const mockGetDashboard = vi.mocked(slaService.getDashboard);
const mockListSlaRecords = vi.mocked(slaService.listSlaRecords);

describe('SlaMonitorPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    stableToastContext.showErrorToast.mockReset();
    mockGetDashboard.mockResolvedValue({
      processDefinitions: { total: 1, draft: 0, deployed: 1, suspended: 0 },
      sla: { active: 0, running: 0, warning: 0, overdue: 0, paused: 0 },
      slaConfigs: { total: 2, enabled: 2 },
    });
    mockListSlaRecords.mockResolvedValue([]);
  });

  it('loads the dashboard once even when translated callbacks are not referentially stable', async () => {
    render(<SlaMonitorPanel />);

    await waitFor(() => expect(screen.getByTestId('sla-dashboard-configs')).toBeInTheDocument());

    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(mockGetDashboard).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Loading...')).not.toBeInTheDocument();
  });

  it('loads drill-down records once after a stat card is opened', async () => {
    render(<SlaMonitorPanel />);

    await waitFor(() => expect(screen.getByTestId('sla-stat-ALL')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('sla-stat-ALL'));

    await waitFor(() => expect(screen.getByTestId('sla-drill-empty')).toBeInTheDocument());
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(mockListSlaRecords).toHaveBeenCalledTimes(1);
    expect(mockListSlaRecords).toHaveBeenCalledWith(undefined);
  });
});
