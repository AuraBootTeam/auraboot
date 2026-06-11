import { test, expect, type APIResponse, type Page } from '@playwright/test';
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

type DecisionVersion = {
  pid: string;
  contentJson?: DecisionTableModel;
};

type DecisionResult = {
  status?: string;
  matched?: boolean;
  outputs?: Record<string, unknown>;
  matchedRules?: Array<{ ruleId?: string }>;
};

type DecisionTableAnalysis = {
  valid?: boolean;
  errors?: Array<{ code?: string }>;
  warnings?: Array<{ code?: string }>;
};

type DecisionTableModel = {
  inputs?: Array<{ id?: string; path?: string; dataType?: string }>;
  rules?: Array<{
    ruleId?: string;
    when?: Record<string, { feel?: string }>;
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

async function readApi<T>(response: Pick<APIResponse, 'json' | 'text' | 'ok' | 'status' | 'url'>): Promise<T> {
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
    decisionName: `Codex FEEL Builtins ${decisionCode}`,
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
  return getApi<DecisionVersion>(page, `/api/decision/versions/${encodeURIComponent(draft.pid)}`);
}

function issueCodes(analysis: DecisionTableAnalysis): string[] {
  return [...(analysis.errors ?? []), ...(analysis.warnings ?? [])]
    .map((issue) => issue.code)
    .filter(Boolean) as string[];
}

test('Decision table FEEL built-ins parse, analyze, run, and persist @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('feel_builtins').replace(/[^a-zA-Z0-9_]/g, '_');
  const decisionCode = `codex_feel_builtins_${suffix}`;
  await ensureDecisionDefinition(page, decisionCode);

  await openDecisionTableWorkbenchFromSidebar(page);
  await page.getByTestId('dtw-decision-code').fill(decisionCode);
  await page.getByLabel('decision-table-name').fill(`Codex FEEL Builtins ${suffix}`);
  await page.getByLabel('decision-table-version-tag').fill(`feel-builtins-${suffix}`);

  await page.getByTestId('dt-add-input').click();
  await page.getByLabel('input-label-1').fill('Submitted On');
  await page.getByLabel('input-path-1').fill('data.submittedOn');
  await page.getByLabel('input-data-type-1').selectOption('date');

  await page.getByTestId('dt-add-input').click();
  await page.getByLabel('input-label-2').fill('SLA Duration');
  await page.getByLabel('input-path-2').fill('data.sla');
  await page.getByLabel('input-data-type-2').selectOption('duration');

  const submittedOnInputId = await currentInputId(page, 1);
  const slaInputId = await currentInputId(page, 2);
  await page.getByLabel('feel-0-amount').fill('-');
  await page.getByLabel('feel-1-amount').fill('-');
  await page.getByLabel(`feel-0-${submittedOnInputId}`).fill('>= date(2026, 6, 10)');
  await page.getByLabel(`feel-0-${slaInputId}`).fill('<= duration("P2D")');
  await page.getByLabel(`feel-1-${submittedOnInputId}`).fill('-');
  await page.getByLabel(`feel-1-${slaInputId}`).fill('-');
  await page.getByLabel('out-0-route').fill('fast');
  await page.getByLabel('out-1-route').fill('fallback');
  await expect(page.getByTestId('dtw-local-diagnostics')).toHaveCount(0);

  const analyzeResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/tables/analyze'),
  );
  await page.getByTestId('dt-analyze').click();
  const analysis = await readApi<DecisionTableAnalysis>(await analyzeResponsePromise);
  expect(analysis.valid).toBe(true);
  expect(issueCodes(analysis)).not.toContain('DMN_UNSUPPORTED_FEEL');
  expect(issueCodes(analysis)).not.toContain('DMN_FEEL_PARSE');
  await expect(page.getByTestId('dt-analysis-panel')).toContainText('校验通过', {
    timeout: 15_000,
  });

  await page.getByTestId('dtw-context-json').fill(
    JSON.stringify(
      {
        record: {
          data: {
            amount: 1,
            submittedOn: '2026-06-11',
            sla: 'P1D',
          },
        },
      },
      null,
      2,
    ),
  );
  const runResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/test-run'),
  );
  await page.getByTestId('dtw-test-run').click();
  const result = await readApi<DecisionResult>(await runResponsePromise);
  expect(result).toMatchObject({
    status: 'MATCHED',
    matched: true,
    outputs: { route: 'fast' },
  });
  expect(result.matchedRules?.map((rule) => rule.ruleId)).toContain('high-value');
  await expect(page.getByTestId('dtw-test-result')).toContainText(/"route":\s*"fast"/, {
    timeout: 15_000,
  });

  const saved = await saveDraftAndReadBack(page, decisionCode);
  expect(saved.contentJson?.inputs?.map((input) => `${input.path}:${input.dataType}`)).toEqual([
    'data.amount:decimal',
    'data.submittedOn:date',
    'data.sla:duration',
  ]);
  expect(saved.contentJson?.rules?.[0]?.when?.[submittedOnInputId]?.feel).toBe('>= date(2026, 6, 10)');
  expect(saved.contentJson?.rules?.[0]?.when?.[slaInputId]?.feel).toBe('<= duration("P2D")');

  await page.screenshot({
    path: testInfo.outputPath('dmn-feel-builtins.png'),
    fullPage: true,
  });
});
