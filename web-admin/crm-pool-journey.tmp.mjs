// 公海运营台领取旅程:入池客户 → 队列展示 → 领取 → 状态流转。每步截图。
import { chromium } from '@playwright/test';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5120';
const OUT = '/Users/ghj/work/auraboot/.workspace/evidence/crm-full-matrix-s105/pool-journey';
import fs from 'fs';
fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({ args: ['--no-proxy-server'] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
const page = await ctx.newPage();

// login
const lr = await page.request.post(BASE + '/login', {
  form: { email: 'admin@auraboot.com', password: 'Test2026x', remember: 'on', redirectTo: '/' },
  maxRedirects: 0,
});
console.log('login:', lr.status());

// 1. 创建入池客户(pool_state=in_pool)
const resp = await page.request.post(BASE + '/api/meta/commands/execute/crm:create_account', {
  data: { payload: {
    crm_acc_code: 'ACC-POOL-001',
    crm_acc_name: '公海验收客户- Pool Journey',
    crm_acc_status: 'active',
    crm_acc_pool_state: 'in_pool',
    crm_acc_industry: '制造业',
  } },
});
const body = await resp.json().catch(() => ({}));
console.log('create in_pool account:', resp.status(), body.code, body.data?.recordPid ?? '');
const accPid = body.data?.recordPid;

// 2. 公海运营台
await page.goto(BASE + '/p/c/crm_customer_pool_item_list', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(6000);
await page.screenshot({ path: OUT + '/pool-1-queue.png' });
const queueText = await page.locator('body').innerText();
console.log('queue shows customer:', queueText.includes('Pool Journey'));

// 3. 领取(点击行的 领取/拾取 动作)
let claimed = false;
for (const label of ['领取', '拾取', 'claim']) {
  const btn = page.getByText(label, { exact: false }).first();
  if (await btn.count()) {
    try { await btn.click({ timeout: 5000 }); claimed = true; console.log('clicked:', label); break; } catch {}
  }
}
await page.waitForTimeout(3000);
await page.screenshot({ path: OUT + '/pool-2-after-claim.png' });
const afterText = await page.locator('body').innerText();
console.log('claim visible in list:', afterText.includes('Pool Journey') || afterText.includes('已领取'));

await browser.close();
console.log('JOURNEY DONE, claimed =', claimed);
