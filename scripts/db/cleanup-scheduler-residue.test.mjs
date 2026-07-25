import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const script = 'scripts/db/cleanup-scheduler-residue.sh';
const source = readFileSync(script, 'utf8');

function fakePsql() {
  const dir = mkdtempSync(path.join(tmpdir(), 'scheduler-residue-test-'));
  const capture = path.join(dir, 'sql.log');
  const executable = path.join(dir, 'psql');
  writeFileSync(executable, `#!/usr/bin/env bash
printf '%s\\n' '--- invocation ---' >> "$FAKE_PSQL_CAPTURE"
cat >> "$FAKE_PSQL_CAPTURE"
printf '%s\\n' 'phantom_task_count=3'
printf '%s\\n' 'inbox_mark_expired_candidates=7'
printf '%s\\n' 'inbox_delete_after_first_run_candidates=11'
`);
  chmodSync(executable, 0o755);
  return { dir, capture };
}

function run(mode) {
  const fake = fakePsql();
  const result = spawnSync('bash', [script, mode], {
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${fake.dir}:${process.env.PATH}`,
      FAKE_PSQL_CAPTURE: fake.capture,
      PG_HOST: 'db.example',
      PG_PORT: '5544',
      PG_USER: 'operator',
      PG_DB: 'aura_ops',
      PG_PASSWORD: 'redacted-test-secret',
    },
  });
  return {
    ...result,
    sql: readFileSync(fake.capture, 'utf8'),
  };
}

test('check mode is read-only and reports both scheduler and inbox impact', () => {
  const result = run('--check');

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /scheduler_residue_mode=check/);
  assert.match(result.stdout, /database_target=db\.example:5544\/aura_ops/);
  assert.match(result.stdout, /result=read_only_check_complete/);
  assert.match(result.sql, /sys-marketplace-upgrade/);
  assert.match(result.sql, /sys-ai-suggestion/);
  assert.match(result.sql, /sys-license-validation/);
  assert.match(result.sql, /inbox_delete_after_first_run_candidates/);
  assert.doesNotMatch(result.sql, /DELETE FROM ab_scheduled_task/);
  assert.doesNotMatch(result.stdout, /redacted-test-secret/);
});

test('apply mode is transactional and scoped to exact system rows', () => {
  const result = run('--apply');

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /scheduler_residue_mode=apply/);
  assert.match(result.stdout, /result=apply_complete/);
  assert.match(result.sql, /BEGIN;/);
  assert.match(result.sql, /DELETE FROM ab_scheduled_task/);
  assert.match(result.sql, /WHERE tenant_id IS NULL/);
  assert.match(result.sql, /RETURNING pid/);
  assert.match(result.sql, /COMMIT;/);
  assert.doesNotMatch(result.sql, /DELETE FROM ab_inbox_item/);
});

test('unknown arguments fail before opening a database connection', () => {
  const result = spawnSync('bash', [script, '--force'], {
    encoding: 'utf8',
  });

  assert.equal(result.status, 2);
  assert.match(result.stderr, /unknown argument '--force'/);
});

test('source keeps the deletion allowlist closed and tenant-scoped', () => {
  const deleteBlock = source.match(
    /WITH deleted AS \([\s\S]*?RETURNING pid[\s\S]*?\)\nSELECT/,
  )?.[0];

  assert.ok(deleteBlock, 'transactional delete block must exist');
  assert.match(deleteBlock, /tenant_id IS NULL/);
  assert.equal((deleteBlock.match(/'sys-/g) ?? []).length, 3);
  assert.doesNotMatch(deleteBlock, /LIKE|handler_bean|name\s*=/);
});
