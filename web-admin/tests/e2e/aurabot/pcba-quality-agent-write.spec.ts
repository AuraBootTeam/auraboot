/**
 * PCBA Quality Agent write E2E
 *
 * Covers the browser-level L2 write path:
 * sidebar -> defect list -> AuraBot -> PCBA Quality Agent -> anomaly/context
 * query -> confirmation card -> confirmed CAPA creation -> CAPA list search.
 *
 * Setup boundary: plugin import, upstream product/defect fixtures, and Agent
 * Definition seeding use API helpers. The covered business write still goes
 * through AuraBot in the browser, user confirmation, and ToolLoopService.
 */

import { expect, test } from '../../fixtures';
import type { APIRequestContext, Locator, Page } from '@playwright/test';
import {
  ensureSidebarExpanded,
  executeCommandViaApi,
  queryFilteredList,
  waitForDynamicPageLoad,
  waitForTableHydration,
} from '../helpers';

const E2E_AGENT_SUFFIX = `${Date.now().toString(36)}_${Math.random()
  .toString(36)
  .slice(2, 8)}`;
const PCBA_QUALITY_AGENT_CODE = `pcba_quality_anomaly_agent_e2e_${E2E_AGENT_SUFFIX}`;
const PCBA_QUALITY_AGENT_NAME = `PCBA Quality Anomaly Analyst E2E ${E2E_AGENT_SUFFIX}`;
const DEFECTS_PATH = '/quality/defects';
const CAPA_PATH = '/quality/capa';
const STUB_TOOL_USE_MARKER = '@@AURABOOT_STUB_TOOL_USE@@';
const AURABOT_LAST_CONVERSATION_KEY = 'aurabot.lastConversationId';
const OSS_PLUGIN_ROOT = process.env.OSS_PLUGIN_ROOT ?? '/app/plugins';
const ENTERPRISE_PLUGIN_ROOT = process.env.ENTERPRISE_PLUGIN_ROOT ?? '/app/plugins-enterprise';

const REQUIRED_PLUGIN_IMPORTS = [
  { root: OSS_PLUGIN_ROOT, name: 'core-meta', pluginId: 'com.auraboot.core-meta' },
  { root: OSS_PLUGIN_ROOT, name: 'core-bpm', pluginId: 'com.auraboot.core-bpm' },
  { root: OSS_PLUGIN_ROOT, name: 'core-announcement', pluginId: 'com.auraboot.core-announcement' },
  { root: OSS_PLUGIN_ROOT, name: 'core-aurabot', pluginId: 'com.auraboot.core-aurabot' },
  { root: OSS_PLUGIN_ROOT, name: 'page-manager', pluginId: 'com.auraboot.page-manager' },
  { root: OSS_PLUGIN_ROOT, name: 'platform-admin', pluginId: 'com.auraboot.platform-admin' },
  { root: OSS_PLUGIN_ROOT, name: 'org-management', pluginId: 'com.auraboot.org-management' },
  {
    root: OSS_PLUGIN_ROOT,
    name: 'agent-control-plane',
    pluginId: 'com.auraboot.agent-control-plane',
  },
  {
    root: ENTERPRISE_PLUGIN_ROOT,
    name: 'product-catalog',
    pluginId: 'com.auraboot.product-catalog',
    force: true,
  },
  {
    root: ENTERPRISE_PLUGIN_ROOT,
    name: 'inventory',
    pluginId: 'com.auraboot.inventory',
    force: true,
  },
  {
    root: ENTERPRISE_PLUGIN_ROOT,
    name: 'quality',
    pluginId: 'com.auraboot.quality',
    force: true,
  },
  { root: ENTERPRISE_PLUGIN_ROOT, name: 'pcba-base', pluginId: 'com.auraboot.pcba-base' },
] as const;

type PcbaQualityFixture = {
  batchNo: string;
  defectPid: string;
  productCode: string;
  productPid: string;
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

async function fetchInstalledPluginIds(request: APIRequestContext) {
  const response = await request.get('/api/plugins');
  if (!response.ok()) return new Set<string>();
  const body = await response.json().catch(() => ({}));
  const records = (body?.data ?? []) as Array<{ pluginId?: string; plugin_id?: string }>;
  return new Set(
    records
      .map((record) => String(record.pluginId ?? record.plugin_id ?? ''))
      .filter((pluginId) => pluginId.length > 0),
  );
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

function pcbaQualityAgentRuntimePayload() {
  return {
    agent_code: PCBA_QUALITY_AGENT_CODE,
    name: PCBA_QUALITY_AGENT_NAME,
    description: 'Quality anomaly analysis agent for PCBA defect trends and CAPA drafts.',
    agent_type: 'reactive',
    model: 'stub-model',
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
      provider: 'stub',
      fallbackProviders: [],
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
  };
}

async function seedPcbaQualityFixture(page: Page): Promise<PcbaQualityFixture> {
  const suffix = Date.now().toString(36).toUpperCase();
  const batchNo = `PCBA-QA-BATCH-${suffix}`;
  const productCode = `PCBA-QA-ASM-${suffix}`;

  const productPid = await executeRequiredCommand(page, 'prod:create_product', {
    prod_name: `PCBA Control Board ${suffix}`,
    prod_spec: '4-layer FR4, AOI required',
    prod_unit: 'pcs',
    prod_type: 'finished',
    prod_base_price: 38,
    prod_cost_price: 27,
    prod_currency: 'USD',
    prod_custom_code: productCode,
  });

  const defectPid = await executeRequiredCommand(page, 'qc:create_defect_record', {
    qc_dr_source_type: 'pqc',
    qc_dr_source_id: `PQC-${suffix}`,
    qc_dr_product_id: productPid,
    qc_dr_batch_no: batchNo,
    qc_dr_defect_type: 'solder_bridge',
    qc_dr_location: 'U12 pin 7-8',
    qc_dr_severity: 'critical',
    qc_dr_root_cause: 'Insufficient solder paste release control on stencil aperture.',
    qc_dr_corrective_action: 'Review stencil aperture and add first-article AOI checkpoint.',
    qc_dr_remark: `PCBA Quality Agent E2E defect ${suffix}`,
  });

  return { batchNo, defectPid, productCode, productPid };
}

async function ensurePcbaQualityAgent(page: Page) {
  const payload = pcbaQualityAgentRuntimePayload();
  const list = await page.request.get(
    `/api/dynamic/agent-definition/list?pageNum=1&pageSize=20&keyword=${PCBA_QUALITY_AGENT_CODE}`,
  );
  expect(list.ok(), 'Agent Definition list API should be available').toBe(true);
  const listBody = await list.json();
  const existing = (await recordsFrom(listBody)).find(
    (record: any) => record.agent_code === PCBA_QUALITY_AGENT_CODE,
  );
  if (existing) {
    const recordId = existing.pid ?? existing.id;
    expect(recordId, 'Existing PCBA quality Agent Definition should expose id or pid').toBeTruthy();
    const update = await page.request.put(`/api/dynamic/agent-definition/${recordId}`, {
      data: payload,
    });
    const updateText = await update.text().catch(() => '');
    expect(update.ok(), `PCBA quality Agent Definition should be set to stub runtime: ${updateText}`).toBe(true);
    return;
  }

  const create = await page.request.post('/api/dynamic/agent-definition/create', {
    data: payload,
  });
  expect(create.ok(), 'PCBA quality Agent Definition should be seedable').toBe(true);
}

async function navigateToQualityPath(page: Page, targetPath: string) {
  await page.addInitScript((key) => window.localStorage.removeItem(key), AURABOT_LAST_CONVERSATION_KEY);
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await page.evaluate((key) => window.localStorage.removeItem(key), AURABOT_LAST_CONVERSATION_KEY);
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav').first();
  await expect(nav).toBeVisible({ timeout: 10000 });

  const target = nav.locator(`a[href="${targetPath}"]`).first();
  if (!(await target.isVisible({ timeout: 800 }).catch(() => false))) {
    const root = nav
      .getByRole('button', { name: /质量管理|Quality/ })
      .or(nav.getByRole('link', { name: /质量管理|Quality/ }))
      .first();
    await expect(root).toBeVisible({ timeout: 10000 });
    await root.scrollIntoViewIfNeeded();
    await root.click().catch(async () => {
      await root.evaluate((el) => (el as HTMLElement).click());
    });
    await page.waitForLoadState('domcontentloaded').catch(() => {});
  }

  const link = nav.locator(`a[href="${targetPath}"]`).first();
  await expect(link).toBeVisible({ timeout: 10000 });
  await link.evaluate((el) => (el as HTMLAnchorElement).click());
  await page.waitForURL((url) => url.pathname === targetPath, { timeout: 15000 });
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

async function selectPcbaQualityAgent(panel: Locator) {
  await panel.getByTestId('agent-selector-trigger').click();
  const dropdown = panel.getByTestId('agent-selector-dropdown');
  await expect(dropdown).toBeVisible({ timeout: 10000 });
  await dropdown.getByText(PCBA_QUALITY_AGENT_NAME).click();
  await expect(panel.getByTestId('agent-selector-trigger')).toContainText(PCBA_QUALITY_AGENT_NAME);
}

async function waitForAuraBotReady(panel: Locator) {
  const input = panel.locator('textarea').first();
  await expect(input).toBeEnabled({ timeout: 150000 });
}

async function sendAuraBotMessage(page: Page, panel: Locator, message: string) {
  await waitForAuraBotReady(panel);
  const input = panel.locator('textarea').first();
  const streamResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/ai/aurabot/chat/stream'),
    { timeout: 150000 },
  );
  await input.fill(message);
  await input.press('Enter');
  const streamResponse = await streamResponsePromise;
  const streamFinished = await streamResponse.finished().catch((error) => error);
  expect(streamResponse.status()).toBe(200);
  expect(streamFinished, 'AuraBot chat stream should complete without transport error').toBeNull();
  await waitForAuraBotReady(panel);
}

async function searchCapaListForDescription(page: Page, capaDescription: string) {
  await navigateToQualityPath(page, CAPA_PATH);
  const input = page.getByTestId('list-search-input');
  await expect(input).toBeVisible({ timeout: 10000 });

  const listResponse = page
    .waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/') &&
        response.url().includes('qc_capa') &&
        response.url().includes('/list') &&
        response.status() === 200,
      { timeout: 15000 },
    )
    .catch(() => null);
  await input.fill(capaDescription);
  await input.press('Enter');
  await listResponse;
  await waitForTableHydration(page);

  const row = page.locator('tbody tr', { hasText: capaDescription }).first();
  await expect(row).toBeVisible({ timeout: 15000 });
}

test.describe('PCBA Quality Agent write flow', () => {
  test.describe.configure({ mode: 'serial', timeout: 180000 });

  test.beforeAll(async ({ request }, testInfo) => {
    testInfo.setTimeout(600000);
    const installedPluginIds = await fetchInstalledPluginIds(request);
    for (const plugin of REQUIRED_PLUGIN_IMPORTS) {
      const forceImport = 'force' in plugin && plugin.force === true;
      if (!forceImport && installedPluginIds.has(plugin.pluginId)) continue;
      await importPluginDirectory(request, plugin);
      installedPluginIds.add(plugin.pluginId);
    }
  });

  test('creates a CAPA draft after browser confirmation @critical', async ({ page }) => {
    const fixture = await seedPcbaQualityFixture(page);
    const suffix = Date.now().toString(36).toUpperCase();
    const capaDescription = `E2E PCBA CAPA ${suffix} for ${fixture.batchNo}`;
    await ensurePcbaQualityAgent(page);

    await navigateToQualityPath(page, DEFECTS_PATH);
    const panel = await openAuraBotPanel(page);
    await selectPcbaQualityAgent(panel);

    await sendAuraBotMessage(
      page,
      panel,
      `先查看最近30天质量异常趋势，工具必须使用 nq_qc_quality_anomaly_trend，参数为 {}。\n${stubToolUse(
        'nq_qc_quality_anomaly_trend',
        {},
      )}`,
    );
    await waitForAuraBotReady(panel);

    await sendAuraBotMessage(
      page,
      panel,
      `针对缺陷 ${fixture.defectPid} 获取 CAPA 上下文。工具必须使用 nq_qc_quality_capa_context，参数必须严格使用 {"sourceRecordPid":"${fixture.defectPid}"}，不要改写 PID。批次号 ${fixture.batchNo}，产品编码 ${fixture.productCode}。\n${stubToolUse(
        'nq_qc_quality_capa_context',
        { sourceRecordPid: fixture.defectPid },
      )}`,
    );
    await waitForAuraBotReady(panel);
    await expect(panel).toContainText(fixture.defectPid, { timeout: 120000 });
    await expect(panel).toContainText(fixture.batchNo, { timeout: 120000 });

    await sendAuraBotMessage(
      page,
      panel,
      `确认创建 CAPA 草稿。qc_capa_source_type 必须是 defect，qc_capa_source_id 必须使用 ${fixture.defectPid}，描述必须是 ${capaDescription}。\n${stubToolUse(
        'cmd_qc_create_capa',
        {
          qc_capa_type: 'corrective',
          qc_capa_source_type: 'defect',
          qc_capa_source_id: fixture.defectPid,
          qc_capa_description: capaDescription,
          qc_capa_root_cause: 'Solder paste release variation at fine-pitch stencil aperture.',
          qc_capa_action_plan:
            'Tighten stencil inspection, add AOI checkpoint, and review paste volume trend for the next three lots.',
          qc_capa_responsible_id: 'e2e-quality-owner',
          qc_capa_due_date: '2026-06-30',
        },
      )}`,
    );
    await expect(panel).toContainText('cmd_qc_create_capa', { timeout: 120000 });
    await expect(panel).toContainText(capaDescription, { timeout: 120000 });
    await expect(panel).toContainText(fixture.defectPid, { timeout: 120000 });
    await expect(panel.getByRole('button', { name: 'Confirm' })).toBeVisible({ timeout: 120000 });

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
      'qc_capa',
      'qc_capa_description',
      capaDescription,
      { operator: 'EQ', pageSize: 10 },
    );
    expect(createdRecords).toHaveLength(1);
    expect(createdRecords[0]).toMatchObject({
      qc_capa_type: 'corrective',
      qc_capa_source_type: 'defect',
      qc_capa_source_id: fixture.defectPid,
      qc_capa_description: capaDescription,
      qc_capa_status: 'open',
    });

    await panel.getByTitle('Close (⌘J)').click();
    await expect(panel).not.toBeVisible({ timeout: 5000 });
    await searchCapaListForDescription(page, capaDescription);
  });
});
