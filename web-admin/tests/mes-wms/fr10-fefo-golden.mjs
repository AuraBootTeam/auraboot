// FR-10 FEFO pick allocation — real-stack backend golden.
//
// Exercises the current Inventory-owner FEFO allocation path
// (ReceiveSourceIssueDemandHandler → IssuePickTaskHandler) end-to-end through the real command
// pipeline + DB round-trip. Run against a live host-first stack:
//   BACKEND_URL=http://127.0.0.1:6463 PG_DB=auraboot_63 PG_HOST=127.0.0.1 PG_PORT=5432 \
//   PG_USER=auraboot PGPASSWORD=auraboot ADMIN_EMAIL=admin@auraboot.com ADMIN_PASSWORD=Test2026x \
//   node fr10-fefo-golden.mjs
//
// ─────────────────────────────────────────────────────────────────────────────────────────────
// SEED PATH (every balance row is created by the REAL command pipeline, never raw psql):
//   1. inv:create_warehouse            → warehouse; lot-tracking + pick-strategy set via the
//      platform dynamic-update API (PUT /api/dynamic/inv_warehouse/{pid}) because NO command
//      whitelists inv_wh_lot_tracking / inv_wh_pick_strategy in its inputFields (verified against
//      the deployed ab_command_definition.execution_config).
//   2. inv:create_warehouse_location   → a location.
//   3. prod:create_product             → the product.
//   4. inv:create_lot  ×N              → lots WITH expiry dates (inv_lot_expiry_date). Pre-creating
//      the lots is what gives FEFO something to sort on: confirm_warehouse_in's findOrCreateLot(code)
//      then resolves the EXISTING expiry-dated lot instead of auto-creating an expiry-less one.
//   5. inv:create_warehouse_in + inv:add_wh_in_line ×N → receipt + lines. The command requires
//      inv_in_line_lot_code / inv_in_line_location_id; a dynamic read-after-write update repeats
//      those exact values to exercise the public update path without changing the fixture intent.
//   6. inv:confirm_warehouse_in        → ConfirmWarehouseInHandler creates one inv_balance row per
//      line, each with inv_bal_lot_id linked to the pre-created lot. THIS is the real pipeline
//      creating the lot-linked balance FEFO sorts on.
//   7. Assert the inbound pipeline initialized inv_bal_available_qty = inv_bal_qty for fresh,
//      unreserved stock (#244). No update workaround is allowed here.
//   8. inv:receive_source_issue_demand → Inventory-owner production demand + line.
//   9. inv:create_issue_pick_task (targetRecordPid = outbound) → the current owner-boundary handler
//      sorts lot-linked inv_balance candidates by expiry and applies the required-date exclusion.
//
// #244 closed the former fresh-stock reachability gap: ConfirmWarehouseInHandler now initializes
// available quantity on first write. This golden deliberately performs no balance update; reverting
// #244 makes the fresh-available assertion and the downstream pick assertions go RED.
//
// FALSIFIABILITY: scenario A writes FAR before NEAR but allocation must still choose NEAR. Scenario B
// independently proves the required-date exclusion drops a sooner-expiring lot that is otherwise available.

import { login, execCommand, makeReporter, uid, queryDb, scalar } from './harness.mjs';

const BACKEND = process.env.BACKEND_URL || 'http://127.0.0.1:6463';
const sq = (s) => String(s).replace(/'/g, "''");
const R = makeReporter();
const token = await login();

// yyyy-MM-dd, N days from today (platform DATE columns want yyyy-MM-dd strings).
function dstr(days) {
  return new Date(Date.now() + days * 86400000).toISOString().slice(0, 10);
}

// Platform dynamic-update API — a real backend pipeline (validation + tenant scoping), NOT raw psql.
async function dynUpdate(model, pid, data) {
  const r = await fetch(`${BACKEND}/api/dynamic/${model}/${encodeURIComponent(pid)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(data),
  });
  const t = await r.text();
  if (!r.ok) throw new Error(`PUT /api/dynamic/${model}/${pid} → ${r.status}: ${t.slice(0, 200)}`);
  return JSON.parse(t);
}

async function createWarehouse(u, strategy) {
  const wh = await execCommand(token, 'inv:create_warehouse',
    { inv_warehouse_name: `WH ${u}`, inv_warehouse_type: 'normal' }, undefined, 'create', { allowError: true });
  if (!wh.recordId) throw new Error(`create_warehouse failed: ${JSON.stringify(wh.raw?.context || wh.raw)}`);
  await dynUpdate('inv_warehouse', wh.recordId, { inv_wh_lot_tracking: true, inv_wh_pick_strategy: strategy });
  const [row] = queryDb(`select inv_wh_lot_tracking, inv_wh_pick_strategy from mt_inv_warehouse where pid='${sq(wh.recordId)}'`);
  if (row[0] !== 't' || row[1] !== strategy) throw new Error(`warehouse config not applied: ${JSON.stringify(row)}`);
  return wh.recordId;
}

async function createLocation(u, whId) {
  const loc = await execCommand(token, 'inv:create_warehouse_location',
    {
      inv_wl_name: `L ${u}`,
      inv_wl_code: `L-${u}`,
      inv_wl_warehouse_id: whId,
      inv_loc_type: 'shelf',
      inv_loc_status: 'active',
    },
    undefined, 'create', { allowError: true });
  if (!loc.recordId) throw new Error(`create_location failed: ${JSON.stringify(loc.raw?.context || loc.raw)}`);
  return loc.recordId;
}

async function createProduct(u) {
  const p = await execCommand(token, 'prod:create_product',
    { prod_name: `Mat ${u}`, prod_type: 'raw_material', prod_unit: 'pcs' }, undefined, 'create', { allowError: true });
  if (!p.recordId) throw new Error(`create_product failed: ${JSON.stringify(p.raw?.context || p.raw)}`);
  return p.recordId;
}

async function createLot(code, prodId, expiryDays) {
  const lot = await execCommand(token, 'inv:create_lot',
    { inv_lot_code: code, inv_lot_type: 'batch', inv_lot_product_id: prodId, inv_lot_expiry_date: dstr(expiryDays) },
    undefined, 'create', { allowError: true });
  if (!lot.recordId) throw new Error(`create_lot(${code}) failed: ${JSON.stringify(lot.raw?.context || lot.raw)}`);
  return { pid: lot.recordId, code, expiry: dstr(expiryDays) };
}

// Inbound one receipt with the given lot lines and confirm it → real pipeline creates lot-linked balance.
async function inboundReceipt(whId, prodId, locId, lines) {
  const rcpt = await execCommand(token, 'inv:create_warehouse_in',
    { inv_in_type: 'purchase', inv_in_date: dstr(0), inv_in_warehouse_id: whId }, undefined, 'create', { allowError: true });
  if (!rcpt.recordId) throw new Error(`create_warehouse_in failed: ${JSON.stringify(rcpt.raw?.context || rcpt.raw)}`);
  for (const { lotCode, qty } of lines) {
    const line = await execCommand(token, 'inv:add_wh_in_line',
      {
        inv_in_line_receipt_id: rcpt.recordId,
        inv_in_line_product_id: prodId,
        inv_in_line_qty: qty,
        inv_in_line_price: 1,
        inv_in_line_lot_code: lotCode,
        inv_in_line_location_id: locId,
      },
      undefined, 'create', { allowError: true });
    if (!line.recordId) throw new Error(`add_wh_in_line failed: ${JSON.stringify(line.raw?.context || line.raw)}`);
    // Preserve a read-after-write check through the dynamic API; both fields are now required command inputs.
    await dynUpdate('inv_inbound_line', line.recordId, { inv_in_line_lot_code: lotCode, inv_in_line_location_id: locId });
  }
  const conf = await execCommand(
    token,
    'inv:confirm_warehouse_in',
    {},
    rcpt.recordId,
    'state_transition',
    { allowError: true, clientRequestId: uid('CONFIRM-IN') },
  );
  if (!conf.ok) throw new Error(`confirm_warehouse_in failed: ${JSON.stringify(conf.raw?.context || conf.raw)}`);
  return rcpt.recordId;
}

function assertFreshAvailableInitialized(whId, scenario) {
  const rows = queryDb(`select inv_bal_qty, inv_bal_available_qty from mt_inv_balance where inv_bal_warehouse_id='${sq(whId)}'`);
  R.check('FR-10', `${scenario}: fresh inbound initializes available_qty without a test workaround`,
    rows.length > 0 && rows.every(([qty, available]) => Number(qty) === Number(available)),
    `balances=${JSON.stringify(rows)}`);
}

// Create a production issue demand through the Inventory owner intake, then allocate its pick task.
async function generatePick(whId, prodId, outDateDays, reqQty) {
  const sourcePid = uid('MES-DEMAND');
  const intake = await execCommand(
    token,
    'inv:receive_source_issue_demand',
    {
      sourceType: 'mes_work_order',
      sourcePid,
      sourceNo: sourcePid,
      warehouseId: whId,
      requiredDate: dstr(outDateDays),
      lines: [{ productId: prodId, quantity: reqQty }],
    },
    undefined,
    'action',
    { allowError: true, clientRequestId: uid('ISSUE-DEMAND') },
  );
  const outboundId = intake.data?.outboundId;
  if (!intake.ok || !outboundId) {
    throw new Error(`receive_source_issue_demand failed: ${JSON.stringify(intake.raw?.context || intake.raw)}`);
  }
  const gen = await execCommand(
    token,
    'inv:create_issue_pick_task',
    {},
    outboundId,
    'action',
    { allowError: true, clientRequestId: uid('ISSUE-PICK') },
  );
  const pickId = gen.data?.pickOrderId;
  const lines = pickId
    ? queryDb(`select inv_pkl_lot_id, inv_pkl_required_qty, inv_pkl_product_id from mt_inv_pick_order_line where inv_pkl_pick_id='${sq(pickId)}'`)
    : [];
  return { gen, pickId, lines };
}

// ───────────────────────────────────────────────── Scenario A: core FEFO (single receipt, FAR line first)
async function scenarioFefo() {
  console.log('\n[FR-10 · A] FEFO — near-expiry lot allocated first, even though FAR line was inbounded first');
  const u = uid('FEFO');
  const wh = await createWarehouse(u, 'fefo');
  const loc = await createLocation(u, wh);
  const prod = await createProduct(u);
  const near = await createLot(`LOT-NEAR-${u}`, prod, 5);
  const far = await createLot(`LOT-FAR-${u}`, prod, 90);
  // Single receipt, FAR line FIRST then NEAR — DB-natural order is NOT the FEFO order.
  await inboundReceipt(wh, prod, loc, [{ lotCode: far.code, qty: 100 }, { lotCode: near.code, qty: 100 }]);

  const bal = queryDb(`select inv_bal_lot_id from mt_inv_balance where inv_bal_warehouse_id='${sq(wh)}' order by created_at`);
  R.check('FR-10', 'A: pipeline created 2 lot-linked balance rows',
    bal.length === 2 && bal.every((r) => r[0]) , `lot_ids=${bal.map((r) => r[0]).join(',')}`);
  assertFreshAvailableInitialized(wh, 'A');

  const { gen, lines } = await generatePick(wh, prod, 0, 50); // window=today: both lots kept
  R.check('FR-10', 'A: create_issue_pick_task executed', gen.ok, `code=${gen.code} status=${gen.status} ctx=${JSON.stringify(gen.raw?.context || '').slice(0, 120)}`);
  R.check('FR-10', 'A: exactly one pick line (50 fits in one lot)', lines.length === 1, `lines=${lines.length}`);
  const pickedLot = lines[0]?.[0];
  R.check('FR-10', 'A: FEFO picked the NEAR-expiry lot (core assertion — not insertion-first FAR)',
    pickedLot === near.pid, `picked=${pickedLot} near=${near.pid} far=${far.pid}`);
}

// ───────────────────────────────────────────────── Scenario B: production-window expiry exclusion
async function scenarioExclusion() {
  console.log('\n[FR-10 · B] Expiry exclusion — a sooner-expiring but past-window lot is NOT allocated');
  const u = uid('FEXCL');
  const wh = await createWarehouse(u, 'fefo');
  const loc = await createLocation(u, wh);
  const prod = await createProduct(u);
  const short = await createLot(`LOT-SHORT-${u}`, prod, 5);   // expires in 5d — SOONEST
  const ok = await createLot(`LOT-OK-${u}`, prod, 90);        // expires in 90d
  await inboundReceipt(wh, prod, loc, [{ lotCode: short.code, qty: 100 }, { lotCode: ok.code, qty: 100 }]);
  assertFreshAvailableInitialized(wh, 'B');

  // Production-window end = today+30. SHORT (today+5) expires BEFORE the window → excluded even though
  // it is fully available and sorts first by FEFO. OK (today+90) is kept.
  const { gen, lines } = await generatePick(wh, prod, 30, 50);
  R.check('FR-10', 'B: create_issue_pick_task executed', gen.ok, `code=${gen.code} status=${gen.status} ctx=${JSON.stringify(gen.raw?.context || '').slice(0, 120)}`);
  const pickedLot = lines[0]?.[0];
  R.check('FR-10', 'B: allocated the OK lot, NOT the sooner-expiring-but-excluded SHORT lot',
    lines.length === 1 && pickedLot === ok.pid, `picked=${pickedLot} ok=${ok.pid} short=${short.pid}`);
  R.check('FR-10', 'B: no pick line references the expired-by-window SHORT lot (exclusion, not deprioritization)',
    !lines.some((r) => r[0] === short.pid), `lot_ids=${lines.map((r) => r[0]).join(',')}`);
}

// ───────────────────────────────────────────────── run
try {
  await scenarioFefo();
  await scenarioExclusion();
} catch (e) {
  R.check('FR-10', 'no unexpected exception during seed/run', false, String(e.message).slice(0, 300));
}

const s = R.summary();
console.log(`\n=== FR-10 FEFO SUMMARY: ${s.pass}/${s.total} checks pass, ${s.fail} fail ===`);
console.log('    Falsifiability: A writes FAR first but allocates NEAR; B excludes a sooner-expiring lot before the required date.');
process.exit(s.fail > 0 ? 1 : 0);
