import { test, expect, type APIResponse, type Locator, type Page } from '@playwright/test';
import { clickRowActionByLocator, ensureSidebarExpanded, waitForDynamicPageLoad } from '../helpers';

const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const TS = Date.now();
const PROCESS_KEY = `rc_bpm_${TS}`;
const PROCESS_NAME = `Rule Center BPM ${TS}`;
const GATEWAY_ID = 'route_gateway';
const TASK_ID = 'manual_review';
const SERVICE_TASK_ID = 'notify_via_action';
const TASK_ASSIGNMENT_DECISION = 'task_assignee';

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  msg?: string;
  data?: T;
};

test.use({ storageState: { cookies: [], origins: [] } });

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
  if (!match?.[1]) {
    throw new Error('login action did not return __session cookie');
  }

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

async function ensureTaskAssignmentDecision(page: Page): Promise<void> {
  const existing = await page.request.get(`/api/decision/definitions/${TASK_ASSIGNMENT_DECISION}`);
  const existingBody = (await existing.json().catch(() => null)) as ApiEnvelope<unknown> | null;
  if (!existing.ok() || !isApiSuccess(existingBody)) {
    await readApi(
      await page.request.post('/api/decision/definitions', {
        data: {
          decisionCode: TASK_ASSIGNMENT_DECISION,
          decisionName: 'Task Assignee',
          description: 'BPM userTask rule-binding golden fixture',
          scopeType: 'BPM',
          ownerModule: 'bpm',
          enabled: true,
        },
      }),
    );
  }
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

function buildDesignerJson(processKey = PROCESS_KEY) {
  return {
    key: processKey,
    name: `${PROCESS_NAME} Designer`,
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 80, y: 180 },
        data: { type: 'startEvent', label: 'Start', config: {} },
      },
      {
        id: GATEWAY_ID,
        type: 'exclusiveGateway',
        position: { x: 270, y: 180 },
        data: {
          type: 'exclusiveGateway',
          label: 'Route',
          config: { name: 'Route', defaultFlow: 'e_gateway_manual' },
        },
      },
      {
        id: 'auto_approve',
        type: 'userTask',
        position: { x: 500, y: 90 },
        data: { type: 'userTask', label: 'Auto Approve', config: {} },
      },
      {
        id: TASK_ID,
        type: 'userTask',
        position: { x: 500, y: 270 },
        data: { type: 'userTask', label: 'Manual Review', config: {} },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 730, y: 180 },
        data: { type: 'endEvent', label: 'End', config: {} },
      },
    ],
    edges: [
      {
        id: 'e_start_gateway',
        source: 'start',
        target: GATEWAY_ID,
        type: 'smoothstep',
        data: {},
      },
      {
        id: 'e_gateway_auto',
        source: GATEWAY_ID,
        target: 'auto_approve',
        type: 'smoothstep',
        data: {
          label: 'auto',
          condition: { type: 'expression', content: '${amount <= 100}', language: 'mvel' },
        },
      },
      {
        id: 'e_gateway_manual',
        source: GATEWAY_ID,
        target: TASK_ID,
        type: 'smoothstep',
        data: {
          label: 'manual',
          condition: { type: 'expression', content: '${amount > 100}', language: 'mvel' },
        },
      },
      {
        id: 'e_auto_end',
        source: 'auto_approve',
        target: 'end',
        type: 'smoothstep',
        data: {},
      },
      {
        id: 'e_manual_end',
        source: TASK_ID,
        target: 'end',
        type: 'smoothstep',
        data: {},
      },
    ],
  };
}

function buildServiceTaskActionDesignerJson(processKey = `${PROCESS_KEY}_action`) {
  return {
    key: processKey,
    name: `${PROCESS_NAME} Service Action`,
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 80, y: 180 },
        data: { type: 'startEvent', label: 'Start', config: {} },
      },
      {
        id: SERVICE_TASK_ID,
        type: 'serviceTask',
        position: { x: 310, y: 180 },
        data: {
          type: 'serviceTask',
          label: 'Notify via Action',
          config: {
            name: 'Notify via Action',
            description: 'BPM consumes Rule Center action catalog',
          },
        },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 560, y: 180 },
        data: { type: 'endEvent', label: 'End', config: {} },
      },
    ],
    edges: [
      {
        id: 'e_start_action',
        source: 'start',
        target: SERVICE_TASK_ID,
        type: 'smoothstep',
        data: {},
      },
      {
        id: 'e_action_end',
        source: SERVICE_TASK_ID,
        target: 'end',
        type: 'smoothstep',
        data: {},
      },
    ],
  };
}

async function openDesigner(page: Page, pid: string, expectedNodeCount = 5): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav
    .locator('a[href="/p/bpm_process_management"]')
    .or(nav.getByRole('link', { name: /流程定义|BPM Process|Process Definition/i }))
    .first();
  const parent = nav
    .getByRole('button', { name: /流程管理|BPM|Process Management|管理|Admin/i })
    .or(nav.getByRole('link', { name: /流程管理|BPM|Process Management|管理|Admin/i }))
    .first();
  if (!(await link.isVisible({ timeout: 1000 }).catch(() => false))) {
    await parent.click().catch(() => undefined);
  }
  await expect(link).toBeVisible({ timeout: 15_000 });
  await link.click();
  await expect(page).toHaveURL(/\/p\/bpm_process_management(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);

  await page.getByTestId('list-search-input').fill(PROCESS_NAME);
  await page.getByTestId('list-search-input').press('Enter');
  const row = page.locator('tbody tr').filter({ hasText: PROCESS_NAME }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await clickRowActionByLocator(page, row, 'open_bpmn_designer', '设计流程');
  await expect(page.locator('.react-flow')).toBeVisible({ timeout: 15_000 });
  await page.waitForFunction(
    () => Boolean((window as unknown as { __bpmnDesignerStore?: unknown }).__bpmnDesignerStore),
    undefined,
    { timeout: 10_000 },
  );
  await expect(page.locator('.react-flow__node')).toHaveCount(expectedNodeCount, {
    timeout: 10_000,
  });
}

async function selectGateway(page: Page): Promise<void> {
  await page.evaluate((nodeId) => {
    const store = (
      window as unknown as {
        __bpmnDesignerStore?: { getState: () => Record<string, (...args: unknown[]) => unknown> };
      }
    ).__bpmnDesignerStore;
    if (!store) throw new Error('BPMN store missing');
    const state = store.getState() as unknown as {
      setSelectedEdge: (id: string | null) => void;
      setSelectedNode: (id: string | null) => void;
    };
    state.setSelectedEdge(null);
    state.setSelectedNode(nodeId);
  }, GATEWAY_ID);

  await page
    .locator('[data-testid="exclusivegateway-rule-binding"]')
    .waitFor({ state: 'visible', timeout: 5_000 });
}

async function selectUserTask(page: Page): Promise<void> {
  await page.evaluate((nodeId) => {
    const store = (
      window as unknown as {
        __bpmnDesignerStore?: { getState: () => Record<string, (...args: unknown[]) => unknown> };
      }
    ).__bpmnDesignerStore;
    if (!store) throw new Error('BPMN store missing');
    const state = store.getState() as unknown as {
      setSelectedEdge: (id: string | null) => void;
      setSelectedNode: (id: string | null) => void;
    };
    state.setSelectedEdge(null);
    state.setSelectedNode(nodeId);
  }, TASK_ID);

  await page.locator('[data-testid="usertask-rule-binding"]').waitFor({
    state: 'visible',
    timeout: 5_000,
  });
}

async function selectServiceTask(page: Page): Promise<void> {
  await page.evaluate((nodeId) => {
    const store = (
      window as unknown as {
        __bpmnDesignerStore?: { getState: () => Record<string, (...args: unknown[]) => unknown> };
      }
    ).__bpmnDesignerStore;
    if (!store) throw new Error('BPMN store missing');
    const state = store.getState() as unknown as {
      setSelectedEdge: (id: string | null) => void;
      setSelectedNode: (id: string | null) => void;
    };
    state.setSelectedEdge(null);
    state.setSelectedNode(nodeId);
  }, SERVICE_TASK_ID);

  await page.locator('[data-testid="servicetask-service-type"]').waitFor({
    state: 'visible',
    timeout: 5_000,
  });
}

async function saveViaUI(page: Page, pid: string): Promise<void> {
  const saveBtn = page.locator('[data-testid="bpmn-toolbar-btn-save"]');
  await expect(saveBtn).toBeVisible({ timeout: 5_000 });
  await expect(saveBtn).toBeEnabled({ timeout: 5_000 });
  await saveBtn.click();

  const dialog = page.locator('[data-testid="bpmn-save-dialog-panel"]');
  await dialog.waitFor({ state: 'visible', timeout: 5_000 });
  const submitBtn = page.locator('[data-testid="bpmn-save-dialog-submit"]');
  await expect(submitBtn).toBeEnabled({ timeout: 5_000 });

  const [response] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().includes(`/api/bpm/process-definitions/${pid}`) &&
        r.request().method().toLowerCase() === 'put' &&
        r.status() < 400,
      { timeout: 20_000 },
    ),
    submitBtn.click(),
  ]);
  expect(response.status(), 'save PUT must succeed').toBeLessThan(400);
  await expect(dialog).toBeHidden({ timeout: 5_000 });
}

async function expectCompiledBpmnRuleBinding(
  page: Page,
  pid: string,
  expectedFragments: string[],
): Promise<string> {
  const bpmnXml = await readApi<string>(
    await page.request.get(`/api/bpm/process-definitions/${pid}/bpmn`),
  );
  expect(bpmnXml, 'compiled BPMN XML must be persisted').toBeTruthy();
  expect(bpmnXml).toContain('name="aura.ruleBinding"');
  for (const fragment of expectedFragments) {
    expect(bpmnXml).toContain(fragment);
  }
  return bpmnXml;
}

async function expectImpactPreviewAccessible(ruleSection: Locator): Promise<void> {
  await ruleSection.locator('[data-testid="decision-rule-section-tab-impact"]').click();
  await expect(ruleSection.locator('[data-testid="decision-impact-preview"]')).toBeVisible();
  await expect(ruleSection.locator('[data-testid="decision-impact-preview"]')).toContainText(
    '影响预览',
  );
  await ruleSection.locator('[data-testid="decision-rule-section-tab-decision"]').click();
  await expect(ruleSection.locator('[data-testid="decision-binding-editor"]')).toBeVisible();
}

async function configureAmountInputMapping(ruleSection: Locator): Promise<void> {
  await ruleSection.getByRole('button', { name: '添加映射' }).click();
  await ruleSection.locator('input[aria-label="mapping-input-0"]').fill('amount');
  await ruleSection.locator('select[aria-label="mapping-field-0"]').selectOption('record:amount');
}

async function configureApplicantInputMapping(ruleSection: Locator): Promise<void> {
  await ruleSection.getByRole('button', { name: '添加映射' }).click();
  const fieldPicker = ruleSection.locator('select[aria-label="mapping-field-0"]');
  await expect(fieldPicker).toContainText('申请人', { timeout: 15_000 });
  await ruleSection.locator('input[aria-label="mapping-input-0"]').fill('wd_req_applicant');
  await fieldPicker.selectOption('record:data.wd_req_applicant');
}

async function expectAmountMappingPreview(ruleSection: Locator, decisionName: string): Promise<void> {
  const preview = ruleSection.locator('[data-testid="decision-binding-preview"]');
  await expect(preview).toContainText(decisionName);
  await expect(preview).toContainText('灰度发布');
  await expect(preview).toContainText('amount');
  await expect(preview).toContainText('流程金额');
}

async function expectApplicantMappingPreview(ruleSection: Locator, decisionName: string | RegExp): Promise<void> {
  const preview = ruleSection.locator('[data-testid="decision-binding-preview"]');
  await expect(preview).toContainText(decisionName);
  await expect(preview).toContainText('灰度发布');
  await expect(preview).toContainText('wd_req_applicant');
  await expect(preview).toContainText('申请人');
}

async function expectGatewayBindingReloaded(page: Page, pid: string): Promise<void> {
  await openDesigner(page, pid);
  await selectGateway(page);

  const ruleSection = page.locator('[data-testid="exclusivegateway-rule-binding"]');
  await expect(ruleSection.locator('[data-testid="exclusivegateway-rule-binding-toggle"]')).toBeChecked();
  await expect(ruleSection.locator('[data-testid="decision-rule-binding-block"]')).toBeVisible();
  await ruleSection.locator('[data-testid="decision-rule-section-tab-decision"]').click();
  await expect(ruleSection.locator('select[aria-label="decision-code"]')).toHaveValue(
    'approval_routing',
  );
  await expect(ruleSection.locator('select[aria-label="version-policy"]')).toHaveValue('ROLLOUT');
  await expect(ruleSection.locator('input[aria-label="mapping-input-0"]')).toHaveValue('amount');
  await expect(ruleSection.locator('select[aria-label="mapping-field-0"]')).toHaveValue(
    'record:amount',
  );
  await expectAmountMappingPreview(ruleSection, '请假审批分派');
}

async function expectUserTaskBindingReloaded(page: Page, pid: string): Promise<void> {
  await openDesigner(page, pid);
  await selectUserTask(page);

  const ruleSection = page.locator('[data-testid="usertask-rule-binding"]');
  await expect(ruleSection.locator('[data-testid="usertask-rule-binding-toggle"]')).toBeChecked();
  await expect(ruleSection.locator('[data-testid="decision-rule-binding-block"]')).toBeVisible();
  await ruleSection.locator('[data-testid="decision-rule-section-tab-decision"]').click();
  await expect(ruleSection.locator('select[aria-label="decision-code"]')).toHaveValue(
    TASK_ASSIGNMENT_DECISION,
  );
  await expect(ruleSection.locator('select[aria-label="version-policy"]')).toHaveValue('ROLLOUT');
  await expect(ruleSection.locator('input[aria-label="mapping-input-0"]')).toHaveValue(
    'wd_req_applicant',
  );
  await expect(ruleSection.locator('select[aria-label="mapping-field-0"]')).toHaveValue(
    'record:data.wd_req_applicant',
  );
  await expect(ruleSection.locator('input[aria-label="output-mapping-output-0"]')).toHaveValue(
    'candidateUserIds',
  );
  await expect(ruleSection.locator('select[aria-label="output-mapping-kind-0"]')).toHaveValue(
    'ACTION_PARAM',
  );
  await expect(ruleSection.locator('input[aria-label="output-mapping-path-0"]')).toHaveValue(
    'candidateUsers',
  );
  await expectApplicantMappingPreview(ruleSection, /任务分派|Task Assignee/);
  await expect(ruleSection.locator('[data-testid="decision-binding-preview"]')).toContainText(
    '候选审批人',
  );
  await expect(ruleSection.locator('[data-testid="decision-binding-preview"]')).toContainText(
    '动作参数',
  );
  const applicantInput = ruleSection.locator('input[aria-label="mapping-input-0"]');
  await applicantInput.scrollIntoViewIfNeeded();
  await expect(applicantInput).toBeInViewport();
  await expect(ruleSection.locator('select[aria-label="mapping-field-0"]')).toBeInViewport();
}

async function expectServiceTaskActionReloaded(page: Page, pid: string): Promise<void> {
  await openDesigner(page, pid, 3);
  await selectServiceTask(page);

  await expect(page.locator('[data-testid="servicetask-service-type"]')).toHaveValue('action');
  await expect(page.locator('[data-testid="servicetask-action-panel"]')).toBeVisible();
  await expect(page.locator('[data-testid="servicetask-action-type"]')).toHaveValue('SEND_SMS');
  await expect(page.locator('[data-testid="servicetask-action-summary"]')).toContainText('不可用');
  await expect(page.locator('[data-testid="servicetask-action-availability"]')).toContainText(
    '当前环境未配置真实短信 provider',
  );
  await expect(page.locator('[data-testid="servicetask-action-provider"]')).toContainText(
    '依赖：真实短信 provider · 未配置',
  );
  await expect(page.locator('[data-testid="servicetask-action-target"]')).toHaveValue(
    'PHONE:${record.phone}',
  );
  await expect(page.locator('[data-testid="servicetask-action-payload"]')).toHaveValue(
    '{"content":"流程 ${process.businessKey} 需要审批"}',
  );
  await expect(page.locator('[data-testid="servicetask-action-result-var"]')).toHaveValue(
    'smsResult',
  );
  await expect(page.locator('[data-testid="servicetask-action-idempotency"]')).toHaveValue(
    '${process.instanceId}:${nodeId}:SEND_SMS',
  );
}

test('BPM gateway property panel hosts the rule center binding editor and persists it @golden', async ({
  page,
  baseURL,
}, testInfo) => {
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  await loginAsAdmin(page, resolvedBaseURL);

  const createResp = await page.request.post('/api/bpm/process-definitions', {
    data: {
      processKey: PROCESS_KEY,
      processName: PROCESS_NAME,
      description: 'Rule Center BPM designer host E2E',
      category: 'e2e-test',
      bpmnContent: '',
      designerJson: JSON.stringify(buildDesignerJson()),
    },
  });
  expect(createResp.ok(), `draft create: ${createResp.status()} ${await createResp.text()}`).toBe(
    true,
  );
  const createBody = await createResp.json();
  const pid = String(createBody?.data?.pid ?? createBody?.data?.id ?? '');
  expect(pid, 'create must return pid').toBeTruthy();

  try {
    await openDesigner(page, pid);
    await selectGateway(page);

    const ruleSection = page.locator('[data-testid="exclusivegateway-rule-binding"]');
    await expect(ruleSection).toBeVisible();
    await ruleSection.locator('[data-testid="exclusivegateway-rule-binding-toggle"]').check();
    await expect(ruleSection.locator('[data-testid="decision-rule-binding-block"]')).toBeVisible();
    await expect(ruleSection.locator('[data-testid="decision-binding-editor"]')).toContainText(
      '引用规则中心',
    );
    await expectImpactPreviewAccessible(ruleSection);

    await ruleSection.locator('select[aria-label="version-policy"]').selectOption('ROLLOUT');
    await configureAmountInputMapping(ruleSection);
    await expectAmountMappingPreview(ruleSection, '请假审批分派');

    await page.screenshot({
      path: testInfo.outputPath('bpm-rule-binding-designer-host.png'),
      fullPage: true,
    });

    await saveViaUI(page, pid);

    const getResp = await page.request.get(`/api/bpm/process-definitions/${pid}`);
    expect(getResp.ok(), `detail get: ${getResp.status()}`).toBe(true);
    const detail = await getResp.json();
    const designerJsonText = String(detail?.data?.designerJson ?? '');
    expect(designerJsonText, 'designerJson must be persisted').toBeTruthy();
    const designerJson = JSON.parse(designerJsonText) as {
      nodes: Array<{ id: string; data?: { config?: Record<string, unknown> } }>;
    };
    const gateway = designerJson.nodes.find((node) => node.id === GATEWAY_ID);
    const ruleBinding = gateway?.data?.config?.ruleBinding;
    expect(ruleBinding).toMatchObject({
      consumerType: 'BPM',
      consumerCode: PROCESS_KEY,
      consumerNodeId: GATEWAY_ID,
      bindingKind: 'DECISION_REF',
      enabled: true,
      decisionBinding: {
        decisionCode: 'approval_routing',
        versionPolicy: 'ROLLOUT',
        fallbackPolicy: { mode: 'FAIL_CLOSED' },
        enabled: true,
        inputMappings: [
          {
            input: 'amount',
            source: { kind: 'FIELD', scope: 'record', path: 'amount' },
          },
        ],
      },
    });

    await expectCompiledBpmnRuleBinding(page, pid, [
      '&quot;decisionCode&quot;:&quot;approval_routing&quot;',
      '&quot;versionPolicy&quot;:&quot;ROLLOUT&quot;',
      '&quot;consumerNodeId&quot;:&quot;route_gateway&quot;',
      '&quot;input&quot;:&quot;amount&quot;',
    ]);
    await expectGatewayBindingReloaded(page, pid);
    await page.screenshot({
      path: testInfo.outputPath('bpm-rule-binding-designer-reloaded.png'),
      fullPage: true,
    });
  } finally {
    await page.request.delete(`/api/bpm/process-definitions/${pid}`);
  }
});

test('BPM serviceTask consumes action catalog availability and persists platform action config @golden', async ({
  page,
  baseURL,
}, testInfo) => {
  testInfo.setTimeout(60_000);
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  await loginAsAdmin(page, resolvedBaseURL);

  const processKey = `${PROCESS_KEY}_action`;
  const processName = `${PROCESS_NAME} Action ServiceTask`;
  const createResp = await page.request.post('/api/bpm/process-definitions', {
    data: {
      processKey,
      processName,
      description: 'Rule Center BPM action catalog availability E2E',
      category: 'e2e-test',
      bpmnContent: '',
      designerJson: JSON.stringify(buildServiceTaskActionDesignerJson(processKey)),
    },
  });
  expect(createResp.ok(), `draft create: ${createResp.status()} ${await createResp.text()}`).toBe(
    true,
  );
  const createBody = await createResp.json();
  const pid = String(createBody?.data?.pid ?? createBody?.data?.id ?? '');
  expect(pid, 'create must return pid').toBeTruthy();

  try {
    await openDesigner(page, pid, 3);
    const catalogResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/decision/actions/catalog') && response.status() < 400,
      { timeout: 15_000 },
    );
    await selectServiceTask(page);
    await catalogResponse;

    await page.locator('[data-testid="servicetask-service-type"]').selectOption('action');
    await expect(page.locator('[data-testid="servicetask-action-panel"]')).toBeVisible();
    await expect(page.locator('[data-testid="servicetask-action-type"]')).toContainText(
      '发送短信（不可用）',
    );
    await page.locator('[data-testid="servicetask-action-type"]').selectOption('SEND_SMS');
    await expect(page.locator('[data-testid="servicetask-action-summary"]')).toContainText('不可用');
    await expect(page.locator('[data-testid="servicetask-action-availability"]')).toContainText(
      '当前环境未配置真实短信 provider',
    );
    await expect(page.locator('[data-testid="servicetask-action-provider"]')).toContainText(
      '依赖：真实短信 provider · 未配置',
    );
    await page.locator('[data-testid="servicetask-action-target"]').fill('PHONE:${record.phone}');
    await page
      .locator('[data-testid="servicetask-action-payload"]')
      .fill('{"content":"流程 ${process.businessKey} 需要审批"}');
    await page.locator('[data-testid="servicetask-action-result-var"]').fill('smsResult');
    await page
      .locator('[data-testid="servicetask-action-idempotency"]')
      .fill('${process.instanceId}:${nodeId}:SEND_SMS');

    await page.screenshot({
      path: testInfo.outputPath('bpm-servicetask-action-availability.png'),
      fullPage: true,
    });

    await saveViaUI(page, pid);

    const getResp = await page.request.get(`/api/bpm/process-definitions/${pid}`);
    expect(getResp.ok(), `detail get: ${getResp.status()}`).toBe(true);
    const detail = await getResp.json();
    const designerJsonText = String(detail?.data?.designerJson ?? '');
    expect(designerJsonText, 'designerJson must be persisted').toBeTruthy();
    const designerJson = JSON.parse(designerJsonText) as {
      nodes: Array<{ id: string; data?: { config?: Record<string, unknown> } }>;
    };
    const serviceTask = designerJson.nodes.find((node) => node.id === SERVICE_TASK_ID);
    expect(serviceTask?.data?.config).toMatchObject({
      serviceType: 'action',
      actionType: 'SEND_SMS',
      actionTarget: 'PHONE:${record.phone}',
      actionPayloadJson: '{"content":"流程 ${process.businessKey} 需要审批"}',
      actionResultVar: 'smsResult',
      actionIdempotencyKey: '${process.instanceId}:${nodeId}:SEND_SMS',
    });

    const bpmnXml = await readApi<string>(
      await page.request.get(`/api/bpm/process-definitions/${pid}/bpmn`),
    );
    expect(bpmnXml).toContain('smart:class="pluginActionServiceTaskDelegate"');
    expect(bpmnXml).toContain('smart:action="SEND_SMS"');
    expect(bpmnXml).toContain('smart:target="PHONE:${record.phone}"');
    expect(bpmnXml).toContain('smart:resultVar="smsResult"');
    expect(bpmnXml).toContain('smart:idempotencyKey="${process.instanceId}:${nodeId}:SEND_SMS"');
    expect(bpmnXml).toContain(
      'smart:payloadJson="{&quot;content&quot;:&quot;流程 ${process.businessKey} 需要审批&quot;}"',
    );

    await expectServiceTaskActionReloaded(page, pid);
    await page.screenshot({
      path: testInfo.outputPath('bpm-servicetask-action-reloaded.png'),
      fullPage: true,
    });
  } finally {
    await page.request.delete(`/api/bpm/process-definitions/${pid}`);
  }
});

test('BPMN designer compact viewport uses overlay drawers without squeezing the canvas @golden', async ({
  page,
  baseURL,
}, testInfo) => {
  testInfo.setTimeout(60_000);
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  await loginAsAdmin(page, resolvedBaseURL);

  const processKey = `${PROCESS_KEY}_compact`;
  const processName = `${PROCESS_NAME} Compact Workspace`;
  const createResp = await page.request.post('/api/bpm/process-definitions', {
    data: {
      processKey,
      processName,
      description: 'BPMN compact workspace E2E',
      category: 'e2e-test',
      bpmnContent: '',
      designerJson: JSON.stringify(buildDesignerJson(processKey)),
    },
  });
  expect(createResp.ok(), `draft create: ${createResp.status()} ${await createResp.text()}`).toBe(
    true,
  );
  const createBody = await createResp.json();
  const pid = String(createBody?.data?.pid ?? createBody?.data?.id ?? '');
  expect(pid, 'create must return pid').toBeTruthy();

  try {
    await openDesigner(page, pid);
    await page.setViewportSize({ width: 632, height: 900 });

    const workspace = page.getByTestId('bpmn-designer-workspace');
    await expect(workspace).toHaveAttribute('data-layout', 'compact', { timeout: 10_000 });
    await expect(page.getByTestId('bpmn-palette-shell')).toHaveAttribute('data-open', 'false');
    await expect(page.getByTestId('bpmn-inspector-shell')).toHaveAttribute('data-open', 'false');
    await expect(page.getByTestId('bpmn-canvas-shell')).toBeVisible();
    await expect(page.locator('.react-flow')).toBeVisible();
    const canvasBox = await page.getByTestId('bpmn-canvas-shell').boundingBox();
    expect(canvasBox?.width ?? 0, 'compact canvas keeps usable width').toBeGreaterThan(420);

    await page.screenshot({
      path: testInfo.outputPath('bpmn-compact-canvas.png'),
      fullPage: true,
    });

    await page.getByTestId('bpmn-toggle-palette').click();
    await expect(page.getByTestId('bpmn-palette-shell')).toHaveAttribute('data-open', 'true');
    await expect(page.getByTestId('bpmn-inspector-shell')).toHaveAttribute('data-open', 'false');
    await expect(page.getByTestId('bpmn-drawer-backdrop')).toBeVisible();
    await page.screenshot({
      path: testInfo.outputPath('bpmn-compact-palette-drawer.png'),
      fullPage: true,
    });

    await page.getByTestId('bpmn-toggle-inspector').click();
    await expect(page.getByTestId('bpmn-palette-shell')).toHaveAttribute('data-open', 'false');
    await expect(page.getByTestId('bpmn-inspector-shell')).toHaveAttribute('data-open', 'true');
    await expect(page.getByTestId('bpmn-drawer-backdrop')).toBeVisible();
    await page.screenshot({
      path: testInfo.outputPath('bpmn-compact-inspector-drawer.png'),
      fullPage: true,
    });
  } finally {
    await page.request.delete(`/api/bpm/process-definitions/${pid}`);
  }
});

test('BPM userTask property panel hosts rule center assignment and exposes impact @golden', async ({
  page,
  baseURL,
}, testInfo) => {
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  await loginAsAdmin(page, resolvedBaseURL);
  await ensureTaskAssignmentDecision(page);

  const processKey = `${PROCESS_KEY}_task`;
  const processName = `${PROCESS_NAME} Task Assignment`;
  const createResp = await page.request.post('/api/bpm/process-definitions', {
    data: {
      processKey,
      processName,
      description: 'Rule Center BPM userTask assignment designer host E2E',
      category: 'e2e-test',
      bpmnContent: '',
      designerJson: JSON.stringify(buildDesignerJson(processKey)),
    },
  });
  expect(createResp.ok(), `draft create: ${createResp.status()} ${await createResp.text()}`).toBe(
    true,
  );
  const createBody = await createResp.json();
  const pid = String(createBody?.data?.pid ?? createBody?.data?.id ?? '');
  expect(pid, 'create must return pid').toBeTruthy();

  try {
    await openDesigner(page, pid);
    await selectUserTask(page);

    const ruleSection = page.locator('[data-testid="usertask-rule-binding"]');
    await expect(ruleSection).toBeVisible();
    await ruleSection.locator('[data-testid="usertask-rule-binding-toggle"]').check();
    await expect(ruleSection.locator('[data-testid="decision-rule-binding-block"]')).toBeVisible();
    await expect(ruleSection.locator('[data-testid="decision-binding-editor"]')).toContainText(
      '引用规则中心',
    );
    await expect(ruleSection.locator('select[aria-label="decision-code"]')).toHaveValue(
      TASK_ASSIGNMENT_DECISION,
    );
    await expectImpactPreviewAccessible(ruleSection);

    await ruleSection.locator('select[aria-label="version-policy"]').selectOption('ROLLOUT');
    await configureApplicantInputMapping(ruleSection);
    await expectApplicantMappingPreview(ruleSection, /任务分派|Task Assignee/);
    await ruleSection.getByRole('button', { name: '添加输出' }).click();
    await expect(
      ruleSection.locator('select[aria-label="output-mapping-output-picker-0"]'),
    ).toContainText('候选审批人');
    await ruleSection.locator('select[aria-label="output-mapping-output-picker-0"]').selectOption(
      'candidateUserIds',
    );
    await expect(ruleSection.locator('input[aria-label="output-mapping-output-0"]')).toHaveValue(
      'candidateUserIds',
    );
    await ruleSection.locator('select[aria-label="output-mapping-kind-0"]').selectOption(
      'ACTION_PARAM',
    );
    await ruleSection.locator('input[aria-label="output-mapping-path-0"]').fill('candidateUsers');
    await expect(ruleSection.locator('[data-testid="decision-binding-preview"]')).toContainText(
      '候选审批人',
    );
    await expect(ruleSection.locator('[data-testid="decision-binding-preview"]')).toContainText(
      '动作参数',
    );
    await expect(ruleSection.locator('[data-testid="decision-binding-preview"]')).toContainText(
      'candidateUsers',
    );

    await page.screenshot({
      path: testInfo.outputPath('bpm-usertask-rule-binding-designer-host.png'),
      fullPage: true,
    });

    await saveViaUI(page, pid);

    const getResp = await page.request.get(`/api/bpm/process-definitions/${pid}`);
    expect(getResp.ok(), `detail get: ${getResp.status()}`).toBe(true);
    const detail = await getResp.json();
    const designerJsonText = String(detail?.data?.designerJson ?? '');
    expect(designerJsonText, 'designerJson must be persisted').toBeTruthy();
    const designerJson = JSON.parse(designerJsonText) as {
      nodes: Array<{ id: string; data?: { config?: Record<string, unknown> } }>;
    };
    const userTask = designerJson.nodes.find((node) => node.id === TASK_ID);
    const assignmentRuleBinding = userTask?.data?.config?.assignmentRuleBinding;
    expect(assignmentRuleBinding).toMatchObject({
      consumerType: 'BPM',
      consumerCode: processKey,
      consumerNodeId: TASK_ID,
      bindingKind: 'DECISION_REF',
      enabled: true,
      decisionBinding: {
        decisionCode: TASK_ASSIGNMENT_DECISION,
        versionPolicy: 'ROLLOUT',
        fallbackPolicy: { mode: 'FAIL_CLOSED' },
        enabled: true,
        inputMappings: [
          {
            input: 'wd_req_applicant',
            source: { kind: 'FIELD', scope: 'record', path: 'data.wd_req_applicant' },
          },
        ],
        outputMappings: [
          {
            output: 'candidateUserIds',
            target: { kind: 'ACTION_PARAM', path: 'candidateUsers' },
          },
        ],
      },
    });

    await expectCompiledBpmnRuleBinding(page, pid, [
      '&quot;decisionCode&quot;:&quot;task_assignee&quot;',
      '&quot;versionPolicy&quot;:&quot;ROLLOUT&quot;',
      '&quot;consumerNodeId&quot;:&quot;manual_review&quot;',
      '&quot;input&quot;:&quot;wd_req_applicant&quot;',
      '&quot;path&quot;:&quot;data.wd_req_applicant&quot;',
      '&quot;output&quot;:&quot;candidateUserIds&quot;',
      '&quot;path&quot;:&quot;candidateUsers&quot;',
    ]);
    await expectUserTaskBindingReloaded(page, pid);
    await page.screenshot({
      path: testInfo.outputPath('bpm-usertask-rule-binding-reloaded.png'),
      fullPage: true,
    });

    await readApi(await page.request.post('/api/decision/usage-index/rebuild'));
    const impact = await readApi<any>(
      await page.request.get(`/api/decision/definitions/${TASK_ASSIGNMENT_DECISION}/impact`),
    );
    const incoming = Array.isArray(impact?.incoming) ? impact.incoming : [];
    const bpmIncoming = incoming.find(
      (ref: any) =>
        ref?.sourceType === 'BPM_PROCESS' &&
        ref?.sourcePid === pid &&
        ref?.binding === 'DESIGNER_NODE',
    );
    expect(bpmIncoming, `BPM impact incoming missing: ${JSON.stringify(incoming)}`).toBeTruthy();
    expect(JSON.stringify(bpmIncoming)).toContain(TASK_ID);

    await openDecisionDefinitionDetailViaSidebar(page, TASK_ASSIGNMENT_DECISION);
    await expect(page.getByTestId('decision-definition-actions-block')).toBeVisible();
    await expect(page.getByTestId('dda-impact-panel')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('impact-graph-panel')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('impact-incoming')).toContainText('BPM_PROCESS');
    await expect(page.getByTestId('impact-incoming')).toContainText('DESIGNER_NODE');
    await expect(page.getByTestId('impact-incoming')).toContainText(processName);
    await page.getByTestId('impact-graph-panel').scrollIntoViewIfNeeded();
    await page.screenshot({
      path: testInfo.outputPath('bpm-usertask-rule-binding-impact-graph.png'),
      fullPage: true,
    });
  } finally {
    await page.request.delete(`/api/bpm/process-definitions/${pid}`);
  }
});
