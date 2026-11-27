import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render } from '@testing-library/react';
import { RecentWidget } from '../RecentWidget';

const { recentVisits, rootLoaderData } = vi.hoisted(() => ({
  recentVisits: {
    value: [
      {
        title: '客户详情',
        path: '/p/crm_account_common/view/ACC-1',
        modelCode: 'crm_account_common',
        visitedAt: '2026-06-15T10:00:00.000Z',
      },
      {
        title: '销售订单',
        path: '/p/sales_order_common/view/SO-1',
        modelCode: 'sales_order_common',
        visitedAt: '2026-06-15T09:00:00.000Z',
      },
    ],
  },
  rootLoaderData: {
    value: {
      menus: [
        { name: '客户', path: '/p/crm_account_common' },
        { name: '报价单', path: '/p/qo_quote_common' },
      ],
    },
  },
}));

// the widget also takes dedupeVisits from this module; keep the real one
vi.mock('../useRecentVisits', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../useRecentVisits')>()),
  getRecentVisits: vi.fn(() => recentVisits.value),
  fetchRecentVisits: vi.fn(async () => recentVisits.value),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'zh-CN',
    t: (key: string, params?: Record<string, unknown>) =>
      params?.minutes ? `${params.minutes}m` : params?.hours ? `${params.hours}h` : key,
  }),
}));

vi.mock('~/root-data', () => ({
  useRootLoaderData: () => rootLoaderData.value,
}));

describe('RecentWidget menu focus', () => {
  beforeEach(() => {
    recentVisits.value = [
      {
        title: '客户详情',
        path: '/p/crm_account_common/view/ACC-1',
        modelCode: 'crm_account_common',
        visitedAt: '2026-06-15T10:00:00.000Z',
      },
      {
        title: '销售订单',
        path: '/p/sales_order_common/view/SO-1',
        modelCode: 'sales_order_common',
        visitedAt: '2026-06-15T09:00:00.000Z',
      },
    ];
    rootLoaderData.value = {
      menus: [
        { name: '客户', path: '/p/crm_account_common' },
        { name: '报价单', path: '/p/qo_quote_common' },
      ],
    };
  });

  it('filters recent visits to the visible menu tree', async () => {
    const { findByText, queryByText } = render(<RecentWidget />);

    expect(await findByText('客户')).toBeInTheDocument();
    expect(queryByText('销售订单')).toBeNull();
  });

  it("shows the record's own title as the second line", async () => {
    const { findByText } = render(<RecentWidget />);

    expect(await findByText('客户')).toBeInTheDocument();
    expect(await findByText('客户详情')).toBeInTheDocument();
  });

  it('gives a visit without a title of its own no second line instead of its path', async () => {
    // two shapes of a title that is only a code: the path, and the page's key
    recentVisits.value = [
      {
        title: '/p/crm_account_common',
        path: '/p/crm_account_common',
        modelCode: 'crm_account_common',
        visitedAt: '2026-06-15T10:00:00.000Z',
      },
      {
        title: 'qo_quote_common',
        path: '/p/qo_quote_common',
        modelCode: 'qo_quote_common',
        visitedAt: '2026-06-15T09:00:00.000Z',
      },
    ];

    const { findAllByText, queryByText } = render(<RecentWidget />);

    expect(await findAllByText('客户')).toHaveLength(1);
    expect(await findAllByText('报价单')).toHaveLength(1);
    expect(queryByText('/p/crm_account_common')).toBeNull();
    expect(queryByText('crm_account_common')).toBeNull();
    expect(queryByText('qo_quote_common')).toBeNull();
  });
});

describe('RecentWidget — whole rows only', () => {
  // jsdom has no layout. The numbers are the ones measured on the real card: a 200 px list with a
  // 12 px bottom padding and rows 60 px high, 2 px apart. Before this change three rows showed and a
  // sliver of the fourth peeked out under them.
  function giveTheListItsSize(listHeight: number) {
    Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
      configurable: true,
      get(this: HTMLElement) {
        return this.getAttribute('data-testid') === 'recent-list' ? listHeight : 0;
      },
    });
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      const parent = this.parentElement;
      const index = parent ? Array.prototype.indexOf.call(parent.children, this) : -1;
      return { top: index * 62, height: index >= 0 ? 60 : 0 } as DOMRect;
    });
    vi.spyOn(window, 'getComputedStyle').mockImplementation(
      () => ({ paddingTop: '0px', paddingBottom: '12px' }) as CSSStyleDeclaration,
    );
    (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }

  const sixVisits = Array.from({ length: 6 }, (_, i) => ({
    title: `页面${i + 1}`,
    path: `/p/m${i + 1}`,
    modelCode: `m${i + 1}`,
    visitedAt: '2026-06-15T10:00:00.000Z',
  }));

  beforeEach(() => {
    recentVisits.value = sixVisits;
    rootLoaderData.value = { menus: sixVisits.map((visit, i) => ({ name: `菜单${i + 1}`, path: visit.path })) };
  });

  afterEach(() => {
    vi.restoreAllMocks();
    delete (HTMLElement.prototype as { clientHeight?: unknown }).clientHeight;
  });

  it('shows only the whole rows that fit and counts the rest on the More control', async () => {
    giveTheListItsSize(200);

    const { findAllByRole, findByTestId } = render(<RecentWidget />);

    expect(await findAllByRole('link')).toHaveLength(3);
    expect(await findByTestId('recent-more-button')).toHaveTextContent('workbench.recent.more (3)');
  });

  it('More shows every row and Show less folds the list again', async () => {
    giveTheListItsSize(200);

    const { findAllByRole, findByTestId } = render(<RecentWidget />);
    fireEvent.click(await findByTestId('recent-more-button'));

    expect(await findAllByRole('link')).toHaveLength(6);
    fireEvent.click(await findByTestId('recent-more-button'));

    expect(await findAllByRole('link')).toHaveLength(3);
  });

  it('has no More control when every row fits', async () => {
    giveTheListItsSize(400);

    const { findAllByRole, queryByTestId } = render(<RecentWidget />);

    expect(await findAllByRole('link')).toHaveLength(6);
    expect(queryByTestId('recent-more-button')).toBeNull();
  });
});
