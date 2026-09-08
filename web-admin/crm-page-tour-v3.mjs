// CRM page tour v3 — zh-CN locale, correct routes, strict per-page checks.
// Read-only navigation against the slot-105 golden stack.
import { chromium } from '@playwright/test';
import fs from 'fs';
import path from 'path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5205';
const OUT = '/Users/ghj/work/auraboot/.workspace/evidence/crm-full-matrix-s105/tour';
fs.mkdirSync(OUT, { recursive: true });

const PAGES = [
  ['dashboard-crm', '/dashboards/view/crm_dashboard'],
  ['forecast-cockpit', '/dashboards/view/crm_sales_forecast'],
  ['lead-desk-workbench', '/p/c/crm_lead_desk_workbench'],
  ['opportunity-workspace', '/p/c/crm_opportunity_workspace'],
  ['customer-360', '/p/c/crm_customer_360_workbench'],
  ['leads', '/p/crm_lead_common'],
  ['customers', '/p/crm_account_common'],
  ['contacts', '/p/crm_contact_common'],
  ['opportunities', '/p/crm_opportunity_common'],
  ['complaints', '/p/crm_complaint'],
  ['sla-policy', '/p/crm_sla_policy'],
  ['sla-breach', '/p/crm_sla_breach'],
  ['lead-capacity', '/p/crm_lead_capacity_common'],
  ['customer-capacity', '/p/crm_customer_capacity'],
  ['lead-pool-list', '/p/c/crm_lead_pool_item_list'],
  ['lead-pool-recycle-rule', '/p/crm_lead_pool_recycle_rule_common'],
  ['customer-pool-list', '/p/c/crm_customer_pool_item_list'],
  ['customer-pool-recycle-rule', '/p/crm_customer_pool_recycle_rule'],
  ['opportunity-close-rule', '/p/crm_opportunity_close_rule'],
  ['email-log', '/p/crm_email_log'],
];

const results = [];
const browser = await chromium.launch({ args: ['--no-proxy-server'] });
const ctx = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  ignoreHTTPSErrors: true,
  locale: 'zh-CN',
  timezoneId: 'Asia/Shanghai',
});
const page = await ctx.newPage();
const consoleErrors = [];
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text().slice(0, 120));
});

// login via API form post (session cookie lands in the context)
const loginResp = await page.request.post(BASE + '/login', {
  form: { email: 'admin@auraboot.com', password: 'Test2026x', remember: 'on', redirectTo: '/' },
  maxRedirects: 0,
});
console.log('login:', loginResp.status());
await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(3000);
await page.screenshot({ path: path.join(OUT, '00-login-landing.png') });

for (const [name, route] of PAGES) {
  const entry = { name, route };
  try {
    const resp = await page.goto(BASE + route, { waitUntil: 'domcontentloaded', timeout: 30000 });
    // give data tables/blocks time to render
    await page.waitForTimeout(9000);
    entry.http = resp ? resp.status() : 'nav-fail';
    await page.screenshot({ path: path.join(OUT, `page-${name}.png`) });
    const text = (await page.locator('body').innerText().catch(() => '')) || '';
    entry.textLen = text.length;
    entry.issues = [];
    if (/\$i18n:/.test(text)) entry.issues.push('i18n-key-leak');
    if (/\bundefined\b/.test(text)) entry.issues.push('literal-undefined');
    if (/\bNaN\b/.test(text)) entry.issues.push('literal-NaN');
    if (/\[object Object\]/.test(text)) entry.issues.push('object-object');
    if (/\bcrm_[a-z0-9]+_(id|pid|code)\b/i.test(text)) entry.issues.push('raw-field-token');
    if (/\b[0-9A-HJKMNP-TV-Z]{26}\b/.test(text)) entry.issues.push('raw-ulid');
    const overflow = await page.evaluate(() => {
      const d = document.documentElement;
      return d.scrollWidth > d.clientWidth + 2 ? d.scrollWidth - d.clientWidth : 0;
    });
    if (overflow > 0) entry.issues.push(`h-overflow-${overflow}px`);
    if (text.length < 200) entry.issues.push(`sparse-${text.length}`);
    if (entry.http !== 200) entry.issues.push(`http-${entry.http}`);
    entry.sample = text.replace(/\s+/g, ' ').slice(0, 200);
  } catch (e) {
    entry.error = String(e).slice(0, 140);
  }
  results.push(entry);
  const flag = (entry.issues || []).length ? ' ⚠ ' + entry.issues.join(',') : ' ✓';
  console.log(entry.name, entry.http ?? '', flag);
}
fs.writeFileSync(path.join(OUT, 'tour-results.json'), JSON.stringify({ consoleErrors: consoleErrors.slice(0, 10), results }, null, 2));
await browser.close();
const flagged = results.filter((r) => (r.issues || []).length);
console.log('DONE flagged:', flagged.length, '/', results.length);
