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
  reference?: Record<string, unknown>;
};

type UserOption = {
  pid?: string;
  id?: string;
  displayName?: string;
  name?: string;
  realName?: string;
  nickName?: string;
  nickname?: string;
  username?: string;
  email?: string;
};

type DecisionVersion = {
  pid: string;
  status?: string;
  version?: number;
};

type MetaModelRecord = {
  pid: string;
  code?: string;
  status?: string;
};

type MetaFieldRecord = {
  pid: string;
  code?: string;
  status?: string;
  refTarget?: Record<string, unknown>;
};

type DynamicRecord = Record<string, unknown> & {
  pid?: string;
};

type FieldOptionRecord = {
  value?: unknown;
  label?: string;
};

type RoleRecord = {
  pid?: string;
  code?: string;
};

type PermissionRecord = {
  id?: number;
  pid?: string;
  permissionPid?: string;
  code?: string;
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
  dataType?: string;
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

async function readApi<T>(
  response: Pick<APIResponse, 'json' | 'text' | 'ok' | 'status' | 'url'>,
): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  expect(
    response.ok(),
    `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body)}`,
  ).toBe(true);
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
  return facts.find(
    (fact) => fact.path === 'record.data.wd_req_type' || fact.path === 'data.wd_req_type',
  );
}

function findApplicantFact(catalog: DecisionFactCatalog): DecisionFact | undefined {
  const facts = [
    ...(catalog.facts ?? []),
    ...(catalog.entities ?? []).flatMap((entity) => entity.facts ?? []),
  ];
  return facts.find(
    (fact) => fact.path === 'record.data.wd_req_applicant' || fact.path === 'data.wd_req_applicant',
  );
}

function findFactByField(
  catalog: DecisionFactCatalog,
  fieldCode: string,
): DecisionFact | undefined {
  const facts = [
    ...(catalog.facts ?? []),
    ...(catalog.entities ?? []).flatMap((entity) => entity.facts ?? []),
  ];
  return facts.find(
    (fact) => fact.path === `record.data.${fieldCode}` || fact.path === `data.${fieldCode}`,
  );
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

function metadataForApplicant(log: DecisionLogRecord): TraceFactMetadata | undefined {
  const metadata = log.traceSnapshot?.factMetadata ?? {};
  const aliases = [
    metadata['record.data.wd_req_applicant'],
    metadata['data.wd_req_applicant'],
    metadata.wd_req_applicant,
  ].filter((item): item is TraceFactMetadata => Boolean(item));
  if (!aliases.length) return undefined;
  return aliases.reduce<TraceFactMetadata>(
    (merged, item) => ({
      ...merged,
      ...item,
      label: merged.label ?? item.label,
      factKey: merged.factKey ?? item.factKey,
      path: merged.path ?? item.path,
      dataType: merged.dataType ?? item.dataType,
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

function metadataForField(
  log: DecisionLogRecord,
  fieldCode: string,
): TraceFactMetadata | undefined {
  const metadata = log.traceSnapshot?.factMetadata ?? {};
  const aliases = [
    metadata[`record.data.${fieldCode}`],
    metadata[`data.${fieldCode}`],
    metadata[fieldCode],
  ].filter((item): item is TraceFactMetadata => Boolean(item));
  if (!aliases.length) return undefined;
  return aliases.reduce<TraceFactMetadata>(
    (merged, item) => ({
      ...merged,
      ...item,
      label: merged.label ?? item.label,
      factKey: merged.factKey ?? item.factKey,
      path: merged.path ?? item.path,
      dataType: merged.dataType ?? item.dataType,
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

async function resolveFirstUser(page: Page): Promise<{ pid: string; label: string }> {
  const users = await getApi<UserOption[]>(page, '/api/admin/users/search?keyword=&size=20');
  const user = users.find((item) => item.pid || item.id);
  expect(user, 'at least one user must exist for user reference trace evidence').toBeTruthy();
  const pid = String(user?.pid ?? user?.id ?? '');
  const label = String(
    user?.displayName ??
      user?.name ??
      user?.realName ??
      user?.nickName ??
      user?.nickname ??
      user?.username ??
      user?.email ??
      pid,
  );
  expect(pid).not.toEqual('');
  expect(label).not.toEqual('');
  return { pid, label };
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

  return postApi<DecisionVersion>(
    page,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`,
  );
}

async function createAndPublishApplicantDecision(
  page: Page,
  decisionCode: string,
  applicantPid: string,
): Promise<DecisionVersion> {
  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `User Reference Trace ${decisionCode}`,
    description: 'E2E decision verifies user reference metadata is visible in Trace',
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
      versionTag: `user-reference-${Date.now()}`,
      contentJson: {
        type: 'compare',
        left: {
          type: 'path',
          scope: 'record',
          path: 'data.wd_req_applicant',
          dataType: 'user',
        },
        operator: 'EQ',
        right: {
          type: 'literal',
          value: applicantPid,
          dataType: 'user',
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

  return postApi<DecisionVersion>(
    page,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`,
  );
}

async function createDraftPhysicalModel(
  page: Page,
  modelCode: string,
  displayName: string,
): Promise<MetaModelRecord> {
  const model = await postApi<MetaModelRecord>(page, '/api/meta/models', {
    code: modelCode,
    displayName,
    modelType: 'entity',
    sourceType: 'physical',
    primaryKey: 'pid',
    description: 'E2E fixture for DecisionOps business reference trace evidence',
  });
  expect(model.pid).toBeTruthy();
  return model;
}

async function createPublishedField(
  page: Page,
  fieldCode: string,
  dataType: string,
  extension: Record<string, unknown>,
  refTarget?: Record<string, unknown>,
): Promise<MetaFieldRecord> {
  const field = await postApi<MetaFieldRecord>(page, '/api/meta/fields', {
    code: fieldCode,
    dataType,
    extension,
    refTarget,
    autoPublish: true,
  });
  expect(field.pid).toBeTruthy();
  return field;
}

async function bindFieldToModel(
  page: Page,
  modelPid: string,
  fieldPid: string,
  displayOrder: number,
): Promise<void> {
  await postApi(
    page,
    `/api/meta/models/${encodeURIComponent(modelPid)}/fields/${encodeURIComponent(fieldPid)}?displayOrder=${displayOrder}&isRequired=false&isReadonly=false&isVisible=true`,
  );
}

async function publishModel(page: Page, modelPid: string, note: string): Promise<MetaModelRecord> {
  const published = await postApi<MetaModelRecord>(
    page,
    `/api/meta/models/${encodeURIComponent(modelPid)}/publish`,
    {
      versionNote: note,
      impactAcknowledged: true,
      acknowledgementNote: note,
    },
  );
  expect(String(published.status ?? '')).toMatch(/published/i);
  return published;
}

function normalizePermissionList(value: unknown): PermissionRecord[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item: any) => ({
      id: Number(item.id ?? item.permissionId),
      pid: String(item.pid ?? item.permissionPid ?? ''),
      code: String(item.code ?? ''),
    }))
    .filter((item) => item.pid && item.code);
}

async function grantModelPermissionsToAdminRole(page: Page, modelCode: string): Promise<void> {
  const rolesResponse = await page.request.get('/api/roles?page=0&size=100');
  await expect(rolesResponse.ok(), `list roles for ${modelCode}`).toBe(true);
  const rolesBody = await rolesResponse.json().catch(() => ({}));
  const roles = ((rolesBody?.data?.records ?? rolesBody?.data ?? []) as RoleRecord[]).filter(
    Boolean,
  );
  const adminRole =
    roles.find((role) => role.code === 'tenant_admin') ??
    roles.find((role) => role.code === 'platform_admin');
  expect(adminRole?.pid, `admin role pid for ${modelCode}`).toBeTruthy();

  const permissions = await getApi<PermissionRecord[]>(
    page,
    `/api/permissions/model/${encodeURIComponent(modelCode)}`,
  );
  const modelPermissionPids = normalizePermissionList(permissions)
    .filter((permission) => permission.code?.startsWith(`model.${modelCode}.`))
    .map((permission) => permission.pid!)
    .filter(Boolean);
  expect(modelPermissionPids, `published model permissions for ${modelCode}`).toEqual(
    expect.arrayContaining([expect.any(String)]),
  );

  const current = await getApi<string[]>(
    page,
    `/api/roles/${encodeURIComponent(adminRole!.pid!)}/permissions`,
  );
  const merged = Array.from(
    new Set([...(Array.isArray(current) ? current : []), ...modelPermissionPids]),
  );
  await postApi(page, `/api/roles/${encodeURIComponent(adminRole!.pid!)}/permissions`, merged);
}

async function createAndPublishBusinessReferenceModels(
  page: Page,
  args: {
    supplierModel: string;
    supplierNameField: string;
    ticketModel: string;
    supplierRefField: string;
  },
): Promise<void> {
  const supplierModel = await createDraftPhysicalModel(
    page,
    args.supplierModel,
    `E2E Supplier ${args.supplierModel}`,
  );
  const supplierNameField = await createPublishedField(page, args.supplierNameField, 'string', {
    displayName: '供应商名称',
    displayField: true,
  });
  await bindFieldToModel(page, supplierModel.pid, supplierNameField.pid, 1);
  await publishModel(page, supplierModel.pid, 'DecisionOps business reference target E2E');
  await grantModelPermissionsToAdminRole(page, args.supplierModel);

  const ticketModel = await createDraftPhysicalModel(
    page,
    args.ticketModel,
    `E2E Ticket ${args.ticketModel}`,
  );
  const supplierRefField = await createPublishedField(
    page,
    args.supplierRefField,
    'reference',
    { displayName: '供应商' },
    {
      targetEntity: args.supplierModel,
      displayField: args.supplierNameField,
      valueField: 'pid',
    },
  );
  expect(supplierRefField.refTarget).toEqual(
    expect.objectContaining({
      targetEntity: args.supplierModel,
      displayField: args.supplierNameField,
    }),
  );
  await bindFieldToModel(page, ticketModel.pid, supplierRefField.pid, 1);
  await publishModel(page, ticketModel.pid, 'DecisionOps business reference source E2E');
  await grantModelPermissionsToAdminRole(page, args.ticketModel);
}

async function createAndPublishBusinessReferenceDecision(
  page: Page,
  decisionCode: string,
  fieldCode: string,
  supplierPid: string,
): Promise<DecisionVersion> {
  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Business Reference Trace ${decisionCode}`,
    description: 'E2E decision verifies business reference metadata is visible in Trace',
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
      versionTag: `business-reference-${Date.now()}`,
      contentJson: {
        type: 'compare',
        left: {
          type: 'path',
          scope: 'record',
          path: `data.${fieldCode}`,
          dataType: 'reference',
        },
        operator: 'EQ',
        right: {
          type: 'literal',
          value: supplierPid,
          dataType: 'reference',
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

  return postApi<DecisionVersion>(
    page,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`,
  );
}

async function openExecutionLogsFromSidebar(page: Page): Promise<void> {
  const openDecisionOpsLogsTab = async () => {
    await page.goto('/decision-ops?tab=logs', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('doc-tab-logs')).toHaveAttribute('aria-selected', 'true', {
      timeout: 15_000,
    });
    await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 15_000 });
  };
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
    if (await parent.isVisible({ timeout: 3000 }).catch(() => false)) {
      await parent.click();
    } else {
      await openDecisionOpsLogsTab();
      return;
    }
  }
  if (!(await logsLink.isVisible({ timeout: 3000 }).catch(() => false))) {
    await openDecisionOpsLogsTab();
    return;
  }
  await expect(logsLink).toBeVisible({ timeout: 10_000 });
  await logsLink.scrollIntoViewIfNeeded();
  await logsLink.click();
  await expect(page).toHaveURL(/\/p\/decisionops_execution_logs(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
  await expect(page.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 15_000 });
}

function isDevNoise(text: string): boolean {
  return /favicon|Failed to fetch dynamically imported module|Outdated Optimize Dep|HMR|Vite|websocket/i.test(
    text,
  );
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

  const suffix = uniqueId('fact_meta')
    .replace(/[^a-zA-Z0-9_]/g, '_')
    .toLowerCase();
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

test('DecisionOps Trace shows user reference fact metadata value labels @golden', async ({
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
  const applicantFact = findApplicantFact(catalog);
  expect(applicantFact).toMatchObject({
    label: expect.stringMatching(/申请人|Applicant/i),
    dataType: expect.stringMatching(/user|reference/i),
  });
  const user = await resolveFirstUser(page);

  const suffix = uniqueId('user_ref_meta')
    .replace(/[^a-zA-Z0-9_]/g, '_')
    .toLowerCase();
  const decisionCode = `drt_e2e_user_ref_meta_${suffix}`;
  const correlationId = `drt-e2e-user-ref-meta-${suffix}`;
  const published = await createAndPublishApplicantDecision(page, decisionCode, user.pid);
  expect(String(published.status ?? '')).toMatch(/published/i);

  const evaluation = await postApi<DecisionResult>(page, '/api/decision/evaluate', {
    decisionCode,
    binding: 'LATEST',
    callerType: 'E2E',
    callerRef: 'decisionops-user-reference-fact-metadata-trace',
    correlationId,
    routingKey: suffix,
    context: {
      record: {
        modelCode: 'wd_leave_request',
        data: {
          wd_req_applicant: user.pid,
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
  const applicantMetadata = metadataForApplicant(log!);
  expect(applicantMetadata).toMatchObject({
    label: expect.stringMatching(/申请人|Applicant/i),
    modelCode: 'wd_leave_request',
    dataType: expect.stringMatching(/user|reference/i),
  });
  expect(applicantMetadata?.valueLabels?.[user.pid]).toContain(user.label);

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
  await expect(factMetadata).toContainText(/申请人|Applicant/i);
  await expect(factMetadata).toContainText('record.data.wd_req_applicant');
  await expect(factMetadata).toContainText('模型 wd_leave_request');
  await expect(factMetadata).toContainText(/类型 (user|reference)/i);
  await expect(factMetadata).toContainText(user.label);

  await page.screenshot({
    path: testInfo.outputPath('decisionops-user-reference-fact-metadata-trace.png'),
    fullPage: true,
  });
  expect(consoleErrors).toEqual([]);
});

test('DecisionOps Trace shows business reference fact metadata value labels @golden', async ({
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

  const suffix = uniqueId('biz_ref_meta')
    .replace(/[^a-zA-Z0-9_]/g, '_')
    .toLowerCase();
  const supplierModel = `drt_e2e_supplier_${suffix}`;
  const supplierNameField = `supplier_name_${suffix}`;
  const ticketModel = `drt_e2e_ticket_${suffix}`;
  const supplierRefField = `supplier_ref_${suffix}`;
  await createAndPublishBusinessReferenceModels(page, {
    supplierModel,
    supplierNameField,
    ticketModel,
    supplierRefField,
  });

  const supplierName = `华东审批供应商 ${suffix}`;
  const supplier = await postApi<DynamicRecord>(
    page,
    `/api/dynamic/${encodeURIComponent(supplierModel)}`,
    {
      [supplierNameField]: supplierName,
    },
  );
  const supplierPid = String(supplier.pid ?? '');
  expect(supplierPid).not.toEqual('');

  const options = await getApi<FieldOptionRecord[]>(
    page,
    `/api/dynamic/${encodeURIComponent(ticketModel)}/field-options/${encodeURIComponent(
      supplierRefField,
    )}?keyword=${encodeURIComponent('华东审批')}`,
  );
  expect(options).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        value: supplierPid,
        label: supplierName,
      }),
    ]),
  );

  const ticket = await postApi<DynamicRecord>(
    page,
    `/api/dynamic/${encodeURIComponent(ticketModel)}`,
    {
      [supplierRefField]: supplierPid,
    },
  );
  const ticketPid = String(ticket.pid ?? '');
  expect(ticketPid).not.toEqual('');
  const reloadedTicket = await getApi<DynamicRecord>(
    page,
    `/api/dynamic/${encodeURIComponent(ticketModel)}/${encodeURIComponent(ticketPid)}`,
  );
  expect(reloadedTicket[supplierRefField]).toBe(supplierPid);
  expect(reloadedTicket[`${supplierRefField}_display`]).toBe(supplierName);

  const catalog = await getApi<DecisionFactCatalog>(
    page,
    `/api/decision/facts/catalog?modelCode=${encodeURIComponent(ticketModel)}`,
  );
  const supplierFact = findFactByField(catalog, supplierRefField);
  expect(supplierFact).toMatchObject({
    label: '供应商',
    dataType: 'reference',
    reference: expect.objectContaining({
      targetEntity: supplierModel,
      displayField: supplierNameField,
    }),
  });

  const decisionCode = `drt_e2e_biz_ref_meta_${suffix}`;
  const correlationId = `drt-e2e-biz-ref-meta-${suffix}`;
  const published = await createAndPublishBusinessReferenceDecision(
    page,
    decisionCode,
    supplierRefField,
    supplierPid,
  );
  expect(String(published.status ?? '')).toMatch(/published/i);

  const evaluation = await postApi<DecisionResult>(page, '/api/decision/evaluate', {
    decisionCode,
    binding: 'LATEST',
    callerType: 'E2E',
    callerRef: 'decisionops-business-reference-fact-metadata-trace',
    correlationId,
    routingKey: suffix,
    context: {
      record: {
        modelCode: ticketModel,
        data: {
          [supplierRefField]: supplierPid,
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
  const supplierMetadata = metadataForField(log!, supplierRefField);
  expect(supplierMetadata).toMatchObject({
    label: '供应商',
    modelCode: ticketModel,
    dataType: 'reference',
  });
  expect(supplierMetadata?.valueLabels?.[supplierPid]).toBe(supplierName);

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
  await expect(factMetadata).toContainText('供应商');
  await expect(factMetadata).toContainText(`record.data.${supplierRefField}`);
  await expect(factMetadata).toContainText(`模型 ${ticketModel}`);
  await expect(factMetadata).toContainText(/类型 reference/i);
  await expect(factMetadata).toContainText(supplierPid);
  await expect(factMetadata).toContainText(supplierName);

  await page.screenshot({
    path: testInfo.outputPath('decisionops-business-reference-fact-metadata-trace.png'),
    fullPage: true,
  });
  expect(consoleErrors).toEqual([]);
});
