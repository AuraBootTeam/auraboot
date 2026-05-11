/**
 * PCBA Quality Agent entry E2E
 *
 * Verifies the UI entry points for AI-005:
 * sidebar -> quality defect list -> AuraBot suggestions -> Agent selector.
 */

import { expect, test } from '../../fixtures';
import type { Page } from '@playwright/test';
import { ensureSidebarExpanded, waitForDynamicPageLoad } from '../helpers';

const PCBA_QUALITY_AGENT_CODE = 'pcba_quality_anomaly_agent';
const PCBA_QUALITY_AGENT_NAME = 'PCBA Quality Anomaly Analyst';
const DEFECTS_PATH = '/quality/defects';

async function recordsFrom(response: { data?: any; records?: unknown[] }) {
  return response?.data?.records || response?.data?.content || response?.records || [];
}

async function ensurePcbaQualityAgent(page: Page) {
  const list = await page.request.get(
    `/api/dynamic/agent-definition/list?pageNum=1&pageSize=20&keyword=${PCBA_QUALITY_AGENT_CODE}`,
  );
  expect(list.ok(), 'Agent Definition list API should be available').toBe(true);
  const listBody = await list.json();
  const existing = (await recordsFrom(listBody)).find(
    (record: any) => record.agent_code === PCBA_QUALITY_AGENT_CODE,
  );
  if (existing) return;

  const create = await page.request.post('/api/dynamic/agent-definition/create', {
    data: {
      agent_code: PCBA_QUALITY_AGENT_CODE,
      name: PCBA_QUALITY_AGENT_NAME,
      description: 'Quality anomaly analysis agent for PCBA defect trends and CAPA drafts.',
      agent_type: 'reactive',
      model: 'MiniMax-M2.5',
      system_prompt:
        'Use qc_quality_anomaly_trend, qc_quality_batch_correlation, and qc_quality_capa_context before proposing any CAPA draft. Create CAPA only after explicit user confirmation. Do not release, reject, pass, fail, close, or dispose quality records.',
      tools: JSON.stringify([
        'nq:qc_quality_anomaly_trend',
        'nq:qc_quality_batch_correlation',
        'nq:qc_quality_capa_context',
        'cmd:qc:create_capa',
      ]),
      skills: JSON.stringify(['dsl.query', 'dsl.command']),
      guardrails: JSON.stringify({
        fallbackProviders: ['minimaxi', 'openai', 'anthropic'],
        maxCostPerRun: 0.5,
        evidenceFirst: true,
        writePolicy:
          'L2 CAPA draft creation requires explicit user confirmation; release/reject actions are outside this agent.',
      }),
      status: 'active',
      personality: 'A PCBA quality engineer focused on traceable anomaly evidence.',
      expertise: 'PCBA quality defects, batch traceability, SPC, NCR, CAPA drafting',
      communication_style: 'professional',
      boundaries:
        'Do not fabricate quality evidence.\nDo not create CAPA drafts without user confirmation.\nDo not execute release, reject, pass, fail, close, rework, shipment, or NCR disposition actions.',
      soul_goals: 'Produce evidence-first quality anomaly analysis and human-confirmed CAPA drafts.',
    },
  });
  expect(create.ok(), 'PCBA quality Agent Definition should be seedable').toBe(true);
}

async function navigateToDefectsViaSidebar(page: Page) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav').first();
  await expect(nav).toBeVisible({ timeout: 10000 });

  const root = nav
    .getByRole('button', { name: /质量管理|Quality/ })
    .or(nav.getByRole('link', { name: /质量管理|Quality/ }))
    .first();
  await expect(root).toBeVisible({ timeout: 10000 });
  await root.evaluate((el) => (el as HTMLElement).click());
  await page.waitForLoadState('domcontentloaded').catch(() => {});

  const defectsLink = nav.locator(`a[href="${DEFECTS_PATH}"]`).first();
  await expect(defectsLink).toBeVisible({ timeout: 10000 });
  await defectsLink.evaluate((el) => (el as HTMLAnchorElement).click());
  await page.waitForURL((url) => url.pathname === DEFECTS_PATH, { timeout: 15000 });
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

test.describe('PCBA Quality Agent entry', () => {
  test.describe.configure({ timeout: 60000 });

  test('shows quality anomaly suggestion and seeded Agent in AuraBot panel @smoke', async ({
    page,
  }) => {
    await ensurePcbaQualityAgent(page);
    await navigateToDefectsViaSidebar(page);

    const agentListResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/agent-definition/list') && response.status() === 200,
      { timeout: 15000 },
    );
    const panel = await openAuraBotPanel(page);
    await agentListResponse;

    await expect(panel.getByText('生成质量异常分析')).toBeVisible({ timeout: 10000 });

    await panel.getByTestId('agent-selector-trigger').click();
    await expect(panel.getByTestId('agent-selector-dropdown')).toBeVisible({ timeout: 10000 });
    await expect(panel.getByText(PCBA_QUALITY_AGENT_NAME)).toBeVisible({ timeout: 10000 });
  });
});
