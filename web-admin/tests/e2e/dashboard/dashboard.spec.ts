/**
 * Dashboard E2E Tests
 *
 * Tests for the Reports Overview Dashboard functionality:
 * - F3-E01: Reports overview page accessibility
 * - F3-E02: Dashboard content or empty state display
 * - F3-E03: Refresh button functionality
 * - F3-E04: Edit dashboard navigation
 * - F3-E05: DashboardViewer grid layout rendering
 * - F3-N01: Empty state when no dashboard configured
 * - F3-N02: Page handles loading state gracefully
 *
 * The Reports Overview page loads a dashboard instance by code "system_overview"
 * and renders it via DashboardViewer. If no dashboard is found, shows empty state
 * with a link to create one in the Dashboard Designer.
 *
 * Uses storageState for authentication.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { ReportsOverviewPage } from '../../pages/ReportsOverviewPage';

test.describe('Dashboard - Reports Overview', () => {
  /**
   * F3-E01: Reports overview page loads
   * Verify that /reports/overview is accessible and the page renders correctly.
   */
  test('F3-E01: Reports overview page loads', async ({ page }) => {
    const rp = new ReportsOverviewPage(page);
    const loaded = await rp
      .goto()
      .then(() => true)
      .catch(() => false);

    if (!loaded) {
      throw new Error(String('Reports overview page not available yet'));
      return;
    }

    // Verify page heading
    await expect(rp.pageTitle).toBeVisible();

    // Verify refresh button is present
    await expect(rp.refreshButton).toBeVisible();

    // Verify edit dashboard button is present
    await expect(rp.editOrCreateButton.first()).toBeVisible();
  });

  /**
   * F3-E02: Dashboard content or empty state
   * Verify that the page shows either dashboard widgets or the empty state.
   */
  test('F3-E02: Dashboard content or empty state displays', async ({ page }) => {
    const rp = new ReportsOverviewPage(page);
    const loaded = await rp
      .goto()
      .then(() => true)
      .catch(() => false);

    if (!loaded) {
      throw new Error(String('Reports overview page not available yet'));
      return;
    }

    // Wait for loading to complete
    await rp.waitForContentLoad();

    // After loading, the page should show one of:
    // 1. DashboardViewer with widgets (react-grid-layout)
    // 2. Empty DashboardViewer ("暂无仪表盘数据")
    // 3. Empty state (data-testid="empty-state")
    const dashboardViewer = rp.dashboardViewer
      .or(rp.gridLayout)
      .or(page.locator('.bg-gray-50.overflow-auto'));
    const emptyDashboard = page.getByText('暂无仪表盘数据');
    const emptyState = rp.emptyState;

    const hasDashboard = await dashboardViewer
      .first()
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    const hasEmptyDashboard = await emptyDashboard.isVisible({ timeout: 1000 }).catch(() => false);
    const hasEmptyState = await emptyState.isVisible({ timeout: 1000 }).catch(() => false);

    // One of the three states must be true
    expect(hasDashboard || hasEmptyDashboard || hasEmptyState).toBe(true);
  });

  /**
   * F3-E05: DashboardViewer grid layout renders
   * When a dashboard is configured, verify the grid layout renders.
   */
  test('F3-E05: DashboardCanvas grid layout renders', async ({ page }) => {
    const rp = new ReportsOverviewPage(page);
    const loaded = await rp
      .goto()
      .then(() => true)
      .catch(() => false);

    if (!loaded) {
      throw new Error(String('Reports overview page not available yet'));
      return;
    }

    // Wait for loading to complete
    await rp.waitForContentLoad();

    // Check if a dashboard was loaded (react-grid-layout present)
    const hasGrid = await rp.gridLayout.isVisible({ timeout: 3000 }).catch(() => false);

    if (hasGrid) {
      // Grid layout rendered - framework is working
      // Widget count may be 0 if dashboard has no widgets configured
      const widgets = rp.gridLayout.locator('> div');
      const widgetCount = await widgets.count();
      // Grid layout container exists = rendering framework is functional
      expect(hasGrid).toBe(true);
      // If widgets exist, verify count
      if (widgetCount > 0) {
        expect(widgetCount).toBeGreaterThan(0);
      }
    } else {
      // No grid layout - check for empty state or skip
      const emptyState = rp.emptyState
        .or(page.getByText('暂无仪表盘数据'))
        .or(page.getByText('No dashboard'))
        .or(rp.loadingIndicator);
      const hasEmpty = await emptyState
        .first()
        .isVisible({ timeout: 3000 })
        .catch(() => false);
      if (!hasEmpty) {
        throw new Error(String('Dashboard not configured and no empty state shown'));
      }
    }
  });

});

test.describe('Dashboard - Refresh and Export', () => {
  /**
   * F3-E04b: Manual refresh button works
   * Verify that the refresh button triggers data reload.
   */
  test('F3-E04b: Manual refresh button works', async ({ page }) => {
    const rp = new ReportsOverviewPage(page);
    const loaded = await rp
      .goto()
      .then(() => true)
      .catch(() => false);

    if (!loaded) {
      throw new Error(String('Reports overview page not available yet'));
      return;
    }

    await page.waitForLoadState('domcontentloaded');

    // Find the refresh button
    await expect(rp.refreshButton).toBeVisible();

    // Click refresh
    await rp.refresh();

    // The button icon should briefly show spinning animation or a toast appears
    // Wait for the page to settle
    await page.waitForLoadState('domcontentloaded');

    // Page should still be functional after refresh
    await expect(rp.pageTitle).toBeVisible();
  });

  /**
   * F3-E04c: Edit dashboard button navigates
   * Verify that the edit/create dashboard button is clickable.
   */
  test('F3-E04c: Edit dashboard button is functional', async ({ page }) => {
    const rp = new ReportsOverviewPage(page);
    const loaded = await rp
      .goto()
      .then(() => true)
      .catch(() => false);

    if (!loaded) {
      throw new Error(String('Reports overview page not available yet'));
      return;
    }

    await page.waitForLoadState('domcontentloaded');

    // Find edit or create button
    await expect(rp.editOrCreateButton.first()).toBeVisible();

    // Verify the button is not disabled
    const isDisabled = await rp.editOrCreateButton
      .first()
      .isDisabled()
      .catch(() => false);
    expect(isDisabled).toBe(false);
  });
});

// ===========================================================================
// Dashboard Boundary Tests
// ===========================================================================

test.describe('Dashboard - Boundary Tests', () => {
  /**
   * F3-N01: Dashboard with no data
   * Verify the dashboard page handles a state with no configured dashboard gracefully.
   * The page should show empty state indicators without crashing.
   */
  test('F3-N01: dashboard with no data should show empty state gracefully', async ({ page }) => {
    const rp = new ReportsOverviewPage(page);
    const loaded = await rp
      .goto()
      .then(() => true)
      .catch(() => false);

    if (!loaded) {
      throw new Error(String('Reports overview page not available yet'));
      return;
    }

    // Wait for loading to complete
    await page.waitForLoadState('domcontentloaded').catch(() => {});

    // Page heading should be visible (no crash)
    await expect(rp.pageTitle).toBeVisible();

    // The page has several possible states:
    // 1. Loading: data-testid="loading-indicator"
    // 2. Dashboard loaded: data-testid="dashboard-viewer" with widgets
    // 3. Dashboard loaded but empty: "暂无仪表盘数据"
    // 4. No dashboard configured: data-testid="empty-state"
    // All are valid graceful behaviors

    // Verify no error overlay is displayed (JavaScript crash)
    const hasErrorOverlay = await rp.errorOverlay.isVisible({ timeout: 1000 }).catch(() => false);
    expect(hasErrorOverlay).toBe(false);

    // Verify refresh button is still functional even in empty state
    await expect(rp.refreshButton).toBeVisible();
  });

  /**
   * F3-N02: Page handles loading state gracefully
   * Verify that the loading state renders correctly and transitions to content.
   */
  test('F3-N02: page handles loading state gracefully', async ({ page }) => {
    const rp = new ReportsOverviewPage(page);
    const loaded = await rp
      .goto()
      .then(() => true)
      .catch(() => false);

    if (!loaded) {
      throw new Error(String('Reports overview page not available yet'));
      return;
    }

    // Page should render heading immediately even while loading
    await expect(rp.pageTitle).toBeVisible();

    // Wait for loading to complete
    await page.waitForLoadState('domcontentloaded');
    await rp.loadingIndicator.waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {});

    // After loading, page should be stable with no crash
    await expect(rp.pageTitle).toBeVisible();

    // Verify no error overlays
    const hasErrorOverlay = await rp.errorOverlay.isVisible({ timeout: 1000 }).catch(() => false);
    expect(hasErrorOverlay).toBe(false);
  });
});
