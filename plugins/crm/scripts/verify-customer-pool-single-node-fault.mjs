#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  printHelp();
  process.exit(0);
}

const stackName = required(args.stackName, '--stack-name');
const slot = requiredInteger(args.slot, '--slot');
const backendUrl = required(args.backendUrl, '--backend-url').replace(/\/$/, '');
const bffUrl = required(args.bffUrl, '--bff-url').replace(/\/$/, '');
const database = required(args.database ?? process.env.PGDATABASE, '--database');
const output = resolve(required(args.out, '--out'));
const workspace = resolve(args.workspace ?? resolve(process.cwd(), '..'));
const repoRoot = process.cwd();
const stateDir = resolve(workspace, '.workspace', 'golden', stackName);
const backendPidFile = resolve(stateDir, 'backend.pid');
const stamp = Date.now().toString();
const failures = [];
const timeline = [];
let restartRequired = false;
let token = '';
let poolPid = '';
let customerPid = '';
let itemPid = '';
let baselineHistory = 0;
let pauseTriggerInstalled = false;
let outageResponse = null;
let recoveryResponse = null;
let retryResponse = null;
let midTransactionResponse = null;
let recycleRecoveryResponse = null;
let recycleRetryResponse = null;
let facts = {};

try {
  await requireHealth('baseline');
  const login = await postJson(`${backendUrl}/api/auth/login`, {
    email: 'admin@auraboot.com',
    password: 'Test2026x',
  });
  token = String(login.body?.data?.jwt ?? '');
  const adminPid = String(login.body?.data?.userPid ?? '');
  if (!login.ok || !token || !adminPid) failNow('admin login did not return jwt and userPid');

  const pool = await command('crm:create_customer_pool', {
    crm_cp_name: `PAR-06 Single Node Fault ${stamp}`,
    crm_cp_member_user_ids: JSON.stringify([adminPid]),
    crm_cp_admin_user_ids: JSON.stringify([adminPid]),
    crm_cp_daily_pick_limit: 10,
    crm_cp_new_cooldown_days: 0,
    crm_cp_previous_owner_cooldown_days: 0,
    crm_cp_auto_recycle: false,
    crm_cp_recycle_after_days: 30,
    crm_cp_recycle_basis: 'claimed_at',
    crm_cp_recycle_match_mode: 'all',
    crm_cp_description: 'PAR-06 reproducible single-node process-exit fixture',
  }, undefined, 'create');
  poolPid = recordPid(pool);
  if (!poolPid) failNow('create_customer_pool returned no public record id');

  const customer = await command('crm:create_account', {
    crm_acc_name: `PAR-06 Fault Customer ${stamp}`,
    crm_acc_industry: 'manufacturing',
    crm_acc_phone: `139${stamp.slice(-8)}`,
    crm_acc_rating: 'A',
    crm_acc_status: 'active',
    crm_acc_remark: 'single-node fault acceptance fixture',
  }, undefined, 'create');
  customerPid = recordPid(customer);
  if (!customerPid) failNow('create_account returned no public record id');

  const moved = await command(
    'crm:move_customer_to_pool',
    { poolId: poolPid, reason: 'single-node fault acceptance' },
    customerPid,
  );
  itemPid = String(moved.poolItemId ?? '');
  if (!itemPid) failNow('move_customer_to_pool returned no pool item id');
  const before = itemFact(itemPid);
  baselineHistory = historyCount(customerPid);
  if (before.status !== 'available' || before.claimedBy != null) {
    failNow(`fixture is not claimable before outage: ${JSON.stringify(before)}`);
  }
  timeline.push({ stage: 'fixture-ready', status: before.status, historyCount: baselineHistory });

  const backendPid = Number(readFileSync(backendPidFile, 'utf8').trim());
  if (!Number.isInteger(backendPid) || backendPid <= 1) failNow('backend pid file is invalid');
  const listenerPid = Number(psqlLikeCommand('lsof', [
    '-nP', `-iTCP:${new URL(backendUrl).port}`, '-sTCP:LISTEN', '-t',
  ]).trim());
  if (listenerPid !== backendPid) {
    failNow(`backend pid ${backendPid} does not own ${backendUrl}; listener=${listenerPid || 'none'}`);
  }
  process.kill(backendPid, 'SIGKILL');
  restartRequired = true;
  await waitForDown();
  timeline.push({ stage: 'backend-killed', pid: backendPid });

  outageResponse = await postJson(
    `${bffUrl}/api/meta/commands/execute/crm:claim_pool_customer`,
    { targetRecordPid: itemPid, operationType: 'update', payload: {} },
    token,
    8_000,
  );
  if (outageResponse.ok && String(outageResponse.body?.code) === '0') {
    failures.push('claim unexpectedly succeeded while the only backend process was down');
  }
  const afterRejected = itemFact(itemPid);
  const rejectedHistory = historyCount(customerPid);
  if (afterRejected.status !== 'available' || afterRejected.claimedBy != null) {
    failures.push(`outage request mutated pool item: ${JSON.stringify(afterRejected)}`);
  }
  if (rejectedHistory !== baselineHistory) {
    failures.push(`outage request changed history count ${baselineHistory} -> ${rejectedHistory}`);
  }
  timeline.push({
    stage: 'outage-request-rejected',
    httpStatus: outageResponse.status,
    status: afterRejected.status,
    historyCount: rejectedHistory,
  });

  const restartStartedAt = Date.now();
  restartStack();
  restartRequired = false;
  await requireHealth('restart');
  const restartMs = Date.now() - restartStartedAt;
  const relogin = await postJson(`${backendUrl}/api/auth/login`, {
    email: 'admin@auraboot.com',
    password: 'Test2026x',
  });
  token = String(relogin.body?.data?.jwt ?? '');
  if (!relogin.ok || !token) failNow('admin re-login failed after backend restart');

  recoveryResponse = await rawCommand('crm:claim_pool_customer', {}, itemPid, 'update');
  if (!recoveryResponse.ok || String(recoveryResponse.body?.code) !== '0') {
    failures.push(`claim did not recover after restart: HTTP ${recoveryResponse.status}`);
  }
  const afterRecovery = itemFact(itemPid);
  const recoveredHistory = historyCount(customerPid);
  const account = accountFact(customerPid);
  if (afterRecovery.status !== 'claimed') failures.push(`recovered status is ${afterRecovery.status}, expected claimed`);
  if (recoveredHistory !== baselineHistory + 1) {
    failures.push(`recovered history count ${recoveredHistory}, expected ${baselineHistory + 1}`);
  }
  if (account.owner == null || account.poolState === 'in_pool') {
    failures.push(`account ownership did not converge: ${JSON.stringify(account)}`);
  }
  timeline.push({
    stage: 'restart-recovered',
    restartMs,
    status: afterRecovery.status,
    historyCount: recoveredHistory,
    accountPoolState: account.poolState,
  });

  retryResponse = await rawCommand('crm:claim_pool_customer', {}, itemPid, 'update');
  if (retryResponse.ok && String(retryResponse.body?.code) === '0') {
    failures.push('duplicate client retry unexpectedly succeeded after the item was already claimed');
  }
  const finalFact = itemFact(itemPid);
  const finalHistory = historyCount(customerPid);
  if (finalFact.status !== 'claimed' || finalHistory !== baselineHistory + 1) {
    failures.push(`duplicate retry changed durable facts: ${JSON.stringify({ finalFact, finalHistory })}`);
  }
  timeline.push({
    stage: 'duplicate-retry-rejected',
    httpStatus: retryResponse.status,
    status: finalFact.status,
    historyCount: finalHistory,
  });

  psqlExecute(`
    UPDATE mt_crm_customer_pool_common
    SET crm_cp_auto_recycle=true,
        crm_cp_recycle_after_days=1,
        crm_cp_recycle_basis='claimed_at',
        updated_at=now()
    WHERE pid='${sqlLit(poolPid)}';
    UPDATE mt_crm_customer_pool_item_common
    SET crm_cpi_claimed_at=now() - interval '30 days',
        crm_cpi_entered_at=now() - interval '31 days',
        updated_at=now()
    WHERE pid='${sqlLit(itemPid)}';
    UPDATE mt_crm_account_common
    SET crm_acc_claimed_at=now() - interval '30 days',
        updated_at=now()
    WHERE pid='${sqlLit(customerPid)}';
  `);
  installPauseTrigger(itemPid);
  pauseTriggerInstalled = true;
  const midCommandPromise = rawCommand('crm:run_customer_pool_recycle');
  await waitForDatabaseSleep();
  const midTransactionPid = ownedBackendPid();
  process.kill(midTransactionPid, 'SIGKILL');
  restartRequired = true;
  await waitForDown();
  midTransactionResponse = await midCommandPromise;
  const rolledBack = itemFact(itemPid);
  const rolledBackHistory = historyCount(customerPid);
  if (rolledBack.status !== 'claimed' || rolledBackHistory !== baselineHistory + 1) {
    failures.push(`mid-transaction process exit did not roll back: ${JSON.stringify({ rolledBack, rolledBackHistory })}`);
  }
  removePauseTrigger();
  pauseTriggerInstalled = false;
  timeline.push({
    stage: 'mid-transaction-backend-killed',
    pid: midTransactionPid,
    httpStatus: midTransactionResponse.status,
    status: rolledBack.status,
    historyCount: rolledBackHistory,
  });

  const secondRestartStartedAt = Date.now();
  restartStack();
  restartRequired = false;
  await requireHealth('mid-transaction-restart');
  const secondRestartMs = Date.now() - secondRestartStartedAt;
  const secondRelogin = await postJson(`${backendUrl}/api/auth/login`, {
    email: 'admin@auraboot.com',
    password: 'Test2026x',
  });
  token = String(secondRelogin.body?.data?.jwt ?? '');
  if (!secondRelogin.ok || !token) failNow('admin re-login failed after mid-transaction restart');

  recycleRecoveryResponse = await rawCommand('crm:run_customer_pool_recycle');
  if (!recycleRecoveryResponse.ok || String(recycleRecoveryResponse.body?.code) !== '0') {
    failures.push(`recycle did not recover after mid-transaction restart: HTTP ${recycleRecoveryResponse.status}`);
  }
  const afterRecycleRecovery = itemFact(itemPid);
  const recycleHistory = historyCount(customerPid);
  if (afterRecycleRecovery.status !== 'available' || recycleHistory !== baselineHistory + 2) {
    failures.push(`recycle recovery durable facts mismatch: ${JSON.stringify({ afterRecycleRecovery, recycleHistory })}`);
  }
  timeline.push({
    stage: 'mid-transaction-recycle-recovered',
    restartMs: secondRestartMs,
    status: afterRecycleRecovery.status,
    historyCount: recycleHistory,
  });

  recycleRetryResponse = await rawCommand('crm:run_customer_pool_recycle');
  const afterRecycleRetry = itemFact(itemPid);
  const recycleRetryHistory = historyCount(customerPid);
  if (afterRecycleRetry.status !== 'available' || recycleRetryHistory !== baselineHistory + 2) {
    failures.push(`recycle retry changed durable facts: ${JSON.stringify({ afterRecycleRetry, recycleRetryHistory })}`);
  }
  timeline.push({
    stage: 'mid-transaction-recycle-retry-idempotent',
    httpStatus: recycleRetryResponse.status,
    status: afterRecycleRetry.status,
    historyCount: recycleRetryHistory,
  });
  facts = {
    before,
    afterRejected,
    afterRecovery,
    finalFact,
    account,
    rolledBack,
    afterRecycleRecovery,
    afterRecycleRetry,
  };
} catch (error) {
  failures.push(error instanceof Error ? error.message : String(error));
} finally {
  if (pauseTriggerInstalled) {
    try {
      removePauseTrigger();
      pauseTriggerInstalled = false;
    } catch (error) {
      failures.push(`pause trigger cleanup failed: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
  if (restartRequired) {
    try {
      restartStack();
      await requireHealth('finally-restart');
      restartRequired = false;
      timeline.push({ stage: 'finally-restart-complete' });
    } catch (error) {
      failures.push(`backend restore failed: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
}

const report = {
  verdict: failures.length === 0 ? 'pass' : 'fail',
  claim: 'single-node pre-dispatch and mid-transaction process-exit, fail-closed rollback, restart recovery and duplicate-retry evidence; not multi-node failover',
  stackName,
  slot,
  database,
  fixture: { customerPid, itemPid },
  timeline,
  responses: {
    outage: responseSummary(outageResponse),
    recovery: responseSummary(recoveryResponse),
    duplicateRetry: responseSummary(retryResponse),
    midTransaction: responseSummary(midTransactionResponse),
    recycleRecovery: responseSummary(recycleRecoveryResponse),
    recycleRetry: responseSummary(recycleRetryResponse),
  },
  facts,
  failures,
};
writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify({
  verdict: report.verdict,
  claim: report.claim,
  timeline: report.timeline,
  failures: report.failures,
}, null, 2));
if (failures.length > 0) process.exit(1);

async function command(code, payload = {}, targetRecordPid, operationType) {
  const response = await rawCommand(code, payload, targetRecordPid, operationType);
  if (!response.ok || String(response.body?.code) !== '0') {
    failNow(`${code} failed: HTTP ${response.status}`);
  }
  return response.body?.data?.data ?? {};
}

async function rawCommand(code, payload = {}, targetRecordPid, operationType) {
  return postJson(`${bffUrl}/api/meta/commands/execute/${code}`, {
    payload,
    ...(targetRecordPid ? { targetRecordPid } : {}),
    ...(operationType ? { operationType } : {}),
  }, token, 20_000);
}

async function postJson(url, data, bearer = '', timeoutMs = 20_000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
      },
      body: JSON.stringify(data),
      signal: controller.signal,
    });
    const body = await response.json().catch(() => ({}));
    return { ok: response.ok, status: response.status, body };
  } catch (error) {
    return { ok: false, status: 0, body: {}, error: error instanceof Error ? error.name : String(error) };
  } finally {
    clearTimeout(timeout);
  }
}

async function requireHealth(stage) {
  const response = await fetch(`${backendUrl}/actuator/health`).catch(() => null);
  const body = response ? await response.text() : '';
  if (!response?.ok || !body.includes('"status":"UP"')) failNow(`${stage} backend health is not UP`);
}

async function waitForDown() {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const response = await fetch(`${backendUrl}/actuator/health`, { signal: AbortSignal.timeout(1_000) }).catch(() => null);
    if (!response) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  failNow('backend port remained reachable after SIGKILL');
}

function restartStack() {
  execFileSync(resolve(repoRoot, 'scripts', 'oss-golden-stack.sh'), [
    'up', stackName, '--slot', slot, '--no-frontend', '--no-warm',
    '--plugin', 'org-management', '--plugin', 'crm',
  ], { cwd: repoRoot, env: process.env, encoding: 'utf8', timeout: 600_000, stdio: ['ignore', 'pipe', 'pipe'] });
}

function ownedBackendPid() {
  const backendPid = Number(readFileSync(backendPidFile, 'utf8').trim());
  if (!Number.isInteger(backendPid) || backendPid <= 1) failNow('backend pid file is invalid');
  const listenerPid = Number(psqlLikeCommand('lsof', [
    '-nP', `-iTCP:${new URL(backendUrl).port}`, '-sTCP:LISTEN', '-t',
  ]).trim());
  if (listenerPid !== backendPid) {
    failNow(`backend pid ${backendPid} does not own ${backendUrl}; listener=${listenerPid || 'none'}`);
  }
  return backendPid;
}

function installPauseTrigger(pid) {
  removePauseTrigger();
  psqlExecute(`
    CREATE FUNCTION par06_customer_pool_fault_pause() RETURNS trigger
    LANGUAGE plpgsql AS $$
    BEGIN
      IF NEW.pid='${sqlLit(pid)}' AND NEW.crm_cpi_status='recycling' THEN
        PERFORM pg_sleep(20);
      END IF;
      RETURN NEW;
    END;
    $$;
    CREATE TRIGGER par06_customer_pool_fault_pause
    BEFORE UPDATE ON mt_crm_customer_pool_item_common
    FOR EACH ROW EXECUTE FUNCTION par06_customer_pool_fault_pause();
  `);
}

function removePauseTrigger() {
  psqlExecute(`
    DROP TRIGGER IF EXISTS par06_customer_pool_fault_pause ON mt_crm_customer_pool_item_common;
    DROP FUNCTION IF EXISTS par06_customer_pool_fault_pause();
  `);
}

async function waitForDatabaseSleep() {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const sleepers = Number(psqlScalar(`
      SELECT count(*)
      FROM pg_stat_activity
      WHERE datname='${sqlLit(database)}'
        AND wait_event='PgSleep'
        AND query ILIKE '%mt_crm_customer_pool_item_common%'
    `));
    if (sleepers > 0) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 200));
  }
  failNow('recycle command never reached the controlled database sleep stage');
}

function itemFact(pid) {
  return JSON.parse(psqlScalar(`
    SELECT json_build_object(
      'status', crm_cpi_status,
      'claimedBy', crm_cpi_claimed_by,
      'recycleToken', crm_cpi_recycle_token
    )::text
    FROM mt_crm_customer_pool_item_common
    WHERE pid='${sqlLit(pid)}'
  `));
}

function accountFact(pid) {
  return JSON.parse(psqlScalar(`
    SELECT json_build_object(
      'owner', crm_acc_owner,
      'poolState', crm_acc_pool_state
    )::text
    FROM mt_crm_account_common
    WHERE pid='${sqlLit(pid)}'
  `));
}

function historyCount(pid) {
  return Number(psqlScalar(`
    SELECT count(*)
    FROM mt_crm_customer_owner_history_common
    WHERE crm_coh_customer_id='${sqlLit(pid)}'
  `));
}

function psqlScalar(sql) {
  return execFileSync('psql', [
    '-h', process.env.PGHOST ?? '127.0.0.1',
    '-p', process.env.PGPORT ?? '5432',
    '-U', process.env.PGUSER ?? 'auraboot',
    '-d', database,
    '-t', '-A', '-P', 'pager=off', '-v', 'ON_ERROR_STOP=1', '-c', sql,
  ], {
    env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD ?? 'auraboot' },
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function psqlExecute(sql) {
  execFileSync('psql', [
    '-h', process.env.PGHOST ?? '127.0.0.1',
    '-p', process.env.PGPORT ?? '5432',
    '-U', process.env.PGUSER ?? 'auraboot',
    '-d', database,
    '-q', '-v', 'ON_ERROR_STOP=1', '-c', sql,
  ], {
    env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD ?? 'auraboot' },
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function psqlLikeCommand(commandName, commandArgs) {
  try {
    return execFileSync(commandName, commandArgs, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (error) {
    return String(error?.stdout ?? '');
  }
}

function recordPid(result) {
  return String(result.recordId ?? result.recordPid ?? result.publicRecordId ?? result.pid ?? result.id ?? '');
}

function responseSummary(response) {
  if (!response) return null;
  return {
    ok: response.ok,
    status: response.status,
    applicationCode: response.body?.code ?? null,
    error: response.error ?? null,
  };
}

function parseArgs(argv) {
  const result = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') return { help: true };
    if (!arg.startsWith('--') || !argv[i + 1] || argv[i + 1].startsWith('--')) failNow(`invalid argument ${arg}`);
    result[arg.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = argv[++i];
  }
  return result;
}
function required(value, label) {
  if (value == null || String(value).trim() === '') failNow(`${label} is required`);
  return String(value).trim();
}
function requiredInteger(value, label) {
  const text = required(value, label);
  if (!/^\d+$/.test(text)) failNow(`${label} must be an integer`);
  return text;
}
function sqlLit(value) { return String(value).replace(/'/g, "''"); }
function failNow(message) { throw new Error(message); }
function printHelp() {
  console.log(`Usage:
  node plugins/crm/scripts/verify-customer-pool-single-node-fault.mjs \\
    --stack-name <runtime> --slot <N> --backend-url <url> --bff-url <url> \\
    --database <db> --workspace <workspace> --out <report.json>

The verifier creates a self-contained customer-pool fixture and SIGKILLs the one owned backend
both before dispatch and during a controlled database write. It proves fail-closed durability,
transaction rollback, restart recovery and duplicate-retry idempotence. It does not claim
multi-node failover.`);
}
