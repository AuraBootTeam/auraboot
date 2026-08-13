import { expect, test, type APIResponse, type Page, type TestInfo } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5224';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6524';
const RUN = process.env.CRM_DEPARTMENT_RUN_ID || `dept-scope-${Date.now().toString(36)}`;
const EVIDENCE_DIR = process.env.CRM_DEPARTMENT_EVIDENCE_DIR
  || path.join('/tmp', `crm-department-scope-${Date.now()}`);
const ADMIN_EMAIL = process.env.CRM_DEPARTMENT_ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.CRM_DEPARTMENT_ADMIN_PASSWORD || 'Test2026x';
const PERSONA_PASSWORD = process.env.CRM_DEPARTMENT_PERSONA_PASSWORD || 'AuraBoot2026!';
const MANAGER_EMAIL = `${RUN}-manager@e2e.local`;
const CHILD_REP_EMAIL = `${RUN}-child@e2e.local`;
const SOUTH_REP_EMAIL = `${RUN}-south@e2e.local`;
const RUN_SUFFIX = RUN.slice(-8);
const EXPECTED_NAMES = [`华东中心直属商机-${RUN_SUFFIX}`, `华东一部下级商机-${RUN_SUFFIX}`];
const FORBIDDEN_NAME = `华南越权不可见商机-${RUN_SUFFIX}`;
const EXPECTED_MANAGER_COUNT = Number(
  process.env.CRM_DEPARTMENT_EXPECTED_MANAGER_COUNT || EXPECTED_NAMES.length,
);

const ids = {
  eastDepartment: '',
  eastChildDepartment: '',
  southDepartment: '',
  eastPosition: '',
  eastChildPosition: '',
  southPosition: '',
  managerEmployee: '',
  managerMember: '',
  managerUser: '',
  childEmployee: '',
  childMember: '',
  childUser: '',
  southEmployee: '',
  southMember: '',
  southUser: '',
  account: '',
};

let adminJwt = '';
let managerJwt = '';
let childRepJwt = '';
let southRepJwt = '';

function findValue(value: unknown, keys: string[]): unknown {
  if (Array.isArray(value)) {
    for (const child of value) {
      const found = findValue(child, keys);
      if (found !== undefined && found !== null && found !== '') return found;
    }
  } else if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    for (const key of keys) {
      if (record[key] !== undefined && record[key] !== null && record[key] !== '') {
        return record[key];
      }
    }
    for (const child of Object.values(record)) {
      const found = findValue(child, keys);
      if (found !== undefined && found !== null && found !== '') return found;
    }
  }
  return undefined;
}

async function api(pathname: string, jwt: string, init: RequestInit = {}): Promise<any> {
  const headers = new Headers(init.headers);
  headers.set('Authorization', `Bearer ${jwt}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${BE}${pathname}`, { ...init, headers });
  const body = await response.json().catch(() => ({}));
  expect(response.ok, `${pathname}: HTTP ${response.status} ${JSON.stringify(body)}`).toBeTruthy();
  expect(String(body.code), `${pathname}: ${JSON.stringify(body)}`).toBe('0');
  return body.data;
}

async function loginJwt(email: string, password: string): Promise<string> {
  const response = await fetch(`${BE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const body = await response.json();
  expect(response.ok, `${email}: ${JSON.stringify(body)}`).toBeTruthy();
  expect(String(body.code), `${email}: ${JSON.stringify(body)}`).toBe('0');
  const jwt = findValue(body.data, ['jwt']);
  expect(jwt, `${email} must return a JWT`).toBeTruthy();
  return String(jwt);
}

async function executeCreate(
  commandCode: string,
  payload: Record<string, unknown>,
  jwt = adminJwt,
): Promise<string> {
  const data = await api(`/api/meta/commands/execute/${commandCode}`, jwt, {
    method: 'POST',
    body: JSON.stringify({ payload, operationType: 'create' }),
  });
  const pid = findValue(data?.data ?? data, ['recordId', 'recordPid', 'publicRecordId', 'pid']);
  expect(pid, `${commandCode} must return a public record PID`).toBeTruthy();
  return String(pid);
}

function phone(index: number): string {
  return `139${String(Date.now()).slice(-7)}${index}`;
}

async function createEmployee(
  email: string,
  name: string,
  deptPid: string,
  positionPid: string,
  index: number,
): Promise<{ pid: string; memberPid: string; userPid: string }> {
  const employee = await api('/api/org/employees', adminJwt, {
    method: 'POST',
    body: JSON.stringify({ name, email, phone: phone(index), deptPid, positionPid }),
  });
  expect(employee?.pid).toBeTruthy();
  expect(employee?.memberPid).toBeTruthy();
  expect(employee?.userPid).toBeTruthy();
  return employee;
}

async function assignRole(memberPid: string, roleCode: string): Promise<void> {
  await api('/api/user-roles/assign-by-code', adminJwt, {
    method: 'POST',
    body: JSON.stringify({ memberPid, roleCodes: [roleCode] }),
  });
}

function matrixActions(matrix: any): any[] {
  return (matrix?.modules || []).flatMap((module: any) =>
    (module.resources || []).flatMap((resource: any) => resource.actions || []));
}

async function seedJourney(): Promise<void> {
  adminJwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  const roles = await api('/api/roles/all', adminJwt);
  const managerRole = (roles || []).find((role: any) => role.code === 'crm_sales_manager');
  expect(managerRole?.pid, 'CRM sales manager role must be imported').toBeTruthy();
  expect(managerRole?.defaultDataScopeType).toBe('all');
  const matrix = await api(`/api/permissions/matrix/${managerRole.pid}`, adminJwt);
  const opportunityRead = matrixActions(matrix).find(
    (action: any) => action.code === 'model.crm_opportunity_common.read',
  );
  expect(opportunityRead).toEqual(expect.objectContaining({
    granted: true,
    scopeType: 'dept_and_sub',
    mergeStrategy: 'MAX',
  }));

  ids.eastDepartment = await executeCreate('org:create_department', {
    org_dept_name: `${RUN} 华东中心`,
    org_dept_order: 10,
    org_dept_status: 'active',
  });
  ids.eastChildDepartment = await executeCreate('org:create_department', {
    org_dept_name: `${RUN} 华东一部`,
    org_dept_parent_id: ids.eastDepartment,
    org_dept_order: 11,
    org_dept_status: 'active',
  });
  ids.southDepartment = await executeCreate('org:create_department', {
    org_dept_name: `${RUN} 华南中心`,
    org_dept_order: 20,
    org_dept_status: 'active',
  });
  ids.eastPosition = await executeCreate('org:create_position', {
    org_pos_code: `${RUN}-E-MGR`,
    org_pos_name: `${RUN} 华东经理岗`,
    org_pos_dept_id: ids.eastDepartment,
    org_pos_level: 'manager',
    org_pos_status: 'active',
  });
  ids.eastChildPosition = await executeCreate('org:create_position', {
    org_pos_code: `${RUN}-E-REP`,
    org_pos_name: `${RUN} 华东销售岗`,
    org_pos_dept_id: ids.eastChildDepartment,
    org_pos_level: 'staff',
    org_pos_status: 'active',
  });
  ids.southPosition = await executeCreate('org:create_position', {
    org_pos_code: `${RUN}-S-REP`,
    org_pos_name: `${RUN} 华南销售岗`,
    org_pos_dept_id: ids.southDepartment,
    org_pos_level: 'staff',
    org_pos_status: 'active',
  });

  const manager = await createEmployee(
    MANAGER_EMAIL,
    `${RUN} 华东经理`,
    ids.eastDepartment,
    ids.eastPosition,
    1,
  );
  ids.managerEmployee = manager.pid;
  ids.managerMember = manager.memberPid;
  ids.managerUser = manager.userPid;
  await assignRole(ids.managerMember, 'crm_sales_manager');

  const childRep = await createEmployee(
    CHILD_REP_EMAIL,
    `${RUN} 华东一部销售`,
    ids.eastChildDepartment,
    ids.eastChildPosition,
    2,
  );
  ids.childEmployee = childRep.pid;
  ids.childMember = childRep.memberPid;
  ids.childUser = childRep.userPid;
  await assignRole(ids.childMember, 'crm_sales');

  const southRep = await createEmployee(
    SOUTH_REP_EMAIL,
    `${RUN} 华南销售`,
    ids.southDepartment,
    ids.southPosition,
    3,
  );
  ids.southEmployee = southRep.pid;
  ids.southMember = southRep.memberPid;
  ids.southUser = southRep.userPid;
  await assignRole(ids.southMember, 'crm_sales');

  ids.account = await executeCreate('crm:create_account', {
    crm_acc_name: `${RUN} 部门权限测试客户`,
    crm_acc_industry: 'software',
    crm_acc_rating: 'A',
    crm_acc_status: 'active',
  });

  for (const [name, owner, amount] of [
    [EXPECTED_NAMES[0], ids.managerUser, 320000],
    [EXPECTED_NAMES[1], ids.childUser, 180000],
    [FORBIDDEN_NAME, ids.southUser, 260000],
  ] as const) {
    await executeCreate('crm:create_opportunity', {
      crm_opp_name: name,
      crm_opp_account_id: ids.account,
      crm_opp_currency_code: 'CNY',
      crm_opp_expected_amount: amount,
      crm_opp_expected_close_date: '2026-12-31T18:00:00+08:00',
      crm_opp_probability: 45,
      crm_opp_owner: owner,
      crm_opp_forecast_category: 'pipeline',
    });
  }

  managerJwt = await loginJwt(MANAGER_EMAIL, PERSONA_PASSWORD);
  childRepJwt = await loginJwt(CHILD_REP_EMAIL, PERSONA_PASSWORD);
  southRepJwt = await loginJwt(SOUTH_REP_EMAIL, PERSONA_PASSWORD);
}

function departmentResolver(): Record<string, unknown> {
  return { $currentDepartmentOwnerPids: { includeSubDepartments: true } };
}

async function listOpportunities(jwt: string, filters?: Record<string, unknown>[]): Promise<any> {
  const query = new URLSearchParams({ pageNum: '1', pageSize: '50' });
  if (filters) query.set('filters', JSON.stringify(filters));
  return api(`/api/dynamic/crm_opportunity_common/list?${query}`, jwt);
}

function opportunityNames(result: any): string[] {
  return (result.records || []).map((record: any) => String(record.crm_opp_name)).sort();
}

async function uiLogin(page: Page): Promise<void> {
  const response = await page.request.post(`${BASE}/login`, {
    form: {
      email: MANAGER_EMAIL,
      password: PERSONA_PASSWORD,
      remember: 'on',
      redirectTo: '/',
    },
    maxRedirects: 0,
  });
  expect([302, 303]).toContain(response.status());
  await page.goto(`${BASE}/`, { waitUntil: 'load' });
  if (page.url().includes('tenant-selection')) {
    await page.getByRole('button', { name: /进入|选择|Enter|AuraBoot/ }).first().click();
    await page.waitForURL((url) => !url.pathname.includes('tenant-selection'));
  }
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<string> {
  const output = path.join(EVIDENCE_DIR, name);
  mkdirSync(path.dirname(output), { recursive: true });
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  return output;
}

function isDepartmentListResponse(response: APIResponse): boolean {
  if (response.request().method() !== 'GET'
      || !response.url().includes('/api/dynamic/crm_opportunity_common/list')) {
    return false;
  }
  const raw = new URL(response.url()).searchParams.get('filters');
  if (!raw) return false;
  const filters = JSON.parse(raw);
  return filters.some((filter: any) =>
    filter.fieldName === 'crm_opp_owner'
      && filter.operator === 'IN'
      && Array.isArray(filter.values)
      && filter.values.some((value: any) => value?.$currentDepartmentOwnerPids));
}

test('department opportunity scope is authoritative for all personas and follows owner transfer', async ({ page }, testInfo) => {
  test.setTimeout(180_000);
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  await seedJourney();

  const views = await api(
    '/api/views/accessible?modelCode=crm_opportunity_common&pageKey=crm_opportunity_common_list',
    managerJwt,
  );
  const departmentView = (views || []).find((view: any) =>
    (view.viewKey || view.viewConfig?.meta?.viewKey) === 'crm_opportunity_department_table');
  expect(departmentView?.pid).toBeTruthy();
  expect(departmentView?.viewConfig?.filters).toEqual([
    expect.objectContaining({
      fieldCode: 'crm_opp_owner',
      operator: 'in',
      isExpression: true,
      expression: '#currentDepartmentOwners',
    }),
  ]);

  const authoritativeList = await listOpportunities(managerJwt);
  const authoritativeNames = opportunityNames(authoritativeList);
  expect(authoritativeNames).toHaveLength(EXPECTED_MANAGER_COUNT);
  expect(authoritativeNames).toEqual([...EXPECTED_NAMES].sort());
  expect(authoritativeNames).not.toContain(FORBIDDEN_NAME);

  const resolverFilters = [{
    fieldName: 'crm_opp_owner',
    operator: 'IN',
    values: [departmentResolver()],
  }];
  const presetList = await listOpportunities(managerJwt, resolverFilters);
  expect(opportunityNames(presetList)).toEqual([...EXPECTED_NAMES].sort());

  const craftedCrossDepartment = await listOpportunities(managerJwt, [{
    fieldName: 'crm_opp_owner',
    operator: 'IN',
    values: [ids.southUser],
  }]);
  expect(craftedCrossDepartment.total).toBe(0);
  expect(opportunityNames(craftedCrossDepartment)).toEqual([]);

  expect(opportunityNames(await listOpportunities(childRepJwt))).toEqual([EXPECTED_NAMES[1]]);
  expect(opportunityNames(await listOpportunities(southRepJwt))).toEqual([FORBIDDEN_NAME]);

  const analysis = await api('/api/meta/chart-data', managerJwt, {
    method: 'POST',
    body: JSON.stringify({
      type: 'aggregate',
      modelCode: 'crm_opportunity_common',
      dimensions: ['crm_opp_forecast_category'],
      metrics: [{ field: 'pid', aggregation: 'count', alias: 'total' }],
      filters: [{
        field: 'crm_opp_owner',
        operator: 'in',
        value: departmentResolver(),
      }],
      limit: 50,
    }),
  });
  expect(analysis.summary?.total).toBe(EXPECTED_NAMES.length);

  const exported = await api('/api/dynamic/crm_opportunity_common/export', managerJwt, {
    method: 'POST',
    body: JSON.stringify({
      format: 'csv',
      conditions: [{
        field: 'crm_opp_owner',
        operator: 'IN',
        value: [departmentResolver()],
      }],
    }),
  });
  expect(exported.recordCount).toBe(EXPECTED_NAMES.length);
  const csvResponse = await fetch(`${BE}${exported.downloadUrl}`, {
    headers: { Authorization: `Bearer ${managerJwt}` },
  });
  expect(csvResponse.ok).toBeTruthy();
  const csv = await csvResponse.text();
  for (const name of EXPECTED_NAMES) expect(csv).toContain(name);
  expect(csv).not.toContain(FORBIDDEN_NAME);
  const csvPath = path.join(EVIDENCE_DIR, 'department-opportunities.csv');
  writeFileSync(csvPath, csv);
  expect(readFileSync(csvPath, 'utf8')).toBe(csv);

  await uiLogin(page);
  const listResponse = page.waitForResponse(isDepartmentListResponse, { timeout: 20_000 });
  await page.goto(
    `${BASE}/p/crm_opportunity_common?view=${encodeURIComponent(String(departmentView.pid))}`,
    { waitUntil: 'domcontentloaded' },
  );
  expect((await listResponse).ok()).toBeTruthy();
  await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByTestId('view-selector-trigger')).toContainText('部门商机');
  for (const name of EXPECTED_NAMES) {
    await expect(page.locator('tr').filter({ hasText: name })).toBeVisible();
  }
  await expect(page.locator('tr').filter({ hasText: FORBIDDEN_NAME })).toHaveCount(0);
  await expect(page.getByTestId(`quick-filter-view-${departmentView.pid}`)).toContainText('部门商机');
  await expect(page.getByTestId(`quick-filter-view-${departmentView.pid}`)).toHaveAttribute(
    'data-preset-active',
    'true',
  );
  const pageText = await page.locator('main, [role="main"]').first().innerText();
  for (const userPid of [ids.managerUser, ids.childUser, ids.southUser]) {
    expect(pageText).not.toContain(userPid);
  }
  expect(pageText).not.toContain('$currentDepartmentOwnerPids');
  const listShot = await shot(page, testInfo, 'department-opportunities-desktop.png');

  const aggregateResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().includes('/api/meta/chart-data')
      && response.request().postData()?.includes('analysis_value') === true,
  { timeout: 20_000 });
  await page.getByTestId('view-analysis-open').click();
  const resolvedAggregate = await aggregateResponse;
  expect(resolvedAggregate.ok()).toBeTruthy();
  const uiAggregateBody = await resolvedAggregate.json();
  const uiAggregateCount = (uiAggregateBody.data?.rows || []).reduce(
    (sum: number, row: any) => sum + Number(row.analysis_value || 0),
    0,
  );
  expect(uiAggregateCount).toBe(EXPECTED_NAMES.length);
  await expect(page.getByTestId('view-analysis-drawer')).toBeVisible();
  await expect(page.getByTestId('view-analysis-drawer')).toContainText('部门商机');
  const analysisShot = await shot(page, testInfo, 'department-opportunities-analysis.png');

  let transferredNames: string[] = [];
  try {
    await api(`/api/org/employees/${ids.childEmployee}/transfer`, adminJwt, {
      method: 'PUT',
      body: JSON.stringify({
        newDeptPid: ids.southDepartment,
        newPositionPid: ids.southPosition,
      }),
    });
    transferredNames = opportunityNames(await listOpportunities(managerJwt));
    expect(transferredNames).toEqual([EXPECTED_NAMES[0]]);
  } finally {
    await api(`/api/org/employees/${ids.childEmployee}/transfer`, adminJwt, {
      method: 'PUT',
      body: JSON.stringify({
        newDeptPid: ids.eastChildDepartment,
        newPositionPid: ids.eastChildPosition,
      }),
    });
  }
  expect(opportunityNames(await listOpportunities(managerJwt))).toEqual([...EXPECTED_NAMES].sort());

  const coverageRows = [
    ['DS-01', 'plugin role import materializes opportunity read as dept_and_sub/MAX'],
    ['DS-02', 'manager unfiltered list is backend-authoritative for current department and descendants'],
    ['DS-03', 'crafted cross-department owner filter cannot widen manager visibility'],
    ['DS-04', 'sales representatives remain self-scoped in positive and negative departments'],
    ['DS-05', 'saved-view resolver produces the same authorized department record set'],
    ['DS-06', 'analysis aggregation applies resolved department owners'],
    ['DS-07', 'CSV export applies resolved department owners'],
    ['DS-08', 'browser preset renders localized labels and does not leak resolver or user PIDs'],
    ['DS-09', 'owner transfer changes department visibility without changing the opportunity'],
    ['DS-10', 'transfer rollback restores original department visibility'],
  ].map(([id, claim]) => ({ id, claim, status: 'pass', evidence: 'this Playwright run' }));

  writeFileSync(path.join(EVIDENCE_DIR, 'manifest.json'), `${JSON.stringify({
    schemaVersion: 1,
    run: {
      id: RUN,
      runtime: process.env.AURA_RUNTIME_NAME || null,
      baseUrl: BASE,
      backendUrl: BE,
      authenticatedPersonas: [ADMIN_EMAIL, MANAGER_EMAIL, CHILD_REP_EMAIL, SOUTH_REP_EMAIL],
      isolation: 'unique organization, users, and business records created by this run',
      dataMigration: 'not required; development stage',
    },
    summary: { total: coverageRows.length, pass: coverageRows.length, fail: 0, skipped: 0, untested: 0 },
    groups: [{ id: 'department-opportunity-scope', title: 'Department opportunity scope', items: coverageRows }],
    assertions: {
      expectedVisible: EXPECTED_NAMES,
      expectedForbidden: [FORBIDDEN_NAME],
      authoritativeApiCount: authoritativeList.total,
      aggregateCount: analysis.summary?.total,
      exportCount: exported.recordCount,
      afterChildOwnerTransferredToSouth: transferredNames,
    },
    evidence: {
      screenshots: [listShot, analysisShot],
      csv: csvPath,
    },
  }, null, 2)}\n`);
});
