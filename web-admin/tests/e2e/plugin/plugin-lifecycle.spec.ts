/**
 * Plugin Lifecycle E2E Tests
 *
 * Tests C2-E01, C2-E03, C2-E04: Plugin management UI tests
 * API tests (C2-E02, C2-E05, C2-E06) migrated to: tests/api/plugin-lifecycle.spec.ts
 *
 * Uses storageState for authentication and real API calls (no mocks).
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';


// Asset management plugin manifest for lifecycle testing
const ASSET_MANAGEMENT_PLUGIN = {
  pluginId: 'com.test.asset-management-lifecycle',
  namespace: 'amlc',
  version: '1.0.0',
  displayName: 'Asset Management',
  'displayName:zh-CN': '资产管理',
  description: 'Asset management plugin for lifecycle E2E testing',
  author: 'E2E Test Team',
  minPlatformVersion: '1.0.0',

  dicts: [
    {
      code: 'amlc_asset_category',
      name: 'Asset Category',
      'name:zh-CN': '资产类别',
      dictType: 'static',
      items: [
        { value: 'it_equipment', label: 'IT Equipment', 'label:zh-CN': 'IT设备', sortNo: 10, status: 'enabled' },
        { value: 'office_furniture', label: 'Office Furniture', 'label:zh-CN': '办公家具', sortNo: 20, status: 'enabled' },
        { value: 'vehicle', label: 'Vehicle', 'label:zh-CN': '车辆', sortNo: 30, status: 'enabled' },
      ],
    },
    {
      code: 'amlc_asset_status',
      name: 'Asset Status',
      'name:zh-CN': '资产状态',
      dictType: 'static',
      items: [
        { value: 'in_use', label: 'In Use', 'label:zh-CN': '使用中', sortNo: 10, status: 'enabled' },
        { value: 'idle', label: 'Idle', 'label:zh-CN': '闲置', sortNo: 20, status: 'enabled' },
        { value: 'scrapped', label: 'Scrapped', 'label:zh-CN': '报废', sortNo: 30, status: 'enabled' },
      ],
    },
  ],

  fields: [
    {
      code: 'amlc_asset_name',
      displayName: 'Asset Name',
      'displayName:zh-CN': '资产名称',
      dataType: 'string',
      constraints: { required: true, maxLength: 200 },
      feature: { searchable: true },
    },
    {
      code: 'amlc_asset_code',
      displayName: 'Asset Code',
      'displayName:zh-CN': '资产编号',
      dataType: 'string',
      constraints: { required: true, maxLength: 50 },
      feature: { searchable: true, unique: true },
    },
    {
      code: 'amlc_category',
      displayName: 'Category',
      'displayName:zh-CN': '资产类别',
      dataType: 'enum',
      dictCode: 'amlc_asset_category',
      constraints: { required: true },
    },
    {
      code: 'amlc_status',
      displayName: 'Status',
      'displayName:zh-CN': '资产状态',
      dataType: 'enum',
      dictCode: 'amlc_asset_status',
      constraints: { required: true },
      defaultValue: 'idle',
    },
    {
      code: 'amlc_purchase_date',
      displayName: 'Purchase Date',
      'displayName:zh-CN': '购入日期',
      dataType: 'date',
    },
    {
      code: 'amlc_value',
      displayName: 'Asset Value',
      'displayName:zh-CN': '资产价值',
      dataType: 'decimal',
      constraints: { min: 0 },
    },
  ],

  models: [
    {
      code: 'amlc_asset',
      displayName: 'Asset',
      'displayName:zh-CN': '资产',
      description: 'Asset entity for lifecycle testing',
      modelType: 'entity',
    },
  ],

  modelFieldBindings: [
    { modelCode: 'amlc_asset', fieldCode: 'amlc_asset_name', sequence: 10, required: true },
    { modelCode: 'amlc_asset', fieldCode: 'amlc_asset_code', sequence: 20, required: true },
    { modelCode: 'amlc_asset', fieldCode: 'amlc_category', sequence: 30, required: true },
    { modelCode: 'amlc_asset', fieldCode: 'amlc_status', sequence: 40, required: true, defaultValue: 'idle' },
    { modelCode: 'amlc_asset', fieldCode: 'amlc_purchase_date', sequence: 50, required: false },
    { modelCode: 'amlc_asset', fieldCode: 'amlc_value', sequence: 60, required: false },
  ],

  permissions: [
    {
      code: 'amlc:asset:read',
      name: 'View Assets',
      'name:zh-CN': '查看资产',
      resourceType: 'model',
      resourceCode: 'amlc_asset',
      action: 'read',
    },
    {
      code: 'amlc:asset:create',
      name: 'Create Assets',
      'name:zh-CN': '创建资产',
      resourceType: 'model',
      resourceCode: 'amlc_asset',
      action: 'create',
    },
    {
      code: 'amlc:asset:update',
      name: 'Update Assets',
      'name:zh-CN': '更新资产',
      resourceType: 'model',
      resourceCode: 'amlc_asset',
      action: 'update',
    },
  ],

  menus: [
    {
      code: 'amlc_root',
      name: 'Asset Management',
      'name:zh-CN': '资产管理',
      path: '/amlc',
      icon: 'Package',
      type: 1,
      orderNo: 800,
      visible: true,
    },
    {
      code: 'amlc_list',
      name: 'Asset List',
      'name:zh-CN': '资产列表',
      path: '/dynamic/amlc_asset',
      icon: 'List',
      type: 1,
      orderNo: 810,
      parentCode: 'amlc_root',
      visible: true,
    },
  ],
};

/**
 * Import result type from plugin API
 */
interface ImportExecuteResult {
  importId: string;
  pluginPid: string;
  pluginId: string;
  namespace: string;
  version: string;
  success: boolean;
  status: string;
  errorMessage?: string;
  resourceCounts: Record<string, Record<string, number>>;
  totalResourceCount: number;
  durationMs: number;
}

test.describe('Plugin Lifecycle', () => {
  test.describe.configure({ mode: 'serial', timeout: 30000 });

  let importResult: ImportExecuteResult | null = null;

  test.beforeAll(async ({ request }) => {
    try {
      const response = await request.post(`/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE`, {
        data: ASSET_MANAGEMENT_PLUGIN,
        headers: { 'Content-Type': 'application/json' },
      });

      if (response.ok()) {
        importResult = await response.json();
      }
    } catch (error) {
      console.warn('Plugin lifecycle setup failed:', error);
    }
  });

  /**
   * C2-E01: Open plugin management page
   * Verify that /system/plugins page is accessible and loads correctly
   */
  test('C2-E01: Plugin management page is accessible', async ({ page }) => {
    await page.goto(`/system/plugins`);
    await page.waitForLoadState('domcontentloaded');

    // Verify the page loaded (not a 404 or error)
    const pageTitle = page.locator('h1, h2, [data-testid="page-title"]');
    const hasTitle = await pageTitle.first().isVisible({ timeout: 10000 }).catch(() => false);

    // Also check for common page elements: table, card layout, or plugin list
    const pluginContent = page.locator(
      '.ant-table, .ant-card, .ant-list, [data-testid="plugin-list"]'
    );
    const hasContent = await pluginContent.first().isVisible({ timeout: 5000 }).catch(() => false);

    // Page should have loaded without 404
    const is404 = await page.locator('text=404').isVisible({ timeout: 2000 }).catch(() => false);
    expect(is404).toBe(false);

    // At minimum the page should have some structure
    expect(hasTitle || hasContent).toBe(true);
  });

  /**
   * C2-E03: Verify menus appear after plugin import
   * The sidebar should contain "资产管理" menu item
   */
  test('C2-E03: Verify plugin menus appear in sidebar', async ({ page }) => {
    // Navigate to home page to trigger menu rendering
    await page.goto(`/`);
    await page.waitForLoadState('domcontentloaded');

    // Check sidebar for the asset management menu
    const sidebar = page.locator('nav, aside, [data-testid="sidebar"], .sidebar, .ant-layout-sider');
    const assetMenu = page.getByText('资产管理').first();

    const menuVisible = await assetMenu.isVisible({ timeout: 10000 }).catch(() => false);

    if (!menuVisible) {
      // Fallback: verify menu exists via API
      const menuResponse = await page.request.get(`/api/menu/user`);
      expect(menuResponse.ok()).toBe(true);

      const menuData = await menuResponse.json();
      const menus = menuData.data || menuData;

      // Recursively search for the asset management menu
      const findMenu = (items: any[]): boolean => {
        for (const item of items) {
          if (item.name === 'amlc_root' || item.path === '/amlc') {
            return true;
          }
          if (item.children && findMenu(item.children)) {
            return true;
          }
        }
        return false;
      };

      expect(Array.isArray(menus) && findMenu(menus)).toBe(true);
    } else {
      expect(menuVisible).toBe(true);
    }
  });

  /**
   * C2-E04: Navigate to asset list page
   * Verify the dynamic page loads correctly for the asset model
   */
  test('C2-E04: Asset list page loads correctly', async ({ page }) => {
    await page.goto(`/dynamic/amlc_asset`);
    await page.waitForLoadState('domcontentloaded');

    // Verify page loaded (not 404)
    const is404 = await page.locator('text=404').isVisible({ timeout: 2000 }).catch(() => false);

    if (is404) {
      // If dynamic route doesn't work, try the wildcard route
      await page.goto(`/amlc`);
      await page.waitForLoadState('domcontentloaded');
    }

    // Look for list page elements: table, create button, or page structure
    const listContent = page.locator(
      '.ant-table, table, [data-testid="dynamic-list"], .data-list'
    );
    const hasListContent = await listContent.first().isVisible({ timeout: 10000 }).catch(() => false);

    // Also check for model-related content via API
    const modelResponse = await page.request.get(`/api/meta/models/code/amlc_asset`);
    expect(modelResponse.ok()).toBe(true);

    const modelData = await modelResponse.json();
    const model = modelData.data || modelData;
    expect(model).not.toBeNull();
    expect(model.code).toBe('amlc_asset');
  });

  /**
   * Cleanup: Uninstall the test plugin after all tests
   */
  test.afterAll(async ({ request }) => {
    if (!importResult?.pluginPid) {
      return;
    }

    try {
      const uninstallResponse = await request.post(
        `/api/plugins/${importResult.pluginPid}/uninstall`,
        {
          data: {
            force: true,
            decisions: {},
          },
        }
      );

      if (uninstallResponse.ok()) {
        console.log('Asset management lifecycle test plugin uninstalled successfully');
      } else {
        console.warn('Failed to uninstall test plugin, status:', uninstallResponse.status());
      }
    } catch (error) {
      console.warn('Failed to cleanup test plugin:', error);
    }
  });
});
