import { test, expect, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const TS = Date.now();
const PROCESS_KEY = `rc_bpm_${TS}`;
const PROCESS_NAME = `Rule Center BPM ${TS}`;
const GATEWAY_ID = 'route_gateway';

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

function buildDesignerJson() {
  return {
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
        id: 'manual_review',
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
        target: 'manual_review',
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
        source: 'manual_review',
        target: 'end',
        type: 'smoothstep',
        data: {},
      },
    ],
  };
}

async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/bpmn-designer?pid=${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.react-flow')).toBeVisible({ timeout: 15_000 });
  await page.waitForFunction(
    () => Boolean((window as unknown as { __bpmnDesignerStore?: unknown }).__bpmnDesignerStore),
    undefined,
    { timeout: 10_000 },
  );
  await expect(page.locator('.react-flow__node')).toHaveCount(5, { timeout: 10_000 });
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
    await expect(ruleSection.locator('[data-testid="decision-impact-preview"]')).toBeVisible();

    await ruleSection.locator('select[aria-label="version-policy"]').selectOption('ROLLOUT');
    await ruleSection.getByRole('button', { name: '添加映射' }).click();
    await ruleSection.locator('input[aria-label="mapping-input-0"]').fill('amount');
    await expect(ruleSection.locator('[data-testid="decision-binding-preview"]')).toContainText(
      '"versionPolicy": "ROLLOUT"',
    );
    await expect(ruleSection.locator('[data-testid="decision-binding-preview"]')).toContainText(
      '"input": "amount"',
    );

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
  } finally {
    await page.request.delete(`/api/bpm/process-definitions/${pid}`);
  }
});
