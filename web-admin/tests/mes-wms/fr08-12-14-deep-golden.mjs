// MES deep-spec-gap golden — the FR-08/12/14 sub-features that the first golden honestly marked
// DEFER, now implemented + verified through the real command pipeline + psql DB round-trip:
//   FR-08: an already-EXPIRED material lot must be BLOCKED on bind (batch-expiry).
//          A still-valid lot that expires inside the work-order production window is also blocked.
//   FR-12: SN uniqueness + relabel/invalidate/evidence-backed identity recovery version chain.
//   FR-14: retest→original-failure loop + component replacement As-built predecessor/lot chain.
//   BACKEND_URL=http://127.0.0.1:6463 PG_DB=auraboot_63 node fr08-12-14-deep-golden.mjs
import {
  login, execCommand, makeReporter, uid, queryDb, scalar, seedExecutionBaseline,
} from './harness.mjs';
const R = makeReporter();
const sq = (s) => String(s).replace(/'/g, "''");
const errText = (r) => {
  const ctx = (r.raw && r.raw.context) || {};
  return String(r.detail || ctx.error || ctx.messageKey || r.message || (r.raw && JSON.stringify(r.raw)) || '');
};

const token = await login();
const dstr = (days) => new Date(Date.now() + days * 86400000).toISOString().slice(0, 10);

// Shared seed: material + BOM + line + work order + op (the faithful shop-floor context).
async function seedWorkOrder() {
  const code = uid('DG');
  const mat = await execCommand(token, 'prod:create_product', { prod_name: `Mat ${code}`, prod_type: 'raw_material', prod_unit: 'pcs' }, undefined, 'create', { allowError: true });
  const bom = await execCommand(token, 'eng_bom_pcba_mbom:create', { eng_bom_name: `BOM ${code}`, eng_bom_product_id: mat.recordId, eng_bom_version: 'A', eng_bom_output_qty: 1 }, undefined, 'create', { allowError: true });
  await execCommand(token, 'eng_bom_line_pcba_mbom:create', { eng_bom_line_bom_id: bom.recordId, eng_bom_line_material_id: mat.recordId, eng_bom_line_qty: 10, eng_bom_line_unit: 'pcs' }, undefined, 'create', { allowError: true });
  const wo = await execCommand(token, 'mfg_work_order_pcba_execution:create', {
    mfg_wo_name: `WO ${code}`,
    mfg_wo_product_id: mat.recordId,
    mfg_wo_bom_id: bom.recordId,
    mfg_wo_plan_qty: 50,
    mfg_wo_plan_start: dstr(0),
    mfg_wo_plan_end: dstr(30),
  }, undefined, 'create', { allowError: true });
  seedExecutionBaseline({
    workOrderId: wo.recordId,
    bomId: bom.recordId,
    materialId: mat.recordId,
    quantity: 10,
  });
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

  // Still valid today, but expires before the work order's +30d planned end.
  const nearCode = uid('LOT-NEAR');
  await execCommand(token, 'inv:create_lot', {
    inv_lot_code: nearCode,
    inv_lot_type: 'batch',
    inv_lot_product_id: s.matId,
    inv_lot_expiry_date: dstr(5),
  }, undefined, 'create', { allowError: true });
  const nearBind = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: s.woId, scanned_material_id: s.matId, scanned_lot_no: nearCode, qty_consumed: 5, work_order_op_id: s.opId },
    undefined, 'action', { allowError: true });
  R.check('FR-08', 'still-valid lot expiring inside remaining production window is BLOCKED',
    !nearBind.ok, `ok=${nearBind.ok} status=${nearBind.status}`);
  R.check('FR-08', 'near-expiry reason names the remaining production window',
    /remaining production window|FR-08 near-expiry/i.test(errText(nearBind)), errText(nearBind));
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
  const activeId = scalar(`select pid from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_component_sn='${sq(compSn)}' and mfg_sg_finished_sn='${sq(finA)}' and coalesce(mfg_sg_status,'active')='active' limit 1`);
  R.check('FR-12', 'initial identity version is active', !!activeId, `id=${activeId}`);

  const bindB = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: s.woId, scanned_material_id: s.matId, scanned_serial_no: compSn, finished_sn: finB, qty_consumed: 1, work_order_op_id: s.opId }, undefined, 'action', { allowError: true });
  R.check('FR-12', 'SAME component SN into a DIFFERENT finished unit is BLOCKED', !bindB.ok, `ok=${bindB.ok}`);
  R.check('FR-12', 'block reason names serial uniqueness', /already built|FR-12 serial/i.test(errText(bindB)), errText(bindB));
  const genB = scalar(`select count(*) from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_component_sn='${sq(compSn)}' and mfg_sg_finished_sn='${sq(finB)}'`);
  R.check('FR-12', 'no genealogy row for the blocked B link', Number(genB) === 0, `rows=${genB}`);

  const bindAgain = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: s.woId, scanned_material_id: s.matId, scanned_serial_no: compSn, finished_sn: finA, qty_consumed: 1, work_order_op_id: s.opId }, undefined, 'action', { allowError: true });
  R.check('FR-12', 're-scan of the SAME (finished,component) pair is idempotent (not blocked)', bindAgain.ok, `ok=${bindAgain.ok}`);
  const afterRescan = scalar(`select count(*) from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_component_sn='${sq(compSn)}'`);
  R.check('FR-12', 'idempotent re-scan did not append a duplicate genealogy row', Number(afterRescan) === 1, `rows=${afterRescan}`);

  const relabelSn = uid('CSN-R');
  const relabel = await execCommand(token, 'mfg_sn_genealogy_pcba_execution:relabel',
    { mfg_sg_component_sn: relabelSn, mfg_sg_reason: 'physical label damaged' },
    activeId, 'action', { allowError: true });
  R.check('FR-12', 'relabel command executes through the real handler', relabel.ok,
    `ok=${relabel.ok} detail=${errText(relabel)}`);
  const relabelRows = queryDb(`select pid,mfg_sg_component_sn,mfg_sg_status,mfg_sg_event_type,mfg_sg_predecessor_id,mfg_sg_identity_root_id from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_finished_sn='${sq(finA)}' order by created_at`);
  const relabelOld = relabelRows.find((r) => r[0] === activeId);
  const relabelNew = relabelRows.find((r) => r[1] === relabelSn);
  R.check('FR-12', 'relabel makes old label terminal and creates one active successor',
    relabelOld?.[2] === 'relabelled' && relabelNew?.[2] === 'active' && relabelNew?.[3] === 'relabel',
    `old=${JSON.stringify(relabelOld)} new=${JSON.stringify(relabelNew)}`);
  R.check('FR-12', 'relabel preserves identity chain via predecessor + root',
    relabelNew?.[4] === activeId && relabelNew?.[5] === activeId,
    `predecessor=${relabelNew?.[4]} root=${relabelNew?.[5]}`);

  const oldLabelReuse = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: s.woId, scanned_material_id: s.matId, scanned_serial_no: compSn, finished_sn: finA, qty_consumed: 1, work_order_op_id: s.opId },
    undefined, 'action', { allowError: true });
  R.check('FR-12', 'old relabelled serial is immediately rejected on scan', !oldLabelReuse.ok,
    `ok=${oldLabelReuse.ok} detail=${errText(oldLabelReuse)}`);

  const reprintSn = uid('CSN-PRINT');
  const reprint = await execCommand(token, 'mfg_sn_genealogy_pcba_execution:reprint',
    { mfg_sg_component_sn: reprintSn, mfg_sg_reason: 'thermal print unreadable' },
    relabelNew?.[0], 'action', { allowError: true });
  R.check('FR-12', 'reprint command invalidates the printed label and issues a new version',
    reprint.ok, `ok=${reprint.ok} detail=${errText(reprint)}`);
  const reprinted = queryDb(`select pid,mfg_sg_status,mfg_sg_event_type,mfg_sg_predecessor_id,mfg_sg_identity_root_id from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_component_sn='${sq(reprintSn)}' limit 1`)[0];
  R.check('FR-12', 'reprint successor preserves predecessor + root and is the only active label',
    reprinted?.[1] === 'active' && reprinted?.[2] === 'reprint'
      && reprinted?.[3] === relabelNew?.[0] && reprinted?.[4] === activeId,
    `row=${JSON.stringify(reprinted)}`);

  const recoveredSn = uid('CSN-REC');
  const recover = await execCommand(token, 'mfg_sn_genealogy_pcba_execution:recover_identity',
    {
      mfg_sg_component_sn: recoveredSn,
      mfg_sg_reason: 'label unreadable',
      mfg_sg_evidence: 'carrier=C17;station=ICT-2;window=10m;adjacent=verified',
      mfg_sg_requested_by: 'line-operator-3',
      mfg_sg_confidence: 0.96,
    },
    reprinted?.[0], 'action', { allowError: true });
  R.check('FR-12', 'identity recovery command accepts evidence + second confirmer', recover.ok,
    `ok=${recover.ok} detail=${errText(recover)}`);
  const recovered = queryDb(`select pid,mfg_sg_status,mfg_sg_event_type,mfg_sg_predecessor_id,mfg_sg_identity_root_id,mfg_sg_evidence,mfg_sg_requested_by,mfg_sg_confirmed_by,mfg_sg_confidence from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_component_sn='${sq(recoveredSn)}' limit 1`)[0];
  R.check('FR-12', 'recovered active version retains evidence, confirmer, confidence, and the same root',
    recovered?.[1] === 'active' && recovered?.[2] === 'identity_recovery'
      && recovered?.[3] === reprinted?.[0] && recovered?.[4] === activeId
      && /carrier=C17/.test(recovered?.[5] || '') && recovered?.[6] === 'line-operator-3'
      && Boolean(recovered?.[7]) && recovered?.[7] !== recovered?.[6]
      && Number(recovered?.[8]) === 0.96,
    `row=${JSON.stringify(recovered)}`);

  const selfConfirmedSn = uid('CSN-SELF');
  const selfConfirmed = await execCommand(token, 'mfg_sn_genealogy_pcba_execution:recover_identity',
    {
      mfg_sg_component_sn: selfConfirmedSn,
      mfg_sg_reason: 'self confirmation must fail',
      mfg_sg_evidence: 'carrier=C17;station=ICT-2',
      mfg_sg_requested_by: recovered?.[7],
      mfg_sg_confidence: 0.96,
    },
    recovered?.[0], 'action', { allowError: true });
  R.check('FR-12', 'identity recovery rejects requester=self authenticated confirmer',
    !selfConfirmed.ok && /independent second confirmer/i.test(errText(selfConfirmed)),
    `ok=${selfConfirmed.ok} detail=${errText(selfConfirmed)}`);
  const selfConfirmedRows = Number(queryDb(`select count(*) from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_component_sn='${sq(selfConfirmedSn)}'`)[0]?.[0] || 0);
  R.check('FR-12', 'rejected self-confirmation writes no identity successor',
    selfConfirmedRows === 0, `rows=${selfConfirmedRows}`);

  const invalidate = await execCommand(token, 'mfg_sn_genealogy_pcba_execution:invalidate',
    { mfg_sg_reason: 'duplicate physical label confirmed' },
    recovered?.[0], 'action', { allowError: true });
  R.check('FR-12', 'invalidate command executes', invalidate.ok, `ok=${invalidate.ok} detail=${errText(invalidate)}`);
  const invalidated = queryDb(`select mfg_sg_status,mfg_sg_reason,mfg_sg_changed_at from mt_mfg_sn_genealogy_pcba_execution where pid='${sq(recovered?.[0])}'`)[0];
  R.check('FR-12', 'invalidated label is terminal with reason + timestamp',
    invalidated?.[0] === 'invalidated' && invalidated?.[1] === 'duplicate physical label confirmed' && !!invalidated?.[2],
    `row=${JSON.stringify(invalidated)}`);
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

  // Repair As-built: original component version remains, replacement points back to it, and the
  // replacement lot enters genealogy. This is separate from the test-result chain but shares the
  // same physical repair journey.
  const s = await seedWorkOrder();
  const originalSn = uid('COMP-ORIG');
  const finishedSn = uid('FIN-REPAIR');
  const install = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: s.woId, scanned_material_id: s.matId, scanned_serial_no: originalSn, finished_sn: finishedSn, qty_consumed: 1, work_order_op_id: s.opId },
    undefined, 'action', { allowError: true });
  R.check('FR-14', 'original As-built component installed before repair', install.ok,
    `ok=${install.ok} detail=${errText(install)}`);
  const originalId = scalar(`select pid from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_component_sn='${sq(originalSn)}' and coalesce(mfg_sg_status,'active')='active' limit 1`);

  const replacementMat = await execCommand(token, 'prod:create_product',
    { prod_name: `Replacement ${s.code}`, prod_type: 'raw_material', prod_unit: 'pcs' },
    undefined, 'create', { allowError: true });
  const replacementLotCode = uid('LOT-REPL');
  const replacementLot = await execCommand(token, 'inv:create_lot',
    { inv_lot_code: replacementLotCode, inv_lot_type: 'batch', inv_lot_product_id: replacementMat.recordId, inv_lot_expiry_date: '2099-12-31' },
    undefined, 'create', { allowError: true });
  const replacementSn = uid('COMP-REPL');
  const replace = await execCommand(token, 'mfg_sn_genealogy_pcba_execution:replace_component',
    {
      mfg_sg_component_sn: replacementSn,
      mfg_sg_component_material_id: replacementMat.recordId,
      mfg_sg_lot_id: replacementLot.recordId,
      mfg_sg_reason: 'FCT failure U17',
    },
    originalId, 'action', { allowError: true });
  R.check('FR-14', 'replace_component command executes through real handler', replace.ok,
    `ok=${replace.ok} detail=${errText(replace)}`);
  const originalAfter = queryDb(`select mfg_sg_status,mfg_sg_component_sn,mfg_sg_component_material_id from mt_mfg_sn_genealogy_pcba_execution where pid='${sq(originalId)}'`)[0];
  const replacementAfter = queryDb(`select mfg_sg_status,mfg_sg_event_type,mfg_sg_predecessor_id,mfg_sg_component_sn,mfg_sg_component_material_id,mfg_sg_lot_id,mfg_sg_reason from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_component_sn='${sq(replacementSn)}'`)[0];
  R.check('FR-14', 'original As-built row is retained and terminally marked replaced',
    originalAfter?.[0] === 'replaced' && originalAfter?.[1] === originalSn && originalAfter?.[2] === s.matId,
    `row=${JSON.stringify(originalAfter)}`);
  R.check('FR-14', 'replacement As-built row names predecessor + new serial/material/lot/reason',
    replacementAfter?.[0] === 'active' && replacementAfter?.[1] === 'component_replacement'
      && replacementAfter?.[2] === originalId && replacementAfter?.[3] === replacementSn
      && replacementAfter?.[4] === replacementMat.recordId && replacementAfter?.[5] === replacementLot.recordId
      && replacementAfter?.[6] === 'FCT failure U17',
    `row=${JSON.stringify(replacementAfter)}`);
}

const summary = R.summary();
console.log(`\n=== MES DEEP-GAP GOLDEN: ${summary.pass}/${summary.total} pass, ${summary.fail} fail ===`);
process.exit(summary.fail > 0 ? 1 : 0);
