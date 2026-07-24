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

// ------------------------------------------------------------------ summary
const s = R.summary();
console.log(`\n=== SUMMARY: ${s.pass}/${s.total} checks pass, ${s.fail} fail ===`);
process.exit(s.fail > 0 ? 1 : 0);
