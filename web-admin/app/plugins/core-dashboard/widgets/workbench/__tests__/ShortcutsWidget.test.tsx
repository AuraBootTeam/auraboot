import { beforeEach, describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import { ShortcutsWidget } from '../ShortcutsWidget';

const { listFavoritesMock, rootLoaderData } = vi.hoisted(() => ({
  listFavoritesMock: vi.fn(),
  rootLoaderData: {
    value: {
      menus: [
        { name: '客户', path: '/p/crm_account_common' },
        { name: '报价单', path: '/p/qo_quote_common' },
      ],
    },
  },
}));

vi.mock('~/shared/services/engagementService', () => ({
  listFavorites: listFavoritesMock,
  removeFavorite: vi.fn(),
  reorderFavorites: vi.fn(),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

vi.mock('~/root', () => ({
  useRootLoaderData: () => rootLoaderData.value,
}));

describe('ShortcutsWidget — redesign', () => {
  beforeEach(() => {
    listFavoritesMock.mockReset();
    listFavoritesMock.mockResolvedValue([]);
    rootLoaderData.value = {
      menus: [
        { name: '客户', path: '/p/crm_account_common' },
        { name: '报价单', path: '/p/qo_quote_common' },
      ],
    };
  });

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

  it('falls back to visible menu entries when no favorites are configured', async () => {
    const { findAllByTestId } = render(<ShortcutsWidget />);

    const rows = (await findAllByTestId('shortcut-row')) as HTMLElement[];

    expect(rows.map((row) => row.textContent)).toEqual(
      expect.arrayContaining([expect.stringContaining('客户'), expect.stringContaining('报价单')]),
    );
    expect(rows).toHaveLength(2);
  });

  it('filters favorite shortcuts that are hidden by menu focus', async () => {
    listFavoritesMock.mockResolvedValueOnce([
      {
        id: 'F1',
        targetLabel: '客户详情',
        targetId: 'crm_account_common',
        targetContext: { path: '/p/crm_account_common/view/ACC-1', icon: 'A' },
      },
      {
        id: 'F2',
        targetLabel: '销售订单',
        targetId: 'sales_order_common',
        targetContext: { path: '/p/sales_order_common', icon: 'S' },
      },
    ]);
    const { findAllByTestId } = render(<ShortcutsWidget />);

    const rows = await findAllByTestId('shortcut-row');

    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('客户详情');
    expect(rows[0].textContent).not.toContain('销售订单');
  });
});
