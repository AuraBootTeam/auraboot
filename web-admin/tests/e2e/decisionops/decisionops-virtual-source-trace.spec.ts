import { test, expect, type APIResponse, type Page } from '@playwright/test';
import { execSync } from 'node:child_process';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { PG_ENV, PSQL_BASE } from '../../helpers/environments';
import { ensureSidebarExpanded, uniqueId, waitForDynamicPageLoad } from '../helpers';

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  msg?: string;
  data?: T;
};

type MetaModelRecord = {
  pid: string;
  code?: string;
  status?: string;
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

type DecisionLogRecord = {
  pid?: string;
  traceId?: string;
  correlationId?: string;
  decisionCode?: string;
  traceSnapshot?: {
    virtualSources?: Array<{
      sourceRef?: string;
      modelCode?: string;
      recordId?: string;
      status?: string;
      fields?: Record<string, unknown>;
    }>;
    unknownReasons?: string[];
  };
};

type DecisionLogPage = {
  records?: DecisionLogRecord[];
};

const E2E_PG_ENV = { ...PG_ENV, PGPASSWORD: PG_ENV.PGPASSWORD ?? 'auraboot' };

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

function sqlLiteral(value: string): string {
  return value.replace(/'/g, "''");
}

function assertSqlIdentifier(identifier: string): string {
  expect(identifier, 'fixture SQL identifier must be generated, not user input').toMatch(/^[a-z][a-z0-9_]*$/);
  return identifier;
}

function psql(sql: string): string {
  return execSync(`${PSQL_BASE} -P pager=off -t -A -c "${sql.replace(/"/g, '\\"')}"`, {
    encoding: 'utf-8',
    env: E2E_PG_ENV,
    timeout: 10_000,
  }).trim();
}

function currentAdminTenantId(): string {
  const tenantId = psql(`
    SELECT tm.tenant_id
      FROM ab_user u
      JOIN ab_tenant_member tm ON tm.user_id = u.id
      JOIN ab_tenant t ON t.id = tm.tenant_id
     WHERE lower(u.email) = lower('${sqlLiteral(DEFAULT_TEST_ACCOUNT.email)}')
       AND coalesce(u.deleted_flag, false) = false
       AND coalesce(tm.deleted_flag, false) = false
       AND coalesce(t.deleted_flag, false) = false
     ORDER BY CASE WHEN t.name <> 'System' THEN 0 ELSE 1 END,
              CASE WHEN t.name = 'AuraBoot Dev' OR t.display_name = 'AuraBoot Dev' THEN 0 ELSE 1 END,
              tm.tenant_id
     LIMIT 1
  `);
  expect(tenantId, 'admin tenant fixture must exist').toMatch(/^\d+$/);
  return tenantId;
}

function createRiskScoreView(viewName: string, tenantId: string): void {
  const ident = assertSqlIdentifier(viewName);
  psql(`
    CREATE OR REPLACE VIEW ${ident} AS
    SELECT id,
           id AS tenant_id,
           91::integer AS "slaRiskScore"
      FROM ab_tenant
     WHERE id = ${tenantId}
  `);
}

async function createAndPublishVirtualModel(
  page: Page,
  args: { modelCode: string; viewName: string },
  onCreatedPid: (pid: string) => void,
): Promise<MetaModelRecord> {
  const created = await postApi<MetaModelRecord>(page, '/api/meta/models', {
    code: args.modelCode,
    displayName: `E2E Virtual Risk ${args.modelCode}`,
    description: 'E2E fixture: SQL view-backed virtual model for DecisionOps trace evidence',
    modelType: 'view',
    modelCategory: 'REFERENCE',
    sourceType: 'sqlView',
    sourceRef: args.viewName,
    primaryKey: 'id',
    capabilities: {
      list: true,
      detail: true,
      export: true,
      sort: true,
      filter: true,
      paginate: true,
      sortableFields: ['id', 'slaRiskScore'],
      filterableFields: ['id', 'slaRiskScore'],
      detailKeyField: 'id',
    },
    fields: [
      {
        code: 'id',
        displayName: 'ID',
        dataType: 'integer',
        primaryKey: true,
        sortable: true,
        filterable: true,
      },
      {
        code: 'slaRiskScore',
        displayName: 'SLA Risk Score',
        dataType: 'integer',
        sortable: true,
        filterable: true,
      },
    ],
    extension: {
      sourceType: 'sqlView',
      sourceRef: args.viewName,
      primaryKey: 'id',
    },
  });
  expect(created.pid).toBeTruthy();
  onCreatedPid(created.pid);

  const published = await postApi<MetaModelRecord>(
    page,
    `/api/meta/models/${encodeURIComponent(created.pid)}/publish?versionNote=${encodeURIComponent(
      'DecisionOps virtual-source trace E2E',
    )}`,
  );
  expect(String(published.status ?? '')).toMatch(/published/i);
  return published;
}

async function createAndPublishDecision(page: Page, decisionCode: string): Promise<DecisionVersion> {
  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Virtual Source Trace ${decisionCode}`,
    description: 'E2E decision reads slaRiskScore from a low-code virtual model source',
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
      versionTag: `virtual-source-${Date.now()}`,
      contentJson: {
        type: 'compare',
        left: {
          type: 'path',
          scope: 'record',
          path: 'data.slaRiskScore',
          dataType: 'integer',
        },
        operator: 'GT',
        right: {
          type: 'literal',
          value: 80,
          dataType: 'integer',
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

test('DecisionOps execution logs show low-code virtual-source trace from a real evaluation @golden', async ({
  page,
}, testInfo) => {
  const consoleErrors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error' && !isDevNoise(msg.text())) {
      consoleErrors.push(msg.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(`PAGEERROR: ${error.message}`));

  const suffix = uniqueId('virtual_trace').replace(/[^a-zA-Z0-9_]/g, '_').toLowerCase();
  const viewName = assertSqlIdentifier(`v_drt_e2e_risk_${suffix}`);
  const modelCode = `drt_e2e_virtual_risk_${suffix}`;
  const decisionCode = `drt_e2e_virtual_decision_${suffix}`;
  const correlationId = `drt-e2e-virtual-${suffix}`;
  let createdModelPid: string | null = null;

  try {
    await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
    await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

    const tenantId = currentAdminTenantId();
    createRiskScoreView(viewName, tenantId);
    const virtualModel = await createAndPublishVirtualModel(page, { modelCode, viewName }, (pid) => {
      createdModelPid = pid;
    });

    const publishedDecision = await createAndPublishDecision(page, decisionCode);
    expect(String(publishedDecision.status ?? '')).toMatch(/published/i);

    const evaluation = await postApi<DecisionResult>(page, '/api/decision/evaluate', {
      decisionCode,
      binding: 'LATEST',
      callerType: 'E2E',
      callerRef: 'decisionops-virtual-source-trace',
      correlationId,
      routingKey: suffix,
      context: {
        record: {
          data: {},
        },
        meta: {
          virtualSources: [
            {
              sourceRef: viewName,
              recordId: tenantId,
            },
          ],
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
    expect(log?.traceSnapshot?.virtualSources?.[0]).toMatchObject({
      sourceRef: viewName,
      modelCode,
      recordId: tenantId,
      status: 'RESOLVED',
      fields: {
        slaRiskScore: 91,
      },
    });
    expect(log?.traceSnapshot?.virtualSources?.[0]?.fields ?? {}).not.toHaveProperty('tenant_id');

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
    await expect(row).toContainText(decisionCode);
    await page.getByTestId(`elta-open-trace-${log!.pid}`).click();
    await expect(page.getByTestId('elta-trace-drawer')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('elta-trace-chain')).toBeVisible();

    const virtualSourceSection = page.getByTestId(`elta-virtual-sources-${log!.pid}`);
    await expect(virtualSourceSection).toBeVisible({ timeout: 10_000 });
    await expect(virtualSourceSection).toContainText('虚拟源');
    await expect(virtualSourceSection).toContainText(viewName);
    await expect(virtualSourceSection).toContainText(modelCode);
    await expect(virtualSourceSection).toContainText('RESOLVED');
    await expect(virtualSourceSection).toContainText('slaRiskScore');
    await expect(virtualSourceSection).toContainText('91');
    await expect(virtualSourceSection).not.toContainText('tenant_id');

    await page.screenshot({
      path: testInfo.outputPath('decisionops-virtual-source-trace.png'),
      fullPage: true,
    });
    expect(consoleErrors).toEqual([]);
  } finally {
    if (createdModelPid) {
      await page.request.delete(`/api/meta/models/${encodeURIComponent(createdModelPid)}`).catch(() => null);
    }
    psql(`DROP VIEW IF EXISTS ${viewName}`);
  }
});
