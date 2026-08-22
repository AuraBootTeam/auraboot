#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const args = parseArgs(process.argv.slice(2));
const backendA = required(args.backendA, '--backend-a').replace(/\/$/, '');
const backendB = required(args.backendB, '--backend-b').replace(/\/$/, '');
const nodeBPid = Number(required(args.nodeBPid, '--node-b-pid'));
const database = required(args.database ?? process.env.PGDATABASE, '--database');
const output = resolve(required(args.out, '--out'));
const failures = [];
const timeline = [];
const stamp = Date.now().toString();
let tokenA = '';
let tokenB = '';
let poolPid = '';
let customerPid = '';
let itemPid = '';
let nodeBStopped = false;

try {
  await Promise.all([requireHealth(backendA, 'node A'), requireHealth(backendB, 'node B')]);
  const [loginA, loginB] = await Promise.all([login(backendA), login(backendB)]);
  tokenA = loginA.token;
  tokenB = loginB.token;
  if (loginA.userPid !== loginB.userPid) failNow('the two nodes resolved different admin identities');
  const actorPid = loginA.userPid;
  timeline.push({ stage: 'two-nodes-ready', nodeBPid, actorPid });

  const pool = await command(backendA, tokenA, 'crm:create_customer_pool', {
    crm_cp_name: `PAR-06 Multi Node ${stamp}`,
    crm_cp_member_user_ids: JSON.stringify([actorPid]),
    crm_cp_admin_user_ids: JSON.stringify([actorPid]),
    crm_cp_daily_pick_limit: 20,
    crm_cp_new_cooldown_days: 0,
    crm_cp_previous_owner_cooldown_days: 0,
    crm_cp_auto_recycle: false,
    crm_cp_recycle_after_days: 1,
    crm_cp_recycle_basis: 'claimed_at',
    crm_cp_recycle_match_mode: 'all',
    crm_cp_description: 'PAR-06 two-backend shared-store acceptance fixture',
  }, undefined, 'create');
  poolPid = recordPid(pool);
  const customer = await command(backendA, tokenA, 'crm:create_account', {
    crm_acc_name: `PAR-06 Multi Node Customer ${stamp}`,
    crm_acc_industry: 'manufacturing',
    crm_acc_phone: `137${stamp.slice(-8)}`,
    crm_acc_rating: 'A',
    crm_acc_status: 'active',
    crm_acc_pool_state: 'owned',
    crm_acc_remark: 'two-node competition and takeover fixture',
  }, undefined, 'create');
  customerPid = recordPid(customer);
  const moved = await command(backendA, tokenA, 'crm:move_customer_to_pool', {
    poolId: poolPid,
    reason: 'multi-node acceptance',
  }, customerPid, 'update');
  itemPid = String(moved.poolItemId ?? '');
  if (!poolPid || !customerPid || !itemPid) failNow('fixture setup omitted a public record id');
  const moveHistory = historyCount();

  const claimStartedAt = Date.now();
  const claimResponses = await Promise.all([
    rawCommand(backendA, tokenA, 'crm:claim_pool_customer', {}, itemPid, 'update'),
    rawCommand(backendB, tokenB, 'crm:claim_pool_customer', {}, itemPid, 'update'),
  ]);
  const claimSuccesses = claimResponses.filter(applicationSuccess).length;
  const claimed = itemFact();
  const claimHistory = historyCount();
  if (claimSuccesses !== 1) failures.push(`cross-node claim winners=${claimSuccesses}, expected 1`);
  if (claimed.status !== 'claimed' || claimed.claimedBy !== actorPid) {
    failures.push(`claim durable fact mismatch: ${JSON.stringify(claimed)}`);
  }
  if (claimHistory !== moveHistory + 1) {
    failures.push(`claim history count ${claimHistory}, expected ${moveHistory + 1}`);
  }
  timeline.push({
    stage: 'cross-node-claim',
    elapsedMs: Date.now() - claimStartedAt,
    winners: claimSuccesses,
    responses: claimResponses.map(responseSummary),
    status: claimed.status,
    historyCount: claimHistory,
  });

  makeClaimEligible();
  const recycleStartedAt = Date.now();
  const recycleResponses = await Promise.all([
    rawCommand(backendA, tokenA, 'crm:run_customer_pool_recycle'),
    rawCommand(backendB, tokenB, 'crm:run_customer_pool_recycle'),
  ]);
  const recycleFacts = itemFact();
  const recycleHistory = historyCount();
  const recycledTotal = recycleResponses.reduce((sum, response) =>
    sum + Number(releaseResult(response)?.recycled ?? 0), 0);
  if (recycleResponses.filter(applicationSuccess).length !== 2) {
    failures.push('one or both cross-node recycle commands failed');
  }
  if (recycledTotal !== 1) failures.push(`cross-node recycled total=${recycledTotal}, expected 1`);
  if (recycleFacts.status !== 'available' || recycleFacts.recycleToken != null) {
    failures.push(`recycle durable fact mismatch: ${JSON.stringify(recycleFacts)}`);
  }
  if (recycleHistory !== claimHistory + 1) {
    failures.push(`recycle history count ${recycleHistory}, expected ${claimHistory + 1}`);
  }
  timeline.push({
    stage: 'cross-node-recycle',
    elapsedMs: Date.now() - recycleStartedAt,
    recycledTotal,
    responses: recycleResponses.map(responseSummary),
    status: recycleFacts.status,
    historyCount: recycleHistory,
  });

  await command(backendB, tokenB, 'crm:claim_pool_customer', {}, itemPid, 'update');
  const reclaimHistory = historyCount();
  const operationKey = `par06-multi-${stamp}`;
  stageNodeBLease(operationKey);
  const activeLeaseResponse = await rawCommand(backendA, tokenA, 'crm:run_customer_pool_recycle');
  const activeLeaseResult = releaseResult(activeLeaseResponse);
  const activeLeaseFact = itemFact();
  if (!applicationSuccess(activeLeaseResponse) || Number(activeLeaseResult?.activeLeases ?? 0) < 1) {
    failures.push(`node A did not respect node B's fresh lease: ${JSON.stringify(responseSummary(activeLeaseResponse))}`);
  }
  if (activeLeaseFact.status !== 'recycling' || historyCount() !== reclaimHistory) {
    failures.push(`fresh lease changed durable facts: ${JSON.stringify(activeLeaseFact)}`);
  }
  timeline.push({
    stage: 'fresh-node-b-lease-respected',
    activeLeases: Number(activeLeaseResult?.activeLeases ?? 0),
    status: activeLeaseFact.status,
    historyCount: historyCount(),
  });

  process.kill(nodeBPid, 'SIGKILL');
  nodeBStopped = true;
  await waitForDown(backendB);
  expireLease();
  const takeoverResponse = await rawCommand(backendA, tokenA, 'crm:run_customer_pool_recycle');
  const takeoverResult = releaseResult(takeoverResponse);
  const takeoverFact = itemFact();
  const takeoverHistory = historyCount();
  if (!applicationSuccess(takeoverResponse) || Number(takeoverResult?.recovered ?? 0) !== 1) {
    failures.push(`surviving node did not report exactly one recovery: ${JSON.stringify(responseSummary(takeoverResponse))}`);
  }
  if (takeoverFact.status !== 'available' || takeoverFact.recycleToken != null) {
    failures.push(`takeover durable fact mismatch: ${JSON.stringify(takeoverFact)}`);
  }
  if (takeoverHistory !== reclaimHistory + 1) {
    failures.push(`takeover history count ${takeoverHistory}, expected ${reclaimHistory + 1}`);
  }
  timeline.push({
    stage: 'node-b-exit-and-node-a-takeover',
    nodeBPid,
    recovered: Number(takeoverResult?.recovered ?? 0),
    status: takeoverFact.status,
    historyCount: takeoverHistory,
  });
} catch (error) {
  failures.push(error instanceof Error ? error.message : String(error));
}

const report = {
  verdict: failures.length === 0 ? 'pass' : 'fail',
  claim: 'two independent backend JVMs share PostgreSQL and Redis; cross-node claim/recycle are single-winner and a surviving node respects then takes over an expired durable lease after the peer exits',
  topology: { backendA, backendB, nodeBPid, sharedDatabase: database, nodeBStopped },
  fixture: { poolPid, customerPid, itemPid },
  timeline,
  failures,
};
writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
if (failures.length > 0) process.exit(1);

async function login(baseUrl) {
  const response = await postJson(`${baseUrl}/api/auth/login`, {
    email: 'admin@auraboot.com', password: 'Test2026x',
  });
  const token = String(response.body?.data?.jwt ?? '');
  const userPid = String(response.body?.data?.userPid ?? '');
  if (!response.ok || !token || !userPid) failNow(`${baseUrl} login failed`);
  return { token, userPid };
}

async function command(baseUrl, token, code, payload = {}, targetRecordPid, operationType) {
  const response = await rawCommand(baseUrl, token, code, payload, targetRecordPid, operationType);
  if (!applicationSuccess(response)) failNow(`${code} failed on ${baseUrl}: HTTP ${response.status}`);
  return releaseResult(response) ?? {};
}

async function rawCommand(baseUrl, token, code, payload = {}, targetRecordPid, operationType) {
  return postJson(`${baseUrl}/api/meta/commands/execute/${code}`, {
    payload,
    ...(targetRecordPid ? { targetRecordPid } : {}),
    ...(operationType ? { operationType } : {}),
  }, token);
}

async function postJson(url, body, token = '') {
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(30_000),
    });
    return { ok: response.ok, status: response.status, body: await response.json().catch(() => ({})) };
  } catch (error) {
    return { ok: false, status: 0, body: {}, error: error instanceof Error ? error.name : String(error) };
  }
}

async function requireHealth(baseUrl, label) {
  const response = await fetch(`${baseUrl}/actuator/health`, { signal: AbortSignal.timeout(5_000) }).catch(() => null);
  const body = response ? await response.text() : '';
  if (!response?.ok || !body.includes('"status":"UP"')) failNow(`${label} is not healthy`);
}

async function waitForDown(baseUrl) {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    const response = await fetch(`${baseUrl}/actuator/health`, { signal: AbortSignal.timeout(1_000) }).catch(() => null);
    if (!response) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 200));
  }
  failNow('node B remained reachable after SIGKILL');
}

function makeClaimEligible() {
  psqlExecute(`
    UPDATE mt_crm_customer_pool_common
    SET crm_cp_auto_recycle=true, crm_cp_recycle_after_days=1,
        crm_cp_recycle_basis='claimed_at', updated_at=now()
    WHERE pid='${sqlLit(poolPid)}';
    UPDATE mt_crm_customer_pool_item_common
    SET crm_cpi_claimed_at=now() - interval '30 days', updated_at=now()
    WHERE pid='${sqlLit(itemPid)}';
    UPDATE mt_crm_account_common
    SET crm_acc_claimed_at=now() - interval '30 days', updated_at=now()
    WHERE pid='${sqlLit(customerPid)}';
  `);
}

function stageNodeBLease(operationKey) {
  psqlExecute(`
    UPDATE mt_crm_customer_pool_item_common
    SET crm_cpi_status='recycling', crm_cpi_recycle_token='${sqlLit(operationKey)}:node-${nodeBPid}', updated_at=now()
    WHERE pid='${sqlLit(itemPid)}';
  `);
}

function expireLease() {
  psqlExecute(`
    UPDATE mt_crm_customer_pool_item_common SET updated_at=now() - interval '1 day'
    WHERE pid='${sqlLit(itemPid)}';
  `);
}

function itemFact() {
  return JSON.parse(psqlScalar(`
    SELECT json_build_object('status', crm_cpi_status, 'claimedBy', crm_cpi_claimed_by,
      'recycleToken', crm_cpi_recycle_token)::text
    FROM mt_crm_customer_pool_item_common WHERE pid='${sqlLit(itemPid)}'
  `));
}

function historyCount() {
  return Number(psqlScalar(`SELECT count(*) FROM mt_crm_customer_owner_history_common
    WHERE crm_coh_customer_id='${sqlLit(customerPid)}'`));
}

function psqlScalar(sql) {
  return execFileSync('psql', ['-h', process.env.PGHOST ?? '127.0.0.1', '-p', process.env.PGPORT ?? '5432',
    '-U', process.env.PGUSER ?? 'auraboot', '-d', database, '-t', '-A', '-P', 'pager=off',
    '-v', 'ON_ERROR_STOP=1', '-c', sql], {
    env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD ?? 'auraboot' }, encoding: 'utf8',
  }).trim();
}

function psqlExecute(sql) {
  execFileSync('psql', ['-h', process.env.PGHOST ?? '127.0.0.1', '-p', process.env.PGPORT ?? '5432',
    '-U', process.env.PGUSER ?? 'auraboot', '-d', database, '-q', '-v', 'ON_ERROR_STOP=1', '-c', sql], {
    env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD ?? 'auraboot' }, encoding: 'utf8',
  });
}

function applicationSuccess(response) { return response.ok && String(response.body?.code) === '0'; }
function releaseResult(response) { return response.body?.data?.data ?? null; }
function recordPid(result) { return String(result.recordId ?? result.recordPid ?? result.publicRecordId ?? result.pid ?? result.id ?? ''); }
function responseSummary(response) {
  const result = releaseResult(response);
  return { ok: response.ok, status: response.status, applicationCode: response.body?.code ?? null,
    result: result && typeof result === 'object' ? result : null, error: response.error ?? null };
}
function sqlLit(value) { return String(value).replace(/'/g, "''"); }
function required(value, label) { if (value == null || String(value).trim() === '') failNow(`${label} is required`); return String(value).trim(); }
function failNow(message) { throw new Error(message); }
function parseArgs(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith('--') || value == null) failNow(`invalid argument ${key ?? ''}`);
    parsed[key.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = value;
  }
  return parsed;
}
