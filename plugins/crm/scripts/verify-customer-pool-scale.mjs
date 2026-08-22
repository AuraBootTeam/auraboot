#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildCustomerPoolScaleConsoleReport } from './lib/customer-pool-scale-report.mjs';

const crmRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = parseArgs(process.argv.slice(2));
if (args.help) {
  printHelp();
  process.exit(0);
}

const datasetSize = positiveInteger(args.rows ?? '100000', '--rows');
const samples = positiveInteger(args.samples ?? '30', '--samples');
const tenantId = requiredInteger(args.tenantId ?? process.env.CRM_SCALE_TENANT_ID, 'tenant id');
const userId = requiredInteger(args.userId ?? process.env.CRM_SCALE_USER_ID, 'user id');
const userPid = required(args.userPid ?? process.env.CRM_SCALE_USER_PID, 'user pid');
const runId = safeToken(args.runId ?? `crm-customer-scale-${datasetSize}`);
const output = resolve(args.out ?? `customer-pool-scale-${datasetSize}.json`);
const requireIndexes = String(args.requireIndexes ?? 'true') !== 'false';
const budgets = {
  statsP95Ms: positiveNumber(args.statsP95Ms ?? '150', '--stats-p95-ms'),
  queueP95Ms: positiveNumber(args.queueP95Ms ?? '300', '--queue-p95-ms'),
  searchP95Ms: positiveNumber(args.searchP95Ms ?? '150', '--search-p95-ms'),
  p99Ms: positiveNumber(args.p99Ms ?? '350', '--p99-ms'),
};

const queries = JSON.parse(readFileSync(resolve(crmRoot, 'config/named-queries.json'), 'utf8'));
const statsDefinition = namedQuery('crm_customer_pool_ops_stats');
const queueDefinition = namedQuery('crm_customer_pool_ops_queue');
const fixtureKey = createHash('sha256').update(runId).digest('hex').slice(0, 12);
const marker = `CPS-${datasetSize}-${fixtureKey}`;
const poolPid = `cp-scale-${datasetSize}-${fixtureKey}`;
const needle = `${marker}-NEEDLE`;

assertDedicatedDatabase();
prepareDataset();

const indexes = expectedIndexes().map((indexName) => ({
  indexName,
  present: psqlScalar(`SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname='${sqlLit(indexName)}')`) === 't',
}));
const statsSql = materialize(statsDefinition.fromSql, { customerKeyword: '', viewFilter: '' });
const queueSql = `${materialize(queueDefinition.fromSql, { customerKeyword: '', viewFilter: '' })} LIMIT 50`;
const searchSql = `${materialize(queueDefinition.fromSql, { customerKeyword: needle, viewFilter: '' })} LIMIT 50`;
const measurements = {
  stats: measure(statsSql, samples),
  queue: measure(queueSql, samples),
  search: measure(searchSql, samples),
};

const failures = [];
if (requireIndexes) {
  for (const index of indexes) {
    if (!index.present) failures.push(`missing index ${index.indexName}`);
  }
}
for (const [name, result] of Object.entries(measurements)) {
  const p95Budget = budgets[`${name}P95Ms`];
  if (result.p95Ms > p95Budget) failures.push(`${name} p95 ${result.p95Ms}ms exceeds ${p95Budget}ms`);
  if (result.p99Ms > budgets.p99Ms) failures.push(`${name} p99 ${result.p99Ms}ms exceeds ${budgets.p99Ms}ms`);
}
if (requireIndexes) {
  const usedSearchIndexes = new Set(measurements.search.indexNames);
  for (const index of indexes) {
    if (index.present && !usedSearchIndexes.has(index.indexName)) {
      failures.push(`search plan did not use index ${index.indexName}`);
    }
  }
}

const report = {
  verdict: failures.length === 0 ? 'pass' : 'fail',
  claim: 'database-layer fixed-dataset evidence only; not full API or browser parity',
  database: pgDatabase(),
  tenantId,
  userId,
  userPid,
  runId,
  datasetSize,
  samples,
  marker,
  indexes,
  budgets,
  measurements,
  failures,
};
writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(buildCustomerPoolScaleConsoleReport(report), null, 2));
if (failures.length > 0) process.exit(1);

function namedQuery(code) {
  const query = queries.find((candidate) => candidate.code === code);
  if (!query?.fromSql) fail(`named query ${code} not found`);
  return query;
}

function assertDedicatedDatabase() {
  const database = pgDatabase();
  if (!/^auraboot_(15[5-9]|1[6-9][0-9]|[2-9][0-9]{2,})$/.test(database)) {
    fail(`refusing scale seed into non-dedicated database ${database}`);
  }
  const conflicting = Number(psqlScalar(
    `SELECT count(*) FROM mt_crm_customer_pool_common WHERE crm_cp_code LIKE 'CPS-%' AND crm_cp_code <> '${sqlLit(marker)}'`,
  ));
  if (conflicting > 0) fail(`database ${database} contains a different CRM customer scale fixture`);
}

function prepareDataset() {
  const existing = Number(psqlScalar(
    `SELECT count(*) FROM mt_crm_customer_pool_item_common WHERE crm_cpi_pool_id='${sqlLit(poolPid)}'`,
  ));
  if (existing === datasetSize) {
    psql('ANALYZE mt_crm_customer_pool_item_common; ANALYZE mt_crm_customer_pool_common');
    return;
  }
  if (existing !== 0) fail(`fixture ${runId} has ${existing} rows, expected 0 or ${datasetSize}`);

  const poolId = 889_000_000_000_000_000n + BigInt(datasetSize);
  const itemBase = 890_000_000_000_000_000n;
  psql(`
    INSERT INTO mt_crm_customer_pool_common (
      id, pid, tenant_id, created_at, updated_at, crm_cp_code, crm_cp_name,
      crm_cp_status, crm_cp_member_user_ids, crm_cp_admin_user_ids,
      crm_cp_daily_pick_limit, crm_cp_new_cooldown_days,
      crm_cp_previous_owner_cooldown_days, crm_cp_auto_recycle,
      crm_cp_recycle_match_mode, crm_cp_recycle_after_days,
      crm_cp_recycle_basis, crm_cp_description, row_version
    ) VALUES (
      ${poolId}, '${sqlLit(poolPid)}', ${tenantId}, now(), now(), '${sqlLit(marker)}',
      '${sqlLit(`Cordys customer parity ${datasetSize}`)}', 'enabled',
      '["${sqlLit(userPid)}"]', '["${sqlLit(userPid)}"]',
      1000, 0, 0, false, 'ALL', 30, 'claimed_at', 'fixed scale fixture', 1
    ) ON CONFLICT (crm_cp_code) DO NOTHING;

    INSERT INTO mt_crm_customer_pool_item_common (
      id, pid, tenant_id, created_at, updated_at, crm_cpi_account_key,
      crm_cpi_account_id, crm_cpi_pool_id, crm_cpi_status, crm_cpi_account_code,
      crm_cpi_account_name, crm_cpi_rating, crm_cpi_phone, crm_cpi_industry,
      crm_cpi_health_score, crm_cpi_previous_owner, crm_cpi_entered_at,
      crm_cpi_entered_by, crm_cpi_reason, crm_cpi_claim_release_at,
      crm_cpi_claimed_at, crm_cpi_claimed_by, row_version
    )
    SELECT
      ${itemBase} + gs, 'cs-${sqlLit(fixtureKey)}-' || lpad(gs::text, 12, '0'),
      ${tenantId}, now() - (gs % 365) * interval '1 day', now(),
      'csa-${sqlLit(fixtureKey)}-' || lpad(gs::text, 12, '0'),
      'csa-${sqlLit(fixtureKey)}-' || lpad(gs::text, 12, '0'), '${sqlLit(poolPid)}',
      CASE WHEN gs % 20 < 13 THEN 'available' WHEN gs % 20 < 16 THEN 'assigned'
           WHEN gs % 20 < 18 THEN 'claimed' WHEN gs % 20 = 18 THEN 'recycling'
           ELSE 'recycling_retry' END,
      '${sqlLit(marker)}-' || lpad(gs::text, 12, '0'),
      CASE WHEN gs = ${datasetSize} THEN '${sqlLit(needle)}' ELSE 'Scale Customer ' || (gs % 10000) END,
      CASE WHEN gs % 3 = 0 THEN 'A' WHEN gs % 3 = 1 THEN 'B' ELSE 'C' END,
      '139' || lpad((gs % 100000000)::text, 8, '0'),
      CASE WHEN gs % 3 = 0 THEN 'manufacturing' WHEN gs % 3 = 1 THEN 'technology' ELSE 'retail' END,
      gs % 101, CASE WHEN gs % 5 = 0 THEN '${sqlLit(userPid)}' ELSE NULL END,
      now() - (gs % 365) * interval '1 day', '${sqlLit(userPid)}', 'fixed scale fixture',
      CASE WHEN gs % 4 = 0 THEN now() + interval '1 day' ELSE now() - interval '1 day' END,
      CASE WHEN gs % 20 >= 13 THEN now() - interval '2 days' ELSE NULL END,
      CASE WHEN gs % 20 BETWEEN 13 AND 17 THEN '${sqlLit(userPid)}' ELSE NULL END, 1
    FROM generate_series(1, ${datasetSize}) AS gs;
    ANALYZE mt_crm_customer_pool_item_common;
    ANALYZE mt_crm_customer_pool_common;
  `);
  const inserted = Number(psqlScalar(
    `SELECT count(*) FROM mt_crm_customer_pool_item_common WHERE crm_cpi_pool_id='${sqlLit(poolPid)}'`,
  ));
  if (inserted !== datasetSize) fail(`inserted ${inserted}, expected ${datasetSize}`);
}

function materialize(sql, params) {
  const values = {
    tenantId: String(tenantId),
    currentUserId: String(userId),
    customerKeyword: `'${sqlLit(params.customerKeyword)}'`,
    viewFilter: `'${sqlLit(params.viewFilter)}'`,
  };
  return sql.replace(/#\{params\.([A-Za-z0-9_]+)}/g, (token, key) => {
    if (!(key in values)) fail(`unsupported named-query parameter ${token}`);
    return values[key];
  });
}

function measure(sql, count) {
  for (let i = 0; i < 3; i++) explain(sql);
  const executions = Array.from({ length: count }, () => explain(sql));
  const times = executions.map((entry) => entry.executionMs);
  const finalExecution = executions.at(-1);
  return {
    minMs: round(Math.min(...times)),
    p50Ms: percentile(times, 0.5),
    p95Ms: percentile(times, 0.95),
    p99Ms: percentile(times, 0.99),
    maxMs: round(Math.max(...times)),
    meanMs: round(times.reduce((sum, value) => sum + value, 0) / times.length),
    nodeTypes: finalExecution.nodeTypes,
    indexNames: finalExecution.indexNames,
    finalPlan: finalExecution.plan,
  };
}

function explain(sql) {
  const result = JSON.parse(psqlScalar(`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ${sql}`))[0];
  const nodes = flattenPlan(result.Plan);
  return {
    executionMs: Number(result['Execution Time']),
    nodeTypes: [...new Set(nodes.map((node) => node['Node Type']))],
    indexNames: [...new Set(nodes.map((node) => node['Index Name']).filter(Boolean))],
    plan: {
      nodeType: result.Plan['Node Type'],
      actualRows: result.Plan['Actual Rows'],
      planningMs: result['Planning Time'],
      executionMs: result['Execution Time'],
      sharedHitBlocks: result.Plan['Shared Hit Blocks'],
      sharedReadBlocks: result.Plan['Shared Read Blocks'],
    },
  };
}

function flattenPlan(plan) {
  return [plan, ...(plan.Plans ?? []).flatMap(flattenPlan)];
}

function expectedIndexes() {
  return ['crm_cpi_account_code', 'crm_cpi_account_name', 'crm_cpi_phone']
    .map((field) => `idx_mt_crm_customer_pool_item_common_${field}_trgm`);
}

function percentile(values, quantile) {
  const sorted = [...values].sort((a, b) => a - b);
  return round(sorted[Math.max(0, Math.ceil(sorted.length * quantile) - 1)]);
}
function round(value) { return Math.round(value * 1000) / 1000; }

function psql(sql) {
  return execFileSync('psql', [...psqlArgs(), '-q', '-v', 'ON_ERROR_STOP=1', '-c', sql], {
    env: psqlEnv(), encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}
function psqlScalar(sql) {
  return execFileSync('psql', [...psqlArgs(), '-t', '-A', '-P', 'pager=off', '-v', 'ON_ERROR_STOP=1', '-c', sql], {
    env: psqlEnv(), encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}
function psqlArgs() {
  return ['-h', process.env.PGHOST ?? '127.0.0.1', '-p', process.env.PGPORT ?? '5432',
    '-U', process.env.PGUSER ?? 'auraboot', '-d', pgDatabase()];
}
function pgDatabase() { return process.env.PGDATABASE ?? process.env.PG_DB ?? ''; }
function psqlEnv() { return { ...process.env, PGPASSWORD: process.env.PGPASSWORD ?? 'auraboot' }; }

function parseArgs(argv) {
  const result = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') return { help: true };
    if (!arg.startsWith('--') || !argv[i + 1] || argv[i + 1].startsWith('--')) fail(`invalid argument ${arg}`);
    const key = arg.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
    result[key] = argv[++i];
  }
  return result;
}
function required(value, label) {
  if (value == null || String(value).trim() === '') fail(`${label} is required`);
  return String(value).trim();
}
function requiredInteger(value, label) {
  const text = required(value, label);
  if (!/^\d+$/.test(text)) fail(`${label} must be an integer`);
  return text;
}
function positiveInteger(value, label) {
  const number = Number(value);
  if (!Number.isInteger(number) || number <= 0) fail(`${label} must be a positive integer`);
  return number;
}
function positiveNumber(value, label) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) fail(`${label} must be positive`);
  return number;
}
function safeToken(value) {
  const token = String(value).replace(/[^0-9A-Za-z_-]/g, '-').slice(0, 48);
  if (!token) fail('run id must contain a letter or number');
  return token;
}
function sqlLit(value) { return String(value).replace(/'/g, "''"); }
function printHelp() {
  console.log(`Usage:
  PGDATABASE=auraboot_192 node plugins/crm/scripts/verify-customer-pool-scale.mjs \\
    --rows 10000 --tenant-id <id> --user-id <id> --user-pid <pid> --out <report.json>

The command refuses non-dedicated databases, prepares one fixed data set without deleting rows,
executes the exact CRM customer-pool named-query SQL, and fails on missing GIN indexes or p95/p99 budgets.
Use --require-indexes false only for an explicit reverse-control report.`);
}
function fail(message) {
  console.error(`[verify-customer-pool-scale] ${message}`);
  process.exit(1);
}
