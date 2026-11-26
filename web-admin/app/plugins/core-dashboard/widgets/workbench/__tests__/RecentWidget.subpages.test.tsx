/**
 * RecentWidget: a list page, its create form and a record's detail all sit under one menu entry.
 * Pages do not set a document title, so without a second line the three read as the same line.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { RecentWidget } from '../RecentWidget';

const DEFAULT_MENUS = [
  { name: '客户', path: '/p/crm_account_common' },
  { name: '报价单', path: '/p/qo_quote_common' },
];
const { recentVisits, menus } = vi.hoisted(() => ({
  recentVisits: {
    value: [] as Array<{ title: string; path: string; visitedAt: string }>,
  },
  menus: {
    value: [
      { name: '客户', path: '/p/crm_account_common' },
      { name: '报价单', path: '/p/qo_quote_common' },
    ] as Array<{ name: string; path: string }>,
  },
}));

vi.mock('../useRecentVisits', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../useRecentVisits')>()),
  getRecentVisits: vi.fn(() => recentVisits.value),
  fetchRecentVisits: vi.fn(async () => recentVisits.value),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'zh-CN',
    // the real helper returns the fallback text when a key has no translation; the seed keys are new
    t: (key: string, params?: Record<string, unknown>, fallback?: string) =>
      fallback ?? (params?.minutes ? `${params.minutes}m` : params?.hours ? `${params.hours}h` : key),
  }),
}));

vi.mock('~/root-data', () => ({
  useRootLoaderData: () => ({ menus: menus.value }),
}));

const SITE_NAME = 'AuraBoot'; // what document.title is on these pages when the visit is recorded
const visit = (path: string, title = SITE_NAME, minutesAgo = 5) => ({
  title,
  path,
  visitedAt: new Date(Date.now() - minutesAgo * 60_000).toISOString(),
});
const rowTexts = () => [...document.querySelectorAll('[data-testid="recent-list"] a')].map((a) => (a.textContent || '').replace(/\s+/g, ' ').trim());
const rowHrefs = () => [...document.querySelectorAll('[data-testid="recent-list"] a')].map((a) => a.getAttribute('href'));

describe('RecentWidget sub-pages of a menu entry', () => {
  beforeEach(() => {
    menus.value = DEFAULT_MENUS;
  });

  it('names the list, its create form and a record differently', () => {
    recentVisits.value = [
      visit('/p/crm_account_common/view/ACC-1'),
      visit('/p/crm_account_common/new'),
      visit('/p/crm_account_common'),
    ];

    render(<RecentWidget />);

    expect(rowHrefs()).toEqual(['/p/crm_account_common/view/ACC-1', '/p/crm_account_common/new', '/p/crm_account_common']);
    // a row reads: icon, first line, optional second line, time — as block spans, so no space between them
    expect(rowTexts()[0]).toMatch(/^📄客户详情5m$/);
    expect(rowTexts()[1]).toMatch(/^📄客户新建5m$/);
    expect(rowTexts()[2]).toMatch(/^📄客户5m$/);
  });

  it('calls an edit page 编辑', () => {
    recentVisits.value = [visit('/p/crm_account_common/edit/ACC-1')];

    render(<RecentWidget />);

    expect(rowTexts()[0]).toMatch(/^📄客户编辑5m$/);
  });

  it('names a sub-page after the menu entry it sits under, the nearest one when two nest', () => {
    menus.value = [
      { name: '设置', path: '/settings' },
      { name: '用户设置', path: '/settings/users' },
    ];
    recentVisits.value = [visit('/settings/users/new'), visit('/settings')];

    render(<RecentWidget />);

    expect(rowTexts()[0]).toMatch(/^📄用户设置新建5m$/);
    expect(rowTexts()[1]).toMatch(/^📄设置5m$/);
  });

  it("keeps the record's own title when the visit has one", () => {
    recentVisits.value = [visit('/p/qo_quote_common/view/SO-1', '华东客户 报价单 SO-1')];

    render(<RecentWidget />);

    expect(rowTexts()[0]).toMatch(/^📄报价单华东客户 报价单 SO-15m$/);
  });

  it('a page that is its own menu entry gets no second line, with or without a query string', () => {
    recentVisits.value = [visit('/p/crm_account_common'), visit('/p/qo_quote_common?page=2')];

    render(<RecentWidget />);

    expect(rowTexts()[0]).toMatch(/^📄客户5m$/);
    expect(rowTexts()[1]).toMatch(/^📄报价单5m$/);
  });

  it('a page whose path merely starts with a menu path is not under that menu', () => {
    recentVisits.value = [visit('/p/crm_account_common_archive')];

    render(<RecentWidget />);

    // the next segment must be separated by a slash: the page is outside the menu tree, like any other page
    expect(rowHrefs()).toEqual([]);
  });

  it('shows one row when two records point at the same page', () => {
    recentVisits.value = [visit('/p/crm_account_common', SITE_NAME, 3), visit('/p/crm_account_common', SITE_NAME, 9)];

    render(<RecentWidget />);

    expect(rowHrefs()).toEqual(['/p/crm_account_common']);
    expect(rowTexts()[0]).toMatch(/^📄客户3m$/);
  });
});
