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

type DecisionFactCatalog = {
  entities?: Array<{
    modelCode?: string;
    facts?: DecisionFact[];
  }>;
  facts?: DecisionFact[];
};

type DecisionFact = {
  path?: string;
  label?: string;
  dataType?: string;
  dictCode?: string;
  allowedValues?: Array<{ value?: unknown; label?: string }>;
};

type DecisionVersion = {
  pid: string;
  status?: string;
  version?: number;
};

type DecisionResult = {
  status?: string;
  matched?: boolean;
  traceId?: string;
  unknownReasons?: string[];
};

type TraceFactMetadata = {
  label?: string;
  factKey?: string;
  path?: string;
  modelCode?: string;
  dictCode?: string;
  valueLabels?: Record<string, string>;
};

type DecisionLogRecord = {
  pid?: string;
  traceId?: string;
  correlationId?: string;
  decisionCode?: string;
  traceSnapshot?: {
    factMetadata?: Record<string, TraceFactMetadata>;
  };
};

type DecisionLogPage = {
  records?: DecisionLogRecord[];
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
  const options = data === undefined ? undefined : { data };
  return readApi<T>(await page.request.post(endpoint, options));
}

async function getApi<T>(page: Page, endpoint: string): Promise<T> {
  return readApi<T>(await page.request.get(endpoint));
}

function findLeaveTypeFact(catalog: DecisionFactCatalog): DecisionFact | undefined {
  const facts = [
    ...(catalog.facts ?? []),
    ...(catalog.entities ?? []).flatMap((entity) => entity.facts ?? []),
  ];
  return facts.find((fact) => fact.path === 'record.data.wd_req_type' || fact.path === 'data.wd_req_type');
}

function metadataForLeaveType(log: DecisionLogRecord): TraceFactMetadata | undefined {
  const metadata = log.traceSnapshot?.factMetadata ?? {};
  const aliases = [
    metadata['record.data.wd_req_type'],
    metadata['data.wd_req_type'],
    metadata.wd_req_type,
  ].filter((item): item is TraceFactMetadata => Boolean(item));
  if (!aliases.length) return undefined;
  return aliases.reduce<TraceFactMetadata>(
    (merged, item) => ({
      ...merged,
      ...item,
      label: merged.label ?? item.label,
      factKey: merged.factKey ?? item.factKey,
      path: merged.path ?? item.path,
      modelCode: merged.modelCode ?? item.modelCode,
      dictCode: merged.dictCode ?? item.dictCode,
      valueLabels: {
        ...(item.valueLabels ?? {}),
        ...(merged.valueLabels ?? {}),
      },
    }),
    {},
  );
}

async function createAndPublishLeaveTypeDecision(
  page: Page,
  decisionCode: string,
): Promise<DecisionVersion> {
  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Fact Metadata Trace ${decisionCode}`,
    description: 'E2E decision verifies low-code model field metadata is visible in Trace',
    scopeType: 'GOVERNANCE',
    ownerModule: 'decision',
    enabled: true,
  });

  const draft = await postApi<DecisionVersion>(
    page,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`,
    {
      kind: 'SIMPLE_CONDITION',
      runtimeAdapter: 'AST_EVALUATOR',
      versionTag: `fact-metadata-${Date.now()}`,
      contentJson: {
        type: 'compare',
        left: {
          type: 'path',
          scope: 'record',
          path: 'data.wd_req_type',
          dataType: 'enum',
        },
        operator: 'EQ',
        right: {
          type: 'literal',
          value: 'annual',
          dataType: 'enum',
        },
      },
    },
  );
  expect(draft.pid).toBeTruthy();

  const validation = await postApi<{ valid?: boolean }>(
    page,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/validate`,
  );
  expect(validation.valid).toBe(true);

  return postApi<DecisionVersion>(page, `/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`);
}

async function openExecutionLogsFromSidebar(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const parent = nav
    .getByRole('button', { name: /决策中心|DecisionOps/i })
    .or(nav.getByRole('link', { name: /决策中心|DecisionOps/i }))
    .first();
  const logsLink = nav
    .locator('a[href="/p/decisionops_execution_logs"]')
    .or(nav.getByRole('link', { name: /执行日志|Execution Logs/i }))
    .first();
  if (!(await logsLink.isVisible({ timeout: 1000 }).catch(() => false))) {
    await expect(parent).toBeVisible({ timeout: 10_000 });
    await parent.click();
  }
  await expect(logsLink).toBeVisible({ timeout: 10_000 });
  await logsLink.scrollIntoViewIfNeeded();
  await logsLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 15_000 });
}

function isDevNoise(text: string): boolean {
  return /favicon|Failed to fetch dynamically imported module|Outdated Optimize Dep|HMR|Vite|websocket/i.test(text);
}

test('DecisionOps Trace shows low-code model fact metadata and dict value labels @golden', async ({
  page,
}, testInfo) => {
  const consoleErrors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error' && !isDevNoise(msg.text())) {
      consoleErrors.push(msg.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(`PAGEERROR: ${error.message}`));

  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const catalog = await getApi<DecisionFactCatalog>(
    page,
    '/api/decision/facts/catalog?modelCode=wd_leave_request',
  );
  const leaveTypeFact = findLeaveTypeFact(catalog);
  expect(leaveTypeFact).toMatchObject({
    label: expect.stringMatching(/请假类型|Leave Type/i),
    dictCode: 'wd_leave_type',
  });
  expect(leaveTypeFact?.allowedValues).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ value: 'annual', label: expect.stringMatching(/年假|Annual/i) }),
      expect.objectContaining({ value: 'sick', label: expect.stringMatching(/病假|Sick/i) }),
    ]),
  );

  const suffix = uniqueId('fact_meta').replace(/[^a-zA-Z0-9_]/g, '_').toLowerCase();
  const decisionCode = `drt_e2e_fact_meta_${suffix}`;
  const correlationId = `drt-e2e-fact-meta-${suffix}`;
  const published = await createAndPublishLeaveTypeDecision(page, decisionCode);
  expect(String(published.status ?? '')).toMatch(/published/i);

  const evaluation = await postApi<DecisionResult>(page, '/api/decision/evaluate', {
    decisionCode,
    binding: 'LATEST',
    callerType: 'E2E',
    callerRef: 'decisionops-fact-metadata-trace',
    correlationId,
    routingKey: suffix,
    context: {
      record: {
        modelCode: 'wd_leave_request',
        data: {
          wd_req_type: 'annual',
        },
      },
    },
  });
  expect(evaluation.matched).toBe(true);
  expect(String(evaluation.status ?? '')).toMatch(/MATCHED|SUCCESS/i);
  expect(evaluation.traceId).toBeTruthy();
  expect(evaluation.unknownReasons ?? []).toEqual([]);

  const logPage = await getApi<DecisionLogPage>(
    page,
    `/api/decision/logs/recent?decisionCode=${encodeURIComponent(decisionCode)}&keyword=${encodeURIComponent(
      evaluation.traceId ?? correlationId,
    )}&size=5`,
  );
  const log = logPage.records?.find(
    (record) => record.traceId === evaluation.traceId || record.correlationId === correlationId,
  );
  expect(log?.pid).toBeTruthy();
  const leaveTypeMetadata = metadataForLeaveType(log!);
  expect(leaveTypeMetadata).toMatchObject({
    label: expect.stringMatching(/请假类型|Leave Type/i),
    modelCode: 'wd_leave_request',
    dictCode: 'wd_leave_type',
  });
  expect(leaveTypeMetadata?.valueLabels?.annual).toMatch(/年假|Annual/i);

  await openExecutionLogsFromSidebar(page);
  await page.getByLabel('log-keyword').fill(evaluation.traceId!);
  await page.getByLabel('log-decision-code').fill(decisionCode);
  const logsResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().includes('/api/decision/logs/recent') &&
      response.url().includes(encodeURIComponent(decisionCode)),
    { timeout: 15_000 },
  );
  await page.getByTestId('elta-apply').click();
  await logsResponse;

  const row = page.getByTestId(`elta-row-${log!.pid}`);
  await expect(row).toBeVisible({ timeout: 15_000 });
  await page.getByTestId(`elta-open-trace-${log!.pid}`).click();
  await expect(page.getByTestId('elta-trace-drawer')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('elta-trace-chain')).toBeVisible();

  const factMetadata = page.getByTestId(`elta-fact-metadata-${log!.pid}`);
  await expect(factMetadata).toBeVisible({ timeout: 10_000 });
  await expect(factMetadata).toContainText('事实快照');
  await expect(factMetadata).toContainText(/请假类型|Leave Type/i);
  await expect(factMetadata).toContainText('record.data.wd_req_type');
  await expect(factMetadata).toContainText('模型 wd_leave_request');
  await expect(factMetadata).toContainText('字典 wd_leave_type');
  await expect(factMetadata).toContainText('annual');
  await expect(factMetadata).toContainText(/年假|Annual/i);

  await page.screenshot({
    path: testInfo.outputPath('decisionops-fact-metadata-trace.png'),
    fullPage: true,
  });
  expect(consoleErrors).toEqual([]);
});
