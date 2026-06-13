/**
 * Dashboard Tab Reorder E2E Tests
 *
 * Tests for the draggable dashboard tab feature:
 * - DTR-001: Dashboard viewer page loads with tabs
 * - DTR-002: Tabs can be dragged to reorder
 * - DTR-003: Tab order persists after page reload
 * - DTR-004: First-time drag hint appears and auto-dismisses
 *
 * Uses real database + API, NO MOCKING.
 * Requires at least 2 published dashboards.
 *
 * @since 7.1.0
 */

import { test, expect } from '../../fixtures';
import { BASE_URL } from '../../helpers/environments';

// Derive from PLAYWRIGHT_BASE_URL so isolated docker stacks (e.g. GA-E2E on
// :5174) work without a hardcoded port. Falls back to the default 5173 used
// by the host-mode dev stack.
const DASHBOARDS_PATH = '/dashboards';
const TABS_SEL = 'nav[aria-label="Dashboard tabs"] button.cursor-grab';
const TS = Date.now();

/**
 * Helper: get JWT token from page cookies for direct API calls
 */
async function getToken(page: import('@playwright/test').Page): Promise<string> {
  const cookies = await page.context().cookies();
  const sessionCookie = cookies.find((c) => c.name === '__session');
  if (!sessionCookie) throw new Error('No __session cookie found');
  const decoded = JSON.parse(
    Buffer.from(decodeURIComponent(sessionCookie.value).split('.')[0], 'base64').toString(),
  );
  return decoded.jwtToken;
}

/**
 * Helper: create a dashboard via API
 */
async function createDashboardViaApi(
  page: import('@playwright/test').Page,
  title: string,
): Promise<{ pid: string; code: string }> {
  const token = await getToken(page);
  const resp = await page.request.post(`${BASE_URL}/api/dashboards`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: { title, scope: 'personal' },
  });
  expect(resp.ok(), `Create dashboard failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  return { pid: body.data.pid, code: body.data.code };
}

/**
 * Helper: publish a dashboard via API
 */
async function publishDashboardViaApi(
  page: import('@playwright/test').Page,
  pid: string,
): Promise<void> {
  const token = await getToken(page);
  const resp = await page.request.post(`${BASE_URL}/api/dashboards/${pid}/publish`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(resp.ok(), `Publish dashboard failed: ${resp.status()}`).toBeTruthy();
}

/**
 * Helper: delete a dashboard via API
 */
async function deleteDashboardViaApi(
  page: import('@playwright/test').Page,
  pid: string,
): Promise<void> {
  const token = await getToken(page);
  await page.request.delete(`${BASE_URL}/api/dashboards/${pid}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

/**
 * Helper: get tab labels from the tab bar
 */
async function getTabLabels(page: import('@playwright/test').Page): Promise<string[]> {
  const tabs = page.locator(TABS_SEL);
  const count = await tabs.count();
  const labels: string[] = [];
  for (let i = 0; i < count; i++) {
    const text = await tabs.nth(i).textContent();
    labels.push(text?.trim() ?? '');
  }
  return labels;
}

function requireTabIndex(labels: string[], label: string): number {
  const index = labels.indexOf(label);
  if (index === -1) {
    throw new Error(`Expected tab "${label}" in tab bar: ${labels.join(' | ')}`);
  }
  return index;
}

function expectTabBefore(labels: string[], firstLabel: string, secondLabel: string): void {
  const firstIndex = requireTabIndex(labels, firstLabel);
  const secondIndex = requireTabIndex(labels, secondLabel);
  expect(
    firstIndex < secondIndex,
    `Expected "${firstLabel}" before "${secondLabel}" in tab bar: ${labels.join(' | ')}`,
  ).toBeTruthy();
}

/**
 * Helper: navigate to dashboards page and wait for tabs to load
 */
async function gotoDashboards(page: import('@playwright/test').Page): Promise<void> {
  await page.goto(DASHBOARDS_PATH);
  await page.waitForLoadState('domcontentloaded');
  // Wait for tabs to appear
  await page.locator(TABS_SEL).first().waitFor({ state: 'visible', timeout: 10000 });
}

/**
 * Helper: perform a mouse drag from one tab to another tab position
 */
async function dragTabToTab(
  page: import('@playwright/test').Page,
  sourceLabel: string,
  targetLabel: string,
): Promise<void> {
  const tabs = page.getByRole('navigation', { name: 'Dashboard tabs' });
  const sourceTab = tabs.getByRole('button', { name: sourceLabel, exact: true });
  const targetTab = tabs.getByRole('button', { name: targetLabel, exact: true });

  await sourceTab.scrollIntoViewIfNeeded();
  await targetTab.scrollIntoViewIfNeeded();

  const sourceBox = await sourceTab.boundingBox();
  const targetBox = await targetTab.boundingBox();
  expect(sourceBox).not.toBeNull();
  expect(targetBox).not.toBeNull();

  // Mouse drag with steps to trigger PointerSensor's distance threshold (5px)
  await page.mouse.move(sourceBox!.x + sourceBox!.width / 2, sourceBox!.y + sourceBox!.height / 2);
  await page.mouse.down();
  await page.mouse.move(targetBox!.x + targetBox!.width / 2, targetBox!.y + targetBox!.height / 2, {
    steps: 10,
  });
  await page.mouse.up();
}

async function dragCreatedTabsToReverseRelativeOrder(
  page: import('@playwright/test').Page,
  firstLabel: string,
  secondLabel: string,
): Promise<{ expectedFirst: string; expectedSecond: string }> {
  const labels = await getTabLabels(page);
  const firstIndex = requireTabIndex(labels, firstLabel);
  const secondIndex = requireTabIndex(labels, secondLabel);
  const sourceLabel = firstIndex < secondIndex ? firstLabel : secondLabel;
  const targetLabel = firstIndex < secondIndex ? secondLabel : firstLabel;

  await dragTabToTab(page, sourceLabel, targetLabel);
  return { expectedFirst: targetLabel, expectedSecond: sourceLabel };
}

test.describe('Dashboard Tab Reorder', () => {
  test.describe.configure({ mode: 'serial' });
  const createdPids: string[] = [];
  let dashALabel = '';
  let dashBLabel = '';

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();

    // Create 2 published dashboards with unique titles for this test run
    dashALabel = `DTR-A-${TS}`;
    const dashA = await createDashboardViaApi(page, dashALabel);
    await publishDashboardViaApi(page, dashA.pid);
    createdPids.push(dashA.pid);

    dashBLabel = `DTR-B-${TS}`;
    const dashB = await createDashboardViaApi(page, dashBLabel);
    await publishDashboardViaApi(page, dashB.pid);
    createdPids.push(dashB.pid);

    await page.close();
    await context.close();
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();

    for (const pid of createdPids) {
      await deleteDashboardViaApi(page, pid).catch(() => {});
    }

    await page.close();
    await context.close();
  });

  /**
   * DTR-001: Dashboard viewer page loads with multiple tabs
   */
  test('DTR-001: page loads with multiple tabs', async ({ page }) => {
    await gotoDashboards(page);

    await expect(page.getByRole('button', { name: dashALabel, exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: dashBLabel, exact: true })).toBeVisible();

    // One tab should be active (has blue border)
    const activeTab = page.locator(`${TABS_SEL}.border-blue-500`);
    await expect(activeTab).toBeVisible();
  });

  /**
   * DTR-002: Tabs can be dragged to reorder
   */
  test('DTR-002: tabs can be dragged to reorder', async ({ page }) => {
    await gotoDashboards(page);

    const { expectedFirst, expectedSecond } = await dragCreatedTabsToReverseRelativeOrder(
      page,
      dashALabel,
      dashBLabel,
    );

    await expect
      .poll(async () => {
        const labels = await getTabLabels(page);
        const firstIndex = requireTabIndex(labels, expectedFirst);
        const secondIndex = requireTabIndex(labels, expectedSecond);
        return firstIndex < secondIndex;
      }, { timeout: 5000 })
      .toBeTruthy();
  });

  /**
   * DTR-003: Tab order persists after page reload
   */
  test('DTR-003: tab order persists after reload', async ({ page }) => {
    await gotoDashboards(page);

    const preferenceWrite = page.waitForResponse(
      (r) =>
        r.url().includes('/api/user-preferences/dashboard_tab_order') &&
        r.request().method().toLowerCase() === 'put',
      { timeout: 5000 },
    );
    const { expectedFirst, expectedSecond } = await dragCreatedTabsToReverseRelativeOrder(
      page,
      dashALabel,
      dashBLabel,
    );
    await preferenceWrite;

    // Reload the page
    await page.reload();
    await page.locator(TABS_SEL).first().waitFor({ state: 'visible', timeout: 10000 });

    expectTabBefore(await getTabLabels(page), expectedFirst, expectedSecond);
  });

  /**
   * DTR-004: First-time drag hint appears
   */
  test('DTR-004: drag hint appears for first-time users', async ({ page }) => {
    // Clear localStorage hint flag before test
    await page.goto(DASHBOARDS_PATH);
    await page.evaluate(() => localStorage.removeItem('dashboard_drag_hint_shown'));

    // Reload to trigger hint
    await page.reload();
    await page.locator(TABS_SEL).first().waitFor({ state: 'visible', timeout: 10000 });

    await expect(page.getByRole('button', { name: dashALabel, exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: dashBLabel, exact: true })).toBeVisible();

    // Hint should appear after ~800ms delay
    const hint = page.locator('[data-testid="drag-hint"]');
    await expect(hint).toBeVisible({ timeout: 3000 });

    // Hint should auto-dismiss after ~4s
    await expect(hint).toBeHidden({ timeout: 6000 });

    // Verify localStorage flag was set
    const flagSet = await page.evaluate(() => localStorage.getItem('dashboard_drag_hint_shown'));
    expect(flagSet).toBe('1');
  });
});
