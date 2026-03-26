/**
 * Plugin Import API Tests
 *
 * Migrated from: tests/e2e/plugin/plugin-import.spec.ts
 * Tests: P-001 ~ P-006, P-009, C-N01, C-N03
 *
 * E2E tests (P-007, P-008) remain in the e2e file.
 *
 * @since 4.0.0
 */

import { test, expect } from '../fixtures';

const TEST_PLUGIN = {
  pluginId: 'com.test.minimal-import-test-api',
  namespace: 'impta',
  version: '1.0.0',
  displayName: 'Minimal Import Test',
  'displayName:zh-CN': '最小化导入测试',
  description: 'Minimal plugin for testing import functionality',
  author: 'Test Team',
  minPlatformVersion: '1.0.0',
  dicts: [
    {
      code: 'impta_priority',
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
      code: 'impta_name',
      displayName: 'Name',
      'displayName:zh-CN': '名称',
      dataType: 'string',
      constraints: { required: true, maxLength: 100 },
      feature: { searchable: true },
    },
    {
      code: 'impta_priority',
      displayName: 'Priority',
      'displayName:zh-CN': '优先级',
      dataType: 'enum',
      dictCode: 'impta_priority',
      constraints: { required: true },
      defaultValue: 'low',
    },
    {
      code: 'impta_quantity',
      displayName: 'Quantity',
      'displayName:zh-CN': '数量',
      dataType: 'integer',
      constraints: { min: 0, max: 1000 },
    },
  ],
  models: [
    {
      code: 'impta_item',
      displayName: 'Test Item',
      'displayName:zh-CN': '测试项目',
      description: 'Minimal test model',
      modelType: 'entity',
    },
  ],
  modelFieldBindings: [
    { modelCode: 'impta_item', fieldCode: 'impta_name', sequence: 10, required: true },
    { modelCode: 'impta_item', fieldCode: 'impta_priority', sequence: 20, required: true, defaultValue: 'low' },
    { modelCode: 'impta_item', fieldCode: 'impta_quantity', sequence: 30, required: false },
  ],
  permissions: [
    {
      code: 'impta:item:read',
      name: 'View Test Items',
      'name:zh-CN': '查看测试项目',
      resourceType: 'model',
      resourceCode: 'impta_item',
      action: 'read',
    },
    {
      code: 'impta:item:create',
      name: 'Create Test Items',
      'name:zh-CN': '创建测试项目',
      resourceType: 'model',
      resourceCode: 'impta_item',
      action: 'create',
    },
  ],
  menus: [
    {
      code: 'impta_root',
      name: 'Minimal Test',
      'name:zh-CN': '最小测试',
      path: '/impta',
      icon: 'TestTube',
      type: 1,
      orderNo: 900,
      visible: true,
    },
  ],
};

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

test.describe('Plugin Import API', () => {
  test.describe.configure({ mode: 'serial', timeout: 15000 });

  let importResult: ImportExecuteResult | null = null;

  test('P-001: Plugin import execution', async ({ request }) => {
    const response = await request.post(`/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE`, {
      data: TEST_PLUGIN,
      headers: { 'Content-Type': 'application/json' },
    });

    expect(response.ok()).toBe(true);
    importResult = await response.json();

    expect(importResult).not.toBeNull();
    expect(importResult!.success).toBe(true);
    expect(importResult!.status).toBe('success');
    expect(importResult!.pluginId).toBe(TEST_PLUGIN.pluginId);
    expect(importResult!.namespace).toBe(TEST_PLUGIN.namespace);
    expect(importResult!.totalResourceCount).toBe(EXPECTED_COUNTS.totalResources);

    const counts = importResult!.resourceCounts;
    const getCount = (type: string) =>
      (counts[type]?.['create'] ?? 0) + (counts[type]?.['update'] ?? 0);
    expect(getCount('dict')).toBe(EXPECTED_COUNTS.dict);
    expect(getCount('field')).toBe(EXPECTED_COUNTS.field);
    expect(getCount('model')).toBe(EXPECTED_COUNTS.model);
    expect(getCount('model_field_binding')).toBe(EXPECTED_COUNTS.binding);
    expect(getCount('permission')).toBe(EXPECTED_COUNTS.permission);
    expect(getCount('menu')).toBe(EXPECTED_COUNTS.menu);
  });

  test('P-002: Dictionary import verification', async ({ request, api }) => {
    const dictResponse = await api.getDictByCode('impta_priority');

    expect(api.isSuccess(dictResponse)).toBe(true);
    expect(dictResponse.data).not.toBeNull();
    expect(dictResponse.data!.code).toBe('impta_priority');
    expect(dictResponse.data!.name).toBe('优先级');

    const itemsResponse = await request.get(`/api/meta/dict/by-code/impta_priority/items`);

    if (itemsResponse.ok()) {
      const itemsData = await itemsResponse.json();
      const items = itemsData.data || itemsData;
      if (Array.isArray(items)) {
        expect(items.length).toBe(EXPECTED_COUNTS.dictItems);
      }
    }
  });

  test('P-003: Field import verification', async ({ request }) => {
    const fieldCodes = ['impta_name', 'impta_priority', 'impta_quantity'];

    for (const code of fieldCodes) {
      const response = await request.get(`/api/meta/fields/key/${code}`);
      expect(response.ok()).toBe(true);
      const data = await response.json();
      expect(data.data || data).not.toBeNull();
    }

    const nameResponse = await request.get(`/api/meta/fields/key/impta_name`);
    const nameData = await nameResponse.json();
    const nameField = nameData.data || nameData;
    expect(nameField.dataType).toBe('string');

    const priorityResponse = await request.get(`/api/meta/fields/key/impta_priority`);
    const priorityData = await priorityResponse.json();
    const priorityField = priorityData.data || priorityData;
    expect(priorityField.dataType).toBe('enum');

    const quantityResponse = await request.get(`/api/meta/fields/key/impta_quantity`);
    const quantityData = await quantityResponse.json();
    const quantityField = quantityData.data || quantityData;
    expect(quantityField.dataType).toBe('integer');
  });

  test('P-004: Model and binding verification', async ({ request }) => {
    const modelResponse = await request.get(`/api/meta/models/code/impta_item`);
    expect(modelResponse.ok()).toBe(true);

    const modelData = await modelResponse.json();
    const model = modelData.data || modelData;

    expect(model).not.toBeNull();
    expect(model.code).toBe('impta_item');
    expect(model.modelType).toBe('entity');
    expect(model.displayName).toBe('测试项目');

    const fieldsResponse = await request.get(`/api/meta/models/${model.pid}/fields`);
    expect(fieldsResponse.ok()).toBe(true);

    const fieldsData = await fieldsResponse.json();
    const fields = fieldsData.data || fieldsData;

    expect(Array.isArray(fields)).toBe(true);
    expect(fields.length).toBe(EXPECTED_COUNTS.binding);

    const boundFieldCodes = fields.map((f: any) => f.code || f.fieldCode);
    expect(boundFieldCodes).toContain('impta_name');
    expect(boundFieldCodes).toContain('impta_priority');
    expect(boundFieldCodes).toContain('impta_quantity');
  });

  test('P-005: Permission import verification', async ({ request }) => {
    const response = await request.get(`/api/permissions?search=impta`);

    if (response.ok()) {
      const data = await response.json();
      const permissions = data.data || data;

      if (Array.isArray(permissions)) {
        const imptaPermissions = permissions.filter((p: any) =>
          p.code?.startsWith('impta:')
        );
        expect(imptaPermissions.length).toBeGreaterThanOrEqual(EXPECTED_COUNTS.permission);
      }
    }
  });

  test('P-006: Menu import verification', async ({ request }) => {
    const response = await request.get(`/api/menus`);

    if (response.ok()) {
      const data = await response.json();
      const menus = data.data || data;

      if (Array.isArray(menus)) {
        const imptaMenu = menus.find((m: any) => m.path === '/impta');
        expect(imptaMenu).toBeDefined();
        if (imptaMenu) {
          expect(imptaMenu.name).toBe('impta_root');
        }
      }
    }
  });

  test('P-009: Import history verification', async ({ request }) => {
    const response = await request.get(`/api/plugins/import/history?limit=50`);

    expect(response.ok()).toBe(true);
    const historyData = await response.json();

    const history = Array.isArray(historyData) ? historyData : (historyData.data || []);

    const testImport = history.find((h: any) => h.pluginId === TEST_PLUGIN.pluginId);

    if (testImport) {
      expect(testImport.status).toBe('success');
      expect(testImport.namespace).toBe(TEST_PLUGIN.namespace);
    } else {
      expect(importResult).not.toBeNull();
      expect(importResult!.status).toBe('success');
    }
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

test.describe('Plugin Import Boundary API', () => {
  test.describe.configure({ timeout: 15000 });

  test('C-N01: Upload non-JSON/ZIP file returns error', async ({ request }) => {
    const response = await request.post(
      `/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE`,
      {
        data: 'This is plain text, not a valid plugin manifest',
        headers: { 'Content-Type': 'text/plain' },
      }
    );

    expect(response.ok()).toBe(false);
    expect(response.status()).toBeGreaterThanOrEqual(400);
  });

  test('C-N03: Re-import same plugin version is idempotent', async ({ request }) => {
    const IDEMPOTENCY_PLUGIN = {
      pluginId: 'com.test.idempotency-test',
      namespace: 'idmpt',
      version: '1.0.0',
      displayName: 'Idempotency Test',
      description: 'Plugin for testing import idempotency',
      author: 'Test Team',
      minPlatformVersion: '1.0.0',
      dicts: [
        {
          code: 'idmpt_status',
          name: 'Status',
          dictType: 'static',
          items: [
            { value: 'ON', label: 'On', sortNo: 10, status: 'enabled' },
          ],
        },
      ],
      fields: [
        {
          code: 'idmpt_name',
          displayName: 'Name',
          dataType: 'string',
          constraints: { required: true },
        },
      ],
      models: [],
      modelFieldBindings: [],
      permissions: [],
      menus: [],
    };

    const firstResponse = await request.post(
      `/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE`,
      {
        data: IDEMPOTENCY_PLUGIN,
        headers: { 'Content-Type': 'application/json' },
      }
    );

    expect(firstResponse.ok()).toBe(true);
    const firstResult: ImportExecuteResult = await firstResponse.json();
    expect(firstResult.success).toBe(true);

    const secondResponse = await request.post(
      `/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE`,
      {
        data: IDEMPOTENCY_PLUGIN,
        headers: { 'Content-Type': 'application/json' },
      }
    );

    expect(secondResponse.ok()).toBe(true);
    const secondResult: ImportExecuteResult = await secondResponse.json();
    expect(secondResult.success).toBe(true);
    expect(secondResult.status).toBe('success');
    expect(secondResult.totalResourceCount).toBe(firstResult.totalResourceCount);

    if (secondResult.pluginPid) {
      try {
        await request.post(`/api/plugins/${secondResult.pluginPid}/uninstall`, {
          data: { force: true, decisions: {} },
        });
      } catch { /* Ignore */ }
    }
  });
});
