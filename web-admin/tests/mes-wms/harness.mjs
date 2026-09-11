// MES/WMS backend command-pipeline golden harness — real-stack IT.
//
// Talks DIRECTLY to the backend (no frontend/BFF) with a JWT, so it exercises the
// real command pipeline + real DB round-trip. Used by mes-wms-backend-golden.mjs.
//
// Auth flow mirrors scripts/host-oee-dashboard-golden.sh: POST /api/auth/login → JWT →
// tenant-selection/process to bind the business space → all subsequent calls carry the
// tenant-scoped Bearer token.

const BACKEND = process.env.BACKEND_URL || 'http://127.0.0.1:6463';
const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Test2026x';

const NOPROXY = { agent: undefined };

async function req(path, { method = 'GET', token, body, allowError = false } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const resp = await fetch(`${BACKEND}${path}`, {
    method,
    headers,
    body: body != null ? JSON.stringify(body) : undefined,
  });
  const text = await resp.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { raw: text }; }
  if (!resp.ok && !allowError) {
    throw new Error(`${method} ${path} → HTTP ${resp.status}: ${text.slice(0, 400)}`);
  }
  return { status: resp.status, ok: resp.ok, json };
}

export async function login() {
  const r = await req('/api/auth/login', {
    method: 'POST',
    body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
  });
  let token = r.json?.data?.jwt || r.json?.data?.token || r.json?.data?.accessToken || r.json?.token;
  if (!token) throw new Error(`login: no token in ${JSON.stringify(r.json).slice(0, 300)}`);
  // Bind business space (single-mode still needs the tenant-scoped token for dynamic data).
  try {
    const spaces = await req('/api/tenant-selection/my-spaces', { token });
    const list = spaces.json?.data || [];
    const biz = list.find((x) => x.spaceType === 'business' && x.tenantId) || list[0];
    if (biz?.tenantId) {
      const sel = await req('/api/tenant-selection/process', {
        method: 'POST', token, body: { tenantId: biz.tenantId, spaceType: biz.spaceType || 'business' },
        allowError: true,
      });
      const t2 = sel.json?.data?.token || sel.json?.data?.accessToken;
      if (t2) token = t2;
    }
  } catch { /* single-mode may not require space binding */ }
  return token;
}

// Execute a command through the real pipeline.
// POST /api/meta/commands/execute/{code} with {payload, targetRecordPid, operationType}.
export async function execCommand(
  token,
  code,
  payload = {},
  targetRecordPid,
  operationType,
  { allowError = false, clientRequestId, expectedVersion } = {},
) {
  const body = { payload };
  if (targetRecordPid) body.targetRecordPid = targetRecordPid;
  if (operationType) body.operationType = operationType;
  if (clientRequestId) body.clientRequestId = clientRequestId;
  if (expectedVersion != null) body.expectedVersion = expectedVersion;
  const r = await req(`/api/meta/commands/execute/${encodeURIComponent(code)}`, {
    method: 'POST', token, body, allowError,
  });
  const data = r.json?.data?.data ?? r.json?.data ?? {};
  const recordId = data.recordPid || data.recordId || data.pid || data.id || '';
  return { status: r.status, ok: r.ok, code: String(r.json?.code ?? ''), recordId: String(recordId), data, raw: r.json };
}

// Query dynamic model rows (DB round-trip through the read pipeline).
export async function listModel(token, modelCode, { pageSize = 50, filters, keyword } = {}) {
  const body = { pageNum: 1, pageSize };
  if (filters) body.filters = filters;
  if (keyword) body.keyword = keyword;
  const r = await req(`/api/dynamic/${modelCode}/list`, { method: 'POST', token, body, allowError: true });
  return r.json?.data?.records ?? r.json?.data?.list ?? r.json?.data ?? [];
}

export async function getRecord(token, modelCode, pid) {
  const rows = await listModel(token, modelCode, { pageSize: 200 });
  return rows.find((x) => String(x.pid) === String(pid) || String(x.id) === String(pid));
}

// Tiny assertion kit — collects results instead of throwing, so one FR failing does not
// abort the whole suite; the runner reports every FR's pass/fail.
export function makeReporter() {
  const results = [];
  return {
    check(fr, name, cond, detail = '') {
      results.push({ fr, name, pass: !!cond, detail });
      const tag = cond ? 'PASS' : 'FAIL';
      console.log(`  [${tag}] ${fr} · ${name}${detail ? ' — ' + detail : ''}`);
      return !!cond;
    },
    deferred(fr, name, detail = '') {
      results.push({ fr, name, deferred: true, detail });
      console.log(`  [DEFER] ${fr} · ${name}${detail ? ' — ' + detail : ''}`);
    },
    results,
    summary() {
      const scored = results.filter((r) => !r.deferred);
      const pass = scored.filter((r) => r.pass).length;
      const deferred = results.filter((r) => r.deferred).length;
      return { total: scored.length, pass, fail: scored.length - pass, deferred };
    },
  };
}

export const uid = (p) => `${p}-${Math.floor(performance.now() * 1000) % 1e9}`;

// Direct DB round-trip assertion — the authoritative real-stack check. The command runs
// through the real API pipeline; we then read the physical mt_* table with psql, bypassing
// the read API's data-permission scoping (which filters admin-created rows in single-mode).
import { execFileSync } from 'node:child_process';
const PG = {
  host: process.env.PG_HOST || '127.0.0.1',
  port: process.env.PG_PORT || '5432',
  user: process.env.PG_USER || 'auraboot',
  db: process.env.PG_DB || 'auraboot_63',
  pass: process.env.PGPASSWORD || 'auraboot',
};
export function queryDb(sql) {
  const out = execFileSync('psql', ['-h', PG.host, '-p', PG.port, '-U', PG.user, '-d', PG.db, '-tAF', '\t', '-c', sql],
    { env: { ...process.env, PGPASSWORD: PG.pass }, encoding: 'utf8' });
  return out.trim().split('\n').filter(Boolean).map((line) => line.split('\t'));
}
export function scalar(sql) {
  const rows = queryDb(sql);
  return rows.length ? rows[0][0] : null;
}

// Downstream MES goldens exercise material binding and identity behavior, not the already-covered
// ReleaseWorkOrder command. Seed only its immutable bi-temporal prerequisite in the isolated test
// database so those handlers cannot fall back to mutable live BOM rows. The payload shape matches
// mfg-work-order-execution-baseline-v1 and is deliberately minimal for the single BOM line under test.
export function seedExecutionBaseline({
  workOrderId,
  bomId,
  materialId,
  quantity = 1,
  unit = 'pcs',
  refDesignator = 'TEST',
}) {
  const tenantId = scalar(
    `select tenant_id from mt_mfg_work_order_pcba_execution where pid='${sqlText(workOrderId)}'`,
  );
  if (!tenantId || !/^\d+$/.test(tenantId)) {
    throw new Error(`cannot resolve tenant for execution-baseline fixture: ${workOrderId}`);
  }
  const payload = {
    schemaVersion: 1,
    baselineHash: '0'.repeat(64),
    bom: {
      id: bomId,
      code: `TEST-${bomId}`,
      name: 'MES golden immutable BOM fixture',
      version: 'test-v1',
      outputQty: 1,
      contentHash: '1'.repeat(64),
      lines: [{
        lineId: `TEST-LINE-${workOrderId}`,
        materialId,
        quantity,
        unit,
        lossRate: 0,
        refDesignator,
      }],
    },
    workOrder: { id: workOrderId, bomId },
  };
  queryDb(`
    insert into ab_bitemporal_record
      (id, tenant_id, entity_type, entity_id, valid_from, valid_to, tx_from, tx_to,
       payload, version_no, created_by, created_at, updated_at, deleted_flag)
    values
      (nextval('ab_bitemporal_record_id_seq'), ${tenantId}, 'mfg_wo_execution_baseline_v1',
       '${sqlText(workOrderId)}', now(), '9999-12-31 23:59:59', now(),
       '9999-12-31 23:59:59', '${sqlText(JSON.stringify(payload))}'::jsonb, 1,
       ${tenantId}, now(), now(), false)
  `);
  const stored = scalar(`
    select count(*) from ab_bitemporal_record
    where tenant_id=${tenantId}
      and entity_type='mfg_wo_execution_baseline_v1'
      and entity_id='${sqlText(workOrderId)}'
      and tx_to='9999-12-31 23:59:59'
      and deleted_flag=false
  `);
  if (Number(stored) !== 1) {
    throw new Error(`execution-baseline fixture was not stored exactly once: ${workOrderId}`);
  }
}

function sqlText(value) {
  return String(value).replaceAll("'", "''");
}
