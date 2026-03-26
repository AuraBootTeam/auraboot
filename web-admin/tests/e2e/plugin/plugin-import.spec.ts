/**
 * Plugin Import E2E Tests
 *
 * Plugin Import UI Verification Tests
 * API tests (P-001 ~ P-006, P-009, C-N01, C-N03) migrated to: tests/api/plugin-import.spec.ts
 * - P-007: UI verification - Model detail page
 * - P-008: UI verification - Dictionary page
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';


// Test plugin manifest
const TEST_PLUGIN = {
  pluginId: 'com.test.minimal-import-test',
  namespace: 'mtest',
  version: '1.0.0',
  displayName: 'Minimal Import Test',
  'displayName:zh-CN': '最小化导入测试',
  description: 'Minimal plugin for testing import functionality',
  author: 'Test Team',
  minPlatformVersion: '1.0.0',

  dicts: [
    {
      code: 'mtest_priority',
      name: 'Priority',
      'name:zh-CN': '优先级',
      dictType: 'static',
      items: [
        { value: 'high', label: 'High', 'label:zh-CN': '高', sortNo: 10, status: 'enabled' },
        { value: 'low', label: 'Low', 'label:zh-CN': '低', sortNo: 20, status: 'enabled' },
      ],
    },
  ],

  fields: [
    {
      code: 'mtest_name',
      displayName: 'Name',
      'displayName:zh-CN': '名称',
      dataType: 'string',
      constraints: { required: true, maxLength: 100 },
      feature: { searchable: true },
    },
    {
      code: 'mtest_priority',
      displayName: 'Priority',
      'displayName:zh-CN': '优先级',
      dataType: 'enum',
      dictCode: 'mtest_priority',
      constraints: { required: true },
      defaultValue: 'low',
    },
    {
      code: 'mtest_quantity',
      displayName: 'Quantity',
      'displayName:zh-CN': '数量',
      dataType: 'integer',
      constraints: { min: 0, max: 1000 },
    },
  ],

  models: [
    {
      code: 'mtest_item',
      displayName: 'Test Item',
      'displayName:zh-CN': '测试项目',
      description: 'Minimal test model',
      modelType: 'entity',
    },
  ],

  modelFieldBindings: [
    { modelCode: 'mtest_item', fieldCode: 'mtest_name', sequence: 10, required: true },
    { modelCode: 'mtest_item', fieldCode: 'mtest_priority', sequence: 20, required: true, defaultValue: 'low' },
    { modelCode: 'mtest_item', fieldCode: 'mtest_quantity', sequence: 30, required: false },
  ],

  permissions: [
    {
      code: 'mtest:item:read',
      name: 'View Test Items',
      'name:zh-CN': '查看测试项目',
      resourceType: 'model',
      resourceCode: 'mtest_item',
      action: 'read',
    },
    {
      code: 'mtest:item:create',
      name: 'Create Test Items',
      'name:zh-CN': '创建测试项目',
      resourceType: 'model',
      resourceCode: 'mtest_item',
      action: 'create',
    },
  ],

  menus: [
    {
      code: 'mtest_root',
      name: 'Minimal Test',
      'name:zh-CN': '最小测试',
      path: '/mtest',
      icon: 'TestTube',
      type: 1,
      orderNo: 900,
      visible: true,
    },
  ],
};

// Expected counts after import
const EXPECTED_COUNTS = {
  dict: 1,
  dictItems: 2,
  field: 3,
  model: 1,
  binding: 3,
  permission: 2,
  menu: 1,
  totalResources: 11,
};

/**
 * Plugin Import API Response
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

test.describe('Plugin Import UI Verification', () => {
  test.describe.configure({ mode: 'serial', timeout: 15000 });

  let importResult: ImportExecuteResult | null = null;
  let modelPid: string | null = null;

  test.beforeAll(async ({ request }) => {
    try {
      const response = await request.post(`/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE`, {
        data: TEST_PLUGIN,
        headers: { 'Content-Type': 'application/json' },
      });

      if (response.ok()) {
        importResult = await response.json();
      }

      // Get model PID for P-007
      const modelResponse = await request.get(`/api/meta/models/code/mtest_item`);
      if (modelResponse.ok()) {
        const modelData = await modelResponse.json();
        modelPid = (modelData.data || modelData).pid;
      }
    } catch (error) {
      console.warn('Plugin import setup failed:', error);
    }
  });

  /**
   * P-007: UI verification - Model detail page
   */
  test('P-007: UI verification - Model detail page', async ({ page }) => {
    // Get model PID if not already set
    if (!modelPid) {
      const modelResponse = await page.request.get(`/api/meta/models/code/mtest_item`);
      if (modelResponse.ok()) {
        const modelData = await modelResponse.json();
        modelPid = (modelData.data || modelData).pid;
      }
    }

    expect(modelPid).not.toBeNull();

    // Navigate to model detail page
    await page.goto(`/meta/models/${modelPid}`);

    // Wait for either model content or login redirect
    const loaded = await Promise.race([
      page.getByText('mtest_item').first().waitFor({ timeout: 8000 }).then(() => 'content'),
      page.waitForURL('**/login**', { timeout: 8000 }).then(() => 'login'),
    ]).catch(() => 'timeout');

    if (loaded === 'login') {
      throw new Error(String('Redirected to login - session expired'))
      return;
    }

    // Verify model code is displayed
    await expect(page.getByText('mtest_item').first()).toBeVisible();

    // Verify model name is displayed (use first() to handle multiple matches)
    await expect(page.getByText('测试项目').first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * P-008: UI verification - Dictionary page
   */
  test('P-008: UI verification - Dictionary page', async ({ page }) => {
    // Navigate to dictionary management page
    await page.goto(`/meta/dict`);

    // Wait for page content or login redirect
    const loaded = await Promise.race([
      page.locator('input[placeholder*="搜索"], input[type="search"]').first().waitFor({ timeout: 8000 }).then(() => 'content'),
      page.waitForURL('**/login**', { timeout: 8000 }).then(() => 'login'),
    ]).catch(() => 'timeout');

    if (loaded === 'login') {
      throw new Error(String('Redirected to login - session expired'))
      return;
    }

    // Search for test dictionary by code
    const codeInput = page.locator('input[placeholder*="字典编码"]').first();
    if (await codeInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await codeInput.fill('mtest_priority');
      // Click search button
      const searchBtn = page.locator('[data-testid="filter-search"]');
      const listRefresh = page.waitForResponse(
        r => r.url().includes('/dict') && r.status() === 200,
        { timeout: 5000 }
      ).catch(() => null);
      await searchBtn.click();
      await listRefresh;
    }

    // Verify dictionary is displayed
    await expect(page.getByText('优先级').first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * Cleanup: Remove test data after all tests
   */
  test.afterAll(async ({ request }) => {
    // Skip cleanup if import wasn't successful
    if (!importResult?.pluginPid) {
      return;
    }

    try {
      // Execute plugin uninstall
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
        console.log('Test plugin uninstalled successfully');
      }
    } catch (error) {
      console.warn('Failed to cleanup test plugin:', error);
    }
  });
});

/**
 * Standalone cleanup test for manual execution
 * Run with: npx playwright test plugin-import.spec.ts -g "cleanup"
 */
test.describe('Plugin Cleanup', () => {
  test('Manual cleanup of test plugin data', async ({ page }) => {
    // This test is skipped by default
    // Uncomment test.skip to enable manual cleanup

    // Get plugin by ID
    const historyResponse = await page.request.get(
      `/api/plugins/import/history?limit=50`
    );
    const history = await historyResponse.json();

    const testPlugin = Array.isArray(history)
      ? history.find((h: any) => h.pluginId === TEST_PLUGIN.pluginId)
      : null;

    if (testPlugin?.pluginPid) {
      const uninstallResponse = await page.request.post(
        `/api/plugins/${testPlugin.pluginPid}/uninstall`,
        {
          data: { force: true, decisions: {} },
        }
      );

      expect(uninstallResponse.ok()).toBe(true);
      console.log('Test plugin cleaned up');
    }
  });
});
