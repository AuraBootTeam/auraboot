import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import { StatsRowWidget } from '../StatsRowWidget';

vi.mock('../useWorkbenchStats', () => ({
  useWorkbenchStats: vi.fn(() => ({
    stats: {
      inbox_pending: { value: 241, label: 'workbench.stats.inbox_pending' },
      inbox_urgent: { value: 7, label: 'workbench.stats.inbox_urgent' },
    },
    loading: false,
  })),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

describe('StatsRowWidget — redesign', () => {
  it('renders the two core inbox cards without gradient classes', () => {
    const { container } = render(<StatsRowWidget />);
    const cards = container.querySelectorAll('[data-testid^="stat-card-"]');
    expect(cards).toHaveLength(2);
    cards.forEach((card: Element) => {
      const className = card.getAttribute('class') ?? '';
      expect(className).not.toMatch(/from-(blue|amber|emerald|violet|rose|cyan|indigo|orange)-/);
      expect(className).toMatch(/bg-white|dark:bg-gray-900/);
    });
  });
});
