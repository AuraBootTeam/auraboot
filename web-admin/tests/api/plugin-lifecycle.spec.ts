/**
 * Plugin Lifecycle API Tests
 *
 * Migrated from: tests/e2e/plugin/plugin-lifecycle.spec.ts
 * Tests: C2-E02, C2-E05, C2-E06
 *
 * E2E tests (C2-E01, C2-E03, C2-E04) remain in the e2e file.
 *
 * @since 4.0.0
 */

import { test, expect } from '@playwright/test';

const ASSET_MANAGEMENT_PLUGIN = {
  pluginId: 'com.test.asset-management-lifecycle-api',
  namespace: 'amla',
  version: '1.0.0',
  displayName: 'Asset Management API',
  'displayName:zh-CN': '资产管理API',
  description: 'Asset management plugin for lifecycle API testing',
  author: 'E2E Test Team',
  minPlatformVersion: '1.0.0',
  dicts: [
    {
      code: 'amla_asset_category',
      name: 'Asset Category',
      'name:zh-CN': '资产类别',
      dictType: 'static',
      items: [
        {
          value: 'it_equipment',
          label: 'IT Equipment',
          'label:zh-CN': 'IT设备',
          sortNo: 10,
          status: 'enabled',
        },
        {
          value: 'office_furniture',
          label: 'Office Furniture',
          'label:zh-CN': '办公家具',
          sortNo: 20,
          status: 'enabled',
        },
      ],
    },
  ],
  fields: [
    {
      code: 'amla_asset_name',
      displayName: 'Asset Name',
      'displayName:zh-CN': '资产名称',
      dataType: 'string',
      constraints: { required: true, maxLength: 200 },
    },
    {
      code: 'amla_category',
      displayName: 'Category',
      'displayName:zh-CN': '资产类别',
      dataType: 'enum',
      dictCode: 'amla_asset_category',
      constraints: { required: true },
    },
  ],
  models: [
    {
      code: 'amla_asset',
      displayName: 'Asset',
      'displayName:zh-CN': '资产',
      description: 'Asset entity for API lifecycle testing',
      modelType: 'entity',
    },
  ],
  modelFieldBindings: [
    { modelCode: 'amla_asset', fieldCode: 'amla_asset_name', sequence: 10, required: true },
    { modelCode: 'amla_asset', fieldCode: 'amla_category', sequence: 20, required: true },
  ],
  permissions: [
    {
      code: 'amla:asset:read',
      name: 'View Assets',
      'name:zh-CN': '查看资产',
      resourceType: 'model',
      resourceCode: 'amla_asset',
      action: 'read',
    },
  ],
  menus: [
    {
      code: 'amla_root',
      name: 'Asset Mgmt API',
      'name:zh-CN': '资产管理API',
      path: '/amla',
      icon: 'Package',
      type: 1,
      orderNo: 801,
      visible: true,
    },
  ],
};

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

test.describe('Plugin Lifecycle API', () => {
  test.describe.configure({ mode: 'serial', timeout: 30000 });

  let importResult: ImportExecuteResult | null = null;

  test('C2-E02: Import plugin via API', async ({ request }) => {
    const response = await request.post(
      `/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE`,
      {
        data: ASSET_MANAGEMENT_PLUGIN,
        headers: { 'Content-Type': 'application/json' },
      },
    );

    expect(response.ok()).toBe(true);
    importResult = await response.json();

    expect(importResult).not.toBeNull();
    expect(importResult!.success).toBe(true);
    expect(importResult!.status).toBe('success');
    expect(importResult!.pluginPid).toBeTruthy();

    const counts = importResult!.resourceCounts;
    const getCount = (type: string) =>
      (counts[type]?.['create'] ?? 0) + (counts[type]?.['update'] ?? 0);
    expect(getCount('dict')).toBe(1);
    expect(getCount('field')).toBe(2);
    expect(getCount('model')).toBe(1);
  });

  test('C2-E05: Disable plugin via API', async ({ request }) => {
    expect(importResult).not.toBeNull();
    expect(importResult!.pluginPid).toBeTruthy();

    const disableResponse = await request.post(`/api/plugins/${importResult!.pluginPid}/disable`);
    expect(disableResponse.ok()).toBe(true);

    const disableData = await disableResponse.json();
    const plugin = disableData.data || disableData;
    expect(plugin.status).toBe('stopped');

    const statusResponse = await request.get(`/api/plugins/${importResult!.pluginPid}`);
    expect(statusResponse.ok()).toBe(true);

    const statusData = await statusResponse.json();
    const pluginStatus = statusData.data || statusData;
    expect(pluginStatus.status).toBe('stopped');
  });

  test('C2-E06: Enable plugin via API', async ({ request }) => {
    expect(importResult).not.toBeNull();
    expect(importResult!.pluginPid).toBeTruthy();

    const enableResponse = await request.post(`/api/plugins/${importResult!.pluginPid}/enable`);
    expect(enableResponse.ok()).toBe(true);

    const enableData = await enableResponse.json();
    const plugin = enableData.data || enableData;
    expect(plugin.status).toBe('active');

    const statusResponse = await request.get(`/api/plugins/${importResult!.pluginPid}`);
    expect(statusResponse.ok()).toBe(true);

    const statusData = await statusResponse.json();
    const pluginStatus = statusData.data || statusData;
    expect(pluginStatus.status).toBe('active');

    const modelResponse = await request.get(`/api/meta/models/code/amla_asset`);
    expect(modelResponse.ok()).toBe(true);
  });

  test.afterAll(async ({ request }) => {
    if (!importResult?.pluginPid) return;
    try {
      await request.post(`/api/plugins/${importResult.pluginPid}/uninstall`, {
        data: { force: true, decisions: {} },
      });
    } catch (error) {
      console.warn('Failed to cleanup test plugin:', error);
    }
  });
});
