#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_ROOT = dirname(fileURLToPath(import.meta.url));
const DEFAULT_REPO_ROOT = resolve(SCRIPT_ROOT, '../..');

const BPM_TABLES = new Set([
  'ab_automation_node_execution',
  'ab_bpm_audit_record',
  'ab_bpm_definition',
  'ab_bpm_domain_config',
  'ab_bpm_execution_log',
  'ab_bpm_node_hook',
  'ab_bpm_notify_record',
  'ab_bpm_process_definition',
  'ab_bpm_rule',
  'ab_bpm_signature_record',
  'ab_bpm_trigger_definition',
  'ab_chain_execution',
  'ab_event_log',
  'ab_node_interceptor',
  'ab_saga_execution',
  'ab_sla_config',
  'ab_sla_record',
]);

const CRM_TABLES = new Set([
  'ab_calendar_event_map',
  'mt_crm_complaint',
]);

const sha256 = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`;

function parseArgs(argv) {
  const options = {
    source: resolve(DEFAULT_REPO_ROOT, 'platform/src/main/resources/db/migration/core'),
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--source') options.source = resolve(argv[++index]);
    else if (argument === '--output') options.output = resolve(argv[++index]);
    else throw new Error(`unknown argument: ${argument}`);
  }
  if (!options.output) throw new Error('--output is required');
  return options;
}

/**
 * Split PostgreSQL SQL without breaking semicolons inside strings, comments, or
 * dollar-quoted PL/pgSQL bodies. Each returned value preserves its comments so
 * generated migrations remain auditable against the released source file.
 */
export function splitSqlStatements(sql) {
  const statements = [];
  let start = 0;
  let index = 0;
  let state = 'normal';
  let dollarTag = '';

  while (index < sql.length) {
    const current = sql[index];
    const next = sql[index + 1];
    if (state === 'line-comment') {
      if (current === '\n') state = 'normal';
      index += 1;
      continue;
    }
    if (state === 'block-comment') {
      if (current === '*' && next === '/') {
        state = 'normal';
        index += 2;
      } else index += 1;
      continue;
    }
    if (state === 'single-quote') {
      if (current === "'" && next === "'") index += 2;
      else if (current === "'") {
        state = 'normal';
        index += 1;
      } else index += 1;
      continue;
    }
    if (state === 'double-quote') {
      if (current === '"' && next === '"') index += 2;
      else if (current === '"') {
        state = 'normal';
        index += 1;
      } else index += 1;
      continue;
    }
    if (state === 'dollar-quote') {
      if (sql.startsWith(dollarTag, index)) {
        state = 'normal';
        index += dollarTag.length;
      } else index += 1;
      continue;
    }

    if (current === '-' && next === '-') {
      state = 'line-comment';
      index += 2;
    } else if (current === '/' && next === '*') {
      state = 'block-comment';
      index += 2;
    } else if (current === "'") {
      state = 'single-quote';
      index += 1;
    } else if (current === '"') {
      state = 'double-quote';
      index += 1;
    } else if (current === '$') {
      const match = sql.slice(index).match(/^\$[A-Za-z_][A-Za-z0-9_]*\$|^\$\$/);
      if (match) {
        dollarTag = match[0];
        state = 'dollar-quote';
        index += dollarTag.length;
      } else index += 1;
    } else if (current === ';') {
      const statement = sql.slice(start, index + 1).trim();
      if (statement) statements.push(statement);
      start = index + 1;
      index += 1;
    } else index += 1;
  }

  const tail = sql.slice(start).trim();
  if (tail) statements.push(tail);
  if (state !== 'normal' && state !== 'line-comment') {
    throw new Error(`unterminated SQL ${state}`);
  }
  return statements;
}

export function stripSqlComments(sql) {
  let result = '';
  let index = 0;
  let state = 'normal';
  let dollarTag = '';
  while (index < sql.length) {
    const current = sql[index];
    const next = sql[index + 1];
    if (state === 'line-comment') {
      if (current === '\n') {
        result += '\n';
        state = 'normal';
      }
      index += 1;
    } else if (state === 'block-comment') {
      if (current === '*' && next === '/') {
        state = 'normal';
        index += 2;
      } else index += 1;
    } else if (state === 'single-quote') {
      result += current;
      if (current === "'" && next === "'") {
        result += next;
        index += 2;
      } else if (current === "'") {
        state = 'normal';
        index += 1;
      } else index += 1;
    } else if (state === 'double-quote') {
      result += current;
      if (current === '"' && next === '"') {
        result += next;
        index += 2;
      } else if (current === '"') {
        state = 'normal';
        index += 1;
      } else index += 1;
    } else if (state === 'dollar-quote') {
      if (sql.startsWith(dollarTag, index)) {
        result += dollarTag;
        index += dollarTag.length;
        state = 'normal';
      } else {
        result += current;
        index += 1;
      }
    } else if (current === '-' && next === '-') {
      state = 'line-comment';
      index += 2;
    } else if (current === '/' && next === '*') {
      state = 'block-comment';
      index += 2;
    } else if (current === "'") {
      state = 'single-quote';
      result += current;
      index += 1;
    } else if (current === '"') {
      state = 'double-quote';
      result += current;
      index += 1;
    } else if (current === '$') {
      const match = sql.slice(index).match(/^\$[A-Za-z_][A-Za-z0-9_]*\$|^\$\$/);
      if (match) {
        dollarTag = match[0];
        state = 'dollar-quote';
        result += dollarTag;
        index += dollarTag.length;
      } else {
        result += current;
        index += 1;
      }
    } else {
      result += current;
      index += 1;
    }
  }
  return result;
}

function tableOwner(identifier) {
  if (
    identifier.startsWith('se_')
    || BPM_TABLES.has(identifier)
    || /(?:^|_)bpm(?:_|$)/.test(identifier)
    || identifier.includes('smartengine')
    || identifier.includes('smart_engine')
  ) return 'bpm';
  if (CRM_TABLES.has(identifier) || /(?:^|_)crm(?:_|$)/.test(identifier)) return 'crm';
  return null;
}

function literalOwners(sql) {
  const owners = new Set();
  if (/(?:^|[^a-z0-9])bpm(?:[^a-z0-9]|$)|smartengine|smart_engine/i.test(sql)) owners.add('bpm');
  if (/(?:^|[^a-z0-9])crm(?:[^a-z0-9]|$)/i.test(sql)) owners.add('crm');
  return owners;
}

export function classifyMigrationStatement(statement) {
  const code = stripSqlComments(statement);
  const normalized = code.toLowerCase();
  const identifierCode = normalized.replace(/'(?:''|[^'])*'/gs, "''");
  const owners = new Set();
  const identifiers = identifierCode.match(/[a-z_][a-z0-9_$]*/g) ?? [];
  for (const identifier of identifiers) {
    const owner = tableOwner(identifier);
    if (owner) owners.add(owner);
  }

  const executable = normalized.trimStart();
  const dataStatement = /^(insert|update|delete|merge)\b/.test(executable);
  if (dataStatement) {
    for (const owner of literalOwners(code)) owners.add(owner);
  }

  if (owners.size === 0) return { owner: 'core', reasons: [] };
  if (owners.size === 1) {
    const [owner] = owners;
    return { owner, reasons: [...owners] };
  }
  return { owner: 'mixed', reasons: [...owners].sort() };
}

function outputMigration(statements, title, sources) {
  const header = [
    `-- ${title}`,
    '-- Generated by scripts/application/migration-ownership.mjs.',
    '-- Do not edit this staged artifact; edit the owning source migration/config instead.',
    ...sources.map((source) => `-- Source: ${source}`),
    '',
  ].join('\n');
  return `${header}${statements.join('\n\n')}\n`;
}

export function splitMigrationDirectory(sourceRoot) {
  const files = readdirSync(sourceRoot)
    .filter((file) => /^V[^/]+\.sql$/.test(file))
    .sort((left, right) => left.localeCompare(right));
  const coreFiles = [];
  const productStatements = { bpm: [], crm: [] };
  const manifestFiles = [];
  const mixed = [];

  for (const file of files) {
    const source = readFileSync(resolve(sourceRoot, file), 'utf8');
    const statements = splitSqlStatements(source);
    const executableStatements = statements.filter((statement) => stripSqlComments(statement).trim().length > 0);
    const core = [];
    const counts = { core: 0, bpm: 0, crm: 0, mixed: 0 };
    executableStatements.forEach((statement, statementIndex) => {
      const classification = classifyMigrationStatement(statement);
      counts[classification.owner] += 1;
      if (classification.owner === 'core') core.push(statement);
      else if (classification.owner === 'mixed') {
        mixed.push({ file, statement: statementIndex + 1, reasons: classification.reasons });
      } else {
        productStatements[classification.owner].push({ file, statement: statementIndex + 1, sql: statement });
      }
    });
    if (core.length > 0) {
      coreFiles.push({ file, content: outputMigration(core, 'AuraBoot core-only migration', [file]) });
    }
    manifestFiles.push({
      file,
      sourceDigest: sha256(source),
      statementCount: executableStatements.length,
      owners: counts,
    });
  }

  return { coreFiles, productStatements, mixed, sourceFiles: manifestFiles };
}

export function writeMigrationSplit(sourceRoot, outputRoot) {
  const result = splitMigrationDirectory(sourceRoot);
  if (result.mixed.length > 0) {
    throw new Error(`migration statements mix CRM and BPM ownership: ${JSON.stringify(result.mixed)}`);
  }
  const coreRoot = resolve(outputRoot, 'core');
  mkdirSync(coreRoot, { recursive: true });
  for (const file of result.coreFiles) writeFileSync(resolve(coreRoot, file.file), file.content);

  const productVersions = { crm: '20260912020000', bpm: '20260912030000' };
  for (const owner of ['crm', 'bpm']) {
    const entries = result.productStatements[owner];
    if (entries.length === 0) continue;
    const productRoot = resolve(outputRoot, 'product', owner);
    mkdirSync(productRoot, { recursive: true });
    const sources = [...new Set(entries.map((entry) => entry.file))];
    const filename = `V${productVersions[owner]}__${owner}_extracted_from_core.sql`;
    writeFileSync(
      resolve(productRoot, filename),
      outputMigration(entries.map((entry) => entry.sql), `Aura ${owner.toUpperCase()} owned migration`, sources),
    );
  }

  const manifest = {
    schemaVersion: 1,
    policy: 'core-product-migration-ownership-v1',
    sourceRoot,
    sourceFiles: result.sourceFiles,
    outputs: {
      coreStatementCount: result.sourceFiles.reduce((sum, file) => sum + file.owners.core, 0),
      crmStatementCount: result.productStatements.crm.length,
      bpmStatementCount: result.productStatements.bpm.length,
      mixedStatementCount: result.mixed.length,
    },
  };
  manifest.identity = sha256(JSON.stringify(manifest));
  writeFileSync(resolve(outputRoot, 'ownership-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  return manifest;
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const manifest = writeMigrationSplit(options.source, options.output);
  process.stdout.write(`${JSON.stringify({ status: 'PASS', output: options.output, ...manifest.outputs, identity: manifest.identity }, null, 2)}\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`migration ownership split failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
