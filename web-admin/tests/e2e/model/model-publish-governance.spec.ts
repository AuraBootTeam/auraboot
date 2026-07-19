import { test, expect, type APIResponse, type Page, type Request, type TestInfo } from '@playwright/test';
import { Client } from 'pg';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { PG_CONN } from '../../helpers/environments';
import { loginViaUI } from '../../helpers/wd-fixtures';
import {
  createFieldBindingData,
  createFieldData,
  createModelData,
} from '../../model-system/helpers/test-data';

type ApiEnvelope<T> = {
  code?: string | number;
  success?: boolean;
  data?: T;
  desc?: string;
  message?: string;
};

type MetaModel = {
  pid: string;
  code: string;
  displayName?: string;
  status?: string;
};

type MetaField = {
  pid: string;
  code: string;
  dataType?: string;
};

type DecisionVersion = {
  pid: string;
  status?: string;
  version?: number;
  fieldRefs?: string[];
};

type DecisionValidateResult = {
  valid?: boolean;
  fieldRefs?: string[];
};

type DecisionFieldImpact = {
  fieldRef?: string;
  references?: Array<{
    sourceType?: string;
    sourceCode?: string;
    targetPath?: string;
    binding?: string;
  }>;
  risk?: {
    blocking?: boolean;
    summary?: string;
    counts?: Record<string, number>;
  };
};

type ModelPublishReplayStep = {
  consumerType?: string;
  sourceCode?: string;
  sourcePid?: string;
  metadata?: Record<string, unknown>;
};

type ModelPublishReplayResult = {
  step?: ModelPublishReplayStep;
  status?: string;
  executed?: boolean;
  matched?: boolean;
  message?: string;
  traceId?: string;
  outputs?: Record<string, unknown>;
  errors?: string[];
};

type ModelPublishReplayReport = {
  results?: ModelPublishReplayResult[];
};

type RoleRecord = {
  id: number;
  pid: string;
  code: string;
  name?: string;
};

type PermissionRecord = {
  id: number;
  pid: string;
  code: string;
  name?: string;
};

type SlaConfigRecord = {
  pid: string;
  name?: string;
  targetType?: string;
  targetKey?: string;
  actionPolicy?: Record<string, unknown>;
};

type BpmProcessRecord = {
  pid: string;
  processKey: string;
  processName?: string;
  status?: string;
  version?: number;
};

test.use({ storageState: { cookies: [], origins: [] } });
test.describe.configure({ timeout: 120_000 });

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

async function putApi<T>(page: Page, endpoint: string, data?: unknown): Promise<T> {
  const options = data === undefined ? undefined : { data };
  return readApi<T>(await page.request.put(endpoint, options));
}

async function getApi<T>(page: Page, endpoint: string): Promise<T> {
  return readApi<T>(await page.request.get(endpoint));
}

async function withDb<T>(fn: (client: Client) => Promise<T>): Promise<T> {
  const client = new Client(PG_CONN);
  await client.connect();
  try {
    return await fn(client);
  } finally {
    await client.end();
  }
}

async function findTenantMemberForEmail(email: string): Promise<{
  memberId: string;
  memberPid: string;
  tenantId: string;
}> {
  return withDb(async (client) => {
    const result = await client.query<{ id: string; pid: string; tenant_id: string }>(
      `
      select tm.id, tm.pid, tm.tenant_id
      from ab_tenant_member tm
      join ab_user u on u.id = tm.user_id
      where u.email = $1
        and tm.deleted_flag = false
      order by tm.id desc
      limit 1
      `,
      [email],
    );
    const member = result.rows[0];
    expect(member, `tenant member for ${email} must exist`).toBeTruthy();
    return {
      memberId: member.id,
      memberPid: member.pid,
      tenantId: member.tenant_id,
    };
  });
}

function shortSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

function modelRow(page: Page, code: string) {
  return page.locator('tbody tr', { hasText: code }).first();
}

async function expectPermissionReplayTraceLinkOpensDecisionOps(
  page: Page,
  args: {
    traceId: string;
    permissionCode: string;
    resourceCode: string;
    testInfo: TestInfo;
  },
) {
  const traceLink = page.getByTestId('model-publish-replay-open-permission-trace');
  await expect(traceLink).toHaveAttribute(
    'href',
    new RegExp(`/p/decisionops_execution_logs\\?traceId=${args.traceId}`),
  );

  const [tracePage] = await Promise.all([
    page.context().waitForEvent('page', { timeout: 10_000 }),
    traceLink.click({ button: 'middle' }),
  ]);
  await tracePage.waitForLoadState('domcontentloaded');
  await tracePage.waitForURL(/\/p\/decisionops_execution_logs(?:\?|$)/, { timeout: 15_000 });

  const traceUrl = new URL(tracePage.url());
  expect(traceUrl.pathname).toBe('/p/decisionops_execution_logs');
  expect(traceUrl.searchParams.get('traceId')).toBe(args.traceId);
  expect(traceUrl.searchParams.get('callerType')).toBe('PERMISSION');
  expect(traceUrl.searchParams.get('callerRef')).toBe(args.permissionCode);

  await expect(tracePage.getByTestId('execution-log-trace-block')).toBeVisible({ timeout: 20_000 });
  const traceRow = tracePage.locator('tr[data-testid^="elta-row-"]').filter({ hasText: args.traceId }).first();
  await expect(traceRow).toBeVisible({ timeout: 20_000 });
  await expect(traceRow).toContainText(args.permissionCode);
  await expect(traceRow).toContainText(/权限|PERMISSION/);
  const rowTestId = await traceRow.getAttribute('data-testid');
  expect(rowTestId, `Expected DecisionOps row test id for trace ${args.traceId}`).toBeTruthy();
  const logKey = rowTestId!.replace('elta-row-', '');

  await tracePage.getByTestId(`elta-open-trace-${logKey}`).click();
  await expect(tracePage.getByTestId('elta-trace-drawer')).toBeVisible({ timeout: 10_000 });
  await expect(tracePage.getByTestId('elta-trace-drawer')).toContainText(args.traceId);
  await expect(tracePage.getByTestId('elta-trace-drawer')).toContainText(args.permissionCode);
  await expect(tracePage.getByTestId(`elta-chain-caller-${logKey}`)).toContainText(/权限|PERMISSION/);
  await expect(tracePage.getByTestId(`elta-chain-caller-${logKey}`)).toContainText(args.permissionCode);

  const auditBackLink = tracePage.getByTestId('elta-open-permission-audit');
  await expect(auditBackLink).toHaveText('打开权限审计');
  const auditHref = await auditBackLink.getAttribute('href');
  expect(auditHref).toContain('/enterprise/permissions?');
  const auditUrl = new URL(auditHref!, tracePage.url());
  expect(auditUrl.searchParams.get('tab')).toBe('audit');
  expect(auditUrl.searchParams.get('traceId')).toBe(args.traceId);
  expect(auditUrl.searchParams.get('resourceCode')).toBe(args.resourceCode);

  await tracePage.screenshot({
    path: args.testInfo.outputPath('model-publish-permission-trace-click-decisionops.png'),
    fullPage: true,
  });
  await tracePage.close();
}

async function navigateToModelListViaMenu(page: Page) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});

  const parent = page
    .locator('button', { hasText: /元数据管理|Metadata|menu\.meta_management/i })
    .first();
  await expect(parent).toBeVisible({ timeout: 10_000 });
  await parent.evaluate((element: HTMLElement) => element.click());

  const listResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/models') &&
      response.request().method() === 'GET' &&
      response.status() === 200,
    { timeout: 10_000 },
  );

  const leaf = page.locator('a[href="/meta/models"], a[href*="/meta/models"]').first();
  await expect(leaf).toBeAttached({ timeout: 5_000 });
  await leaf.evaluate((element: HTMLElement) => element.click());
  await listResponse;

  await expect(page).toHaveURL(/\/meta\/models(?:\?|$)/, { timeout: 10_000 });
  await expect(page.locator('table').first()).toBeVisible({ timeout: 10_000 });
}

async function searchModel(page: Page, keyword: string) {
  const searchInput = page
    .locator(
      [
        '[data-testid="list-search-input"]',
        'input[placeholder*="Search"]',
        'input[placeholder*="搜索"]',
        'input[placeholder*="查询"]',
      ].join(', '),
    )
    .first();
  await expect(searchInput).toBeVisible({ timeout: 10_000 });
  await searchInput.click();
  await searchInput.fill(keyword);

  const listResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/models') &&
      response.request().method() === 'GET' &&
      response.status() === 200,
    { timeout: 10_000 },
  );
  await searchInput.press('Enter');
  await listResponse;
}

async function createDraftModelWithField(page: Page, suffix: string) {
  const modelCode = `rcmp_${suffix}`;
  const fieldCode = `amount_${suffix}`;

  const model = await postApi<MetaModel>(
    page,
    '/api/meta/models',
    createModelData({
      code: modelCode,
      displayName: `Model Publish Governance ${suffix}`,
      description: 'E2E model publish governance fixture',
      modelType: 'entity',
    }),
  );
  expect(model.pid).toBeTruthy();
  expect(model.status).toMatch(/draft/i);

  const field = await postApi<MetaField>(
    page,
    '/api/meta/fields',
    createFieldData('decimal', {
      code: fieldCode,
      uiSchema: { label: `Amount ${suffix}` },
      extension: {
        masked: true,
        fieldPermissionChange: true,
        permissionCode: `model.${modelCode}.${fieldCode}.view`,
        fieldPermission: {
          view: ['manager'],
          edit: ['manager'],
        },
      },
      ruleSchema: {
        extensions: {
          masked: true,
          fieldPermissionChange: true,
          permissionCode: `model.${modelCode}.${fieldCode}.view`,
        },
        permissionRule: {
          readable: false,
          fieldSecurity: {
            maskSensitive: true,
          },
        },
      },
    }),
  );
  expect(field.pid).toBeTruthy();

  await postApi(
    page,
    `/api/meta/models/${encodeURIComponent(model.pid)}/fields/bind`,
    createFieldBindingData(field.pid, {
      required: false,
      visible: true,
      editable: true,
      displayOrder: 0,
    }),
  );

  return { model, field, modelCode, fieldCode, runtimeFieldRef: `record.data.${fieldCode}` };
}

async function createPublishedDecisionReferencingField(
  page: Page,
  decisionCode: string,
  runtimeFieldRef: string,
): Promise<DecisionVersion> {
  const [, path] = runtimeFieldRef.split('record.');
  expect(path).toBeTruthy();

  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Model Publish Governance ${decisionCode}`,
    description: 'E2E decision references a low-code model field before model publish',
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
      versionTag: `model-publish-${Date.now()}`,
      contentJson: {
        type: 'compare',
        left: {
          type: 'path',
          scope: 'record',
          path,
          dataType: 'decimal',
        },
        operator: 'GT',
        right: {
          type: 'literal',
          value: 1000,
          dataType: 'decimal',
        },
      },
    },
  );
  expect(draft.pid).toBeTruthy();

  const validation = await postApi<DecisionValidateResult>(
    page,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/validate`,
  );
  expect(validation.valid).toBe(true);
  expect(validation.fieldRefs ?? []).toContain(runtimeFieldRef);

  const published = await postApi<DecisionVersion>(
    page,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`,
    {
      impactAcknowledged: true,
      note: 'Model publish governance E2E fixture',
    },
  );
  expect(String(published.status ?? '')).toMatch(/published/i);
  expect(published.fieldRefs ?? [runtimeFieldRef]).toContain(runtimeFieldRef);
  return published;
}

async function createPublishedTaskAssignmentDecisionReferencingField(
  page: Page,
  args: {
    decisionCode: string;
    fieldCode: string;
    runtimeFieldRef: string;
    suffix: string;
  },
): Promise<DecisionVersion> {
  const [, path] = args.runtimeFieldRef.split('record.');
  expect(path).toBeTruthy();
  const managerUser = `u-manager-${args.suffix}`;
  const backupUser = `u-finance-${args.suffix}`;
  const financeGroup = `finance-${args.suffix}`;

  await postApi(page, '/api/decision/definitions', {
    decisionCode: args.decisionCode,
    decisionName: `Model Publish BPM Assignment ${args.decisionCode}`,
    description: 'E2E decision resolves BPM userTask candidates during model publish replay',
    scopeType: 'BPM',
    ownerModule: 'decision',
    enabled: true,
  });

  const draft = await postApi<DecisionVersion>(
    page,
    `/api/decision/definitions/${encodeURIComponent(args.decisionCode)}/versions`,
    {
      kind: 'DECISION_TABLE',
      runtimeAdapter: 'PLATFORM_DECISION_TABLE',
      versionTag: `model-publish-bpm-assignment-${Date.now()}`,
      contentJson: {
        hitPolicy: 'FIRST',
        inputs: [
          {
            id: args.fieldCode,
            label: 'Amount',
            expr: {
              type: 'path',
              scope: 'record',
              path,
              dataType: 'decimal',
            },
          },
        ],
        outputs: [
          { id: 'candidateUserIds', label: 'Candidate Users', dataType: 'collection' },
          { id: 'candidateGroupIds', label: 'Candidate Groups', dataType: 'collection' },
          { id: 'route', label: 'Route', dataType: 'string' },
        ],
        rules: [
          {
            ruleId: 'assign-high-amount',
            priority: 10,
            when: {
              [args.fieldCode]: { operator: 'GTE', value: 1000 },
            },
            then: {
              candidateUserIds: [managerUser, backupUser],
              candidateGroupIds: [financeGroup],
              route: 'manager-review',
            },
          },
        ],
        defaultOutput: {
          candidateUserIds: [],
          candidateGroupIds: [],
          route: 'manual-review',
        },
      },
      outputSchemaJson: {
        outputs: [
          { id: 'candidateUserIds', label: 'Candidate Users', dataType: 'collection' },
          { id: 'candidateGroupIds', label: 'Candidate Groups', dataType: 'collection' },
          { id: 'route', label: 'Route', dataType: 'string' },
        ],
      },
    },
  );
  expect(draft.pid).toBeTruthy();

  const validation = await postApi<DecisionValidateResult>(
    page,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/validate`,
  );
  expect(validation.valid).toBe(true);
  expect(validation.fieldRefs ?? []).toContain(args.runtimeFieldRef);

  const published = await postApi<DecisionVersion>(
    page,
    `/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`,
    {
      impactAcknowledged: true,
      note: 'Model publish BPM assignment replay E2E fixture',
    },
  );
  expect(String(published.status ?? '')).toMatch(/published/i);
  return published;
}

async function createPermissionPolicyUsageReferencingField(
  page: Page,
  args: {
    suffix: string;
    modelCode: string;
    fieldCode: string;
    decisionCode: string;
  },
): Promise<{
  role: RoleRecord;
  permission: PermissionRecord;
  permissionCode: string;
  member: { memberId: string; memberPid: string; tenantId: string };
}> {
  const role = await postApi<RoleRecord>(page, '/api/roles', {
    code: `rcmp_perm_role_${args.suffix}`,
    name: `Model Publish Permission Replay ${args.suffix}`,
    description: 'Model publish Permission replay E2E fixture',
    type: 'custom',
  });
  expect(role.pid).toBeTruthy();

  const permissionCode = `model.${args.modelCode}.approve`;
  const policyField = {
    scope: 'record',
    path: `data.${args.fieldCode}`,
    label: `Amount ${args.suffix}`,
    dataType: 'decimal',
  };
  const permission = await postApi<PermissionRecord>(page, '/api/permissions', {
    code: permissionCode,
    name: `Approve ${args.modelCode}`,
    description: 'Model publish Permission replay E2E fixture',
    resourceType: 'model',
    resourceCode: args.modelCode,
    action: 'approve',
    source: 'e2e',
    sourceRef: 'model-publish-governance',
    policySchema: {
      dynamicAbac: {
        type: 'rule-center',
        fieldCatalogMode: 'merge',
        fieldCatalogModelCode: args.modelCode,
        fields: [policyField],
      },
    },
  });
  expect(permission.pid).toBeTruthy();

  await postApi<boolean>(page, `/api/roles/${role.pid}/permissions`, [permission.pid]);

  const member = await findTenantMemberForEmail(DEFAULT_TEST_ACCOUNT.email);
  await postApi<void>(page, `/api/roles/${role.pid}/members`, [member.memberPid]);

  await putApi<void>(page, `/api/permissions/matrix/${role.pid}/policy/${permission.pid}`, {
    dynamicAbac: {
      fields: [policyField],
      ruleBinding: {
        active: true,
        bindingKind: 'DECISION_REF',
        consumerType: 'PERMISSION',
        consumerNodeId: 'modelPublishReplay',
        decisionBinding: {
          decisionCode: args.decisionCode,
          versionPolicy: 'LATEST_PUBLISHED',
          fallbackPolicy: { mode: 'FAIL_CLOSED' },
          inputMappings: [
            {
              input: args.fieldCode,
              source: { kind: 'FIELD', scope: 'record', path: `data.${args.fieldCode}` },
            },
          ],
        },
      },
    },
  });

  return { role, permission, permissionCode, member };
}

async function createSlaNodeUsageReferencingField(
  page: Page,
  args: {
    suffix: string;
    modelCode: string;
    fieldCode: string;
    decisionCode: string;
  },
): Promise<{
  sla: SlaConfigRecord;
  slaCode: string;
  targetKey: string;
  processKey: string;
}> {
  const slaCode = `rcmp_sla_${args.suffix}`;
  const targetKey = `task_${args.suffix}`;
  const processKey = `approval_flow_${args.suffix}`;
  const sla = await postApi<SlaConfigRecord>(page, '/api/bpm/sla-configs', {
    name: `Model Publish SLA Node ${args.suffix}`,
    targetType: 'NODE',
    targetKey,
    domainCode: args.modelCode,
    modelCode: args.modelCode,
    deadlineMode: 'FIXED',
    deadlineValue: 'PT30M',
    businessCalendar: false,
    warningRules: [],
    ruleBinding: {
      consumerType: 'SLA',
      consumerCode: slaCode,
      consumerNodeId: targetKey,
      bindingKind: 'DECISION_REF',
      enabled: true,
      decisionBinding: {
        decisionCode: args.decisionCode,
        versionPolicy: 'LATEST_PUBLISHED',
        inputMappings: [
          {
            input: args.fieldCode,
            source: { kind: 'FIELD', scope: 'record', path: `data.${args.fieldCode}` },
          },
        ],
        outputMappings: [
          {
            output: 'deadlineMinutes',
            target: { kind: 'SLA_FIELD', path: 'deadlineMinutes' },
          },
        ],
        fallbackPolicy: {
          mode: 'DEFAULT_VALUE',
          defaultOutputs: { deadlineMinutes: 30 },
        },
        traceMode: 'ALWAYS',
        enabled: true,
      },
    },
    actionPolicy: {
      trigger: 'SLA_TIMEOUT',
      actions: [
        {
          type: 'NOTIFY',
          target: 'ROLE:admin',
          order: 10,
          payload: {
            channel: 'in_app',
            title: `Model publish SLA timeout ${args.suffix}`,
            content: 'Model publish governance SLA node replay timeout action.',
          },
          idempotencyKeyTemplate: '${sla.recordPid}:model_publish_sla_timeout:NOTIFY',
        },
      ],
    },
    suspendPolicy: 'pause',
  });
  expect(sla.pid).toBeTruthy();
  expect(sla.targetType).toMatch(/node/i);
  expect(sla.targetKey).toBe(targetKey);
  return { sla, slaCode, targetKey, processKey };
}

async function createBpmProcessUsageReferencingField(
  page: Page,
  args: {
    suffix: string;
    modelCode: string;
    fieldCode: string;
  },
): Promise<{
  process: BpmProcessRecord;
  processKey: string;
  edgeId: string;
  sourceNodeId: string;
  targetNodeId: string;
}> {
  const processKey = `approval_bpm_${args.suffix}`;
  const processName = `模型发布 BPM 复核 ${args.suffix}`;
  const sourceNodeId = 'gateway_route';
  const targetNodeId = 'task_assign';
  const edgeId = 'edge_high_amount';
  const designerJson = {
    key: processKey,
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 80, y: 120 },
        data: { type: 'startEvent', label: 'Start' },
      },
      {
        id: sourceNodeId,
        type: 'exclusiveGateway',
        position: { x: 260, y: 120 },
        data: { type: 'exclusiveGateway', label: 'Amount route' },
      },
      {
        id: targetNodeId,
        type: 'userTask',
        position: { x: 460, y: 120 },
        data: { type: 'userTask', label: 'Approval task' },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 660, y: 120 },
        data: { type: 'endEvent', label: 'End' },
      },
    ],
    edges: [
      {
        id: 'edge_start_to_gateway',
        source: 'start',
        target: sourceNodeId,
        data: { label: 'Start route' },
      },
      {
        id: edgeId,
        source: sourceNodeId,
        target: targetNodeId,
        data: {
          label: 'High amount',
          conditionSpec: {
            root: {
              type: 'compare',
              left: {
                type: 'path',
                scope: 'record',
                path: `data.${args.fieldCode}`,
                dataType: 'decimal',
              },
              operator: 'GTE',
              right: { type: 'literal', value: 1000, dataType: 'decimal' },
            },
          },
        },
      },
      {
        id: 'edge_task_to_end',
        source: targetNodeId,
        target: 'end',
        data: { label: 'Complete' },
      },
    ],
  };
  const bpmnContent = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="http://auraboot.com/bpmn">',
    `  <bpmn:process id="${processKey}" name="${processName}" isExecutable="true">`,
    '    <bpmn:startEvent id="start" />',
    '    <bpmn:exclusiveGateway id="gateway_route" />',
    '    <bpmn:userTask id="task_assign" name="Approval task" />',
    '    <bpmn:endEvent id="end" />',
    '  </bpmn:process>',
    '</bpmn:definitions>',
  ].join('\n');

  const process = await postApi<BpmProcessRecord>(page, '/api/bpm/process-definitions', {
    processKey,
    processName,
    description: 'Model publish governance BPM_PROCESS replay fixture',
    category: 'approval',
    bpmnContent,
    designerJson: JSON.stringify(designerJson),
    businessDataBindings: [
      {
        modelCode: args.modelCode,
        fieldCode: args.fieldCode,
        source: 'record',
      },
    ],
  });
  expect(process.pid).toBeTruthy();
  expect(process.processKey).toBe(processKey);
  return { process, processKey, edgeId, sourceNodeId, targetNodeId };
}

async function createBpmUserTaskAssignmentUsageReferencingField(
  page: Page,
  args: {
    suffix: string;
    modelCode: string;
    fieldCode: string;
    decisionCode: string;
  },
): Promise<{
  process: BpmProcessRecord;
  processKey: string;
  processName: string;
  taskNodeId: string;
}> {
  const processKey = `approval_bpm_assign_${args.suffix}`;
  const processName = `模型发布 BPM 分派 ${args.suffix}`;
  const taskNodeId = 'task_assign';
  const ruleBinding = {
    consumerType: 'BPM',
    consumerCode: processKey,
    consumerNodeId: taskNodeId,
    bindingKind: 'DECISION_REF',
    enabled: true,
    decisionBinding: {
      decisionCode: args.decisionCode,
      versionPolicy: 'LATEST_PUBLISHED',
      inputMappings: [
        {
          input: args.fieldCode,
          source: { kind: 'FIELD', scope: 'record', path: `data.${args.fieldCode}` },
        },
      ],
      outputMappings: [
        {
          output: 'candidateUserIds',
          target: { kind: 'ACTION_PARAM', path: 'candidateUsers' },
        },
        {
          output: 'candidateGroupIds',
          target: { kind: 'ACTION_PARAM', path: 'candidateGroups' },
        },
      ],
      fallbackPolicy: { mode: 'FAIL_CLOSED' },
      traceMode: 'ALWAYS',
      enabled: true,
    },
  };
  const designerJson = {
    key: processKey,
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 80, y: 120 },
        data: { type: 'startEvent', label: 'Start' },
      },
      {
        id: taskNodeId,
        type: 'userTask',
        position: { x: 320, y: 120 },
        data: {
          type: 'userTask',
          label: 'Approval task',
          config: { ruleBinding },
        },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 560, y: 120 },
        data: { type: 'endEvent', label: 'End' },
      },
    ],
    edges: [
      {
        id: 'edge_start_to_task',
        source: 'start',
        target: taskNodeId,
        data: { label: 'Start approval' },
      },
      {
        id: 'edge_task_to_end',
        source: taskNodeId,
        target: 'end',
        data: { label: 'Complete' },
      },
    ],
  };
  const bpmnContent = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="http://auraboot.com/bpmn">',
    `  <bpmn:process id="${processKey}" name="${processName}" isExecutable="true">`,
    '    <bpmn:startEvent id="start" />',
    '    <bpmn:userTask id="task_assign" name="Approval task" />',
    '    <bpmn:endEvent id="end" />',
    '  </bpmn:process>',
    '</bpmn:definitions>',
  ].join('\n');

  const process = await postApi<BpmProcessRecord>(page, '/api/bpm/process-definitions', {
    processKey,
    processName,
    description: 'Model publish governance BPM userTask assignment replay fixture',
    category: 'approval',
    bpmnContent,
    designerJson: JSON.stringify(designerJson),
    businessDataBindings: [],
  });
  expect(process.pid).toBeTruthy();
  expect(process.processKey).toBe(processKey);
  return { process, processKey, processName, taskNodeId };
}

function requirePermissionReplayResult(
  report: ModelPublishReplayReport,
  permissionCode: string,
): ModelPublishReplayResult {
  const result = (report.results ?? []).find((item) => {
    const outputs = item.outputs ?? {};
    const metadata = item.step?.metadata ?? {};
    return item.step?.consumerType === 'PERMISSION_POLICY'
      || item.step?.sourceCode === permissionCode
      || outputs.permissionCode === permissionCode
      || metadata.permissionCode === permissionCode;
  });
  if (!result) {
    throw new Error(
      `Permission replay result missing for ${permissionCode}: ${JSON.stringify(report.results ?? [])}`,
    );
  }
  return result;
}

function requireSlaNodeReplayResult(
  report: ModelPublishReplayReport,
  targetKey: string,
  slaPid: string,
): ModelPublishReplayResult {
  const result = (report.results ?? []).find((item) => {
    const outputs = item.outputs ?? {};
    const metadata = item.step?.metadata ?? {};
    return item.step?.consumerType === 'SLA_RULE' && (
      item.step?.sourcePid === slaPid
      || item.step?.sourceCode === slaPid
      || outputs.targetKey === targetKey
      || metadata.targetKey === targetKey
    );
  });
  if (!result) {
    throw new Error(
      `SLA NODE replay result missing for ${targetKey}: ${JSON.stringify(report.results ?? [])}`,
    );
  }
  return result;
}

function requireBpmReplayResult(
  report: ModelPublishReplayReport,
  processKey: string,
  edgeId: string,
): ModelPublishReplayResult {
  const result = (report.results ?? []).find((item) => {
    const outputs = item.outputs ?? {};
    const metadata = item.step?.metadata ?? {};
    return item.step?.consumerType === 'BPM_PROCESS' && (
      item.step?.sourceCode === processKey
      || outputs.processKey === processKey
      || metadata.processKey === processKey
      || outputs.edgeId === edgeId
      || metadata.edgeId === edgeId
    );
  });
  if (!result) {
    throw new Error(
      `BPM replay result missing for ${processKey}/${edgeId}: ${JSON.stringify(report.results ?? [])}`,
    );
  }
  return result;
}

function requireBpmAssignmentReplayResult(
  report: ModelPublishReplayReport,
  processKey: string,
  nodeId: string,
): ModelPublishReplayResult {
  const result = (report.results ?? []).find((item) => {
    const outputs = item.outputs ?? {};
    const metadata = item.step?.metadata ?? {};
    return item.step?.consumerType === 'BPM_PROCESS' && (
      item.step?.sourceCode === processKey
      || outputs.processKey === processKey
      || metadata.processKey === processKey
      || outputs.nodeId === nodeId
      || metadata.nodeId === nodeId
    );
  });
  if (!result) {
    throw new Error(
      `BPM assignment replay result missing for ${processKey}/${nodeId}: ${JSON.stringify(report.results ?? [])}`,
    );
  }
  return result;
}

test('RC-MODEL-01: model detail publish governance blocks low-code field refs until impact acknowledgement', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);

  const suffix = shortSuffix();
  const { model, modelCode, fieldCode, runtimeFieldRef } = await createDraftModelWithField(
    page,
    suffix,
  );
  const decisionCode = `rcmp_dec_${suffix}`;
  await createPublishedDecisionReferencingField(page, decisionCode, runtimeFieldRef);
  const { permissionCode, member } = await createPermissionPolicyUsageReferencingField(page, {
    suffix,
    modelCode,
    fieldCode,
    decisionCode,
  });
  const slaUsage = await createSlaNodeUsageReferencingField(page, {
    suffix,
    modelCode,
    fieldCode,
    decisionCode,
  });
  const bpmUsage = await createBpmProcessUsageReferencingField(page, {
    suffix,
    modelCode,
    fieldCode,
  });

  await postApi(page, '/api/decision/usage-index/rebuild');
  await expect(async () => {
    const currentImpact = await getApi<DecisionFieldImpact>(
      page,
      `/api/decision/fields/impact?fieldRef=${encodeURIComponent(runtimeFieldRef)}`,
    );
      expect(currentImpact.fieldRef).toBe(runtimeFieldRef);
      expect(currentImpact.risk?.blocking).toBe(true);
      expect(currentImpact.references ?? []).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            sourceType: 'DECISION_VERSION',
            targetPath: runtimeFieldRef,
          }),
          expect.objectContaining({
            sourceType: 'PERMISSION_POLICY',
            sourceCode: permissionCode,
            targetPath: runtimeFieldRef,
          }),
          expect.objectContaining({
            sourceType: 'SLA_RULE',
            sourceCode: slaUsage.sla.pid,
            targetPath: runtimeFieldRef,
          }),
          expect.objectContaining({
            sourceType: 'BPM_PROCESS',
            sourceCode: bpmUsage.processKey,
            targetPath: runtimeFieldRef,
          }),
        ]),
      );
  }).toPass({ timeout: 15_000 });

  await navigateToModelListViaMenu(page);
  await searchModel(page, modelCode);

  const row = modelRow(page, modelCode);
  await expect(row).toBeVisible({ timeout: 10_000 });
  await expect(row).toContainText(/草稿|Draft/i);

  const viewButton = row.getByRole('button', { name: '查看' }).first();
  await expect(viewButton).toBeVisible({ timeout: 5_000 });
  await viewButton.evaluate((element: HTMLElement) => element.click());
  await expect(page).toHaveURL(new RegExp(`/meta/models/${model.pid}(?:#|\\?|$)`), {
    timeout: 10_000,
  });
  await expect(page.getByText(modelCode).first()).toBeVisible({ timeout: 10_000 });

  await page.getByTestId('model-more-actions').click();
  const previewResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish/preview`) &&
      response.request().method() === 'GET' &&
      response.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('model-publish-action').click();
  await previewResponse;

  const dialog = page.getByTestId('model-publish-dialog');
  await expect(dialog).toBeVisible({ timeout: 10_000 });
  const governance = page.getByTestId('model-publish-governance');
  await expect(governance).toBeVisible({ timeout: 10_000 });
  await expect(governance).toContainText('规则中心影响治理');
  await expect(governance).toContainText('需确认');
  await expect(governance).toContainText(`${modelCode}.${fieldCode}`);
  await expect(governance).toContainText(/DECISION_VERSION|决策版本/);
  await expect(page.getByTestId('model-publish-replay-plan')).toContainText('发布后复核计划');
  await expect(page.getByTestId('model-publish-replay-plan')).toContainText(/重新校验|回放/);
  const replayResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      response.request().method() === 'POST' &&
      response.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('model-publish-run-replay').click();
  await replayResponse;
  const replayReport = page.getByTestId('model-publish-replay-report');
  await expect(replayReport).toContainText('复核报告');
  await expect(replayReport).toContainText(/可自动执行|需人工复核/);
  await expect(replayReport).toContainText(/待样本|需样本/);
  await expect(replayReport).toContainText('可使用代表性成员和记录数据执行权限策略复核');
  await expect(replayReport).toContainText('权限策略 PID');
  await expect(replayReport).toContainText('授权类型: 授权');
  await expect(replayReport).toContainText('状态: 启用');
  await expect(replayReport).toContainText('SLA 节点复核');
  await expect(replayReport).toContainText('可使用流程实例、租户和任务样本执行 SLA 节点复核');
  await expect(replayReport).toContainText('目标类型: 流程节点');
  await expect(replayReport).toContainText(`目标节点: ${slaUsage.targetKey}`);
  await expect(replayReport).toContainText('动作数: 1');
  await expect(replayReport).toContainText('动作触发: SLA 超时');
  await expect(replayReport).toContainText('可使用流程实例和业务记录样本执行 BPM 规则复核');
  await expect(replayReport).toContainText('流程标识');
  await expect(replayReport).toContainText(bpmUsage.processKey);
  await expect(replayReport).toContainText('连线 ID');
  await expect(replayReport).toContainText(bpmUsage.edgeId);
  await expect(replayReport).toContainText('绑定位置: 连线条件');
  await expect(replayReport).toContainText('绑定类型: 条件表达式');
  await expect(replayReport).toContainText('影响字段');
  await expect(replayReport).toContainText(`${modelCode}.${fieldCode}`);
  await expect(replayReport).toContainText('字段风险: 字段权限变更');
  await expect(replayReport).toContainText('风险说明: 字段已脱敏且权限策略已变化，需使用低权限样本复核');
  await expect(replayReport).toContainText('脱敏字段: 是');
  await expect(replayReport).toContainText('字段权限变更: 是');
  await expect(replayReport).toContainText('需要低权限样本: 是');
  await expect(replayReport).not.toContainText('sampleContext');
  await expect(replayReport).not.toContainText('Decision replay executed');
  await expect(replayReport).not.toContainText('permissionPolicyPid');
  await expect(replayReport).not.toContainText('grantType');
  await expect(replayReport).not.toContainText('FIELD_PERMISSION_CHANGE');
  await expect(replayReport).not.toContainText('MASKED_PERMISSION_CHANGE');
  await expect(replayReport).not.toContainText('fieldPermissionChange');

  await expect(page.getByTestId('model-publish-permission-sample')).toBeVisible({
    timeout: 10_000,
  });
  await expect(page.getByTestId('model-publish-sla-node-sample')).toBeVisible({
    timeout: 10_000,
  });
  await expect(page.getByTestId('model-publish-bpm-sample')).toBeVisible({
    timeout: 10_000,
  });
  let invalidReplayRequests = 0;
  const trackInvalidReplayRequest = (request: Request) => {
    if (
      request.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      request.method() === 'POST'
    ) {
      invalidReplayRequests += 1;
    }
  };
  page.on('request', trackInvalidReplayRequest);
  await page.getByTestId('model-publish-run-permission-replay').click();
  await expect(page.getByTestId('model-publish-permission-sample-error')).toContainText(
    '请填写有效的权限成员 ID',
  );
  await expect(
    page.getByTestId('toast-stack').getByRole('alert').filter({
      hasText: '请填写有效的权限成员 ID',
    }),
  ).toHaveCount(0);
  await expect.poll(() => invalidReplayRequests).toBe(0);
  page.off('request', trackInvalidReplayRequest);

  let invalidSlaReplayRequests = 0;
  const trackInvalidSlaReplayRequest = (request: Request) => {
    if (
      request.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      request.method() === 'POST'
    ) {
      invalidSlaReplayRequests += 1;
    }
  };
  page.on('request', trackInvalidSlaReplayRequest);
  await page.getByTestId('model-publish-run-sla-node-replay').click();
  await expect(page.getByTestId('model-publish-sla-node-sample-error')).toContainText(
    '请填写流程实例 ID',
  );
  await expect(
    page.getByTestId('toast-stack').getByRole('alert').filter({
      hasText: '请填写流程实例 ID',
    }),
  ).toHaveCount(0);
  await expect.poll(() => invalidSlaReplayRequests).toBe(0);
  page.off('request', trackInvalidSlaReplayRequest);

  await page.getByTestId('model-publish-bpm-record-json').fill('{invalid-json');
  let invalidBpmReplayRequests = 0;
  const trackInvalidBpmReplayRequest = (request: Request) => {
    if (
      request.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      request.method() === 'POST'
    ) {
      invalidBpmReplayRequests += 1;
    }
  };
  page.on('request', trackInvalidBpmReplayRequest);
  await page.getByTestId('model-publish-run-bpm-replay').click();
  await expect(page.getByTestId('model-publish-bpm-sample-error')).toContainText(
    'BPM 记录数据必须是有效 JSON 对象',
  );
  await expect(
    page.getByTestId('toast-stack').getByRole('alert').filter({
      hasText: 'BPM 记录数据必须是有效 JSON 对象',
    }),
  ).toHaveCount(0);
  await expect.poll(() => invalidBpmReplayRequests).toBe(0);
  page.off('request', trackInvalidBpmReplayRequest);

  await page.getByTestId('model-publish-permission-member-id').fill(member.memberId);
  await page.getByTestId('model-publish-permission-record-json').fill('{invalid-json');
  let invalidJsonReplayRequests = 0;
  const trackInvalidJsonReplayRequest = (request: Request) => {
    if (
      request.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      request.method() === 'POST'
    ) {
      invalidJsonReplayRequests += 1;
    }
  };
  page.on('request', trackInvalidJsonReplayRequest);
  await page.getByTestId('model-publish-run-permission-replay').click();
  await expect(page.getByTestId('model-publish-permission-sample-error')).toContainText(
    '记录数据必须是有效 JSON 对象',
  );
  await expect(
    page.getByTestId('toast-stack').getByRole('alert').filter({
      hasText: '记录数据必须是有效 JSON 对象',
    }),
  ).toHaveCount(0);
  await expect.poll(() => invalidJsonReplayRequests).toBe(0);
  page.off('request', trackInvalidJsonReplayRequest);

  await page.getByTestId('model-publish-permission-code').fill(permissionCode);
  await page.getByTestId('model-publish-permission-record-pid').fill(`record-${suffix}`);
  await page.getByTestId('model-publish-permission-record-json').fill(
    JSON.stringify({ [fieldCode]: 1200, amount: 1200 }, null, 2),
  );

  const permissionReplayResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      response.request().method() === 'POST' &&
      response.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('model-publish-run-permission-replay').click();
  const permissionReplay = await permissionReplayResponse;
  const replayBody = permissionReplay.request().postDataJSON() as {
    executeAutomated?: boolean;
    sampleContext?: {
      permission?: { memberId?: string; permissionCode?: string };
      record?: { pid?: string; data?: Record<string, unknown> };
    };
  };
  expect(replayBody.executeAutomated).toBe(true);
  expect(replayBody.sampleContext?.permission?.memberId).toBe(member.memberId);
  expect(replayBody.sampleContext?.permission?.permissionCode).toBe(permissionCode);
  expect(replayBody.sampleContext?.record?.pid).toBe(`record-${suffix}`);
  expect(replayBody.sampleContext?.record?.data?.[fieldCode]).toBe(1200);
  const allowReplayReport = await readApi<ModelPublishReplayReport>(permissionReplay);
  const allowPermissionResult = requirePermissionReplayResult(allowReplayReport, permissionCode);
  expect(allowPermissionResult.status).toBe('EXECUTED');
  expect(allowPermissionResult.executed).toBe(true);
  expect(allowPermissionResult.traceId).toBeTruthy();
  expect(allowPermissionResult.matched).toBe(true);
  expect(allowPermissionResult.message).toContain('ALLOW');
  expect(allowPermissionResult.outputs).toEqual(expect.objectContaining({
    permissionCode,
    memberId: member.memberId,
    ruleTraceId: allowPermissionResult.traceId,
    granted: true,
    affectedFieldRef: `${modelCode}.${fieldCode}`,
    fieldMasked: true,
    fieldPermissionChange: true,
    fieldPermission: `model.${modelCode}.${fieldCode}.view`,
    fieldRiskLevel: 'FIELD_PERMISSION_CHANGE',
    fieldRiskSummary: 'MASKED_PERMISSION_CHANGE',
    requiresLowPermissionSample: true,
  }));
  await expect(replayReport).toContainText(/权限策略|PERMISSION_POLICY/);
  await expect(replayReport).toContainText('已执行');
  await expect(replayReport).toContainText('决策版本复核结果');
  await expect(replayReport).toContainText('权限策略复核结果：允许');
  await expect(replayReport).toContainText('权限标识');
  await expect(replayReport).toContainText(permissionCode);
  await expect(replayReport).toContainText('成员 ID');
  await expect(replayReport).toContainText(member.memberId);
  await expect(replayReport).toContainText('授权结果: 是');
  await expect(replayReport).toContainText('原因: 已授权');
  await expect(replayReport).toContainText('字段风险: 字段权限变更');
  await expect(replayReport).toContainText('风险说明: 字段已脱敏且权限策略已变化，需使用低权限样本复核');
  await expect(page.getByTestId('model-publish-replay-open-permission-trace')).toHaveAttribute(
    'href',
    new RegExp(`/p/decisionops_execution_logs\\?traceId=${allowPermissionResult.traceId}`),
  );
  await expect(replayReport).not.toContainText('Permission replay executed');
  await expect(replayReport).not.toContainText('granted: true');
  await expect(replayReport).not.toContainText('Granted');
  await expect(replayReport).not.toContainText('steps:');
  await expect(replayReport).not.toContainText('FIELD_PERMISSION_CHANGE');
  await expect(replayReport).not.toContainText('MASKED_PERMISSION_CHANGE');
  await expect(replayReport).not.toContainText('fieldPermissionChange');
  await expect(page.getByTestId('model-publish-permission-sample-error')).toHaveCount(0);

  await replayReport.screenshot({
    path: testInfo.outputPath('model-publish-permission-allow-report.png'),
  });

  await page.getByTestId('model-publish-permission-record-pid').fill(`record-deny-${suffix}`);
  await page.getByTestId('model-publish-permission-record-json').fill(
    JSON.stringify({ [fieldCode]: 100, amount: 100 }, null, 2),
  );
  const deniedPermissionReplayResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      response.request().method() === 'POST' &&
      response.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('model-publish-run-permission-replay').click();
  const deniedPermissionReplay = await deniedPermissionReplayResponse;
  const deniedReplayBody = deniedPermissionReplay.request().postDataJSON() as {
    executeAutomated?: boolean;
    sampleContext?: {
      permission?: { memberId?: string; permissionCode?: string };
      record?: { pid?: string; data?: Record<string, unknown> };
    };
  };
  expect(deniedReplayBody.executeAutomated).toBe(true);
  expect(deniedReplayBody.sampleContext?.permission?.memberId).toBe(member.memberId);
  expect(deniedReplayBody.sampleContext?.permission?.permissionCode).toBe(permissionCode);
  expect(deniedReplayBody.sampleContext?.record?.pid).toBe(`record-deny-${suffix}`);
  expect(deniedReplayBody.sampleContext?.record?.data?.[fieldCode]).toBe(100);
  const denyReplayReport = await readApi<ModelPublishReplayReport>(deniedPermissionReplay);
  const denyPermissionResult = requirePermissionReplayResult(denyReplayReport, permissionCode);
  expect(denyPermissionResult.status).toBe('EXECUTED');
  expect(denyPermissionResult.executed).toBe(true);
  expect(denyPermissionResult.traceId).toBeTruthy();
  expect(denyPermissionResult.matched).toBe(false);
  expect(denyPermissionResult.message).toContain('DENY');
  expect(denyPermissionResult.outputs).toEqual(expect.objectContaining({
    permissionCode,
    memberId: member.memberId,
    ruleTraceId: denyPermissionResult.traceId,
    granted: false,
    affectedFieldRef: `${modelCode}.${fieldCode}`,
    fieldMasked: true,
    fieldPermissionChange: true,
    fieldPermission: `model.${modelCode}.${fieldCode}.view`,
    fieldRiskLevel: 'FIELD_PERMISSION_CHANGE',
    fieldRiskSummary: 'MASKED_PERMISSION_CHANGE',
    requiresLowPermissionSample: true,
  }));
  await expect(replayReport).toContainText(/权限策略|PERMISSION_POLICY/);
  await expect(replayReport).toContainText('已执行');
  await expect(replayReport).toContainText('权限策略复核结果：拒绝');
  await expect(replayReport).toContainText('授权结果: 否');
  await expect(replayReport).toContainText('原因: 条件未满足');
  await expect(replayReport).toContainText('字段风险: 字段权限变更');
  await expect(page.getByTestId('model-publish-replay-open-permission-trace')).toHaveAttribute(
    'href',
    new RegExp(`/p/decisionops_execution_logs\\?traceId=${denyPermissionResult.traceId}`),
  );
  await expect(replayReport).not.toContainText('Permission replay executed');
  await expect(replayReport).not.toContainText('granted: false');
  await expect(replayReport).not.toContainText('Condition guard not satisfied');
  await expect(replayReport).not.toContainText('steps:');
  await expect(replayReport).not.toContainText('FIELD_PERMISSION_CHANGE');

  await replayReport.screenshot({
    path: testInfo.outputPath('model-publish-permission-deny-report.png'),
  });

  await expectPermissionReplayTraceLinkOpensDecisionOps(page, {
    traceId: denyPermissionResult.traceId!,
    permissionCode,
    resourceCode: modelCode,
    testInfo,
  });

  const slaProcessInstanceId = `PROC-${suffix}`;
  const slaTaskId = `TASK-${suffix}`;
  await page.getByTestId('model-publish-sla-process-instance-id').fill(slaProcessInstanceId);
  await page.getByTestId('model-publish-sla-tenant-id').fill(member.tenantId);
  await page.getByTestId('model-publish-sla-task-id').fill(slaTaskId);
  await page.getByTestId('model-publish-sla-process-key').fill(slaUsage.processKey);
  await page.getByTestId('model-publish-sla-record-json').fill(
    JSON.stringify(
      {
        [fieldCode]: 2400,
        amount: 2400,
        targetKey: slaUsage.targetKey,
      },
      null,
      2,
    ),
  );

  const slaReplayResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      response.request().method() === 'POST' &&
      response.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('model-publish-run-sla-node-replay').click();
  const slaReplay = await slaReplayResponse;
  const slaReplayBody = slaReplay.request().postDataJSON() as {
    executeAutomated?: boolean;
    sampleContext?: {
      bpm?: {
        processInstanceId?: string;
        tenantId?: string;
        taskId?: string;
        processKey?: string;
      };
      record?: { data?: Record<string, unknown> };
    };
  };
  expect(slaReplayBody.executeAutomated).toBe(true);
  expect(slaReplayBody.sampleContext?.bpm?.processInstanceId).toBe(slaProcessInstanceId);
  expect(slaReplayBody.sampleContext?.bpm?.tenantId).toBe(member.tenantId);
  expect(slaReplayBody.sampleContext?.bpm?.taskId).toBe(slaTaskId);
  expect(slaReplayBody.sampleContext?.bpm?.processKey).toBe(slaUsage.processKey);
  expect(slaReplayBody.sampleContext?.record?.data?.[fieldCode]).toBe(2400);
  const slaReplayReport = await readApi<ModelPublishReplayReport>(slaReplay);
  const slaNodeResult = requireSlaNodeReplayResult(
    slaReplayReport,
    slaUsage.targetKey,
    slaUsage.sla.pid,
  );
  expect(slaNodeResult.status).toBe('EXECUTED');
  expect(slaNodeResult.executed).toBe(true);
  expect(slaNodeResult.matched).toBe(true);
  expect(slaNodeResult.message).toContain('SLA NODE replay');
  expect(slaNodeResult.outputs).toEqual(expect.objectContaining({
    targetType: 'NODE',
    targetKey: slaUsage.targetKey,
    processInstanceId: slaProcessInstanceId,
    taskId: slaTaskId,
    actionCount: 1,
    actionPolicyTrigger: 'SLA_TIMEOUT',
  }));
  expect(slaNodeResult.outputs?.slaRecordPid).toEqual(expect.any(String));
  expect(slaNodeResult.outputs).not.toHaveProperty('recordPid');
  await expect(replayReport).toContainText(/SLA 策略|SLA_RULE/);
  await expect(replayReport).toContainText('已执行');
  await expect(replayReport).toContainText('SLA 节点复核已执行');
  await expect(replayReport).toContainText('流程实例');
  await expect(replayReport).toContainText(slaProcessInstanceId);
  await expect(replayReport).toContainText('任务 ID');
  await expect(replayReport).toContainText(slaTaskId);
  await expect(replayReport).toContainText('目标类型: 流程节点');
  await expect(replayReport).toContainText(`目标节点: ${slaUsage.targetKey}`);
  await expect(replayReport).toContainText('动作数: 1');
  await expect(replayReport).toContainText('动作触发: SLA 超时');
  await expect(replayReport).toContainText('SLA 记录 PID');
  await expect(replayReport).not.toContainText('SLA NODE replay executed');
  await expect(replayReport).not.toContainText('SLA NODE replay activated');
  await expect(replayReport).not.toContainText('actionPolicyTrigger');
  await expect(replayReport).not.toContainText('SLA_TIMEOUT');
  await expect(replayReport).not.toContainText('deadlineMode');
  await expect(replayReport).not.toContainText('deadlineValue');
  await expect(replayReport).not.toContainText('modelCode');
  await expect(replayReport).not.toContainText('recordPid');
  await expect(page.getByTestId('model-publish-sla-node-sample-error')).toHaveCount(0);

  await replayReport.screenshot({
    path: testInfo.outputPath('model-publish-sla-node-report.png'),
  });

  const bpmProcessInstanceId = `BPM-${suffix}`;
  await page.getByTestId('model-publish-bpm-process-instance-id').fill(bpmProcessInstanceId);
  await page.getByTestId('model-publish-bpm-process-key').fill(bpmUsage.processKey);
  await page.getByTestId('model-publish-bpm-record-pid').fill(`bpm-record-${suffix}`);
  await page.getByTestId('model-publish-bpm-record-json').fill(
    JSON.stringify({ [fieldCode]: 2400, amount: 2400 }, null, 2),
  );

  const bpmReplayResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      response.request().method() === 'POST' &&
      response.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('model-publish-run-bpm-replay').click();
  const bpmReplay = await bpmReplayResponse;
  const bpmReplayBody = bpmReplay.request().postDataJSON() as {
    executeAutomated?: boolean;
    sampleContext?: {
      bpm?: { processInstanceId?: string; processKey?: string };
      record?: { pid?: string; data?: Record<string, unknown> };
    };
  };
  expect(bpmReplayBody.executeAutomated).toBe(true);
  expect(bpmReplayBody.sampleContext?.bpm?.processInstanceId).toBe(bpmProcessInstanceId);
  expect(bpmReplayBody.sampleContext?.bpm?.processKey).toBe(bpmUsage.processKey);
  expect(bpmReplayBody.sampleContext?.record?.pid).toBe(`bpm-record-${suffix}`);
  expect(bpmReplayBody.sampleContext?.record?.data?.[fieldCode]).toBe(2400);
  const bpmReplayReport = await readApi<ModelPublishReplayReport>(bpmReplay);
  const bpmResult = requireBpmReplayResult(
    bpmReplayReport,
    bpmUsage.processKey,
    bpmUsage.edgeId,
  );
  expect(bpmResult.status).toBe('EXECUTED');
  expect(bpmResult.executed).toBe(true);
  expect(bpmResult.matched).toBe(true);
  expect(bpmResult.outputs).toEqual(expect.objectContaining({
    processKey: bpmUsage.processKey,
    processInstanceId: bpmProcessInstanceId,
    edgeId: bpmUsage.edgeId,
    edgeSource: bpmUsage.sourceNodeId,
    edgeTarget: bpmUsage.targetNodeId,
    nodeType: 'sequenceFlow',
    bindingKind: 'CONDITION',
    bindingSurface: 'edge conditionSpec',
    conditionResult: 'TRUE',
  }));
  await expect(replayReport).toContainText(/BPM 流程|BPM_PROCESS/);
  await expect(replayReport).toContainText('已执行');
  await expect(replayReport).toContainText('BPM 规则复核结果：命中');
  await expect(replayReport).toContainText('流程实例');
  await expect(replayReport).toContainText(bpmProcessInstanceId);
  await expect(replayReport).toContainText('流程标识');
  await expect(replayReport).toContainText(bpmUsage.processKey);
  await expect(replayReport).toContainText('节点类型: 流程连线');
  await expect(replayReport).toContainText('连线 ID');
  await expect(replayReport).toContainText(bpmUsage.edgeId);
  await expect(replayReport).toContainText('来源节点');
  await expect(replayReport).toContainText(bpmUsage.sourceNodeId);
  await expect(replayReport).toContainText('目标节点');
  await expect(replayReport).toContainText(bpmUsage.targetNodeId);
  await expect(replayReport).toContainText('绑定位置: 连线条件');
  await expect(replayReport).toContainText('绑定类型: 条件表达式');
  await expect(replayReport).toContainText('条件结果: 满足');
  await expect(replayReport).not.toContainText('BPM replay evaluated');
  await expect(replayReport).not.toContainText('bindingKind');
  await expect(replayReport).not.toContainText('conditionResult');
  await expect(page.getByTestId('model-publish-bpm-sample-error')).toHaveCount(0);

  const bpmReportCard = page.getByTestId('model-publish-replay-result-BPM_PROCESS');
  await expect(bpmReportCard).toBeVisible({ timeout: 5_000 });
  await expect(bpmReportCard).toContainText('BPM 规则复核结果：命中');
  await expect(bpmReportCard).toContainText(bpmUsage.processKey);
  await bpmReportCard.scrollIntoViewIfNeeded();
  await bpmReportCard.screenshot({
    path: testInfo.outputPath('model-publish-bpm-report.png'),
  });

  await expect(governance).toContainText('迁移计划');
  await expect(governance).toContainText('历史版本策略');

  const confirm = page.getByTestId('model-publish-confirm');
  await expect(confirm).toBeDisabled();

  await governance.screenshot({
    path: testInfo.outputPath('model-publish-governance-impact-ack.png'),
  });

  await page.getByTestId('model-publish-impact-ack').check();
  await expect(confirm).toBeEnabled();

  const publishRequest = page.waitForRequest((request) => {
    return (
      request.url().includes(`/api/meta/models/${model.pid}/publish`) &&
      request.method() === 'POST'
    );
  });
  const publishResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish`) &&
      response.request().method() === 'POST' &&
      response.status() === 200,
    { timeout: 20_000 },
  );
  await confirm.click();
  const request = await publishRequest;
  const postData = request.postDataJSON() as { impactAcknowledged?: boolean; acknowledgementNote?: string };
  expect(postData).toEqual(
    expect.objectContaining({
      impactAcknowledged: true,
      acknowledgementNote: expect.stringContaining('Model publish impact acknowledged'),
    }),
  );
  await publishResponse;

  const published = await getApi<MetaModel>(page, `/api/meta/models/${model.pid}`);
  expect(published.status).toMatch(/published/i);
});

test('RC-MODEL-01B: model publish replay executes BPM userTask assignment rule binding', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);

  const suffix = shortSuffix();
  const { model, modelCode, fieldCode, runtimeFieldRef } = await createDraftModelWithField(
    page,
    `bpm_assign_${suffix}`,
  );
  const decisionCode = `rcmp_bpm_assign_${suffix}`;
  await createPublishedTaskAssignmentDecisionReferencingField(page, {
    decisionCode,
    fieldCode,
    runtimeFieldRef,
    suffix,
  });
  const bpmUsage = await createBpmUserTaskAssignmentUsageReferencingField(page, {
    suffix,
    modelCode,
    fieldCode,
    decisionCode,
  });
  const expectedUsers = [`u-manager-${suffix}`, `u-finance-${suffix}`];
  const expectedGroups = [`finance-${suffix}`];

  await postApi(page, '/api/decision/usage-index/rebuild');
  await expect(async () => {
    const currentImpact = await getApi<DecisionFieldImpact>(
      page,
      `/api/decision/fields/impact?fieldRef=${encodeURIComponent(runtimeFieldRef)}`,
    );
    expect(currentImpact.fieldRef).toBe(runtimeFieldRef);
    expect(currentImpact.risk?.blocking).toBe(true);
    expect(currentImpact.references ?? []).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          sourceType: 'BPM_PROCESS',
          sourceCode: bpmUsage.processKey,
          targetPath: runtimeFieldRef,
          binding: 'DESIGNER_NODE',
        }),
      ]),
    );
  }).toPass({ timeout: 15_000 });

  await navigateToModelListViaMenu(page);
  await searchModel(page, modelCode);

  const row = modelRow(page, modelCode);
  await expect(row).toBeVisible({ timeout: 10_000 });
  const viewButton = row.getByRole('button', { name: '查看' }).first();
  await expect(viewButton).toBeVisible({ timeout: 5_000 });
  await viewButton.evaluate((element: HTMLElement) => element.click());
  await expect(page).toHaveURL(new RegExp(`/meta/models/${model.pid}(?:#|\\?|$)`), {
    timeout: 10_000,
  });

  await page.getByTestId('model-more-actions').click();
  const previewResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish/preview`) &&
      response.request().method() === 'GET' &&
      response.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('model-publish-action').click();
  await previewResponse;

  const governance = page.getByTestId('model-publish-governance');
  await expect(governance).toBeVisible({ timeout: 10_000 });
  await expect(governance).toContainText('规则中心影响治理');
  await expect(governance).toContainText(`${modelCode}.${fieldCode}`);
  await expect(governance).toContainText(/BPM 流程|BPM_PROCESS/);
  await expect(governance).toContainText(bpmUsage.processName);
  await expect(page.getByTestId('model-publish-bpm-sample')).toBeVisible({ timeout: 10_000 });

  const readyResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      response.request().method() === 'POST' &&
      response.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('model-publish-run-replay').click();
  const readyReplay = await readyResponse;
  const readyReport = await readApi<ModelPublishReplayReport>(readyReplay);
  const readyResult = requireBpmAssignmentReplayResult(
    readyReport,
    bpmUsage.processKey,
    bpmUsage.taskNodeId,
  );
  expect(readyResult.status).toBe('READY');
  expect(readyResult.outputs).toEqual(expect.objectContaining({
    processKey: bpmUsage.processKey,
    nodeId: bpmUsage.taskNodeId,
    nodeType: 'userTask',
    bindingKind: 'DECISION_REF',
    bindingSurface: 'node ruleBinding',
    decisionCode,
  }));

  const replayReport = page.getByTestId('model-publish-replay-report');
  await expect(replayReport).toContainText('可使用流程实例和业务记录样本执行 BPM 规则复核');

  const processInstanceId = `BPM-ASSIGN-${suffix}`;
  const recordPid = `bpm-assign-record-${suffix}`;
  await page.getByTestId('model-publish-bpm-process-instance-id').fill(processInstanceId);
  await page.getByTestId('model-publish-bpm-process-key').fill(bpmUsage.processKey);
  await page.getByTestId('model-publish-bpm-record-pid').fill(recordPid);
  await page.getByTestId('model-publish-bpm-record-json').fill(
    JSON.stringify({ [fieldCode]: 2600, amount: 2600 }, null, 2),
  );

  const bpmReplayResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      response.request().method() === 'POST' &&
      response.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('model-publish-run-bpm-replay').click();
  const bpmReplay = await bpmReplayResponse;
  const bpmReplayBody = bpmReplay.request().postDataJSON() as {
    executeAutomated?: boolean;
    sampleContext?: {
      bpm?: { processInstanceId?: string; processKey?: string };
      record?: { pid?: string; data?: Record<string, unknown> };
    };
  };
  expect(bpmReplayBody.executeAutomated).toBe(true);
  expect(bpmReplayBody.sampleContext?.bpm?.processInstanceId).toBe(processInstanceId);
  expect(bpmReplayBody.sampleContext?.bpm?.processKey).toBe(bpmUsage.processKey);
  expect(bpmReplayBody.sampleContext?.record?.pid).toBe(recordPid);
  expect(bpmReplayBody.sampleContext?.record?.data?.[fieldCode]).toBe(2600);

  const bpmReplayReport = await readApi<ModelPublishReplayReport>(bpmReplay);
  const bpmResult = requireBpmAssignmentReplayResult(
    bpmReplayReport,
    bpmUsage.processKey,
    bpmUsage.taskNodeId,
  );
  expect(bpmResult.status).toBe('EXECUTED');
  expect(bpmResult.executed).toBe(true);
  expect(bpmResult.matched).toBe(true);
  expect(bpmResult.outputs).toEqual(expect.objectContaining({
    processKey: bpmUsage.processKey,
    processInstanceId,
    nodeId: bpmUsage.taskNodeId,
    nodeType: 'userTask',
    bindingKind: 'DECISION_REF',
    bindingSurface: 'node ruleBinding',
    decisionCode,
    decisionStatus: 'MATCHED',
    candidateUserIds: expectedUsers,
    candidateGroupIds: expectedGroups,
    failClosed: false,
  }));
  await expect(replayReport).toContainText('BPM 分派规则复核已执行：已解析候选审批人');
  await expect(replayReport).toContainText('节点类型: 审批任务');
  await expect(replayReport).toContainText(`节点 ID: ${bpmUsage.taskNodeId}`);
  await expect(replayReport).toContainText('绑定位置: 节点规则绑定');
  await expect(replayReport).toContainText('绑定类型: 决策引用');
  await expect(replayReport).toContainText('候选审批人');
  await expect(replayReport).toContainText(expectedUsers[0]);
  await expect(replayReport).toContainText(expectedUsers[1]);
  await expect(replayReport).toContainText('候选审批组');
  await expect(replayReport).toContainText(expectedGroups[0]);
  await expect(replayReport).toContainText('失败关闭: 否');
  await expect(replayReport).not.toContainText('BPM replay evaluated');
  await expect(replayReport).not.toContainText('candidateUserIds');
  await expect(replayReport).not.toContainText('candidateGroupIds');
  await expect(page.getByTestId('model-publish-bpm-sample-error')).toHaveCount(0);

  const bpmReportCard = page.getByTestId('model-publish-replay-result-BPM_PROCESS');
  await expect(bpmReportCard).toBeVisible({ timeout: 5_000 });
  await expect(bpmReportCard).toContainText('已解析候选审批人');
  await expect(bpmReportCard).toContainText(expectedUsers[0]);
  await bpmReportCard.scrollIntoViewIfNeeded();
  await bpmReportCard.screenshot({
    path: testInfo.outputPath('model-publish-bpm-assignment-report.png'),
  });
});

test('RC-MODEL-01C: model publish replay shows BPM userTask fail-closed when decision binding errors', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);

  const suffix = shortSuffix();
  const { model, modelCode, fieldCode, runtimeFieldRef } = await createDraftModelWithField(
    page,
    `bpm_fail_${suffix}`,
  );
  const missingDecisionCode = `rcmp_bpm_missing_${suffix}`;
  const bpmUsage = await createBpmUserTaskAssignmentUsageReferencingField(page, {
    suffix,
    modelCode,
    fieldCode,
    decisionCode: missingDecisionCode,
  });

  await postApi(page, '/api/decision/usage-index/rebuild');
  await expect(async () => {
    const currentImpact = await getApi<DecisionFieldImpact>(
      page,
      `/api/decision/fields/impact?fieldRef=${encodeURIComponent(runtimeFieldRef)}`,
    );
    expect(currentImpact.fieldRef).toBe(runtimeFieldRef);
    expect(currentImpact.risk?.blocking).toBe(true);
    expect(currentImpact.references ?? []).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          sourceType: 'BPM_PROCESS',
          sourceCode: bpmUsage.processKey,
          targetPath: runtimeFieldRef,
          binding: 'DESIGNER_NODE',
        }),
      ]),
    );
  }).toPass({ timeout: 15_000 });

  await navigateToModelListViaMenu(page);
  await searchModel(page, modelCode);

  const row = modelRow(page, modelCode);
  await expect(row).toBeVisible({ timeout: 10_000 });
  const viewButton = row.getByRole('button', { name: '查看' }).first();
  await expect(viewButton).toBeVisible({ timeout: 5_000 });
  await viewButton.evaluate((element: HTMLElement) => element.click());
  await expect(page).toHaveURL(new RegExp(`/meta/models/${model.pid}(?:#|\\?|$)`), {
    timeout: 10_000,
  });

  await page.getByTestId('model-more-actions').click();
  const previewResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish/preview`) &&
      response.request().method() === 'GET' &&
      response.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('model-publish-action').click();
  await previewResponse;

  const governance = page.getByTestId('model-publish-governance');
  await expect(governance).toBeVisible({ timeout: 10_000 });
  await expect(governance).toContainText('规则中心影响治理');
  await expect(governance).toContainText(`${modelCode}.${fieldCode}`);
  await expect(governance).toContainText(/BPM 流程|BPM_PROCESS/);
  await expect(governance).toContainText(bpmUsage.processName);
  await expect(page.getByTestId('model-publish-bpm-sample')).toBeVisible({ timeout: 10_000 });

  const processInstanceId = `BPM-FAIL-CLOSED-${suffix}`;
  const recordPid = `bpm-fail-record-${suffix}`;
  await page.getByTestId('model-publish-bpm-process-instance-id').fill(processInstanceId);
  await page.getByTestId('model-publish-bpm-process-key').fill(bpmUsage.processKey);
  await page.getByTestId('model-publish-bpm-record-pid').fill(recordPid);
  await page.getByTestId('model-publish-bpm-record-json').fill(
    JSON.stringify({ [fieldCode]: 2600, amount: 2600 }, null, 2),
  );

  const bpmReplayResponse = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/models/${model.pid}/publish/replay`) &&
      response.request().method() === 'POST' &&
      response.status() === 200,
    { timeout: 15_000 },
  );
  await page.getByTestId('model-publish-run-bpm-replay').click();
  const bpmReplay = await bpmReplayResponse;
  const bpmReplayBody = bpmReplay.request().postDataJSON() as {
    executeAutomated?: boolean;
    sampleContext?: {
      bpm?: { processInstanceId?: string; processKey?: string };
      record?: { pid?: string; data?: Record<string, unknown> };
    };
  };
  expect(bpmReplayBody.executeAutomated).toBe(true);
  expect(bpmReplayBody.sampleContext?.bpm?.processInstanceId).toBe(processInstanceId);
  expect(bpmReplayBody.sampleContext?.bpm?.processKey).toBe(bpmUsage.processKey);
  expect(bpmReplayBody.sampleContext?.record?.pid).toBe(recordPid);
  expect(bpmReplayBody.sampleContext?.record?.data?.[fieldCode]).toBe(2600);

  const bpmReplayReport = await readApi<ModelPublishReplayReport>(bpmReplay);
  const bpmResult = requireBpmAssignmentReplayResult(
    bpmReplayReport,
    bpmUsage.processKey,
    bpmUsage.taskNodeId,
  );
  expect(bpmResult.status).toBe('FAILED');
  expect(bpmResult.executed).toBe(false);
  expect(bpmResult.matched).toBe(false);
  expect(bpmResult.errors ?? []).toEqual(expect.arrayContaining(['BPM_RULE_BINDING_FAIL_CLOSED']));
  expect(bpmResult.outputs).toEqual(expect.objectContaining({
    processKey: bpmUsage.processKey,
    processInstanceId,
    nodeId: bpmUsage.taskNodeId,
    nodeType: 'userTask',
    bindingKind: 'DECISION_REF',
    bindingSurface: 'node ruleBinding',
    decisionCode: missingDecisionCode,
    decisionStatus: 'ERROR',
    fallbackApplied: true,
    errorCode: 'DECISION_EVALUATION_FAILED',
    candidateUserIds: [],
    candidateGroupIds: [],
    failClosed: true,
  }));

  const replayReport = page.getByTestId('model-publish-replay-report');
  await expect(replayReport).toContainText('BPM 分派规则复核失败：规则执行异常，已失败关闭，未使用静态审批人兜底');
  await expect(replayReport).toContainText('失败关闭: 是');
  await expect(replayReport).toContainText('已使用兜底: 是');
  await expect(replayReport).toContainText('错误码: 决策执行失败');
  await expect(replayReport).toContainText('规则绑定已失败关闭');
  await expect(replayReport).toContainText('候选审批人: 无');
  await expect(replayReport).toContainText('候选审批组: 无');
  await expect(replayReport).not.toContainText('BPM rule binding failed closed');
  await expect(replayReport).not.toContainText('BPM_RULE_BINDING_FAIL_CLOSED');
  await expect(replayReport).not.toContainText('DECISION_EVALUATION_FAILED');
  await expect(replayReport).not.toContainText('candidateUserIds');
  await expect(page.getByTestId('model-publish-bpm-sample-error')).toHaveCount(0);

  const bpmReportCard = page.getByTestId('model-publish-replay-result-BPM_PROCESS');
  await expect(bpmReportCard).toBeVisible({ timeout: 5_000 });
  await expect(bpmReportCard).toContainText('已失败关闭');
  await bpmReportCard.scrollIntoViewIfNeeded();
  await bpmReportCard.screenshot({
    path: testInfo.outputPath('model-publish-bpm-assignment-fail-closed-report.png'),
  });
});
