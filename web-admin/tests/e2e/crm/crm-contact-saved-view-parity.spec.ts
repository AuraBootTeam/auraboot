import {
  expect,
  test,
  type Browser,
  type Page,
  type Response,
  type TestInfo,
} from '@playwright/test';
import { Pool } from 'pg';
import { BACKEND_URL, BASE_URL, PG_CONN } from '../../helpers/environments';
const RUN_ID = process.env.CRM_CONTACT_SAVED_VIEW_RUN_ID?.trim() || '';
const ADMIN_EMAIL = process.env.CRM_CONTACT_SAVED_VIEW_ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.CRM_CONTACT_SAVED_VIEW_ADMIN_PASSWORD || 'Test2026x';
const PERSONA_PASSWORD = process.env.CRM_CONTACT_SAVED_VIEW_PERSONA_PASSWORD || 'AuraBoot2026!';

if (!RUN_ID) {
  throw new Error(
    'CRM_CONTACT_SAVED_VIEW_RUN_ID is required; refusing an empty or residue-backed CRM SavedView run',
  );
}

if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]{5,48}$/.test(RUN_ID)) {
  throw new Error(
    `CRM_CONTACT_SAVED_VIEW_RUN_ID must be 6-49 URL-safe characters, received ${JSON.stringify(RUN_ID)}`,
  );
}

const RUN_SUFFIX = RUN_ID.slice(-12);
const MANAGER_EMAIL = `${RUN_ID}-manager@e2e.local`;
const CHILD_REP_EMAIL = `${RUN_ID}-child@e2e.local`;
const SOUTH_REP_EMAIL = `${RUN_ID}-south@e2e.local`;
const CONTACT_NAMES = {
  manager: `华东经理联系人-${RUN_SUFFIX}`,
  child: `华东一部联系人-${RUN_SUFFIX}`,
  south: `华南隔离联系人-${RUN_SUFFIX}`,
} as const;
const PERSONAL_VIEW_NAME = `联系人跟进视图-${RUN_SUFFIX}`;

const ids = {
  eastDepartment: '',
  eastChildDepartment: '',
  southDepartment: '',
  eastPosition: '',
  eastChildPosition: '',
  southPosition: '',
  managerMember: '',
  managerUser: '',
  childMember: '',
  childUser: '',
  southMember: '',
  southUser: '',
  account: '',
  managerContact: '',
  childContact: '',
  southContact: '',
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
  const response = await fetch(`${BACKEND_URL}${pathname}`, { ...init, headers });
  const body = await response.json().catch(() => ({}));
  expect(response.ok, `${pathname}: HTTP ${response.status} ${JSON.stringify(body)}`).toBeTruthy();
  expect(String(body.code), `${pathname}: ${JSON.stringify(body)}`).toBe('0');
  return body.data;
}

async function loginJwt(email: string, password: string): Promise<string> {
  const response = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const body = await response.json().catch(() => ({}));
  expect(response.ok, `${email}: HTTP ${response.status} ${JSON.stringify(body)}`).toBeTruthy();
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
  const runHash = [...RUN_ID].reduce(
    (hash, character) => (hash * 31 + character.charCodeAt(0)) % 10_000_000,
    0,
  );
  return `139${String(runHash).padStart(7, '0')}${index}`;
}

async function createEmployee(
  email: string,
  name: string,
  deptPid: string,
  positionPid: string,
  index: number,
): Promise<{ memberPid: string; userPid: string }> {
  const employee = await api('/api/org/employees', adminJwt, {
    method: 'POST',
    body: JSON.stringify({ name, email, phone: phone(index), deptPid, positionPid }),
  });
  expect(employee?.memberPid, `${email} member pid`).toBeTruthy();
  expect(employee?.userPid, `${email} user pid`).toBeTruthy();
  return employee;
}

async function assignRole(memberPid: string, roleCode: string): Promise<void> {
  await api('/api/user-roles/assign-by-code', adminJwt, {
    method: 'POST',
    body: JSON.stringify({ memberPid, roleCodes: [roleCode] }),
  });
}

async function seedJourney(): Promise<void> {
  adminJwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  ids.eastDepartment = await executeCreate('org:create_department', {
    org_dept_name: `${RUN_ID} 华东中心`,
    org_dept_order: 10,
    org_dept_status: 'active',
  });
  ids.eastChildDepartment = await executeCreate('org:create_department', {
    org_dept_name: `${RUN_ID} 华东一部`,
    org_dept_parent_id: ids.eastDepartment,
    org_dept_order: 11,
    org_dept_status: 'active',
  });
  ids.southDepartment = await executeCreate('org:create_department', {
    org_dept_name: `${RUN_ID} 华南中心`,
    org_dept_order: 20,
    org_dept_status: 'active',
  });
  ids.eastPosition = await executeCreate('org:create_position', {
    org_pos_code: `${RUN_ID}-E-MGR`,
    org_pos_name: `${RUN_ID} 华东经理岗`,
    org_pos_dept_id: ids.eastDepartment,
    org_pos_level: 'manager',
    org_pos_status: 'active',
  });
  ids.eastChildPosition = await executeCreate('org:create_position', {
    org_pos_code: `${RUN_ID}-E-REP`,
    org_pos_name: `${RUN_ID} 华东销售岗`,
    org_pos_dept_id: ids.eastChildDepartment,
    org_pos_level: 'staff',
    org_pos_status: 'active',
  });
  ids.southPosition = await executeCreate('org:create_position', {
    org_pos_code: `${RUN_ID}-S-REP`,
    org_pos_name: `${RUN_ID} 华南销售岗`,
    org_pos_dept_id: ids.southDepartment,
    org_pos_level: 'staff',
    org_pos_status: 'active',
  });

  const manager = await createEmployee(
    MANAGER_EMAIL,
    `${RUN_ID} 华东经理`,
    ids.eastDepartment,
    ids.eastPosition,
    1,
  );
  ids.managerMember = manager.memberPid;
  ids.managerUser = manager.userPid;
  await assignRole(ids.managerMember, 'crm_sales_manager');

  const childRep = await createEmployee(
    CHILD_REP_EMAIL,
    `${RUN_ID} 华东一部销售`,
    ids.eastChildDepartment,
    ids.eastChildPosition,
    2,
  );
  ids.childMember = childRep.memberPid;
  ids.childUser = childRep.userPid;
  await assignRole(ids.childMember, 'crm_sales');

  const southRep = await createEmployee(
    SOUTH_REP_EMAIL,
    `${RUN_ID} 华南销售`,
    ids.southDepartment,
    ids.southPosition,
    3,
  );
  ids.southMember = southRep.memberPid;
  ids.southUser = southRep.userPid;
  await assignRole(ids.southMember, 'crm_sales');

  ids.account = await executeCreate('crm:create_account', {
    crm_acc_name: `${RUN_ID} SavedView 客户`,
    crm_acc_industry: 'software',
    crm_acc_rating: 'A',
    crm_acc_status: 'active',
  });

  ids.managerContact = await executeCreate('crm:create_contact', {
    crm_ct_account_id: ids.account,
    crm_ct_name: CONTACT_NAMES.manager,
    crm_ct_email: `${RUN_ID}-manager-contact@e2e.local`,
    crm_ct_owner: ids.managerUser,
  });
  ids.childContact = await executeCreate('crm:create_contact', {
    crm_ct_account_id: ids.account,
    crm_ct_name: CONTACT_NAMES.child,
    crm_ct_email: `${RUN_ID}-child-contact@e2e.local`,
    crm_ct_owner: ids.childUser,
  });
  ids.southContact = await executeCreate('crm:create_contact', {
    crm_ct_account_id: ids.account,
    crm_ct_name: CONTACT_NAMES.south,
    crm_ct_email: `${RUN_ID}-south-contact@e2e.local`,
    crm_ct_owner: ids.southUser,
  });

  managerJwt = await loginJwt(MANAGER_EMAIL, PERSONA_PASSWORD);
  childRepJwt = await loginJwt(CHILD_REP_EMAIL, PERSONA_PASSWORD);
  southRepJwt = await loginJwt(SOUTH_REP_EMAIL, PERSONA_PASSWORD);
}

function departmentResolver(): Record<string, unknown> {
  return { $currentDepartmentOwnerPids: { includeSubDepartments: true } };
}

async function listContacts(jwt: string, filters?: Record<string, unknown>[]): Promise<any> {
  const query = new URLSearchParams({ pageNum: '1', pageSize: '50' });
  if (filters) query.set('filters', JSON.stringify(filters));
  return api(`/api/dynamic/crm_contact_common/list?${query.toString()}`, jwt);
}

function contactNames(result: any): string[] {
  return (result.records || []).map((record: any) => String(record.crm_ct_name)).sort();
}

async function accessibleViews(jwt: string): Promise<any[]> {
  return api(
    '/api/views/accessible?modelCode=crm_contact_common&pageKey=crm_contact_common_list',
    jwt,
  );
}

async function uiLogin(page: Page, email: string): Promise<void> {
  const response = await page.request.post(`${BASE_URL}/login`, {
    form: { email, password: PERSONA_PASSWORD, remember: 'on', redirectTo: '/' },
    maxRedirects: 0,
  });
  expect([302, 303], `BFF login for ${email}`).toContain(response.status());
  await page.goto(`${BASE_URL}/`, { waitUntil: 'load' });
  if (page.url().includes('tenant-selection')) {
    await page
      .getByRole('button', { name: /进入|选择|Enter|AuraBoot/ })
      .first()
      .click();
    await page.waitForURL((url) => !url.pathname.includes('tenant-selection'));
  }
}

async function openContactsFromMenu(page: Page): Promise<void> {
  await page.goto(`${BASE_URL}/dashboards`, { waitUntil: 'domcontentloaded' });
  const link = page.locator('nav a[href="/p/crm_contact_common"]').first();
  await expect(link).toBeVisible({ timeout: 15_000 });
  const listResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().includes('/api/dynamic/crm_contact_common/list') &&
      response.ok(),
  );
  await link.click();
  expect((await listResponse).ok()).toBe(true);
  await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 20_000 });
}

function isContactListResponse(response: Response): boolean {
  return (
    response.request().method() === 'GET' &&
    response.url().includes('/api/dynamic/crm_contact_common/list')
  );
}

async function selectPreset(page: Page, pid: string): Promise<void> {
  const responsePromise = page.waitForResponse(isContactListResponse, { timeout: 20_000 });
  await page.getByTestId(`quick-filter-view-${pid}`).click();
  const response = await responsePromise;
  expect(response.ok(), `select preset ${pid}: HTTP ${response.status()}`).toBe(true);
  await expect(page.getByTestId(`quick-filter-view-${pid}`)).toHaveAttribute(
    'data-preset-active',
    'true',
  );
}

function contactRow(page: Page, name: string) {
  return page.locator('tbody tr').filter({ hasText: name });
}

async function openManagePanel(page: Page): Promise<void> {
  await page.getByTestId('view-selector-trigger').click();
  await expect(page.getByTestId('view-selector-search')).toBeVisible();
  await page.getByTestId('view-selector-manage').click();
  await expect(page.getByTestId('saved-view-manage-panel')).toBeVisible();
}

async function createPersonalTableView(page: Page): Promise<string> {
  const createResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/views',
    { timeout: 10_000 },
  );
  await page.getByTestId('saved-view-create-personal').click();
  await expect(page.getByTestId('saved-view-quota-status')).toContainText(/个人视图|Personal/);
  await page.getByTestId('saved-view-type-table').click();
  const response = await createResponse;
  expect(response.ok(), `create personal view: HTTP ${response.status()}`).toBe(true);
  const body = await response.json();
  const pid = body.data?.pid ?? body.pid;
  expect(pid, 'UI-created personal SavedView pid').toBeTruthy();
  await expect(page).toHaveURL(new RegExp(`view=${pid}`), { timeout: 10_000 });
  return String(pid);
}

async function screenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const output = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
}

async function newPersonaPage(
  browser: Browser,
  email: string,
): Promise<{ page: Page; close: () => Promise<void> }> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await uiLogin(page, email);
  return { page, close: () => context.close() };
}

test.describe('CRM contact SavedView — Cordys parity slice', () => {
  test.setTimeout(240_000);

  test('my/department presets and personal SavedView persist with owner isolation @critical @golden', async ({
    browser,
  }, testInfo) => {
    await seedJourney();

    const managerViews = await accessibleViews(managerJwt);
    const myView = managerViews.find(
      (view) => (view.viewKey || view.viewConfig?.meta?.viewKey) === 'crm_contact_my_table',
    );
    const departmentView = managerViews.find(
      (view) => (view.viewKey || view.viewConfig?.meta?.viewKey) === 'crm_contact_department_table',
    );
    expect(myView?.pid, 'My Contacts preset must be imported').toBeTruthy();
    expect(departmentView?.pid, 'Department Contacts preset must be imported').toBeTruthy();
    expect(myView.viewConfig?.filters).toEqual([
      expect.objectContaining({
        fieldCode: 'crm_ct_owner',
        operator: 'eq',
        isExpression: true,
        expression: '#currentUser',
      }),
    ]);
    expect(departmentView.viewConfig?.filters).toEqual([
      expect.objectContaining({
        fieldCode: 'crm_ct_owner',
        operator: 'in',
        isExpression: true,
        expression: '#currentDepartmentOwners',
      }),
    ]);

    expect(
      contactNames(
        await listContacts(managerJwt, [
          { fieldName: 'crm_ct_owner', operator: 'EQ', values: [ids.managerUser] },
        ]),
      ),
    ).toEqual([CONTACT_NAMES.manager]);
    expect(
      contactNames(
        await listContacts(managerJwt, [
          { fieldName: 'crm_ct_owner', operator: 'IN', values: [departmentResolver()] },
        ]),
      ),
    ).toEqual([CONTACT_NAMES.child, CONTACT_NAMES.manager].sort());
    expect(contactNames(await listContacts(childRepJwt))).toEqual([CONTACT_NAMES.child]);
    expect(contactNames(await listContacts(southRepJwt))).toEqual([CONTACT_NAMES.south]);

    const manager = await newPersonaPage(browser, MANAGER_EMAIL);
    let personalViewPid = '';
    try {
      await openContactsFromMenu(manager.page);
      await selectPreset(manager.page, String(myView.pid));
      await expect(contactRow(manager.page, CONTACT_NAMES.manager)).toBeVisible();
      await expect(contactRow(manager.page, CONTACT_NAMES.child)).toHaveCount(0);
      await expect(contactRow(manager.page, CONTACT_NAMES.south)).toHaveCount(0);
      await screenshot(manager.page, testInfo, '01-my-contacts');

      await selectPreset(manager.page, String(departmentView.pid));
      await expect(contactRow(manager.page, CONTACT_NAMES.manager)).toBeVisible();
      await expect(contactRow(manager.page, CONTACT_NAMES.child)).toBeVisible();
      await expect(contactRow(manager.page, CONTACT_NAMES.south)).toHaveCount(0);
      await screenshot(manager.page, testInfo, '02-department-contacts');

      await openManagePanel(manager.page);
      personalViewPid = await createPersonalTableView(manager.page);
      await openManagePanel(manager.page);
      await manager.page.getByTestId(`saved-view-action-edit-${personalViewPid}`).click();
      await manager.page
        .getByTestId(`saved-view-edit-name-${personalViewPid}`)
        .fill(PERSONAL_VIEW_NAME);
      const renameResponse = manager.page.waitForResponse(
        (response) =>
          response.request().method() === 'PUT' &&
          new URL(response.url()).pathname === `/api/views/${personalViewPid}`,
        { timeout: 10_000 },
      );
      await manager.page.getByTestId(`saved-view-edit-save-${personalViewPid}`).click();
      expect((await renameResponse).ok()).toBe(true);
      await expect(manager.page.getByTestId(`saved-view-row-${personalViewPid}`)).toContainText(
        PERSONAL_VIEW_NAME,
      );
      const setDefaultResponse = manager.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === `/api/views/${personalViewPid}/set-default`,
        { timeout: 10_000 },
      );
      await manager.page.getByTestId(`saved-view-action-set-default-${personalViewPid}`).click();
      expect((await setDefaultResponse).ok()).toBe(true);
      await manager.page.getByTestId(`saved-view-select-${personalViewPid}`).click();
      await expect(manager.page).toHaveURL(new RegExp(`view=${personalViewPid}`));

      await manager.page.getByTestId('row-height-btn').click();
      await manager.page.getByTestId('row-height-option-tall').click();
      await expect(manager.page.getByTestId('personal-view-draft-banner')).toBeVisible();
      const saveResponse = manager.page.waitForResponse(
        (response) =>
          response.request().method() === 'PUT' &&
          new URL(response.url()).pathname === `/api/views/${personalViewPid}`,
        { timeout: 10_000 },
      );
      await manager.page.getByTestId('personal-view-save-current').click();
      expect((await saveResponse).ok()).toBe(true);
      await expect(manager.page.getByTestId('personal-view-draft-banner')).toHaveCount(0);
      await screenshot(manager.page, testInfo, '03-personal-view-saved');
    } finally {
      await manager.close();
    }

    expect(
      personalViewPid,
      'personal view must have been created before persistence checks',
    ).toBeTruthy();
    const persisted = (await accessibleViews(managerJwt)).find(
      (view) => view.pid === personalViewPid,
    );
    expect(persisted).toMatchObject({
      pid: personalViewPid,
      name: PERSONAL_VIEW_NAME,
      scope: 'personal',
      isDefault: true,
    });
    expect(persisted.viewConfig?.rowHeight).toBe('tall');

    const pool = new Pool(PG_CONN);
    try {
      const contactFacts = await pool.query<{
        pid: string;
        crm_ct_name: string;
        crm_ct_owner: string;
      }>(
        `SELECT pid, crm_ct_name, crm_ct_owner
           FROM mt_crm_contact_common
          WHERE pid = ANY($1::varchar[])
          ORDER BY pid`,
        [[ids.managerContact, ids.childContact, ids.southContact]],
      );
      expect(contactFacts.rows).toEqual(
        [
          {
            pid: ids.managerContact,
            crm_ct_name: CONTACT_NAMES.manager,
            crm_ct_owner: ids.managerUser,
          },
          { pid: ids.childContact, crm_ct_name: CONTACT_NAMES.child, crm_ct_owner: ids.childUser },
          { pid: ids.southContact, crm_ct_name: CONTACT_NAMES.south, crm_ct_owner: ids.southUser },
        ].sort((left, right) => left.pid.localeCompare(right.pid)),
      );
      const viewFact = await pool.query<{
        name: string;
        scope: string;
        owner_id: string;
        is_default: boolean;
        row_height: string;
      }>(
        `SELECT name, scope, owner_id, is_default, view_config ->> 'rowHeight' AS row_height
           FROM ab_saved_view
          WHERE pid = $1 AND deleted_flag = false`,
        [personalViewPid],
      );
      expect(viewFact.rows).toEqual([
        {
          name: PERSONAL_VIEW_NAME,
          scope: 'personal',
          owner_id: ids.managerUser,
          is_default: true,
          row_height: 'tall',
        },
      ]);
    } finally {
      await pool.end();
    }

    const restoredManager = await newPersonaPage(browser, MANAGER_EMAIL);
    try {
      await openContactsFromMenu(restoredManager.page);
      await expect(restoredManager.page.getByTestId('view-selector-trigger')).toHaveAttribute(
        'data-current-view-name',
        PERSONAL_VIEW_NAME,
      );
      await expect(contactRow(restoredManager.page, CONTACT_NAMES.manager)).toHaveCSS(
        'height',
        '60px',
      );
      await screenshot(restoredManager.page, testInfo, '04-new-context-restored');
    } finally {
      await restoredManager.close();
    }

    const southViews = await accessibleViews(southRepJwt);
    expect(southViews.some((view) => view.pid === personalViewPid)).toBe(false);
    const forbiddenDetail = await fetch(`${BACKEND_URL}/api/views/${personalViewPid}`, {
      headers: { Authorization: `Bearer ${southRepJwt}` },
    });
    expect([403, 404]).toContain(forbiddenDetail.status);

    const south = await newPersonaPage(browser, SOUTH_REP_EMAIL);
    try {
      await openContactsFromMenu(south.page);
      await south.page.getByTestId('view-selector-trigger').click();
      const selector = south.page.getByRole('listbox', { name: /选择视图|Select View/ });
      await expect(selector).toBeVisible();
      await expect(selector).not.toContainText(PERSONAL_VIEW_NAME);
      await south.page.keyboard.press('Escape');
      await expect(contactRow(south.page, CONTACT_NAMES.south)).toBeVisible();
      await expect(contactRow(south.page, CONTACT_NAMES.manager)).toHaveCount(0);
      await expect(contactRow(south.page, CONTACT_NAMES.child)).toHaveCount(0);
      await screenshot(south.page, testInfo, '05-cross-department-isolation');
    } finally {
      await south.close();
    }
  });
});
