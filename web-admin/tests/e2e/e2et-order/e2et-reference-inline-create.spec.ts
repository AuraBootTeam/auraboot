/**
 * E2E Test Order — Reference inline create golden.
 *
 * Covers the real browser flow:
 * - open order form customer reference selector
 * - click "+ 新建"
 * - create customer in the quick-create DSL form
 * - assert backend persistence and automatic field selection
 *
 * Uses real database, NO MOCKING.
 */

import { test, expect } from '../../fixtures';
import type { APIResponse, Browser, Locator, Page } from '@playwright/test';
import { uniqueId, waitForDynamicPageLoad, waitForFormReady } from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { loginViaUI } from '../../helpers/wd-fixtures';

const CUSTOMER_FIELD = 'e2et_order_customer';
const CUSTOMER_CREATE_COMMAND = '/api/meta/commands/execute/e2et:create_customer';
const TEST_PASSWORD = 'Test2026x';
const CUSTOMER_FULL_FORM_FIELDS = [
  'e2et_cust_code',
  'e2et_cust_name',
  'e2et_cust_region',
  'e2et_cust_active',
  'e2et_cust_contact',
  'e2et_cust_email',
];

type ApiEnvelope<T> = {
  code?: number | string;
  data?: T;
  message?: string;
};

type PermissionRecord = {
  pid: string;
  code: string;
};

type RoleRecord = {
  pid: string;
  code: string;
};

async function openOrderCreateForm(page: Page): Promise<Locator> {
  const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
  await order.gotoNewForm();
  await waitForDynamicPageLoad(page);
  await waitForFormReady(page, 10_000);

  const customerTrigger = page.getByTestId(`select-trigger-${CUSTOMER_FIELD}`);
  await expect(customerTrigger).toBeVisible({ timeout: 5_000 });
  return customerTrigger;
}

async function openInlineCreateDialog(page: Page, customerTrigger: Locator): Promise<Locator> {
  await customerTrigger.click();

  const createNew = page.getByTestId(`select-create-new-${CUSTOMER_FIELD}`);
  await expect(createNew).toBeVisible({ timeout: 5_000 });
  await createNew.click();

  const dialog = page
    .locator('[role="dialog"]')
    .filter({ has: page.getByTestId('dsl-form-renderer') })
    .first();
  await expect(dialog).toBeVisible({ timeout: 5_000 });
  await expect(dialog.getByTestId('form-field-e2et_cust_code')).toBeVisible({ timeout: 5_000 });
  return dialog;
}

async function fillDialogTextField(
  dialog: Locator,
  fieldName: string,
  value: string,
): Promise<void> {
  const input = dialog
    .locator(
      [
        `[data-testid="form-field-${fieldName}"] input`,
        `[data-testid="field-${fieldName}"] input`,
        `input[name="${fieldName}"]`,
        `#${fieldName}`,
      ].join(', '),
    )
    .first();
  await expect(input).toBeVisible({ timeout: 5_000 });
  await input.fill(value);
}

async function selectEastRegion(page: Page, dialog: Locator): Promise<void> {
  const regionTrigger = dialog.getByTestId('select-trigger-e2et_cust_region');
  await expect(regionTrigger).toBeVisible({ timeout: 5_000 });
  await regionTrigger.click();

  const eastOption = page.getByRole('option', { name: /华东|East/i }).first();
  if (await eastOption.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await eastOption.click();
    return;
  }

  await page.locator('[role="option"][data-value="east"], [data-value="east"]').first().click();
}

async function findCustomerByCode(page: Page, code: string): Promise<Record<string, any> | null> {
  const filters = encodeURIComponent(
    JSON.stringify([{ fieldName: 'e2et_cust_code', operator: 'EQ', value: code }]),
  );
  const resp = await page.request.get(
    `/api/dynamic/e2et_customer/list?pageNum=1&pageSize=10&filters=${filters}`,
  );
  expect(resp.ok()).toBe(true);
  const body = await resp.json();
  const records = body?.data?.records ?? body?.data?.list ?? [];
  return records.find((record: any) => record?.e2et_cust_code === code) ?? null;
}

async function expectApiData<T>(response: APIResponse, label: string): Promise<T> {
  const text = await response.text();
  let body: ApiEnvelope<T>;
  try {
    body = JSON.parse(text) as ApiEnvelope<T>;
  } catch {
    throw new Error(`${label}: non-JSON response HTTP ${response.status()}: ${text}`);
  }

  expect(response.ok(), `${label}: HTTP ${response.status()}: ${text}`).toBe(true);
  expect(String(body.code ?? '0'), `${label}: API envelope ${text}`).toBe('0');
  return body.data as T;
}

async function fetchPermissionsByResourceType(
  page: Page,
  resourceType: string,
): Promise<PermissionRecord[]> {
  return expectApiData<PermissionRecord[]>(
    await page.request.get(`/api/permissions/resource-type/${resourceType}`),
    `load ${resourceType} permissions`,
  );
}

async function resolvePermissionPids(page: Page, permissionCodes: string[]): Promise<string[]> {
  const permissions = [
    ...(await fetchPermissionsByResourceType(page, 'function')),
    ...(await fetchPermissionsByResourceType(page, 'operation')),
    ...(await fetchPermissionsByResourceType(page, 'data')),
    ...(await fetchPermissionsByResourceType(page, 'model')),
  ];
  const byCode = new Map(permissions.map((permission) => [permission.code, permission.pid]));
  const missing = permissionCodes.filter((code) => !byCode.has(code));

  expect(missing, `missing fixture permissions: ${missing.join(', ')}`).toEqual([]);
  return permissionCodes.map((code) => byCode.get(code)!);
}

async function createRoleWithPermissions(
  page: Page,
  roleCode: string,
  permissionCodes: string[],
): Promise<RoleRecord> {
  const role = await expectApiData<RoleRecord>(
    await page.request.post('/api/roles', {
      data: {
        code: roleCode,
        name: `Reference Inline Create ${roleCode}`.slice(0, 50),
        description: 'E2ET reference inline create permission gate',
        type: 'custom',
      },
    }),
    `create role ${roleCode}`,
  );

  const permissionPids = await resolvePermissionPids(page, permissionCodes);
  await expectApiData<boolean>(
    await page.request.post(`/api/roles/${role.pid}/permissions`, {
      data: permissionPids,
    }),
    `assign permissions to ${roleCode}`,
  );
  return role;
}

async function createRoleUser(
  page: Page,
  args: { email: string; roleCode: string },
): Promise<void> {
  const result = await expectApiData<{ assignedRoles?: string[] }>(
    await page.request.post('/api/admin/users', {
      data: {
        email: args.email,
        displayName: `Reference Inline Create ${args.roleCode}`.slice(0, 50),
        initialPassword: TEST_PASSWORD,
        roleCodes: [args.roleCode],
        sendInviteEmail: false,
      },
    }),
    `create user ${args.email}`,
  );

  expect(result.assignedRoles ?? [], `assigned roles for ${args.email}`).toContain(args.roleCode);
}

async function openAsRoleUser(
  browser: Browser,
  email: string,
): Promise<{ page: Page; close: () => Promise<void> }> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await loginViaUI(page, email, TEST_PASSWORD);
  return { page, close: () => context.close() };
}

function extractPermissionCodes(payload: Record<string, any>): string[] {
  const permissionCodes = payload?.permissions?.permissionCodes;
  if (Array.isArray(permissionCodes)) return permissionCodes.map(String);

  const permissions = payload?.permissions?.permissions;
  if (Array.isArray(permissions)) {
    return permissions.map((permission) => String(permission?.code ?? '')).filter(Boolean);
  }
  return [];
}

test.describe('E2E Test Order — Reference inline create golden', () => {
  test('RIC-001: creates a customer inline and auto-selects it on the order form @critical', async ({
    page,
  }) => {
    test.setTimeout(30_000);
    const custCode = `RIC-${uniqueId('C')}`;
    const custName = `Inline Customer ${uniqueId('N')}`;

    const customerTrigger = await openOrderCreateForm(page);
    const dialog = await openInlineCreateDialog(page, customerTrigger);

    await fillDialogTextField(dialog, 'e2et_cust_code', custCode);
    await fillDialogTextField(dialog, 'e2et_cust_name', custName);
    await selectEastRegion(page, dialog);

    const commandResponse = page.waitForResponse(
      (resp) =>
        resp.url().includes(CUSTOMER_CREATE_COMMAND) &&
        resp.request().method().toLowerCase() === 'post',
      { timeout: 5_000 },
    );

    await dialog.getByTestId('form-btn-save').click();

    const response = await commandResponse;
    const body = await response.json();
    expect(String(body.code)).toBe('0');

    await expect(dialog).not.toBeVisible({ timeout: 5_000 });
    await expect(customerTrigger).toContainText(custName, { timeout: 5_000 });

    const customer = await findCustomerByCode(page, custCode);
    expect(customer).toBeTruthy();
    expect(customer?.e2et_cust_name).toBe(custName);
    expect(customer?.e2et_cust_region).toBe('east');
  });

  test('RIC-004: quick-create dialog renders the full target-model form in phase 1 @critical', async ({
    page,
  }) => {
    test.setTimeout(30_000);

    const customerTrigger = await openOrderCreateForm(page);
    const dialog = await openInlineCreateDialog(page, customerTrigger);

    for (const fieldName of CUSTOMER_FULL_FORM_FIELDS) {
      await expect(dialog.getByTestId(`form-field-${fieldName}`)).toBeVisible({
        timeout: 5_000,
      });
    }
    await expect(dialog.getByTestId('form-btn-save')).toBeVisible();
  });

  test('RIC-002: failed quick-create keeps the order reference unselected @critical', async ({
    page,
  }) => {
    test.setTimeout(30_000);
    const custCode = `RICFAIL-${uniqueId('C')}`;
    const custName = `Rejected Inline Customer ${uniqueId('N')}`;

    const customerTrigger = await openOrderCreateForm(page);
    const dialog = await openInlineCreateDialog(page, customerTrigger);

    await fillDialogTextField(dialog, 'e2et_cust_code', custCode);
    await fillDialogTextField(dialog, 'e2et_cust_name', custName);

    await dialog.getByTestId('form-btn-save').click();

    await expect(dialog).toBeVisible({ timeout: 5_000 });
    await expect(customerTrigger).not.toContainText(custName);
    expect(await findCustomerByCode(page, custCode)).toBeNull();
  });

  test('RIC-003: user without customer create permission cannot open inline create @critical', async ({
    browser,
    page,
  }) => {
    test.setTimeout(45_000);
    const suffix = Date.now().toString(36);
    const roleCode = `e2et_order_only_${suffix}`;
    const email = `e2e-ricgate-${suffix}@e2e.local`;

    await createRoleWithPermissions(page, roleCode, [
      'page.page.read',
      'e2et.order.manage',
      'e2et.order.read',
      'e2et.customer.read',
      'model.e2et_order',
      'model.e2et_order.read',
      'model.e2et_order.create',
      'model.e2et_customer',
      'model.e2et_customer.read',
    ]);
    await createRoleUser(page, { email, roleCode });

    const gatedSession = await openAsRoleUser(browser, email);
    try {
      const me = await expectApiData<Record<string, any>>(
        await gatedSession.page.request.get('/api/auth/me'),
        `load profile ${email}`,
      );
      const permissionCodes = extractPermissionCodes(me);
      expect(permissionCodes).toContain('page.page.read');
      expect(permissionCodes).toContain('e2et.order.manage');
      expect(permissionCodes).toContain('model.e2et_order.create');
      expect(permissionCodes).not.toContain('e2et.customer.manage');
      expect(permissionCodes).not.toContain('model.e2et_customer.create');

      const customerTrigger = await openOrderCreateForm(gatedSession.page);
      await customerTrigger.click();

      await expect(
        gatedSession.page.getByTestId(`select-create-new-${CUSTOMER_FIELD}`),
      ).toHaveCount(0, { timeout: 5_000 });
      await expect(
        gatedSession.page.locator('[role="dialog"]').filter({
          has: gatedSession.page.getByTestId('dsl-form-renderer'),
        }),
      ).toHaveCount(0);
    } finally {
      await gatedSession.close();
    }
  });
});
