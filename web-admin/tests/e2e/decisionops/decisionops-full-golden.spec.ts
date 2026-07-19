import {
  test,
  expect,
  type APIResponse,
  type Browser,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { ensureSidebarExpanded, uniqueId, waitForDynamicPageLoad } from '../helpers';

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  msg?: string;
  data?: T;
};

type DecisionVersion = {
  pid: string;
  version?: number;
  status?: string;
  decisionCode?: string;
};

type ConnectorRecord = {
  pid: string;
  name: string;
};

type WebhookRecord = {
  pid: string;
  name: string;
  eventType?: string;
  event_type?: string;
};

type DecisionLogRecord = {
  pid?: string;
  traceId?: string;
  correlationId?: string;
  decisionCode?: string;
  outputSnapshot?: Record<string, unknown>;
};

type DecisionLogPage = {
  records?: DecisionLogRecord[];
};

type FailedResponse = {
  method: string;
  status: number;
  url: string;
  body?: string;
};

const DECISION_OUTPUT_TABLE = {
  hitPolicy: 'FIRST',
  inputs: [
    {
      id: 'amount',
      label: 'Amount',
      scope: 'record',
      path: 'data.amount',
      dataType: 'decimal',
    },
  ],
  outputs: [
    { id: 'deadlineMinutes', label: 'Deadline minutes', dataType: 'integer' },
    { id: 'severity', label: 'Severity', dataType: 'string' },
    { id: 'notificationTemplate', label: 'Notification template', dataType: 'string' },
  ],
  rules: [
    {
      ruleId: 'high_value_deadline',
      priority: 10,
      when: {
        amount: { operator: 'EQ', value: '', feel: '> 10000' },
      },
      then: {
        deadlineMinutes: 45,
        severity: 'warning',
        notificationTemplate: 'High value request ${record.data.amount}',
      },
    },
    {
      ruleId: 'standard_deadline',
      priority: 20,
      when: {},
      then: {
        deadlineMinutes: 120,
        severity: 'normal',
        notificationTemplate: 'Standard request',
      },
    },
  ],
};

const DECISION_OUTPUT_SCHEMA = {
  outputs: DECISION_OUTPUT_TABLE.outputs.map((output) => ({
    id: output.id,
    label: output.label,
    dataType: output.dataType,
  })),
};

const ignoredConsolePatterns = [
  /favicon/i,
  /Failed to load resource.*(?:404|net::ERR_ABORTED)/i,
  /Failed to load resource: the server responded with a status of (?:400|500)/i,
];

test.use({ storageState: { cookies: [], origins: [] } });

function assertApiSuccess<T>(response: APIResponse, body: ApiEnvelope<T>): T {
  expect(
    response.ok(),
    `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body)}`,
  ).toBe(true);
  const code = body.code;
  if (code !== undefined && code !== null && String(code) !== '0') {
    throw new Error(`API returned non-success code ${String(code)}: ${JSON.stringify(body)}`);
  }
  if (body.success === false) {
    throw new Error(`API returned success=false: ${JSON.stringify(body)}`);
  }
  return body.data as T;
}

async function readApi<T>(response: APIResponse): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  return assertApiSuccess(response, body);
}

async function postApi<T>(page: Page, endpoint: string, data?: unknown): Promise<T> {
  return readApi<T>(await page.request.post(endpoint, { data }));
}

async function getApi<T>(page: Page, endpoint: string): Promise<T> {
  return readApi<T>(await page.request.get(endpoint));
}

async function capture(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await page.screenshot({
    path: testInfo.outputPath(`${name}.png`),
    fullPage: true,
  });
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function expectNoLegacyConsoleLinks(
  page: Page,
  options: { allowConsoleContainer?: boolean } = {},
): Promise<void> {
  if (!options.allowConsoleContainer) {
    await expect(page.getByTestId('decisionops-console')).toHaveCount(0);
  }
  const legacyLinks = await page.evaluate(() =>
    Array.from(document.querySelectorAll('a, button'))
      .map((element) => {
        const anchor = element instanceof HTMLAnchorElement ? element : null;
        return {
          text: element.textContent?.trim() ?? '',
          href: anchor?.getAttribute('href') ?? '',
          dataHref: element.getAttribute('data-href') ?? '',
          onClick: element.getAttribute('onclick') ?? '',
        };
      })
      .filter((entry) => {
        const values = [entry.href, entry.dataHref, entry.onClick].filter(Boolean);
        return values.some((value) => {
          if (entry.href === '/decision-ops' && value === '/decision-ops') {
            return false;
          }
          return value.includes('/decision-ops');
        });
      }),
  );
  expect(legacyLinks).toEqual([]);
}

async function loginAsAdmin(page: Page): Promise<void> {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);
}

async function seedDecision(page: Page, decisionCode: string): Promise<DecisionVersion> {
  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Golden Decision ${decisionCode}`,
    description: 'DecisionOps full golden E2E fixture',
    scopeType: 'AUTOMATION',
    ownerModule: 'decision',
    enabled: true,
  });

  return postApi<DecisionVersion>(
    page,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`,
    {
      kind: 'DECISION_TABLE',
      runtimeAdapter: 'PLATFORM_DECISION_TABLE',
      versionTag: `golden-${Date.now()}`,
      contentJson: DECISION_OUTPUT_TABLE,
      outputSchemaJson: DECISION_OUTPUT_SCHEMA,
    },
  );
}

async function seedConnector(page: Page, name: string): Promise<ConnectorRecord> {
  return postApi<ConnectorRecord>(page, '/api/connectors', {
    name,
    baseUrl: 'https://example.com',
    authType: 'none',
    defaultHeaders: '{}',
    timeoutMs: 5000,
    retryPolicy: '{}',
    enabled: true,
  });
}

async function seedWebhook(page: Page, name: string): Promise<WebhookRecord> {
  return postApi<WebhookRecord>(page, '/api/webhooks', {
    name,
    eventType: 'record_created',
    targetUrl: 'https://example.com/decisionops-golden',
    modelCode: 'crm_complaint',
    enabled: true,
    maxRetries: 1,
    timeoutMs: 3000,
  });
}

async function clickDecisionDefinitionFromSidebar(page: Page): Promise<void> {
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
    if (await parent.isVisible({ timeout: 3000 }).catch(() => false)) {
      await parent.click();
    }
  }
  await expect(definitionsLink).toBeVisible({ timeout: 10000 });
  await definitionsLink.scrollIntoViewIfNeeded();
  await expect
    .poll(
      async () =>
        definitionsLink.evaluate((element) => {
          const rect = element.getBoundingClientRect();
          const x = rect.left + Math.min(24, Math.max(1, rect.width / 2));
          const y = rect.top + rect.height / 2;
          const target = document.elementFromPoint(x, y);
          return Boolean(target && (element === target || element.contains(target)));
        }),
      { timeout: 5000, intervals: [100, 250, 500] },
    )
    .toBe(true);
  await definitionsLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_definitions(?:$|\?)/);
  await waitForDynamicPageLoad(page);
}

async function decisionDefinitionRow(page: Page, decisionCode: string) {
  const row = page.locator('[data-testid^="table-row-"]').filter({ hasText: decisionCode }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await row.scrollIntoViewIfNeeded();
  await row.hover();
  return row;
}

async function searchListIfAvailable(page: Page, keyword: string): Promise<void> {
  const search = page.getByTestId('list-search-input');
  if (!(await search.isVisible({ timeout: 2_000 }).catch(() => false))) return;
  await search.fill(keyword);
  await search.press('Enter');
  await expect
    .poll(
      async () => page.locator('[data-testid^="table-row-"]').count(),
      { timeout: 5_000, intervals: [100, 250, 500] },
    )
    .toBeGreaterThan(0)
    .catch(() => undefined);
}

async function tableRowByText(page: Page, text: string) {
  const row = page.locator('[data-testid^="table-row-"]').filter({ hasText: text }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await row.scrollIntoViewIfNeeded();
  await row.hover();
  return row;
}

async function clickRowAction(page: Page, row: ReturnType<Page['locator']>, actionCode: string): Promise<void> {
  const action = row.getByTestId(`row-action-${actionCode}`).first();
  if (await action.isVisible({ timeout: 1_000 }).catch(() => false)) {
    await action.click();
    return;
  }
  await row.getByTestId('row-action-more').click();
  const dropdownAction = page.getByTestId(`row-action-${actionCode}`).first();
  await expect(dropdownAction).toBeVisible({ timeout: 5_000 });
  await dropdownAction.click();
}

async function openDecisionDefinitionDetailFromList(page: Page, decisionCode: string): Promise<void> {
  const row = await decisionDefinitionRow(page, decisionCode);
  const encoded = encodeURIComponent(decisionCode);
  await Promise.all([
    page.waitForURL(new RegExp(`/p/decisionops_definitions/view/${escapeRegExp(encoded)}`), {
      timeout: 15_000,
    }),
    clickRowAction(page, row, 'detail'),
  ]);
  await waitForDynamicPageLoad(page);
}

async function openDecisionRolloutFromList(page: Page, decisionCode: string): Promise<void> {
  await clickDecisionDefinitionFromSidebar(page);
  const row = await decisionDefinitionRow(page, decisionCode);
  const encoded = encodeURIComponent(decisionCode);
  await Promise.all([
    page.waitForURL(new RegExp(`/p/decisionops_rollouts\\?decisionCode=${escapeRegExp(encoded)}`), {
      timeout: 15_000,
    }),
    clickRowAction(page, row, 'rollout'),
  ]);
  await waitForDynamicPageLoad(page);
}

async function openDecisionOpsSidebarLink(
  page: Page,
  href: string,
  linkName: RegExp,
  expectedUrl: RegExp,
): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const sidebar = page.getByTestId('sidebar');
  const navigationInViewport = async () =>
    page.evaluate(() => {
      const candidates = Array.from(
        document.querySelectorAll<HTMLElement>('[data-testid="sidebar"], nav, aside, [role="navigation"]'),
      );
      return candidates.some((element) => {
        const rect = element.getBoundingClientRect();
        const style = window.getComputedStyle(element);
        return (
          style.display !== 'none' &&
          style.visibility !== 'hidden' &&
          rect.width > 0 &&
          rect.height > 0 &&
          rect.right > 0 &&
          rect.left < window.innerWidth &&
          rect.bottom > 0 &&
          rect.top < window.innerHeight
        );
      });
    });
  const sidebarToggle = page.getByTestId('header-sidebar-toggle');
  if (await sidebarToggle.isVisible({ timeout: 1000 }).catch(() => false)) {
    if (!(await navigationInViewport())) {
      await sidebarToggle.click();
    }
    await expect
      .poll(navigationInViewport, { timeout: 5000, intervals: [100, 250, 500] })
      .toBe(true)
      .catch(() => undefined);
  }
  const navWithTarget = page
    .locator('[data-testid="sidebar"], nav, aside, [role="navigation"]')
    .filter({ has: page.locator(`a[href="${href}"]`) })
    .first();
  const nav =
    (await navWithTarget.count()) > 0
      ? navWithTarget
      : (await sidebar.count()) > 0
        ? sidebar
        : page.locator('nav, aside, [role="navigation"]').first();
  const parent = nav
    .getByRole('button', { name: /规则中心|决策中心|Rule Center|DecisionOps/i })
    .or(nav.getByRole('link', { name: /规则中心|决策中心|Rule Center|DecisionOps/i }))
    .first();
  const targetLink = async () => {
    const candidates = page.locator(`a[href="${href}"]`).or(page.getByRole('link', { name: linkName }));
    const count = await candidates.count();
    for (let index = 0; index < count; index += 1) {
      const candidate = candidates.nth(index);
      const inViewport = await candidate
        .evaluate((element) => {
          const rect = element.getBoundingClientRect();
          const style = window.getComputedStyle(element);
          return (
            style.display !== 'none' &&
            style.visibility !== 'hidden' &&
            rect.width > 0 &&
            rect.height > 0 &&
            rect.right > 0 &&
            rect.left < window.innerWidth &&
            rect.bottom > 0 &&
            rect.top < window.innerHeight
          );
        })
        .catch(() => false);
      if (inViewport) return candidate;
    }
    return candidates.first();
  };
  let link = await targetLink();
  if (!(await link.isVisible({ timeout: 1000 }).catch(() => false))) {
    await expect(parent).toBeVisible({ timeout: 10000 });
    await parent.click();
    link = await targetLink();
  }
  await expect(link).toBeVisible({ timeout: 10000 });
  await link.scrollIntoViewIfNeeded();
  await link.click();
  await expect(page).toHaveURL(expectedUrl, { timeout: 15000 });
  await waitForDynamicPageLoad(page);
}

async function openEventPolicyListFromSidebar(page: Page): Promise<void> {
  await openDecisionOpsSidebarLink(
    page,
    '/p/decisionops_event_policies',
    /事件策略|Event Policy/i,
    /\/p\/decisionops_event_policies(?:$|\?)/,
  );
}

async function openDecisionTableWorkbenchFromSidebar(page: Page): Promise<void> {
  await openDecisionOpsSidebarLink(
    page,
    '/p/decisionops_tables',
    /决策表|Decision Tables/i,
    /\/p\/decisionops_tables(?:$|\?)/,
  );
}

async function openRuleCenterFromSidebar(page: Page): Promise<void> {
  await openDecisionOpsSidebarLink(
    page,
    '/decision-ops',
    /策略工作台|规则中心|Rule Center|DecisionOps/i,
    /\/decision-ops(?:$|\?)/,
  );
}

async function openExecutionLogsFromDefinitionDetail(page: Page, decisionCode: string): Promise<void> {
  const encoded = encodeURIComponent(decisionCode);
  await Promise.all([
    page.waitForURL(new RegExp(`/p/decisionops_execution_logs\\?decisionCode=${escapeRegExp(encoded)}`), {
      timeout: 15_000,
    }),
    page.getByTestId('dda-open-logs').click(),
  ]);
  await waitForDynamicPageLoad(page);
}

async function openModelFieldsFromSidebar(page: Page): Promise<void> {
  await openDecisionOpsSidebarLink(
    page,
    '/p/decisionops_model_fields',
    /数据模型|Data Model/i,
    /\/p\/decisionops_model_fields(?:$|\?)/,
  );
}

async function openModelFieldImpactFromList(
  page: Page,
  fieldPath: string,
  decisionCode: string,
): Promise<void> {
  await openModelFieldsFromSidebar(page);
  await searchListIfAvailable(page, fieldPath);
  let row = page
    .locator('[data-testid^="table-row-"]')
    .filter({ hasText: fieldPath })
    .filter({ hasText: decisionCode })
    .first();
  if (!(await row.isVisible({ timeout: 2_000 }).catch(() => false))) {
    row = await tableRowByText(page, fieldPath);
  } else {
    await row.scrollIntoViewIfNeeded();
    await row.hover();
  }
  await Promise.all([
    page.waitForURL(/\/p\/decisionops_model_fields_impact\?fieldRef=.*data\.amount/, {
      timeout: 15_000,
    }),
    clickRowAction(page, row, 'impact'),
  ]);
  await waitForDynamicPageLoad(page);
}

async function openConnectorsFromSidebar(page: Page): Promise<void> {
  await openDecisionOpsSidebarLink(
    page,
    '/p/decisionops_connectors',
    /集成连接器|Connectors/i,
    /\/p\/decisionops_connectors(?:$|\?)/,
  );
}

async function openConnectorDetailFromList(page: Page, connector: ConnectorRecord): Promise<void> {
  await openConnectorsFromSidebar(page);
  await searchListIfAvailable(page, connector.name);
  const row = await tableRowByText(page, connector.name);
  await Promise.all([
    page.waitForURL(
      new RegExp(`/p/decisionops_connectors/view/${escapeRegExp(encodeURIComponent(connector.pid))}`),
      { timeout: 15_000 },
    ),
    clickRowAction(page, row, 'detail'),
  ]);
  await waitForDynamicPageLoad(page);
}

async function openWebhooksFromSidebar(page: Page): Promise<void> {
  await openDecisionOpsSidebarLink(
    page,
    '/p/decisionops_webhooks',
    /Webhook 治理|Webhook Governance/i,
    /\/p\/decisionops_webhooks(?:$|\?)/,
  );
}

async function openWebhookDetailFromList(page: Page, webhook: WebhookRecord): Promise<void> {
  await openWebhooksFromSidebar(page);
  await searchListIfAvailable(page, webhook.name);
  const row = await tableRowByText(page, webhook.name);
  await Promise.all([
    page.waitForURL(
      new RegExp(`/p/decisionops_webhooks/view/${escapeRegExp(encodeURIComponent(webhook.pid))}`),
      { timeout: 15_000 },
    ),
    clickRowAction(page, row, 'detail'),
  ]);
  await waitForDynamicPageLoad(page);
}

async function assertAnonymousBlocked(browser: Browser): Promise<void> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const anonymous = await context.newPage();
  await anonymous.goto('/p/decisionops_definitions', { waitUntil: 'domcontentloaded' });
  await expect(anonymous).toHaveURL(/\/login(?:$|\?)/);
  await context.close();
}

function isExpectedFailedResponse(response: FailedResponse): boolean {
  const url = new URL(response.url);
  return (
    response.method === 'POST' &&
    response.status === 400 &&
    url.pathname === '/api/event-policy/definitions'
  );
}

test.describe.serial('DecisionOps full-app golden', () => {
  test('covers DSL module actions, negative states, reuse links, and legacy console retirement', async ({
    browser,
    page,
  }, testInfo) => {
    test.setTimeout(180_000);
    const consoleErrors: string[] = [];
    const failedResponseDetails: Array<Promise<FailedResponse>> = [];
    page.on('console', (message) => {
      if (message.type() !== 'error') return;
      const text = message.text();
      if (ignoredConsolePatterns.some((pattern) => pattern.test(text))) return;
      consoleErrors.push(text);
    });
    page.on('pageerror', (error) => consoleErrors.push(error.message));
    page.on('response', (response) => {
      if (response.status() < 400) return;
      failedResponseDetails.push(
        (async () => ({
          method: response.request().method(),
          status: response.status(),
          url: response.url(),
          body: await response.text().catch((error) => `Unable to read body: ${String(error)}`),
        }))(),
      );
    });

    await assertAnonymousBlocked(browser);
    await loginAsAdmin(page);

    const suffix = uniqueId('decisionops').replace(/[^a-zA-Z0-9_]/g, '_');
    const decisionCode = `golden_decision_${suffix}`;
    const policyCode = `golden_policy_${suffix}`;
    const policyCopyCode = `${policyCode}_copy`;
    const connector = await seedConnector(page, `Golden Connector ${suffix}`);
    const webhook = await seedWebhook(page, `Golden Webhook ${suffix}`);
    const draft = await seedDecision(page, decisionCode);

    await clickDecisionDefinitionFromSidebar(page);
    await expectNoLegacyConsoleLinks(page);
    await openDecisionDefinitionDetailFromList(page, decisionCode);
    await expect(page.getByTestId('decision-definition-actions-block')).toBeVisible();
    await expect(page.getByTestId('dda-impact-panel')).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId(`dda-version-${draft.pid}`)).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId(`dda-publish-${draft.pid}`)).toHaveCount(0);
    await page.getByTestId(`dda-validate-${draft.pid}`).click();
    await expect(page.getByTestId('dda-message')).toContainText(/校验成功|validated/i, {
      timeout: 15000,
    });
    await capture(page, testInfo, 'decisionops-definition-detail');

    await postApi<DecisionVersion>(
      page,
      `/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`,
      {
        impactAcknowledged: true,
        note: 'DecisionOps full golden publish',
      },
    );
    const correlationId = `golden-${suffix}`;
    const evaluate = await postApi<{
      matched?: boolean;
      status?: string;
      traceId?: string;
      outputs?: Record<string, unknown>;
    }>(page, '/api/decision/evaluate', {
      decisionCode,
      binding: 'LATEST',
      callerType: 'E2E',
      callerRef: 'decisionops-full-golden',
      correlationId,
      routingKey: `golden-${suffix}`,
      context: {
        record: {
          data: {
            amount: 20000,
          },
        },
      },
    });
    expect(evaluate.matched).toBe(true);
    expect(String(evaluate.status ?? '')).toMatch(/MATCHED|SUCCESS/i);
    expect(evaluate.traceId).toBeTruthy();
    expect(evaluate.outputs).toMatchObject({
      deadlineMinutes: 45,
      severity: 'warning',
      notificationTemplate: 'High value request ${record.data.amount}',
    });

    const decisionLogPage = await getApi<DecisionLogPage>(
      page,
      `/api/decision/logs/recent?decisionCode=${encodeURIComponent(decisionCode)}&keyword=${encodeURIComponent(
        evaluate.traceId ?? correlationId,
      )}&size=5`,
    );
    const outputLog = decisionLogPage.records?.find(
      (log) => log.traceId === evaluate.traceId || log.correlationId === correlationId,
    );
    expect(outputLog?.pid).toBeTruthy();
    expect(outputLog?.outputSnapshot).toMatchObject({
      deadlineMinutes: 45,
      severity: 'warning',
      notificationTemplate: 'High value request ${record.data.amount}',
    });
    const outputLogPid = outputLog!.pid!;

    await openExecutionLogsFromDefinitionDetail(page, decisionCode);
    await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('elta-filters')).toBeVisible();
    await expect(page.getByTestId(`elta-row-${outputLogPid}`)).toBeVisible({ timeout: 15000 });
    await page.getByTestId(`elta-open-trace-${outputLogPid}`).click();
    await expect(page.getByTestId('elta-trace-drawer')).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('elta-trace-chain')).toBeVisible();
    await expect(page.getByTestId(`elta-output-snapshot-${outputLogPid}`)).toContainText(
      'DMN 输出',
    );
    await expect(page.getByTestId(`elta-output-snapshot-${outputLogPid}`)).toContainText(
      'Deadline minutes',
    );
    await expect(page.getByTestId(`elta-output-snapshot-${outputLogPid}`)).toContainText(
      '45',
    );
    await expect(page.getByTestId(`elta-output-snapshot-${outputLogPid}`)).toContainText(
      '严重程度',
    );
    await expect(page.getByTestId(`elta-output-snapshot-${outputLogPid}`)).toContainText('warning');
    await expect(page.getByTestId(`elta-output-snapshot-${outputLogPid}`)).toContainText(
      'Notification template',
    );
    await expect(page.getByTestId(`elta-output-snapshot-${outputLogPid}`)).not.toContainText(
      'deadlineMinutes',
    );
    await expect(page.getByTestId(`elta-output-snapshot-${outputLogPid}`)).not.toContainText(
      'notificationTemplate',
    );
    await capture(page, testInfo, 'decisionops-execution-log-dmn-output');
    await expectNoLegacyConsoleLinks(page);

    await openEventPolicyListFromSidebar(page);
    await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 10000 });
    await page.getByTestId('epa-new-policy').click();
    await expect(page.getByTestId('epa-editor')).toBeVisible();
    await page.getByTestId('epa-save-policy').click();
    await expect(page.getByTestId('epa-error')).toBeVisible({ timeout: 10000 });
    await page.getByLabel('policy-code').fill(policyCode);
    await page.getByLabel('policy-name').fill(`Golden Policy ${suffix}`);
    await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
    await page.getByLabel('policy-target-type').fill('FORM');
    await page.getByLabel('policy-target-key').fill('complaint');
    await page.getByTestId('epa-save-policy').click();
    await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
      timeout: 15000,
    });
    await expect(page.getByTestId('epa-open-designer')).toBeVisible({ timeout: 10000 });
    await page.getByTestId('epa-toggle-enabled').click();
    await expect(page.getByTestId('epa-message')).toContainText(/策略已启用|策略已停用/, {
      timeout: 15000,
    });
    await page.getByTestId('epa-copy-policy').click();
    await page.getByLabel('policy-code').fill(policyCopyCode);
    await page.getByLabel('policy-name').fill(`Golden Policy Copy ${suffix}`);
    await page.getByTestId('epa-save-policy').click();
    await expect(page).toHaveURL(
      new RegExp(`/p/decisionops_event_policies/view/${policyCopyCode}`),
      {
        timeout: 15000,
      },
    );
    await page.getByTestId('epa-open-logs').click();
    await expect(page).toHaveURL(/\/p\/decisionops_execution_logs\?policyCode=/, {
      timeout: 10000,
    });
    await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 10000 });
    await page.goBack({ waitUntil: 'domcontentloaded' });
    await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCopyCode}`), {
      timeout: 15_000,
    });
    await waitForDynamicPageLoad(page);
    await page.getByTestId('epa-open-designer').click();
    await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
      timeout: 10000,
    });
    await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('epd-workflow')).toBeVisible();
    await capture(page, testInfo, 'decisionops-event-policy-designer');
    await expectNoLegacyConsoleLinks(page);

    await openDecisionTableWorkbenchFromSidebar(page);
    await expect(page.getByTestId('decision-table-workbench-block')).toBeVisible({
      timeout: 15000,
    });
    await expect(page.getByTestId('decision-table-editor')).toBeVisible();
    await page.getByTestId('dtw-test-run').click();
    await expect(page.getByTestId('dtw-test-result')).not.toContainText(/NOT_MATCHED|未命中/i, {
      timeout: 15000,
    });
    await expect(page.getByTestId('dtw-test-result')).toContainText(/MATCHED|命中/i, {
      timeout: 15000,
    });
    await expect(page.getByTestId('dtw-test-result')).toContainText(/route|director/i);
    await page.getByLabel('feel-1-amount').fill('< 5000');
    await page.getByTestId('dt-analyze').click();
    await expect(page.getByTestId('dt-analysis-panel')).toContainText(
      /DMN_CONTINUOUS_GAP|连续区间缺口/,
      { timeout: 15000 },
    );
    await expect(page.getByTestId('dt-analysis-panel')).toContainText('[5000..10000]');
    await capture(page, testInfo, 'decisionops-dmn-gap');

    await page.getByTestId('dt-add-input').click();
    await page.getByLabel('input-label-1').fill('Submitted On');
    await page.getByLabel('input-path-1').fill('data.submittedOn');
    await page.getByLabel('input-data-type-1').selectOption('date');
    const dateInputTestId = await page
      .locator('[data-testid^="dt-in-"]')
      .nth(1)
      .getAttribute('data-testid');
    expect(dateInputTestId).toBeTruthy();
    const dateInputId = dateInputTestId!.replace('dt-in-', '');
    await page.getByLabel(`feel-0-${dateInputId}`).fill('< 2026-06-01');
    await page.getByLabel(`feel-1-${dateInputId}`).fill('>= 2026-06-10');
    await page.getByTestId('dt-analyze').click();
    await expect(page.getByTestId('dt-analysis-panel')).toContainText('DMN_COMPLEX_INPUT_PROOF', {
      timeout: 15000,
    });
    await expect(page.getByTestId('dt-analysis-panel')).toContainText('dataType: date');
    await expect(page.getByTestId('dt-analysis-panel')).toContainText('[2026-06-01..2026-06-10)');
    await expect(page.getByTestId('dt-analysis-panel')).toContainText(
      /continuous inputs [2-9]|连续输入 [2-9]/i,
    );
    await capture(page, testInfo, 'decisionops-dmn-complex-date-gap');
    await expectNoLegacyConsoleLinks(page);

    await openModelFieldImpactFromList(page, 'data.amount', decisionCode);
    await expect(page.getByTestId('decision-field-impact')).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('field-impact-risk')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('field-preflight-run').click();
    await expect(page.getByTestId('field-preflight-result')).toBeVisible({ timeout: 15000 });
    await capture(page, testInfo, 'decisionops-field-impact');

    await openConnectorDetailFromList(page, connector);
    await expect(page.getByTestId('decision-integration-impact')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('integration-impact-refresh')).toBeVisible();
    await expect(page.getByTestId('integration-impact-manage')).toHaveAttribute(
      'href',
      '/p/api_connector',
    );
    await expect(page.getByTestId('integration-impact-risk')).toBeVisible({ timeout: 15000 });
    await page.getByTestId('integration-impact-refresh').click();
    await expect(page.getByTestId('integration-impact-risk')).toBeVisible({ timeout: 15000 });

    const webhookTargetCode = webhook.eventType ?? webhook.event_type ?? 'record_created';
    await getApi(
      page,
      `/api/decision/integrations/impact?targetType=WEBHOOK&targetCode=${encodeURIComponent(webhookTargetCode)}`,
    );
    await openWebhookDetailFromList(page, webhook);
    await expect(page.getByTestId('decision-integration-impact')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('integration-impact-manage')).toHaveAttribute(
      'href',
      '/p/webhook',
    );
    await expect(page.getByTestId('integration-impact-risk')).toBeVisible({ timeout: 15000 });
    await capture(page, testInfo, 'decisionops-integration-impact');
    await expectNoLegacyConsoleLinks(page);

    await openDecisionRolloutFromList(page, decisionCode);
    await expect(page.getByTestId('decision-rollout-monitor')).toBeVisible({ timeout: 15000 });
    await expect
      .poll(
        async () => {
          const states = await Promise.all([
            page
              .getByTestId('rollout-create')
              .isVisible()
              .catch(() => false),
            page
              .getByTestId('rollout-empty')
              .isVisible()
              .catch(() => false),
            page
              .getByTestId('rollout-permission-hint')
              .isVisible()
              .catch(() => false),
          ]);
          return states.some(Boolean);
        },
        { timeout: 10000, intervals: [100, 250, 500] },
      )
      .toBe(true);
    await expectNoLegacyConsoleLinks(page);

    await openRuleCenterFromSidebar(page);
    await expect(page).toHaveURL(/\/decision-ops(?:$|\?)/, { timeout: 10000 });
    await expect(page.getByTestId('decisionops-console')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('doc-panel-studio')).toBeVisible({ timeout: 15000 });
    await expectNoLegacyConsoleLinks(page, { allowConsoleContainer: true });

    await page.setViewportSize({ width: 390, height: 844 });
    await openDecisionTableWorkbenchFromSidebar(page);
    await expect(page.getByTestId('decision-table-workbench-block')).toBeVisible({
      timeout: 15000,
    });
    await expect(page.getByTestId('decision-table-editor')).toBeVisible();
    await capture(page, testInfo, 'decisionops-mobile-table');

    const failedResponses = await Promise.all(failedResponseDetails);
    const unexpectedFailedResponses = failedResponses.filter(
      (response) => !isExpectedFailedResponse(response),
    );
    expect(unexpectedFailedResponses).toEqual([]);
    expect(consoleErrors).toEqual([]);
  });
});
