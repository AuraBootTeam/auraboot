import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, it } from 'node:test';

import {
  classifyMigrationStatement,
  splitMigrationDirectory,
  splitSharedSeedStatement,
  splitSqlStatements,
  writeMigrationSplit,
} from './migration-ownership.mjs';

it('keeps unrelated core rows out of CRM shared-seed artifacts', () => {
  const parts = splitSharedSeedStatement(`
    INSERT INTO ab_agent_capability (pid, capability_code, object_patterns) VALUES
    ('CAP_CRM', 'crm.query', '["crm_*"]'),
    ('CAP_PM', 'pm.query', '["pm_*"]'),
    ('CAP_GENERIC', 'generic.query', '["*"]')
    ON CONFLICT DO NOTHING;
  `);
  assert.equal(parts.length, 2);
  const crm = parts.find((part) => part.includes('CAP_CRM'));
  const core = parts.find((part) => part.includes('CAP_PM'));
  assert.ok(crm);
  assert.ok(core);
  assert.doesNotMatch(crm, /CAP_PM|CAP_GENERIC/);
  assert.match(core, /CAP_PM/);
  assert.match(core, /CAP_GENERIC/);
});

describe('application migration ownership', () => {
  it('keeps semicolons inside PostgreSQL strings and dollar-quoted blocks intact', () => {
    const statements = splitSqlStatements(`
      CREATE TABLE core_table (value text DEFAULT ';');
      DO $$ BEGIN PERFORM ';'; END $$;
      -- a trailing comment with ;
      INSERT INTO core_table VALUES ('done');
    `);
    assert.equal(statements.length, 3);
    assert.match(statements[1], /PERFORM ';'/);
  });

  it('assigns owned schema statements and product seed rows away from core', () => {
    assert.equal(classifyMigrationStatement('CREATE TABLE ab_bpm_rule(id bigint);').owner, 'bpm');
    assert.equal(classifyMigrationStatement('ALTER TABLE se_task_instance ADD COLUMN extra jsonb;').owner, 'bpm');
    assert.equal(classifyMigrationStatement('DROP INDEX IF EXISTS idx_bpm_notify_recipient;').owner, 'bpm');
    assert.equal(classifyMigrationStatement('CREATE TABLE mt_crm_complaint(id bigint);').owner, 'crm');
    assert.equal(
      classifyMigrationStatement("INSERT INTO ab_capability(code) VALUES ('crm.manage');").owner,
      'crm',
    );
    assert.equal(
      classifyMigrationStatement("CREATE TABLE ab_event_policy(source_type text); -- BPM is a provider").owner,
      'core',
    );
    assert.equal(
      classifyMigrationStatement("CREATE TABLE ab_automation(trigger_type text CHECK (trigger_type IN ('on_bpm_event')));").owner,
      'core',
    );
  });

  it('creates collision-free core and product migration sets', () => {
    const source = mkdtempSync(join(tmpdir(), 'aura-migration-source-'));
    const output = mkdtempSync(join(tmpdir(), 'aura-migration-output-'));
    writeFileSync(join(source, 'V1__baseline.sql'), `
      CREATE TABLE ab_core(id bigint);
      CREATE TABLE ab_bpm_rule(id bigint);
      CREATE TABLE mt_crm_complaint(id bigint);
    `);
    const manifest = writeMigrationSplit(source, output);
    assert.equal(manifest.outputs.coreStatementCount, 1);
    assert.equal(manifest.outputs.bpmStatementCount, 1);
    assert.equal(manifest.outputs.crmStatementCount, 1);
    assert.doesNotMatch(readFileSync(join(output, 'core/V1__baseline.sql'), 'utf8'), /bpm|crm/i);
    assert.match(
      readFileSync(join(output, 'product/crm/V20260912020000__crm_extracted_from_core.sql'), 'utf8'),
      /mt_crm_complaint/,
    );
  });

  it('does not publish a Flyway migration that contains comments only', () => {
    const source = mkdtempSync(join(tmpdir(), 'aura-migration-comment-only-'));
    writeFileSync(join(source, 'V1__comment.sql'), '-- no executable SQL; product name BPM\n');
    assert.deepEqual(splitMigrationDirectory(source).coreFiles, []);
  });

  it('fails closed when one statement mixes product owners', () => {
    const source = mkdtempSync(join(tmpdir(), 'aura-migration-mixed-'));
    const output = join(source, 'out');
    mkdirSync(output);
    writeFileSync(
      join(source, 'V1__mixed.sql'),
      "INSERT INTO ab_capability(code) VALUES ('crm.manage'), ('bpm.manage');",
    );
    const split = splitMigrationDirectory(source);
    assert.equal(split.mixed.length, 1);
    assert.throws(() => writeMigrationSplit(source, output), /mix CRM and BPM ownership/);
  });
});
