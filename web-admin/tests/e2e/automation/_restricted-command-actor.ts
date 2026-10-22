import { expect, type Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { uniqueId } from '../helpers';

type Permission = { id: string | number; code: string; children?: Permission[] };

export async function provisionRestrictedCommandActor(admin: Page) {
  const code = uniqueId('cmd_deny').replace(/[^a-zA-Z0-9_]/g, '_');
  const roleResponse = await admin.request.post('/api/roles', {
    data: {
      code,
      name: code,
      description: 'Owned automation command-denial fixture',
      type: 'custom',
      status: 'active',
      scopeType: 'tenant',
      defaultDataScopeType: 'all',
    },
  });
  expect(roleResponse.status()).toBe(200);
  const role = await roleResponse.json();
  expect(role.code).toBe('0');
  expect(role.data.pid).toEqual(expect.any(String));

  const treeResponse = await admin.request.get('/api/permissions/tree');
  expect(treeResponse.status()).toBe(200);
  // Permission IDs are database longs; preserve their exact decimal representation.
  const tree = JSON.parse((await treeResponse.text()).replace(/("id"\s*:\s*)(\d{16,})/g, '$1"$2"'));
  expect(tree.code).toBe('0');
  const permissions: Permission[] = [];
  function visit(nodes: Permission[]) {
    for (const node of nodes) {
      permissions.push(node);
      visit(node.children || []);
    }
  }
  visit(tree.data);
  const required = [
    'automation.read',
    'automation.update',
    'meta.model.read',
    'meta.field.read',
    'meta.command.read',
    'meta.page.read',
    'model.e2et_order.create',
    'model.e2et_order.read',
    'e2et.order.read',
  ];
  const grants = required.map((code) => {
    const matches = permissions.filter((permission) => permission.code === code);
    expect(matches, `exact permission fixture: ${code}`).toHaveLength(1);
    return { permissionId: matches[0].id, granted: true };
  });
  const grantResponse = await admin.request.put(`/api/permissions/matrix/${role.data.pid}/batch`, {
    data: grants,
  });
  expect(grantResponse.status()).toBe(200);
  expect((await grantResponse.json()).code).toBe('0');

  const account = { email: `${code}@e2e.local`, password: DEFAULT_TEST_ACCOUNT.password };
  const userResponse = await admin.request.post('/api/admin/users', {
    data: {
      email: account.email,
      displayName: code,
      initialPassword: account.password,
      roleCodes: [code],
      sendInviteEmail: false,
    },
  });
  expect(userResponse.status()).toBe(200);
  expect((await userResponse.json()).code).toBe('0');
  return account;
}
