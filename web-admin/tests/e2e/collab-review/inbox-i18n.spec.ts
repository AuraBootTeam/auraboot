import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

/**
 * The inbox page must speak the app's language.
 *
 * It used to hardcode English — "Inbox / Refresh / Mark all read / Pending / Title /
 * Type / Status" — while the shell around it (search box, sidebar, notification centre)
 * rendered Chinese. Nothing failed; the page just quietly read as a different product.
 *
 * These assertions are about the *rendered* language, so they would still catch a
 * regression that keeps the i18n keys but stops resolving them.
 */

const SHOTS = process.env.PROBE_SHOT_DIR || '/tmp/collab-probe';
fs.mkdirSync(SHOTS, { recursive: true });

test.describe('inbox localisation', () => {
  test('IB-I18N-1: the page renders in Chinese, not a mix', async ({ page }) => {
    await page.goto('/inbox', { waitUntil: 'domcontentloaded' });
    // Heading is the localised title, not the English one.
    await expect(page.getByRole('heading', { name: '待办事项' })).toBeVisible({ timeout: 15000 });
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(SHOTS, '40-inbox-zh.png'), fullPage: true });

    const main = page.locator('main, [data-testid="unified-inbox-page"]').first();
    const text = await main.innerText();
    fs.writeFileSync(path.join(SHOTS, 'inbox-i18n-text.txt'), text);

    // Toolbar + columns + filters are localised.
    for (const label of ['刷新', '全部标为已读', '待处理', '标题', '类型', '状态', '时间', '操作']) {
      expect(text, `missing localised label: ${label}`).toContain(label);
    }

    // And every English label a zh-CN user used to see is gone. Listing them one by one
    // is the point: the first version of this test only checked the handful of strings I
    // had just changed, and passed while "All 10", "10 results", "Pending", "Assignment",
    // "High" were still rendering in English right next to the Chinese ones.
    //
    // Seeded rows carry English *content* ("E2E Assignment Item …") — that is data, not
    // chrome, so the check runs over the page's own labels with data rows excluded.
    const chrome = text
      .split('\n')
      .filter((line) => !line.includes('E2E ') && !line.includes('Seeded by'))
      .join('\n');

    for (const english of [
      'Mark all read',
      'Inbox failed to load',
      'items need attention',
      'results',
      'Pending',
      'Assignment',
      'Alert',
      'High',
      'Medium',
    ]) {
      expect(chrome, `English leaked back into the page: ${english}`).not.toContain(english);
    }
  });

  test('IB-I18N-2: every type tab is localised', async ({ page }) => {
    await page.goto('/inbox', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: '待办事项' })).toBeVisible({ timeout: 15000 });
    await page.waitForTimeout(1000);

    const toolbar = page.locator('[data-testid="inbox-primary-toolbar"]');
    const text = await toolbar.innerText();
    // All six item types the backend can produce.
    for (const label of ['全部', '审批', '任务', '提及', '提醒', '指派']) {
      expect(text, `tab not localised: ${label}`).toContain(label);
    }
    // The English tab labels must not survive alongside them.
    for (const english of ['Approval', 'Assignment', 'Mention']) {
      expect(text, `English tab label still rendered: ${english}`).not.toContain(english);
    }
  });
});
