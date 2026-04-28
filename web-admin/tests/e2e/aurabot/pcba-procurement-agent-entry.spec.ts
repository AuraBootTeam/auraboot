/**
 * PCBA Procurement Agent entry E2E
 *
 * Verifies the UI entry points needed before the full write/approval flow:
 * sidebar -> procurement comparison list -> AuraBot suggestions -> Agent selector.
 */

import { expect, test } from '../../fixtures';
import type { Page } from '@playwright/test';
import { ensureSidebarExpanded, waitForDynamicPageLoad } from '../helpers';

const PCBA_AGENT_CODE = 'pcba_procurement_comparison_agent';
const PCBA_AGENT_NAME = 'PCBA Procurement Advisor';
const COMPARISON_PATH = '/p/pe_procurement_comparison';

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
        'Use verified supplier price, lead-time, qualification, and evaluation-score evidence before proposing a procurement comparison draft.',
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
  await trigger.click();
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

test.describe('PCBA Procurement Agent entry', () => {
  test.describe.configure({ timeout: 60000 });

  test('shows PCBA comparison suggestion and seeded Agent in AuraBot panel @smoke', async ({
    page,
  }) => {
    await ensurePcbaProcurementAgent(page);
    await navigateToComparisonViaSidebar(page);

    const agentListResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/agent-definition/list') && response.status() === 200,
      { timeout: 15000 },
    );
    const panel = await openAuraBotPanel(page);
    await agentListResponse;

    await expect(panel.getByText('生成供应商比价建议')).toBeVisible({ timeout: 10000 });

    await panel.getByTestId('agent-selector-trigger').click();
    await expect(panel.getByTestId('agent-selector-dropdown')).toBeVisible({ timeout: 10000 });
    await expect(panel.getByText(PCBA_AGENT_NAME)).toBeVisible({ timeout: 10000 });
  });
});
