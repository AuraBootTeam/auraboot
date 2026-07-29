import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { SchedulesTab } from '../AgentDetailTabs';

const { get, post, translate } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  translate: (_key: string, _params?: unknown, fallback?: string) => fallback ?? _key,
}));

vi.mock('~/shared/services/http-client', () => ({
  get,
  post,
  put: vi.fn(),
  del: vi.fn(),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    t: translate,
  }),
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
  }),
}));

const schedule = {
  pid: 'SCHEDULE_1',
  title: 'Daily account review',
  cron_expression: '0 0 9 * * *',
  schedule_status: 'active',
  next_run_at: null,
  last_run_at: '2026-07-29T09:00:00Z',
  timezone: 'Asia/Shanghai',
  daily_run_budget: 8,
  concurrency_limit: 2,
  last_block_reason: 'QUIET_HOURS',
};

describe('Agent schedules tab', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    get.mockResolvedValue({ code: '0', data: { records: [schedule] } });
    post.mockResolvedValue({
      code: '0',
      data: { taskPid: 'TASK_1', schedulePid: 'SCHEDULE_1' },
    });
  });

  it('confirms run-now and dispatches through the governed schedule endpoint', async () => {
    render(<SchedulesTab agentCode="proactive_sales" />);

    await screen.findByText('Daily account review');
    fireEvent.click(screen.getByTestId('run-schedule-now-SCHEDULE_1'));
    expect(screen.getByRole('dialog', { name: 'Run schedule now' })).toBeTruthy();
    fireEvent.click(screen.getByTestId('confirm-run-schedule-now'));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/agent/schedule/SCHEDULE_1/trigger', {}),
    );
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
  });

  it('queries by explicit agent code and renders governance evidence', async () => {
    render(<SchedulesTab agentCode="proactive_sales" />);

    expect(await screen.findByText('Daily account review')).toBeTruthy();
    expect(screen.getByText(/8 per day · 2 concurrent/)).toBeTruthy();
    expect(screen.getByTestId('run-schedule-now-SCHEDULE_1')).toHaveClass('whitespace-nowrap');
    expect(screen.getByText(/QUIET_HOURS/)).toBeTruthy();
    expect(get).toHaveBeenCalledWith(
      '/api/dynamic/agent-schedule/list',
      expect.objectContaining({
        pageNum: 1,
        pageSize: 20,
        filters: expect.stringContaining('proactive_sales'),
      }),
    );
  });

  it('shows an explicit error and retries instead of disguising failure as empty', async () => {
    get.mockRejectedValueOnce(new Error('network unavailable'));

    render(<SchedulesTab agentCode="proactive_sales" />);

    expect(await screen.findByTestId('agent-schedules-error')).toHaveTextContent(
      'Schedules could not be loaded',
    );
    expect(screen.queryByText('No schedules')).toBeNull();

    fireEvent.click(screen.getByTestId('retry-agent-schedules'));
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Daily account review')).toBeTruthy();
  });
});
