import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  ensureQuoteRoleUser,
  openQuoteRolePage,
  seedQuoteForCorrectedBomUpload,
  type CreatedRows,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Release gate for QuoteOps surface authorization.
 *
 * This spec proves the two invariants that a page-only test would miss:
 *   1. quote root ACL still blocks users who did not receive the record;
 *   2. surface permission only broadens tab access after root access is allowed.
 *
 * Fixed smoke accounts keep the gate reproducible on an already-used stack. They are
 * test fixtures, not real employees. The quote itself is admin-owned and explicitly
 * shared through ReBAC, which isolates surface grants from ordinary owner/self scope.
 */

const QUOTE_ROLE_TEST_PASSWORD = 'Test2026x';

type SmokeKey = 'sales' | 'sales_b' | 'procurement' | 'approver';

const SMOKE_USERS: Record<SmokeKey, QuoteRoleUser> = {
  sales: {
    key: 'smoke_surface_sales',
    email: 'smoke-surface-sales@e2e.local',
    displayName: 'Smoke Surface Sales',
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes: ['qo_sales'],
  },
  sales_b: {
    key: 'smoke_surface_sales_b',
    email: 'smoke-surface-sales-b@e2e.local',
    displayName: 'Smoke Surface Sales B',
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes: ['qo_sales'],
  },
  procurement: {
    key: 'smoke_surface_proc',
    email: 'smoke-surface-proc@e2e.local',
    displayName: 'Smoke Surface Procurement',
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes: ['qo_procurement'],
  },
  approver: {
    key: 'smoke_surface_approver',
    email: 'smoke-surface-approver@e2e.local',
    displayName: 'Smoke Surface Approver',
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes: ['qo_commercial_approver'],
  },
};

const SHARED_SMOKE_KEYS: Array<SmokeKey> = ['sales', 'procurement', 'approver'];

const TAB_LABELS = {
  materials: '资料上传',
  bomPrice: 'BOM价格计算',
  processFee: '加工点数',
  output: '报价Excel',
};

type SurfaceProbe = { queryCode: string; expectedStatus: number };

const EXPECTED_SURFACES: Record<
  SmokeKey,
  { visibleTabs: string[]; hiddenTabs: string[]; probes: SurfaceProbe[] }
> = {
  sales: {
    visibleTabs: [TAB_LABELS.materials, TAB_LABELS.bomPrice],
    hiddenTabs: [TAB_LABELS.processFee, TAB_LABELS.output],
    probes: [
      { queryCode: 'qo_quote_bom_price_metrics', expectedStatus: 200 },
      { queryCode: 'qo_quote_process_fee_metrics', expectedStatus: 403 },
      { queryCode: 'qo_quote_output_readiness', expectedStatus: 403 },
      { queryCode: 'qo_quote_output_documents', expectedStatus: 403 },
    ],
  },
  procurement: {
    visibleTabs: [TAB_LABELS.materials, TAB_LABELS.bomPrice, TAB_LABELS.processFee],
    hiddenTabs: [TAB_LABELS.output],
    probes: [
      { queryCode: 'qo_quote_bom_price_metrics', expectedStatus: 200 },
      { queryCode: 'qo_quote_process_fee_metrics', expectedStatus: 200 },
      { queryCode: 'qo_quote_output_readiness', expectedStatus: 403 },
      { queryCode: 'qo_quote_output_documents', expectedStatus: 403 },
    ],
  },
  approver: {
    visibleTabs: [TAB_LABELS.materials, TAB_LABELS.processFee, TAB_LABELS.output],
    hiddenTabs: [TAB_LABELS.bomPrice],
    probes: [
      { queryCode: 'qo_quote_bom_price_metrics', expectedStatus: 403 },
      { queryCode: 'qo_quote_process_fee_metrics', expectedStatus: 200 },
      { queryCode: 'qo_quote_output_readiness', expectedStatus: 200 },
      { queryCode: 'qo_quote_output_documents', expectedStatus: 200 },
    ],
  },
  sales_b: {
    visibleTabs: [],
    hiddenTabs: [],
    probes: [{ queryCode: 'qo_quote_bom_price_metrics', expectedStatus: 403 }],
  },
};

test.describe('Quote surface permission release gate', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  let quote: CreatedRows;
  let sharePids: string[] = [];

  test.beforeAll(async ({ browser }) => {
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    quote = await seedQuoteForCorrectedBomUpload(adminPage);

    for (const user of Object.values(SMOKE_USERS)) {
      await ensureQuoteRoleUser(adminPage, user);
    }

    for (const key of SHARED_SMOKE_KEYS) {
      const user = SMOKE_USERS[key];
      const search = await adminPage.request.get(
        `/api/admin/users/search?keyword=${encodeURIComponent(user.email)}&size=5`,
      );
      const searchBody = await search.json().catch(() => ({}) as any);
      expect(
        search.ok(),
        `smoke user search ${key}: HTTP ${search.status()} ${JSON.stringify(searchBody)}`,
      ).toBe(true);
      const users = Array.isArray(searchBody?.data) ? searchBody.data : [];
      const userPid = users.find((item: any) => item?.email === user.email)?.pid;
      expect(userPid, `smoke user ${key} should expose a stable pid`).toBeTruthy();

      const share = await adminPage.request.post('/api/record-share', {
        data: {
          resourceCode: 'qo_quote_common',
          recordPid: quote.quoteId,
          subjectType: 'member',
          subjectPid: userPid,
          permissionMask: 'read',
        },
      });
      expect(
        share.ok(),
        `record share for ${key}: HTTP ${share.status()} ${await share.text()}`,
      ).toBe(true);
    }

    const shares = await adminPage.request.get(
      `/api/record-share?resourceCode=qo_quote_common&recordPid=${encodeURIComponent(quote.quoteId)}`,
    );
    const sharesBody = await shares.json().catch(() => ({}) as any);
    sharePids = (Array.isArray(sharesBody?.data) ? sharesBody.data : [])
      .map((item: any) => String(item?.pid ?? ''))
      .filter(Boolean);

    await adminContext.close();
  });

  test.afterAll(async ({ browser }) => {
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    if (sharePids.length > 0) {
      await adminPage.request.post('/api/record-share/batch-delete', {
        data: { sharePids },
      });
    }
    await cleanupRows(adminPage, quote);
    await adminContext.close();
  });

  for (const key of Object.keys(SMOKE_USERS) as Array<SmokeKey>) {
    test(`${SMOKE_USERS[key].key}: root ACL and surface authorization`, async ({ browser }) => {
      const { page } = await openQuoteRolePage(browser, SMOKE_USERS[key]);

      const root = await page.request.get(
        `/api/dynamic/qo_quote_common/${encodeURIComponent(quote.quoteId)}`,
      );
      expect(root.status(), `unshared smoke ${key} root visibility`).toBe(
        key === 'sales_b' ? 403 : 200,
      );

      for (const probe of EXPECTED_SURFACES[key].probes) {
        const response = await page.request.post(
          `/api/meta/named-queries/${probe.queryCode}/execute`,
          { data: { parameters: { quoteId: quote.quoteId } } },
        );
        expect(
          response.status(),
          `${key} ${probe.queryCode} HTTP status`,
        ).toBe(probe.expectedStatus);
      }

      await page.goto(
        `/p/qo_quote_common/view/${quote.quoteId}#bom_price`,
        { waitUntil: 'domcontentloaded' },
      );

      for (const label of EXPECTED_SURFACES[key].visibleTabs) {
        await expect(page.getByRole('tab', { name: label })).toBeVisible({ timeout: 20_000 });
      }
      for (const label of EXPECTED_SURFACES[key].hiddenTabs) {
        await expect(page.getByRole('tab', { name: label })).toHaveCount(0);
      }
    });
  }
});
