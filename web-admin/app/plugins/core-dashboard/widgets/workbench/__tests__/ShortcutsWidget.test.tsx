import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/react';
import { ShortcutsWidget } from '../ShortcutsWidget';

const { listFavoritesMock, rootLoaderData, translateMock } = vi.hoisted(() => ({
  listFavoritesMock: vi.fn(),
  translateMock: (key: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? key,
  rootLoaderData: {
    value: {
      menus: [
        { name: '客户', path: '/p/crm_account_common', icon: 'IconBuilding' },
        { name: '报价单', path: '/p/qo_quote_common', icon: 'FileText' },
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
  useI18n: () => ({ t: translateMock }),
}));

vi.mock('~/root-data', () => ({
  useRootLoaderData: () => rootLoaderData.value,
}));

describe('ShortcutsWidget — redesign', () => {
  beforeEach(() => {
    listFavoritesMock.mockReset();
    listFavoritesMock.mockResolvedValue([]);
    rootLoaderData.value = {
      menus: [
        { name: '客户', path: '/p/crm_account_common', icon: 'IconBuilding' },
        { name: '报价单', path: '/p/qo_quote_common', icon: 'FileText' },
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

  it('renders menu icon ids as svg icons instead of raw text', async () => {
    const { findAllByTestId } = render(<ShortcutsWidget />);
    const icons = await findAllByTestId('shortcut-icon');

    expect(icons[0].textContent).not.toContain('IconBuilding');
    expect(icons[0].querySelector('svg')).not.toBeNull();
  });

  it('falls back to visible menu entries when no favorites are configured', async () => {
    const { findAllByTestId } = render(<ShortcutsWidget />);

    const rows = (await findAllByTestId('shortcut-row')) as HTMLElement[];

    expect(rows.map((row) => row.textContent)).toEqual(
      expect.arrayContaining([expect.stringContaining('客户'), expect.stringContaining('报价单')]),
    );
    expect(rows).toHaveLength(2);
  });

  it('uses the menu name when its translation key has no resource', async () => {
    Object.assign(rootLoaderData.value.menus[0], { nameKey: 'menu.cls_menu' });
    const { findAllByTestId } = render(<ShortcutsWidget />);
    const rows = await findAllByTestId('shortcut-row');
    expect(rows[0].textContent).toContain('客户');
    expect(rows[0].textContent).not.toContain('menu.cls_menu');
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

  it('keeps authored shortcuts static unless personalization is explicitly enabled', async () => {
    const { findAllByTestId, queryByTestId } = render(
      <ShortcutsWidget
        shortcuts={[
          { label: '新建客户', path: '/p/crm_account_common/new', icon: 'IconBuilding' },
        ]}
      />,
    );

    const rows = await findAllByTestId('shortcut-row');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('新建客户');
    expect(listFavoritesMock).not.toHaveBeenCalled();
    expect(queryByTestId('shortcuts-customize-button')).toBeNull();
  });

  it('uses authored shortcuts as the personalizable baseline when no favorites exist', async () => {
    const { findAllByTestId, findByTestId } = render(
      <ShortcutsWidget
        personalizable
        shortcuts={[
          { label: '新建客户', path: '/p/crm_account_common/new', icon: 'IconBuilding' },
        ]}
      />,
    );

    const rows = await findAllByTestId('shortcut-row');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('新建客户');
    expect(listFavoritesMock).toHaveBeenCalledWith('menu');
    expect(await findByTestId('shortcuts-customize-button')).not.toBeNull();
  });

  it('replaces the authored baseline with the user ordered favorites', async () => {
    listFavoritesMock.mockResolvedValueOnce([
      {
        id: 'F2',
        targetLabel: '报价单',
        targetId: 'qo_quote_common',
        targetContext: { path: '/p/qo_quote_common', icon: 'FileText' },
      },
      {
        id: 'F1',
        targetLabel: '客户',
        targetId: 'crm_account_common',
        targetContext: { path: '/p/crm_account_common', icon: 'IconBuilding' },
      },
    ]);

    const { findAllByTestId, findByText } = render(
      <ShortcutsWidget
        personalizable
        shortcuts={[
          { label: '新建客户', path: '/p/crm_account_common/new', icon: 'IconBuilding' },
        ]}
      />,
    );

    const rows = await findAllByTestId('shortcut-row');
    expect(rows.map((row: HTMLElement) => row.textContent)).toEqual([
      expect.stringContaining('报价单'),
      expect.stringContaining('客户'),
    ]);
    expect(await findByText('编辑')).not.toBeNull();
  });
});

describe('ShortcutsWidget — whole rows only', () => {
  // jsdom has no layout. The numbers are the ones measured on the real card: a 180 px list with an
  // 8 px bottom padding and 48 px rows. Before this change 3.75 rows showed and the fourth was cut.
  function giveTheListAHeight(listHeight: number, bottomPadding: number) {
    Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
      configurable: true,
      get(this: HTMLElement) {
        return this.getAttribute('data-testid') === 'shortcuts-list' ? listHeight : 0;
      },
    });
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return { height: this.tagName === 'LI' ? 48 : 0 } as DOMRect;
    });
    vi.spyOn(window, 'getComputedStyle').mockImplementation(
      () => ({ paddingBottom: `${bottomPadding}px` }) as CSSStyleDeclaration,
    );
    (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }

  const eightMenus = Array.from({ length: 8 }, (_, i) => ({
    name: `菜单${i + 1}`,
    path: `/p/menu_${i + 1}`,
    icon: 'FileText',
  }));

  beforeEach(() => {
    listFavoritesMock.mockReset();
    listFavoritesMock.mockResolvedValue([]);
    rootLoaderData.value = { menus: eightMenus };
  });

  afterEach(() => {
    vi.restoreAllMocks();
    delete (HTMLElement.prototype as { clientHeight?: unknown }).clientHeight;
  });

  it('shows only the whole rows that fit and counts the rest on the More control', async () => {
    giveTheListAHeight(180, 8);

    const { findAllByTestId, findByTestId } = render(<ShortcutsWidget />);

    expect(await findAllByTestId('shortcut-row')).toHaveLength(3);
    expect(await findByTestId('shortcuts-more-button')).toHaveTextContent('更多 (5)');
  });

  it('More shows every row and turns into Show less, which folds the list again', async () => {
    giveTheListAHeight(180, 8);

    const { findAllByTestId, findByTestId } = render(<ShortcutsWidget />);
    fireEvent.click(await findByTestId('shortcuts-more-button'));

    expect(await findAllByTestId('shortcut-row')).toHaveLength(8);
    const showLess = await findByTestId('shortcuts-more-button');
    expect(showLess).toHaveTextContent('收起');

    fireEvent.click(showLess);

    expect(await findAllByTestId('shortcut-row')).toHaveLength(3);
    expect(await findByTestId('shortcuts-more-button')).toHaveTextContent('更多 (5)');
  });

  it('follows a taller card instead of a fixed number of rows', async () => {
    giveTheListAHeight(250, 8);

    const { findAllByTestId, findByTestId } = render(<ShortcutsWidget />);

    expect(await findAllByTestId('shortcut-row')).toHaveLength(5);
    expect(await findByTestId('shortcuts-more-button')).toHaveTextContent('更多 (3)');
  });

  it('has no More control when every row fits', async () => {
    giveTheListAHeight(400, 8);

    const { findAllByTestId, queryByTestId } = render(<ShortcutsWidget />);

    expect(await findAllByTestId('shortcut-row')).toHaveLength(8);
    expect(queryByTestId('shortcuts-more-button')).toBeNull();
  });

  it('shows every row while the list cannot be measured', async () => {
    const { findAllByTestId, queryByTestId } = render(<ShortcutsWidget />);

    expect(await findAllByTestId('shortcut-row')).toHaveLength(8);
    expect(queryByTestId('shortcuts-more-button')).toBeNull();
  });
});
