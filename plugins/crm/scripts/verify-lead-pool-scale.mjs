#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildLeadPoolScaleConsoleReport } from './lib/lead-pool-scale-report.mjs';

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
const runId = safeToken(args.runId ?? `crm-scale-${datasetSize}`);
const output = resolve(args.out ?? `lead-pool-scale-${datasetSize}.json`);
const requireIndexes = String(args.requireIndexes ?? 'true') !== 'false';
const budgets = {
  statsP95Ms: positiveNumber(args.statsP95Ms ?? '120', '--stats-p95-ms'),
  queueP95Ms: positiveNumber(args.queueP95Ms ?? '250', '--queue-p95-ms'),
  searchP95Ms: positiveNumber(args.searchP95Ms ?? '120', '--search-p95-ms'),
  p99Ms: positiveNumber(args.p99Ms ?? '300', '--p99-ms'),
};

const queries = JSON.parse(readFileSync(resolve(crmRoot, 'config/named-queries.json'), 'utf8'));
const statsDefinition = namedQuery('crm_lead_pool_ops_stats');
const queueDefinition = namedQuery('crm_lead_pool_ops_queue');
const marker = `CRM-SCALE-${runId}`;
const poolPid = `crm-scale-pool-${runId}`;
const needle = `${marker}-NEEDLE`;

assertDedicatedDatabase();
prepareDataset();

const indexes = expectedIndexes().map((indexName) => ({
  indexName,
  present: psqlScalar(`SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname='${sqlLit(indexName)}')`) === 't',
}));

const statsSql = materialize(statsDefinition.fromSql, { leadKeyword: '', viewFilter: '' });
const queueSql = `${materialize(queueDefinition.fromSql, { leadKeyword: '', viewFilter: '' })} LIMIT 50`;
const searchSql = `${materialize(queueDefinition.fromSql, { leadKeyword: needle, viewFilter: '' })} LIMIT 50`;
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
console.log(JSON.stringify(buildLeadPoolScaleConsoleReport(report), null, 2));
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
    `SELECT count(*) FROM mt_crm_lead_pool WHERE crm_lp_code LIKE 'CRM-SCALE-%' AND crm_lp_code <> '${sqlLit(marker)}'`,
  ));
  if (conflicting > 0) fail(`database ${database} contains a different CRM scale fixture`);
}

function prepareDataset() {
  const existing = Number(psqlScalar(
    `SELECT count(*) FROM mt_crm_lead_pool_item WHERE crm_lpi_pool_id='${sqlLit(poolPid)}'`,
  ));
  if (existing === datasetSize) {
    psql('ANALYZE mt_crm_lead_pool_item; ANALYZE mt_crm_lead_pool');
    return;
  }
  if (existing !== 0) fail(`fixture ${runId} has ${existing} rows, expected 0 or ${datasetSize}`);

  const poolId = 879_000_000_000_000_000n + BigInt(datasetSize);
  const itemBase = 880_000_000_000_000_000n;
  psql(`
    INSERT INTO mt_crm_lead_pool (
      id, pid, tenant_id, created_at, updated_at, crm_lp_code, crm_lp_name,
      crm_lp_status, crm_lp_member_user_ids, crm_lp_admin_user_ids,
      crm_lp_daily_pick_limit, crm_lp_new_cooldown_days,
      crm_lp_previous_owner_cooldown_days, crm_lp_auto_recycle,
      crm_lp_recycle_after_days, crm_lp_recycle_basis, row_version
    ) VALUES (
      ${poolId}, '${sqlLit(poolPid)}', ${tenantId}, now(), now(), '${sqlLit(marker)}',
      '${sqlLit(`Cordys parity ${datasetSize}`)}', 'enabled',
      '["${sqlLit(userPid)}"]', '["${sqlLit(userPid)}"]',
      1000, 0, 0, false, 30, 'claimed_at', 1
    ) ON CONFLICT (crm_lp_code) DO NOTHING;

    INSERT INTO mt_crm_lead_pool_item (
      id, pid, tenant_id, created_at, updated_at, crm_lpi_lead_key,
      crm_lpi_lead_id, crm_lpi_pool_id, crm_lpi_status, crm_lpi_lead_code,
      crm_lpi_company, crm_lpi_contact_name, crm_lpi_contact_phone,
      crm_lpi_source, crm_lpi_score, crm_lpi_previous_owner,
      crm_lpi_entered_at, crm_lpi_entered_by, crm_lpi_reason,
      crm_lpi_claim_release_at, crm_lpi_claimed_at, crm_lpi_claimed_by, row_version
    )
    SELECT
      ${itemBase} + gs, '${sqlLit(runId)}-item-' || lpad(gs::text, 12, '0'),
      ${tenantId}, now() - (gs % 365) * interval '1 day', now(),
      '${sqlLit(runId)}-lead-' || lpad(gs::text, 12, '0'),
      '${sqlLit(runId)}-lead-' || lpad(gs::text, 12, '0'), '${sqlLit(poolPid)}',
      CASE WHEN gs % 10 < 7 THEN 'available' WHEN gs % 10 < 9 THEN 'assigned' ELSE 'claimed' END,
      '${sqlLit(marker)}-' || lpad(gs::text, 12, '0'),
      CASE WHEN gs = ${datasetSize} THEN '${sqlLit(needle)}' ELSE 'Scale Company ' || (gs % 10000) END,
      'Scale Contact ' || (gs % 5000), '139' || lpad((gs % 100000000)::text, 8, '0'),
      CASE WHEN gs % 3 = 0 THEN 'website' WHEN gs % 3 = 1 THEN 'referral' ELSE 'event' END,
      gs % 101, CASE WHEN gs % 5 = 0 THEN '${sqlLit(userPid)}' ELSE NULL END,
      now() - (gs % 365) * interval '1 day', '${sqlLit(userPid)}', 'fixed scale fixture',
      CASE WHEN gs % 4 = 0 THEN now() + interval '1 day' ELSE now() - interval '1 day' END,
      CASE WHEN gs % 10 >= 7 THEN now() - interval '2 days' ELSE NULL END,
      CASE WHEN gs % 10 >= 7 THEN '${sqlLit(userPid)}' ELSE NULL END, 1
    FROM generate_series(1, ${datasetSize}) AS gs;
    ANALYZE mt_crm_lead_pool_item;
    ANALYZE mt_crm_lead_pool;
  `);
  const inserted = Number(psqlScalar(
    `SELECT count(*) FROM mt_crm_lead_pool_item WHERE crm_lpi_pool_id='${sqlLit(poolPid)}'`,
  ));
  if (inserted !== datasetSize) fail(`inserted ${inserted}, expected ${datasetSize}`);
}

function materialize(sql, params) {
  const values = {
    tenantId: String(tenantId),
    currentUserId: String(userId),
    leadKeyword: `'${sqlLit(params.leadKeyword)}'`,
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
    minMs: round(Math.min(...times)), p50Ms: percentile(times, 0.5),
    p95Ms: percentile(times, 0.95), p99Ms: percentile(times, 0.99),
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
      nodeType: result.Plan['Node Type'], actualRows: result.Plan['Actual Rows'],
      planningMs: result['Planning Time'], executionMs: result['Execution Time'],
      sharedHitBlocks: result.Plan['Shared Hit Blocks'], sharedReadBlocks: result.Plan['Shared Read Blocks'],
    },
  };
}

function flattenPlan(plan) {
  return [plan, ...(plan.Plans ?? []).flatMap(flattenPlan)];
}

function expectedIndexes() {
  return ['crm_lpi_lead_code', 'crm_lpi_company', 'crm_lpi_contact_name']
    .map((field) => `idx_mt_crm_lead_pool_item_${field}_trgm`);
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
  PGDATABASE=auraboot_155 node plugins/crm/scripts/verify-lead-pool-scale.mjs \\
    --rows 10000 --tenant-id <id> --user-id <id> --user-pid <pid> --out <report.json>

The command refuses non-dedicated databases, prepares one fixed data set without deleting rows,
executes the exact CRM lead-pool named-query SQL, and fails on missing GIN indexes or p95/p99 budgets.
Use --require-indexes false only for an explicit reverse-control report.`);
}
function fail(message) {
  console.error(`[verify-lead-pool-scale] ${message}`);
  process.exit(1);
}
