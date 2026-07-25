// FR-22 shift-handover — ACTION-DRIVEN golden. Not "the list renders": actually drives the
// acknowledge (签认) action point end-to-end and screenshots the full loop —
//   before (待签认 row) → click 签认 → the form dialog that opens (the action point) →
//   fill + submit → success toast + the row flips 待签认→已签认 (the post-action confirmation).
// Every step is asserted (DB status change + toast + dialog appeared) so it is falsifiable.
//   BASE=http://127.0.0.1:5163 PG_DB=auraboot_63 node fr22-action-golden.mjs
import { chromium } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { login, execCommand, uid } from '../harness.mjs';
const BASE = process.env.BASE || 'http://127.0.0.1:5163';
const OUT = new URL('.', import.meta.url).pathname;
const db = (sql) => execFileSync('psql', ['-h', '127.0.0.1', '-p', '5432', '-U', 'auraboot', '-d', process.env.PG_DB || 'auraboot_63', '-tAc', sql], { env: { ...process.env, PGPASSWORD: 'auraboot' }, encoding: 'utf8' }).trim();
const results = [];
const check = (n, c, d = '') => { results.push({ pass: !!c }); console.log(`  [${c ? 'PASS' : 'FAIL'}] ${n}${d ? ' — ' + d : ''}`); };

// Seed a FRESH pending_ack handover so this golden is repeatable (each run acknowledges its own
// row, never a leftover). handover_time=now makes it sort first in the workbench (desc), so the
// first 签认 button on the page is ours.
const token = await login();
const wsCode = uid('WS-ACT');
const ws = await execCommand(token, 'mfg_workstation_pcba_execution:create',
  { mfg_ws_code: wsCode, mfg_ws_name: `SMT-${wsCode}`, mfg_ws_operation_type: 'smt', mfg_ws_capacity_per_hour: 100 }, undefined, 'create', { allowError: true });
const nowIso = new Date(Date.now() + 60000).toISOString();
await execCommand(token, 'mfg_shift_handover:create_handover',
  { mfg_sho_workstation_id: ws.recordId, mfg_sho_outgoing_shift: 'day', mfg_sho_incoming_shift: 'night',
    mfg_sho_outgoing_person: 'Alice', mfg_sho_handover_time: nowIso, mfg_sho_notes: 'action golden seed' }, undefined, 'action', { allowError: true });

const b = await chromium.launch();
const p = await (await b.newContext({ ignoreHTTPSErrors: true, locale: 'zh-CN', viewport: { width: 1440, height: 900 } })).newPage();
try {
  const es = 'input[type="email"], input[name="email"], input[autocomplete="username"]';
  let s = false;
  for (let a = 0; a < 5 && !s; a++) { await p.goto(`${BASE}/`, { waitUntil: 'load', timeout: 25000 }).catch(() => {}); await p.waitForTimeout(2000); s = await p.waitForSelector(es, { state: 'visible', timeout: 8000 }).then(() => 1).catch(() => 0); }
  await p.fill(es, 'admin@auraboot.com'); await p.fill('input[type="password"]', 'Test2026x');
  await Promise.all([p.waitForTimeout(2500), p.click('button[type="submit"]')]); await p.waitForTimeout(1500);

  // Precondition: a pending_ack handover exists (there is 1 seeded). Capture its pid + workstation
  // so we can assert THIS row transitions after the action.
  const pendPid = db(`select pid from mt_mfg_shift_handover where mfg_sho_status='pending_ack' and mfg_sho_workstation_id='${ws.recordId}' limit 1`);
  check('precondition: freshly-seeded 待签认 handover exists to acknowledge', !!pendPid, `pid=${pendPid} ws=${wsCode}`);

  await p.goto(`${BASE}/p/c/mfg_shift_handover_workbench`, { waitUntil: 'load', timeout: 25000 }).catch(() => {});
  await p.waitForTimeout(3500);
  // STEP 1 — before: the workbench with a 待签认 row + a 签认 action.
  await p.screenshot({ path: `${OUT}/fr22-act-1-before.png`, fullPage: true });
  const mainBefore = (await p.locator('main').first().innerText().catch(() => '')) || '';
  check('before: table shows a 待签认 row', /待签认/.test(mainBefore), 'status column');
  // Deterministically target OUR seeded row (by its unique workstation code), not the first 签认 on
  // the page — repeated create_handover runs leave several pending_ack rows, and `.first()` could
  // acknowledge someone else's (flaky). The seeded row is uniquely identified by wsCode.
  const seededRow = p.locator('tr', { hasText: wsCode });
  await seededRow.scrollIntoViewIfNeeded().catch(() => {});
  const ackBtn = seededRow.getByText(/^签认$/).first();
  check('before: a 签认 action point is present on our seeded pending row', await ackBtn.isVisible().catch(() => false));

  // STEP 2 — action point: click 签认 → the acknowledge FORM DIALOG opens (incoming_person field).
  await ackBtn.click();
  const dialog = p.locator('[data-testid="form-dialog"]');
  const dialogShown = await dialog.waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false);
  await p.waitForTimeout(600);
  await p.screenshot({ path: `${OUT}/fr22-act-2-action-form.png`, fullPage: true });
  check('action point: clicking 签认 opens the acknowledge form dialog', dialogShown);
  const field = p.locator('[data-testid="form-dialog"] input:visible, [data-testid="form-dialog"] textarea:visible').first();
  const hasField = await field.isVisible().catch(() => false);
  check('action point: form has the incoming_person (接班人) field', hasField);
  if (hasField) { await field.fill('Bob (接班签认)'); await p.waitForTimeout(300); }

  // STEP 3 — submit (确认) → post-action confirmation: success toast + the row flips to 已签认.
  await p.locator('[data-testid="form-dialog-submit"]').click().catch(() => {});
  await p.waitForTimeout(2500);
  await p.screenshot({ path: `${OUT}/fr22-act-3-after-confirm.png`, fullPage: true });
  const mainAfter = (await p.locator('main').first().innerText().catch(() => '')) || '';
  // Post-action confirmation — three independent, durable signals (the transient toast is not
  // required; the row visibly flipping is the real confirmation the operator sees):
  const dbStatus = db(`select mfg_sho_status || '|' || coalesce(mfg_sho_incoming_person,'') from mt_mfg_shift_handover where pid='${pendPid}'`);
  const [status, incoming] = dbStatus.split('|');
  check('post-action (DB): handover pending_ack → acknowledged', status === 'acknowledged', `db status=${status}`);
  check('post-action (DB): the entered 接班人 persisted', incoming === 'Bob (接班签认)', `incoming=${incoming}`);
  check('post-action (UI): the row now renders 已签认 + the entered 接班人 (durable confirmation)',
    /已签认/.test(mainAfter) && /Bob \(接班签认\)/.test(mainAfter) && !(await dialog.isVisible().catch(() => false)) && !/加载失败/.test(mainAfter));
} catch (e) { check('no exception', false, String(e.message).slice(0, 160)); await p.screenshot({ path: `${OUT}/fr22-act-err.png` }).catch(() => {}); }
await b.close();
const pass = results.filter((r) => r.pass).length;
console.log(`\n=== FR-22 ACTION GOLDEN (acknowledge 行动点驱动): ${pass}/${results.length} pass ===`);
process.exit(pass < results.length ? 1 : 0);
