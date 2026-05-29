import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatsCardWidget } from '../StatsCardWidget';

vi.mock('../useWorkbenchStats', () => ({
  useWorkbenchStats: vi.fn(),
}));
import { useWorkbenchStats } from '../useWorkbenchStats';

const mocked = useWorkbenchStats as unknown as ReturnType<typeof vi.fn>;

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

describe('StatsCardWidget — redesign', () => {
  beforeEach(() => {
    mocked.mockReset();
  });

  it('renders a white card without gradient background classes', () => {
    mocked.mockReturnValue({
      stats: { inbox_pending: { value: 241, label: 'workbench.stats.inbox_pending' } },
      loading: false,
    });
    const { container } = render(<StatsCardWidget statKey="inbox_pending" />);
    const card = container.querySelector('[data-testid="stat-card-inbox_pending"]');
    expect(card).not.toBeNull();
    const cls = card!.className;
    expect(cls).not.toMatch(/from-(blue|amber|emerald|violet|rose|cyan|indigo|orange)-/);
    expect(cls).toMatch(/bg-white/);
    expect(cls).toMatch(/border/);
  });

  it('renders a sparkline polyline when series has ≥ 2 points', () => {
    mocked.mockReturnValue({
      stats: {
        inbox_pending: {
          value: 241,
          label: 'workbench.stats.inbox_pending',
          series: { period: 'day', points: [220, 225, 223, 232, 235, 240, 241] },
        },
      },
      loading: false,
    });
    const { container } = render(<StatsCardWidget statKey="inbox_pending" />);
    expect(container.querySelector('polyline')).not.toBeNull();
  });

  it('renders no sparkline svg when series is missing (avoids dead-UI baseline)', () => {
    mocked.mockReturnValue({
      stats: { bpm_running: { value: 0, label: 'workbench.stats.bpm_running' } },
      loading: false,
    });
    const { container } = render(<StatsCardWidget statKey="bpm_running" />);
    expect(container.querySelector('polyline')).toBeNull();
    expect(container.querySelector('svg')).toBeNull();
  });

  it('shows trend text with positive color class when direction is up', () => {
    mocked.mockReturnValue({
      stats: {
        inbox_pending: {
          value: 241,
          label: 'workbench.stats.inbox_pending',
          trend: { direction: 'up', value: 5.2, period: 'week', unit: 'percent' },
        },
      },
      loading: false,
    });
    render(<StatsCardWidget statKey="inbox_pending" />);
    const trend = screen.getByText(/5\.2/);
    expect(trend.className).toMatch(/text-emerald-/);
  });
});
