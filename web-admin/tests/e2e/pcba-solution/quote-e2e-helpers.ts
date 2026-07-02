import type { Browser, BrowserContext, Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { utils as XLSXUtils, write } from 'xlsx';
import { expect } from '../../fixtures';
import { loginViaUI } from '../../helpers/wd-fixtures';
import {
  clickRowActionByLocator,
  ensureSidebarExpanded,
  findRowInPaginatedList,
  waitForDynamicPageLoad,
} from '../helpers';

export type CreatedRows = {
  quoteId: string;
  quoteCode: string;
  rows: Array<{ model: string; pid: string }>;
};

export type DynamicFilter = {
  fieldName: string;
  operator: string;
  value: unknown;
};

export type BomPriceManualReviewSeed = CreatedRows & {
  lineId: string;
  mpn: string;
  suggestedEvidenceId: string;
  failedEvidenceId: string;
};

export type BomWorkbenchSeed = CreatedRows & {
  projectId: string;
  taskId: string;
  standardLineId: string;
  rawLineId: string;
  canonicalLineId: string;
  matchResultId: string;
  primaryEvidenceId: string;
  secondaryEvidenceId: string;
  exportRevisionId: string;
  candidateCode: string;
  marker: string;
};

type QuoteLineSeed = {
  sourceRef: string;
  sourceRowNo: number;
  description: string;
  refdes: string;
  mpn: string;
  packageName: string;
  qty: number;
  itemType?: string;
  unitCost?: number;
  lineCost?: number;
  linePrice?: number;
  smtPoints: number;
  thtPoints: number;
  boardWidthMm?: number;
  boardHeightMm?: number;
  boardAreaMm2?: number;
  gerberParseStatus?: string;
  gerberValidationStatus?: string;
  gerberValidationMessages?: string[];
  gerberInspection?: Record<string, unknown>;
};

export const GERBER_RUNTIME_TOP_FILE_ID = '01KV22CQ7PKX3W50Y7MM575ACK';
export const GERBER_RUNTIME_BOTTOM_FILE_ID = '01KV22CQ7PKX3W50Y7MM575ACM';
export const QUOTE_ROLE_TEST_PASSWORD = 'Test2026x';

export type QuoteRoleUser = {
  key: string;
  email: string;
  displayName: string;
  password: string;
  roleCodes: string[];
};

export type MenuSnapshotItem = {
  code: string;
  path: string;
  permissionCode: string;
  name: string;
};

export type RoleSnapshot = {
  roleCodes: string[];
  permissionCodes: string[];
  menuCodes: string[];
  menuPaths: string[];
  menus: MenuSnapshotItem[];
};

export type CommandProbeResult = {
  status: number;
  body: Record<string, unknown>;
  text: string;
};

export function isTransientViteDynamicImportIssue(text: string): boolean {
  return (
    /Failed to fetch dynamically imported module:\s+https?:\/\/(?:127\.0\.0\.1|localhost):\d+\/app\//i.test(
      text,
    ) || /React Router caught the following error during render.*Failed to fetch dynamically imported module/i.test(text)
  );
}

async function clickSidebarPage(page: Page, href: string, label: RegExp): Promise<void> {
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav.locator(`a[href="${href}"]`).or(nav.getByRole('link', { name: label })).first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  for (let attempt = 0; attempt < 2; attempt += 1) {
    await link.scrollIntoViewIfNeeded();
    await link.click();
    const navigated = await page
      .waitForURL((url) => url.pathname === href, { timeout: 8_000 })
      .then(() => true)
      .catch(() => false);
    if (navigated) break;
    if (attempt === 1) {
      await expect.poll(() => new URL(page.url()).pathname).toBe(href);
    }
  }
  await waitForDynamicPageLoad(page, 20_000);
  const main = page.locator('main');
  const contentLoaded = await expect(main)
    .toContainText(label, { timeout: 20_000 })
    .then(() => true)
    .catch(() => false);
  if (!contentLoaded) {
    // Fresh Vite runtimes can force a one-time dependency-optimization reload after menu entry.
    await page.reload({ waitUntil: 'domcontentloaded' });
    await waitForDynamicPageLoad(page, 20_000);
    await expect(main).toContainText(label, { timeout: 20_000 });
  }
}

export async function openQuoteDetailFromList(page: Page, created: CreatedRows): Promise<void> {
  expect(created.quoteId, 'quote id is required to open quote detail').toBeTruthy();
  expect(created.quoteCode, 'quote code is required to find the quote row').toBeTruthy();

  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  await clickSidebarPage(page, '/p/qo_quote_common', /报价单|Quotes/i);

  const row = await findRowInPaginatedList(page, created.quoteCode, 20_000);
  await Promise.all([
    page
      .waitForURL((url) => url.pathname === `/p/qo_quote_common/view/${created.quoteId}`, {
        timeout: 20_000,
      })
      .catch(() => null),
    clickRowActionByLocator(page, row, 'view', '查看'),
  ]);
  await waitForDynamicPageLoad(page, 20_000);
}

export async function openQuoteCreateFormFromList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  await clickSidebarPage(page, '/p/qo_quote_common', /报价单|Quotes/i);

  const createButton = page
    .getByTestId('toolbar-btn-create')
    .or(page.getByRole('button', { name: /新建报价|Create/i }))
    .first();
  await expect(createButton).toBeVisible({ timeout: 20_000 });
  await Promise.all([
    page
      .waitForURL((url) => url.pathname === '/p/qo_quote_common/new', { timeout: 20_000 })
      .catch(() => null),
    createButton.click(),
  ]);
  await waitForDynamicPageLoad(page, 20_000);
}

const BOM_INTERNAL_FIXTURE_MODELS = [
  'req_requirement_set_pcba_bom',
  'bom_conversion_task_pcba',
  'bom_raw_line_pcba',
  'bom_standard_line_pcba',
  'bom_match_result_pcba',
  'bom_match_evidence',
  'bom_review_decision',
  'bom_export_revision',
];

const MODEL_FIXTURE_ACTIONS = ['read', 'create', 'update', 'delete', 'export', 'import'];

export function makeQuoteRoleUser(
  key: string,
  uid: string,
  roleCodes: string[],
): QuoteRoleUser {
  const normalized = key.replace(/[^a-z0-9]+/gi, '-').toLowerCase();
  return {
    key,
    email: `e2e-${normalized}-${uid}@e2e.local`,
    displayName: `E2E ${key} ${uid.slice(-8)}`.slice(0, 50),
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes,
  };
}

export async function ensureQuoteRoleUser(page: Page, user: QuoteRoleUser): Promise<void> {
  const resp = await page.request.post('/api/admin/users', {
    data: {
      email: user.email,
      displayName: user.displayName,
      initialPassword: user.password,
      roleCodes: user.roleCodes,
      sendInviteEmail: false,
    },
    timeout: 20_000,
  });
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `create role user ${user.key} (${user.email}) HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);

  const assignedRoles = Array.isArray((body as any).data?.assignedRoles)
    ? (body as any).data.assignedRoles.map(String)
    : [];
  for (const roleCode of user.roleCodes) {
    expect(
      assignedRoles,
      `create role user ${user.key} should assign role ${roleCode}; response=${JSON.stringify(body).slice(0, 800)}`,
    ).toContain(roleCode);
  }
}

export async function openQuoteRolePage(
  browser: Browser,
  user: QuoteRoleUser,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await loginViaUI(page, user.email, user.password);
  return { context, page };
}

function extractPermissionCodes(permissions: Record<string, unknown>): string[] {
  const permissionCodes = permissions.permissionCodes;
  if (Array.isArray(permissionCodes)) {
    return permissionCodes.map(String).sort();
  }
  const permissionObjects = permissions.permissions;
  if (Array.isArray(permissionObjects)) {
    return permissionObjects
      .map((permission) => String((permission as Record<string, unknown>).code ?? ''))
      .filter(Boolean)
      .sort();
  }
  return [];
}

function flattenMenuData(items: unknown[]): MenuSnapshotItem[] {
  const result: MenuSnapshotItem[] = [];
  const visit = (menuItems: unknown[]) => {
    for (const item of menuItems) {
      const menu = item as Record<string, unknown>;
      result.push({
        code: String(menu.code ?? ''),
        path: String(menu.path ?? ''),
        permissionCode: String(menu.permissionCode ?? menu.permission_code ?? ''),
        name: String(menu.name ?? ''),
      });
      const children = menu.children ?? menu.submenu;
      if (Array.isArray(children)) {
        visit(children);
      }
    }
  };
  visit(items);
  return result;
}

export async function fetchRoleSnapshot(page: Page): Promise<RoleSnapshot> {
  const meResp = await page.request.get('/api/auth/me', { timeout: 15_000 });
  const meBody = await meResp.json().catch(() => ({}));
  expect(
    meResp.ok(),
    `/api/auth/me HTTP ${meResp.status()}: ${JSON.stringify(meBody).slice(0, 800)}`,
  ).toBe(true);

  const permissions = ((meBody as any).data?.permissions ?? {}) as Record<string, unknown>;
  const roles = Array.isArray(permissions.roles) ? permissions.roles : [];
  const roleCodes = roles
    .map((role) => String((role as Record<string, unknown>).code ?? ''))
    .filter(Boolean)
    .sort();
  const permissionCodes = extractPermissionCodes(permissions);

  const menuResp = await page.request.get('/api/menu/user', { timeout: 15_000 });
  const menuBody = await menuResp.json().catch(() => ({}));
  expect(
    menuResp.ok(),
    `/api/menu/user HTTP ${menuResp.status()}: ${JSON.stringify(menuBody).slice(0, 800)}`,
  ).toBe(true);
  const menuRoot = Array.isArray((menuBody as any).data) ? (menuBody as any).data : [];
  const menus = flattenMenuData(menuRoot);

  return {
    roleCodes,
    permissionCodes,
    menus,
    menuCodes: menus.map((menu) => menu.code).filter(Boolean).sort(),
    menuPaths: menus.map((menu) => menu.path).filter(Boolean).sort(),
  };
}

function parseJsonObject(text: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

export async function probeCommand(
  page: Page,
  commandCode: string,
  payload: Record<string, unknown> = {},
  targetRecordId?: string,
  operationType?: string,
): Promise<CommandProbeResult> {
  const data: Record<string, unknown> = { payload };
  if (targetRecordId) data.targetRecordId = targetRecordId;
  if (operationType) data.operationType = operationType;
  const resp = await page.request.post(
    `/api/meta/commands/execute/${encodeURIComponent(commandCode)}`,
    {
      data,
      timeout: 20_000,
    },
  );
  const text = await resp.text();
  return { status: resp.status(), text, body: parseJsonObject(text) };
}

export function isPermissionDeniedResult(result: CommandProbeResult): boolean {
  const code = String((result.body as any).code ?? (result.body as any).status ?? '');
  if (result.status === 403 || code === '403' || code === '10403') {
    return true;
  }
  return /Access forbidden|Access denied|Forbidden|required permission|permission denied|缺少权限|无权限|没有权限|not authorized/i.test(
    result.text,
  );
}

export async function expectCommandDenied(
  page: Page,
  commandCode: string,
  payload: Record<string, unknown> = {},
  targetRecordId?: string,
  operationType?: string,
): Promise<void> {
  const result = await probeCommand(page, commandCode, payload, targetRecordId, operationType);
  expect(
    isPermissionDeniedResult(result),
    `${commandCode} should be denied, got HTTP ${result.status}: ${result.text.slice(0, 800)}`,
  ).toBe(true);
}

export async function expectCommandNotDenied(
  page: Page,
  commandCode: string,
  payload: Record<string, unknown> = {},
  targetRecordId?: string,
  operationType?: string,
): Promise<CommandProbeResult> {
  const result = await probeCommand(page, commandCode, payload, targetRecordId, operationType);
  expect(
    isPermissionDeniedResult(result),
    `${commandCode} should pass permission gate, got HTTP ${result.status}: ${result.text.slice(0, 800)}`,
  ).toBe(false);
  return result;
}

async function pollAsyncTaskResult(page: Page, taskCode: string): Promise<Record<string, unknown>> {
  const terminal = new Set(['completed', 'failed', 'cancelled']);
  let resultData: Record<string, unknown> = {};

  await expect
    .poll(
      async () => {
        const resp = await page.request.get(`/api/async-tasks/${encodeURIComponent(taskCode)}`, {
          timeout: 15_000,
        });
        const body = await resp.json().catch(() => ({}));
        if (!resp.ok()) {
          return `http:${resp.status()}:${JSON.stringify(body).slice(0, 500)}`;
        }
        const task = ((body as any).data ?? {}) as Record<string, unknown>;
        const status = String(task.status ?? '').toLowerCase();
        if (terminal.has(status)) {
          if (status === 'completed') {
            resultData = ((task as any).resultData ?? {}) as Record<string, unknown>;
            return 'completed';
          }
          return `terminal:${status}:${JSON.stringify(task).slice(0, 800)}`;
        }
        return status || 'pending';
      },
      {
        timeout: 180_000,
        intervals: [1000, 1500, 2000, 3000],
        message: `async task ${taskCode} should complete`,
      },
    )
    .toBe('completed');

  return resultData;
}

export async function executeCommand(
  page: Page,
  commandCode: string,
  payload: Record<string, unknown> = {},
  targetRecordId?: string,
  operationType?: string,
): Promise<Record<string, unknown>> {
  const data: Record<string, unknown> = { payload };
  if (targetRecordId) {
    // deployed builds resolve the target via targetRecordPid; keep both for compatibility
    data.targetRecordId = targetRecordId;
    data.targetRecordPid = targetRecordId;
  }
  if (operationType) data.operationType = operationType;
  const resp = await page.request.post(`/api/meta/commands/execute/${commandCode}`, {
    data,
    timeout: 30_000,
  });
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `${commandCode} HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  expect(String((body as any).code), `${commandCode} should return code=0`).toBe('0');
  const commandData = ((body as any).data?.data ?? {}) as Record<string, unknown>;
  if (commandData.async === true && typeof commandData.taskCode === 'string') {
    return pollAsyncTaskResult(page, commandData.taskCode);
  }
  // some handlers return recordPid instead of recordId — normalize so callers can rely on recordId
  if (commandData.recordId === undefined && commandData.recordPid !== undefined) {
    commandData.recordId = commandData.recordPid;
  }
  return commandData;
}

export async function dynamicCreate(
  page: Page,
  model: string,
  data: Record<string, unknown>,
  rows: CreatedRows['rows'],
): Promise<string> {
  const resp = await page.request.post(`/api/dynamic/${model}/create`, {
    data,
    timeout: 15_000,
  });
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `${model} create HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  const record = ((body as any).data?.data ?? (body as any).data ?? body) as Record<
    string,
    unknown
  >;
  const pid = String(record.pid ?? record.recordId ?? record.id ?? '');
  expect(pid, `${model} create should return pid`).toBeTruthy();
  rows.push({ model, pid });
  return pid;
}

function extractRecords(body: unknown): Record<string, unknown>[] {
  const root = body as any;
  const data = root?.data?.data ?? root?.data ?? root;
  if (Array.isArray(data)) return data as Record<string, unknown>[];
  if (Array.isArray(data?.records)) return data.records as Record<string, unknown>[];
  if (Array.isArray(data?.data)) return data.data as Record<string, unknown>[];
  if (Array.isArray(data?.list)) return data.list as Record<string, unknown>[];
  if (Array.isArray(data?.items)) return data.items as Record<string, unknown>[];
  return [];
}

export async function readDynamicRecord(
  page: Page,
  model: string,
  pid: string,
): Promise<Record<string, unknown>> {
  const resp = await page.request.get(`/api/dynamic/${model}/${pid}`, { timeout: 15_000 });
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `${model}/${pid} HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  const record = ((body as any).data?.data ?? (body as any).data ?? body) as Record<
    string,
    unknown
  >;
  expect(record?.pid ?? record?.id, `${model}/${pid} should return a record`).toBeTruthy();
  return record;
}

export async function queryDynamicRecords(
  page: Page,
  model: string,
  filters: DynamicFilter[],
  options: { pageSize?: number; timeout?: number } = {},
): Promise<Record<string, unknown>[]> {
  const filtersParam = encodeURIComponent(JSON.stringify(filters));
  const pageSize = options.pageSize ?? 50;
  const resp = await page.request.get(
    `/api/dynamic/${model}/list?pageNum=1&pageSize=${pageSize}&filters=${filtersParam}`,
    { timeout: options.timeout ?? 15_000 },
  );
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `${model} list HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  return extractRecords(body);
}

export async function queryNamedDataSourceRecords(
  page: Page,
  queryCode: string,
  params: Record<string, unknown>,
): Promise<Record<string, unknown>[]> {
  const search = new URLSearchParams({
    datasourceId: `nq:${queryCode}`,
    valueField: 'pid',
    labelField: 'name',
    format: 'records',
  });
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) search.set(key, String(value));
  }

  const resp = await page.request.get(`/api/datasource/list?${search.toString()}`, {
    timeout: 15_000,
  });
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `${queryCode} datasource HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  expect(String((body as any).code), `${queryCode} datasource should return code=0`).toBe('0');
  return extractRecords(body);
}

export async function cleanupRows(page: Page, created: CreatedRows): Promise<void> {
  for (const row of [...created.rows].reverse()) {
    await page.request.delete(`/api/dynamic/${row.model}/${row.pid}`).catch(() => {});
  }
  if (created.quoteId) {
    await page.request.delete(`/api/dynamic/qo_quote_common/${created.quoteId}`).catch(() => {});
  }
}

async function fetchTenantAdminRole(page: Page): Promise<Record<string, unknown>> {
  const resp = await page.request.get('/api/roles?keyword=tenant_admin&pageNum=1&pageSize=50', {
    timeout: 15_000,
  });
  const body = await resp.json().catch(() => ({}));
  expect(resp.ok(), `tenant_admin role lookup HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`).toBe(true);
  const roles = Array.isArray((body as any).data?.records) ? (body as any).data.records : [];
  const role = roles.find((item: Record<string, unknown>) => item.code === 'tenant_admin');
  expect(role, `tenant_admin role should exist: ${JSON.stringify(body).slice(0, 800)}`).toBeTruthy();
  return role as Record<string, unknown>;
}

async function fetchModelPermissionPids(page: Page, modelCodes: string[]): Promise<string[]> {
  const permissions: Array<Record<string, unknown>> = [];
  for (const resourceType of ['model', 'MODEL']) {
    const permissionsResp = await page.request.get(
      `/api/permissions/resource-type/${resourceType}`,
      { timeout: 15_000 },
    );
    const permissionsBody = await permissionsResp.json().catch(() => ({}));
    expect(
      permissionsResp.ok(),
      `${resourceType} permission list HTTP ${permissionsResp.status()}: ${JSON.stringify(permissionsBody).slice(0, 500)}`,
    ).toBe(true);
    if (Array.isArray((permissionsBody as any).data)) {
      permissions.push(...((permissionsBody as any).data as Array<Record<string, unknown>>));
    }
  }
  // Plugin-imported model.<m>.<action> permissions can carry a blank resource_type
  // (permissions.json "type":"API" since plugins R2), so the resource-type scan misses them
  // and a blind create would 422 "already exists". Merge the full permission tree by code.
  const treeResp = await page.request.get('/api/permissions/tree', { timeout: 15_000 });
  const treeBody = await treeResp.json().catch(() => ({}));
  if (treeResp.ok()) {
    const flatten = (node: unknown): void => {
      if (Array.isArray(node)) {
        node.forEach(flatten);
        return;
      }
      if (node && typeof node === 'object') {
        const record = node as Record<string, unknown>;
        if (record.code && record.pid) permissions.push(record);
        Object.values(record).forEach((value) => {
          if (value && typeof value === 'object') flatten(value);
        });
      }
    };
    flatten((treeBody as any).data);
  }
  const byCode = new Map(
    permissions
      .map((permission) => [
        String(permission.code ?? ''),
        String(permission.pid ?? ''),
      ] as const)
      .filter(([code, pid]) => code && pid),
  );

  const out = new Set<string>();
  for (const modelCode of modelCodes) {
    for (const action of MODEL_FIXTURE_ACTIONS) {
      const code = `model.${modelCode}.${action}`;
      let pid = byCode.get(code);
      if (!pid) {
        const createResp = await page.request.post('/api/permissions', {
          data: {
            code,
            name: `E2E ${modelCode} ${action}`,
            description: `E2E fixture permission for ${modelCode}.${action}`,
            resourceType: 'model',
            resourceCode: modelCode,
            action,
            source: 'e2e',
            sourceRef: 'quoteops-bom-workbench-golden',
          },
          timeout: 15_000,
        });
        const createBody = await createResp.json().catch(() => ({}));
        expect(
          createResp.ok(),
          `${code} permission create HTTP ${createResp.status()}: ${JSON.stringify(createBody).slice(0, 500)}`,
        ).toBe(true);
        pid = String((createBody as any).data?.pid ?? '');
        expect(pid, `${code} created permission should expose pid`).toBeTruthy();
        byCode.set(code, pid);
      }
      out.add(pid);
    }
  }
  return [...out];
}

export async function ensureTenantAdminModelPermissions(
  page: Page,
  modelCodes: string[] = BOM_INTERNAL_FIXTURE_MODELS,
): Promise<void> {
  const role = await fetchTenantAdminRole(page);
  const rolePid = String(role.pid ?? '');
  expect(rolePid, 'tenant_admin role should expose pid').toBeTruthy();

  const currentResp = await page.request.get(`/api/roles/${encodeURIComponent(rolePid)}/permissions`, {
    timeout: 15_000,
  });
  const currentBody = await currentResp.json().catch(() => ({}));
  expect(
    currentResp.ok(),
    `tenant_admin permission lookup HTTP ${currentResp.status()}: ${JSON.stringify(currentBody).slice(0, 500)}`,
  ).toBe(true);
  const currentPids = Array.isArray((currentBody as any).data)
    ? (currentBody as any).data.map(String)
    : [];
  const currentSet = new Set(currentPids);
  const neededPids = await fetchModelPermissionPids(page, modelCodes);
  const missing = neededPids.filter((pid) => !currentSet.has(pid));
  if (missing.length === 0) return;

  const assignResp = await page.request.post(`/api/roles/${encodeURIComponent(rolePid)}/permissions`, {
    data: [...currentPids, ...missing],
    timeout: 20_000,
  });
  const assignBody = await assignResp.json().catch(() => ({}));
  expect(
    assignResp.ok(),
    `tenant_admin permission assignment HTTP ${assignResp.status()}: ${JSON.stringify(assignBody).slice(0, 500)}`,
  ).toBe(true);
}

export function createCorrectedBomWorkbook(filePath: string): string {
  mkdirSync(path.dirname(filePath), { recursive: true });
  const workbook = XLSXUtils.book_new();
  const worksheet = XLSXUtils.aoa_to_sheet([
    [
      'MPN',
      'Description',
      'RefDes',
      'Qty',
      'Unit',
      'Package',
      'SMT Points',
      'THT Points',
      'Pin Count',
      'Hole Count',
      'Positioning Pin Count',
      'Function Pin Count',
    ],
    ['RC0603FR-0710KL', '10K resistor', 'R1,R2', 7600, 'pcs', '0603', 2, 0, 2, 0, 0, 2],
    ['STM32F103C8T6', 'MCU', 'U1', 200, 'pcs', 'LQFP48', 48, 0, 48, 0, 0, 48],
    ['', 'missing mpn row', 'C1', 10, 'pcs', '0603', 1, 0, 2, 0, 0, 2],
  ]);
  XLSXUtils.book_append_sheet(workbook, worksheet, 'Corrected BOM');
  const bytes = write(workbook, { bookType: 'xlsx', type: 'buffer' });
  writeFileSync(filePath, bytes);
  return filePath;
}

async function seedQuoteScaffold(
  page: Page,
  marker: string,
  lines: QuoteLineSeed[],
  factoryClass = 'consumer',
): Promise<CreatedRows> {
  const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
  const quoteCode = `QO-E2E-${marker}-${suffix}`;
  const created: CreatedRows = { quoteId: '', quoteCode, rows: [] };

  try {
    const accountResult = await executeCommand(
      page,
      'crm:create_account',
      {
        crm_acc_name: `E2E ${marker} Customer ${suffix}`,
        crm_acc_industry: 'electronics',
        crm_acc_rating: 'A',
      },
      undefined,
      'create',
    );
    const accountId = String(accountResult.recordId ?? accountResult.pid ?? accountResult.id ?? '');
    expect(accountId, 'crm:create_account should return account id').toBeTruthy();
    created.rows.push({ model: 'crm_account_common', pid: accountId });

    const projectId = await dynamicCreate(
      page,
      'req_requirement_set_pcba_bom',
      {
        bom_project_name: `E2E ${marker} Project ${suffix}`,
        bom_project_customer_id: accountId,
        bom_project_quality_level: 'industrial',
        bom_pcba_code: `PCBA-${marker}-${suffix}`,
        bom_project_remark: `Seeded by QuoteOps ${marker} E2E`,
      },
      created.rows,
    );

    const customerRequestId = await dynamicCreate(
      page,
      'crm_customer_request_common',
      {
        crm_cr_code: `CR-E2E-${marker}-${suffix}`,
        crm_cr_title: `E2E ${marker} request ${suffix}`,
        crm_cr_account_id: accountId,
        crm_cr_type: 'pcba_quote',
        crm_cr_status: 'draft',
        crm_cr_priority: 'normal',
        crm_cr_source_channel: `quote_${marker.toLowerCase()}_e2e`,
      },
      created.rows,
    );
    let pcbaRfqId = await dynamicCreate(
      page,
      'crm_customer_request_pcba_rfq',
      {
        crm_crq_code: `PCBA-RFQ-E2E-${marker}-${suffix}`,
        crm_customer_request_id: customerRequestId,
        crm_crq_product_model: `E2E-BOARD-${marker}-${suffix}`,
        crm_crq_board_count: 3,
        crm_crq_board_layer: 4,
        crm_crq_pcba_qty: 3,
        crm_crq_assembly_type: 'SMT',
        crm_crq_delivery_class: 'standard',
        crm_crq_dfm_status: 'pending',
        crm_crq_bom_status: 'pending',
      },
      created.rows,
    );
    const quoteId = await dynamicCreate(
      page,
      'qo_quote_common',
      {
        qo_quote_customer: `E2E ${marker} Customer ${suffix}`,
        qo_quote_code: quoteCode,
        qo_quote_status: 'draft',
        qo_quote_version_no: 1,
        qo_quote_crm_account_id: accountId,
        qo_quote_project_id: projectId,
        qo_quote_customer_request_id: customerRequestId,
        qo_quote_tax_rate: 0.13,
        qo_quote_factory_class: factoryClass,
        qo_quote_industry: 'pcba',
        // required since the corrected-BOM-at-create contract (#1107); dynamic insert runs
        // no import side effect, so a fixture file id (same pattern as the rsa rows) is enough
        corrected_bom_file: `e2e-corrected-bom-${suffix}`,
      },
      created.rows,
    );
    created.quoteId = quoteId;

    await dynamicCreate(
      page,
      'qo_rfq_source_attachment_common',
      {
        qo_rsa_rfq_id: pcbaRfqId,
        qo_rsa_type: 'raw_bom',
        qo_rsa_filename: `bom-${suffix}.xlsx`,
        qo_rsa_file_id: `e2e-raw-bom-${suffix}`,
        qo_rsa_version_no: 1,
        qo_rsa_parse_status: 'parsed',
        qo_rsa_validation_status: 'passed',
        qo_rsa_uploaded_at: new Date().toISOString(),
      },
      created.rows,
    );
    await dynamicCreate(
      page,
      'qo_rfq_source_attachment_common',
      {
        qo_rsa_rfq_id: pcbaRfqId,
        qo_rsa_type: 'gerber_package',
        qo_rsa_filename: `gerber-${suffix}.zip`,
        qo_rsa_file_id: `e2e-gerber-${suffix}`,
        qo_rsa_version_no: 1,
        qo_rsa_parse_status: 'parsed',
        qo_rsa_validation_status: 'passed',
        qo_rsa_uploaded_at: new Date().toISOString(),
      },
      created.rows,
    );

    for (const line of lines) {
      await dynamicCreate(
        page,
        'qo_quote_line_common',
        {
          qo_ql_quote_id: quoteId,
          qo_ql_item_type: line.itemType ?? 'component',
          qo_ql_source_ref: line.sourceRef,
          qo_ql_source_row_no: line.sourceRowNo,
          qo_ql_description: line.description,
          qo_ql_refdes: line.refdes,
          qo_ql_mpn: line.mpn,
          qo_ql_package: line.packageName,
          qo_ql_qty: line.qty,
          qo_ql_unit: 'PCS',
          qo_ql_unit_cost: line.unitCost ?? 0,
          qo_ql_line_cost: line.lineCost ?? 0,
          qo_ql_line_price: line.linePrice ?? 0,
          qo_ql_smt_points: line.smtPoints,
          qo_ql_tht_points: line.thtPoints,
          ...(line.boardWidthMm !== undefined ? { qo_ql_board_width_mm: line.boardWidthMm } : {}),
          ...(line.boardHeightMm !== undefined
            ? { qo_ql_board_height_mm: line.boardHeightMm }
            : {}),
          ...(line.boardAreaMm2 !== undefined ? { qo_ql_board_area_mm2: line.boardAreaMm2 } : {}),
          ...(line.gerberParseStatus ? { qo_ql_gerber_parse_status: line.gerberParseStatus } : {}),
          ...(line.gerberValidationStatus
            ? { qo_ql_gerber_validation_status: line.gerberValidationStatus }
            : {}),
          ...(line.gerberValidationMessages
            ? { qo_ql_gerber_validation_messages: line.gerberValidationMessages }
            : {}),
          ...(line.gerberInspection ? { qo_ql_gerber_inspection: line.gerberInspection } : {}),
          qo_ql_risk: 'ok',
          qo_ql_validation_status: 'confirmed',
        },
        created.rows,
      );
    }

    return created;
  } catch (error) {
    await cleanupRows(page, created);
    throw error;
  }
}

export async function seedQuoteForCorrectedBomUpload(page: Page): Promise<CreatedRows> {
  return seedQuoteScaffold(page, 'CBOM', [], 'consumer');
}

export async function seedBomWorkbench(page: Page): Promise<BomWorkbenchSeed> {
  await ensureTenantAdminModelPermissions(page);
  const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
  const marker = `E2E-BOM-${suffix}`;
  const created = {
    quoteId: '',
    quoteCode: '',
    rows: [],
    projectId: '',
    taskId: '',
    standardLineId: '',
    rawLineId: '',
    canonicalLineId: '',
    matchResultId: '',
    primaryEvidenceId: '',
    secondaryEvidenceId: '',
    exportRevisionId: '',
    candidateCode: `E2E-R-10K-A-${suffix}`,
    marker,
  } as BomWorkbenchSeed;

  try {
    const accountResult = await executeCommand(
      page,
      'crm:create_account',
      {
        crm_acc_name: `${marker} customer`,
        crm_acc_industry: 'electronics',
        crm_acc_rating: 'A',
      },
      undefined,
      'create',
    );
    const accountId = String(accountResult.recordId ?? accountResult.pid ?? accountResult.id ?? '');
    expect(accountId, 'crm:create_account should return account id').toBeTruthy();
    created.rows.push({ model: 'crm_account_common', pid: accountId });

    created.projectId = await dynamicCreate(
      page,
      'req_requirement_set_pcba_bom',
      {
        bom_project_name: `${marker} project`,
        bom_project_customer_id: accountId,
        bom_project_quality_level: 'industrial',
        bom_pcba_code: `PCBA-${suffix}`,
        bom_project_remark: 'Seeded by QuoteOps BOM workbench golden E2E',
      },
      created.rows,
    );

    created.taskId = await dynamicCreate(
      page,
      'bom_conversion_task_pcba',
      {
        bom_task_no: `TASK-${suffix}`,
        bom_task_customer_id: accountId,
        bom_task_project_id: created.projectId,
        bom_task_source_package: 'quoteops-e2e',
        bom_task_source_model: 'req_requirement_set_pcba_bom',
        bom_task_source_id: created.projectId,
        bom_task_raw_file_id: `raw-${suffix}`,
        bom_task_raw_filename: `${marker}-raw.xlsx`,
        bom_task_status: 'completed',
        bom_task_completed_at: new Date().toISOString(),
        bom_task_total_rows: 2,
        bom_task_valid_rows: 2,
        bom_task_green_count: 1,
        bom_task_yellow_count: 1,
        bom_task_red_count: 0,
        bom_task_reason_breakdown: JSON.stringify({ match_multi_candidate: 1 }),
        bom_task_export_file_id: `export-${suffix}-r1`,
        bom_task_export_filename: `${marker}-standard-bom-r1.xlsx`,
        bom_task_edited_after_completion: false,
        bom_task_edit_count: 0,
      },
      created.rows,
    );

    created.rawLineId = await dynamicCreate(
      page,
      'bom_raw_line_pcba',
      {
        bom_raw_task_id: created.taskId,
        bom_raw_row_no: 1,
        bom_raw_material_name: '10K resistor raw',
        bom_raw_spec: '10K 1% 0603',
        bom_raw_package: '0603',
        bom_raw_mpn: 'RC0603FR-0710KL',
        bom_raw_refdes: 'R1,R2',
        bom_raw_qty: '2',
        bom_raw_extra_columns_json: JSON.stringify({
          __parse_evidence: {
            profileCode: 'E2E_PROFILE',
            composition: { matchRule: 'mpn+spec' },
            llm: { confidence: 0, decision: { reason: 'not invoked in deterministic E2E' } },
          },
        }),
      },
      created.rows,
    );

    created.standardLineId = await dynamicCreate(
      page,
      'bom_standard_line_pcba',
      {
        bom_std_task_id: created.taskId,
        bom_std_row_no: 1,
        bom_std_raw_row_no: 1,
        bom_std_category: 'resistor',
        bom_std_material_code: '',
        bom_std_material_name: '10K resistor canonical',
        bom_std_spec: '10K 1% 0603',
        bom_std_package: '0603',
        bom_std_brand: 'Yageo',
        bom_std_mpn: 'RC0603FR-0710KL',
        bom_std_refdes: 'R1,R2',
        bom_std_qty: 2,
        bom_std_unit: 'PCS',
        bom_std_reason_code: 'match_multi_candidate',
        bom_std_candidate_codes: `${created.candidateCode},E2E-R-10K-B-${suffix}`,
        bom_std_manual_confirmed: false,
        bom_std_raw_hash: `raw-hash-${suffix}-1`,
      },
      created.rows,
    );

    const directLineId = await dynamicCreate(
      page,
      'bom_standard_line_pcba',
      {
        bom_std_task_id: created.taskId,
        bom_std_row_no: 2,
        bom_std_raw_row_no: 2,
        bom_std_category: 'ic',
        bom_std_material_code: `E2E-U1-${suffix}`,
        bom_std_material_name: 'MCU direct copy',
        bom_std_spec: 'LQFP48',
        bom_std_package: 'LQFP48',
        bom_std_mpn: 'STM32F103C8T6',
        bom_std_refdes: 'U1',
        bom_std_qty: 1,
        bom_std_unit: 'PCS',
        bom_std_reason_code: 'direct_copy',
        bom_std_manual_confirmed: false,
        bom_std_raw_hash: `raw-hash-${suffix}-2`,
      },
      created.rows,
    );

    created.matchResultId = await dynamicCreate(
      page,
      'bom_match_result_pcba',
      {
        bom_mr_task_id: created.taskId,
        bom_mr_std_item_id: created.standardLineId,
        bom_mr_status_color: 'yellow',
        bom_mr_reason: '同规格存在多个候选物料，需人工确认',
        bom_mr_match_source: 'item_master',
      },
      created.rows,
    );

    await dynamicCreate(
      page,
      'bom_match_result_pcba',
      {
        bom_mr_task_id: created.taskId,
        bom_mr_std_item_id: directLineId,
        bom_mr_status_color: 'green',
        bom_mr_reason: '100% 直接复制',
        bom_mr_match_source: 'direct_copy',
      },
      created.rows,
    );

    created.primaryEvidenceId = await dynamicCreate(
      page,
      'bom_match_evidence',
      {
        bom_me_task_id: created.taskId,
        bom_me_canonical_line_id: created.standardLineId,
        bom_me_material_code: created.candidateCode,
        bom_me_candidate_source: 'item_master',
        bom_me_status_color: 'yellow',
        bom_me_score: 0.96,
        bom_me_rank: 1,
        bom_me_reason_code: 'match_multi_candidate',
        bom_me_evidence_json: JSON.stringify({
          source: 'item_master',
          matchSource: 'mpn+spec',
          spec: '10K 1% 0603',
        }),
        bom_me_conflict_json: JSON.stringify({ brand: 'multi-brand same spec' }),
        bom_me_candidate_snapshot_json: JSON.stringify({
          materialName: '10K resistor candidate A',
          specModel: '10K 1% 0603',
          packageCode: '0603',
          brand: 'Yageo',
          mpn: 'RC0603FR-0710KL',
          attributes: { resistance: '10K', tolerance_pct: 0.01 },
        }),
      },
      created.rows,
    );

    created.secondaryEvidenceId = await dynamicCreate(
      page,
      'bom_match_evidence',
      {
        bom_me_task_id: created.taskId,
        bom_me_canonical_line_id: created.standardLineId,
        bom_me_material_code: `E2E-R-10K-B-${suffix}`,
        bom_me_candidate_source: 'item_master',
        bom_me_status_color: 'yellow',
        bom_me_score: 0.91,
        bom_me_rank: 2,
        bom_me_reason_code: 'match_multi_candidate',
        bom_me_evidence_json: JSON.stringify({
          source: 'item_master',
          matchSource: 'spec',
          spec: '10K 1% 0603',
        }),
        bom_me_candidate_snapshot_json: JSON.stringify({
          materialName: '10K resistor candidate B',
          specModel: '10K 1% 0603',
          packageCode: '0603',
          brand: 'UniOhm',
          mpn: '0603WAF1002T5E',
          attributes: { resistance: '10K', tolerance_pct: 0.01 },
        }),
      },
      created.rows,
    );

    created.exportRevisionId = await dynamicCreate(
      page,
      'bom_export_revision',
      {
        bom_er_task_id: created.taskId,
        bom_er_revision_no: 1,
        bom_er_source_state_hash: `state-${suffix}-r1`,
        bom_er_source_decision_version: 0,
        bom_er_file_id: `export-${suffix}-r1`,
        bom_er_filename: `${marker}-standard-bom-r1.xlsx`,
        bom_er_generated_by: 'e2e',
        bom_er_generated_at: new Date().toISOString(),
        bom_er_green_count: 1,
        bom_er_yellow_count: 1,
        bom_er_red_count: 0,
        bom_er_status: 'current',
      },
      created.rows,
    );

    return created;
  } catch (error) {
    await cleanupRows(page, created);
    throw error;
  }
}

export async function seedDownloadableQuote(page: Page): Promise<CreatedRows> {
  const created = await seedQuoteScaffold(page, 'XLSX', [
    {
      sourceRef: 'BOM-XLSX-1',
      sourceRowNo: 2,
      description: 'STM32F103C8T6 MCU',
      refdes: 'U1',
      mpn: 'STM32F103C8T6',
      packageName: 'LQFP48',
      qty: 3,
      unitCost: 1.25,
      lineCost: 3.75,
      linePrice: 5,
      smtPoints: 2,
      thtPoints: 0,
    },
  ]);
  try {
    await executeCommand(
      page,
      'qo_quote_common:compute_process_fee',
      {},
      created.quoteId,
      'update',
    );
    await executeCommand(
      page,
      'qo_quote_common:override_process_fee',
      {
        amount: 1.2,
        reason: 'E2E manual confirmation for generated Excel download',
      },
      created.quoteId,
      'update',
    );
    await executeCommand(page, 'qo_quote_common:rollup_cost', {}, created.quoteId, 'update');
    return created;
  } catch (error) {
    await cleanupRows(page, created);
    throw error;
  }
}

export async function seedProcessFeeReviewQuote(page: Page): Promise<CreatedRows> {
  const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
  const created = await seedQuoteScaffold(page, 'PFR', [
    {
      sourceRef: 'BOM-PFR-UNMATCHED',
      sourceRowNo: 2,
      description: 'Unmatched process-fee package',
      refdes: 'U9',
      mpn: `E2E-UNMATCHED-${suffix}`,
      packageName: `NO_RULE_PKG_${suffix}`,
      qty: 3,
      unitCost: 0.5,
      lineCost: 1.5,
      linePrice: 2,
      smtPoints: 2,
      thtPoints: 0,
    },
    {
      sourceRef: 'BOM-PFR-MIXED',
      sourceRowNo: 3,
      description: 'Mixed SMT and DIP row requiring manual review',
      refdes: 'U10,J10',
      mpn: `E2E-MIXED-${suffix}`,
      packageName: 'MIXED-PKG',
      qty: 2,
      unitCost: 0.75,
      lineCost: 1.5,
      linePrice: 2.5,
      smtPoints: 1,
      thtPoints: 1,
    },
  ]);

  try {
    await executeCommand(
      page,
      'qo_quote_common:compute_process_fee',
      {},
      created.quoteId,
      'update',
    );
    return created;
  } catch (error) {
    await cleanupRows(page, created);
    throw error;
  }
}

export async function seedBomPriceManualReviewQuote(page: Page): Promise<BomPriceManualReviewSeed> {
  const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
  const mpn = `E2E-MANUAL-${suffix}`;
  const created = (await seedQuoteScaffold(page, 'BPM', [
    {
      sourceRef: `BOM-BPM-MANUAL-${suffix}`,
      sourceRowNo: 2,
      description: 'Manual price E2E resistor',
      refdes: 'R10',
      mpn,
      packageName: '0603',
      qty: 10,
      unitCost: 0,
      lineCost: 0,
      linePrice: 0,
      smtPoints: 1,
      thtPoints: 0,
    },
  ])) as BomPriceManualReviewSeed;

  try {
    const lineId = created.rows.find((row) => row.model === 'qo_quote_line_common')?.pid ?? '';
    expect(lineId, 'BOM price manual review seed should create one quote line').toBeTruthy();
    created.lineId = lineId;
    created.mpn = mpn;

    created.suggestedEvidenceId = await dynamicCreate(
      page,
      'qo_price_evidence_common',
      {
        qo_pe_quote_line_id: lineId,
        qo_pe_part_no: mpn,
        qo_pe_source: 'deepseek_llm',
        qo_pe_source_ref: `e2e-deepseek-${suffix}`,
        qo_pe_supplier_name: 'DeepSeek AI',
        qo_pe_unit_price: 1.1111,
        qo_pe_currency: 'CNY',
        qo_pe_moq: 1,
        qo_pe_mpq: 1,
        qo_pe_confidence: 0.42,
        qo_pe_valid_until: '2030-12-31',
        qo_pe_status: 'suggested',
        qo_pe_snapshot: {
          source: 'deepseek_llm',
          suggestion: 'E2E AI candidate price',
          queryPartNo: mpn,
        },
      },
      created.rows,
    );

    created.failedEvidenceId = await dynamicCreate(
      page,
      'qo_price_evidence_common',
      {
        qo_pe_quote_line_id: lineId,
        qo_pe_part_no: mpn,
        qo_pe_source: 'kingdee_purchase_history',
        qo_pe_source_ref: `e2e-kingdee-not-found-${suffix}`,
        qo_pe_supplier_name: 'Kingdee history',
        qo_pe_currency: 'CNY',
        qo_pe_confidence: 0,
        qo_pe_status: 'not_found',
        qo_pe_override_reason: 'E2E historical price missing',
        qo_pe_snapshot: {
          source: 'kingdee_purchase_history',
          failureCode: 'price_not_found',
          queryPartNo: mpn,
        },
      },
      created.rows,
    );

    return created;
  } catch (error) {
    await cleanupRows(page, created);
    throw error;
  }
}

export async function seedGerberRuntimeQuote(page: Page): Promise<CreatedRows> {
  const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
  return seedQuoteScaffold(page, 'GERBER', [
    {
      sourceRef: `GERBER-E2E-${suffix}`,
      sourceRowNo: 2,
      description: 'E2E Gerber runtime board',
      refdes: 'C1,J1',
      mpn: `E2E-GERBER-${suffix}`,
      packageName: 'E2E-GERBER-PKG',
      qty: 1,
      unitCost: 0.5,
      lineCost: 0.5,
      linePrice: 1,
      smtPoints: 2,
      thtPoints: 1,
      boardWidthMm: 42,
      boardHeightMm: 18,
      boardAreaMm2: 756,
      gerberParseStatus: 'parsed',
      gerberValidationStatus: 'warning',
      gerberValidationMessages: ['E2E_ALIGNMENT_WARNING'],
      gerberInspection: {
        project: {
          code: 'E2E-GERBER-RUNTIME',
          name: 'Dynamic line persisted Gerber inspection',
        },
        board: {
          xMinMm: 0,
          yMinMm: 0,
          xMaxMm: 42,
          yMaxMm: 18,
          widthMm: 42,
          heightMm: 18,
        },
        boardSvgUrls: {
          top: `/${GERBER_RUNTIME_TOP_FILE_ID}.svg`,
          bottom: `/${GERBER_RUNTIME_BOTTOM_FILE_ID}.svg`,
        },
        summary: {
          bomRefCount: 2,
          cplRefCount: 2,
          smdCount: 2,
          thtCount: 1,
          errorCount: 0,
          warningCount: 1,
        },
        layerManifest: [
          {
            filename: 'E2E-TopLayer.GTL',
            role: 'top_copper',
            side: 'top',
            kind: 'gerber',
            flashCount: 2,
          },
          {
            filename: 'E2E-BottomLayer.GBL',
            role: 'bottom_copper',
            side: 'bottom',
            kind: 'gerber',
            flashCount: 1,
          },
        ],
        drillFiles: [{ filename: 'E2E-PTH.DRL', plated: true, hitCount: 1 }],
        issues: [
          {
            severity: 'warning',
            code: 'E2E_ALIGNMENT_WARNING',
            refdes: 'J1',
            message: 'E2E warning generated from persisted inspection JSON.',
          },
        ],
        components: [
          {
            refdes: 'C1',
            footprint: 'C0603',
            xMm: 10,
            yMm: 6,
            side: 'top',
            smd: true,
            pins: 2,
            rotation: 90,
            issues: [],
            bomItem: { materialName: 'E2E capacitor', process: 'SMT' },
          },
          {
            refdes: 'J1',
            footprint: 'HDR-2P',
            xMm: 28,
            yMm: 12,
            side: 'bottom',
            smd: false,
            pins: 2,
            rotation: 180,
            issues: [
              {
                severity: 'warning',
                code: 'E2E_ALIGNMENT_WARNING',
                refdes: 'J1',
                message: 'E2E bottom-side marker warning.',
              },
            ],
            bomItem: { materialName: 'E2E header', process: 'DIP' },
          },
        ],
      },
    },
  ]);
}
