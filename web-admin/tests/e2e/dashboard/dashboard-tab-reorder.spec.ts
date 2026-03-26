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

const BASE_URL = 'http://localhost:5173';
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
    Buffer.from(decodeURIComponent(sessionCookie.value).split('.')[0], 'base64').toString()
  );
  return decoded.jwtToken;
}

/**
 * Helper: create a dashboard via API
 */
async function createDashboardViaApi(
  page: import('@playwright/test').Page,
  title: string
): Promise<{ pid: string; code: string }> {
  const token = await getToken(page);
  const resp = await page.request.post(`${BASE_URL}/api/dashboards`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: { title, scope: 'global' },
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
  pid: string
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
  pid: string
): Promise<void> {
  const token = await getToken(page);
  await page.request.delete(`${BASE_URL}/api/dashboards/${pid}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

/**
 * Helper: clear user preference for dashboard tab order
 */
async function clearTabOrderPreference(
  page: import('@playwright/test').Page
): Promise<void> {
  const token = await getToken(page);
  await page.request.put(`${BASE_URL}/api/user-preferences/dashboard_tab_order`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: { value: null },
  });
}

/**
 * Helper: set dashboard tab order preference by dashboard codes
 */
async function setTabOrderPreference(
  page: import('@playwright/test').Page,
  codes: string[]
): Promise<void> {
  const token = await getToken(page);
  const resp = await page.request.put(`${BASE_URL}/api/user-preferences/dashboard_tab_order`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: { value: codes },
  });
  expect(resp.ok(), `Set tab order preference failed: ${resp.status()}`).toBeTruthy();
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
 * Helper: perform a mouse drag from first tab to second tab position
 */
async function dragFirstToSecond(page: import('@playwright/test').Page): Promise<void> {
  const tabs = page.locator(TABS_SEL);
  const firstTab = tabs.first();
  const secondTab = tabs.nth(1);

  const firstBox = await firstTab.boundingBox();
  const secondBox = await secondTab.boundingBox();
  expect(firstBox).not.toBeNull();
  expect(secondBox).not.toBeNull();

  // Mouse drag with steps to trigger PointerSensor's distance threshold (5px)
  await page.mouse.move(firstBox!.x + firstBox!.width / 2, firstBox!.y + firstBox!.height / 2);
  await page.mouse.down();
  await page.mouse.move(
    secondBox!.x + secondBox!.width / 2,
    secondBox!.y + secondBox!.height / 2,
    { steps: 10 }
  );
  await page.mouse.up();
}

test.describe('Dashboard Tab Reorder', () => {
  test.describe.configure({ mode: 'serial' });
  const createdPids: string[] = [];
  let dashALabel = '';
  let dashBLabel = '';
  let dashACode = '';
  let dashBCode = '';

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await context.newPage();

    // Create 2 published dashboards with unique titles for this test run
    dashALabel = `DTR-A-${TS}`;
    const dashA = await createDashboardViaApi(page, dashALabel);
    dashACode = dashA.code;
    await publishDashboardViaApi(page, dashA.pid);
    createdPids.push(dashA.pid);

    dashBLabel = `DTR-B-${TS}`;
    const dashB = await createDashboardViaApi(page, dashBLabel);
    dashBCode = dashB.code;
    await publishDashboardViaApi(page, dashB.pid);
    createdPids.push(dashB.pid);

    // Put freshly created dashboards at the top for deterministic drag targets.
    await setTabOrderPreference(page, [dashACode, dashBCode]);

    await page.close();
    await context.close();
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await context.newPage();

    for (const pid of createdPids) {
      await deleteDashboardViaApi(page, pid).catch(() => {});
    }
    await clearTabOrderPreference(page).catch(() => {});

    await page.close();
    await context.close();
  });

  /**
   * DTR-001: Dashboard viewer page loads with multiple tabs
   */
  test('DTR-001: page loads with multiple tabs', async ({ page }) => {
    await gotoDashboards(page);

    const tabs = page.locator(TABS_SEL);
    const count = await tabs.count();

    // Should have at least 2 tabs
    expect(count).toBeGreaterThanOrEqual(2);

    // One tab should be active (has blue border)
    const activeTab = page.locator(`${TABS_SEL}.border-blue-500`);
    await expect(activeTab).toBeVisible();
  });

  /**
   * DTR-002: Tabs can be dragged to reorder
   */
  test('DTR-002: tabs can be dragged to reorder', async ({ page }) => {
    // Place A/B at top so first two tabs are deterministic.
    await setTabOrderPreference(page, [dashACode, dashBCode]);

    await gotoDashboards(page);

    const tabs = page.locator(TABS_SEL);
    expect(await tabs.count()).toBeGreaterThanOrEqual(2);
    expect((await tabs.first().textContent())?.trim()).toBe(dashALabel);
    expect((await tabs.nth(1).textContent())?.trim()).toBe(dashBLabel);

    // Drag first tab to second position
    await dragFirstToSecond(page);

    // After drag: B should now be first
    await expect
      .poll(async () => ((await tabs.first().textContent()) || '').trim(), { timeout: 5000 })
      .toBe(dashBLabel);
  });

  /**
   * DTR-003: Tab order persists after page reload
   */
  test('DTR-003: tab order persists after reload', async ({ page }) => {
    // Place A/B at top so first two tabs are deterministic.
    await setTabOrderPreference(page, [dashACode, dashBCode]);

    await gotoDashboards(page);

    const tabs = page.locator(TABS_SEL);
    expect(await tabs.count()).toBeGreaterThanOrEqual(2);
    expect((await tabs.first().textContent())?.trim()).toBe(dashALabel);
    expect((await tabs.nth(1).textContent())?.trim()).toBe(dashBLabel);

    // Drag first tab to second position (A after B)
    await dragFirstToSecond(page);

    // Wait for persistence API call to complete
    await page.waitForResponse(
      (r) => r.url().includes('/api/user-preferences/dashboard_tab_order') && r.request().method().toLowerCase() === 'put',
      { timeout: 5000 }
    );

    // Reload the page
    await page.reload();
    await page.locator(TABS_SEL).first().waitFor({ state: 'visible', timeout: 10000 });

    // After reload: B should still be first and A second (order persisted)
    const afterReloadFirst = (await tabs.first().textContent())?.trim();
    const afterReloadSecond = (await tabs.nth(1).textContent())?.trim();
    expect(afterReloadFirst).toBe(dashBLabel);
    expect(afterReloadSecond).toBe(dashALabel);
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

    // Check for at least 2 tabs (hint only appears when > 1 tab)
    const tabCount = await page.locator(TABS_SEL).count();
    if (tabCount < 2) {
      throw new Error(String('Need at least 2 tabs for drag hint'))
      return;
    }

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
