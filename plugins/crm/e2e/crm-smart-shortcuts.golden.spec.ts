import { expect, test, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5196';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6466';
const RUN = process.env.CRM_SMART_SHORTCUTS_RUN_ID || `smart-shortcuts-${Date.now()}`;
const EVIDENCE_DIR = process.env.CRM_SMART_SHORTCUTS_EVIDENCE_DIR
  || path.resolve(process.cwd(), '.workspace', 'evidence', 'crm-smart-shortcuts');
const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';

let adminJwt = '';
let screenshotPath = '';

async function api(pathname: string, init: RequestInit = {}): Promise<any> {
  const headers = new Headers(init.headers);
  if (adminJwt) headers.set('Authorization', `Bearer ${adminJwt}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${BE}${pathname}`, { ...init, headers });
  const body = await response.json().catch(() => ({}));
  expect(response.ok, `${pathname}: HTTP ${response.status} ${JSON.stringify(body)}`).toBeTruthy();
  expect(String(body?.code), `${pathname}: ${JSON.stringify(body)}`).toBe('0');
  return body;
}

async function uiLogin(page: Page): Promise<void> {
  const response = await page.request.post(`${BASE}/login`, {
    form: {
      email: ADMIN_EMAIL,
      password: PASSWORD,
      remember: 'on',
      redirectTo: '/',
    },
    maxRedirects: 0,
  });
  expect([302, 303], `UI session login: HTTP ${response.status()}`).toContain(response.status());
}

async function clearMenuFavorites(): Promise<void> {
  const favorites = await api('/api/user-engagement?engagementType=favorite&targetType=menu');
  for (const favorite of favorites.data ?? []) {
    await api(`/api/user-engagement/${favorite.id}`, { method: 'DELETE' });
  }
}

test.beforeAll(async () => {
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  const response = await fetch(`${BE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: ADMIN_EMAIL, password: PASSWORD }),
  });
  const body = await response.json();
  expect(response.ok, JSON.stringify(body)).toBeTruthy();
  adminJwt = String(body?.data?.jwt || '');
  expect(adminJwt).toBeTruthy();
  await clearMenuFavorites();
});

test.afterAll(async () => {
  const favorites = adminJwt
    ? await api('/api/user-engagement?engagementType=favorite&targetType=menu')
    : { data: [] };
  writeFileSync(path.join(EVIDENCE_DIR, `${RUN}.json`), `${JSON.stringify({
    schemaVersion: 1,
    runId: RUN,
    technicalVerdict: screenshotPath ? 'pass' : 'incomplete',
    screenshot: screenshotPath || null,
    favorites: (favorites.data ?? []).map((favorite: Record<string, any>) => ({
      targetId: favorite.targetId,
      targetLabel: favorite.targetLabel,
      sortOrder: favorite.sortOrder,
      path: favorite.targetContext?.path,
    })),
    dataMigration: 'out-of-scope-development-stage',
  }, null, 2)}\n`);
});

test('销售首页快捷入口可自定义、排序并跨刷新保持', async ({ page }) => {
  await uiLogin(page);
  let outdatedOptimizeDep = false;
  page.on('response', (response) => {
    if (response.status() === 504 && response.statusText().includes('Outdated Optimize Dep')) {
      outdatedOptimizeDep = true;
    }
  });
  await page.goto(`${BASE}/dashboards/view/crm_dashboard`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: /^销售首页$/ })).toBeVisible({ timeout: 15_000 })
    .catch(async (error) => {
      if (!outdatedOptimizeDep) throw error;
      await page.reload({ waitUntil: 'domcontentloaded' });
    });
  await expect(page.getByRole('heading', { name: /^销售首页$/ })).toBeVisible({ timeout: 25_000 });

  const block = page.getByTestId('dashboard-block-block_sales_shortcuts');
  await expect(block.getByTestId('shortcut-row')).toHaveCount(6);
  await block.getByTestId('shortcuts-customize-button').click();
  const modal = page.getByTestId('add-favorite-modal');
  await expect(modal).toBeVisible();

  for (const label of ['联系人查重', '我的任务']) {
    await Promise.all([
      page.waitForResponse((response) => response.url().includes('/api/user-engagement')
        && response.request().method() === 'POST'
        && response.ok()),
      modal.getByRole('button', { name: new RegExp(`${label}$`) }).click(),
    ]);
  }
  await modal.getByTestId('add-favorite-modal-close').click();

  const rows = block.getByTestId('shortcut-row');
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toHaveAttribute('href', '/p/crm_contact_duplicate_mobile');
  await expect(rows.nth(1)).toHaveAttribute('href', '/p/crm_my_tasks');

  await block.getByRole('button', { name: '编辑', exact: true }).click();
  await Promise.all([
    page.waitForResponse((response) => response.url().endsWith('/api/user-engagement/reorder')
      && response.request().method() === 'PUT'
      && response.ok()),
    rows.nth(1).dragTo(rows.nth(0)),
  ]);
  await block.getByRole('button', { name: '完成', exact: true }).click();
  await expect(rows.nth(0)).toHaveAttribute('href', '/p/crm_my_tasks');
  await expect(rows.nth(1)).toHaveAttribute('href', '/p/crm_contact_duplicate_mobile');

  await page.reload();
  await expect(page.getByRole('heading', { name: /^销售首页$/ })).toBeVisible({ timeout: 25_000 });
  const persistedRows = page.getByTestId('dashboard-block-block_sales_shortcuts')
    .getByTestId('shortcut-row');
  await expect(persistedRows).toHaveCount(2);
  await expect(persistedRows.nth(0)).toHaveAttribute('href', '/p/crm_my_tasks');
  await expect(page.getByTestId('dashboard-block-block_sales_period_overview'))
    .toContainText('新增线索', { timeout: 25_000 });
  await expect(persistedRows.nth(1)).toHaveAttribute('href', '/p/crm_contact_duplicate_mobile');

  const favorites = await api('/api/user-engagement?engagementType=favorite&targetType=menu');
  expect((favorites.data ?? []).map((favorite: Record<string, any>) => ({
    targetId: favorite.targetId,
    sortOrder: favorite.sortOrder,
    path: favorite.targetContext?.path,
  }))).toEqual([
    { targetId: 'crm_tasks', sortOrder: 0, path: '/p/crm_my_tasks' },
    {
      targetId: 'crm_contact_duplicate_mobile',
      sortOrder: 1,
      path: '/p/crm_contact_duplicate_mobile',
    },
  ]);

  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect(page.getByText('加载中...', { exact: true })).toHaveCount(0, { timeout: 25_000 });
  await expect(persistedRows).toHaveCount(2);
  await expect(persistedRows.nth(0)).toHaveAttribute('href', '/p/crm_my_tasks');
  await page.evaluate(async () => {
    await document.fonts.ready;
    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
    });
  });
  screenshotPath = path.join(EVIDENCE_DIR, `${RUN}.png`);
  await page.screenshot({ path: screenshotPath, animations: 'disabled' });
  await page.getByTestId('dashboard-block-block_sales_shortcuts').screenshot({
    path: path.join(EVIDENCE_DIR, `${RUN}-shortcuts.png`),
    animations: 'disabled',
  });
});
