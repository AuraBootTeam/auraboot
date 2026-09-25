import assert from 'node:assert/strict';
import test from 'node:test';
import { shadowBindingRolePolicy } from './shadow-binding-role-policy.mjs';
const input = { schema: 'public', runtimeRole: 'runtime_account', shadowRole: 'shadow_account', ownerRole: 'migration_owner' };
test('unsafe or overlapping role inputs cannot generate executable SQL', () => {
  for (const key of Object.keys(input)) {
    for (const value of ['', undefined, 'a;drop table t', 'a"b', 'pg_read_all_data', 'a'.repeat(64)]) {
      assert.throws(() => shadowBindingRolePolicy({ ...input, [key]: value }));
    }
  }
  assert.throws(() => shadowBindingRolePolicy({ ...input, shadowRole: input.ownerRole }));
  assert.throws(() => shadowBindingRolePolicy({ ...input, runtimeRole: 'public' }));
  assert.match(shadowBindingRolePolicy(input), /COMMIT;\n$/);
});
