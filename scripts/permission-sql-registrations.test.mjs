import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { permissionCodesFromSql } from './permission-sql-registrations.mjs';

test('recognizes the deployed System-tenant activation registration without granting tenant roles', () => {
  const sql = readFileSync(new URL('../platform/src/main/resources/db/migration/core/V20260928010000__application_binding_activation_permission.sql', import.meta.url), 'utf8');
  assert.deepEqual([...permissionCodesFromSql(sql)], ['platform.application_binding.activate']);
  assert.match(sql, /t.name='System'/);
  assert.match(sql, /r.code='platform_admin'/);
});
test('reads code column by position, with nested commas and escaped literals', () => {
  assert.deepEqual([...permissionCodesFromSql("INSERT INTO ab_permission (pid, name, code) VALUES (substring(md5('a,b') from 1 for 26), 'it''s registered', 'demo.execute');")], ['demo.execute']);
});
test('rejects references, grants, comments and dynamic codes as registration proof', () => {
  for (const sql of [
    "SELECT 'demo.execute' FROM ab_permission;",
    "INSERT INTO ab_role_permission (code) VALUES ('demo.execute');",
    "-- INSERT INTO ab_permission (code) VALUES ('demo.execute');",
    "/* INSERT INTO ab_permission (code) VALUES ('demo.execute'); */",
    "INSERT INTO ab_permission (name, code) SELECT 'demo.execute', source.code FROM source;",
  ]) assert.equal(permissionCodesFromSql(sql).size, 0, sql);
});
