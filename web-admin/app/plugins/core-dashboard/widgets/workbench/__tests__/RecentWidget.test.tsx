import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
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

vi.mock('../useRecentVisits', () => ({
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
    recentVisits.value = [
      {
        title: '/p/crm_account_common',
        path: '/p/crm_account_common',
        modelCode: 'crm_account_common',
        visitedAt: '2026-06-15T10:00:00.000Z',
      },
      {
        title: 'crm_account_common',
        path: '/p/crm_account_common',
        modelCode: 'crm_account_common',
        visitedAt: '2026-06-15T09:00:00.000Z',
      },
    ];

    const { findAllByText, queryByText } = render(<RecentWidget />);

    expect(await findAllByText('客户')).toHaveLength(2);
    expect(queryByText('/p/crm_account_common')).toBeNull();
    expect(queryByText('crm_account_common')).toBeNull();
  });
});
