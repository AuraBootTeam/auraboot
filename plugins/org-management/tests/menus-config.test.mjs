import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it } from 'node:test';

const __dirname = dirname(fileURLToPath(import.meta.url));
const menus = JSON.parse(readFileSync(join(__dirname, '../config/menus.json'), 'utf8'));

function menuByCode(code) {
  const menu = menus.find((item) => item.code === code);
  assert.ok(menu, `menu ${code} should exist`);
  return menu;
}

describe('org-management RBAC menu configuration', () => {
  it('keeps the minimal permission management entries discoverable and ordered', () => {
    assert.deepEqual(
      ['org_teams', 'member_management', 'permission_roles', 'permission_management'].map(
        (code) => {
          const menu = menuByCode(code);
          return {
            code: menu.code,
            parentCode: menu.parentCode,
            name: menu['name:zh-CN'] ?? menu.name,
            path: menu.path,
            permissionCode: menu.permissionCode,
            orderNo: menu.orderNo,
            visible: menu.visible,
          };
        },
      ),
      [
        {
          code: 'org_teams',
          parentCode: 'org_management',
          name: '团队',
          path: '/organization/teams',
          permissionCode: 'org_teams',
          orderNo: 10,
          visible: true,
        },
        {
          code: 'member_management',
          parentCode: 'org_management',
          name: '用户',
          path: '/p/tenant_member',
          permissionCode: 'member_management',
          orderNo: 20,
          visible: true,
        },
        {
          code: 'permission_roles',
          parentCode: 'org_management',
          name: '角色',
          path: '/enterprise/permissions',
          permissionCode: 'permission_management',
          orderNo: 30,
          visible: true,
        },
        {
          code: 'permission_management',
          parentCode: 'org_management',
          name: '权限/授权关系',
          path: '/enterprise/permissions',
          permissionCode: 'permission_management',
          orderNo: 40,
          visible: true,
        },
      ],
    );
  });
});
