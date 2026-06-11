import { test, expect, type APIResponse, type Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';
import {
  ensureSidebarExpanded,
  extractRecordId,
  uniqueId,
  waitForDynamicPageLoad,
} from '../helpers';

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
};

type SlaConfigRecord = {
  pid: string;
  name?: string;
  ruleBinding?: unknown;
  rule_binding?: unknown;
};

type DecisionModelField = {
  entityCode?: string;
  path?: string;
  label?: string;
  refs?: number;
};

type DecisionImpact = {
  incoming?: Array<{
    sourceType?: string;
    sourcePid?: string;
    binding?: string;
  }>;
};

type FieldImpact = {
  references?: Array<{
    sourceType?: string;
    sourcePid?: string;
    binding?: string;
    targetPath?: string;
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

async function ensureDecisionDefinition(page: Page, decisionCode: string): Promise<void> {
  const existing = await page.request.get(`/api/decision/definitions/${decisionCode}`);
  const body = (await existing.json().catch(() => null)) as ApiEnvelope<unknown> | null;
  if (existing.ok() && isApiSuccess(body)) return;
  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `SLA Rule Center ${decisionCode}`,
    scopeType: 'SLA',
    ownerModule: 'decision',
    enabled: true,
  });
}

async function publishSlaDecisionVersion(
  page: Page,
  decisionCode: string,
  catalogFieldPath: string,
): Promise<DecisionVersion> {
  await ensureDecisionDefinition(page, decisionCode);
  const draft = await postApi<DecisionVersion>(
    page,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`,
    {
      kind: 'DECISION_TABLE',
      runtimeAdapter: 'PLATFORM_DECISION_TABLE',
      versionTag: `sla-ui-${Date.now()}`,
      contentJson: {
        hitPolicy: 'FIRST',
        inputs: [
          {
            id: 'catalogPriority',
            label: 'Catalog Priority',
            expr: {
              type: 'path',
              scope: 'record',
              path: catalogFieldPath,
              dataType: 'string',
            },
          },
        ],
        outputs: [{ id: 'deadlineMinutes', label: 'Deadline Minutes', dataType: 'integer' }],
        rules: [
          {
            ruleId: 'catalog-priority-high',
            priority: 10,
            when: { catalogPriority: { operator: 'EQ', value: 'HIGH' } },
            then: { deadlineMinutes: 45 },
          },
        ],
        defaultOutput: { deadlineMinutes: 120 },
      },
    },
  );
  await postApi(page, `/api/decision/versions/${draft.pid}/validate`);
  return postApi(page, `/api/decision/versions/${draft.pid}/publish`, {
    impactAcknowledged: true,
    note: 'SLA rule-center binding E2E fixture',
  });
}

async function createSlaConfig(page: Page, name: string, targetKey: string): Promise<string> {
  const response = await page.request.post('/api/meta/commands/execute/admin:create_sla_config', {
    data: {
      operationType: 'create',
      payload: {
        name,
        target_type: 'NODE',
        target_key: targetKey,
        deadline_mode: 'FIXED',
        deadline_value: 'PT24H',
        suspend_policy: 'pause',
        enabled: true,
      },
    },
  });
  const body = await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }));
  expect(response.ok(), `Create SLA config failed: ${JSON.stringify(body)}`).toBe(true);
  const pid = extractRecordId(body);
  expect(Boolean(pid), `Cannot extract SLA pid: ${JSON.stringify(body)}`).toBe(true);
  return pid;
}

async function deleteSlaConfig(page: Page, pid: string): Promise<void> {
  await page.request
    .post('/api/meta/commands/execute/admin:delete_sla_config', {
      data: { targetRecordId: pid, operationType: 'delete', payload: {} },
    })
    .catch(() => undefined);
}

async function openSlaConfigListFromSidebar(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav
    .locator('a[href="/p/sla_config"]')
    .or(nav.getByRole('link', { name: /SLA\s*配置|SLA Configuration/i }))
    .first();
  const adminParent = nav
    .getByRole('button', { name: /管理|Admin|系统|Platform/i })
    .or(nav.getByRole('link', { name: /管理|Admin|系统|Platform/i }))
    .first();
  if (!(await link.isVisible({ timeout: 1000 }).catch(() => false))) {
    await adminParent.click().catch(() => undefined);
  }
  await expect(link).toBeVisible({ timeout: 15_000 });
  await link.click();
  await expect(page).toHaveURL(/\/p\/sla_config(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
}

async function openSlaConfigEditor(page: Page, name: string): Promise<void> {
  await openSlaConfigListFromSidebar(page);
  await page.getByTestId('list-search-input').fill(name);
  await page.getByTestId('list-search-input').press('Enter');
  const row = page.locator('tbody tr').filter({ hasText: name }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await row
    .getByRole('link', { name: /编辑|Edit/i })
    .or(row.getByRole('button', { name: /编辑|Edit/i }))
    .first()
    .click();
  await expect(page.getByTestId('decision-rule-binding-block')).toBeVisible({ timeout: 15_000 });
}

test('SLA config form hosts rule-center binding with backend field catalog and impact evidence @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const suffix = uniqueId('sla_rule').replace(/[^a-zA-Z0-9_]/g, '_');
  const decisionCode = 'complaint_sla_deadline';
  const catalogFieldPath = `data.slaCatalog_${suffix}`;
  const catalogFieldRef = `record.${catalogFieldPath}`;
  const slaName = `Codex SLA Rule Center ${suffix}`;
  const targetKey = `approve_${suffix}`;

  await publishSlaDecisionVersion(page, decisionCode, catalogFieldPath);
  await readApi(await page.request.post('/api/decision/usage-index/rebuild'));

  const pid = await createSlaConfig(page, slaName, targetKey);

  try {
    const modelFieldsResponse = page.waitForResponse(
      (response) => response.url().includes('/api/decision/model/fields') && response.status() < 400,
      { timeout: 15_000 },
    );
    await openSlaConfigEditor(page, slaName);
    const block = page.getByTestId('decision-rule-binding-block');
    const fields = await readApi<DecisionModelField[]>(await modelFieldsResponse);
    expect(
      fields.some((field) => field.entityCode === 'record' && field.path === catalogFieldPath),
      `model field catalog should include ${catalogFieldRef}: ${JSON.stringify(fields)}`,
    ).toBe(true);
    expect(
      fields.some(
        (field) =>
          field.entityCode === 'record' &&
          field.path === 'data.deadline_value' &&
          field.refs === 0 &&
          /SLA|截止|Deadline/i.test(field.label ?? ''),
      ),
      `model field catalog should include sla_config.deadline_value from meta model metadata: ${JSON.stringify(fields)}`,
    ).toBe(true);

    await block.getByLabel('decision-code').selectOption(decisionCode);
    await block.getByLabel('version-policy').selectOption('LATEST_PUBLISHED');
    await block.getByLabel('fallback-mode').selectOption('FAIL_CLOSED');
    await block.getByRole('button', { name: '添加映射' }).click();
    await expect(block.locator(`select[aria-label="mapping-field-0"] option[value="record:${catalogFieldPath}"]`)).toHaveCount(1);
    await block.getByLabel('mapping-input-0').fill('catalogPriority');
    await block.getByLabel('mapping-field-0').selectOption(`record:${catalogFieldPath}`);
    await block.getByRole('button', { name: '添加输出' }).click();
    await block.getByLabel('output-mapping-output-0').fill('deadlineMinutes');
    await block.getByLabel('output-mapping-kind-0').selectOption('SLA_FIELD');
    await block.getByLabel('output-mapping-path-0').fill('deadlineMinutes');

    await block.getByLabel('test-run-context').fill(
      JSON.stringify(
        {
          record: {
            data: {
              targetType: 'NODE',
              targetKey,
              priority: 'HIGH',
              [`slaCatalog_${suffix}`]: 'HIGH',
            },
          },
        },
        null,
        2,
      ),
    );
    const runResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && response.url().includes('/api/decision/evaluate'),
      { timeout: 15_000 },
    );
    await block.getByLabel('run-decision-test').click();
    await readApi(await runResponse);
    await expect(block.getByTestId('decision-test-result')).toContainText('"status": "MATCHED"', {
      timeout: 15_000,
    });
    await expect(block.getByTestId('decision-test-result')).toContainText('"deadlineMinutes": 45');

    const saveResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/api/meta/commands/execute/admin:update_sla_config'),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: /^保存$|^Save$/ }).click();
    await readApi(await saveResponse);

    const saved = await readApi<SlaConfigRecord>(await page.request.get(`/api/bpm/sla-configs/${pid}`));
    const ruleBinding = (saved.ruleBinding ?? saved.rule_binding) as Record<string, unknown>;
    expect(ruleBinding).toMatchObject({
      consumerType: 'SLA',
      bindingKind: 'DECISION_REF',
      decisionBinding: {
        decisionCode,
        versionPolicy: 'LATEST_PUBLISHED',
        inputMappings: [
          {
            input: 'catalogPriority',
            source: { kind: 'FIELD', scope: 'record', path: catalogFieldPath },
          },
        ],
        outputMappings: [
          {
            output: 'deadlineMinutes',
            target: { kind: 'SLA_FIELD', path: 'deadlineMinutes' },
          },
        ],
      },
    });

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('decision-rule-binding-block')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel('mapping-input-0')).toHaveValue('catalogPriority');
    await expect(page.getByLabel('mapping-field-0')).toHaveValue(`record:${catalogFieldPath}`);

    await readApi(await page.request.post('/api/decision/usage-index/rebuild'));
    const impact = await readApi<DecisionImpact>(
      await page.request.get(`/api/decision/definitions/${decisionCode}/impact`),
    );
    expect(impact.incoming ?? []).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          sourceType: 'SLA_RULE',
          sourcePid: pid,
          binding: 'RULE_BINDING',
        }),
      ]),
    );
    const fieldImpact = await readApi<FieldImpact>(
      await page.request.get('/api/decision/fields/impact', {
        params: { fieldRef: catalogFieldRef },
      }),
    );
    expect(fieldImpact.references ?? []).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          sourceType: 'SLA_RULE',
          sourcePid: pid,
          binding: 'RULE_BINDING',
        }),
      ]),
    );

    await page.screenshot({
      path: testInfo.outputPath('sla-rule-center-binding-saved.png'),
      fullPage: true,
    });
  } finally {
    await deleteSlaConfig(page, pid);
  }
});
