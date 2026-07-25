// MES deep-spec-gap golden — the FR-08/12/14 sub-features that the first golden honestly marked
// DEFER, now implemented + verified through the real command pipeline + psql DB round-trip:
//   FR-08: an already-EXPIRED material lot must be BLOCKED on bind (batch-expiry).
//   FR-12: a component serial can be built into exactly ONE finished unit (SN uniqueness).
//   FR-14: a passing RETEST verifies the original defect (fail→defect→rework→retest closed loop).
//   BACKEND_URL=http://127.0.0.1:6463 PG_DB=auraboot_63 node fr08-12-14-deep-golden.mjs
import { login, execCommand, makeReporter, uid, queryDb, scalar } from './harness.mjs';
const R = makeReporter();
const sq = (s) => String(s).replace(/'/g, "''");
const errText = (r) => {
  const ctx = (r.raw && r.raw.context) || {};
  return String(r.detail || ctx.error || ctx.messageKey || r.message || (r.raw && JSON.stringify(r.raw)) || '');
};

const token = await login();

// Shared seed: material + BOM + line + work order + op (the faithful shop-floor context).
async function seedWorkOrder() {
  const code = uid('DG');
  const mat = await execCommand(token, 'prod:create_product', { prod_name: `Mat ${code}`, prod_type: 'raw_material', prod_unit: 'pcs' }, undefined, 'create', { allowError: true });
  const bom = await execCommand(token, 'eng_bom_pcba_mbom:create', { eng_bom_name: `BOM ${code}`, eng_bom_product_id: mat.recordId, eng_bom_version: 'A', eng_bom_output_qty: 1 }, undefined, 'create', { allowError: true });
  await execCommand(token, 'eng_bom_line_pcba_mbom:create', { eng_bom_line_bom_id: bom.recordId, eng_bom_line_material_id: mat.recordId, eng_bom_line_qty: 10, eng_bom_line_unit: 'pcs' }, undefined, 'create', { allowError: true });
  const wo = await execCommand(token, 'mfg_work_order_pcba_execution:create', { mfg_wo_name: `WO ${code}`, mfg_wo_product_id: mat.recordId, mfg_wo_bom_id: bom.recordId, mfg_wo_plan_qty: 50 }, undefined, 'create', { allowError: true });
  const op = await execCommand(token, 'mfg_work_order_operation_pcba_execution:create', { mfg_wop_work_order_id: wo.recordId, mfg_wop_seq: 10, mfg_wop_name: `SMT ${code}`, mfg_wop_planned_qty: 50, mfg_wop_operator: 'Alice' }, undefined, 'create', { allowError: true });
  return { code, matId: mat.recordId, woId: wo.recordId, opId: op.recordId };
}

// ── FR-08: batch/near-expiry blocking ────────────────────────────────────────
console.log('\n[FR-08] expired material lot must be blocked on bind');
{
  const s = await seedWorkOrder();
  const expiredCode = uid('LOT-EXP');
  await execCommand(token, 'inv:create_lot', { inv_lot_code: expiredCode, inv_lot_type: 'batch', inv_lot_product_id: s.matId, inv_lot_expiry_date: '2020-01-01' }, undefined, 'create', { allowError: true });
  const expBind = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: s.woId, scanned_material_id: s.matId, scanned_lot_no: expiredCode, qty_consumed: 5, work_order_op_id: s.opId }, undefined, 'action', { allowError: true });
  R.check('FR-08', 'EXPIRED lot bind is BLOCKED (command fails)', !expBind.ok, `ok=${expBind.ok} status=${expBind.status}`);
  R.check('FR-08', 'block reason names expiry', /expire|FR-08 batch-expiry/i.test(errText(expBind)), errText(expBind));

  // a non-expired lot for the SAME material must still bind fine (proves it is not blocking everything)
  const goodCode = uid('LOT-OK');
  await execCommand(token, 'inv:create_lot', { inv_lot_code: goodCode, inv_lot_type: 'batch', inv_lot_product_id: s.matId, inv_lot_expiry_date: '2099-12-31' }, undefined, 'create', { allowError: true });
  const okBind = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: s.woId, scanned_material_id: s.matId, scanned_lot_no: goodCode, qty_consumed: 5, work_order_op_id: s.opId }, undefined, 'action', { allowError: true });
  R.check('FR-08', 'non-expired lot binds fine (selective block, not blanket)', okBind.ok, `ok=${okBind.ok}`);
}

// ── FR-12: component serial uniqueness ───────────────────────────────────────
console.log('\n[FR-12] a component serial can be built into exactly one finished unit');
{
  const s = await seedWorkOrder();
  const compSn = uid('CSN');
  const finA = uid('FIN-A');
  const finB = uid('FIN-B');
  const bindA = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: s.woId, scanned_material_id: s.matId, scanned_serial_no: compSn, finished_sn: finA, qty_consumed: 1, work_order_op_id: s.opId }, undefined, 'action', { allowError: true });
  R.check('FR-12', 'component SN binds into finished unit A', bindA.ok, `ok=${bindA.ok} detail=${errText(bindA)}`);
  const genA = scalar(`select count(*) from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_component_sn='${sq(compSn)}' and mfg_sg_finished_sn='${sq(finA)}'`);
  R.check('FR-12', 'genealogy A←component row persisted', Number(genA) === 1, `rows=${genA}`);

  const bindB = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: s.woId, scanned_material_id: s.matId, scanned_serial_no: compSn, finished_sn: finB, qty_consumed: 1, work_order_op_id: s.opId }, undefined, 'action', { allowError: true });
  R.check('FR-12', 'SAME component SN into a DIFFERENT finished unit is BLOCKED', !bindB.ok, `ok=${bindB.ok}`);
  R.check('FR-12', 'block reason names serial uniqueness', /already built|FR-12 serial/i.test(errText(bindB)), errText(bindB));
  const genB = scalar(`select count(*) from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_component_sn='${sq(compSn)}' and mfg_sg_finished_sn='${sq(finB)}'`);
  R.check('FR-12', 'no genealogy row for the blocked B link', Number(genB) === 0, `rows=${genB}`);

  const bindAgain = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: s.woId, scanned_material_id: s.matId, scanned_serial_no: compSn, finished_sn: finA, qty_consumed: 1, work_order_op_id: s.opId }, undefined, 'action', { allowError: true });
  R.check('FR-12', 're-scan of the SAME (finished,component) pair is idempotent (not blocked)', bindAgain.ok, `ok=${bindAgain.ok}`);
}

// ── FR-14: retest → original-failure closed loop ─────────────────────────────
console.log('\n[FR-14] passing retest verifies the original defect (closed loop)');
{
  const code = uid('RT');
  const prod = await execCommand(token, 'prod:create_product', { prod_name: `TP ${code}`, prod_type: 'raw_material', prod_unit: 'pcs' }, undefined, 'create', { allowError: true });
  const tp = await execCommand(token, 'qc:create_test_program', { qc_tp_name: `ICT ${code}`, qc_tp_type: 'ict', qc_tp_product_id: prod.recordId, qc_tp_version: 'v1' }, undefined, 'create', { allowError: true });
  await execCommand(token, 'qc:activate_test_program', {}, tp.recordId, 'state_transition', { allowError: true });
  const sn = uid('SN');
  const failRes = await execCommand(token, 'qc:record_test_result',
    { qc_tr_program_id: tp.recordId, qc_tr_serial_number: sn, qc_tr_result: 'fail', qc_tr_operator: 'QA', qc_tr_raw_data: { failures: [{ type: 'solder_bridge', componentRef: 'R12' }] } },
    undefined, 'create', { allowError: true });
  R.check('FR-14', 'failing result recorded', failRes.ok, `ok=${failRes.ok} detail=${errText(failRes)}`);
  const defect = queryDb(`select pid from mt_qc_test_defect where qc_td_test_result_id='${sq(failRes.recordId)}' limit 1`);
  const defectId = defect[0] && defect[0][0];
  R.check('FR-14', 'defect auto-created from the failure', !!defectId, `defect=${defectId}`);

  // record a PASSING retest that references the defect → the loop must verify the defect
  const retest = await execCommand(token, 'qc:record_test_result',
    { qc_tr_program_id: tp.recordId, qc_tr_serial_number: sn, qc_tr_result: 'pass', qc_tr_operator: 'QA', qc_tr_retest_of_defect_id: defectId, qc_tr_raw_data: { failures: [] } },
    undefined, 'create', { allowError: true });
  R.check('FR-14', 'passing retest (referencing the defect) recorded', retest.ok, `ok=${retest.ok} detail=${errText(retest)}`);
  const defStatus = scalar(`select qc_td_rework_status from mt_qc_test_defect where pid='${sq(defectId)}'`);
  R.check('FR-14', 'defect → VERIFIED by the passing retest (loop closed)', defStatus === 'verified', `rework_status=${defStatus}`);
  // full chain resolves: retest → its retest_of_defect_id → defect → defect.test_result_id → original fail
  const chain = queryDb(`select r.pid, r.qc_tr_retest_of_defect_id, d.qc_td_test_result_id from mt_qc_test_result r join mt_qc_test_defect d on d.pid=r.qc_tr_retest_of_defect_id where r.pid='${sq(retest.recordId)}'`);
  R.check('FR-14', 'closed-loop chain resolves retest→defect→original failure', chain[0] && chain[0][2] === failRes.recordId, `chain=${JSON.stringify(chain[0])}`);
}

R.summary('MES DEEP-GAP GOLDEN (FR-08 expiry / FR-12 SN-unique / FR-14 retest loop)');
