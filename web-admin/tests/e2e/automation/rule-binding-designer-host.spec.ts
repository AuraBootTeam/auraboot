import { test, expect, type APIResponse, type Page, type Response } from '@playwright/test';
import { ensureSidebarExpanded, uniqueId, waitForDynamicPageLoad } from '../helpers';
import {
  dragNodeToCanvas,
  fillNodeConfig,
  connectEdge,
  saveAutomation,
  deleteViaApi,
} from '../_helpers/flow-designer-harness';

const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const PREFERRED_MODEL_CODE = 'e2et_order';
const DECISION_CODE = 'approval_routing';

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  msg?: string;
  data?: T;
};

type ModelRecord = {
  code?: string;
  displayName?: string;
  name?: string;
  extension?: {
    displayName?: string;
    name?: string;
  };
};

type PageResult<T> = {
  records?: T[];
  rows?: T[];
  content?: T[];
  total?: number;
};

type AutomationRecord = {
  pid: string;
  name?: string;
  modelCode?: string;
  triggerType?: string;
  enabled?: boolean;
};

type AutomationLogPayload = {
  id: number;
  pid?: string;
  status?: string;
  triggerPayload?: {
    decision?: {
      decisionCode?: string;
      matched?: boolean;
      status?: string;
      traceId?: string;
      outputs?: Record<string, unknown>;
    };
  };
  actionResults?: Array<Record<string, unknown>>;
};

type AutomationNodeExecution = {
  nodeId: string;
  status: string;
  errorMessage?: string;
};

type AutomationTestRunFixture = {
  automation: AutomationRecord;
  triggerLog: AutomationLogPayload;
};

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(90_000);

async function loginAsAdmin(page: Page, baseURL: string): Promise<void> {
  const response = await page.request.post(`${baseURL}/login`, {
    form: {
      channelCode: 'email_password',
      email: ADMIN_EMAIL,
      password: ADMIN_PASSWORD,
      remember: 'on',
      redirectTo: '/',
    },
    maxRedirects: 0,
  });

  expect(response.status(), `login failed: HTTP ${response.status()}`).toBe(302);

  const setCookie = response.headers()['set-cookie'] ?? '';
  const match = setCookie.match(/__session=([^;]+)/);
  if (match?.[1]) {
    const hostname = new URL(baseURL).hostname;
    await page.context().addCookies([
      {
        name: '__session',
        value: match[1],
        domain: hostname,
        path: '/',
        httpOnly: true,
        sameSite: 'Lax',
        expires: Math.floor(Date.now() / 1000) + 60 * 60,
      },
    ]);
  }
}

function isApiSuccess<T>(body: ApiEnvelope<T> | null | undefined): body is ApiEnvelope<T> {
  if (!body) return false;
  if (body.success === false) return false;
  const code = body.code;
  return code === undefined || code === null || String(code) === '0';
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function readApi<T>(response: APIResponse): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  expect(response.ok(), `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body)}`).toBe(
    true,
  );
  expect(isApiSuccess(body), `API failed: ${JSON.stringify(body)}`).toBe(true);
  return body.data as T;
}

async function readResponseApi<T>(response: Response): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  expect(response.ok(), `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body)}`).toBe(
    true,
  );
  expect(isApiSuccess(body), `API failed: ${JSON.stringify(body)}`).toBe(true);
  return body.data as T;
}

function pageResultRows<T>(payload: PageResult<T> | T[] | undefined | null): T[] {
  if (Array.isArray(payload)) return payload;
  return payload?.records ?? payload?.rows ?? payload?.content ?? [];
}

async function findAutomationByName(page: Page, name: string): Promise<AutomationRecord> {
  const payload = await readApi<PageResult<AutomationRecord> | AutomationRecord[]>(
    await page.request.get('/api/automations', {
      params: {
        keyword: name,
        size: 20,
      },
    }),
  );
  const match = pageResultRows(payload).find((record) => record.name === name);
  expect(match, `Expected seed automation named ${name}`).toBeTruthy();
  expect(match?.pid, `Seed automation ${name} must expose pid`).toBeTruthy();
  return match as AutomationRecord;
}

async function getNodeStatusesByLogId(
  page: Page,
  logId: number,
): Promise<AutomationNodeExecution[]> {
  return readApi<AutomationNodeExecution[]>(
    await page.request.get(`/api/automation/executions/by-log/${logId}/node-statuses`),
  );
}

async function openWorkflowDemoSeedAutomationAndRunTest(
  page: Page,
  baseURL: string,
): Promise<AutomationTestRunFixture> {
  await loginAsAdmin(page, baseURL);

  const automation = await findAutomationByName(page, '长假申请提醒');
  expect(automation.modelCode).toBe('wd_leave_request');
  await openAutomationDesigner(page, automation.pid);

  await expect(page.locator('[data-testid="automation-editor-name-input"]')).toHaveValue(
    '长假申请提醒',
  );
  await expect(page.locator('.react-flow__node').first()).toBeVisible({ timeout: 15_000 });

  const triggerResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/automations/${automation.pid}/trigger`) &&
      response.request().method() === 'POST',
    { timeout: 30_000 },
  );
  await page.locator('[data-testid="btn-test-run"]').click();
  const triggerLog = await readResponseApi<AutomationLogPayload>(await triggerResponsePromise);

  expect(triggerLog.id, 'manual test run must return numeric log id for runtime overlay').toBeTruthy();
  expect(triggerLog.status).toBe('success');
  expect(triggerLog.triggerPayload?.decision).toMatchObject({
    decisionCode: 'leave_request_automation',
    matched: true,
  });
  expect(triggerLog.triggerPayload?.decision?.traceId, 'test run must return unified trace id').toBeTruthy();
  expect(triggerLog.triggerPayload?.decision?.outputs).toMatchObject({
    severity: 'warning',
    actionType: 'send_notification',
  });
  expect(triggerLog.actionResults ?? []).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        actionType: 'send_notification',
        status: 'success',
      }),
    ]),
  );

  return { automation, triggerLog };
}

async function ensureApprovalRoutingDecision(page: Page): Promise<void> {
  const existing = await page.request.get(`/api/decision/definitions/${DECISION_CODE}`);
  const existingBody = (await existing.json().catch(() => null)) as ApiEnvelope<unknown> | null;
  if (!existing.ok() || !isApiSuccess(existingBody)) {
    await readApi(
      await page.request.post('/api/decision/definitions', {
        data: {
          decisionCode: DECISION_CODE,
          decisionName: 'Approval Routing',
          description: 'Automation rule-binding golden fixture',
          scopeType: 'AUTOMATION',
          ownerModule: 'decision',
          enabled: true,
        },
      }),
    );
  }

  await readApi(await page.request.get(`/api/decision/definitions/${DECISION_CODE}/versions`));
}

async function resolvePublishedModelLabel(page: Page): Promise<string> {
  const payload = await readApi<{ records?: ModelRecord[] } | ModelRecord[]>(
    await page.request.get('/api/meta/models?size=500&currentOnly=true&status=published'),
  );
  const records = Array.isArray(payload) ? payload : payload?.records || [];
  const selected =
    records.find((record) => record.code === PREFERRED_MODEL_CODE) ??
    records.find((record) => Boolean(record.code)) ??
    null;

  expect(selected, 'Automation rule-binding E2E needs at least one published model').toBeTruthy();
  return (
    selected?.displayName ||
    selected?.name ||
    selected?.extension?.displayName ||
    selected?.extension?.name ||
    selected?.code ||
    PREFERRED_MODEL_CODE
  );
}

async function openNewDesigner(page: Page): Promise<void> {
  await openAutomationListFromSidebar(page);
  await page.getByTestId('btn-create-automation').click();
  await expect(page).toHaveURL(/\/automation\/new(?:[?#].*)?$/, { timeout: 15_000 });
  await page
    .locator('[data-testid="automation-editor-name-input"]')
    .waitFor({ state: 'visible', timeout: 30_000 });
  await page
    .locator('[data-testid="flow-palette-shell"]')
    .waitFor({ state: 'attached', timeout: 20_000 });
  await page.locator('[data-testid="palette-node-trigger-record-create"]').waitFor({
    state: 'attached',
    timeout: 20_000,
  });
  await page.locator('.react-flow__pane').waitFor({ state: 'visible', timeout: 10_000 });
}

async function openAutomationDesigner(page: Page, pid: string): Promise<void> {
  await openAutomationListFromSidebar(page);
  const editLink = page.getByTestId(`btn-edit-${pid}`);
  await expect(editLink).toBeVisible({ timeout: 15_000 });
  await editLink.click();
  await expect(page).toHaveURL(new RegExp(`/automation/${pid}(?:[?#].*)?$`), { timeout: 15_000 });
  await page
    .locator('[data-testid="automation-editor-name-input"]')
    .waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('.react-flow__pane').waitFor({ state: 'visible', timeout: 10_000 });
}

async function openAutomationListFromSidebar(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav
    .locator('a[href="/automations"]')
    .or(nav.getByRole('link', { name: /自动化|Automation/i }))
    .first();
  const parent = nav
    .getByRole('button', { name: /系统管理|System Management|System|管理|Admin/i })
    .or(nav.getByRole('link', { name: /系统管理|System Management|System|管理|Admin/i }))
    .first();
  if (!(await link.isVisible({ timeout: 1000 }).catch(() => false))) {
    await parent.scrollIntoViewIfNeeded().catch(() => null);
    await parent.click().catch(async () => {
      await parent.evaluate((el: HTMLElement) => el.click()).catch(() => undefined);
    });
  }
  await expect(link).toBeVisible({ timeout: 15_000 });
  await link.click();
  await expect(page).toHaveURL(/\/automations(?:$|[/?#])/, { timeout: 15_000 });
  await expect(page.locator('[data-testid="page-title"]').first()).toBeVisible({
    timeout: 15_000,
  });
}

async function openDecisionDefinitionDetailViaSidebar(page: Page, decisionCode: string): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const parent = nav
    .getByRole('button', { name: /决策中心|DecisionOps/i })
    .or(nav.getByRole('link', { name: /决策中心|DecisionOps/i }))
    .first();
  const definitionsLink = nav
    .locator('a[href="/p/decisionops_definitions"]')
    .or(nav.getByRole('link', { name: /决策定义|Decision Definitions/i }))
    .first();
  if (!(await definitionsLink.isVisible({ timeout: 1000 }).catch(() => false))) {
    await expect(parent).toBeVisible({ timeout: 10_000 });
    await parent.click();
  }
  await expect(definitionsLink).toBeVisible({ timeout: 10_000 });
  await definitionsLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_definitions(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);

  const searchResponse = page
    .waitForResponse(
      (response) =>
        response.url().includes('/api/decision/definitions') &&
        response.url().includes(`keyword=${encodeURIComponent(decisionCode)}`) &&
        response.status() < 400,
      { timeout: 15_000 },
    )
    .catch(() => null);
  await page.getByTestId('list-search-input').fill(decisionCode);
  await page.getByTestId('list-search-input').press('Enter');
  await searchResponse;

  const exactDecisionCode = new RegExp(`^\\s*${escapeRegExp(decisionCode)}\\s*$`);
  const row = page
    .locator('tbody tr')
    .filter({ has: page.locator('td').filter({ hasText: exactDecisionCode }) })
    .first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await row
    .getByRole('link', { name: /详情|Detail/i })
    .or(row.getByRole('button', { name: /详情|Detail/i }))
    .first()
    .click();
  await expect(page).toHaveURL(/\/p\/decisionops_definitions\/view\//, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
}

async function setAutomationName(page: Page, name: string): Promise<void> {
  const input = page.locator('[data-testid="automation-editor-name-input"]');
  await expect(async () => {
    await input.click();
    await input.fill('');
    await input.pressSequentially(name, { delay: 10 });
    await expect(input).toHaveValue(name, { timeout: 2_000 });
  }).toPass({ timeout: 15_000, intervals: [250, 500, 1_000] });
}

async function selectNodeAndOpenRuleBinding(page: Page, nodeId: string) {
  const ruleField = page.locator('[data-testid="prop-field-ruleBinding"]');
  if (!(await ruleField.isVisible().catch(() => false))) {
    const inspectorShell = page.locator('[data-testid="flow-inspector-shell"]');
    const inspectorOpen = (await inspectorShell.getAttribute('data-open').catch(() => 'false')) === 'true';
    if (!inspectorOpen) {
      const backdrop = page.locator('[data-testid="flow-drawer-backdrop"]');
      if (await backdrop.isVisible().catch(() => false)) {
        await backdrop.click();
      }
      await page.locator(`[data-testid="flow-node-${nodeId}"]`).click();
      const openedAfterSelect =
        (await inspectorShell.getAttribute('data-open').catch(() => 'false')) === 'true';
      const toggleInspector = page.locator('[data-testid="flow-toggle-inspector"]');
      if (!openedAfterSelect && (await toggleInspector.isVisible().catch(() => false))) {
        await toggleInspector.click();
      }
    }
    await expandCollapsedPropertyGroups(page);
  }
  await expect(ruleField).toBeVisible({ timeout: 15_000 });
  await expect(ruleField.locator('[data-testid="decision-rule-binding-block"]')).toBeVisible({
    timeout: 15_000,
  });
  return ruleField;
}

async function selectNodeAndOpenPropertyField(page: Page, nodeId: string, fieldKey: string) {
  const field = page.locator(`[data-testid="prop-field-${fieldKey}"]`);
  if (!(await field.isVisible().catch(() => false))) {
    const inspectorShell = page.locator('[data-testid="flow-inspector-shell"]');
    const backdrop = page.locator('[data-testid="flow-drawer-backdrop"]');
    if (await backdrop.isVisible().catch(() => false)) {
      await backdrop.click();
    }
    await page.locator(`[data-testid="flow-node-${nodeId}"]`).click();
    const openedAfterSelect =
      (await inspectorShell.getAttribute('data-open').catch(() => 'false')) === 'true';
    const toggleInspector = page.locator('[data-testid="flow-toggle-inspector"]');
    if (!openedAfterSelect && (await toggleInspector.isVisible().catch(() => false))) {
      await toggleInspector.click();
    }
    await expandCollapsedPropertyGroups(page);
  }
  await expect(field).toBeVisible({ timeout: 15_000 });
  return field;
}

async function switchExpressionFieldToTextMode(field: ReturnType<Page['locator']>) {
  await expect(field.locator('[data-testid="expression-editor"]')).toBeVisible({
    timeout: 15_000,
  });
  const textMode = field.locator('[data-testid="mode-text"]');
  if (await textMode.isEnabled().catch(() => false)) {
    await textMode.click();
  }
  const textarea = field.locator('[data-testid="formula-editor-textarea"]').first();
  await expect(textarea).toBeVisible({ timeout: 15_000 });
  return textarea;
}

async function insertExpressionField(
  field: ReturnType<Page['locator']>,
  fieldText: string | RegExp,
) {
  const textarea = await switchExpressionFieldToTextMode(field);
  await field.getByRole('button', { name: /插入字段|Insert Field/i }).click();
  const picker = field.locator('[data-testid="formula-field-picker"]').first();
  await expect(picker).toBeVisible({ timeout: 15_000 });
  const option = picker.locator('button').filter({ hasText: fieldText }).first();
  await expect(option).toBeVisible({ timeout: 15_000 });
  await option.click();
  await expect(textarea).toHaveValue(
    typeof fieldText === 'string'
      ? new RegExp(`\\$\\{${escapeRegExp(fieldText)}\\}`)
      : fieldText,
  );
  return textarea;
}

async function addInputMappingRow(ruleField: ReturnType<Page['locator']>, index = 0) {
  const row = ruleField.locator(`[data-testid="decision-binding-mapping-${index}"]`);
  const addButton = ruleField
    .locator('.decision-rule-mapping-header')
    .filter({ hasText: '输入映射' })
    .getByRole('button', { name: '添加映射' });
  await expect(async () => {
    if ((await row.count()) === 0) {
      await addButton.click();
    }
    await expect(row).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 20_000, intervals: [500, 1_000, 2_000] });
  return row;
}

async function addOutputMappingRow(ruleField: ReturnType<Page['locator']>, index = 0) {
  const row = ruleField.locator(`[data-testid="decision-output-mapping-${index}"]`);
  const addButton = ruleField
    .locator('.decision-rule-mapping-header')
    .filter({ hasText: '输出映射' })
    .getByRole('button', { name: '添加输出' });
  if ((await row.count()) === 0) {
    await addButton.click();
  }
  await expect(row).toBeVisible({ timeout: 15_000 });
  return row;
}

async function expandCollapsedPropertyGroups(page: Page): Promise<void> {
  const toggles = page.locator('[data-testid^="prop-group-toggle-"][aria-expanded="false"]');
  for (let guard = 0; guard < 20; guard += 1) {
    if ((await toggles.count()) === 0) return;
    await toggles.first().click();
  }
  throw new Error('too many collapsed property groups to expand');
}

test('Automation trigger property panel hosts the rule center binding editor and persists it @golden', async ({
  page,
  baseURL,
}, testInfo) => {
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  await loginAsAdmin(page, resolvedBaseURL);
  await ensureApprovalRoutingDecision(page);
  const modelLabel = await resolvePublishedModelLabel(page);
  await openNewDesigner(page);

  const name = `Rule binding host ${uniqueId()}`;
  await setAutomationName(page, name);

  const triggerId = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
  await fillNodeConfig(page, triggerId, { modelCode: modelLabel });

  const ruleField = await selectNodeAndOpenRuleBinding(page, triggerId);
  await expect(ruleField.locator('[data-testid="rule-binding-property-field"]')).toBeVisible();
  await expect(ruleField.locator('[data-testid="decision-rule-binding-block"]')).toBeVisible();
  await expect(ruleField.locator('[data-testid="decision-binding-editor"]')).toContainText(
    '引用规则中心',
  );
  await expect(ruleField.locator('[data-testid="decision-rule-section-tabs"]')).toBeVisible();
  await ruleField.locator('[data-testid="decision-rule-section-tab-impact"]').click();
  await expect(ruleField.locator('[data-testid="decision-impact-preview"]')).toBeVisible();
  await ruleField.locator('[data-testid="decision-rule-section-tab-test"]').click();
  await expect(ruleField.locator('[data-testid="decision-test-runner"]')).toBeVisible();
  await ruleField.locator('[data-testid="decision-rule-section-tab-decision"]').click();

  await ruleField.locator('select[aria-label="version-policy"]').selectOption('ROLLOUT');
  await ruleField.getByRole('button', { name: '添加映射' }).click();
  await ruleField.locator('input[aria-label="mapping-input-0"]').fill('amount');
  await expect(
    ruleField.locator(
      'select[aria-label="mapping-field-0"] option[value="record:data.e2et_order_amount"]',
    ),
  ).toHaveCount(1);
  await ruleField.locator('select[aria-label="mapping-field-0"]').selectOption(
    'record:data.e2et_order_amount',
  );
  await expect(ruleField.locator('[data-testid="decision-binding-preview"]')).toContainText(
    '请假审批分派',
  );
  await expect(ruleField.locator('[data-testid="decision-binding-preview"]')).toContainText(
    '灰度发布',
  );
  await expect(ruleField.locator('[data-testid="decision-binding-preview"]')).toContainText(
    'amount',
  );
  await expect(ruleField.locator('[data-testid="decision-binding-preview"]')).toContainText(
    '订单总额',
  );
  await expect(ruleField.locator('[data-testid="decision-binding-preview"]')).not.toContainText(
    'decisionCode',
  );

  await page.screenshot({
    path: testInfo.outputPath('automation-rule-binding-designer-host.png'),
    fullPage: true,
  });

  const { pid } = await saveAutomation(page);
  try {
    const response = await page.request.get(`/api/automations/${pid}`);
    expect(response.ok(), `failed to read saved automation ${pid}`).toBeTruthy();
    const body = await response.json();
    const savedNode = body?.data?.flowConfig?.nodes?.find((node: any) => node.id === triggerId);
    expect(savedNode?.data?.config?.ruleBinding).toMatchObject({
      consumerType: 'AUTOMATION',
      consumerNodeId: 'trigger',
      bindingKind: 'DECISION_REF',
      enabled: true,
      decisionBinding: {
        decisionCode: DECISION_CODE,
        versionPolicy: 'ROLLOUT',
        fallbackPolicy: { mode: 'FAIL_CLOSED' },
        enabled: true,
        inputMappings: [
          {
            input: 'amount',
            source: { kind: 'FIELD', scope: 'record', path: 'data.e2et_order_amount' },
          },
        ],
      },
    });

    await openAutomationDesigner(page, pid);
    await expect(page.locator(`[data-testid="flow-node-${triggerId}"]`)).toBeVisible({
      timeout: 15_000,
    });
    const reloadedRuleField = await selectNodeAndOpenRuleBinding(page, triggerId);
    await expect(reloadedRuleField.locator('select[aria-label="version-policy"]')).toHaveValue(
      'ROLLOUT',
    );
    await expect(reloadedRuleField.locator('input[aria-label="mapping-input-0"]')).toHaveValue(
      'amount',
    );
    await expect(reloadedRuleField.locator('select[aria-label="mapping-field-0"]')).toHaveValue(
      'record:data.e2et_order_amount',
    );
    await expect(reloadedRuleField.locator('[data-testid="decision-binding-preview"]')).toContainText(
      '灰度发布',
    );
    await expect(reloadedRuleField.locator('[data-testid="decision-binding-preview"]')).toContainText(
      '请假审批分派',
    );
    await page.screenshot({
      path: testInfo.outputPath('automation-rule-binding-reloaded.png'),
      fullPage: true,
    });

    await readApi(await page.request.post('/api/decision/usage-index/rebuild'));
    const impact = await readApi<any>(
      await page.request.get(`/api/decision/definitions/${DECISION_CODE}/impact`),
    );
    const incoming = Array.isArray(impact?.incoming) ? impact.incoming : [];
    expect(incoming).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          sourceType: 'AUTOMATION',
          sourcePid: pid,
          binding: 'RULE_BINDING',
        }),
      ]),
    );

    await openDecisionDefinitionDetailViaSidebar(page, DECISION_CODE);
    await expect(page.getByTestId('decision-definition-actions-block')).toBeVisible();
    await expect(page.getByTestId('dda-impact-panel')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('impact-graph-panel')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('impact-incoming')).toContainText('AUTOMATION');
    await expect(page.getByTestId('impact-incoming')).toContainText('RULE_BINDING');
    await expect(page.getByTestId('impact-incoming')).toContainText(name);
    await page.screenshot({
      path: testInfo.outputPath('automation-rule-binding-impact-graph.png'),
      fullPage: true,
    });
  } finally {
    await deleteViaApi(page, pid);
  }
});

test('Automation send-notification action inserts fact catalog and rule output fields, then persists after reload @golden', async ({
  page,
  baseURL,
}, testInfo) => {
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5194';
  await loginAsAdmin(page, resolvedBaseURL);
  await ensureApprovalRoutingDecision(page);
  const modelLabel = await resolvePublishedModelLabel(page);
  await openNewDesigner(page);

  const name = `Action field picker ${uniqueId()}`;
  await setAutomationName(page, name);

  const triggerId = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
  const actionId = await dragNodeToCanvas(page, 'action-send-notification', { x: 150, y: 260 });
  await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
  await connectEdge(page, triggerId, actionId);
  await fillNodeConfig(page, triggerId, { modelCode: modelLabel });

  const ruleField = await selectNodeAndOpenRuleBinding(page, triggerId);
  await expect(ruleField.locator('select[aria-label="decision-code"]')).toHaveValue(
    DECISION_CODE,
    { timeout: 15_000 },
  );
  await expect(ruleField.locator('[data-testid="decision-binding-empty"]')).toBeVisible({
    timeout: 15_000,
  });
  await addInputMappingRow(ruleField);
  await ruleField.locator('input[aria-label="mapping-input-0"]').fill('amount');
  await ruleField.locator('select[aria-label="mapping-field-0"]').selectOption(
    'record:data.e2et_order_amount',
  );
  await addOutputMappingRow(ruleField);
  await expect(ruleField.locator('select[aria-label="output-mapping-output-picker-0"]')).toContainText(
    '审批等级',
  );
  await ruleField.locator('select[aria-label="output-mapping-output-picker-0"]').selectOption(
    'severity',
  );
  await expect(ruleField.locator('input[aria-label="output-mapping-output-0"]')).toHaveValue(
    'severity',
  );
  await ruleField.locator('select[aria-label="output-mapping-kind-0"]').selectOption('ACTION_PARAM');
  await ruleField.locator('input[aria-label="output-mapping-path-0"]').fill('severity');

  const titleField = await selectNodeAndOpenPropertyField(page, actionId, 'title');
  const titleTextarea = await insertExpressionField(titleField, 'record.data.e2et_order_amount');
  await expect(titleTextarea).toHaveValue(/\$\{record\.data\.e2et_order_amount\}/);

  const contentField = await selectNodeAndOpenPropertyField(page, actionId, 'content');
  const contentTextarea = await switchExpressionFieldToTextMode(contentField);
  await contentTextarea.fill('规则等级 ');
  await insertExpressionField(contentField, 'decision.outputs.severity');
  await expect(contentTextarea).toHaveValue(/规则等级 \$\{decision\.outputs\.severity\}/);

  const recipientsField = await selectNodeAndOpenPropertyField(page, actionId, 'recipients');
  const recipientsTextarea = await switchExpressionFieldToTextMode(recipientsField);
  await recipientsTextarea.fill('ROLE:wd_manager');

  await page.screenshot({
    path: testInfo.outputPath('automation-action-field-picker-before-save.png'),
    fullPage: true,
  });

  const { pid } = await saveAutomation(page);
  try {
    const response = await page.request.get(`/api/automations/${pid}`);
    expect(response.ok(), `failed to read saved automation ${pid}`).toBeTruthy();
    const body = await response.json();
    const savedTrigger = body?.data?.flowConfig?.nodes?.find((node: any) => node.id === triggerId);
    const savedAction = body?.data?.flowConfig?.nodes?.find((node: any) => node.id === actionId);
    expect(savedTrigger?.data?.config?.ruleBinding?.decisionBinding?.outputMappings).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          output: 'severity',
          target: { kind: 'ACTION_PARAM', path: 'severity' },
        }),
      ]),
    );
    expect(savedAction?.data?.config).toMatchObject({
      actionType: 'send_notification',
      notificationType: 'in_app',
      title: expect.stringContaining('${record.data.e2et_order_amount}'),
      content: expect.stringContaining('${decision.outputs.severity}'),
      recipients: 'ROLE:wd_manager',
    });

    await openAutomationDesigner(page, pid);
    await expect(page.locator(`[data-testid="flow-node-${actionId}"]`)).toBeVisible({
      timeout: 15_000,
    });
    const reloadedTitle = await switchExpressionFieldToTextMode(
      await selectNodeAndOpenPropertyField(page, actionId, 'title'),
    );
    await expect(reloadedTitle).toHaveValue(/\$\{record\.data\.e2et_order_amount\}/);
    const reloadedContent = await switchExpressionFieldToTextMode(
      await selectNodeAndOpenPropertyField(page, actionId, 'content'),
    );
    await expect(reloadedContent).toHaveValue(/规则等级 \$\{decision\.outputs\.severity\}/);
    const reloadedRecipients = await switchExpressionFieldToTextMode(
      await selectNodeAndOpenPropertyField(page, actionId, 'recipients'),
    );
    await expect(reloadedRecipients).toHaveValue('ROLE:wd_manager');

    await page.screenshot({
      path: testInfo.outputPath('automation-action-field-picker-reloaded.png'),
      fullPage: true,
    });
  } finally {
    await deleteViaApi(page, pid);
  }
});

test('Automation test run surfaces rule binding decision trace and runtime overlay from workflow-demo seed @golden', async ({
  page,
  baseURL,
}, testInfo) => {
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5194';
  const { automation, triggerLog } = await openWorkflowDemoSeedAutomationAndRunTest(
    page,
    resolvedBaseURL,
  );

  const resultPanel = page.locator('[data-testid="automation-test-run-result"]');
  await expect(resultPanel).toBeVisible({ timeout: 20_000 });
  const decisionTrace = page.locator('[data-testid="automation-decision-trace"]');
  await expect(decisionTrace).toBeVisible();
  await expect(decisionTrace).toContainText(/规则决策追踪|Rule Decision Trace/);
  await expect(decisionTrace.locator('[data-testid="automation-decision-trace-status"]')).toContainText(
    /已命中|Matched/,
  );
  await expect(decisionTrace).toContainText('leave_request_automation');
  await expect(decisionTrace).toContainText('warning');
  await expect(decisionTrace).toContainText('send_notification');
  await expect(resultPanel).toContainText(/发送通知|Send Notification/);
  await expect(resultPanel).toContainText(/接收人数|Recipient Count|recipientCount/);

  const unifiedTraceLink = page.locator('[data-testid="automation-unified-trace-link"]');
  await expect(unifiedTraceLink).toBeVisible();
  await expect(unifiedTraceLink).toHaveAttribute(
    'href',
    `/p/decisionops_execution_logs?traceId=${triggerLog.triggerPayload?.decision?.traceId}&decisionCode=leave_request_automation&callerType=AUTOMATION&callerRef=${automation.pid}`,
  );

  const runtimeLink = page.locator('[data-testid="automation-runtime-trace-link"]');
  await expect(runtimeLink).toBeVisible();
  await expect(runtimeLink).toHaveAttribute(
    'href',
    `/automation/${automation.pid}?logId=${triggerLog.id}`,
  );

  await page.screenshot({
    path: testInfo.outputPath('automation-rule-binding-decision-trace.png'),
    fullPage: true,
  });

  const nodeStatuses = await getNodeStatusesByLogId(page, triggerLog.id);
  expect(nodeStatuses.length, 'runtime overlay endpoint must expose node execution statuses').toBeGreaterThan(
    0,
  );
  expect(
    nodeStatuses.filter((status) => String(status.status).toLowerCase() === 'failed'),
    `runtime overlay must not contain failed nodes: ${JSON.stringify(nodeStatuses)}`,
  ).toHaveLength(0);

  await runtimeLink.click();
  await expect(page).toHaveURL(
    new RegExp(`/automation/${escapeRegExp(automation.pid)}\\?logId=${triggerLog.id}`),
    { timeout: 15_000 },
  );
  await expect(page.locator('.react-flow__node').first()).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('[data-runtime-status]').first()).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('[data-runtime-status="completed"]').first()).toBeVisible({
    timeout: 15_000,
  });

  await page.screenshot({
    path: testInfo.outputPath('automation-runtime-overlay-from-decision-trace.png'),
    fullPage: true,
  });
});

test('Automation test run opens unified DecisionOps trace and links back to automation @golden', async ({
  page,
  baseURL,
}, testInfo) => {
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5194';
  const { automation, triggerLog } = await openWorkflowDemoSeedAutomationAndRunTest(
    page,
    resolvedBaseURL,
  );
  const decisionTraceId = triggerLog.triggerPayload?.decision?.traceId;
  expect(decisionTraceId, 'manual test run must produce a DecisionOps trace id').toBeTruthy();

  const unifiedTraceLink = page.locator('[data-testid="automation-unified-trace-link"]');
  await expect(unifiedTraceLink).toBeVisible({ timeout: 20_000 });
  await expect(unifiedTraceLink).toHaveAttribute(
    'href',
    `/p/decisionops_execution_logs?traceId=${decisionTraceId}&decisionCode=leave_request_automation&callerType=AUTOMATION&callerRef=${automation.pid}`,
  );

  await unifiedTraceLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs\?/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(decisionTraceId as string).first()).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('[data-testid^="elta-row-"]').first()).toContainText('自动化');
  await expect(page.locator('[data-testid^="elta-row-"]').first()).toContainText(automation.pid);

  await page.locator('[data-testid^="elta-open-trace-"]').first().click();
  const drawer = page.getByTestId('elta-trace-drawer');
  await expect(drawer).toBeVisible({ timeout: 15_000 });
  await expect(drawer).toContainText('执行链路');
  await expect(drawer).toContainText('请假申请自动化策略');
  await expect(drawer).toContainText(automation.pid);
  const automationBackLink = page.getByTestId('elta-open-automation');
  await expect(automationBackLink).toHaveAttribute('href', `/automation/${automation.pid}`);

  await page.screenshot({
    path: testInfo.outputPath('automation-unified-decisionops-trace.png'),
    fullPage: true,
  });

  await automationBackLink.click();
  await expect(page).toHaveURL(new RegExp(`/automation/${escapeRegExp(automation.pid)}(?:[?#].*)?$`), {
    timeout: 15_000,
  });
  await expect(page.locator('[data-testid="automation-editor-name-input"]')).toHaveValue(
    '长假申请提醒',
  );
  await expect(page.locator('.react-flow__node').first()).toBeVisible({ timeout: 15_000 });

  await page.screenshot({
    path: testInfo.outputPath('automation-unified-trace-back-to-designer.png'),
    fullPage: true,
  });
});
