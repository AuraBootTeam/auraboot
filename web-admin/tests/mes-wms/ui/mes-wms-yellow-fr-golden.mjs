// MES 🟡 pre-existing FR — real-browser golden. Navigates each FR's user-facing DSL page,
// screenshots it, and asserts it renders without errors / raw-code leaks. Aesthetics are
// reviewed from the screenshots (agent-vision). Run against the live host-first stack:
//   BASE=http://127.0.0.1:5163 node mes-wms-yellow-fr-golden.mjs
import { chromium } from '@playwright/test';
const BASE = process.env.BASE || 'http://127.0.0.1:5163';
const OUT = new URL('.', import.meta.url).pathname;
const results = [];
const check = (n, c, d = '') => { results.push({ n, pass: !!c }); console.log(`  [${c ? 'PASS' : 'FAIL'}] ${n}${d ? ' — ' + d : ''}`); };

// FR → user-facing route (from menus.json). /p/<model>=list, /p/c/<key>=custom/workbench, /dashboards/view/<key>.
const PAGES = [
  { fr: 'FR-01', name: '工单接入与冻结', route: '/p/mfg_work_order_pcba_execution', expect: /生产管理|工单|Work Order/, file: 'fr01-work-order' },
  { fr: 'FR-03', name: '派工/工位分配', route: '/p/mfg_workstation_assignment_pcba_execution', expect: /分配|派工|工位|Assignment/, file: 'fr03-assignment' },
  { fr: 'FR-07', name: '工序执行(工位)', route: '/p/c/pe_station_execution', expect: /工位|工序|执行|Station|Operation/, file: 'fr07-station-exec' },
  { fr: 'FR-07b', name: '工序列表', route: '/p/mfg_work_order_operation_pcba_execution', expect: /工序|操作|Operation/, file: 'fr07-operation-list' },
  { fr: 'FR-15', name: '异常/Andon 工作台', route: '/p/c/mfg_andon_workbench', expect: /Andon|异常|安灯/, file: 'fr15-andon' },
  { fr: 'FR-15b', name: '工序异常', route: '/p/mfg_operation_exception_pcba_execution', expect: /异常|Exception/, file: 'fr15-op-exception' },
  { fr: 'FR-21', name: '产出/损耗/结案(报工)', route: '/p/mfg_work_report_pcba_execution', expect: /报工|产出|Work Report/, file: 'fr21-work-report' },
  { fr: 'FR-23', name: '生产控制塔(车间看板)', route: '/dashboards/view/pe_shop_floor_dashboard', expect: /车间|看板|生产|Shop Floor|Dashboard/, file: 'fr23-shop-floor' },
];
const RAW_CODE = /pending_ack\b|\bday\b · \bnight\b|\$i18n:|\bnull\b\s*·|undefined|\bNaN\b|\[object Object\]/;

const b = await chromium.launch();
const p = await (await b.newContext({ ignoreHTTPSErrors: true, locale: 'zh-CN', viewport: { width: 1440, height: 900 } })).newPage();
const consoleErrors = [];
p.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
try {
  // login (SSE-safe: never gate on networkidle; email field is autocomplete=username)
  const emailSel = 'input[type="email"], input[name="email"], input[autocomplete="username"]';
  let seen = false;
  for (let a = 0; a < 5 && !seen; a++) { await p.goto(`${BASE}/`, { waitUntil: 'load', timeout: 25000 }).catch(() => {}); await p.waitForTimeout(2000); seen = await p.waitForSelector(emailSel, { state: 'visible', timeout: 8000 }).then(() => true).catch(() => false); }
  if (!seen) throw new Error('login form did not appear');
  await p.fill(emailSel, 'admin@auraboot.com');
  await p.fill('input[type="password"]', 'Test2026x');
  await Promise.all([p.waitForTimeout(2500), p.click('button[type="submit"]')]);
  await p.waitForTimeout(1500);
  check('login', /\/home|\/dashboard|\/p\//.test(p.url()), p.url());

  for (const pg of PAGES) {
    consoleErrors.length = 0;
    await p.goto(`${BASE}${pg.route}`, { waitUntil: 'load', timeout: 25000 }).catch(() => {});
    await p.waitForTimeout(3000); // let the DSL page + data load
    await p.screenshot({ path: `${OUT}/${pg.file}.png`, fullPage: true });
    const main = (await p.locator('main').first().innerText().catch(() => '')) || (await p.locator('body').innerText().catch(() => ''));
    const notFound = /Page not found|页面不存在|404|Not Found/i.test(main);
    check(`${pg.fr} ${pg.name} — page renders (not 404)`, !notFound && main.length > 30, notFound ? '404!' : `${main.length} chars`);
    check(`${pg.fr} — content matches (${pg.expect})`, pg.expect.test(main), main.replace(/\s+/g, ' ').slice(0, 60));
    check(`${pg.fr} — no raw-code leak`, !RAW_CODE.test(main), (main.match(RAW_CODE) || [''])[0]);
    check(`${pg.fr} — no console errors`, consoleErrors.length === 0, consoleErrors.slice(0, 1).join('').slice(0, 80));
  }
} catch (e) { check('no exception', false, String(e.message).slice(0, 160)); await p.screenshot({ path: `${OUT}/yellow-err.png` }).catch(() => {}); }
await b.close();
const pass = results.filter((r) => r.pass).length;
console.log(`\n=== 🟡 FR UI GOLDEN: ${pass}/${results.length} pass ===`);
process.exit(pass < results.length ? 1 : 0);
