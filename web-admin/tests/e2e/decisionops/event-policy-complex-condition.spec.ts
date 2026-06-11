import { test, expect, type APIResponse, type Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { ensureSidebarExpanded, uniqueId, waitForDynamicPageLoad } from '../helpers';

type JsonResponseLike = Pick<APIResponse, 'json' | 'text' | 'ok' | 'status' | 'url'>;

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  msg?: string;
  data?: T;
};

type EventPolicyVersion = {
  pid: string;
  status?: string;
  version?: number;
  rulesJson?: unknown;
  rules_json?: unknown;
};

type FieldImpact = {
  fieldRef?: string;
  references?: Array<{
    sourceType?: string;
    sourceCode?: string;
    sourcePid?: string;
    binding?: string;
  }>;
};

type EventPolicyRunResult = {
  status?: string;
  matchedRuleCodes?: string[];
  actionPlans?: unknown[];
};

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

function isApiSuccess<T>(body: ApiEnvelope<T> | null | undefined): body is ApiEnvelope<T> {
  if (!body) return false;
  if (body.success === false) return false;
  const code = body.code;
  return code === undefined || code === null || String(code) === '0';
}

async function readApi<T>(response: JsonResponseLike): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  expect(response.ok(), `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body)}`).toBe(
    true,
  );
  expect(isApiSuccess(body), `API failed: ${JSON.stringify(body)}`).toBe(true);
  return body.data as T;
}

async function openEventPolicyListFromSidebar(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const parent = nav
    .getByRole('button', { name: /决策中心|DecisionOps/i })
    .or(nav.getByRole('link', { name: /决策中心|DecisionOps/i }))
    .first();
  const eventPolicyLink = nav
    .locator('a[href="/p/decisionops_event_policies"]')
    .or(nav.getByRole('link', { name: /Event Policy/i }))
    .first();
  if (!(await eventPolicyLink.isVisible({ timeout: 1000 }).catch(() => false))) {
    await expect(parent).toBeVisible({ timeout: 10_000 });
    await parent.click();
  }
  await expect(eventPolicyLink).toBeVisible({ timeout: 10_000 });
  await eventPolicyLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policies(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
}

function parseRulesJson(value: unknown): unknown[] {
  if (typeof value === 'string') {
    const parsed = JSON.parse(value) as unknown;
    return Array.isArray(parsed) ? parsed : [];
  }
  return Array.isArray(value) ? value : [];
}

function eventPolicyContext(data: Record<string, unknown>) {
  return {
    record: {
      entityCode: 'complaint',
      recordId: uniqueId('cmp'),
      data,
    },
  };
}

test('EventPolicy designer persists complex AND/OR/NOT conditions and backend runtime honors them @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('ep_complex').replace(/[^a-zA-Z0-9_]/g, '_');
  const policyCode = `codex_ep_complex_${suffix}`;
  const targetKey = `complaint_${suffix}`;

  await openEventPolicyListFromSidebar(page);
  await expect(page.getByTestId('event-policy-actions-block')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('epa-new-policy').click();
  await expect(page.getByTestId('epa-editor')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('policy-code').fill(policyCode);
  await page.getByLabel('policy-name').fill(`Codex Complex Condition ${suffix}`);
  await page.getByLabel('policy-event-type').fill('FORM_SUBMITTED');
  await page.getByLabel('policy-target-type').fill('FORM');
  await page.getByLabel('policy-target-key').fill(targetKey);
  await page.getByTestId('epa-save-policy').click();
  await expect(page).toHaveURL(new RegExp(`/p/decisionops_event_policies/view/${policyCode}`), {
    timeout: 15_000,
  });

  const modelFieldsResponse = page.waitForResponse(
    (response) => response.url().includes('/api/decision/model/fields') && response.status() < 400,
    { timeout: 15_000 },
  );
  await page.getByTestId('epa-open-designer').click();
  await expect(page).toHaveURL(/\/p\/decisionops_event_policy_designer\?policyCode=/, {
    timeout: 15_000,
  });
  await expect(page.getByTestId('event-policy-designer-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('epd-workflow')).toBeVisible();
  await readApi<unknown[]>(await modelFieldsResponse);

  await page.getByTestId('epd-step-rules').click();
  await expect(page.getByTestId('policy-rules-editor')).toBeVisible();
  await page.getByTestId('cb-add').click();
  await expect(page.locator('select[aria-label="field-0"] option[value="record:data.priority"]')).toHaveCount(1);
  await page.getByLabel('field-0').selectOption('record:data.priority');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('HIGH');

  await page.getByTestId('cb-add-group').click();
  await page.getByTestId('op-or-1').click();
  await page.getByLabel('field-1-0').selectOption('record:data.amount');
  await page.getByLabel('operator-1-0').selectOption('GT');
  await page.getByLabel('value-1-0').fill('5000');
  await page.getByTestId('cb-add-1').click();
  await page.getByLabel('field-1-1').selectOption('record:data.status');
  await page.getByLabel('operator-1-1').selectOption('EQ');
  await page.getByLabel('value-1-1').fill('VIP');

  await page.getByTestId('cb-add-not').click();
  await expect(page.getByTestId('cb-not-2')).toBeVisible();
  await page.getByLabel('field-2-0').selectOption('record:data.status');
  await page.getByLabel('operator-2-0').selectOption('EQ');
  await page.getByLabel('value-2-0').fill('BLOCKED');
  await expect(page.getByTestId('cb-preview')).toContainText('并且');
  await expect(page.getByTestId('cb-preview')).toContainText('或');
  await expect(page.getByTestId('cb-preview')).toContainText('非');

  await page.getByTestId('epd-step-actions').click();
  await page.getByTestId('epd-add-action').click();
  await page.getByLabel('action-target-0').fill('ROLE:support_manager');
  await page.getByLabel('action-payload-0').fill('{"template":"complex_condition_alert"}');

  await page.screenshot({
    path: testInfo.outputPath('event-policy-complex-condition-configured.png'),
    fullPage: true,
  });

  await page.getByTestId('epd-step-publish').click();
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/definitions/${policyCode}/versions`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-save-draft').click();
  const draft = await readApi<EventPolicyVersion>(await draftResponsePromise);
  expect(draft.pid).toBeTruthy();
  await expect(page.getByTestId('epd-publish-status')).toContainText(/DRAFT|草稿/i, {
    timeout: 10_000,
  });

  const validateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/validate`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-validate-version').click();
  await expect((await readApi<EventPolicyVersion>(await validateResponsePromise)).status).toBe('VALIDATED');
  await expect(page.getByTestId('epd-publish-status')).toContainText(/VALIDATED|已校验/i, {
    timeout: 10_000,
  });

  const publishResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/event-policy/versions/${draft.pid}/publish`),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-publish-version').click();
  await expect((await readApi<EventPolicyVersion>(await publishResponsePromise)).status).toBe('PUBLISHED');
  await expect(page.getByTestId('epd-publish-status')).toContainText(/PUBLISHED|已发布/i, {
    timeout: 10_000,
  });

  await page.getByTestId('epd-step-test').click();
  const runButtonResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && response.url().includes('/api/event-policy/run'),
    { timeout: 15_000 },
  );
  await page.getByTestId('epd-run-published').click();
  await readApi<unknown>(await runButtonResponse);
  await expect(page.getByTestId('epd-run-result')).toBeVisible({ timeout: 10_000 });

  const versions = await readApi<EventPolicyVersion[]>(
    await page.request.get(`/api/event-policy/definitions/${policyCode}/versions`),
  );
  const latest = versions.find((version) => version.pid === draft.pid) ?? versions[0];
  const rules = parseRulesJson(latest.rulesJson ?? latest.rules_json);
  expect(rules[0]).toMatchObject({
    ruleCode: 'R-1',
    condition: {
      type: 'group',
      op: 'AND',
      children: [
        {
          type: 'compare',
          operator: 'EQ',
          left: { scope: 'record', path: 'data.priority' },
          right: { value: 'HIGH' },
        },
        {
          type: 'group',
          op: 'OR',
        },
        {
          type: 'not',
          child: {
            type: 'compare',
            operator: 'EQ',
            left: { scope: 'record', path: 'data.status' },
            right: { value: 'BLOCKED' },
          },
        },
      ],
    },
  });

  const matched = await readApi<EventPolicyRunResult>(
    await page.request.post('/api/event-policy/run', {
      data: {
        eventType: 'FORM_SUBMITTED',
        targetType: 'FORM',
        targetKey,
        context: eventPolicyContext({
          priority: 'HIGH',
          amount: 9000,
          status: 'OPEN',
        }),
      },
    }),
  );
  expect(matched.status).toBe('MATCHED');
  expect(matched.matchedRuleCodes).toContain('R-1');
  expect(matched.actionPlans?.length).toBe(1);

  const notMatched = await readApi<EventPolicyRunResult>(
    await page.request.post('/api/event-policy/run', {
      data: {
        eventType: 'FORM_SUBMITTED',
        targetType: 'FORM',
        targetKey,
        context: eventPolicyContext({
          priority: 'HIGH',
          amount: 9000,
          status: 'BLOCKED',
        }),
      },
    }),
  );
  expect(notMatched.status).toBe('NOT_MATCHED');
  expect(notMatched.matchedRuleCodes ?? []).toEqual([]);
  expect(notMatched.actionPlans ?? []).toEqual([]);

  await readApi(await page.request.post('/api/decision/usage-index/rebuild'));
  const priorityImpact = await readApi<FieldImpact>(
    await page.request.get('/api/decision/fields/impact', {
      params: { fieldRef: 'record.data.priority' },
    }),
  );
  expect(priorityImpact.fieldRef).toBe('record.data.priority');
  expect(priorityImpact.references ?? []).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        sourceType: 'EVENT_POLICY',
        sourceCode: policyCode,
        sourcePid: draft.pid,
        binding: 'VERSION_RULES',
      }),
    ]),
  );

  await page.screenshot({
    path: testInfo.outputPath('event-policy-complex-condition-published.png'),
    fullPage: true,
  });
});
