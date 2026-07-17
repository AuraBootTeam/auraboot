import { test, expect, type Page, type Response, type TestInfo } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { ensureSidebarExpanded, uniqueId, waitForDynamicPageLoad } from '../helpers';

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  msg?: string;
  data?: T;
};

type ConditionFragment = {
  pid?: string;
  fragmentCode: string;
  fragmentName?: string;
  version?: number;
  status?: string;
  fieldRefs?: string[];
  decisionRefs?: string[];
};

type ConditionFragmentEvaluation = {
  fragmentCode?: string;
  version?: number;
  matched?: boolean;
  result?: string;
};

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

async function readApi<T>(response: Response): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  expect(response.ok(), `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body)}`).toBe(true);
  const code = body.code;
  expect(code === undefined || code === null || String(code) === '0', JSON.stringify(body)).toBe(true);
  expect(body.success === false, JSON.stringify(body)).toBe(false);
  return body.data as T;
}

async function openConditionFragmentsFromSidebar(page: Page): Promise<void> {
  if (!/\/home(?:$|\?)/.test(page.url())) {
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
  }
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const parent = nav
    .getByRole('button', { name: /规则中心|决策中心|Rule Center|DecisionOps/i })
    .or(nav.getByRole('link', { name: /规则中心|决策中心|Rule Center|DecisionOps/i }))
    .first();
  const conditionFragmentsLink = nav
    .locator('a[href="/p/decisionops_condition_fragments"]')
    .or(nav.getByRole('link', { name: /条件片段库|Condition Fragments/i }))
    .first();

  if (!(await conditionFragmentsLink.isVisible({ timeout: 1_000 }).catch(() => false))) {
    await expect(parent).toBeVisible({ timeout: 10_000 });
    await parent.click();
  }

  await expect(conditionFragmentsLink).toBeVisible({ timeout: 10_000 });
  await conditionFragmentsLink.scrollIntoViewIfNeeded();
  await conditionFragmentsLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_condition_fragments(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
  await expect(page.getByTestId('condition-fragment-library')).toBeVisible({ timeout: 10_000 });
}

async function captureLibrary(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await page.getByTestId('condition-fragment-library').screenshot({
    path: testInfo.outputPath(`${name}.png`),
  });
}

test('Condition fragment library creates, reuses, publishes, and impact-acks a shared v2 fragment @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  await openConditionFragmentsFromSidebar(page);

  const suffix = uniqueId('cfl').replace(/[^a-zA-Z0-9_]/g, '_').toLowerCase();
  const fragmentCode = `cfl_${suffix}`;
  const fragmentName = `条件片段复用 ${suffix}`;

  await page.getByTestId('cfl-open-create').click();
  await expect(page.getByTestId('cfl-editor')).toBeVisible();
  await expect(page.getByTestId('condition-builder')).toBeVisible();

  await page.getByLabel('fragment-code').fill(fragmentCode);
  await page.getByLabel('fragment-name').fill(fragmentName);
  await page.getByLabel('fragment-scope-type').selectOption('SLA');
  await page.getByLabel('fragment-scope-ref').fill('wd_leave_approval');
  await page.getByLabel('fragment-owner-module').fill('workflow-demo');
  await page.getByLabel('fragment-description').fill('规则中心条件片段 golden：SLA 决策绑定和复用影响确认');

  await expect(page.getByLabel('fragment-decision-binding-select')).toContainText('请假审批 SLA 截止时间', {
    timeout: 15_000,
  });
  await page.getByLabel('fragment-decision-binding-select').selectOption('complaint_sla_deadline');
  await page.getByTestId('cfl-add-decision-binding').click();
  await expect(page.getByTestId('cfl-decision-binding-complaint_sla_deadline')).toContainText(
    '请假审批 SLA 截止时间',
  );

  await page.getByTestId('cb-add').click();
  await page.getByLabel('field-0').selectOption('record:data.targetKey');
  await page.getByLabel('operator-0').selectOption('EQ');
  await page.getByLabel('value-0').selectOption('task_manager_approve');
  await expect(page.getByTestId('cb-preview')).toContainText('主管审批节点');

  const createResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/decision/condition-fragments') &&
      response.request().method() === 'POST',
    { timeout: 15_000 },
  );
  await page.getByTestId('cfl-save-fragment').click();
  const createResponse = await createResponsePromise;
  const createRequest = createResponse.request().postDataJSON() as Record<string, any>;
  expect(createRequest).toMatchObject({
    fragmentCode,
    fragmentName,
    scopeType: 'SLA',
    scopeRef: 'wd_leave_approval',
    ownerModule: 'workflow-demo',
  });
  expect(createRequest.conditionSpec).toMatchObject({
    decisionBindings: [
      {
        decisionCode: 'complaint_sla_deadline',
        versionPolicy: 'LATEST_PUBLISHED',
        enabled: true,
      },
    ],
    root: {
      type: 'group',
      children: [
        {
          operator: 'EQ',
          left: { scope: 'record', path: 'data.targetKey' },
          right: { value: 'task_manager_approve' },
        },
      ],
    },
  });
  const created = await readApi<ConditionFragment>(createResponse);
  expect(created).toMatchObject({
    fragmentCode,
    version: 1,
    status: 'DRAFT',
    decisionRefs: ['complaint_sla_deadline'],
  });

  await expect(page.getByTestId(`cfl-row-${fragmentCode}`)).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('cfl-message')).toContainText('已创建条件片段');
  await expect(page.getByTestId('cfl-impact')).toContainText('主管审批 SLA', { timeout: 15_000 });
  await expect(page.getByTestId('cfl-impact')).toContainText('HR 审批 SLA');
  await expect(page.getByTestId('cfl-decision-link-complaint_sla_deadline')).toContainText(
    '请假审批 SLA 截止时间',
  );

  const validateV1ResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/decision/condition-fragment-versions/') &&
      response.url().endsWith('/validate') &&
      response.request().method() === 'POST',
    { timeout: 15_000 },
  );
  await page.getByTestId('cfl-validate-selected').click();
  const validatedV1 = await readApi<ConditionFragment>(await validateV1ResponsePromise);
  expect(validatedV1).toMatchObject({ fragmentCode, version: 1, status: 'VALIDATED' });
  await expect(page.getByTestId('cfl-message')).toContainText('已校验');

  const publishV1ResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/decision/condition-fragment-versions/') &&
      response.url().endsWith('/publish') &&
      response.request().method() === 'POST',
    { timeout: 15_000 },
  );
  await expect(page.getByTestId('cfl-publish-selected')).toBeEnabled();
  await page.getByTestId('cfl-publish-selected').click();
  const publishV1Response = await publishV1ResponsePromise;
  expect(publishV1Response.request().postDataJSON()).toMatchObject({ impactAcknowledged: false });
  const publishedV1 = await readApi<ConditionFragment>(publishV1Response);
  expect(publishedV1).toMatchObject({ fragmentCode, version: 1, status: 'PUBLISHED' });
  await expect(page.getByTestId('cfl-message')).toContainText('已发布');

  const evaluateV1ResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/decision/condition-fragments/${fragmentCode}/evaluate`) &&
      response.request().method() === 'POST',
    { timeout: 15_000 },
  );
  await page.getByTestId('cfl-run-evaluate').click();
  const evaluatedV1 = await readApi<ConditionFragmentEvaluation>(await evaluateV1ResponsePromise);
  expect(evaluatedV1).toMatchObject({ fragmentCode, version: 1, matched: true });
  await expect(page.getByTestId('cfl-evaluation')).toContainText('命中');
  await expect(page.getByTestId('cfl-evaluation')).toContainText('v1');

  await page.getByTestId('cfl-open-version').click();
  await expect(page.getByTestId('cfl-editor')).toBeVisible();
  await expect(page.getByTestId('cfl-decision-binding-complaint_sla_deadline')).toContainText(
    '请假审批 SLA 截止时间',
  );
  await page.getByLabel('fragment-name').fill(`${fragmentName} v2`);
  await page.getByLabel('value-0').selectOption('task_hr_approve');
  await expect(page.getByTestId('cb-preview')).toContainText('HR 审批节点');

  const createV2ResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/decision/condition-fragments/${fragmentCode}/versions`) &&
      response.request().method() === 'POST',
    { timeout: 15_000 },
  );
  await page.getByTestId('cfl-save-fragment').click();
  const createV2Response = await createV2ResponsePromise;
  const createV2Request = createV2Response.request().postDataJSON() as Record<string, any>;
  expect(createV2Request.conditionSpec).toMatchObject({
    decisionBindings: [
      {
        decisionCode: 'complaint_sla_deadline',
        versionPolicy: 'LATEST_PUBLISHED',
        enabled: true,
      },
    ],
    root: {
      children: [
        {
          right: { value: 'task_hr_approve' },
        },
      ],
    },
  });
  const createdV2 = await readApi<ConditionFragment>(createV2Response);
  expect(createdV2).toMatchObject({ fragmentCode, version: 2, status: 'DRAFT' });
  await expect(page.getByTestId('cfl-message')).toContainText(`已创建 ${fragmentCode} v2`);

  const validateV2ResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/decision/condition-fragment-versions/') &&
      response.url().endsWith('/validate') &&
      response.request().method() === 'POST',
    { timeout: 15_000 },
  );
  await page.getByTestId('cfl-validate-selected').click();
  const validatedV2 = await readApi<ConditionFragment>(await validateV2ResponsePromise);
  expect(validatedV2).toMatchObject({ fragmentCode, version: 2, status: 'VALIDATED' });

  await expect(page.getByTestId('cfl-impact')).toContainText('主管审批 SLA', { timeout: 15_000 });
  await expect(page.getByTestId('cfl-impact')).toContainText('HR 审批 SLA');
  await expect(page.getByTestId('cfl-publish-selected')).toBeDisabled();
  await expect(page.getByTestId('cfl-publish-selected')).toHaveAttribute(
    'title',
    /请先确认 [1-9][0-9]* 个复用方影响/,
  );
  await expect(page.getByTestId('cfl-impact-ack')).toBeVisible();

  await page.getByTestId('cfl-impact-ack').check();
  await expect(page.getByTestId('cfl-publish-selected')).toBeEnabled();
  const publishV2ResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/decision/condition-fragment-versions/') &&
      response.url().endsWith('/publish') &&
      response.request().method() === 'POST',
    { timeout: 15_000 },
  );
  await page.getByTestId('cfl-publish-selected').click();
  const publishV2Response = await publishV2ResponsePromise;
  expect(publishV2Response.request().postDataJSON()).toMatchObject({ impactAcknowledged: true });
  const publishedV2 = await readApi<ConditionFragment>(publishV2Response);
  expect(publishedV2).toMatchObject({ fragmentCode, version: 2, status: 'PUBLISHED' });
  await expect(page.getByTestId('cfl-message')).toContainText('已发布');
  await expect(page.getByTestId('cfl-versions')).toContainText('v2');
  await expect(page.getByTestId('cfl-versions')).toContainText('已发布');

  const evaluateV2ResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/decision/condition-fragments/${fragmentCode}/evaluate`) &&
      response.request().method() === 'POST',
    { timeout: 15_000 },
  );
  await page.getByTestId('cfl-run-evaluate').click();
  const evaluatedV2 = await readApi<ConditionFragmentEvaluation>(await evaluateV2ResponsePromise);
  expect(evaluatedV2).toMatchObject({ fragmentCode, version: 2, matched: false });
  await expect(page.getByTestId('cfl-evaluation')).toContainText('未命中');
  await expect(page.getByTestId('cfl-evaluation')).toContainText('v2');

  await captureLibrary(page, testInfo, 'condition-fragment-library-v2-impact-ack');
});
