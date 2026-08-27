import type { Browser, Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { ensureSidebarExpanded, uniqueId } from '../helpers';
import {
  ensureQuoteRoleUser,
  makeQuoteRoleUser,
  openQuoteRolePage,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

const DASHBOARD_PATH = '/dashboards/view/qo_tool_admin_dashboard';
const TREND_QUERIES = ['qo_tool_quote_weekly_trend', 'qo_tool_bom_weekly_trend'] as const;
const PEOPLE_QUERIES = [
  'qo_tool_quote_people_workload',
  'qo_tool_bom_people_workload',
] as const;
const PLATFORM_BASE_CAPABILITY = 'sys.cap.member_base';

async function queryRecords(page: Page, code: string): Promise<Record<string, unknown>[]> {
  const response = await page.request.get(
    `/api/datasource/list?datasourceId=nq:${encodeURIComponent(code)}&format=records&maxItems=100`,
  );
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok(),
    `${code} HTTP ${response.status()}: ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
  const records = (body as any)?.data?.records;
  expect(Array.isArray(records), `${code} should return records`).toBe(true);
  return records as Record<string, unknown>[];
}

async function expectFourCharts(page: Page, path: string): Promise<void> {
  await page.goto(path, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('canvas').first()).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('canvas')).toHaveCount(4);
  if (path === '/home') {
    await expect(page.getByRole('heading', { name: '工作台' })).toBeVisible();
  } else {
    await expect(page.getByRole('heading', { name: '报价与 BOM 运营概览' })).toBeVisible();
  }
}

async function createOrdinaryRole(page: Page, roleCode: string): Promise<void> {
  const createResponse = await page.request.post('/api/roles', {
    data: {
      code: roleCode,
      name: `E2E Operations Viewer ${roleCode.slice(-10)}`,
      description: 'E2E tenant member without Quote/BOM analytics administration',
      type: 'custom',
      status: 'active',
      scopeType: 'tenant',
    },
  });
  const createBody = await createResponse.json().catch(() => ({}));
  expect(createResponse.ok(), JSON.stringify(createBody).slice(0, 800)).toBe(true);
  const rolePid = String((createBody as any)?.data?.pid ?? '');
  expect(rolePid).toBeTruthy();

  const grantResponse = await page.request.put(
    `/api/permission/capabilities?rolePid=${encodeURIComponent(rolePid)}`,
    { data: [PLATFORM_BASE_CAPABILITY] },
  );
  expect(grantResponse.ok()).toBe(true);
}

async function withUserPage(
  browser: Browser,
  user: QuoteRoleUser,
  run: (page: Page) => Promise<void>,
): Promise<void> {
  const { context, page } = await openQuoteRolePage(browser, user);
  try {
    await run(page);
  } finally {
    await context.close();
  }
}

test.describe('Quote and BOM operations dashboard @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);

  const uid = uniqueId('qo_ops').replace(/_/g, '-');
  const roleCode = `e2e_qo_ops_viewer_${uid.replace(/[^a-z0-9]+/gi, '_')}`.slice(0, 60);
  const ordinaryUser = makeQuoteRoleUser('qo_ops_viewer', uid, [roleCode]);

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      await createOrdinaryRole(page, roleCode);
      await ensureQuoteRoleUser(page, ordinaryUser);
    } finally {
      await context.close();
    }
  });

  test('admin gets one menu link and the same four charts on dashboard and home', async ({ page }) => {
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);
    await expect(page.getByTestId('sidebar').locator(`a[href="${DASHBOARD_PATH}"]`)).toHaveCount(1);

    await expectFourCharts(page, '/home');
    await expectFourCharts(page, DASHBOARD_PATH);

    for (const code of TREND_QUERIES) {
      const records = await queryRecords(page, code);
      expect(records, `${code} should include all twelve Monday-based weeks`).toHaveLength(12);
      expect(
        records.some((row) => Number(row.quote_count ?? row.bom_count ?? 0) > 0),
        `${code} should prove non-zero seeded business data`,
      ).toBe(true);
    }
    for (const code of PEOPLE_QUERIES) {
      const records = await queryRecords(page, code);
      expect(records.length, `${code} should include at least one active creator`).toBeGreaterThan(0);
      expect(
        records.every((row) => Number(row.quote_count ?? row.created_count ?? 0) > 0),
        `${code} admin rows should contain active creators only`,
      ).toBe(true);
    }
  });

  test('ordinary employee sees home contribution without admin menu and personnel data is self-only', async ({
    browser,
  }) => {
    await withUserPage(browser, ordinaryUser, async (page) => {
      await expectFourCharts(page, '/home');
      await ensureSidebarExpanded(page);
      await expect(page.getByTestId('sidebar').locator(`a[href="${DASHBOARD_PATH}"]`)).toHaveCount(0);

      for (const code of TREND_QUERIES) {
        const records = await queryRecords(page, code);
        expect(records).toHaveLength(12);
      }
      for (const code of PEOPLE_QUERIES) {
        const records = await queryRecords(page, code);
        expect(records, `${code} ordinary employee must receive only one self row`).toHaveLength(1);
        expect(String(records[0]?.creator_name ?? '')).toBeTruthy();
      }
    });
  });
});
