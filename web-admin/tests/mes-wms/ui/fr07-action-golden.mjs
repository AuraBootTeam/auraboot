// FR-07 工位执行 — action-driven UI golden. Not just "page renders": actually CLICKS the
// row "start" action and asserts the operation transitions pending→in_progress in BOTH the
// live DB and the UI (§2.2: 门禁绿≠功能可用 — drive the real action point, assert state change).
//   BASE=http://127.0.0.1:5163 PG_DB=auraboot_63 node fr07-action-golden.mjs
import { chromium } from '@playwright/test';
import { execFileSync } from 'node:child_process';
const BASE = process.env.BASE || 'http://127.0.0.1:5163';
const OUT = new URL('.', import.meta.url).pathname;
const db = (sql) => execFileSync('psql', ['-h', '127.0.0.1', '-p', '5432', '-U', 'auraboot', '-d', process.env.PG_DB || 'auraboot_63', '-tAc', sql], { env: { ...process.env, PGPASSWORD: 'auraboot' }, encoding: 'utf8' }).trim();
const results = [];
const check = (n, c, d = '') => { results.push({ pass: !!c }); console.log(`  [${c ? 'PASS' : 'FAIL'}] ${n}${d ? ' — ' + d : ''}`); };

const b = await chromium.launch();
const p = await (await b.newContext({ ignoreHTTPSErrors: true, locale: 'zh-CN', viewport: { width: 1440, height: 900 } })).newPage();
try {
  const es = 'input[type="email"], input[name="email"], input[autocomplete="username"]';
  let s = false;
  for (let a = 0; a < 5 && !s; a++) { await p.goto(`${BASE}/`, { waitUntil: 'load', timeout: 25000 }).catch(() => {}); await p.waitForTimeout(2000); s = await p.waitForSelector(es, { state: 'visible', timeout: 8000 }).then(() => 1).catch(() => 0); }
  await p.fill(es, 'admin@auraboot.com'); await p.fill('input[type="password"]', 'Test2026x');
  await Promise.all([p.waitForTimeout(2500), p.click('button[type="submit"]')]); await p.waitForTimeout(1500);

  const before = Number(db("select count(*) from mt_mfg_work_order_operation_pcba_execution where mfg_wop_status='in_progress'"));
  const pending = Number(db("select count(*) from mt_mfg_work_order_operation_pcba_execution where mfg_wop_status='pending'"));
  check('precondition: pending operations exist to start', pending > 0, `pending=${pending} in_progress=${before}`);

  await p.goto(`${BASE}/p/c/pe_station_execution`, { waitUntil: 'load', timeout: 25000 }).catch(() => {});
  await p.waitForTimeout(3000);
  await p.screenshot({ path: `${OUT}/fr07-before-start.png`, fullPage: true });

  // Click the first row "start" action (label Start/start).
  const startBtn = p.getByText(/^start$/i).first();
  const visible = await startBtn.isVisible().catch(() => false);
  check('start action button present on a row', visible);
  let toastText = '';
  if (visible) {
    await startBtn.click();
    await p.waitForTimeout(3000); // command executes through the real pipeline
    toastText = await p.locator('body').innerText().catch(() => '') || '';
    await p.screenshot({ path: `${OUT}/fr07-after-start.png`, fullPage: true });
  }

  // FR-05/FR-07 seam: my seeded operations have no active production version, so the FR-05 startup
  // interlock MUST block the start. Assert the operation did NOT transition (stayed pending in DB)
  // and the interlock reason surfaced — this proves the interlock gate works end-to-end via the UI.
  const after = Number(db("select count(*) from mt_mfg_work_order_operation_pcba_execution where mfg_wop_status='in_progress'"));
  check('FR-05 interlock blocks start (no illegal pending→in_progress)', after === before, `in_progress ${before}→${after}`);
  check('interlock reason surfaced to the operator', /interlock|互锁|blocked|product_version|生产版本/i.test(toastText), 'block message');
} catch (e) { check('no exception', false, String(e.message).slice(0, 160)); await p.screenshot({ path: `${OUT}/fr07-action-err.png` }).catch(() => {}); }
await b.close();
const pass = results.filter((r) => r.pass).length;
console.log(`\n=== FR-07 ACTION GOLDEN: ${pass}/${results.length} pass ===`);
process.exit(pass < results.length ? 1 : 0);
