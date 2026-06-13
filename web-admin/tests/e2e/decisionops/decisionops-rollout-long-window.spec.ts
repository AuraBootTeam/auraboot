import { test, expect, type APIResponse, type Page, type TestInfo } from '@playwright/test';
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
  version?: number;
  status?: string;
};

type DecisionResult = {
  traceId?: string;
  decisionVersion?: number;
  status?: string;
  matched?: boolean;
};

type DecisionRollout = {
  pid: string;
  decisionCode?: string;
  baselineVersion?: number;
  candidateVersion?: number;
  status?: string;
  percentage?: number;
  cohort?: unknown;
  segment?: unknown;
  audit?: unknown;
};

type DecisionRolloutMetrics = {
  policyPid: string;
  windowHours?: number;
  bucketSeconds?: number;
  retentionDays?: number;
  source?: string;
  latencyAggregation?: string;
  baseline: { evaluations: number; matched: number; resultDistribution?: Record<string, number> };
  candidate: { evaluations: number; matched: number; resultDistribution?: Record<string, number> };
  windows?: Array<{
    baseline: { evaluations: number };
    candidate: { evaluations: number };
  }>;
};

type DecisionLog = {
  decisionCode?: string;
  selectedVersion?: number;
  rolloutPolicyPid?: string;
  rolloutArm?: string;
  routingKey?: string;
  rolloutResultKey?: string;
};

type PageResult<T> = {
  records: T[];
};

const BASELINE_AST = amountGtAst(10_000);
const CANDIDATE_AST = amountGtAst(5_000);

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

function amountGtAst(threshold: number) {
  return {
    type: 'compare',
    left: {
      type: 'path',
      scope: 'record',
      path: 'data.amount',
      dataType: 'decimal',
    },
    operator: 'GT',
    right: {
      type: 'literal',
      value: threshold,
      dataType: 'decimal',
    },
  };
}

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

async function capture(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await page.screenshot({
    path: testInfo.outputPath(`${name}.png`),
    fullPage: true,
  });
}

async function openRolloutGovernanceFromSidebar(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const parent = nav
    .getByRole('button', { name: /决策中心|DecisionOps/i })
    .or(nav.getByRole('link', { name: /决策中心|DecisionOps/i }))
    .first();
  const link = nav
    .locator('a[href="/p/decisionops_rollouts"]')
    .or(nav.getByRole('link', { name: /发布治理|Rollout Governance/i }))
    .first();
  if (!(await link.isVisible({ timeout: 1000 }).catch(() => false))) {
    await parent.click();
  }
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(/\/p\/decisionops_rollouts(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
  await expect(page.getByTestId('decision-rollout-monitor')).toBeVisible({ timeout: 15_000 });
}

async function seedDecisionDefinition(page: Page, decisionCode: string): Promise<void> {
  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Rollout Long Window ${decisionCode}`,
    description: 'DecisionOps rollout long-window E2E fixture',
    scopeType: 'GOVERNANCE',
    ownerModule: 'decision',
    enabled: true,
  });
}

async function createAndPublishVersion(
  page: Page,
  decisionCode: string,
  versionTag: string,
  contentJson: unknown,
): Promise<DecisionVersion> {
  const draft = await postApi<DecisionVersion>(
    page,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`,
    {
      kind: 'SIMPLE_CONDITION',
      runtimeAdapter: 'AST_EVALUATOR',
      versionTag,
      contentJson,
    },
  );
  await postApi(page, `/api/decision/versions/${encodeURIComponent(draft.pid)}/validate`);
  return postApi<DecisionVersion>(page, `/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`, {
    impactAcknowledged: true,
    note: `Publish ${versionTag} for rollout E2E`,
  });
}

async function evaluateRollout(
  page: Page,
  decisionCode: string,
  routingKey: string,
  tenantSegment: string,
): Promise<DecisionResult> {
  const shortCorrelationKey = routingKey.replace(/[^a-zA-Z0-9]/g, '').slice(-20);
  return postApi<DecisionResult>(page, '/api/decision/evaluate', {
    decisionCode,
    binding: 'ROLLOUT',
    callerType: 'E2E',
    callerRef: 'decisionops-rollout-long-window',
    correlationId: `rw-${shortCorrelationKey}-${tenantSegment}`,
    routingKey,
    tenantSegment,
    context: {
      record: {
        data: {
          amount: 6000,
        },
      },
    },
  });
}

async function rolloutLogs(page: Page, decisionCode: string, arm: 'BASELINE' | 'CANDIDATE') {
  const params = new URLSearchParams({
    decisionCode,
    rolloutArm: arm,
    page: '0',
    size: '10',
  });
  return getApi<PageResult<DecisionLog>>(page, `/api/decision/logs/recent?${params.toString()}`);
}

function rolloutRowsForPid(logs: PageResult<DecisionLog>, pid: string): DecisionLog[] {
  return logs.records.filter((record) => record.rolloutPolicyPid === pid);
}

test('rollout governance drives deterministic split, long-window metrics, logs, and audit @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('rollout_window').replace(/[^a-zA-Z0-9_]/g, '_');
  const decisionCode = `codex_rollout_window_${suffix}`;
  const candidateKey = `record-candidate-${suffix}`;
  const secondCandidateKey = `record-candidate-alt-${suffix}`;
  await seedDecisionDefinition(page, decisionCode);
  const baseline = await createAndPublishVersion(page, decisionCode, `baseline-${suffix}`, BASELINE_AST);
  const candidate = await createAndPublishVersion(page, decisionCode, `candidate-${suffix}`, CANDIDATE_AST);
  expect(baseline.version).toBe(1);
  expect(candidate.version).toBe(2);

  await openRolloutGovernanceFromSidebar(page);
  await page.getByLabel('rollout-decision-code').fill(decisionCode);
  await expect(page.getByTestId('rollout-empty')).toBeVisible({ timeout: 15_000 });
  await page.getByLabel('rollout-baseline-version').fill('1');
  await page.getByLabel('rollout-candidate-version').fill('2');
  await page.getByLabel('rollout-percentage').fill('100');
  await page.getByLabel('rollout-routing-key').fill('routingKey');
  await page.getByLabel('rollout-cohort-routing-keys').fill(`${candidateKey}, ${secondCandidateKey}`);
  await page.getByLabel('rollout-cohort-trace-prefixes').fill(`vip-${suffix}`);
  await page.getByLabel('rollout-tenant-segments').fill('beta');
  await page.getByLabel('rollout-salt').fill(`salt-${suffix}`);

  const createResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/decision/definitions/${encodeURIComponent(decisionCode)}/rollouts`),
  );
  await page.getByTestId('rollout-create').click();
  const rollout = await readApi<DecisionRollout>(await createResponsePromise);
  const rolloutPid = rollout.pid;
  await expect(page.getByTestId(`rollout-row-${rolloutPid}`)).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId(`rollout-row-${rolloutPid}`)).toContainText('DRAFT');

  const persistedDraft = await getApi<DecisionRollout>(
    page,
    `/api/decision/rollouts/${encodeURIComponent(rolloutPid)}`,
  );
  expect(persistedDraft).toMatchObject({
    decisionCode,
    baselineVersion: 1,
    candidateVersion: 2,
    percentage: 100,
    cohort: {
      routingKeys: [candidateKey, secondCandidateKey],
      traceIdPrefix: [`vip-${suffix}`],
    },
    segment: { tenantSegments: ['beta'] },
  });

  const activateResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/decision/rollouts/${encodeURIComponent(rolloutPid)}/activate`),
  );
  await page.getByTestId(`rollout-activate-${rolloutPid}`).click();
  const activeRollout = await readApi<DecisionRollout>(await activateResponsePromise);
  expect(activeRollout.status).toBe('ACTIVE');
  await expect(page.getByTestId(`rollout-row-${rolloutPid}`)).toContainText('ACTIVE', {
    timeout: 15_000,
  });

  const candidateOne = await evaluateRollout(page, decisionCode, candidateKey, 'beta');
  const candidateTwo = await evaluateRollout(page, decisionCode, secondCandidateKey, 'beta');
  const baselineBySegment = await evaluateRollout(page, decisionCode, candidateKey, 'stable');
  expect(candidateOne).toMatchObject({ decisionVersion: 2, matched: true, status: 'MATCHED' });
  expect(candidateTwo).toMatchObject({ decisionVersion: 2, matched: true, status: 'MATCHED' });
  expect(baselineBySegment).toMatchObject({
    decisionVersion: 1,
    matched: false,
    status: 'NOT_MATCHED',
  });

  const candidateLogs = rolloutRowsForPid(await rolloutLogs(page, decisionCode, 'CANDIDATE'), rolloutPid);
  const baselineLogs = rolloutRowsForPid(await rolloutLogs(page, decisionCode, 'BASELINE'), rolloutPid);
  expect(candidateLogs.map((record) => record.selectedVersion).sort()).toEqual([2, 2]);
  expect(candidateLogs.map((record) => record.routingKey).sort()).toEqual([
    candidateKey,
    secondCandidateKey,
  ].sort());
  expect(candidateLogs.map((record) => record.rolloutResultKey).sort()).toEqual([
    'matched=true,truth=TRUE',
    'matched=true,truth=TRUE',
  ]);
  expect(baselineLogs.map((record) => record.selectedVersion)).toEqual([1]);
  expect(baselineLogs.map((record) => record.routingKey)).toEqual([candidateKey]);
  expect(baselineLogs.map((record) => record.rolloutResultKey)).toEqual([
    'matched=false,truth=FALSE',
  ]);

  const metricsResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().includes(`/api/decision/rollouts/${encodeURIComponent(rolloutPid)}/metrics`) &&
      response.url().includes('windowHours=2160') &&
      response.url().includes('bucketMinutes=5'),
  );
  await page.getByLabel('rollout-metrics-window-hours').fill('2160');
  await page.getByLabel('rollout-metrics-bucket-minutes').fill('5');
  const metrics = await readApi<DecisionRolloutMetrics>(await metricsResponsePromise);
  expect(metrics).toMatchObject({
    policyPid: rolloutPid,
    windowHours: 2160,
    bucketSeconds: 300,
    retentionDays: 90,
    source: 'PRE_AGGREGATED_BUCKETS',
    latencyAggregation: 'MAX_BUCKET_P95',
    baseline: {
      evaluations: 1,
      matched: 0,
      resultDistribution: { 'matched=false,truth=FALSE': 1 },
    },
    candidate: {
      evaluations: 2,
      matched: 2,
      resultDistribution: { 'matched=true,truth=TRUE': 2 },
    },
  });
  const windowTotals = (metrics.windows ?? []).reduce(
    (totals, window) => ({
      baseline: totals.baseline + window.baseline.evaluations,
      candidate: totals.candidate + window.candidate.evaluations,
    }),
    { baseline: 0, candidate: 0 },
  );
  expect(windowTotals).toEqual({ baseline: 1, candidate: 2 });
  await expect(page.getByTestId('rollout-metrics-meta')).toContainText('Window 2160h', {
    timeout: 15_000,
  });
  await expect(page.getByTestId('rollout-metrics-meta')).toContainText('Bucket 5m');
  await expect(page.getByTestId('rollout-metrics-meta')).toContainText('Retention 90d');
  await expect(page.getByTestId('rollout-metrics-candidate')).toContainText('Eval 2');
  await expect(page.getByTestId('rollout-metrics-baseline')).toContainText('Eval 1');
  await expect(page.getByTestId('rollout-window-trend')).toBeVisible();
  await capture(page, testInfo, 'decisionops-rollout-long-window-metrics');

  await page.getByTestId(`rollout-promote-${rolloutPid}`).click();
  await expect(page.getByTestId('rollout-confirm-panel')).toBeVisible();
  await page.getByLabel('rollout-action-note').fill('candidate metrics accepted after long-window check');
  const promoteResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/decision/rollouts/${encodeURIComponent(rolloutPid)}/promote`),
  );
  await page.getByTestId('rollout-confirm-action').click();
  const promoted = await readApi<DecisionRollout>(await promoteResponsePromise);
  expect(promoted.status).toBe('PROMOTED');
  await expect(page.getByTestId(`rollout-row-${rolloutPid}`)).toContainText('PROMOTED', {
    timeout: 15_000,
  });
  const promotedDetail = await getApi<DecisionRollout>(
    page,
    `/api/decision/rollouts/${encodeURIComponent(rolloutPid)}`,
  );
  expect(JSON.stringify(promotedDetail.audit)).toContain('candidate metrics accepted after long-window check');
});
