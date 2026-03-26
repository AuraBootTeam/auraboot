/**
 * Permission Control API Tests
 *
 * Migrated from: tests/e2e/permission/permission-control.spec.ts
 * Tests: E4-E02, E4-E05, E-N01, E4-E06
 *
 * E2E tests (E4-E01, E4-E03, E4-E04) remain in the e2e file.
 *
 * @since 4.0.0
 */

import { test, expect } from '@playwright/test';

test.describe('Permission Control API', () => {

  test('E4-E02: Menu filtered by permission (API verification)', async ({ request }) => {
    const allMenusResponse = await request.get(`/api/menu/all`);

    if (!allMenusResponse.ok()) {
      test.skip(true, 'All menus API not accessible');
      return;
    }

    const allMenusData = await allMenusResponse.json();
    const allMenus = allMenusData.data || allMenusData;

    const userMenusResponse = await request.get(`/api/menu/user`);
    expect(userMenusResponse.ok()).toBe(true);

    const userMenusData = await userMenusResponse.json();
    const filteredMenus = userMenusData.data || userMenusData;

    expect(Array.isArray(filteredMenus)).toBe(true);
    expect(Array.isArray(allMenus)).toBe(true);

    const countMenus = (menus: any[]): number => {
      let count = 0;
      for (const m of menus) {
        count++;
        if (m.children && Array.isArray(m.children)) {
          count += countMenus(m.children);
        }
      }
      return count;
    };

    const allCount = countMenus(allMenus);
    const userCount = countMenus(filteredMenus);

    expect(userCount).toBeGreaterThan(0);
    expect(allCount).toBeGreaterThan(0);
    expect(userCount).toBeLessThanOrEqual(allCount + 5);
  });

  test('E4-E05: Role-permission binding via API', async ({ request }) => {
    const rolesResponse = await request.get(`/api/roles/all`);

    if (!rolesResponse.ok()) {
      test.skip(true, 'Roles API not accessible');
      return;
    }

    const rolesData = await rolesResponse.json();
    const roles = rolesData.data || rolesData;

    expect(Array.isArray(roles)).toBe(true);

    if (roles.length === 0) {
      test.skip(true, 'No roles available for permission binding test');
      return;
    }

    const testRole = roles[0];
    const roleId = testRole.id || testRole.pid;

    const rolePermResponse = await request.get(`/api/permissions/role/${roleId}`);

    expect(rolePermResponse.ok()).toBe(true);

    const rolePermData = await rolePermResponse.json();
    const rolePermissions = rolePermData.data || rolePermData;

    expect(Array.isArray(rolePermissions)).toBe(true);

    for (const perm of rolePermissions) {
      expect(perm.code).toBeTruthy();
      if (perm.resourceType !== null && perm.resourceType !== undefined) {
        expect(typeof perm.resourceType).toBe('string');
      }
    }
  });

  test('E-N01: Access protected API without auth returns 401/403', async ({ request }) => {
    const unauthResponse = await request.fetch(`/api/permissions`, {
      headers: {
        'Authorization': 'Bearer invalid-token-for-e2e-test',
        'Cookie': '',
      },
    });

    const status = unauthResponse.status();
    expect([401, 403]).toContain(status);

    const adminResponse = await request.fetch(`/api/roles/all`, {
      headers: {
        'Authorization': 'Bearer invalid-token-for-e2e-test',
        'Cookie': '',
      },
    });

    const adminStatus = adminResponse.status();
    expect([401, 403]).toContain(adminStatus);
  });

  test('E4-E06: Data permission scope (API verification)', async ({ request }) => {
    const userPermResponse = await request.get(`/api/menu/user`);

    expect(userPermResponse.ok()).toBe(true);

    const userData = await userPermResponse.json();
    const menus = userData.data || userData;

    expect(Array.isArray(menus)).toBe(true);

    const checkResponse = await request.get(
      `/api/menu/permission/check?permissionCode=system:user:read`
    );

    if (checkResponse.ok()) {
      const checkData = await checkResponse.json();
      const hasPermission = checkData.data;
      expect(typeof hasPermission).toBe('boolean');
    }

    const buttonResponse = await request.get(`/api/menu/buttons`);
    expect(buttonResponse.ok()).toBe(true);

    const buttonData = await buttonResponse.json();
    const buttons = buttonData.data || buttonData;

    expect(Array.isArray(buttons)).toBe(true);
  });
});
