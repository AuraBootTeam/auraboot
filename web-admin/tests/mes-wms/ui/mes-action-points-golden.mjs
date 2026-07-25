// MES action-points golden — drives the workbench action buttons that were BROKEN (fired an empty
// payload → 400) until each got its inputFields declared. For each: open the form (the action
// point), fill it, submit, and assert the real state change + screenshot the loop. Repeatable
// (seeds its own preconditions). Falsifiable: without the inputFields fix every submit 400s.
//   BASE=http://127.0.0.1:5163 PG_DB=auraboot_63 BACKEND_URL=http://127.0.0.1:6463 node mes-action-points-golden.mjs
import { chromium } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { login, execCommand, uid } from '../harness.mjs';
const BASE = process.env.BASE || 'http://127.0.0.1:5163';
const OUT = new URL('.', import.meta.url).pathname;
const db = (sql) => execFileSync('psql', ['-h', '127.0.0.1', '-p', '5432', '-U', 'auraboot', '-d', process.env.PG_DB || 'auraboot_63', '-tAc', sql], { env: { ...process.env, PGPASSWORD: 'auraboot' }, encoding: 'utf8' }).trim();
const results = [];
const check = (n, c, d = '') => { results.push({ pass: !!c }); console.log(`  [${c ? 'PASS' : 'FAIL'}] ${n}${d ? ' — ' + d : ''}`); };
const F = (p, f) => p.locator(`[data-testid="form-dialog-field-${f}"]`);
async function openForm(p, triggerText) {
  await p.getByText(triggerText).first().click();
  return p.locator('[data-testid="form-dialog"]').waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false);
}

const token = await login();
// A pending operation to use as an exception/hold target.
const opPid = db("select pid from mt_mfg_work_order_operation_pcba_execution where mfg_wop_status='pending' order by created_at desc limit 1");

const b = await chromium.launch();
const p = await (await b.newContext({ ignoreHTTPSErrors: true, locale: 'zh-CN', viewport: { width: 1440, height: 900 } })).newPage();
try {
  const es = 'input[type="email"], input[name="email"], input[autocomplete="username"]';
  let s = false;
  for (let a = 0; a < 5 && !s; a++) { await p.goto(`${BASE}/`, { waitUntil: 'load', timeout: 25000 }).catch(() => {}); await p.waitForTimeout(2000); s = await p.waitForSelector(es, { state: 'visible', timeout: 8000 }).then(() => 1).catch(() => 0); }
  await p.fill(es, 'admin@auraboot.com'); await p.fill('input[type="password"]', 'Test2026x');
  await Promise.all([p.waitForTimeout(2500), p.click('button[type="submit"]')]); await p.waitForTimeout(1500);

  // ── 1. create_handover (生成交接单) ────────────────────────────────────────
  await p.goto(`${BASE}/p/c/mfg_shift_handover_workbench`, { waitUntil: 'load', timeout: 25000 }).catch(() => {});
  await p.waitForTimeout(3000);
  const hoBefore = Number(db('select count(*) from mt_mfg_shift_handover'));
  const f1 = await openForm(p, /^生成交接单$/);
  await p.waitForTimeout(1000);
  await p.screenshot({ path: `${OUT}/act-create-handover-form.png`, fullPage: true });
  check('create_handover: 生成交接单 opens a form', f1);
  await F(p, 'mfg_sho_workstation_id').selectOption({ index: 1 });
  await F(p, 'mfg_sho_outgoing_shift').selectOption('day');
  await F(p, 'mfg_sho_incoming_shift').selectOption('night');
  await F(p, 'mfg_sho_outgoing_person').fill('Alice');
  await p.waitForTimeout(300);
  await p.locator('[data-testid="form-dialog-submit"]').click();
  await p.waitForTimeout(2500);
  await p.screenshot({ path: `${OUT}/act-create-handover-after.png`, fullPage: true });
  const hoAfter = Number(db('select count(*) from mt_mfg_shift_handover'));
  check('create_handover: a new handover was created (state change)', hoAfter === hoBefore + 1, `count ${hoBefore}→${hoAfter}`);

  // ── 2. resolve (Andon 解决异常) — seed an open exception first ───────────────
  const exRes = await execCommand(token, 'mfg_operation_exception_pcba_execution:report',
    { mfg_oe_work_order_op_id: opPid, mfg_oe_type: 'equipment', mfg_oe_severity: 'high', mfg_oe_description: `action golden ${uid('EX')}`, mfg_oe_downtime_min: 0 }, undefined, 'create', { allowError: true });
  const exPid = exRes.recordId || db("select pid from mt_mfg_operation_exception_pcba_execution where mfg_oe_status='open' order by created_at desc limit 1");
  check('resolve: seeded an open exception', !!exPid && db(`select mfg_oe_status from mt_mfg_operation_exception_pcba_execution where pid='${exPid}'`) !== 'resolved', `ex=${exPid}`);
  await p.goto(`${BASE}/p/c/mfg_andon_workbench`, { waitUntil: 'load', timeout: 25000 }).catch(() => {});
  await p.waitForTimeout(3000);
  const f2 = await openForm(p, /^解决异常$/);
  await p.waitForTimeout(800);
  await p.screenshot({ path: `${OUT}/act-resolve-form.png`, fullPage: true });
  check('resolve: 解决异常 opens a form', f2);
  await F(p, 'mfg_oe_resolution').fill('已更换保险丝并复位设备');
  await F(p, 'mfg_oe_downtime_min').fill('12');
  await p.waitForTimeout(300);
  await p.locator('[data-testid="form-dialog-submit"]').click();
  await p.waitForTimeout(2500);
  await p.screenshot({ path: `${OUT}/act-resolve-after.png`, fullPage: true });
  const exStatus = db(`select mfg_oe_status from mt_mfg_operation_exception_pcba_execution where pid='${exPid}'`);
  check('resolve: exception → resolved (state change)', exStatus === 'resolved', `status=${exStatus}`);

  // ── 3. place_hold (下达 Hold) ────────────────────────────────────────────────
  await p.goto(`${BASE}/p/c/mfg_hold_workbench`, { waitUntil: 'load', timeout: 25000 }).catch(() => {});
  await p.waitForTimeout(3000);
  const holdBefore = Number(db("select count(*) from mt_mfg_hold where mfg_hold_status='active'"));
  const f3 = await openForm(p, /^下达 Hold$/);
  await p.waitForTimeout(800);
  await p.screenshot({ path: `${OUT}/act-place-hold-form.png`, fullPage: true });
  check('place_hold: 下达 Hold opens a form', f3);
  await F(p, 'mfg_hold_target_type').selectOption('operation');
  await F(p, 'mfg_hold_target_pid').fill(opPid);
  await F(p, 'mfg_hold_scope').selectOption('full');
  await F(p, 'mfg_hold_reason').fill('来料批次待判定');
  await F(p, 'mfg_hold_responsible').fill('Alice');
  await p.waitForTimeout(300);
  await p.locator('[data-testid="form-dialog-submit"]').click();
  await p.waitForTimeout(2500);
  await p.screenshot({ path: `${OUT}/act-place-hold-after.png`, fullPage: true });
  const holdAfter = Number(db("select count(*) from mt_mfg_hold where mfg_hold_status='active'"));
  check('place_hold: a new active hold was created (state change)', holdAfter === holdBefore + 1, `active holds ${holdBefore}→${holdAfter}`);

  // ── 4. release_hold (解除) — release the hold we just placed ──────────────────
  const heldPid = db(`select pid from mt_mfg_hold where mfg_hold_status='active' and mfg_hold_target_pid='${opPid}' order by created_at desc limit 1`);
  await p.reload({ waitUntil: 'load' }).catch(() => {});
  await p.waitForTimeout(3000);
  const f4 = await openForm(p, /^解除$/);
  await p.waitForTimeout(800);
  await p.screenshot({ path: `${OUT}/act-release-hold-form.png`, fullPage: true });
  check('release_hold: 解除 opens a form', f4);
  await F(p, 'mfg_hold_release_note').fill('判定合格,解除 Hold');
  await p.waitForTimeout(300);
  await p.locator('[data-testid="form-dialog-submit"]').click();
  await p.waitForTimeout(2500);
  await p.screenshot({ path: `${OUT}/act-release-hold-after.png`, fullPage: true });
  const relStatus = db(`select mfg_hold_status from mt_mfg_hold where pid='${heldPid}'`);
  check('release_hold: hold → released (state change)', relStatus === 'released', `status=${relStatus}`);
} catch (e) { check('no exception', false, String(e.message).slice(0, 160)); await p.screenshot({ path: `${OUT}/act-err.png` }).catch(() => {}); }
await b.close();
const pass = results.filter((r) => r.pass).length;
console.log(`\n=== MES ACTION-POINTS GOLDEN: ${pass}/${results.length} pass ===`);
process.exit(pass < results.length ? 1 : 0);
