/**
 * PCBA ERP - Fixed demo seed smoke.
 *
 * Coverage:
 * - Imports the PCBA pilot plugin set.
 * - Reads the fixed PCBA-DEMO-20260426 seed contract.
 * - Enters every Demo Flow page from the sidebar and verifies the fixed seed
 *   search value is visible.
 *
 * This spec intentionally does not create seed data. Run the demo seed importer
 * before this smoke when validating a fresh environment.
 */

import { test, expect, type APIRequestContext, type Page } from '../../fixtures';
import type { Locator } from '@playwright/test';
import { readFileSync } from 'node:fs';
import {
  ensureSidebarExpanded,
  findRowInPaginatedList,
  waitForDynamicPageLoad,
  waitForTableHydration,
} from '../helpers/index';

type SeedMenu = {
  menuCode: string;
  modelCode: string;
  pageKey: string;
  expectedSearchValue: string;
};

type PcbaMenu = {
  code: string;
  path: string;
  parentCode?: string;
  orderNo?: number;
  'name:zh-CN'?: string;
  'name:en'?: string;
};

type DemoEntry = SeedMenu & {
  href: string;
  label: RegExp;
  route: RegExp;
};

const NAV_TIMEOUT = 15_000;
const ENTERPRISE_ROOT =
  process.env.AURABOOT_ENTERPRISE_ROOT || '/Users/ghj/work/auraboot/auraboot-enterprise';
const ENTERPRISE_PLUGIN_ROOT = `${ENTERPRISE_ROOT}/plugins`;
const SEED_FILE = `${ENTERPRISE_PLUGIN_ROOT}/pcba-solution/config/demo-data/pcba-demo-20260426.json`;
const MENU_FILE = `${ENTERPRISE_PLUGIN_ROOT}/pcba-solution/config/menus.json`;

const REQUIRED_PLUGINS = [
  'product-catalog',
  'crm',
  'sales',
  'inventory',
  'procurement',
  'finance',
  'quality',
  'pcba-industry',
  'pcba-solution',
  'pcba-crm',
  'pcba-procurement',
  'pcba-manufacturing',
  'pcba-compliance',
  'pcba-finance',
  'pcba-sales',
  'pcba-warehouse',
];

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function readJson<T>(filePath: string): T {
  return JSON.parse(readFileSync(filePath, 'utf8')) as T;
}

function demoEntries(): DemoEntry[] {
  const seed = readJson<{ demoMenus: SeedMenu[] }>(SEED_FILE);
  const menus = readJson<PcbaMenu[]>(MENU_FILE);
  const menusByCode = new Map(menus.map((menu) => [menu.code, menu]));

  return seed.demoMenus.map((seedMenu) => {
    const menu = menusByCode.get(seedMenu.menuCode);
    expect(menu, `${seedMenu.menuCode} should exist in pcba-solution menus`).toBeTruthy();
    expect(menu?.path, `${seedMenu.menuCode} should have a menu path`).toBe(`/p/${seedMenu.modelCode}`);

    const names = [menu?.['name:zh-CN'], menu?.['name:en']]
      .filter((name): name is string => Boolean(name))
      .map(escapeRegExp);
    return {
      ...seedMenu,
      href: menu!.path,
      label: new RegExp(names.join('|'), 'i'),
      route: new RegExp(`/p/${escapeRegExp(seedMenu.modelCode)}(?:$|[?#])`),
    };
  });
}

async function importPluginDirectory(
  request: APIRequestContext,
  pluginName: string,
): Promise<void> {
  const response = await request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: `${ENTERPRISE_PLUGIN_ROOT}/${pluginName}`,
      conflictStrategy: 'OVERWRITE_SAFE',
      autoPublishModels: true,
      autoPublishFields: true,
      autoPublishCommands: true,
      autoPublishPages: true,
    },
    headers: { 'Content-Type': 'application/json' },
    timeout: 600_000,
  });

  const body = await response.json().catch(() => ({}));
  const data = body?.data ?? body;
  const success = response.ok() && (data?.success === true || body?.success === true);
  expect(
    success,
    `${pluginName} import should succeed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
}

function entryLink(nav: Locator, entry: DemoEntry): Locator {
  const byHref = nav.locator(`a[href="${entry.href}"], a[href$="${entry.href}"]`);
  const byLabel = byHref.filter({ hasText: entry.label });
  return byLabel.or(byHref).first();
}

async function clickIfVisible(locator: Locator): Promise<boolean> {
  const visible = await locator.isVisible({ timeout: 1_000 }).catch(() => false);
  if (!visible) return false;
  await locator.scrollIntoViewIfNeeded();
  await locator.evaluate((el: HTMLElement) => el.click());
  return true;
}

async function openDemoEntry(page: Page, entry: DemoEntry): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await nav.waitFor({ state: 'visible', timeout: NAV_TIMEOUT });

  await clickIfVisible(
    nav
      .getByRole('button', { name: /PCBA ERP|PCBA|电子制造/i })
      .or(nav.getByRole('menuitem', { name: /PCBA ERP|PCBA|电子制造/i }))
      .or(nav.getByRole('link', { name: /PCBA ERP|PCBA|电子制造/i }))
      .or(nav.locator('text=/PCBA ERP|PCBA|电子制造/i'))
      .first(),
  );

  await clickIfVisible(
    nav
      .getByRole('button', { name: /演示主线|Demo Flow/i })
      .or(nav.getByRole('menuitem', { name: /演示主线|Demo Flow/i }))
      .or(nav.getByRole('link', { name: /演示主线|Demo Flow/i }))
      .or(nav.locator('button, [role="menuitem"], a').filter({ hasText: /演示主线|Demo Flow/i }))
      .first(),
  );

  const listResponse = page
    .waitForResponse(
      (response) =>
        response.url().includes(`/api/dynamic/${entry.modelCode}`) &&
        response.url().includes('list') &&
        response.status() === 200,
      { timeout: NAV_TIMEOUT },
    )
    .catch(() => null);

  const leaf = entryLink(nav, entry);
  await leaf.waitFor({ state: 'visible', timeout: NAV_TIMEOUT });
  await leaf.scrollIntoViewIfNeeded();
  await leaf.evaluate((el: HTMLElement) => el.click());
  await expect(page).toHaveURL(entry.route, { timeout: NAV_TIMEOUT });
  await listResponse;
  await waitForDynamicPageLoad(page, NAV_TIMEOUT);
  await waitForTableHydration(page, { timeout: 5_000 });
}

async function expectSeedVisible(page: Page, entry: DemoEntry): Promise<void> {
  await openDemoEntry(page, entry);

  await expect(page.locator('main, [role="main"]').first()).toBeVisible({
    timeout: NAV_TIMEOUT,
  });
  await expect(
    page.getByText(/403|404|Forbidden|Not Found|页面不存在|无权限|Unauthorized/i).first(),
  ).not.toBeVisible({ timeout: 1_000 });

  const row = await findRowInPaginatedList(page, entry.expectedSearchValue, NAV_TIMEOUT);
  await expect(
    row,
    `${entry.menuCode} should show fixed seed ${entry.expectedSearchValue}`,
  ).toBeVisible({ timeout: NAV_TIMEOUT });
}

test.describe('PCBA-009B - Fixed demo seed menu smoke @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(240_000);

  const entries = demoEntries();

  test.beforeAll(async ({ request }, testInfo) => {
    testInfo.setTimeout(300_000);
    for (const pluginName of REQUIRED_PLUGINS) {
      await importPluginDirectory(request, pluginName);
    }
  });

  test('all Demo Flow entries can find fixed seed records from the sidebar', async ({ page }) => {
    for (const entry of entries) {
      await test.step(`${entry.menuCode}: ${entry.expectedSearchValue}`, async () => {
        await expectSeedVisible(page, entry);
      });
    }
  });
});
