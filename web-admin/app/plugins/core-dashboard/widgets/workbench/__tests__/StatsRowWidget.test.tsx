import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import { StatsRowWidget } from '../StatsRowWidget';

vi.mock('../useWorkbenchStats', () => ({
  useWorkbenchStats: vi.fn(() => ({
    stats: {
      inbox_pending: { value: 241, label: 'workbench.stats.inbox_pending' },
      bpm_running: { value: 0, label: 'workbench.stats.bpm_running' },
      crm_account_active: { value: 107, label: 'workbench.stats.crm_account_active' },
      crm_opportunity_amount: { value: 0, label: 'workbench.stats.crm_opportunity_amount' },
    },
    loading: false,
  })),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

describe('StatsRowWidget — redesign', () => {
  it('renders 4 cards in a grid without gradient classes', () => {
    const { container } = render(<StatsRowWidget />);
    const cards = container.querySelectorAll('[data-testid^="stat-card-"]');
    expect(cards).toHaveLength(4);
    cards.forEach((c) => {
      expect(c.className).not.toMatch(/from-(blue|amber|emerald|violet|rose|cyan|indigo|orange)-/);
      expect(c.className).toMatch(/bg-white|dark:bg-gray-900/);
    });
  });
});
