import { liveShowcaseLogin } from '../../helpers/live-showcase-login';
/** Live typed extraction through the generic DSL form, with reference and manual-value protection. */
import { test, expect } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { navigateToMenuByClick } from '../helpers';
const evidence = process.env.AURA_EVIDENCE_DIR!;

test('SC03 live delivery notes → typed budget/date draft → preserved manual values → reviewed project save', async ({
  page,
}) => {
  test.setTimeout(180000);
  page.setDefaultTimeout(20000);
  expect(process.env.PW_REQUIRE_LIVE_LLM).toBe('1');
  await mkdir(evidence, { recursive: true });
  await liveShowcaseLogin(page);
  const me = (await (await page.request.get('/api/auth/me')).json()).data;
  expect(String(me.user.tenantId)).toBe(process.env.AURA_ADMIN_TENANT_ID);
  await navigateToMenuByClick(page, ['项目交付', 'AI整理交付需求']);
  await expect(page).toHaveURL(/\/p\/c\/tpm_project_ai_intake$/);
  await page.getByTestId('select-trigger-tpm_pj_customer_id').click();
  await page.getByRole('option', { name: '华辰制造', exact: true }).click();
  await page.getByTestId('user-select-trigger-tpm_pj_owner_user').click();
  await page.getByTestId(`user-select-option-tpm_pj_owner_user-${me.user.pid}`).click();
  const manual = '下周二由负责人核对验收清单（人工确认）';
  await page.locator('#tpm_pj_next_action').fill(manual);
  const existing = await page.request.get('/api/dynamic/tpm_project/list?pageSize=1');
  expect(existing.ok()).toBe(true);
  const projectList = await existing.json();
  expect(String(projectList.code)).toBe('0');
  expect(Number.isInteger(projectList.data.total)).toBe(true);
  const name = `华辰制造 · 第${projectList.data.total + 1}期供应商对账升级`;
  const source = `以下是虚构客户会议纪要：正式项目名称为“${name}”。本期范围为供应商台账导入、对账联调和交付培训。项目预算128000.50元人民币，交付日期为2026-11-20；开始日期尚未确定，不能推测。风险：客户测试环境未开放，联调可能延期。下一步拟先安排接口联调。客户与负责人已经人工选好，不要更改。请只整理明确资料，保留未说明字段为空。`;
  const writes: string[] = [];
  page.on('request', (r) => {
    if (
      r.method() === 'POST' &&
      new URL(r.url()).pathname.startsWith('/api/meta/commands/execute/')
    )
      writes.push(r.url());
  });
  await page.getByTestId('ai-fill-trigger').click();
  await page.getByTestId('ai-fill-input').fill(source);
  const streamPromise = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/ai/aurabot/chat/stream' &&
      r.request().method() === 'POST',
  );
  await page.getByTestId('ai-fill-confirm').click();
  const stream = await streamPromise;
  expect(stream.status()).toBe(200);
  const request = stream.request().postDataJSON();
  expect(request.formFill.modelCode).toBe('tpm_project');
  const fields = new Map(request.formFill.fields.map((f: any) => [f.code, f]));
  expect(fields.get('tpm_pj_budget')).toMatchObject({ type: 'number', locked: false });
  expect(fields.get('tpm_pj_end_date')).toMatchObject({ format: 'date', locked: false });
  // Locked/reference fields are excluded from the provider contract, not merely advisory locks.
  for (const code of ['tpm_pj_customer_id', 'tpm_pj_owner_user', 'tpm_pj_status'])
    expect(fields.has(code)).toBe(false);
  expect(await stream.text()).toContain('"action":"form_fill"');
  await expect(page.getByTestId('ai-fill-dialog')).toBeHidden();
  await expect(page.locator('#tpm_pj_name')).toHaveValue(name);
  await expect(page.locator('#tpm_pj_budget')).toHaveAttribute('aria-valuenow', '128000.5');
  await expect(page.locator('#tpm_pj_end_date')).toHaveValue('2026-11-20');
  await expect(page.locator('#tpm_pj_start_date')).toHaveValue('');
  await expect(page.locator('#tpm_pj_description')).toHaveValue(/对账联调/);
  const description = page.locator('#tpm_pj_description');
  await expect(description.locator('..')).toContainText(`${(await description.inputValue()).length}/5000`);
  await expect(page.locator('#tpm_pj_risk_summary')).toHaveValue(/测试环境未开放/);
  await expect(page.locator('#tpm_pj_next_action')).toHaveValue(manual);
  await expect(page.getByTestId('select-trigger-tpm_pj_customer_id')).toContainText('华辰制造');
  expect(writes).toHaveLength(0);
  await page.screenshot({
    path: join(evidence, 'SC03-delivery-applied-form.png'),
    fullPage: false,
  });
  await page.getByTestId('form-ai-fill-receipt').locator('summary').click();
  await expect(page.getByTestId('form-ai-review-tpm_pj_budget')).toContainText('128000.50');
  await page.screenshot({
    path: join(evidence, 'SC03-delivery-draft-review.png'),
    fullPage: false,
  });
  const traces = await (
    await page.request.get('/api/ai/traces', {
      params: { sessionId: request.sessionId, pageSize: '20' },
    })
  ).json();
  const trace = traces.records.find((t: any) => t.input === source);
  expect(trace).toBeTruthy();
  const detail = await (await page.request.get(`/api/ai/traces/${trace.traceId}`)).json();
  expect(detail.trace.metadata.provider_code).toBe(process.env.PW_EXPECT_LIVE_PROVIDER);
  expect(detail.trace.metadata.runtime_skill.code).toBe('form_draft_fill');
  expect(
    detail.spans.some(
      (s: any) =>
        s.type === 'generation' &&
        s.model === process.env.PW_EXPECT_LIVE_MODEL &&
        s.inputTokens > 0,
    ),
  ).toBe(true);
  const budget = page.locator('#tpm_pj_budget');
  await budget.click();
  await expect(budget).toHaveValue('128000.50');
  await budget.fill('132000.5');
  await expect(budget).toHaveAttribute('aria-valuenow', '132000.5');
  await budget.press('Tab');
  await expect(budget).toHaveValue('132,000.50');
  await page.screenshot({
    path: join(evidence, 'SC03-delivery-reviewed-form.png'),
    fullPage: false,
  });
  const savePromise = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/meta/commands/execute/tpm:create_project' &&
      r.request().method() === 'POST',
  );
  await page.getByTestId('form-btn-submit').click();
  const saved = await savePromise;
  expect(saved.status()).toBe(200);
  const body = await saved.json();
  expect(String(body.code)).toBe('0');
  expect(saved.request().postDataJSON().payload).toMatchObject({
    tpm_pj_name: name,
    tpm_pj_budget: 132000.5,
    tpm_pj_next_action: manual,
    tpm_pj_end_date: '2026-11-20',
  });
  expect(writes).toHaveLength(1);
  await expect(page).toHaveURL(/\/p\/tpm_project$/);
  await page.reload();
  const row = page.getByRole('row').filter({ hasText: name });
  await expect(row).toBeVisible();
  const record = await (
    await page.request.get(`/api/dynamic/tpm_project/${body.data.data.recordPid}`)
  ).json();
  expect(String(record.code)).toBe('0');
  expect(record.data).toMatchObject({
    tpm_pj_name: name,
    tpm_pj_budget: 132000.5,
    tpm_pj_next_action: manual,
    tpm_pj_status: 'planning',
  });
  expect(String(record.data.tpm_pj_end_date)).toContain('2026-11-20');
  await page.screenshot({
    path: join(evidence, 'SC03-delivery-saved-project.png'),
    fullPage: false,
  });
  await writeFile(
    join(evidence, 'SC03-delivery-intake-receipt.json'),
    JSON.stringify(
      {
        traceId: trace.traceId,
        provider: detail.trace.metadata.provider_code,
        model: process.env.PW_EXPECT_LIVE_MODEL,
        pid: record.data.pid,
        originalSource: source,
        expectedBudget: 132000.5,
        manualValuePreserved: true,
        unknownStartDateOmitted: true,
        referenceFieldsChosenByHuman: true,
      },
      null,
      2,
    ),
  );
});
