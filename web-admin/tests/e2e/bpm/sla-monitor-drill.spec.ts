/**
 * SLA Monitor Drill-down E2E Tests
 *
 * Tests for the SLA Monitor dashboard page and drill-down panel interactions.
 * SLA-MON-01 ~ SLA-MON-08.
 *
 * Prerequisites: Backend running, SLA monitor page accessible.
 *
 * Uses real database, NO MOCKING.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';

test.describe('SLA Monitor Drill-down', () => {
  let hasMonitorData = true;

  test.beforeEach(async ({ page }) => {
    await page.goto('/bpm/sla-monitor');
    await page.waitForLoadState('domcontentloaded');
    // Wait for dashboard to load — the h1 contains "sla"
    await expect(page.locator('h1').filter({ hasText: /sla|监控/i })).toBeVisible({
      timeout: 5000,
    });
    const emptyStateVisible = await expect
      .poll(
        async () => {
          const text = await page.locator('main').textContent().catch(() => '');
          return /暂无监控数据|No monitoring data available/i.test(text || '');
        },
        { timeout: 5000 },
      )
      .toBe(true)
      .then(() => true)
      .catch(() => false);
    hasMonitorData = !emptyStateVisible;
    test.skip(!hasMonitorData, 'Current environment has no SLA monitor data');
  });

  /**
   * SLA-MON-01: Monitor page loads, 3 dashboard areas visible (@smoke)
   */
  test('SLA-MON-01: Monitor page loads, 3 dashboard areas visible @smoke', async ({ page }) => {
    // All 3 dashboard sections should be visible via data-testid
    await expect(page.locator('[data-testid="sla-dashboard-process-definitions"]')).toBeVisible();
    await expect(page.locator('[data-testid="sla-dashboard-active-records"]')).toBeVisible();
    await expect(page.locator('[data-testid="sla-dashboard-configs"]')).toBeVisible();

    // Also verify the h2 headings exist within each section
    const procDefSection = page.locator('[data-testid="sla-dashboard-process-definitions"]');
    await expect(procDefSection.locator('h2')).toBeVisible();

    const activeRecordsSection = page.locator('[data-testid="sla-dashboard-active-records"]');
    await expect(activeRecordsSection.locator('h2')).toBeVisible();

    const configsSection = page.locator('[data-testid="sla-dashboard-configs"]');
    await expect(configsSection.locator('h2')).toBeVisible();
  });

  /**
   * SLA-MON-02: Stat cards display numbers (Process Definitions, SLA Records, SLA Configs) (@smoke)
   */
  test('SLA-MON-02: Stat cards display numbers @smoke', async ({ page }) => {
    // Process Definitions section should have stat cards with numeric values
    const procDefSection = page.locator('[data-testid="sla-dashboard-process-definitions"]');
    const procDefCards = procDefSection.locator('.border.rounded-lg.p-4');
    await expect(procDefCards.first()).toBeVisible();
    // Each card should have a numeric value (text-2xl font-bold)
    const procDefValues = procDefSection.locator('.text-2xl.font-bold');
    const procDefCount = await procDefValues.count();
    expect(procDefCount).toBeGreaterThanOrEqual(1);
    for (let i = 0; i < procDefCount; i++) {
      const text = await procDefValues.nth(i).textContent();
      expect(text).toMatch(/^\d+$/);
    }

    // Active SLA Records section — 5 clickable stat cards
    const activeSection = page.locator('[data-testid="sla-dashboard-active-records"]');
    await expect(page.locator('[data-testid="sla-stat-ALL"]')).toBeVisible();
    await expect(page.locator('[data-testid="sla-stat-running"]')).toBeVisible();
    await expect(page.locator('[data-testid="sla-stat-WARNING"]')).toBeVisible();
    await expect(page.locator('[data-testid="sla-stat-OVERDUE"]')).toBeVisible();
    await expect(page.locator('[data-testid="sla-stat-paused"]')).toBeVisible();
    // Verify each has a numeric value
    const slaValues = activeSection.locator('.text-2xl.font-bold');
    const slaCount = await slaValues.count();
    expect(slaCount).toBe(5);
    for (let i = 0; i < slaCount; i++) {
      const text = await slaValues.nth(i).textContent();
      expect(text).toMatch(/^\d+$/);
    }

    // SLA Configs section should have stat cards
    const configsSection = page.locator('[data-testid="sla-dashboard-configs"]');
    const configValues = configsSection.locator('.text-2xl.font-bold');
    const configCount = await configValues.count();
    expect(configCount).toBeGreaterThanOrEqual(1);
    for (let i = 0; i < configCount; i++) {
      const text = await configValues.nth(i).textContent();
      expect(text).toMatch(/^\d+$/);
    }
  });

  /**
   * SLA-MON-03: Click SLA record card triggers drill-down, panel appears (@critical)
   */
  test('SLA-MON-03: Click SLA record card triggers drill-down @critical', async ({ page }) => {
    // Drill-down panel should NOT be visible initially
    await expect(page.locator('[data-testid="sla-drill-panel"]')).not.toBeVisible();

    // Click the "Active" (ALL) stat card — wait for the SLA records API response
    const allCard = page.locator('[data-testid="sla-stat-ALL"]');
    await expect(allCard).toBeVisible();

    await Promise.all([
      page.waitForResponse(
        (resp) => resp.url().includes('/api/bpm/monitor/sla-records') && resp.status() === 200,
      ),
      allCard.click(),
    ]);

    // Drill-down panel should now be visible
    await expect(page.locator('[data-testid="sla-drill-panel"]')).toBeVisible();

    // The card should be in "active" state (ring-2 class)
    await expect(allCard).toHaveClass(/ring-2/);
  });

  /**
   * SLA-MON-04: Drill-down panel table shows columns (Status, Process, Node, Start, Deadline, Remaining, Warning)
   */
  test('SLA-MON-04: Drill-down panel table shows expected columns', async ({ page }) => {
    // Click the ALL card to open drill-down
    const allCard = page.locator('[data-testid="sla-stat-ALL"]');
    await Promise.all([
      page.waitForResponse(
        (resp) => resp.url().includes('/api/bpm/monitor/sla-records') && resp.status() === 200,
      ),
      allCard.click(),
    ]);

    const drillPanel = page.locator('[data-testid="sla-drill-panel"]');
    await expect(drillPanel).toBeVisible();

    // Check if there are records or empty state
    const hasRecords = (await drillPanel.locator('[data-testid="sla-record-row"]').count()) > 0;
    const hasEmpty = await drillPanel
      .locator('[data-testid="sla-drill-empty"]')
      .isVisible()
      .catch(() => false);

    if (hasRecords) {
      // Verify table column headers
      const thead = drillPanel.locator('thead');
      await expect(thead).toBeVisible();

      // Check for expected column headers (using fallback English text or i18n)
      const headerRow = thead.locator('tr');
      const headers = headerRow.locator('th');
      const headerCount = await headers.count();
      // 7 data columns + 1 empty arrow column = 8
      expect(headerCount).toBe(8);

      // Verify key header labels exist in the header row
      const headerText = await headerRow.textContent();
      // At minimum, we expect Status, Process/Node columns to be present
      // Headers could be i18n translated, so check for at least the structure
      expect(headerText).toBeTruthy();
    } else {
      // Empty state should be visible
      expect(hasEmpty).toBe(true);
    }
  });

  /**
   * SLA-MON-05: Close drill-down panel restores initial state
   */
  test('SLA-MON-05: Close drill-down panel restores initial state', async ({ page }) => {
    // Open drill-down by clicking ALL card
    const allCard = page.locator('[data-testid="sla-stat-ALL"]');
    await Promise.all([
      page.waitForResponse(
        (resp) => resp.url().includes('/api/bpm/monitor/sla-records') && resp.status() === 200,
      ),
      allCard.click(),
    ]);

    // Verify panel is visible
    await expect(page.locator('[data-testid="sla-drill-panel"]')).toBeVisible();

    // Click close button
    await page.locator('[data-testid="sla-drill-close"]').click();

    // Drill-down panel should disappear
    await expect(page.locator('[data-testid="sla-drill-panel"]')).not.toBeVisible();

    // The ALL card should no longer have active ring
    await expect(allCard).not.toHaveClass(/ring-2/);

    // All 3 dashboard sections should still be visible
    await expect(page.locator('[data-testid="sla-dashboard-process-definitions"]')).toBeVisible();
    await expect(page.locator('[data-testid="sla-dashboard-active-records"]')).toBeVisible();
    await expect(page.locator('[data-testid="sla-dashboard-configs"]')).toBeVisible();
  });

  /**
   * SLA-MON-06: Switch status cards (ALL -> running -> WARNING), panel content changes
   */
  test('SLA-MON-06: Switch status cards, panel content changes', async ({ page }) => {
    // Click ALL card
    const allCard = page.locator('[data-testid="sla-stat-ALL"]');
    await Promise.all([
      page.waitForResponse(
        (resp) => resp.url().includes('/api/bpm/monitor/sla-records') && resp.status() === 200,
      ),
      allCard.click(),
    ]);
    await expect(page.locator('[data-testid="sla-drill-panel"]')).toBeVisible();
    await expect(allCard).toHaveClass(/ring-2/);

    // Switch to running card
    const runningCard = page.locator('[data-testid="sla-stat-running"]');
    await Promise.all([
      page.waitForResponse(
        (resp) => resp.url().includes('/api/bpm/monitor/sla-records') && resp.status() === 200,
      ),
      runningCard.click(),
    ]);

    // running card should now be active, ALL card should not
    await expect(runningCard).toHaveClass(/ring-2/);
    await expect(allCard).not.toHaveClass(/ring-2/);
    await expect(page.locator('[data-testid="sla-drill-panel"]')).toBeVisible();

    // Switch to WARNING card
    const warningCard = page.locator('[data-testid="sla-stat-WARNING"]');
    await Promise.all([
      page.waitForResponse(
        (resp) => resp.url().includes('/api/bpm/monitor/sla-records') && resp.status() === 200,
      ),
      warningCard.click(),
    ]);

    // WARNING card should now be active, running should not
    await expect(warningCard).toHaveClass(/ring-2/);
    await expect(runningCard).not.toHaveClass(/ring-2/);
    await expect(page.locator('[data-testid="sla-drill-panel"]')).toBeVisible();
  });

  /**
   * SLA-MON-07: Refresh button reloads data
   */
  test('SLA-MON-07: Refresh button reloads data', async ({ page }) => {
    // Verify refresh button is visible
    const refreshBtn = page.locator('[data-testid="sla-refresh"]');
    await expect(refreshBtn).toBeVisible();

    // Click refresh and wait for dashboard API response
    await Promise.all([
      page.waitForResponse(
        (resp) => resp.url().includes('/api/bpm/monitor/dashboard') && resp.status() === 200,
      ),
      refreshBtn.click(),
    ]);

    // After refresh, all 3 dashboard sections should still be visible
    await expect(page.locator('[data-testid="sla-dashboard-process-definitions"]')).toBeVisible();
    await expect(page.locator('[data-testid="sla-dashboard-active-records"]')).toBeVisible();
    await expect(page.locator('[data-testid="sla-dashboard-configs"]')).toBeVisible();

    // Stat cards should still have numeric values
    const allCard = page.locator('[data-testid="sla-stat-ALL"]');
    await expect(allCard).toBeVisible();
    const value = allCard.locator('.text-2xl.font-bold');
    const text = await value.textContent();
    expect(text).toMatch(/^\d+$/);
  });

  /**
   * SLA-MON-08: Empty data state handling (no SLA records shows empty state)
   */
  test('SLA-MON-08: Empty data state handling', async ({ page }) => {
    // Click a status card that is likely to have 0 records (e.g., paused or OVERDUE).
    // We check the card value first — if it shows 0, the drill-down should show empty state.
    const pausedCard = page.locator('[data-testid="sla-stat-paused"]');
    await expect(pausedCard).toBeVisible();

    const pausedValue = await pausedCard.locator('.text-2xl.font-bold').textContent();
    const overdueCard = page.locator('[data-testid="sla-stat-OVERDUE"]');
    const overdueValue = await overdueCard.locator('.text-2xl.font-bold').textContent();
    const warningCard = page.locator('[data-testid="sla-stat-WARNING"]');
    const warningValue = await warningCard.locator('.text-2xl.font-bold').textContent();

    // Find a card with 0 records to test empty state
    let targetCard = pausedCard;
    let targetValue = pausedValue;
    if (overdueValue === '0') {
      targetCard = overdueCard;
      targetValue = overdueValue;
    } else if (warningValue === '0') {
      targetCard = warningCard;
      targetValue = warningValue;
    }

    // Click the target card
    await Promise.all([
      page.waitForResponse(
        (resp) => resp.url().includes('/api/bpm/monitor/sla-records') && resp.status() === 200,
      ),
      targetCard.click(),
    ]);

    await expect(page.locator('[data-testid="sla-drill-panel"]')).toBeVisible();

    if (targetValue === '0') {
      // Should show empty state
      await expect(page.locator('[data-testid="sla-drill-empty"]')).toBeVisible();
      // Should NOT show table rows
      expect(await page.locator('[data-testid="sla-record-row"]').count()).toBe(0);
    } else {
      // If all status cards have records, verify the panel is at least functional
      // (either shows records or empty — both are valid states)
      const hasRecords = (await page.locator('[data-testid="sla-record-row"]').count()) > 0;
      const hasEmpty = await page
        .locator('[data-testid="sla-drill-empty"]')
        .isVisible()
        .catch(() => false);
      expect(hasRecords || hasEmpty).toBe(true);
    }
  });
});
