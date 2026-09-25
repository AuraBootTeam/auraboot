import { test } from 'node:test';
import assert from 'node:assert/strict';
import { registryRolePolicy } from './registry-role-policy.mjs';
const fixture = { schema: 'public', runtimeRole: 'app_runtime', registrarRole: 'app_registrar', ownerRole: 'app_migrator' };
test('rejects SQL injection, missing identities and overlapping roles', () => {
  for (const key of Object.keys(fixture)) {
    for (const value of [undefined, '', 'a; DROP ROLE x', 'a"x', 'pg_read_all_data']) {
      assert.throws(() => registryRolePolicy({ ...fixture, [key]: value }));
    }
  }
  assert.throws(() => registryRolePolicy({ ...fixture, registrarRole: fixture.runtimeRole }));
  assert.throws(() => registryRolePolicy({ ...fixture, runtimeRole: 'public' }));
});
test('emits one explicit transaction with scoped grants and privilege preconditions', () => {
  const sql = registryRolePolicy(fixture);
  assert.match(sql, /BEGIN;/);
  assert.match(sql, /COMMIT;\n$/);
  assert.match(sql, /pg_auth_members/);
  assert.match(sql, /NOT rolsuper AND NOT rolcreaterole/);
  assert.match(sql, /has_schema_privilege/);
  assert.match(sql, /GRANT UPDATE \(next_release_sequence\)/);
  assert.doesNotMatch(sql, /CREATE ROLE|ALTER ROLE|SECURITY DEFINER|GRANT ALL/);
  assert.doesNotMatch(sql, /GRANT INSERT[^;]+TO "app_runtime"/);
});
