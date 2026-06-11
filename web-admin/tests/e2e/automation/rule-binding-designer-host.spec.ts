import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';
import {
  dragNodeToCanvas,
  fillNodeConfig,
  saveAutomation,
  deleteViaApi,
} from '../_helpers/flow-designer-harness';

const DESIGNER_NEW = '/automation/new';
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const MODEL_LABEL = '投诉';

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

async function openNewDesigner(page: Page): Promise<void> {
  await page.goto(DESIGNER_NEW, { waitUntil: 'domcontentloaded' });
  await page
    .locator('[data-testid="automation-editor-name-input"]')
    .waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('[data-testid="flow-palette"]').waitFor({ state: 'visible', timeout: 20_000 });
  await page.locator('[data-testid="palette-node-trigger-record-create"]').waitFor({
    state: 'visible',
    timeout: 20_000,
  });
  await page.locator('.react-flow__pane').waitFor({ state: 'visible', timeout: 10_000 });
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

test('Automation trigger property panel hosts the rule center binding editor and persists it @golden', async ({
  page,
  baseURL,
}, testInfo) => {
  const resolvedBaseURL = baseURL ?? 'http://127.0.0.1:5212';
  await loginAsAdmin(page, resolvedBaseURL);
  await openNewDesigner(page);

  const name = `Rule binding host ${uniqueId()}`;
  await setAutomationName(page, name);

  const triggerId = await dragNodeToCanvas(page, 'trigger-record-create', { x: 150, y: 80 });
  await fillNodeConfig(page, triggerId, { modelCode: MODEL_LABEL });

  const ruleField = page.locator('[data-testid="prop-field-ruleBinding"]');
  await expect(ruleField).toBeVisible();
  await expect(ruleField.locator('[data-testid="rule-binding-property-field"]')).toBeVisible();
  await expect(ruleField.locator('[data-testid="decision-rule-binding-block"]')).toBeVisible();
  await expect(ruleField.locator('[data-testid="decision-binding-editor"]')).toContainText(
    '引用规则中心',
  );
  await expect(ruleField.locator('[data-testid="decision-impact-preview"]')).toBeVisible();
  await expect(ruleField.locator('[data-testid="decision-test-runner"]')).toBeVisible();

  await ruleField.locator('select[aria-label="version-policy"]').selectOption('ROLLOUT');
  await ruleField.getByRole('button', { name: '添加映射' }).click();
  await ruleField.locator('input[aria-label="mapping-input-0"]').fill('amount');
  await expect(ruleField.locator('[data-testid="decision-binding-preview"]')).toContainText(
    '"versionPolicy": "ROLLOUT"',
  );
  await expect(ruleField.locator('[data-testid="decision-binding-preview"]')).toContainText(
    '"input": "amount"',
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
        decisionCode: 'approval_routing',
        versionPolicy: 'ROLLOUT',
        fallbackPolicy: { mode: 'FAIL_CLOSED' },
        enabled: true,
        inputMappings: [
          {
            input: 'amount',
            source: { kind: 'FIELD', scope: 'record', path: 'data.amount' },
          },
        ],
      },
    });
  } finally {
    await deleteViaApi(page, pid);
  }
});
