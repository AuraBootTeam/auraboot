// Seed controlled, business-meaningful CRM data (localized names) via the
// governed command API. Run against the slot-105 golden stack.
import { chromium } from '@playwright/test';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5205';
const browser = await chromium.launch({ args: ['--no-proxy-server'] });
const ctx = await browser.newContext({ locale: 'zh-CN' });
const page = await ctx.newPage();

await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
await page.fill('input[type="password"], input[name*="pass" i]', 'Test2026x', { timeout: 15000 }).catch(() => {});
await page.fill('input[type="text"], input[name*="user" i], input[name*="email" i]', 'admin@auraboot.com').catch(() => {});
await page.keyboard.press('Enter');
await page.waitForTimeout(2000);

async function run(commandCode, payload, operationType) {
  const data = { payload };
  if (arguments[2]) data.operationType = operationType;
  const resp = await page.request.post(`${BASE}/api/meta/commands/execute/${commandCode}`, { data });
  const body = await resp.json().catch(() => ({}));
  if (!resp.ok() || String(body?.code) !== '0') {
    console.log('FAIL', commandCode, resp.status(), JSON.stringify(body).slice(0, 160));
    return null;
  }
  const pid = body?.data?.recordPid ?? body?.data?.recordId ?? body?.data?.pid ?? null;
  console.log('OK', commandCode, pid ?? '');
  return { pid, body };
}

// 客户
await run('crm:create_account', {
  crm_account_name: '华东智造集团',
  crm_account_industry: '制造业',
  crm_account_phone: '021-6688-0001',
  crm_account_address: '上海市浦东新区张江高科技园区博云路 2 号',
});
// 联系人
await run('crm:create_contact', {
  crm_contact_name: '王建国',
  crm_contact_title: '采购总监',
  crm_contact_email: 'wangjianguo@example.cn',
  crm_contact_phone: '138-0000-1001',
});
// 线索 ×2(新建/已联系各一)
await run('crm:create_lead', {
  crm_lead_company: '苏州精密部件有限公司',
  crm_lead_contact_name: '李文娟',
  crm_lead_contact_email: 'liwenjuan@example.cn',
  crm_lead_source: 'website',
});
await run('crm:create_lead', {
  crm_lead_company: '杭州数联科技有限公司',
  crm_lead_contact_name: '赵启明',
  crm_lead_contact_email: 'zhaoqiming@example.cn',
  crm_lead_source: 'referral',
});
// 商机
await run('crm:create_opportunity', {
  crm_opp_name: '华东智造 MES 一期建设项目',
  crm_opp_expected_amount: 1280000,
  crm_opp_expected_close_date: '2026-12-31',
});
// 营销活动
await run('crm:create_campaign', {
  crm_campaign_name: '2026 秋季智能制造巡展',
  crm_campaign_description: '面向华东制造业客户的线下巡展与产品演示活动',
});
// SLA 策略
await run('crm:create_sla_policy', {
  crm_slp_name: '金牌客户 SLA(2 小时响应)',
  crm_slp_priority: 'high',
  crm_slp_response_hours: 2,
  crm_slp_resolution_hours: 8,
  crm_slp_escalation_hours: 24,
  crm_slp_enabled: true,
});
// 投诉(on-dict: quality)
await run('crm:create_complaint', {
  crm_cmp_title: '首批到货外观质量问题反馈',
  crm_cmp_type: 'quality',
  crm_cmp_severity: 'high',
  crm_cmp_description: '首批到货的 3 件精密部件外包装破损,内部暂无损伤,需要质量部门现场复核并出具结论。',
});

await browser.close();
console.log('SEED DONE');
