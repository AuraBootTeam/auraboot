import { test, expect, type APIResponse, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
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

type JsonResponseLike = Pick<APIResponse, 'json' | 'text' | 'ok' | 'status' | 'url'>;

type DecisionVersion = {
  pid: string;
  status?: string;
  contentJson?: DecisionTableModel;
};

type DecisionResult = {
  status?: string;
  matched?: boolean;
  outputs?: Record<string, unknown>;
  matchedRules?: Array<{ ruleId?: string }>;
};

type DecisionTableDmnXmlResult = {
  valid?: boolean;
  dmnXml?: string;
  model?: DecisionTableModel;
  errors?: Array<{ code?: string; message?: string }>;
};

type DecisionTableModel = {
  hitPolicy?: string;
  aggregation?: string;
  inputs?: Array<{
    id?: string;
    label?: string;
    scope?: string;
    path?: string;
    dataType?: string;
  }>;
  outputs?: Array<{
    id?: string;
    label?: string;
    dataType?: string;
    allowedValues?: unknown[];
  }>;
  rules?: Array<{
    ruleId?: string;
    when?: Record<string, { operator?: string; value?: unknown; feel?: string }>;
    then?: Record<string, unknown>;
  }>;
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

async function postApi<T>(page: Page, endpoint: string, data?: unknown): Promise<T> {
  return readApi<T>(await page.request.post(endpoint, { data }));
}

async function getApi<T>(page: Page, endpoint: string): Promise<T> {
  return readApi<T>(await page.request.get(endpoint));
}

async function openDecisionTableWorkbenchFromSidebar(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const parent = nav
    .getByRole('button', { name: /决策中心|DecisionOps/i })
    .or(nav.getByRole('link', { name: /决策中心|DecisionOps/i }))
    .first();
  const link = nav
    .locator('a[href="/p/decisionops_tables"]')
    .or(nav.getByRole('link', { name: /决策表|Decision Tables/i }))
    .first();
  if (!(await link.isVisible({ timeout: 1000 }).catch(() => false))) {
    await parent.click();
  }
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(/\/p\/decisionops_tables(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
  await expect(page.getByTestId('decision-table-workbench-block')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('decision-table-editor')).toBeVisible({ timeout: 15_000 });
}

async function ensureDecisionDefinition(page: Page, decisionCode: string): Promise<void> {
  const existing = await page.request.get(`/api/decision/definitions/${encodeURIComponent(decisionCode)}`);
  let existingBody: ApiEnvelope<unknown> | null = null;
  try {
    existingBody = (await existing.json()) as ApiEnvelope<unknown>;
  } catch {
    existingBody = null;
  }
  if (existing.ok() && isApiSuccess(existingBody)) return;
  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Codex DMN Matrix ${decisionCode}`,
    scopeType: 'GOVERNANCE',
    ownerModule: 'decision',
    enabled: true,
  });
}

async function currentInputId(page: Page, index: number): Promise<string> {
  const testId = await page.locator('[data-testid^="dt-in-"]').nth(index).getAttribute('data-testid');
  expect(testId, `input header ${index} should expose dt-in-*`).toBeTruthy();
  return testId!.replace('dt-in-', '');
}

async function saveDraftAndReadBack(page: Page, decisionCode: string): Promise<DecisionVersion> {
  const draftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`),
  );
  await page.getByTestId('dtw-save-draft').click();
  const draft = await readApi<DecisionVersion>(await draftResponsePromise);
  await expect(page.getByTestId('dtw-workflow-message')).toContainText('草稿已保存', {
    timeout: 15_000,
  });
  const saved = await getApi<DecisionVersion>(page, `/api/decision/versions/${encodeURIComponent(draft.pid)}`);
  expect(saved.contentJson, `saved version ${draft.pid} should include contentJson`).toBeTruthy();
  return saved;
}

test('Decision table editor deepens columns, FEEL cells, hit policies and DMN round-trip @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('dmn_matrix').replace(/[^a-zA-Z0-9_]/g, '_');
  const decisionCode = `codex_dmn_matrix_${suffix}`;
  const decisionName = `Codex DMN Matrix ${suffix}`;
  await ensureDecisionDefinition(page, decisionCode);

  await openDecisionTableWorkbenchFromSidebar(page);
  await page.getByTestId('dtw-decision-code').fill(decisionCode);
  await page.getByLabel('decision-table-name').fill(decisionName);
  await page.getByLabel('decision-table-version-tag').fill(`dmn-matrix-${suffix}`);

  await page.getByLabel('feel-1-amount').fill('< 5000');
  const amountGapResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/tables/analyze'),
  );
  await page.getByTestId('dt-analyze').click();
  const amountGap = await readApi<{
    metrics?: { gapCount?: number };
    errors?: Array<{ code?: string }>;
    warnings?: Array<{ code?: string }>;
  }>(await amountGapResponsePromise);
  const amountGapCodes = [...(amountGap.errors ?? []), ...(amountGap.warnings ?? [])].map(
    (issue) => issue.code,
  );
  expect(amountGapCodes).toContain('DMN_CONTINUOUS_GAP');
  await expect(page.getByTestId('dt-analysis-panel')).toContainText(/DMN_.*GAP/, {
    timeout: 15_000,
  });

  await page.getByTestId('dt-add-input').click();
  await page.getByLabel('input-label-1').fill('Submitted On');
  await page.getByLabel('input-path-1').fill('data.submittedOn');
  await page.getByLabel('input-data-type-1').selectOption('date');
  await page.getByLabel('move-input-up-1').click();
  await expect(page.locator('[data-testid^="dt-in-"]').first()).toContainText('Submitted On');
  await page.getByLabel('move-input-down-0').click();
  await expect(page.locator('[data-testid^="dt-in-"]').nth(1)).toContainText('Submitted On');

  await page.getByTestId('dt-add-output').click();
  await page.getByLabel('output-label-1').fill('Temporary Output');
  await page.getByLabel('output-data-type-1').selectOption('integer');
  await page.getByLabel('delete-output-1').click();
  await expect(page.getByLabel('output-label-1')).toHaveCount(0);

  await page.getByLabel('output-label-0').fill('Risk Score');
  await page.getByLabel('output-data-type-0').selectOption('decimal');
  const submittedOnInputId = await currentInputId(page, 1);

  await page.getByLabel('feel-0-amount').fill('< 5000');
  await page.getByLabel('feel-1-amount').fill('>= 10000');
  await page.getByLabel(`feel-0-${submittedOnInputId}`).fill('< 2026-06-01');
  await page.getByLabel(`feel-1-${submittedOnInputId}`).fill('>= 2026-06-10');
  const analyzeResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/tables/analyze'),
  );
  await page.getByTestId('dt-analyze').click();
  const analysisBody = await readApi<{
    metrics?: { gapCount?: number; continuousInputCount?: number };
    errors?: Array<{ code?: string }>;
    warnings?: Array<{ code?: string }>;
  }>(await analyzeResponsePromise);
  expect((analysisBody.metrics?.continuousInputCount ?? 0) >= 1).toBe(true);
  await expect(page.getByTestId('dt-analysis-panel')).toContainText('DMN_COMPLEX_INPUT_PROOF', {
    timeout: 15_000,
  });

  await page.getByLabel('hit-policy').selectOption('COLLECT');
  await page.getByLabel('collect-aggregation').selectOption('SUM');
  await page.getByLabel('feel-0-amount').fill('>= 10000');
  await page.getByLabel('feel-1-amount').fill('< 30000');
  await page.getByLabel(`feel-0-${submittedOnInputId}`).fill('>= 2026-06-01');
  await page.getByLabel(`feel-1-${submittedOnInputId}`).fill('>= 2026-06-01');
  await page.getByLabel('out-0-route').fill('3');
  await page.getByLabel('out-1-route').fill('5');
  await page.getByTestId('dtw-context-json').fill(
    JSON.stringify(
      {
        record: {
          data: {
            amount: 20000,
            submittedOn: '2026-06-15',
          },
        },
      },
      null,
      2,
    ),
  );
  const collectRunResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/test-run'),
  );
  await page.getByTestId('dtw-test-run').click();
  const collectResult = await readApi<DecisionResult>(await collectRunResponsePromise);
  expect(collectResult.status).toBe('MATCHED');
  expect(collectResult.outputs?.route).toBe(8);
  await expect(page.getByTestId('dtw-test-result')).toContainText(/"route":\s*8/, {
    timeout: 15_000,
  });

  const collectVersion = await saveDraftAndReadBack(page, decisionCode);
  expect(collectVersion.contentJson?.hitPolicy).toBe('COLLECT');
  expect(collectVersion.contentJson?.aggregation).toBe('SUM');
  expect(collectVersion.contentJson?.inputs?.map((input) => input.path)).toEqual([
    'data.amount',
    'data.submittedOn',
  ]);
  expect(collectVersion.contentJson?.inputs?.[1]?.dataType).toBe('date');
  expect(collectVersion.contentJson?.outputs).toHaveLength(1);
  expect(collectVersion.contentJson?.outputs?.[0]?.dataType).toBe('decimal');
  expect(collectVersion.contentJson?.rules?.[0]?.when?.amount?.feel).toBe('>= 10000');
  expect(collectVersion.contentJson?.rules?.[1]?.then?.route).toBe('5');

  await page.getByLabel('hit-policy').selectOption('PRIORITY');
  await expect(page.getByLabel('collect-aggregation')).toHaveCount(0);
  await page.getByLabel('output-label-0').fill('Priority Route');
  await page.getByLabel('output-data-type-0').selectOption('string');
  await page.getByLabel('output-allowed-values-0').fill('urgent,normal');
  await page.getByLabel('out-0-route').fill('normal');
  await page.getByLabel('out-1-route').fill('urgent');
  const priorityRunResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/test-run'),
  );
  await page.getByTestId('dtw-test-run').click();
  const priorityResult = await readApi<DecisionResult>(await priorityRunResponsePromise);
  expect(priorityResult.status).toBe('MATCHED');
  expect(priorityResult.outputs?.route).toBe('urgent');
  expect(priorityResult.matchedRules?.map((rule) => rule.ruleId)).toContain('default-route');
  await expect(page.getByTestId('dtw-test-result')).toContainText(/"route":\s*"urgent"/, {
    timeout: 15_000,
  });

  const priorityVersion = await saveDraftAndReadBack(page, decisionCode);
  expect(priorityVersion.contentJson?.hitPolicy).toBe('PRIORITY');
  expect(priorityVersion.contentJson?.outputs?.[0]?.allowedValues).toEqual(['urgent', 'normal']);
  expect(priorityVersion.contentJson?.rules?.[1]?.then?.route).toBe('urgent');

  const roundTripResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/tables/round-trip'),
  );
  await page.getByTestId('dt-roundtrip-dmn').click();
  const roundTrip = await readApi<DecisionTableDmnXmlResult>(await roundTripResponsePromise);
  expect(roundTrip.valid).toBe(true);
  expect(roundTrip.model?.hitPolicy).toBe('PRIORITY');
  expect(roundTrip.model?.outputs?.[0]?.allowedValues).toEqual(['urgent', 'normal']);
  await expect(page.getByTestId('dt-dmn-status')).toContainText('DMN XML Round-trip 通过', {
    timeout: 15_000,
  });
  await expect(page.getByTestId('dt-dmn-xml')).toHaveValue(/hitPolicy="PRIORITY"/);

  const exportResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/tables/export-dmn'),
  );
  const downloadPromise = page.waitForEvent('download');
  await page.getByTestId('dt-export-dmn').click();
  const [download, exportResponse] = await Promise.all([downloadPromise, exportResponsePromise]);
  const exportResult = await readApi<DecisionTableDmnXmlResult>(exportResponse);
  expect(exportResult.valid).toBe(true);
  expect(exportResult.dmnXml).toContain('hitPolicy="PRIORITY"');
  expect(exportResult.dmnXml).toContain('<outputValues>');
  const suggested = download.suggestedFilename();
  expect(suggested).toBe(`${decisionCode}.dmn.xml`);
  const savedPath = testInfo.outputPath(suggested);
  await download.saveAs(savedPath);
  const xml = await readFile(savedPath, 'utf8');
  expect(xml).toContain('<decisionTable');
  expect(xml).toContain('hitPolicy="PRIORITY"');
  expect(xml).toContain('urgent');
  expect(xml).toContain('submittedOn');

  const importResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/tables/import-dmn'),
  );
  await page.getByTestId('dt-import-dmn').click();
  const imported = await readApi<DecisionTableDmnXmlResult>(await importResponsePromise);
  expect(imported.valid).toBe(true);
  expect(imported.model?.hitPolicy).toBe('PRIORITY');
  expect(imported.model?.inputs?.map((input) => input.path)).toEqual([
    'data.amount',
    'data.submittedOn',
  ]);
  await expect(page.getByTestId('dt-dmn-status')).toContainText('DMN XML 已导入', {
    timeout: 15_000,
  });
  await expect(page.getByLabel('hit-policy')).toHaveValue('PRIORITY');
  await expect(page.getByLabel('output-allowed-values-0')).toHaveValue('urgent,normal');

  await page.screenshot({
    path: testInfo.outputPath('dmn-table-editor-deep.png'),
    fullPage: true,
  });
});
