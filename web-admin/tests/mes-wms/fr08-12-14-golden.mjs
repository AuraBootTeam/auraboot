// MES/WMS backend command-pipeline golden — FR-08 / FR-12 / FR-14 (real-stack IT).
//
// Runs real commands through the pipeline (POST /api/meta/commands/execute/{code})
// and asserts the DB round-trip with psql (read API has scope filtering, so DB is the
// authoritative check). Reuses the FR-13 seed pattern from mes-wms-backend-golden.mjs.
//
//   BACKEND_URL=http://127.0.0.1:6463 PG_DB=auraboot_63 PG_HOST=127.0.0.1 PG_PORT=5432 \
//   PG_USER=auraboot PGPASSWORD=auraboot ADMIN_EMAIL=admin@auraboot.com ADMIN_PASSWORD=Test2026x \
//   node fr08-12-14-golden.mjs
//
// Command codes + payload field names + physical table/column names were all verified
// against the command JSONs, the handler sources, and the live DB information_schema
// (no guessing — §5/§15):
//   • mfg_work_order_pcba_execution:validate_material_binding (type:custom, handler self-persists)
//       — plugins/pcba-manufacturing .../handler/ValidateMaterialBindingHandler.java
//       — payload: work_order_id, scanned_material_id, scanned_lot_no, scanned_serial_no,
//                  qty_consumed, work_order_op_id, finished_sn
//       — writes mt_mfg_material_consumption_pcba_execution (FR-08 consumption) and
//               mt_mfg_sn_genealogy_pcba_execution (FR-12 genealogy)
//   • qc:create_test_program / qc:activate_test_program / qc:record_test_result /
//     qc:create_rework_order (plugins/quality) — a failing test result auto-creates a
//     qc_test_defect (qc_td_test_result_id → the failed result); the rework order links to
//     that defect via qc_rw_source_type='test_defect' + qc_rw_source_id (FR-14 fail→rework).

import { login, execCommand, makeReporter, uid, queryDb, scalar } from './harness.mjs';

const sq = (s) => String(s).replace(/'/g, "''");
const errText = (r) =>
  JSON.stringify(r?.raw?.context?.detail ?? r?.raw?.context ?? r?.raw?.message ?? r?.raw ?? '').slice(0, 140);

const R = makeReporter();
const token = await login();
console.log('=== MES/WMS FR-08 / FR-12 / FR-14 backend golden (real command pipeline + DB round-trip) ===');

// ------------------------------------------------------------------ FR-08 + FR-12
// Material verification & consumption (FR-08) + SN genealogy (FR-12) via validate_material_binding.
async function frMaterialBindingAndGenealogy() {
  console.log('\n[FR-08/12] validate_material_binding — mis-material block + consumption + SN genealogy');
  const code = uid('MB');

  // --- Seed (reuse FR-13 pattern): product(material) + BOM + BOM line + WO(with BOM) + inv_lot ---
  const mat = await execCommand(token, 'prod:create_product',
    { prod_name: `Mat ${code}`, prod_type: 'raw_material', prod_unit: 'pcs' }, undefined, 'create', { allowError: true });
  if (!R.check('FR-08', 'seed BOM material (product)', mat.recordId, `mat=${mat.recordId} detail=${errText(mat)}`)) return;

  // A second, DIFFERENT product = the wrong material scanned at the station (NOT in this BOM).
  const wrong = await execCommand(token, 'prod:create_product',
    { prod_name: `WrongMat ${code}`, prod_type: 'raw_material', prod_unit: 'pcs' }, undefined, 'create', { allowError: true });
  if (!R.check('FR-08', 'seed wrong (non-BOM) material', wrong.recordId, `wrong=${wrong.recordId}`)) return;

  const bom = await execCommand(token, 'eng_bom_pcba_mbom:create',
    { eng_bom_name: `BOM ${code}`, eng_bom_product_id: mat.recordId, eng_bom_version: 'A', eng_bom_output_qty: 1 }, undefined, 'create', { allowError: true });
  if (!R.check('FR-08', 'seed BOM', bom.recordId, `bom=${bom.recordId} detail=${errText(bom)}`)) return;

  const line = await execCommand(token, 'eng_bom_line_pcba_mbom:create',
    { eng_bom_line_bom_id: bom.recordId, eng_bom_line_material_id: mat.recordId, eng_bom_line_qty: 10, eng_bom_line_unit: 'pcs' }, undefined, 'create', { allowError: true });
  if (!R.check('FR-08', 'seed BOM line (material IS in BOM)', line.recordId, `line=${line.recordId}`)) return;

  const wo = await execCommand(token, 'mfg_work_order_pcba_execution:create',
    { mfg_wo_name: `MB-WO ${code}`, mfg_wo_product_id: mat.recordId, mfg_wo_bom_id: bom.recordId, mfg_wo_plan_qty: 50 }, undefined, 'create', { allowError: true });
  if (!R.check('FR-08', 'create work order with BOM assigned (mfg_wo_bom_id set)', wo.recordId, `wo=${wo.recordId}`)) return;

  // Optional real work-order operation (unchecked by handler, but faithful to the shop-floor scan).
  const op = await execCommand(token, 'mfg_work_order_operation_pcba_execution:create',
    { mfg_wop_work_order_id: wo.recordId, mfg_wop_seq: 10, mfg_wop_name: `SMT ${code}`, mfg_wop_planned_qty: 50, mfg_wop_operator: 'Alice' }, undefined, 'create', { allowError: true });
  const opId = op.recordId || undefined;

  // inv_lot for the CORRECT material — handler matches on (inv_lot_code, inv_lot_product_id).
  const lotCode = uid('LOT');
  const lot = await execCommand(token, 'inv:create_lot',
    { inv_lot_code: lotCode, inv_lot_type: 'batch', inv_lot_product_id: mat.recordId }, undefined, 'create', { allowError: true });
  if (!R.check('FR-08', 'seed inv_lot for the material', lot.recordId, `lot=${lot.recordId} code=${lotCode}`)) return;

  // --- FR-08 错料阻断: scan a material NOT in the BOM → the command MUST fail (handler throws) ---
  const mis = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: wo.recordId, scanned_material_id: wrong.recordId, qty_consumed: 5 },
    undefined, 'action', { allowError: true });
  R.check('FR-08', 'mis-material scan is BLOCKED (command fails)',
    mis.ok === false, `ok=${mis.ok} status=${mis.status} code=${mis.code}`);
  // Falsifiable for the RIGHT reason: it must fail *because* the material is not in the BOM,
  // not because of some unrelated seed error.
  R.check('FR-08', 'block reason is a mis-bind ("not in BOM")',
    mis.ok === false && errText(mis).toLowerCase().includes('not in bom'), `err=${errText(mis)}`);

  // --- FR-08 consumption + FR-12 genealogy: CORRECT material + lot + serial + finished_sn ---
  const componentSn = uid('CSN');
  const finishedSn = uid('FSN');
  const good = await execCommand(token, 'mfg_work_order_pcba_execution:validate_material_binding',
    { work_order_id: wo.recordId, scanned_material_id: mat.recordId, scanned_lot_no: lotCode,
      scanned_serial_no: componentSn, qty_consumed: 5, finished_sn: finishedSn, work_order_op_id: opId },
    undefined, 'action', { allowError: true });
  R.check('FR-08', 'correct material scan passes', good.ok && good.data?.valid === true,
    `ok=${good.ok} valid=${good.data?.valid} consumption_recorded=${good.data?.consumption_recorded} genealogy_recorded=${good.data?.genealogy_recorded} detail=${errText(good)}`);

  // FR-08 consumption row: linked to WO + the actual inv_lot + material, qty preserved.
  const consRows = queryDb(`select pid, mfg_mc_lot_id, mfg_mc_material_id, mfg_mc_qty_consumed from mt_mfg_material_consumption_pcba_execution where mfg_mc_work_order_id='${sq(wo.recordId)}' and mfg_mc_material_id='${sq(mat.recordId)}'`);
  const cons = consRows[0];
  R.check('FR-08', 'consumption row exists linked to WO + correct lot + material',
    !!cons && cons[1] === lot.recordId && cons[2] === mat.recordId, `row=${JSON.stringify(cons)} expected lot=${lot.recordId}`);
  R.check('FR-08', 'consumed qty persisted (=5)', !!cons && Number(cons[3]) === 5, `qty=${cons?.[3]}`);

  // FR-12 genealogy row: finished_sn (parent) ← component sn + consumed material (child).
  const genRows = queryDb(`select pid, mfg_sg_component_sn, mfg_sg_component_material_id from mt_mfg_sn_genealogy_pcba_execution where mfg_sg_finished_sn='${sq(finishedSn)}'`);
  const gen = genRows[0];
  R.check('FR-12', 'genealogy row links finished_sn(parent) → component sn + material(child)',
    !!gen && gen[1] === componentSn && gen[2] === mat.recordId, `row=${JSON.stringify(gen)} expected componentSn=${componentSn} material=${mat.recordId}`);
}
try { await frMaterialBindingAndGenealogy(); } catch (e) { R.check('FR-08', 'no exception', false, String(e.message).slice(0, 200)); }

// ------------------------------------------------------------------ FR-14
// Test & rework linkage: test program → activate → failing test result (auto-creates a
// defect linked to the result) → rework order for that defect → assert the fail→rework link.
async function frTestReworkLinkage() {
  console.log('\n[FR-14] Test & rework linkage — fail result → auto defect → rework order');
  const code = uid('QC');

  const prod = await execCommand(token, 'prod:create_product',
    { prod_name: `TP-Mat ${code}`, prod_type: 'raw_material', prod_unit: 'pcs' }, undefined, 'create', { allowError: true });
  if (!R.check('FR-14', 'seed product', prod.recordId, `prod=${prod.recordId}`)) return;

  const tp = await execCommand(token, 'qc:create_test_program',
    { qc_tp_name: `FCT ${code}`, qc_tp_type: 'fct', qc_tp_product_id: prod.recordId, qc_tp_version: 'v1' }, undefined, 'create', { allowError: true });
  if (!R.check('FR-14', 'create test program', tp.recordId, `tp=${tp.recordId} detail=${errText(tp)}`)) return;

  const act = await execCommand(token, 'qc:activate_test_program', {}, tp.recordId, 'state_transition', { allowError: true });
  R.check('FR-14', 'activate_test_program executes', act.ok, `ok=${act.ok} code=${act.code} detail=${errText(act)}`);
  const tpStatus = scalar(`select qc_tp_status from mt_qc_test_program where pid='${sq(tp.recordId)}'`);
  R.check('FR-14', 'test program status → active', tpStatus === 'active', `status=${tpStatus}`);

  // Record a FAILING test result (qc_tr_result='fail') with raw_data.failures → the handler
  // auto-creates a qc_test_defect whose qc_td_test_result_id points back at this result.
  const serial = uid('SN');
  const tr = await execCommand(token, 'qc:record_test_result',
    { qc_tr_program_id: tp.recordId, qc_tr_serial_number: serial, qc_tr_result: 'fail',
      qc_tr_raw_data: { failures: [ { type: 'solder_bridge', componentRef: 'R12', location: 'top', severity: 'major', description: 'bridge between pads', disposition: 'rework' } ] } },
    undefined, 'create', { allowError: true });
  R.check('FR-14', 'record_test_result (fail) executes', tr.ok, `ok=${tr.ok} code=${tr.code} detail=${errText(tr)}`);

  // Resolve the created test-result pid from the DB by its unique serial (do not trust the
  // create+handler response shape).
  const trPid = scalar(`select pid from mt_qc_test_result where qc_tr_serial_number='${sq(serial)}' and qc_tr_result='fail' order by created_at desc limit 1`);
  if (!R.check('FR-14', 'failing test result persisted', !!trPid, `trPid=${trPid} serial=${serial}`)) return;

  // FR-14 core part 1: the failing result must auto-create a defect linked back to it.
  //
  // ⚠️ SHIPPED PRODUCT BUG (this assertion is RED against the live stack, correctly):
  //   RecordTestResultHandler.execute() reads the just-created result via db.getById and
  //   gates defect creation on `rawDataObj instanceof Map` (qc_tr_raw_data). But the platform
  //   read-shape contract (DynamicDataServiceImpl.java:273-274 / :1004-1005 →
  //   JsonbFieldHelper.normalizeJsonReadValues: "json/jsonb fields leave as JSON strings, never
  //   PGobject") returns json fields as STRINGS, so `instanceof Map` is never true → NO
  //   qc_test_defect is ever auto-created (0 rows in the DB), even though qc_tr_raw_data is
  //   correctly persisted as jsonb with a failures[] array. Because CreateReworkOrderHandler can
  //   only link a rework order to a failure via a qc_test_defect (or qc_ncr) source, the FR-14
  //   fail→rework linkage cannot form through the shipped happy-path. Fix = handler should
  //   JSON-parse the string (or platform should return json as a Map). This golden stays RED
  //   until then — do NOT lower the bar / seed the defect via raw INSERT to make it green.
  const defRows = queryDb(`select pid, qc_td_test_result_id, qc_td_defect_type, qc_td_rework_status from mt_qc_test_defect where qc_td_test_result_id='${sq(trPid)}'`);
  const def = defRows[0];
  if (!R.check('FR-14', 'failing result auto-created a defect linked to the test result',
      !!def && def[1] === trPid,
      `defect=${JSON.stringify(def)} testResult=${trPid} — PRODUCT BUG: RecordTestResultHandler gates on 'raw_data instanceof Map' but platform read-shape returns json as String → defect never created (0 rows)`)) return;
  const defectId = def[0];

  // Create a rework order for that failed defect (source_type=test_defect + source_id=defect).
  const rw = await execCommand(token, 'qc:create_rework_order',
    { qc_rw_source_type: 'test_defect', qc_rw_source_id: defectId, qc_rw_product_id: prod.recordId,
      qc_rw_serial_no: serial, qc_rw_quantity: 1, qc_rw_defect_code: 'solder_bridge', qc_rw_notes: `rework for ${serial}` },
    undefined, 'create', { allowError: true });
  R.check('FR-14', 'create_rework_order executes', rw.ok, `ok=${rw.ok} code=${rw.code} detail=${errText(rw)}`);

  // FR-14 core part 2: a rework order row exists linked to the failed defect.
  const rwRows = queryDb(`select pid, qc_rw_source_type, qc_rw_source_id, qc_rw_status from mt_qc_rework_order where qc_rw_source_type='test_defect' and qc_rw_source_id='${sq(defectId)}'`);
  const rwRow = rwRows[0];
  R.check('FR-14', 'rework order row exists linked to the failed defect',
    !!rwRow && rwRow[2] === defectId, `row=${JSON.stringify(rwRow)} expected defect=${defectId}`);

  // FR-14 full chain (复测↔原失败关联): rework order → defect → the ORIGINAL failing test result.
  const chained = scalar(`select count(*) from mt_qc_rework_order rw join mt_qc_test_defect td on td.pid = rw.qc_rw_source_id where rw.qc_rw_source_id='${sq(defectId)}' and td.qc_td_test_result_id='${sq(trPid)}'`);
  R.check('FR-14', 'full chain resolves: rework order → defect → failing test result', Number(chained) >= 1, `chained rows=${chained}`);
}
try { await frTestReworkLinkage(); } catch (e) { R.check('FR-14', 'no exception', false, String(e.message).slice(0, 200)); }

// ------------------------------------------------------------------ deeper-gap caveats (honest)
// This golden proves the SHIPPED happy-path + the key blocking/linkage of each FR. It does NOT
// cover these deeper spec gaps (they are genuinely not implemented / out of scope for this pass):
R.deferred('FR-08', 'batch-expiry / near-expiry blocking on scan not covered (handler matches BOM membership + lot code, not expiry)');
R.deferred('FR-12', 'SN uniqueness constraint not covered (genealogy is append-only; no unique guard on finished/component SN)');
R.deferred('FR-14', 'retest→original-failure closed-loop chain not covered (proved fail→defect→rework link; not the retest-result-back-to-original-fail loop)');

// ------------------------------------------------------------------ summary
const s = R.summary();
const frCovered = [...new Set(R.results.filter((r) => !r.deferred).map((r) => r.fr))];
console.log(`\n=== SUMMARY: ${s.pass}/${s.total} checks pass, ${s.fail} fail, ${s.deferred} deferred ===`);
console.log(`    FRs covered: ${frCovered.sort().join(', ')}`);
console.log(`    Deferred (deeper gaps, honest): ${[...new Set(R.results.filter((r) => r.deferred).map((r) => r.fr))].join(', ')}`);
process.exit(s.fail > 0 ? 1 : 0);
