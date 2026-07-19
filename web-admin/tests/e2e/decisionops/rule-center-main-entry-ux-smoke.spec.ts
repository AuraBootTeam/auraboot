import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { ensureSidebarExpanded, waitForDynamicPageLoad } from '../helpers';

type ViewportSpec = {
  key: string;
  width: number;
  height: number;
};

type EntrySpec = {
  key: string;
  path: string;
  href?: string;
  assertReady: (page: Page) => Promise<void>;
};

const VIEWPORTS: ViewportSpec[] = [
  { key: 'desktop', width: 1280, height: 900 },
  { key: 'compact', width: 632, height: 900 },
];

const ENTRIES: EntrySpec[] = [
  {
    key: 'strategy-studio',
    path: '/decision-ops',
    assertReady: async (page) => {
      await expect(page.getByTestId('decisionops-console')).toBeVisible({ timeout: 15_000 });
      await expect(page.getByTestId('strategy-studio')).toBeVisible({ timeout: 15_000 });
      await expect(page.locator('main').first()).toContainText(/策略工作台|规则中心/);
    },
  },
  {
    key: 'decision-definitions',
    path: '/p/decisionops_definitions',
    assertReady: async (page) => {
      await waitForDynamicPageLoad(page);
      const main = page.locator('main').first();
      await expect(main).toContainText(/决策定义|决策编码/);
      await expect(page.locator('table').first()).toBeVisible({ timeout: 15_000 });
    },
  },
  {
    key: 'decision-tables',
    path: '/p/decisionops_tables',
    assertReady: async (page) => {
      await waitForDynamicPageLoad(page);
      await expect(
        page.getByTestId('decision-table-workbench-block').or(page.locator('table').first()).first(),
      ).toBeVisible({ timeout: 15_000 });
      await expect(page.locator('main').first()).toContainText(/决策表|决策编码/);
    },
  },
  {
    key: 'condition-fragments',
    path: '/p/decisionops_condition_fragments',
    assertReady: async (page) => {
      await waitForDynamicPageLoad(page);
      await expect(page.getByTestId('condition-fragment-library')).toBeVisible({ timeout: 15_000 });
      await expect(page.locator('main').first()).toContainText(/条件片段|片段/);
    },
  },
  {
    key: 'event-policies',
    path: '/p/decisionops_event_policies',
    assertReady: async (page) => {
      await waitForDynamicPageLoad(page);
      await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
      await expect(page.locator('main').first()).toContainText(/事件策略|新建策略/);
    },
  },
  {
    key: 'execution-logs',
    path: '/p/decisionops_execution_logs',
    assertReady: async (page) => {
      await waitForDynamicPageLoad(page);
      await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 15_000 });
      await expect(page.locator('main').first()).toContainText('执行日志');
    },
  },
  {
    key: 'sla-config',
    path: '/p/sla_config',
    assertReady: async (page) => {
      await waitForDynamicPageLoad(page);
      const main = page.locator('main').first();
      await expect(main).toContainText(/SLA 配置|目标类型|截止模式/);
      await expect(page.locator('table').first()).toBeVisible({ timeout: 15_000 });
    },
  },
  {
    key: 'sla-monitor',
    path: '/bpm/sla-monitor',
    assertReady: async (page) => {
      await expect(page.getByTestId('sla-dashboard-configs')).toBeVisible({ timeout: 15_000 });
      await expect(page.getByTestId('sla-dashboard-active-records')).toBeVisible({ timeout: 15_000 });
      await expect(page.getByTestId('sla-strategy-chain')).toBeVisible({ timeout: 15_000 });
      await expect(page.locator('main').first()).toContainText('SLA 监控');
      await expect(page.locator('main').first()).not.toContainText('SLA Monitor');
    },
  },
  {
    key: 'bpm-process-management',
    path: '/p/bpm_process_management',
    assertReady: async (page) => {
      await waitForDynamicPageLoad(page);
      const main = page.locator('main').first();
      await expect(main).toContainText(/流程标识|流程名称|流程定义/);
      await expect(page.locator('table').first()).toBeVisible({ timeout: 15_000 });
    },
  },
];

test.use({ storageState: { cookies: [], origins: [] } });
test.describe.configure({ timeout: 180_000 });

async function assertSidebarContainsRuleCenterEntries(page: Page) {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  for (const entry of ENTRIES) {
    const href = entry.href ?? entry.path;
    await expect(nav.locator(`a[href="${href}"]`).first(), `missing sidebar href ${href}`).toBeAttached({
      timeout: 10_000,
    });
  }
}

async function assertNoFatalOrRawFallback(page: Page, entry: EntrySpec, viewport: ViewportSpec) {
  const body = page.locator('body');
  await expect(body, `${entry.key}/${viewport.key} should not crash`).not.toContainText(
    /Oops|Cannot read properties|TypeError|ReferenceError|Page Unavailable|Access forbidden|Unauthorized/i,
  );
  await expect(body, `${entry.key}/${viewport.key} should not show raw i18n keys`).not.toContainText(
    /\$i18n:|menu\.[a-z0-9_.-]+|model\.[a-z0-9_.-]+\.label/i,
  );
}

async function assertNoPageHorizontalOverflow(page: Page, entry: EntrySpec, viewport: ViewportSpec) {
  const metrics = await page.evaluate(() => ({
    width: window.innerWidth,
    documentScrollWidth: document.documentElement.scrollWidth,
    bodyScrollWidth: document.body.scrollWidth,
  }));
  const overflow = Math.max(metrics.documentScrollWidth, metrics.bodyScrollWidth) - metrics.width;
  expect(overflow, `${entry.key}/${viewport.key} page horizontal overflow`).toBeLessThanOrEqual(2);
}

async function captureEntry(page: Page, testInfo: TestInfo, entry: EntrySpec, viewport: ViewportSpec) {
  await page.screenshot({
    path: testInfo.outputPath(`rule-center-main-entry-${entry.key}-${viewport.key}.png`),
    fullPage: true,
  });
}

test('RC-UX-01: rule center main entries stay usable in desktop and compact Chinese layouts @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);
  await assertSidebarContainsRuleCenterEntries(page);

  for (const viewport of VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });

    for (const entry of ENTRIES) {
      await page.goto(entry.path, { waitUntil: 'domcontentloaded' });
      await expect(page).toHaveURL(new RegExp(`${entry.path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?:$|[?#])`), {
        timeout: 15_000,
      });
      await entry.assertReady(page);
      await assertNoFatalOrRawFallback(page, entry, viewport);
      await assertNoPageHorizontalOverflow(page, entry, viewport);
      await captureEntry(page, testInfo, entry, viewport);
    }
  }
});
