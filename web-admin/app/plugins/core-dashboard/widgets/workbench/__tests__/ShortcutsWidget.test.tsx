import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import { ShortcutsWidget } from '../ShortcutsWidget';

vi.mock('~/shared/services/engagementService', () => ({
  listFavorites: vi.fn(async () => []),
  removeFavorite: vi.fn(),
  reorderFavorites: vi.fn(),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

describe('ShortcutsWidget — redesign', () => {
  it('renders items in a vertical list (ul)', async () => {
    const { findByTestId } = render(<ShortcutsWidget />);
    const list = await findByTestId('shortcuts-list');
    expect(list.tagName.toLowerCase()).toBe('ul');
  });

  it('rows do not have pastel tile backgrounds (bg-*-50)', async () => {
    const { findAllByTestId } = render(<ShortcutsWidget />);
    const rows = await findAllByTestId('shortcut-row');
    rows.forEach((row: HTMLElement) => {
      expect(row.className).not.toMatch(/bg-(blue|green|amber|violet|orange|indigo|rose)-50/);
    });
  });

  it('renders an icon tile and a chevron per row', async () => {
    const { findAllByTestId } = render(<ShortcutsWidget />);
    const rows = await findAllByTestId('shortcut-row');
    expect(rows.length).toBeGreaterThan(0);
    rows.forEach((row: HTMLElement) => {
      expect(row.querySelector('[data-testid="shortcut-icon"]')).not.toBeNull();
      expect(row.textContent).toContain('›');
    });
  });
});
