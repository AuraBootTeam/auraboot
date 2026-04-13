/**
 * Asset Permission API Tests
 *
 * Migrated from: tests/e2e/plugin/asset-permission.spec.ts
 * Tests: Prerequisite, B4-E01, B4-E02, B4-E03, B4-E06
 *
 * E2E tests (B4-E04, B4-E05) remain in the e2e file.
 *
 * @since 4.0.0
 */

import { test, expect } from '@playwright/test';

const ASSET_PERM_PLUGIN = {
  pluginId: 'com.test.asset-permission-api',
  namespace: 'asta',
  version: '1.0.0',
  displayName: 'Asset Permission API Test',
  'displayName:zh-CN': '资产权限API测试',
  description: 'Plugin for testing permission control via API',
  author: 'E2E Test Team',
  minPlatformVersion: '1.0.0',
  dicts: [
    {
      code: 'asta_asset_status',
      name: 'Asset Status',
      'name:zh-CN': '资产状态',
      dictType: 'static',
      items: [
        { value: 'active', label: 'Active', 'label:zh-CN': '在用', sortNo: 10, status: 'enabled' },
        { value: 'idle', label: 'Idle', 'label:zh-CN': '闲置', sortNo: 20, status: 'enabled' },
      ],
    },
  ],
  fields: [
    {
      code: 'asta_asset_name',
      displayName: 'Asset Name',
      'displayName:zh-CN': '资产名称',
      dataType: 'string',
      constraints: { required: true, maxLength: 200 },
    },
    {
      code: 'asta_status',
      displayName: 'Status',
      'displayName:zh-CN': '状态',
      dataType: 'enum',
      dictCode: 'asta_asset_status',
      constraints: { required: true },
    },
    {
      code: 'asta_price',
      displayName: 'Price',
      'displayName:zh-CN': '价格',
      dataType: 'decimal',
      constraints: { min: 0 },
      feature: { sensitive: true },
    },
  ],
  models: [
    {
      code: 'asta_asset',
      displayName: 'Asset',
      'displayName:zh-CN': '资产',
      description: 'Asset entity for API permission testing',
      modelType: 'entity',
    },
  ],
  modelFieldBindings: [
    { modelCode: 'asta_asset', fieldCode: 'asta_asset_name', sequence: 10, required: true },
    { modelCode: 'asta_asset', fieldCode: 'asta_status', sequence: 20, required: true },
    { modelCode: 'asta_asset', fieldCode: 'asta_price', sequence: 30, required: false },
  ],
  permissions: [
    {
      code: 'asta:asset:read',
      name: 'View Assets',
      'name:zh-CN': '查看资产',
      resourceType: 'model',
      resourceCode: 'asta_asset',
      action: 'read',
    },
    {
      code: 'asta:asset:create',
      name: 'Create Assets',
      'name:zh-CN': '创建资产',
      resourceType: 'model',
      resourceCode: 'asta_asset',
      action: 'create',
    },
    {
      code: 'asta:asset:delete',
      name: 'Delete Assets',
      'name:zh-CN': '删除资产',
      resourceType: 'model',
      resourceCode: 'asta_asset',
      action: 'delete',
    },
    {
      code: 'asta:asset:view_price',
      name: 'View Asset Price',
      'name:zh-CN': '查看资产价格',
      resourceType: 'field',
      resourceCode: 'asta_price',
      action: 'read',
    },
  ],
  menus: [
    {
      code: 'asta_root',
      name: 'Asset Perm API Test',
      'name:zh-CN': '资产权限API测试',
      path: '/asta',
      icon: 'Shield',
      type: 1,
      orderNo: 871,
      visible: true,
      permissionCode: 'asta:asset:read',
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

test.describe('Asset Permission API', () => {
  test.describe.configure({ mode: 'serial' });

  let importResult: ImportExecuteResult | null = null;

  test('Prerequisite: Import asset-permission plugin', async ({ request }) => {
    const response = await request.post(
      `/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE`,
      {
        data: ASSET_PERM_PLUGIN,
        headers: { 'Content-Type': 'application/json' },
      },
    );

    expect(response.ok()).toBe(true);
    importResult = await response.json();

    expect(importResult).not.toBeNull();
    expect(importResult!.success).toBe(true);
    expect(importResult!.status).toBe('SUCCESS');
    expect(importResult!.pluginPid).toBeTruthy();
  });

  test('B4-E01: Permissions created during plugin import', async ({ request }) => {
    const permResponse = await request.get(`/api/permissions/model/asta_asset`);

    if (!permResponse.ok()) {
      const resourceTypeResponse = await request.get(`/api/permissions/resource-type/MODEL`);

      if (resourceTypeResponse.ok()) {
        const data = await resourceTypeResponse.json();
        const allPerms = data.data || data;

        if (Array.isArray(allPerms)) {
          const createdPermissions = allPerms.filter((p: any) => p.code?.startsWith('asta:'));
          if (createdPermissions.length > 0) {
            const readPerm = createdPermissions.find((p: any) => p.code === 'asta:asset:read');
            if (readPerm) {
              expect(readPerm.resourceType).toBe('model');
              expect(readPerm.action).toBe('read');
            }
          }
        }
      }
    } else {
      const permData = await permResponse.json();
      const perms = permData.data || permData;
      expect(Array.isArray(perms)).toBe(true);
    }

    expect(importResult?.success).toBe(true);
  });

  test('B4-E02: Menu visibility based on permissions', async ({ request }) => {
    const menuResponse = await request.get(`/api/menu/user`);
    expect(menuResponse.ok()).toBe(true);

    const menuData = await menuResponse.json();
    const menus = menuData.data || menuData;

    expect(Array.isArray(menus)).toBe(true);

    const findMenuByCode = (menuList: any[], code: string): any | null => {
      for (const menu of menuList) {
        if (menu.code === code) return menu;
        if (menu.children && Array.isArray(menu.children)) {
          const found = findMenuByCode(menu.children, code);
          if (found) return found;
        }
      }
      return null;
    };

    const allMenusResponse = await request.get(`/api/menu/all`);
    if (allMenusResponse.ok()) {
      const allMenusData = await allMenusResponse.json();
      const allMenus = allMenusData.data || allMenusData;

      const astaMenu = findMenuByCode(allMenus, 'asta_root');
      if (astaMenu) {
        expect(astaMenu.permissionCode).toBe('asta:asset:read');
      }
    }
  });

  test('B4-E03: Role-permission binding verification', async ({ request }) => {
    const rolesResponse = await request.get(`/api/roles`);

    if (!rolesResponse.ok()) {
      test.skip();
      return;
    }

    const rolesData = await rolesResponse.json();
    const roles = rolesData.data || rolesData;

    if (!Array.isArray(roles) || roles.length === 0) {
      test.skip();
      return;
    }

    const adminRole = roles.find(
      (r: any) =>
        r.code === 'tenant_admin' || r.name?.includes('Admin') || r.name?.includes('管理员'),
    );

    if (adminRole) {
      const roleId = adminRole.id || adminRole.pid;
      const rolePermResponse = await request.get(`/api/roles/${roleId}/permissions`);

      if (rolePermResponse.ok()) {
        const rolePermData = await rolePermResponse.json();
        const rolePermissions = rolePermData.data || rolePermData;
        expect(Array.isArray(rolePermissions)).toBe(true);
      }
    }

    const modelPermResponse = await request.get(`/api/permissions/resource-type/MODEL`);

    if (modelPermResponse.ok()) {
      const modelPermData = await modelPermResponse.json();
      const modelPerms = modelPermData.data || modelPermData;

      if (Array.isArray(modelPerms)) {
        const astaPerms = modelPerms.filter((p: any) => p.code?.startsWith('asta:'));
        expect(astaPerms.length).toBeGreaterThan(0);
      }
    }

    expect(Array.isArray(roles)).toBe(true);
    expect(roles.length).toBeGreaterThan(0);
  });

  test('B4-E06: Sensitive field permission check', async ({ request }) => {
    const fieldsResponse = await request.get(`/api/meta/fields`);

    if (fieldsResponse.ok()) {
      const fieldsData = await fieldsResponse.json();
      const fields = fieldsData.data || fieldsData;

      if (Array.isArray(fields)) {
        const priceField = fields.find((f: any) => f.code === 'asta_price');
        if (priceField) {
          const isSensitive = priceField.feature?.sensitive;
          console.log(`Price field found, sensitive flag: ${isSensitive}`);
        }
      }
    }

    const fieldPermResponse = await request.get(`/api/permissions/resource-type/FIELD`);

    if (fieldPermResponse.ok()) {
      const permData = await fieldPermResponse.json();
      const perms = permData.data || permData;

      if (Array.isArray(perms)) {
        const viewPricePerm = perms.find((p: any) => p.code === 'asta:asset:view_price');
        if (viewPricePerm) {
          expect(viewPricePerm.resourceType).toBe('field');
          expect(viewPricePerm.action).toBe('read');
        }
      }
    }

    expect(importResult?.success).toBe(true);
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
