/**
 * PCBA Procurement Agent write E2E
 *
 * Covers the browser-level L2 write path:
 * sidebar -> comparison list -> AuraBot -> PCBA Agent -> supplier query ->
 * confirmation card -> confirmed draft creation -> list search.
 */

import { expect, test } from '../../fixtures';
import type { Page, Locator } from '@playwright/test';
import {
  ensureSidebarExpanded,
  queryFilteredList,
  waitForDynamicPageLoad,
  waitForTableHydration,
} from '../helpers';

const PCBA_AGENT_CODE = 'pcba_procurement_comparison_agent';
const PCBA_AGENT_NAME = 'PCBA Procurement Advisor';
const COMPARISON_PATH = '/p/pe_procurement_comparison';
const PRODUCT_CODE = 'PCBA-DEMO-RM-001';
const PRODUCT_PID = '01KQ69DZCXDXZG830QK09ZCQV1';
const SUPPLIER_PID = '01KQ69DZJS4XQH0QY6BBDQ6CEH';

async function recordsFrom(response: { data?: any; records?: unknown[] }) {
  return response?.data?.records || response?.data?.content || response?.records || [];
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
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
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

test.describe('PCBA Procurement Agent write flow', () => {
  test.describe.configure({ timeout: 180000 });

  test('creates a procurement comparison draft after browser confirmation @critical', async ({
    page,
  }) => {
    const comparisonCode = `E2E-PCBA-CMP-${Date.now().toString(36).toUpperCase()}`;
    await ensurePcbaProcurementAgent(page);

    await navigateToComparisonViaSidebar(page);
    const panel = await openAuraBotPanel(page);
    await selectPcbaAgent(panel);

    await sendAuraBotMessage(
      panel,
      `必须调用 pe_procurement_comparison_supplier_options 查询产品 ${PRODUCT_CODE} 的供应商报价。需求数量 1200，需求日期 2026-05-30。查询后给出推荐，并在表格中保留 product_id 和 supplier_id。`,
    );
    await expect(panel.getByText('Data Query')).toBeVisible({ timeout: 120000 });
    await expect(panel).toContainText('Shenzhen Precision Components', { timeout: 120000 });

    await sendAuraBotMessage(
      panel,
      `确认创建采购比价草稿。请使用上一轮推荐供应商 Shenzhen Precision Components，pe_pc_code 必须设置为 ${comparisonCode}，pe_pc_product_id 必须使用 ${PRODUCT_PID}，pe_pc_recommended_supplier_id 必须使用 ${SUPPLIER_PID}，数量 1200，需求日期 2026-05-30。`,
    );
    await expect(panel).toContainText('cmd_pe_create_procurement_comparison_draft', {
      timeout: 120000,
    });
    await expect(panel).toContainText(comparisonCode, { timeout: 120000 });
    await expect(panel).toContainText(PRODUCT_PID, { timeout: 120000 });
    await expect(panel).toContainText(SUPPLIER_PID, { timeout: 120000 });

    const executeResponse = page.waitForResponse(
      (response) => response.url().includes('/api/ai/aurabot/execute') && response.status() === 200,
      { timeout: 120000 },
    );
    await panel.getByRole('button', { name: 'Confirm' }).click();
    const executeText = await (await executeResponse).text();
    expect(executeText).toContain('pe_procurement_comparison_inserted');
    expect(executeText).toContain('recordId');

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
      pe_pc_product_id: PRODUCT_PID,
      pe_pc_recommended_supplier_id: SUPPLIER_PID,
      pe_pc_status: 'draft',
    });

    await panel.getByTitle('Close (⌘J)').click();
    await expect(panel).not.toBeVisible({ timeout: 5000 });
    await searchListForCode(page, comparisonCode);
  });
});
