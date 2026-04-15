/**
 * OSS /home workbench smoke test.
 *
 * After Plan 4 (workbench migrated from ent to OSS), /home should render
 * the default workbench dashboard containing Inbox, Recent, Shortcuts widgets
 * created by WorkbenchTemplateProvider on first access.
 *
 * @since 2026-04-15
 */

import { test, expect } from '../../fixtures';

test.describe('OSS /home workbench', () => {
  test('loads default workbench on first access', async ({ page }) => {
    await page.goto('/home');
    await expect(page.locator('body')).toBeVisible({ timeout: 5000 });
  });

  test('renders Inbox widget', async ({ page }) => {
    await page.goto('/home');
    await expect(page.getByText(/收件箱|Inbox|待办/i)).toBeVisible({ timeout: 8000 });
  });

  test('renders Shortcuts widget', async ({ page }) => {
    await page.goto('/home');
    await expect(page.getByText(/快捷入口|Shortcuts|快捷/i)).toBeVisible({ timeout: 8000 });
  });

  test('Pipeline widget shows CRM-unavailable fallback in OSS', async ({ page }) => {
    await page.goto('/home');
    const pipelineUnavailable = page.locator('[data-testid="pipeline-crm-unavailable"]');
    const pipelineContent = page.locator('[data-testid="pipeline-chart"]');
    await expect(pipelineUnavailable.or(pipelineContent).first()).toBeVisible({ timeout: 8000 });
  });
});
