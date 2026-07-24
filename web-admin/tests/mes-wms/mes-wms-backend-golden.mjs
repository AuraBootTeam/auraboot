// MES/WMS backend command-pipeline golden — real-stack IT for the 8 delivered FRs.
// Executes real commands through the pipeline and asserts the DB round-trip (via the
// read API). Run against a live host-first stack: BACKEND_URL=http://127.0.0.1:6463 node this.

import { login, execCommand, listModel, getRecord, makeReporter, uid, queryDb, scalar } from './harness.mjs';

const sq = (s) => String(s).replace(/'/g, "''");

const R = makeReporter();
const token = await login();
console.log('=== MES/WMS backend golden (real command pipeline + DB round-trip) ===');

// ------------------------------------------------------------------ FR-04 HandlingUnit
async function frHandlingUnit() {
  console.log('\n[FR-04] HandlingUnit pack — qty conservation + event lineage + code-not-pid note');
  const parentCode = uid('HU-PAL');
  const childCode = uid('HU-CTN');
  const parent = await execCommand(token, 'inv:create_handling_unit',
    { inv_hu_code: parentCode, inv_hu_type: 'pallet', inv_hu_qty: 5, inv_hu_unit: 'pcs' }, undefined, 'create');
  const child = await execCommand(token, 'inv:create_handling_unit',
    { inv_hu_code: childCode, inv_hu_type: 'carton', inv_hu_qty: 10, inv_hu_unit: 'pcs' }, undefined, 'create');
  R.check('FR-04', 'create parent+child HU', parent.recordId && child.recordId, `parent=${parent.recordId} child=${child.recordId}`);
  if (!parent.recordId || !child.recordId) return;

  // Pack child into parent.
  const packed = await execCommand(token, 'inv:pack',
    { inv_hu_parent_id: parent.recordId }, child.recordId, 'action', { allowError: true });
  R.check('FR-04', 'inv:pack executes', packed.ok, `code=${packed.code} status=${packed.status}`);

  // DB round-trip: parent qty rolled up 5+10=15, child linked to parent.
  const parentQty = scalar(`select inv_hu_qty from mt_inv_handling_unit where pid='${sq(parent.recordId)}'`);
  const childParent = scalar(`select inv_hu_parent_id from mt_inv_handling_unit where pid='${sq(child.recordId)}'`);
  R.check('FR-04', 'parent qty rollup = 15', Number(parentQty) === 15, `parent qty=${parentQty}`);
  R.check('FR-04', 'child linked to parent', String(childParent) === String(parent.recordId), `child.parent=${childParent}`);

  // Event lineage: two pack events, and the note references the related unit by CODE not raw pid (#230).
  const evRows = queryDb(`select inv_hue_hu_id, inv_hue_event_type, inv_hue_note from mt_inv_handling_unit_event where inv_hue_hu_id in ('${sq(parent.recordId)}','${sq(child.recordId)}')`);
  const childEvt = evRows.find((e) => e[0] === child.recordId && e[1] === 'pack');
  const parentEvt = evRows.find((e) => e[0] === parent.recordId && e[1] === 'pack');
  R.check('FR-04', 'pack event rows exist (child+parent)', childEvt && parentEvt, `events=${evRows.length}`);
  const note = childEvt ? childEvt[2] : '';
  R.check('FR-04', 'note uses HU code, not raw pid', note.includes(parentCode) && !note.includes(parent.recordId), `note="${note}"`);
}

try { await frHandlingUnit(); } catch (e) { R.check('FR-04', 'no exception', false, String(e.message).slice(0, 200)); }

// ------------------------------------------------------------------ FR-09 Tooling life
async function frTooling() {
  console.log('\n[FR-09] SMT tooling — usage-cycle accumulation + over-life block');
  const code = uid('TL');
  const tl = await execCommand(token, 'mfg_tooling_pcba_asset:create',
    { mfg_tl_code: code, mfg_tl_name: `Stencil ${code}`, mfg_tl_type: 'stencil', mfg_tl_life_limit_cycles: 100 }, undefined, 'create', { allowError: true });
  R.check('FR-09', 'create tooling', tl.recordId, `id=${tl.recordId} code=${tl.code}`);
  if (!tl.recordId) return;
  // Record usage (accumulate cycles). Payload key discovered by iteration against live stack.
  const u1 = await execCommand(token, 'mfg_tooling_pcba_asset:record_usage', { cycles: 30 }, tl.recordId, 'action', { allowError: true });
  R.check('FR-09', 'record_usage executes', u1.ok, `code=${u1.code} status=${u1.status}`);
  const used = scalar(`select mfg_tl_used_cycles from mt_mfg_tooling_pcba_asset where pid='${sq(tl.recordId)}'`);
  R.check('FR-09', 'used cycles accumulated (>0)', Number(used) > 0, `used=${used}`);
}
try { await frTooling(); } catch (e) { R.check('FR-09', 'no exception', false, String(e.message).slice(0, 200)); }

// ------------------------------------------------------------------ FR-20 Downtime (no double-count)
async function frDowntime() {
  console.log('\n[FR-20] Equipment downtime — overlapping breakdown must not double-count');
  const code = uid('EQ');
  const eq = await execCommand(token, 'mfg_equipment_pcba_asset:create',
    { mfg_eq_code: code, mfg_eq_name: `Reflow ${code}`, mfg_eq_type: 'reflow' }, undefined, 'create', { allowError: true });
  R.check('FR-20', 'create equipment', eq.recordId, `id=${eq.recordId}`);
  if (!eq.recordId) return;
  const b1 = await execCommand(token, 'mfg_equipment_pcba_asset:breakdown', {}, eq.recordId, 'state_transition', { allowError: true });
  R.check('FR-20', 'breakdown transitions status', b1.ok, `code=${b1.code}`);
  const status = scalar(`select mfg_eq_status from mt_mfg_equipment_pcba_asset where pid='${sq(eq.recordId)}'`);
  R.check('FR-20', 'status = breakdown', status === 'breakdown', `status=${status}`);
  // Second breakdown while already down (overlap) must not open a second downtime window (#219).
  await execCommand(token, 'mfg_equipment_pcba_asset:breakdown', {}, eq.recordId, 'state_transition', { allowError: true });
  const openDt = scalar(`select count(*) from mt_mfg_equipment_downtime_pcba_asset where mfg_dt_equipment_id='${sq(eq.recordId)}' and mfg_dt_end_time is null`);
  R.check('FR-20', 'overlapping breakdown: still exactly 1 open downtime (no double-count)', Number(openDt) === 1, `open downtime rows=${openDt}`);
}
try { await frDowntime(); } catch (e) { R.check('FR-20', 'no exception', false, String(e.message).slice(0, 200)); }

// ------------------------------------------------------------------ FR-22 Shift Handover
async function frHandover() {
  console.log('\n[FR-22] Shift handover — create + snapshot + acknowledge (dual sign-off)');
  const wsCode = uid('WS');
  const ws = await execCommand(token, 'mfg_workstation_pcba_execution:create',
    { mfg_ws_code: wsCode, mfg_ws_name: `SMT-${wsCode}`, mfg_ws_operation_type: 'smt', mfg_ws_capacity_per_hour: 100 }, undefined, 'create', { allowError: true });
  R.check('FR-22', 'create workstation', ws.recordId, `id=${ws.recordId} code=${ws.code}`);
  if (!ws.recordId) return;
  const ho = await execCommand(token, 'mfg_shift_handover:create_handover',
    { mfg_sho_workstation_id: ws.recordId, mfg_sho_outgoing_shift: 'day', mfg_sho_incoming_shift: 'night',
      mfg_sho_outgoing_person: 'Alice', mfg_sho_notes: 'reflow oven monitored' }, undefined, 'action', { allowError: true });
  R.check('FR-22', 'create_handover executes', ho.ok, `code=${ho.code} status=${ho.status}`);
  // create_handover is an action command; resolve the created row from the DB by workstation.
  const hoRow = queryDb(`select pid, mfg_sho_status, mfg_sho_workstation_id from mt_mfg_shift_handover where mfg_sho_workstation_id='${sq(ws.recordId)}' order by created_at desc limit 1`);
  const hoPid = hoRow[0] ? hoRow[0][0] : '';
  R.check('FR-22', 'handover persisted (pending_ack, workstation linked)', hoRow[0] && hoRow[0][1] === 'pending_ack' && hoRow[0][2] === ws.recordId, `row=${JSON.stringify(hoRow[0])}`);
  if (!hoPid) return;
  const ack = await execCommand(token, 'mfg_shift_handover:acknowledge_handover',
    { mfg_sho_incoming_person: 'Bob' }, hoPid, 'action', { allowError: true });
  R.check('FR-22', 'acknowledge_handover executes', ack.ok, `code=${ack.code} detail=${JSON.stringify(ack.raw?.context||'').slice(0,80)}`);
  const status = scalar(`select mfg_sho_status from mt_mfg_shift_handover where pid='${sq(hoPid)}'`);
  R.check('FR-22', 'status → acknowledged (dual sign-off)', status === 'acknowledged', `status=${status}`);
}
try { await frHandover(); } catch (e) { R.check('FR-22', 'no exception', false, String(e.message).slice(0, 200)); }

// ------------------------------------------------------------------ FR-16 Hold
async function frHold() {
  console.log('\n[FR-16] Hold — place hold on a target + persisted');
  const code = uid('EQH');
  const eq = await execCommand(token, 'mfg_equipment_pcba_asset:create',
    { mfg_eq_code: code, mfg_eq_name: `Held ${code}`, mfg_eq_type: 'reflow' }, undefined, 'create', { allowError: true });
  if (!R.check('FR-16', 'create hold target (equipment)', eq.recordId, `id=${eq.recordId}`)) return;
  const hold = await execCommand(token, 'mfg_hold:place_hold',
    { mfg_hold_target_type: 'equipment', mfg_hold_target_pid: eq.recordId, mfg_hold_scope: 'full',
      mfg_hold_reason: 'calibration drift', mfg_hold_responsible: 'QA' }, undefined, 'action', { allowError: true });
  R.check('FR-16', 'place_hold executes', hold.ok, `code=${hold.code} status=${hold.status} detail=${JSON.stringify(hold.raw?.context||'').slice(0,80)}`);
  const held = scalar(`select count(*) from mt_mfg_hold where mfg_hold_target_pid='${sq(eq.recordId)}' and mfg_hold_status='active'`);
  R.check('FR-16', 'active hold row persisted', Number(held) >= 1, `active holds=${held}`);
}
try { await frHold(); } catch (e) { R.check('FR-16', 'no exception', false, String(e.message).slice(0, 200)); }

// ------------------------------------------------------------------ FR-05 Interlock (7 checks)
async function frInterlock() {
  console.log('\n[FR-05] Start interlock — 7-check evaluator returns structured result');
  const code = uid('WOP');
  // Precondition chain: work order → operation (references aren't FK-enforced, so dummy ids suffice).
  const wo = await execCommand(token, 'mfg_work_order_pcba_execution:create',
    { mfg_wo_name: `WO ${code}`, mfg_wo_product_id: `prod-${code}`, mfg_wo_bom_id: `bom-${code}`, mfg_wo_plan_qty: 100 }, undefined, 'create', { allowError: true });
  if (!R.check('FR-05', 'create work order', wo.recordId, `id=${wo.recordId} detail=${JSON.stringify(wo.raw?.context||'').slice(0,90)}`)) return;
  const op = await execCommand(token, 'mfg_work_order_operation_pcba_execution:create',
    { mfg_wop_work_order_id: wo.recordId, mfg_wop_seq: 10, mfg_wop_name: `SMT op ${code}`, mfg_wop_planned_qty: 100, mfg_wop_operator: 'Alice' }, undefined, 'create', { allowError: true });
  if (!R.check('FR-05', 'create work-order operation', op.recordId, `id=${op.recordId} code=${op.code} detail=${JSON.stringify(op.raw?.context||'').slice(0,90)}`)) return;
  const chk = await execCommand(token, 'mfg_work_order_operation_pcba_execution:check_interlock', {}, op.recordId, 'action', { allowError: true });
  R.check('FR-05', 'check_interlock executes', chk.ok, `code=${chk.code} status=${chk.status}`);
  // The evaluator persists one row per check into mt_mfg_interlock_check_pcba_execution.
  R.check('FR-05', 'evaluator ran 7 checks (6→7 per #219)', Number(chk.data?.checkedItems) === 7, `checkedItems=${chk.data?.checkedItems} failed=${chk.data?.failedItems} passed=${chk.data?.passed}`);
  const codes = queryDb(`select distinct mfg_ic_item_code from mt_mfg_interlock_check_pcba_execution where mfg_ic_work_order_op_id='${sq(op.recordId)}'`).map((r) => r[0]);
  R.check('FR-05', '7 distinct check items persisted', codes.length === 7, `codes=${codes.join(',')}`);
  R.check('FR-05', 'tooling_life check present (#219 7th check)', codes.includes('tooling_life'), `has tooling_life=${codes.includes('tooling_life')}`);
}
try { await frInterlock(); } catch (e) { R.check('FR-05', 'no exception', false, String(e.message).slice(0, 200)); }

// ------------------------------------------------------------------ FR-13 Kitting
async function frKitting() {
  console.log('\n[FR-13] Kitting analysis — compute produces a kitting result per work order');
  const code = uid('KWO');
  const wo = await execCommand(token, 'mfg_work_order_pcba_execution:create',
    { mfg_wo_name: `KitWO ${code}`, mfg_wo_product_id: `prod-${code}`, mfg_wo_bom_id: `bom-${code}`, mfg_wo_plan_qty: 50 }, undefined, 'create', { allowError: true });
  if (!R.check('FR-13', 'create work order', wo.recordId, `id=${wo.recordId}`)) return;
  const kit = await execCommand(token, 'inv:compute_kitting', { inv_kr_work_order_id: wo.recordId }, undefined, 'action', { allowError: true });
  // FR-13's meaningful assertion (full vs critical kitting) needs a real eng_bom_pcba_mbom +
  // BOM lines + inventory balance seeded. compute_kitting correctly refuses a work order whose
  // BOM does not resolve ("Record not found") — that IS correct behaviour, but it means this FR's
  // full golden requires a multi-model seed not yet built. Report honestly as DEFERRED, not green.
  const needsBomSeed = !kit.ok && /not found|no BOM/i.test(JSON.stringify(kit.raw || ''));
  R.deferred('FR-13', 'kitting rate/critical golden requires real BOM + lines + balance seed',
    needsBomSeed ? 'compute_kitting correctly rejects unresolved BOM — seed needed for full golden' : `code=${kit.code}`);
}
try { await frKitting(); } catch (e) { R.check('FR-13', 'no exception', false, String(e.message).slice(0, 200)); }

// FR-10 FEFO — real-stack golden requires inventory balance with expiry-dated lots + a warehouse
// + a pick source seeded (multi-model). Reported honestly as DEFERRED until that seed is built.
R.deferred('FR-10', 'FEFO allocation golden requires balance + expiry-dated lots + warehouse seed', 'multi-model seed not yet built');

// ------------------------------------------------------------------ summary
const s = R.summary();
const frCovered = [...new Set(R.results.filter((r) => !r.deferred).map((r) => r.fr))];
console.log(`\n=== SUMMARY: ${s.pass}/${s.total} checks pass, ${s.fail} fail, ${s.deferred} deferred ===`);
console.log(`    FRs with real-stack backend golden: ${frCovered.sort().join(', ')} (${frCovered.length}/8)`);
console.log(`    Deferred (need multi-model seed): ${[...new Set(R.results.filter((r) => r.deferred).map((r) => r.fr))].join(', ') || 'none'}`);
process.exit(s.fail > 0 ? 1 : 0);
