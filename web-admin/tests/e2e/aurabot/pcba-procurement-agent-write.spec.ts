/**
 * PCBA Procurement Agent write E2E
 *
 * Covers the browser-level L2 write path:
 * sidebar -> comparison list -> AuraBot -> PCBA Agent -> supplier query ->
 * confirmation card -> confirmed draft creation -> list search.
 *
 * Setup boundary: plugin import, upstream product/supplier/price fixtures,
 * Agent Definition seeding, and approval-policy seeding use API helpers. They
 * do not count as the covered UI write path. The covered business writes still
 * go through AuraBot in the browser, user confirmation, ToolLoopService, and
 * the approval UI.
 */

import { expect, test } from '../../fixtures';
import type { APIRequestContext, Page, Locator } from '@playwright/test';
import {
  clickRowActionByLocator,
  executeCommandViaApi,
  ensureSidebarExpanded,
  findRowInPaginatedList,
  queryFilteredList,
  waitForDynamicPageLoad,
  waitForTableHydration,
} from '../helpers';
import { gotoAcpUiPage } from '../agent-control-plane/route-helpers';

const PCBA_AGENT_CODE = 'pcba_procurement_comparison_agent';
const PCBA_AGENT_NAME = 'PCBA Procurement Advisor';
const COMPARISON_PATH = '/p/pe_procurement_comparison';
const STUB_TOOL_USE_MARKER = '@@AURABOOT_STUB_TOOL_USE@@';
const SUBMIT_REVIEW_TOOL = 'cmd_pe_submit_procurement_comparison';
const APPROVAL_POLICY_NAME = 'E2E PCBA Agent Review Approval';
const AURABOT_LAST_CONVERSATION_KEY = 'aurabot.lastConversationId';
const OSS_PLUGIN_ROOT = process.env.OSS_PLUGIN_ROOT ?? '/app/plugins';
const ENTERPRISE_PLUGIN_ROOT = process.env.ENTERPRISE_PLUGIN_ROOT ?? '/app/plugins-enterprise';

const REQUIRED_PLUGIN_IMPORTS = [
  { root: OSS_PLUGIN_ROOT, name: 'core-meta' },
  { root: OSS_PLUGIN_ROOT, name: 'core-bpm' },
  { root: OSS_PLUGIN_ROOT, name: 'core-announcement' },
  { root: OSS_PLUGIN_ROOT, name: 'core-aurabot' },
  { root: OSS_PLUGIN_ROOT, name: 'page-manager' },
  { root: OSS_PLUGIN_ROOT, name: 'platform-admin' },
  { root: OSS_PLUGIN_ROOT, name: 'org-management' },
  { root: OSS_PLUGIN_ROOT, name: 'agent-control-plane' },
  { root: ENTERPRISE_PLUGIN_ROOT, name: 'product-catalog' },
  { root: ENTERPRISE_PLUGIN_ROOT, name: 'crm' },
  { root: ENTERPRISE_PLUGIN_ROOT, name: 'sales' },
  { root: ENTERPRISE_PLUGIN_ROOT, name: 'inventory' },
  { root: ENTERPRISE_PLUGIN_ROOT, name: 'procurement' },
  { root: ENTERPRISE_PLUGIN_ROOT, name: 'finance' },
  { root: ENTERPRISE_PLUGIN_ROOT, name: 'quality' },
  { root: ENTERPRISE_PLUGIN_ROOT, name: 'pcba-base' },
  { root: ENTERPRISE_PLUGIN_ROOT, name: 'pcba-procurement' },
  { root: ENTERPRISE_PLUGIN_ROOT, name: 'pcba-solution' },
] as const;

type PcbaProcurementFixture = {
  productCode: string;
  productPid: string;
  supplierName: string;
  supplierPid: string;
};

async function recordsFrom(response: { data?: any; records?: unknown[] }) {
  return response?.data?.records || response?.data?.content || response?.records || [];
}

async function importPluginDirectory(
  request: APIRequestContext,
  plugin: { root: string; name: string },
) {
  const path = `${plugin.root}/${plugin.name}`;
  const response = await request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path,
      conflictStrategy: 'OVERWRITE',
      autoPublishModels: true,
      autoPublishFields: true,
      autoPublishCommands: true,
      autoPublishPages: true,
    },
    headers: { 'Content-Type': 'application/json' },
    timeout: 600000,
  });
  const body = await response.json().catch(() => ({}));
  const data = body?.data ?? body;
  const success = response.ok() && (data?.success === true || body?.success === true);
  expect(
    success,
    `${plugin.name} import should succeed from ${path}: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
}

async function executeRequiredCommand(
  page: Page,
  commandCode: string,
  payload: Record<string, unknown>,
) {
  const result = await executeCommandViaApi(
    page,
    commandCode,
    payload,
    undefined,
    'create',
    { allowHttpError: true, timeoutMs: 30000 },
  );
  expect(result.recordId, `${commandCode} should create a record`).toBeTruthy();
  return result.recordId;
}

function stubToolUse(name: string, input: Record<string, unknown>) {
  return `${STUB_TOOL_USE_MARKER} ${JSON.stringify({ name, input })}`;
}

async function seedPcbaProcurementFixture(page: Page): Promise<PcbaProcurementFixture> {
  const suffix = Date.now().toString(36).toUpperCase();
  const productCode = `PCBA-DEMO-RM-${suffix}`;
  const supplierName = `Shenzhen Precision Components ${suffix}`;

  const productPid = await executeRequiredCommand(page, 'prod:create_product', {
    prod_name: `STM32 MCU ${suffix}`,
    prod_spec: 'MSL 3, industrial temperature',
    prod_unit: 'pcs',
    prod_type: 'raw_material',
    prod_base_price: 8.5,
    prod_cost_price: 7.8,
    prod_currency: 'USD',
    prod_custom_code: productCode,
  });

  const supplierPid = await executeRequiredCommand(page, 'pe:create_supplier', {
    pe_supplier_name: supplierName,
    pe_supplier_contact: 'Wang Lei',
    pe_supplier_phone: '+86-755-5555-0101',
    pe_sup_level: 'strategic',
    pe_sup_lead_time_days: 14,
    pe_sup_payment_terms: 'Net 45',
  });

  await executeRequiredCommand(page, 'pe:create_supplier_price', {
    pe_sp_supplier_id: supplierPid,
    pe_sp_product_id: productPid,
    pe_sp_unit_price: 8.75,
    pe_sp_currency: 'USD',
    pe_sp_min_qty: 1000,
    pe_sp_lead_time_days: 12,
    pe_sp_valid_from: '2026-04-01',
    pe_sp_valid_to: '2026-12-31',
    pe_sp_remark: `PCBA agent E2E supplier price for ${suffix}`,
  });

  return { productCode, productPid, supplierName, supplierPid };
}

async function ensurePcbaProcurementAgent(page: Page) {
  const list = await page.request.get(
    `/api/dynamic/agent-definition/list?pageNum=1&pageSize=20&keyword=${PCBA_AGENT_CODE}`,
  );
  expect(list.ok(), 'Agent Definition list API should be available').toBe(true);
  const listBody = await list.json();
  const existing = (await recordsFrom(listBody)).find(
    (record: any) => record.agent_code === PCBA_AGENT_CODE,
  );
  if (existing) return;

  const create = await page.request.post('/api/dynamic/agent-definition/create', {
    data: {
      agent_code: PCBA_AGENT_CODE,
      name: PCBA_AGENT_NAME,
      description: 'Procurement comparison agent for PCBA supplier evidence.',
      agent_type: 'reactive',
      model: 'MiniMax-M2.5',
      system_prompt:
        'You are the PCBA Procurement Advisor. First query pe_procurement_comparison_supplier_options. Always include product_id and supplier_id. For cmd_pe_create_procurement_comparison_draft, use PID values for pe_pc_product_id and pe_pc_recommended_supplier_id, and set a non-empty pe_pc_code requested by the user.',
      tools: JSON.stringify([
        'nq:pe_procurement_comparison_supplier_options',
        'cmd:pe:create_procurement_comparison_draft',
        'cmd:pe:update_procurement_comparison_draft',
        'cmd:pe:submit_procurement_comparison',
      ]),
      skills: JSON.stringify(['dsl.query', 'dsl.command']),
      guardrails: JSON.stringify({
        fallbackProviders: ['minimaxi', 'openai', 'anthropic'],
        maxCostPerRun: 0.5,
        evidenceFirst: true,
      }),
      status: 'active',
      personality: 'A procurement analyst focused on traceable supplier comparison evidence.',
      expertise: 'PCBA procurement, supplier comparison, price and lead-time evidence',
      communication_style: 'professional',
      boundaries:
        'Do not fabricate supplier evidence.\nDo not execute L2 write commands without user confirmation.\nDo not execute L3 review submission without human approval.',
      soul_goals: 'Produce evidence-first procurement comparison drafts for human review.',
    },
  });
  expect(create.ok(), 'PCBA procurement Agent Definition should be seedable').toBe(true);
}

async function ensurePcbaReviewApprovalPolicy(page: Page) {
  const payload = {
    policy_name: APPROVAL_POLICY_NAME,
    description: 'E2E policy for PCBA Agent procurement comparison review submission.',
    trigger_rules: JSON.stringify([{ type: 'tool_call', pattern: SUBMIT_REVIEW_TOOL }]),
    approver_rules: JSON.stringify([{ type: 'role', roleCode: 'tenant_admin' }]),
    policy_status: 'active',
    timeout_hours: 24,
    timeout_action: 'reject',
    auto_approve: false,
  };
  const existing = await queryFilteredList(
    page,
    'approval-policy',
    'policy_name',
    APPROVAL_POLICY_NAME,
    { operator: 'EQ', pageSize: 5 },
  );
  const policyPid = existing[0]?.pid ? String(existing[0].pid) : '';
  if (policyPid) {
    await executeCommandViaApi(page, 'acp:update_approval_policy', payload, policyPid, 'update');
    return;
  }
  const created = await executeCommandViaApi(
    page,
    'acp:create_approval_policy',
    payload,
    undefined,
    'create',
  );
  expect(created.recordId, 'PCBA Agent review approval policy should be seedable').toBeTruthy();
}

async function createProcurementComparisonDraft(
  page: Page,
  comparisonCode: string,
  fixture: PcbaProcurementFixture,
) {
  const result = await executeCommandViaApi(
    page,
    'pe:create_procurement_comparison_draft',
    {
      pe_pc_code: comparisonCode,
      pe_pc_product_id: fixture.productPid,
      pe_pc_required_qty: 1200,
      pe_pc_need_date: '2026-05-30',
      pe_pc_recommended_supplier_id: fixture.supplierPid,
      pe_pc_recommended_price: 8.75,
      pe_pc_recommended_lead_time_days: 12,
      pe_pc_supplier_score: 92,
      pe_pc_evidence_summary: 'E2E fixture: supplier quote, lead time, and score evidence.',
      pe_pc_recommendation: `Recommend ${fixture.supplierName} for human review.`,
      pe_pc_risk_notes: 'Requires L3 approval before downstream purchase-order action.',
      pe_pc_source_run_id: `e2e-${comparisonCode}`,
    },
    undefined,
    'create',
  );
  expect(result.recordId, 'Procurement comparison draft should be created').toBeTruthy();
  return result.recordId;
}

async function expandIfNeeded(page: Page, label: RegExp, targetHref: string) {
  const nav = page.locator('nav').first();
  const target = nav.locator(`a[href="${targetHref}"]`).first();
  if (await target.isVisible({ timeout: 800 }).catch(() => false)) return;

  const trigger = nav
    .getByRole('button', { name: label })
    .or(nav.getByRole('link', { name: label }))
    .first();
  await expect(trigger).toBeVisible({ timeout: 10000 });
  await trigger.scrollIntoViewIfNeeded();
  await trigger.click().catch(async () => {
    await trigger.evaluate((el) => (el as HTMLElement).click());
  });
  await page.waitForLoadState('domcontentloaded').catch(() => {});
}

async function navigateToComparisonViaSidebar(page: Page) {
  await page.addInitScript((key) => window.localStorage.removeItem(key), AURABOT_LAST_CONVERSATION_KEY);
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await page.evaluate((key) => window.localStorage.removeItem(key), AURABOT_LAST_CONVERSATION_KEY);
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav').first();
  await expect(nav).toBeVisible({ timeout: 10000 });
  await expandIfNeeded(page, /PCBA ERP/, '/pcba-erp/procurement');
  await expandIfNeeded(page, /采购执行|Procurement/, '/pcba-erp/srm');
  await expandIfNeeded(page, /供应商管理|Supplier Management/, COMPARISON_PATH);

  const comparisonLink = nav.locator(`a[href="${COMPARISON_PATH}"]`).first();
  await expect(comparisonLink).toBeVisible({ timeout: 10000 });
  await comparisonLink.evaluate((el) => (el as HTMLAnchorElement).click());
  await page.waitForURL((url) => url.pathname === COMPARISON_PATH, { timeout: 15000 });
  await waitForDynamicPageLoad(page);
}

async function openAuraBotPanel(page: Page) {
  const panel = page.locator('[data-testid="aurabot-panel"]');
  if (!(await panel.isVisible({ timeout: 1000 }).catch(() => false))) {
    const toggle = page.locator('[data-testid="ai-panel-toggle"]');
    await expect(toggle).toBeVisible({ timeout: 10000 });
    await toggle.click();
  }
  await expect(panel).toBeVisible({ timeout: 10000 });
  const historyTrigger = panel.getByTestId('aurabot-history-trigger');
  if (await historyTrigger.isVisible({ timeout: 3000 }).catch(() => false)) {
    await historyTrigger.click();
    const newSessionBtn = panel.getByTestId('aurabot-new-session');
    await expect(newSessionBtn).toBeVisible({ timeout: 5000 });
    await newSessionBtn.click();
    await expect(panel.locator('textarea')).toBeVisible({ timeout: 5000 });
  }
  await page.evaluate((key) => window.localStorage.removeItem(key), AURABOT_LAST_CONVERSATION_KEY);
  return panel;
}

async function selectPcbaAgent(panel: Locator) {
  await panel.getByTestId('agent-selector-trigger').click();
  const dropdown = panel.getByTestId('agent-selector-dropdown');
  await expect(dropdown).toBeVisible({ timeout: 10000 });
  await dropdown.getByText(PCBA_AGENT_NAME).click();
  await expect(panel.getByTestId('agent-selector-trigger')).toContainText(PCBA_AGENT_NAME);
}

async function sendAuraBotMessage(panel: Locator, message: string) {
  const input = panel.locator('textarea').first();
  await expect(input).toBeEnabled({ timeout: 150000 });
  await input.fill(message);
  await panel.getByTestId('aurabot-send').click();
}

async function searchListForCode(page: Page, comparisonCode: string) {
  const input = page.getByTestId('list-search-input');
  await expect(input).toBeVisible({ timeout: 10000 });

  const listResponse = page
    .waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/pe_procurement_comparison/list') &&
        response.status() === 200,
      { timeout: 15000 },
    )
    .catch(() => null);
  await input.fill(comparisonCode);
  await input.press('Enter');
  await listResponse;
  await waitForTableHydration(page);

  const row = page.locator('tbody tr', { hasText: comparisonCode }).first();
  await expect(row).toBeVisible({ timeout: 15000 });
}

async function fetchComparisonByPid(page: Page, comparisonPid: string) {
  const records = await queryFilteredList(
    page,
    'pe_procurement_comparison',
    'pid',
    comparisonPid,
    { operator: 'EQ', pageSize: 1 },
  );
  return records[0] ?? null;
}

function approvalRecordContainsPid(record: Record<string, unknown>, comparisonPid: string) {
  const requestData = record.request_data;
  if (typeof requestData === 'string') {
    return requestData.includes(comparisonPid);
  }
  return JSON.stringify(requestData ?? {}).includes(comparisonPid);
}

async function waitForApprovalForComparison(page: Page, comparisonPid: string) {
  let matched: Record<string, unknown> | null = null;
  await expect
    .poll(
      async () => {
        const records = await queryFilteredList(
          page,
          'agent-approval',
          'approval_description',
          `Tool: ${SUBMIT_REVIEW_TOOL}`,
          {
            operator: 'EQ',
            pageSize: 50,
            extraFilters: [{ fieldName: 'approval_status', operator: 'EQ', value: 'pending' }],
          },
        );
        matched =
          records.find((record) => approvalRecordContainsPid(record, comparisonPid)) ?? null;
        return matched?.pid ? String(matched.pid) : '';
      },
      { timeout: 30000, intervals: [500, 1000, 2000] },
    )
    .not.toBe('');
  return matched!;
}

async function waitForComparisonStatus(page: Page, comparisonPid: string, status: string) {
  await expect
    .poll(
      async () => {
        const record = await fetchComparisonByPid(page, comparisonPid);
        return String(record?.pe_pc_status ?? '');
      },
      { timeout: 30000, intervals: [500, 1000, 2000] },
    )
    .toBe(status);
}

async function waitForApprovalStatus(page: Page, approvalPid: string, status: string) {
  await expect
    .poll(
      async () => {
        const records = await queryFilteredList(
          page,
          'agent-approval',
          'pid',
          approvalPid,
          { operator: 'EQ', pageSize: 1 },
        );
        return String(records[0]?.approval_status ?? '');
      },
      { timeout: 30000, intervals: [500, 1000, 2000] },
    )
    .toBe(status);
}

async function requestReviewApprovalViaAuraBot(page: Page, comparisonCode: string, comparisonPid: string) {
  await navigateToComparisonViaSidebar(page);
  const panel = await openAuraBotPanel(page);
  await selectPcbaAgent(panel);

  await sendAuraBotMessage(
    panel,
    `请将采购比价草稿 ${comparisonCode} 提交人工复核。必须调用工具 ${SUBMIT_REVIEW_TOOL}，工具参数必须严格使用 {"recordId":"${comparisonPid}"}。不要创建新草稿，不要修改 recordId。\n${stubToolUse(
      SUBMIT_REVIEW_TOOL,
      { recordId: comparisonPid },
    )}`,
  );
  await expect(panel).toContainText(SUBMIT_REVIEW_TOOL, { timeout: 120000 });
  await expect(panel).toContainText(/approval|审批/i, { timeout: 120000 });

  const approval = await waitForApprovalForComparison(page, comparisonPid);
  await panel.getByTitle('Close (⌘J)').click();
  await expect(panel).not.toBeVisible({ timeout: 5000 });
  return approval;
}

async function actOnApprovalViaUi(
  page: Page,
  approval: Record<string, unknown>,
  actionCode: 'approve' | 'reject',
) {
  const title = String(approval.approval_title ?? 'Agent requests approval');
  await gotoAcpUiPage(page, '/dynamic/agent-approval');
  await waitForDynamicPageLoad(page);
  const row = await findRowInPaginatedList(page, title, 15000);
  await expect(row).toContainText('pending', { timeout: 10000 });

  const responsePromise = page.waitForResponse(
    (response) => {
      const url = decodeURIComponent(response.url());
      return url.includes(`/api/meta/commands/execute/acp:${actionCode}_request`);
    },
    { timeout: 30000 },
  );
  await clickRowActionByLocator(page, row, actionCode, actionCode);
  const confirmBtn = page
    .locator(
      '[data-testid="confirm-ok"], .ant-modal-confirm-btns button.ant-btn-primary, button:has-text("确认"), button:has-text("确定")',
    )
    .first();
  if (await confirmBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
    await confirmBtn.click();
  }
  const response = await responsePromise;
  const body = await response.text().catch(() => '');
  expect(response.status(), `approval command response: ${body}`).toBe(200);
}

test.describe('PCBA Procurement Agent write flow', () => {
  test.describe.configure({ mode: 'serial', timeout: 180000 });

  test.beforeAll(async ({ request }, testInfo) => {
    testInfo.setTimeout(600000);
    for (const plugin of REQUIRED_PLUGIN_IMPORTS) {
      await importPluginDirectory(request, plugin);
    }
  });

  test('creates a procurement comparison draft after browser confirmation @critical', async ({
    page,
  }) => {
    const comparisonCode = `E2E-PCBA-CMP-${Date.now().toString(36).toUpperCase()}`;
    const fixture = await seedPcbaProcurementFixture(page);
    await ensurePcbaProcurementAgent(page);

    await navigateToComparisonViaSidebar(page);
    const panel = await openAuraBotPanel(page);
    await selectPcbaAgent(panel);

    await sendAuraBotMessage(
      panel,
      `必须调用工具 nq_pe_procurement_comparison_supplier_options 查询供应商报价。工具参数必须严格使用 {"productId":"${fixture.productPid}"}，不得改写 productId。产品编码仅用于展示：${fixture.productCode}。需求数量 1200，需求日期 2026-05-30。查询后给出推荐，并在表格中保留 product_id 和 supplier_id。\n${stubToolUse(
        'nq_pe_procurement_comparison_supplier_options',
        { productId: fixture.productPid },
      )}`,
    );
    await expect(panel).toContainText('nq_pe_procurement_comparison_supplier_options', {
      timeout: 120000,
    });
    await expect(panel).toContainText(fixture.supplierName, { timeout: 120000 });

    await sendAuraBotMessage(
      panel,
      `确认创建采购比价草稿。请使用上一轮推荐供应商 ${fixture.supplierName}，pe_pc_code 必须设置为 ${comparisonCode}，pe_pc_product_id 必须使用 ${fixture.productPid}，pe_pc_recommended_supplier_id 必须使用 ${fixture.supplierPid}，数量 1200，需求日期 2026-05-30。\n${stubToolUse(
        'cmd_pe_create_procurement_comparison_draft',
        {
          pe_pc_code: comparisonCode,
          pe_pc_product_id: fixture.productPid,
          pe_pc_required_qty: 1200,
          pe_pc_need_date: '2026-05-30',
          pe_pc_recommended_supplier_id: fixture.supplierPid,
          pe_pc_recommended_price: 8.75,
          pe_pc_recommended_lead_time_days: 12,
          pe_pc_supplier_score: 92,
          pe_pc_evidence_summary:
            'E2E fixture: supplier quote, lead time, and score evidence.',
          pe_pc_recommendation: `Recommend ${fixture.supplierName} for human review.`,
          pe_pc_risk_notes: 'Requires L3 approval before downstream purchase-order action.',
          pe_pc_source_run_id: `e2e-${comparisonCode}`,
        },
      )}`,
    );
    await expect(panel).toContainText('cmd_pe_create_procurement_comparison_draft', {
      timeout: 120000,
    });
    await expect(panel).toContainText(comparisonCode, { timeout: 120000 });
    await expect(panel).toContainText(fixture.productPid, { timeout: 120000 });
    await expect(panel).toContainText(fixture.supplierPid, { timeout: 120000 });

    const executeResponsePromise = page.waitForResponse(
      (response) => response.url().includes('/api/ai/aurabot/execute'),
      { timeout: 120000 },
    );
    await panel.getByRole('button', { name: 'Confirm' }).click();
    const executeResponse = await executeResponsePromise;
    const executeText = await executeResponse.text().catch(() => '');
    expect(executeResponse.status(), `execute response body: ${executeText}`).toBe(200);

    const createdRecords = await queryFilteredList(
      page,
      'pe_procurement_comparison',
      'pe_pc_code',
      comparisonCode,
      { operator: 'EQ', pageSize: 10 },
    );
    expect(createdRecords).toHaveLength(1);
    expect(createdRecords[0]).toMatchObject({
      pe_pc_code: comparisonCode,
      pe_pc_product_id: fixture.productPid,
      pe_pc_recommended_supplier_id: fixture.supplierPid,
      pe_pc_status: 'draft',
    });

    await panel.getByTitle('Close (⌘J)').click();
    await expect(panel).not.toBeVisible({ timeout: 5000 });
    await searchListForCode(page, comparisonCode);
  });

  test('submits a draft to L3 approval and applies review status after UI approval @critical', async ({
    page,
  }) => {
    const comparisonCode = `E2E-PCBA-L3-OK-${Date.now().toString(36).toUpperCase()}`;
    const fixture = await seedPcbaProcurementFixture(page);
    await ensurePcbaProcurementAgent(page);
    await ensurePcbaReviewApprovalPolicy(page);
    const comparisonPid = await createProcurementComparisonDraft(page, comparisonCode, fixture);

    const approval = await requestReviewApprovalViaAuraBot(page, comparisonCode, comparisonPid);
    await waitForComparisonStatus(page, comparisonPid, 'draft');

    await actOnApprovalViaUi(page, approval, 'approve');

    await waitForApprovalStatus(page, String(approval.pid), 'approved');
    await waitForComparisonStatus(page, comparisonPid, 'review_required');
  });

  test('keeps the draft unchanged when the L3 approval is rejected in the UI @critical', async ({
    page,
  }) => {
    const comparisonCode = `E2E-PCBA-L3-NG-${Date.now().toString(36).toUpperCase()}`;
    const fixture = await seedPcbaProcurementFixture(page);
    await ensurePcbaProcurementAgent(page);
    await ensurePcbaReviewApprovalPolicy(page);
    const comparisonPid = await createProcurementComparisonDraft(page, comparisonCode, fixture);

    const approval = await requestReviewApprovalViaAuraBot(page, comparisonCode, comparisonPid);
    await waitForComparisonStatus(page, comparisonPid, 'draft');

    await actOnApprovalViaUi(page, approval, 'reject');

    await waitForApprovalStatus(page, String(approval.pid), 'rejected');
    await waitForComparisonStatus(page, comparisonPid, 'draft');
  });
});
