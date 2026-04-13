/**
 * Plugin Package Coverage Smoke Tests
 *
 * Validates all local plugin packages are installed and their representative
 * dynamic pages are accessible through UI navigation.
 */

import { test, expect } from '../../fixtures';
import type { TestInfo } from '@playwright/test';
import { navigateToDynamicPage, waitForDynamicPageLoad } from '../helpers/index';
import { writeIstanbulCoverage } from '../helpers/coverage';

type PluginSmokeCase = {
  pluginId: string;
  pluginDir: string;
  modelCode?: string;
  navigationModelCode?: string;
};

const PLUGIN_CASES: PluginSmokeCase[] = [
  {
    pluginId: 'com.auraboot.project-management',
    pluginDir: 'project-management',
    modelCode: 'pm_project',
  },
  {
    pluginId: 'com.auraboot.quarry-industry',
    pluginDir: 'quarry-industry',
    modelCode: 'qo_daily_report',
  },
  { pluginId: 'com.auraboot.annual-plan', pluginDir: 'annual-plan', modelCode: 'ap_annual_plan' },
  { pluginId: 'com.auraboot.dual-prevention', pluginDir: 'dual-prevention', modelCode: 'dp_issue' },
  { pluginId: 'com.test.e2e-order', pluginDir: 'e2e-test-order', modelCode: 'e2et_order' },
  { pluginId: 'com.auraboot.asset-management', pluginDir: 'asset-management', modelCode: 'asset' },
  { pluginId: 'com.auraboot.crm', pluginDir: 'crm', modelCode: 'crm_lead' },
  { pluginId: 'com.auraboot.sales', pluginDir: 'sales', modelCode: 'sl_sales_quotation' },
  {
    pluginId: 'com.auraboot.procurement',
    pluginDir: 'procurement',
    modelCode: 'pr_purchase_order',
  },
  { pluginId: 'com.auraboot.inventory', pluginDir: 'inventory', modelCode: 'inv_inbound' },
  { pluginId: 'com.auraboot.finance', pluginDir: 'finance', modelCode: 'fin_account' },
  { pluginId: 'com.auraboot.quality', pluginDir: 'quality', modelCode: 'qc_iqc_order' },
  {
    pluginId: 'com.auraboot.pcba-manufacturing',
    pluginDir: 'pcba-industry',
    modelCode: 'pe_production_plan',
  },
  {
    pluginId: 'com.auraboot.pcba-solution',
    pluginDir: 'pcba-solution',
    navigationModelCode: 'pe_production_plan',
  },
];

test.describe('Plugin Package Smoke Coverage', () => {
  test.setTimeout(process.env.E2E_COVERAGE === '1' ? 90000 : 30000);
  test.afterEach(async ({ page }, testInfo: TestInfo) => {
    await writeIstanbulCoverage(page, testInfo);
  });

  for (const pluginCase of PLUGIN_CASES) {
    test(`PLUGIN-SMOKE: ${pluginCase.pluginDir} should be installed and accessible @smoke`, async ({
      page,
    }) => {
      const pluginResp = await page.request.get('/api/plugins?current=1&size=300');
      expect(pluginResp.ok(), `Plugin API should be available for ${pluginCase.pluginId}`).toBe(
        true,
      );
      const pluginBody = await pluginResp.json().catch(() => ({}));
      const plugins = pluginBody?.data?.records ?? pluginBody?.data?.data ?? pluginBody?.data ?? [];
      const targetPlugin = Array.isArray(plugins)
        ? plugins.find((item: any) => item.pluginId === pluginCase.pluginId)
        : null;
      expect(targetPlugin, `${pluginCase.pluginId} should be installed`).toBeTruthy();

      const navigationModelCode = pluginCase.navigationModelCode ?? pluginCase.modelCode;

      if (pluginCase.modelCode) {
        const modelResp = await page.request.get(`/api/meta/models/code/${pluginCase.modelCode}`);
        expect(modelResp.ok(), `Model ${pluginCase.modelCode} should exist`).toBe(true);
        const modelBody = await modelResp.json().catch(() => ({}));
        expect(
          modelBody?.data?.status,
          `Model ${pluginCase.modelCode} should be published for ${pluginCase.pluginId}`,
        ).toBe('published');
      }

      // Navigate with resilience — page may crash on render for some plugins
      try {
        if (!navigationModelCode) {
          throw new Error(`No navigation model configured for ${pluginCase.pluginId}`);
        }
        await navigateToDynamicPage(page, navigationModelCode);
        await waitForDynamicPageLoad(page);
      } catch (err: any) {
        // If page was closed/crashed, re-navigate
        if (err.message?.includes('closed') || err.message?.includes('Target')) {
          throw new Error(String(`Page crashed during navigation to ${navigationModelCode}`));
          return;
        }
        throw err;
      }
      await expect(page).toHaveURL(new RegExp(`/p/${navigationModelCode}([/?#].*)?$`));

      const content = page.locator(
        '.ant-table, table, [role="table"], .ant-form, form, [data-testid="dynamic-list"], [data-testid="dynamic-form"]',
      );
      await expect(
        content.first(),
        `${navigationModelCode} page should render table or form`,
      ).toBeVisible();
    });
  }
});
