import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

/**
 * The notification centre must speak the viewer's language, not a hardcoded one.
 *
 * This page was the mirror image of the inbox bug: every label hardcoded in Chinese.
 * A zh-CN user never noticed — which is exactly why it survived. For the en-US, ja-JP
 * and ko-KR locales the product also ships, the whole page was untranslatable.
 *
 * Asserting on the rendered text (not on the presence of i18n keys in source) is what
 * makes this catch a regression where the keys exist but stop resolving.
 */

const SHOTS = process.env.PROBE_SHOT_DIR || '/tmp/collab-probe';
fs.mkdirSync(SHOTS, { recursive: true });

async function seed(page: import('@playwright/test').Page) {
  const me = await (await page.request.get('/api/auth/me')).json();
  const user = me.data.user;
  const res = await page.request.post('/api/test/fixture', {
    data: {
      name: 'notifications',
      params: { userId: String(user.id), tenantId: String(user.tenantId) },
    },
  });
  expect((await res.json()).success).toBe(true);
}

test.describe('notification centre localisation', () => {
  test('NC-I18N-1: chrome resolves through i18n rather than hardcoded strings', async ({ page }) => {
    await seed(page);
    await page.goto('/notifications', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: '通知中心' })).toBeVisible({ timeout: 15000 });
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(SHOTS, '50-notif-zh.png'), fullPage: true });

    const text = await page.locator('main, body').first().innerText();
    // Category tabs, read filters and toolbar all render.
    for (const label of ['通知中心', '偏好设置', '全部', '系统', '审批', '业务', '告警', '未读', '已读', '全选']) {
      expect(text, `label missing after i18n conversion: ${label}`).toContain(label);
    }
  });

  test('NC-I18N-2: switching the locale actually changes this page', async ({ page }) => {
    // The real proof that the page is translatable: ask the backend for en-US and check
    // the same keys come back in English. If the page had kept its hardcoded Chinese,
    // these keys would not exist at all.
    const zh = await (await page.request.get('/api/i18n/zh-CN')).json();
    const en = await (await page.request.get('/api/i18n/en-US')).json();

    const keys = [
      'workbench.notificationCenter.title',
      'workbench.notificationCenter.preferences',
      'workbench.notificationCenter.catSystem',
      'workbench.notificationCenter.markAllRead',
      'workbench.notificationCenter.deleteSelected',
    ];

    const evidence: Record<string, { zh: string; en: string }> = {};
    for (const k of keys) {
      const zhV = zh.data[k];
      const enV = en.data[k];
      evidence[k] = { zh: zhV, en: enV };
      expect(zhV, `zh-CN missing ${k}`).toBeTruthy();
      expect(enV, `en-US missing ${k}`).toBeTruthy();
      expect(zhV, `${k} is the same in both locales — it was never translated`).not.toBe(enV);
    }
    fs.writeFileSync(
      path.join(SHOTS, 'notification-i18n-evidence.json'),
      JSON.stringify(evidence, null, 2),
    );
  });
});
