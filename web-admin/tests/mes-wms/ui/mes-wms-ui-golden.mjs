// MES/WMS UI golden — real-browser verification of the delivered FR pages + polish fixes.
// Run against a live host-first stack (backend + Vite + BFF). Drives the login form, navigates each
// standalone DSL page (/p/c/<pageKey>), asserts key content + polish-fix rendering, and screenshots.
//   BASE=http://127.0.0.1:5163 node mes-wms-ui-golden.mjs
import { chromium } from '@playwright/test';
const BASE = process.env.BASE || 'http://127.0.0.1:5163';
const OUT = new URL('.', import.meta.url).pathname;
const results = [];
const check = (name, cond, detail = '') => { results.push({ name, pass: !!cond }); console.log(`  [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' — ' + detail : ''}`); };

const b = await chromium.launch();
const p = await (await b.newContext({ ignoreHTTPSErrors: true, locale: 'zh-CN' })).newPage();
try {
  // NOTE: the app proxies SSE (/api/notifications/stream), so waitForLoadState('networkidle')
  // NEVER fires — never gate on it. Go straight to the selector (this is what works).
  const emailSel = 'input[type="email"], input[name="email"], input[autocomplete="username"]';
  let seen = false;
  for (let attempt = 0; attempt < 3 && !seen; attempt++) {
    await p.goto(`${BASE}/`, { waitUntil: 'domcontentloaded', timeout: 25000 }).catch(() => {});
    seen = await p.waitForSelector(emailSel, { timeout: 12000 }).then(() => true).catch(() => false);
  }
  if (!seen) throw new Error('login form did not appear after 3 attempts');
  await p.fill(emailSel, 'admin@auraboot.com');
  await p.fill('input[type="password"]', 'Test2026x');
  await Promise.all([p.waitForTimeout(2500), p.click('button[type="submit"]')]);
  await p.waitForTimeout(1500);
  check('login succeeds', /\/home|\/dashboard|\/p\//.test(p.url()), p.url());

  // FR-22 shift handover workbench — renders + #228 dict/reference resolution (白班/夜班/已签认, not raw codes).
  await p.goto(`${BASE}/p/c/mfg_shift_handover_workbench`, { waitUntil: 'domcontentloaded', timeout: 25000 });
  await p.waitForTimeout(2500);
  await p.waitForTimeout(1500);
  await p.screenshot({ path: `${OUT}/fr22-handover-workbench.png`, fullPage: true });
  const main = (await p.locator('main').first().innerText().catch(() => '')) || '';
  check('FR-22 workbench renders (班次交接工作台)', /班次交接工作台/.test(main));
  check('FR-22 metric-strip renders (待签认)', /待签认/.test(main));
  check('FR-22 #228 shift dict resolved (白班/夜班 not day/night)', /白班|夜班/.test(main) && !/\bday\b|\bnight\b/.test(main), 'shift labels');
  check('FR-22 status dict resolved (已签认/待签认 not pending_ack)', /已签认|待签认/.test(main) && !/pending_ack|acknowledged/.test(main), 'status labels');
} catch (e) { check('no exception', false, String(e.message).slice(0, 160)); await p.screenshot({ path: `${OUT}/err.png` }).catch(() => {}); }
await b.close();
const pass = results.filter((r) => r.pass).length;
console.log(`\n=== UI GOLDEN: ${pass}/${results.length} pass ===`);
console.log(`    Covered: FR-22 handover workbench (real browser + #228 polish verified). Remaining FR pages: scale via same pattern.`);
process.exit(pass < results.length ? 1 : 0);
