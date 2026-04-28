/**
 * PCBA ERP — Navigation IA Smoke
 *
 * Coverage:
 * - D1 Menu Navigation: sidebar click from PCBA role workspaces, not direct business route goto
 * - D2 List Rendering: dynamic list shell renders table/header signals
 * - Dashboard Contract: dashboards use config/dashboards/*.json and /dashboards?code={code}
 * - UX Guardrails: no 403/404 shell and no raw i18n/model keys in visible content
 */

import { test, expect, type APIRequestContext, type Page } from '../../fixtures';
import type { Locator } from '@playwright/test';
import {
  ensureSidebarExpanded,
  waitForDynamicPageLoad,
  waitForTableHydration,
} from '../helpers/index';

type PcbaEntry = {
  id: string;
  href: string;
  label: RegExp;
  parentLabel?: RegExp;
  route: RegExp;
  modelCode?: string;
  dashboardTitle?: RegExp;
};

const NAV_TIMEOUT = 15_000;
const ENTERPRISE_PLUGIN_ROOT = '/Users/ghj/work/auraboot/auraboot-enterprise/plugins';

const REQUIRED_PLUGINS = [
  'pcba-solution',
  'pcba-crm',
  'pcba-procurement',
  'pcba-manufacturing',
  'pcba-compliance',
  'pcba-finance',
];

const DASHBOARD_ENTRY: PcbaEntry = {
  id: 'executive-overview',
  href: '/dashboards?code=pe_executive_dashboard',
  label: /经营概览|Overview/i,
  route: /\/dashboards\?code=pe_executive_dashboard(?:$|[&#])/,
  dashboardTitle: /经营概览仪表盘|Executive KPI|经营概览/i,
};

const SALES_AND_PROCUREMENT_ENTRIES: PcbaEntry[] = [
  {
    id: 'rfq-list',
    href: '/p/pe_rfq',
    label: /询价单|RFQ/i,
    parentLabel: /销售到订单|Sales To Order/i,
    route: /\/p\/pe_rfq(?:$|[?#])/,
    modelCode: 'pe_rfq',
  },
  {
    id: 'order-confirmation-list',
    href: '/p/pe_order_confirmation',
    label: /订单确认|Order Confirmation/i,
    parentLabel: /采购执行|Procurement/i,
    route: /\/p\/pe_order_confirmation(?:$|[?#])/,
    modelCode: 'pe_order_confirmation',
  },
  {
    id: 'asn-list',
    href: '/p/pe_asn',
    label: /送货通知|ASN|Advance Shipment/i,
    parentLabel: /采购执行|Procurement/i,
    route: /\/p\/pe_asn(?:$|[?#])/,
    modelCode: 'pe_asn',
  },
];

const PLANNING_AND_PRODUCTION_ENTRIES: PcbaEntry[] = [
  {
    id: 'mrp-workspace',
    href: '/dashboards?code=pe_mrp_dashboard',
    label: /MRP工作台|MRP Workspace/i,
    parentLabel: /计划排程|Planning/i,
    route: /\/dashboards\?code=pe_mrp_dashboard(?:$|[&#])/,
    dashboardTitle: /MRP看板|MRP/i,
  },
  {
    id: 'mrp-runs',
    href: '/p/pe_mrp_run',
    label: /MRP运算|MRP Runs/i,
    parentLabel: /计划排程|Planning/i,
    route: /\/p\/pe_mrp_run(?:$|[?#])/,
    modelCode: 'pe_mrp_run',
  },
  {
    id: 'shop-floor',
    href: '/dashboards?code=pe_shop_floor_dashboard',
    label: /车间执行|Shop Floor/i,
    parentLabel: /生产执行|Production/i,
    route: /\/dashboards\?code=pe_shop_floor_dashboard(?:$|[&#])/,
    dashboardTitle: /车间看板|Shop Floor/i,
  },
  {
    id: 'production-plans',
    href: '/p/pe_production_plan',
    label: /生产计划|Production Plans/i,
    parentLabel: /生产执行|Production/i,
    route: /\/p\/pe_production_plan(?:$|[?#])/,
    modelCode: 'pe_production_plan',
  },
];

const GOVERNANCE_AND_FINANCE_ENTRIES: PcbaEntry[] = [
  {
    id: 'compliance-docs',
    href: '/p/pe_compliance_doc',
    label: /合规文档|Compliance Docs/i,
    parentLabel: /质量门禁|Quality/i,
    route: /\/p\/pe_compliance_doc(?:$|[?#])/,
    modelCode: 'pe_compliance_doc',
  },
  {
    id: 'cost-estimates',
    href: '/p/pe_cost_estimate',
    label: /成本估算|Cost Estimates/i,
    parentLabel: /成本财务|Finance/i,
    route: /\/p\/pe_cost_estimate(?:$|[?#])/,
    modelCode: 'pe_cost_estimate',
  },
];

function entryLink(nav: Locator, entry: PcbaEntry): Locator {
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

async function revealMenuEntry(page: Page, entry: PcbaEntry): Promise<Locator> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await nav.waitFor({ state: 'visible', timeout: NAV_TIMEOUT });

  let leaf = entryLink(nav, entry);
  if (await leaf.isVisible({ timeout: 1_000 }).catch(() => false)) return leaf;

  await clickIfVisible(
    nav
      .getByRole('button', { name: /PCBA ERP|PCBA|电子制造/i })
      .or(nav.getByRole('menuitem', { name: /PCBA ERP|PCBA|电子制造/i }))
      .or(nav.getByRole('link', { name: /PCBA ERP|PCBA|电子制造/i }))
      .or(nav.locator('text=/PCBA ERP|PCBA|电子制造/i'))
      .first(),
  );

  leaf = entryLink(nav, entry);
  if (await leaf.isVisible({ timeout: 1_000 }).catch(() => false)) return leaf;

  if (entry.parentLabel) {
    await clickIfVisible(
      nav
        .getByRole('button', { name: entry.parentLabel })
        .or(nav.getByRole('menuitem', { name: entry.parentLabel }))
        .or(nav.getByRole('link', { name: entry.parentLabel }))
        .or(nav.locator('button, [role="menuitem"], a').filter({ hasText: entry.parentLabel }))
        .first(),
    );
  }

  leaf = entryLink(nav, entry);
  await leaf.waitFor({ state: 'visible', timeout: NAV_TIMEOUT });
  return leaf;
}

async function openEntryFromSidebar(page: Page, entry: PcbaEntry): Promise<void> {
  const leaf = await revealMenuEntry(page, entry);
  const listResponsePromise = entry.modelCode
    ? page
        .waitForResponse(
          (r) =>
            r.url().includes(`/api/dynamic/${entry.modelCode}`) &&
            r.url().includes('list') &&
            r.status() === 200,
          { timeout: NAV_TIMEOUT },
        )
        .catch(() => null)
    : Promise.resolve(null);

  await leaf.scrollIntoViewIfNeeded();
  await leaf.evaluate((el: HTMLElement) => el.click());
  await expect(page).toHaveURL(entry.route, { timeout: NAV_TIMEOUT });
  await listResponsePromise;
}

async function expectHealthyPageShell(page: Page): Promise<void> {
  const main = page.locator('main, [role="main"]').first();
  await expect(main).toBeVisible({ timeout: NAV_TIMEOUT });

  const errorShell = page
    .getByText(/403|404|Forbidden|Not Found|页面不存在|无权限|Unauthorized/i)
    .first();
  await expect(errorShell).not.toBeVisible({ timeout: 1_000 });
  await expect(page.locator('body')).not.toContainText(/\$i18n:|model\.pe_|field\.pe_|menu\.pe_/i, {
    timeout: 1_000,
  });
}

async function expectDynamicListReady(page: Page): Promise<void> {
  await waitForDynamicPageLoad(page, NAV_TIMEOUT);
  await waitForTableHydration(page, { timeout: 5_000 });

  const table = page.locator('table, [role="table"], [data-testid="dynamic-list"]').first();
  await expect(table).toBeVisible({ timeout: NAV_TIMEOUT });

  const headers = page.locator('thead th, [role="columnheader"]');
  await expect(headers.first()).toBeVisible({ timeout: NAV_TIMEOUT });
  const headerText = (await headers.allTextContents()).join(' ');
  expect(headerText).not.toMatch(/\bpe_[a-z0-9_]+\b|model\.|field\./i);
}

async function expectDashboardReady(page: Page, title: RegExp): Promise<void> {
  await page.waitForLoadState('domcontentloaded');
  const main = page.locator('main, [role="main"]').first();
  await expect(main).toBeVisible({ timeout: NAV_TIMEOUT });
  await expect(main.getByText(title).first()).toBeVisible({ timeout: NAV_TIMEOUT });

  const dashboardContent = main
    .locator('table, [role="table"], canvas, svg, [class*="chart"], [class*="card"]')
    .first();
  await expect(dashboardContent).toBeVisible({ timeout: NAV_TIMEOUT });
}

async function importPluginDirectory(request: APIRequestContext, pluginName: string): Promise<void> {
  const response = await request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: `${ENTERPRISE_PLUGIN_ROOT}/${pluginName}`,
      conflictStrategy: 'OVERWRITE',
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

test.describe('PCBA ERP — Navigation IA Smoke @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90_000);

  test.beforeAll(async ({ request }, testInfo) => {
    testInfo.setTimeout(180_000);
    for (const pluginName of REQUIRED_PLUGINS) {
      await importPluginDirectory(request, pluginName);
    }
  });

  test('PCBA-IA-01: overview dashboard uses the dashboard route contract', async ({ page }) => {
    await openEntryFromSidebar(page, DASHBOARD_ENTRY);
    await expectHealthyPageShell(page);
    await expectDashboardReady(page, DASHBOARD_ENTRY.dashboardTitle!);
  });

  test('PCBA-IA-02: sales-to-order and procurement entries are reachable', async ({ page }) => {
    for (const entry of SALES_AND_PROCUREMENT_ENTRIES) {
      await openEntryFromSidebar(page, entry);
      await expectHealthyPageShell(page);
      await expectDynamicListReady(page);
    }
  });

  test('PCBA-IA-03: planning and production entries are reachable', async ({ page }) => {
    for (const entry of PLANNING_AND_PRODUCTION_ENTRIES) {
      await openEntryFromSidebar(page, entry);
      await expectHealthyPageShell(page);
      if (entry.dashboardTitle) {
        await expectDashboardReady(page, entry.dashboardTitle);
      } else {
        await expectDynamicListReady(page);
      }
    }
  });

  test('PCBA-IA-04: quality and finance entries are reachable', async ({ page }) => {
    for (const entry of GOVERNANCE_AND_FINANCE_ENTRIES) {
      await openEntryFromSidebar(page, entry);
      await expectHealthyPageShell(page);
      await expectDynamicListReady(page);
    }
  });
});
