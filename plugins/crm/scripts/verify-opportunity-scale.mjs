#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  printHelp();
  process.exit(0);
}

const datasetSize = positiveInteger(args.rows ?? '10000', '--rows');
const samples = positiveInteger(args.samples ?? '20', '--samples');
const tenantId = requiredInteger(args.tenantId ?? process.env.CRM_SCALE_TENANT_ID, 'tenant id');
const userId = requiredInteger(args.userId ?? process.env.CRM_SCALE_USER_ID, 'user id');
const database = required(process.env.PGDATABASE ?? process.env.PG_DB, 'PGDATABASE');
const confirmedDatabase = required(args.confirmDedicatedDatabase, '--confirm-dedicated-database');
const backend = String(args.backend ?? process.env.BACKEND_URL ?? 'http://127.0.0.1:6455').replace(/\/$/, '');
const email = args.email ?? process.env.CRM_ADMIN_EMAIL ?? 'admin@auraboot.com';
const password = required(process.env.CRM_ADMIN_PASSWORD, 'CRM_ADMIN_PASSWORD');
const runId = safeToken(args.runId ?? `crm-opportunity-scale-${datasetSize}`);
const output = resolve(args.out ?? `crm-opportunity-scale-${datasetSize}.json`);
const marker = `CRM-OPP-SCALE-${runId}`;
const needle = `${marker}-NEEDLE`;
const budgets = {
  listP95Ms: positiveNumber(args.listP95Ms ?? '500', '--list-p95-ms'),
  searchP95Ms: positiveNumber(args.searchP95Ms ?? '500', '--search-p95-ms'),
  p99Ms: positiveNumber(args.p99Ms ?? '800', '--p99-ms'),
};

if (database !== confirmedDatabase || !/^auraboot_[1-9][0-9]*$/.test(database)) {
  fail(`refusing scale seed: PGDATABASE=${database} confirmation=${confirmedDatabase}`);
}

prepareDataset();
const jwt = await login();
const measurements = {
  list: await measureApi('/api/dynamic/crm_opportunity_common/list?pageNum=1&pageSize=50', jwt),
  search: await measureApi(
    `/api/dynamic/crm_opportunity_common/list?pageNum=1&pageSize=50&keyword=${encodeURIComponent(needle)}`,
    jwt,
    needle,
  ),
};
const indexes = [
  'idx_mt_crm_opportunity_common_tenant_created',
  'idx_mt_crm_opportunity_common_crm_opp_name_trgm',
].map((indexName) => ({
  indexName,
  present: psqlScalar(
    `SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname='${sqlLit(indexName)}')`,
  ) === 't',
}));
const plans = {
  list: explain(`SELECT pid FROM mt_crm_opportunity_common WHERE tenant_id=${tenantId} ORDER BY created_at DESC LIMIT 50`),
  search: explain(
    `SELECT pid FROM mt_crm_opportunity_common WHERE tenant_id=${tenantId} AND crm_opp_name ILIKE '%${sqlLit(needle)}%' ORDER BY created_at DESC LIMIT 50`,
  ),
};

const failures = [];
for (const index of indexes) if (!index.present) failures.push(`missing index ${index.indexName}`);
for (const [name, result] of Object.entries(measurements)) {
  if (result.p95Ms > budgets[`${name}P95Ms`]) {
    failures.push(`${name} p95 ${result.p95Ms}ms exceeds ${budgets[`${name}P95Ms`]}ms`);
  }
  if (result.p99Ms > budgets.p99Ms) {
    failures.push(`${name} p99 ${result.p99Ms}ms exceeds ${budgets.p99Ms}ms`);
  }
}
if (!plans.list.indexNames.includes('idx_mt_crm_opportunity_common_tenant_created')) {
  failures.push('list plan did not use tenant-created index');
}

const report = {
  schemaVersion: 1,
  verdict: failures.length === 0 ? 'pass' : 'fail',
  claim: '10k fixed-dataset PostgreSQL plus authenticated backend-list evidence; browser rendering is a separate gate',
  database,
  backend,
  tenantId,
  userId,
  runId,
  datasetSize,
  samples,
  marker,
  budgets,
  indexes,
  measurements,
  plans,
  failures,
  dataMigration: 'not required; development stage',
};
mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 });
console.log(JSON.stringify({ output, verdict: report.verdict, measurements, failures }, null, 2));
if (failures.length > 0) process.exit(1);

function prepareDataset() {
  const conflicting = Number(psqlScalar(
    `SELECT count(*) FROM mt_crm_opportunity_common WHERE crm_opp_code LIKE 'CRM-OPP-SCALE-%' AND crm_opp_code NOT LIKE '${sqlLit(marker)}-%'`,
  ));
  if (conflicting > 0) fail(`database ${database} contains a different opportunity scale fixture`);
  const existing = Number(psqlScalar(
    `SELECT count(*) FROM mt_crm_opportunity_common WHERE crm_opp_code LIKE '${sqlLit(marker)}-%'`,
  ));
  if (existing === datasetSize) {
    psql('ANALYZE mt_crm_opportunity_common');
    return;
  }
  if (existing !== 0) fail(`fixture ${runId} has ${existing} rows, expected 0 or ${datasetSize}`);
  const idBase = 889_000_000_000_000_000n;
  psql(`
    INSERT INTO mt_crm_opportunity_common (
      id, pid, created_by, updated_by, tenant_id, created_at, updated_at,
      crm_opp_code, crm_opp_name, crm_opp_stage, crm_opp_currency_code,
      crm_opp_expected_amount, crm_opp_expected_amount_base, crm_opp_base_currency_code,
      crm_opp_expected_close_date, crm_opp_probability, crm_opp_forecast_category, row_version
    )
    SELECT
      ${idBase} + gs,
      '${sqlLit(runId)}-' || lpad(gs::text, 12, '0'),
      ${userId}, ${userId}, ${tenantId},
      now() - (gs % 365) * interval '1 day', now() - (gs % 60) * interval '1 minute',
      '${sqlLit(marker)}-' || lpad(gs::text, 12, '0'),
      CASE WHEN gs = ${datasetSize} THEN '${sqlLit(needle)}' ELSE '${sqlLit(marker)}-' || lpad(gs::text, 12, '0') END,
      CASE WHEN gs % 5 = 0 THEN 'negotiation' WHEN gs % 3 = 0 THEN 'proposal' ELSE 'discovery' END,
      'CNY', 10000 + gs, 10000 + gs, 'CNY',
      now() + (gs % 180) * interval '1 day', 20 + (gs % 70),
      CASE WHEN gs % 5 = 0 THEN 'commit' WHEN gs % 3 = 0 THEN 'best_case' ELSE 'pipeline' END,
      1
    FROM generate_series(1, ${datasetSize}) AS gs;
    ANALYZE mt_crm_opportunity_common;
  `);
  const inserted = Number(psqlScalar(
    `SELECT count(*) FROM mt_crm_opportunity_common WHERE crm_opp_code LIKE '${sqlLit(marker)}-%'`,
  ));
  if (inserted !== datasetSize) fail(`inserted ${inserted}, expected ${datasetSize}`);
}

async function login() {
  const response = await fetch(`${backend}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const body = await response.json().catch(() => ({}));
  const jwt = body?.data?.jwt;
  if (!response.ok || String(body?.code) !== '0' || !jwt) fail(`login failed HTTP ${response.status}`);
  return String(jwt);
}

async function measureApi(path, jwt, expectedNeedle = null) {
  for (let index = 0; index < 3; index += 1) await request(path, jwt, expectedNeedle);
  const executions = [];
  for (let index = 0; index < samples; index += 1) {
    executions.push(await request(path, jwt, expectedNeedle));
  }
  const times = executions.map((execution) => execution.elapsedMs);
  return {
    minMs: round(Math.min(...times)),
    p50Ms: percentile(times, 0.5),
    p95Ms: percentile(times, 0.95),
    p99Ms: percentile(times, 0.99),
    maxMs: round(Math.max(...times)),
    meanMs: round(times.reduce((sum, value) => sum + value, 0) / times.length),
    finalCount: executions.at(-1).count,
  };
}

async function request(path, jwt, expectedNeedle) {
  const startedAt = performance.now();
  const response = await fetch(`${backend}${path}`, {
    headers: { Authorization: `Bearer ${jwt}` },
  });
  const body = await response.json().catch(() => ({}));
  const elapsedMs = performance.now() - startedAt;
  if (!response.ok || String(body?.code) !== '0') fail(`request ${path} failed HTTP ${response.status}`);
  const records = body?.data?.records ?? body?.data?.list ?? [];
  if (expectedNeedle && !records.some((record) => record.crm_opp_name === expectedNeedle)) {
    fail(`search response omitted exact needle ${expectedNeedle}`);
  }
  return { elapsedMs, count: records.length };
}

function explain(sql) {
  const result = JSON.parse(psqlScalar(`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ${sql}`))[0];
  const nodes = flattenPlan(result.Plan);
  return {
    executionMs: Number(result['Execution Time']),
    nodeTypes: [...new Set(nodes.map((node) => node['Node Type']))],
    indexNames: [...new Set(nodes.map((node) => node['Index Name']).filter(Boolean))],
    actualRows: Number(result.Plan['Actual Rows']),
  };
}

function flattenPlan(plan) { return [plan, ...(plan.Plans ?? []).flatMap(flattenPlan)]; }
function percentile(values, quantile) {
  const sorted = [...values].sort((left, right) => left - right);
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
    '-U', process.env.PGUSER ?? 'auraboot', '-d', database];
}
function psqlEnv() { return { ...process.env, PGPASSWORD: process.env.PGPASSWORD ?? 'auraboot' }; }
function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--help' || arg === '-h') return { help: true };
    if (!arg.startsWith('--') || !argv[index + 1] || argv[index + 1].startsWith('--')) fail(`invalid argument ${arg}`);
    const key = arg.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
    result[key] = argv[index += 1];
  }
  return result;
}
function required(value, label) {
  if (value == null || String(value).trim() === '') fail(`${label} is required`);
  return String(value).trim();
}
function requiredInteger(value, label) {
  const valueText = required(value, label);
  if (!/^\d+$/.test(valueText)) fail(`${label} must be an integer`);
  return valueText;
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
  const token = String(value).replace(/[^0-9A-Za-z_-]/g, '-').slice(0, 40);
  if (!token) fail('run id must contain a letter or number');
  return token;
}
function sqlLit(value) { return String(value).replace(/'/g, "''"); }
function fail(message) { throw new Error(message); }
function printHelp() {
  console.log(`Usage:
  PGDATABASE=auraboot_55 CRM_ADMIN_PASSWORD=... node plugins/crm/scripts/verify-opportunity-scale.mjs \\
    --confirm-dedicated-database auraboot_55 --tenant-id <id> --user-id <id> \\
    --rows 10000 --samples 20 --out <receipt.json>

Seeds one fixed opportunity dataset without deleting rows, then measures authenticated backend list/search
and PostgreSQL index plans. Browser rendering remains a separate acceptance gate.`);
}
