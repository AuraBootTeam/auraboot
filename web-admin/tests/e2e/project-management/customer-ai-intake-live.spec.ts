import { liveShowcaseLogin } from '../../helpers/live-showcase-login';
import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { navigateToMenuByClick } from '../helpers';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

test.use({
  storageState: { cookies: [], origins: [] },
  locale: 'zh-CN',
  viewport: { width: 1440, height: 1000 },
});
test.setTimeout(180_000);

async function customers(page: Page, name: string) {
  const response = await page.request.get('/api/dynamic/tpm_customer/list', {
    params: {
      pageNum: '1',
      pageSize: '20',
      filters: JSON.stringify([{ fieldName: 'tpm_cu_name', operator: 'EQ', value: name }]),
    },
  });
  expect(response.ok()).toBe(true);
  const result = await response.json();
  expect(String(result.code)).toBe('0');
  expect(Array.isArray(result.data.records)).toBe(true);
  return result.data.records as Array<Record<string, unknown>>;
}
async function capture(page: Page, info: TestInfo, name: string) {
  await info.attach(name, {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png',
  });
}
async function extractLive(page: Page, source: string, info: TestInfo, evidenceName: string) {
  await page.getByTestId('ai-fill-trigger').click();
  await page.getByTestId('ai-fill-input').fill(source);
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/ai/aurabot/chat/stream' &&
      r.request().method() === 'POST',
  );
  await page.getByTestId('ai-fill-confirm').click();
  const stream = await response;
  expect(stream.ok()).toBe(true);
  const request = stream.request().postDataJSON();
  expect(request.formFill.modelCode).toBe('tpm_customer');
  expect(request.formFill.fields.map((field: { code: string }) => field.code).sort()).toEqual([
    'tpm_cu_contact',
    'tpm_cu_name',
  ]);
  expect(request.message).not.toContain('@@AURABOOT_STUB');
  expect(await stream.text()).toContain('"action":"form_fill"');
  await expect(page.getByTestId('ai-fill-dialog')).toBeHidden();
  await expect(page.getByTestId('form-ai-fill-receipt')).toBeVisible();
  const tracePage = await page.request.get('/api/ai/traces', {
    params: { sessionId: request.sessionId, pageSize: '20' },
  });
  expect(tracePage.ok()).toBe(true);
  const trace = (await tracePage.json()).records.find(
    (item: { input: string }) => item.input === request.message,
  );
  expect(trace, 'the live request must have a persisted trace').toBeTruthy();
  const traceResponse = await page.request.get(`/api/ai/traces/${trace.traceId}`);
  expect(traceResponse.ok()).toBe(true);
  const detail = await traceResponse.json();
  expect(detail.trace.metadata.runtime_skill.code).toBe('form_draft_fill');
  expect(detail.trace.metadata.provider_code).toBe(process.env.PW_EXPECT_LIVE_PROVIDER);
  expect(detail.trace.metadata.provider_code).not.toMatch(/stub|mock|fake/i);
  expect(
    detail.spans.some(
      (span: { model?: string }) => span.model === process.env.PW_EXPECT_LIVE_MODEL,
    ),
  ).toBe(true);
  await info.attach(`${evidenceName}-live-provider-trace`, {
    body: JSON.stringify({
      traceId: trace.traceId,
      provider: detail.trace.metadata.provider_code,
      expectedModel: process.env.PW_EXPECT_LIVE_MODEL,
      runtimeSkill: detail.trace.metadata.runtime_skill.code,
    }),
    contentType: 'application/json',
  });
  return trace.traceId as string;
}

test('SC06: live customer notes → reviewed draft → undo → manual correction → saved record', async ({
  page,
}, info) => {
  // This is a live-only scenario. Missing configuration is a failed prerequisite,
  // never a skip or a stub-backed extraction-quality success.
  expect(
    process.env.PW_REQUIRE_LIVE_LLM,
    'set PW_REQUIRE_LIVE_LLM=1 only for a verified live-provider runtime',
  ).toBe('1');
  expect(
    process.env.PW_EXPECT_LIVE_PROVIDER,
    'the exact provider identity is required',
  ).toBeTruthy();
  expect(process.env.PW_EXPECT_LIVE_PROVIDER).not.toMatch(/stub|mock|fake/i);
  expect(
    process.env.PW_EXPECT_LIVE_MODEL,
    'the exact configured live model is required',
  ).toBeTruthy();
  let name: string;
  const sourceFor = (customerName: string) => `今天客户会议纪要比较零散：先核对公司名称，正式客户名称是“${customerName}”。对接人为采购部张经理。请只整理明确的客户名称和客户联系人，不要推测其他资料。`;
  page.setDefaultTimeout(20000);
  await liveShowcaseLogin(page);
  const identity = await (await page.request.get('/api/auth/me')).json();
  expect(String(identity.code)).toBe('0');
  expect(String(identity.data.user.tenantId)).toBe(process.env.AURA_ADMIN_TENANT_ID);
  expect(identity.data.user.email).toBe(DEFAULT_TEST_ACCOUNT.email);

  const existing = await page.request.get('/api/dynamic/tpm_customer/list?pageSize=1');
  expect(existing.ok()).toBe(true);
  const customerList = await existing.json();
  expect(String(customerList.code)).toBe('0');
  expect(Number.isInteger(customerList.data.total)).toBe(true);
  name = `华辰制造 · 第${customerList.data.total + 1}事业部`;
  const source = sourceFor(name);

  await navigateToMenuByClick(page, ['项目交付', 'AI整理客户资料']);
  await expect(page).toHaveURL(/\/p\/c\/tpm_customer_ai_intake$/);
  await expect(page.getByTestId('ai-fill-banner')).toBeVisible();
  const customerName = page.getByTestId('form-field-tpm_cu_name').locator('input');
  const contact = page.getByTestId('form-field-tpm_cu_contact').locator('input');
  await expect(customerName).toHaveValue('');
  await expect(contact).toHaveValue('');
  expect(await customers(page, name)).toHaveLength(0);
  const writes: string[] = [];
  page.on('request', (request) => {
    if (
      request.method() !== 'GET' &&
      /\/api\/(meta\/commands|commands|command|dynamic)\//.test(request.url())
    )
      writes.push(request.url());
  });
  await capture(page, info, 'SC06-explicit-ai-entry');
  await page.getByTestId('ai-fill-trigger').click();
  await expect(page.getByTestId('ai-fill-confirm')).toBeDisabled();
  await page
    .getByTestId('ai-fill-dialog')
    .getByRole('button', { name: '取消', exact: true })
    .click();
  await expect(page.getByTestId('ai-fill-dialog')).toBeHidden();
  expect(writes).toHaveLength(0);
  const firstTrace = await extractLive(page, source, info, 'SC06-first-extraction');
  await expect(customerName).toHaveValue(name);
  await expect(contact).toHaveValue(/张经理/);
  await page.getByTestId('form-ai-fill-receipt').locator('summary').click();
  await expect(page.getByTestId('form-ai-review-tpm_cu_name')).toBeVisible();
  await expect(page.getByTestId('form-ai-review-tpm_cu_name')).toContainText(name);
  await capture(page, info, 'SC06-extracted-draft-review');
  expect(writes).toHaveLength(0);
  expect(await customers(page, name)).toHaveLength(0);
  await page.getByTestId('form-ai-fill-undo').click();
  await expect(customerName).toHaveValue('');
  await expect(contact).toHaveValue('');
  await capture(page, info, 'SC06-undo-draft');
  const secondTrace = await extractLive(
    page,
    `${source} 这次请再次按原文整理，保持客户名称不变。`,
    info,
    'SC06-second-extraction',
  );
  expect(secondTrace).not.toBe(firstTrace);
  await expect(customerName).toHaveValue(name);
  await contact.fill('张经理 · 采购部（人工确认）');
  await expect(contact).toHaveValue('张经理 · 采购部（人工确认）');
  expect(writes).toHaveLength(0);
  expect(await customers(page, name)).toHaveLength(0);
  await capture(page, info, 'SC06-manually-reviewed-before-save');
  const save = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/meta/commands/execute/tpm:create_customer' &&
      r.request().method() === 'POST',
  );
  await page.getByTestId('form-btn-submit').click();
  const response = await save;
  expect(response.ok()).toBe(true);
  expect(response.request().postDataJSON().payload).toMatchObject({
    tpm_cu_name: name,
    tpm_cu_contact: '张经理 · 采购部（人工确认）',
  });
  const result = await response.json();
  expect(String(result.code)).toBe('0');
  expect(writes).toHaveLength(1);
  await expect(page).toHaveURL(/\/p\/tpm_customer$/);
  const records = await customers(page, name);
  expect(records).toHaveLength(1);
  expect(records[0]).toMatchObject({
    tpm_cu_name: name,
    tpm_cu_contact: '张经理 · 采购部（人工确认）',
  });
  await page.reload();
  const row = page.getByRole('row').filter({ hasText: name });
  await expect(row).toBeVisible();
  await expect(row).toContainText('张经理 · 采购部（人工确认）');
  await capture(page, info, 'SC06-saved-customer-after-refresh');
});
