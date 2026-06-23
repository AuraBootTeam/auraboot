import { test, expect, type Page } from '../../fixtures';
import type { APIResponse, Browser } from '@playwright/test';
import * as XLSX from 'xlsx';
import { BASE_URL } from '../../helpers/environments';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { uniqueId } from '../helpers';

type ApiEnvelope<T> = {
  code: string;
  message?: string;
  data?: T;
};

type EmployeeAccount = {
  userPid: string;
  name: string;
  type: string;
  userName: string;
  initialPassword: string;
  assignedRoles: string[];
  mustChangePassword: boolean;
};

type EmployeeProvisionResponse = {
  total: number;
  accounts: EmployeeAccount[];
};

type AuthenticationResponse = {
  jwt: string;
  tenantId?: number | string;
};

const RUN_ID = uniqueId('acct').replace(/[^a-zA-Z0-9]/g, '').slice(-10);
const JSON_EMPLOYEE_NAME = `端到端甲${RUN_ID}`;
const XLSX_EMPLOYEE_NAME = `端到端乙${RUN_ID}`;
const RESET_EMPLOYEE_NAME = `端到端丙${RUN_ID}`;

test.describe.serial('Employee Account Login Policy @auth @critical', () => {
  let jsonEmployee: EmployeeAccount;
  let xlsxEmployee: EmployeeAccount;
  let resetEmployee: EmployeeAccount;

  test.beforeAll(async ({ browser }) => {
    const adminPage = await newPublicPage(browser);
    try {
      const adminHeaders = await loginAdminHeaders(adminPage);
      await ensureEmailPasswordLoginChannel(adminPage, adminHeaders);
      jsonEmployee = await provisionEmployee(adminPage, JSON_EMPLOYEE_NAME, adminHeaders);
      xlsxEmployee = await importEmployeeWorkbook(adminPage, XLSX_EMPLOYEE_NAME, adminHeaders);
      resetEmployee = await provisionEmployee(adminPage, RESET_EMPLOYEE_NAME, adminHeaders);
    } finally {
      await adminPage.context().close();
    }
  });

  test('EAL-001: JSON employee account uses name login and jjzz random password', async ({ browser }) => {
    assertGeneratedEmployee(jsonEmployee, JSON_EMPLOYEE_NAME);

    const page = await newPublicPage(browser);
    try {
      await loginWithIdentifier(page, jsonEmployee.userName, jsonEmployee.initialPassword);
      await expect(page).not.toHaveURL(/\/login/);
      expect(page.url()).not.toContain('forceChangePassword=true');

      await page.goto('/personal/profile', { waitUntil: 'domcontentloaded' });
      await expect(page.locator('h1:has-text("个人资料")')).toBeVisible({ timeout: 10_000 });
      await expect(page.getByText(JSON_EMPLOYEE_NAME).first()).toBeVisible({ timeout: 10_000 });
    } finally {
      await page.context().close();
    }
  });

  test('EAL-002: Excel employee import provisions a loginable name account', async ({ browser }) => {
    assertGeneratedEmployee(xlsxEmployee, XLSX_EMPLOYEE_NAME);

    const page = await newPublicPage(browser);
    try {
      await loginWithIdentifier(page, xlsxEmployee.userName, xlsxEmployee.initialPassword);
      await expect(page).not.toHaveURL(/\/login/);
      expect(page.url()).not.toContain('forceChangePassword=true');
    } finally {
      await page.context().close();
    }
  });

  test('EAL-003: admin reset keeps password admin-managed and does not force user change', async ({ browser }) => {
    const adminPage = await newPublicPage(browser);
    let resetPassword: string;
    try {
      const adminHeaders = await loginAdminHeaders(adminPage);
      const resetResp = await adminPage.request.post(
        `/api/admin/users/${resetEmployee.userPid}/reset-password`,
        { headers: adminHeaders },
      );
      const resetBody = await expectApiSuccess<{ tempPassword: string }>(
        resetResp,
        'admin reset password should return a temporary password',
      );
      resetPassword = resetBody.tempPassword;
      expect(resetPassword).toMatch(/^.{12}$/);
    } finally {
      await adminPage.context().close();
    }

    const page = await newPublicPage(browser);
    try {
      await loginWithIdentifier(page, resetEmployee.userName, resetPassword);
      await expect(page).not.toHaveURL(/\/login/);
      expect(page.url()).not.toContain('forceChangePassword=true');

      await page.goto('/personal/security', { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: 'Security Settings' })).toBeVisible({
        timeout: 10_000,
      });
      await expect(page.locator('[data-testid="change-password-btn"]')).toHaveCount(0);
      await expect(page.locator('[data-testid="current-password-input"]')).toHaveCount(0);
    } finally {
      await page.context().close();
    }
  });

  test('EAL-004: self-service password APIs and pages are disabled', async ({ browser }) => {
    const page = await newPublicPage(browser);
    try {
      await page.goto('/forgot-password', { waitUntil: 'domcontentloaded' });
      await expect(page.locator('[data-testid="forgot-password-disabled"]')).toBeVisible();
      await expect(page.getByText(/tenant administrator/i)).toBeVisible();

      await page.goto('/reset-password?token=e2e-disabled', { waitUntil: 'domcontentloaded' });
      await expect(page.locator('[data-testid="reset-password-disabled"]')).toBeVisible();
      await expect(page.getByText(/tenant administrator/i)).toBeVisible();

      const forgotResp = await page.request.post('/api/auth/forgot-password', {
        data: { email: 'nobody@example.test' },
      });
      const forgotBody = (await forgotResp.json()) as ApiEnvelope<unknown>;
      expect(forgotResp.ok()).toBe(true);
      expect(forgotBody.code).toBe('403');

      const resetResp = await page.request.post('/api/auth/reset-password', {
        data: { token: 'token', newPassword: 'jjzz@1234' },
      });
      const resetBody = (await resetResp.json()) as ApiEnvelope<unknown>;
      expect(resetResp.ok()).toBe(true);
      expect(resetBody.code).toBe('403');
    } finally {
      await page.context().close();
    }
  });

  test('EAL-005: authenticated users cannot call self-service change-password API', async ({ browser }) => {
    const page = await newPublicPage(browser);
    try {
      await loginWithIdentifier(page, jsonEmployee.userName, jsonEmployee.initialPassword);
      const changeResp = await page.request.put('/api/user/password', {
        data: {
          currentPassword: jsonEmployee.initialPassword,
          newPassword: 'jjzz@5678',
          confirmPassword: 'jjzz@5678',
        },
      });
      const changeBody = (await changeResp.json()) as ApiEnvelope<unknown>;
      expect(changeResp.status()).toBe(403);
      expect(changeBody.code).toBe('403');
    } finally {
      await page.context().close();
    }
  });
});

async function newPublicPage(browser: Browser): Promise<Page> {
  const context = await browser.newContext({
    storageState: { cookies: [], origins: [] },
    baseURL: BASE_URL,
  });
  return context.newPage();
}

async function provisionEmployee(
  page: Page,
  name: string,
  headers: Record<string, string>,
): Promise<EmployeeAccount> {
  const response = await page.request.post('/api/admin/users/employee-accounts', {
    headers,
    data: {
      employees: [{ name, type: '管理员' }],
    },
  });
  const data = await expectApiSuccess<EmployeeProvisionResponse>(
    response,
    `provision employee ${name}`,
  );
  expect(data.total).toBe(1);
  return data.accounts[0];
}

async function importEmployeeWorkbook(
  page: Page,
  name: string,
  headers: Record<string, string>,
): Promise<EmployeeAccount> {
  const workbook = XLSX.utils.book_new();
  const sheet = XLSX.utils.aoa_to_sheet([
    ['序号', '姓名', '类型', '手机'],
    [1, name, '管理员', 18600001234],
  ]);
  XLSX.utils.book_append_sheet(workbook, sheet, '在职人员信息');
  const buffer = XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }) as Buffer;

  const response = await page.request.post('/api/admin/users/employee-accounts/import', {
    headers,
    multipart: {
      file: {
        name: 'employee-accounts.xlsx',
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer,
      },
    },
  });
  const data = await expectApiSuccess<EmployeeProvisionResponse>(
    response,
    `import employee workbook ${name}`,
  );
  expect(data.total).toBe(1);
  return data.accounts[0];
}

async function loginAdminHeaders(page: Page): Promise<Record<string, string>> {
  const loginResp = await page.request.post('/api/auth/login', {
    data: {
      identifier: DEFAULT_TEST_ACCOUNT.email,
      email: DEFAULT_TEST_ACCOUNT.email,
      password: DEFAULT_TEST_ACCOUNT.password,
    },
  });
  const loginData = await expectApiSuccess<AuthenticationResponse>(
    loginResp,
    'admin login for employee-account E2E setup',
  );
  let jwt = loginData.jwt;

  if (!loginData.tenantId) {
    const spacesResp = await page.request.get('/api/tenant-selection/my-spaces', {
      headers: bearerHeaders(jwt),
    });
    const spaces = await expectApiSuccess<Array<{ tenantId: number | string; tenantName?: string; spaceType?: string }>>(
      spacesResp,
      'admin tenant spaces for employee-account E2E setup',
    );
    const businessSpace =
      spaces.find((space) => space.spaceType === 'business' && space.tenantName === 'AuraBoot Demo') ??
      spaces.find((space) => space.spaceType === 'business');
    expect(businessSpace, 'admin should have a business tenant for employee-account E2E').toBeTruthy();

    const selectResp = await page.request.post('/api/tenant-selection/process', {
      headers: bearerHeaders(jwt),
      data: { action: 'select', tenantId: businessSpace!.tenantId },
    });
    const selected = await expectApiSuccess<AuthenticationResponse>(
      selectResp,
      'admin tenant selection for employee-account E2E setup',
    );
    jwt = selected.jwt;
  }

  return bearerHeaders(jwt);
}

async function ensureEmailPasswordLoginChannel(page: Page, headers: Record<string, string>) {
  const response = await page.request.put('/api/admin/login-channels', {
    headers,
    data: [
      { channel: 'email_password', enabled: true, sortOrder: 0 },
      { channel: 'sms', enabled: false, sortOrder: 1 },
      { channel: 'email_code', enabled: false, sortOrder: 2 },
    ],
  });
  const raw = await response.text();
  let body: ApiEnvelope<unknown>;
  try {
    body = JSON.parse(raw) as ApiEnvelope<unknown>;
  } catch {
    body = { code: String(response.status()), message: raw };
  }
  expect(response.ok(), `ensure account/password login channel: HTTP ${response.status()} ${body.message ?? ''}`).toBe(
    true,
  );
  expect(body.code, 'ensure account/password login channel: business code').toBe('0');
}

function bearerHeaders(jwt: string): Record<string, string> {
  return {
    Authorization: `Bearer ${jwt}`,
  };
}

async function expectApiSuccess<T>(response: APIResponse, message: string): Promise<T> {
  const raw = await response.text();
  let body: ApiEnvelope<T>;
  try {
    body = JSON.parse(raw) as ApiEnvelope<T>;
  } catch {
    body = { code: String(response.status()), message: raw };
  }
  expect(response.ok(), `${message}: HTTP ${response.status()} ${body.message ?? ''}`).toBe(true);
  expect(body.code, `${message}: business code`).toBe('0');
  expect(body.data, `${message}: response data`).toBeTruthy();
  return body.data as T;
}

function assertGeneratedEmployee(account: EmployeeAccount, expectedName: string) {
  expect(account.name).toBe(expectedName);
  expect(account.userName).toBe(expectedName);
  expect(account.type).toBe('管理员');
  expect(account.initialPassword).toMatch(/^jjzz@\d{4}$/);
  expect(account.assignedRoles).toContain('tenant_admin');
  expect(account.mustChangePassword).toBe(false);
}

async function loginWithIdentifier(page: Page, identifier: string, password: string) {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true', {
    timeout: 5_000,
  });

  const emailPasswordTab = page.getByTestId('login-tab-email_password');
  if (await emailPasswordTab.isVisible().catch(() => false)) {
    await emailPasswordTab.click();
  }

  const identifierInput = page.locator('#identifier');
  await identifierInput.waitFor({ state: 'visible', timeout: 5_000 });
  await identifierInput.fill(identifier);
  await expect(identifierInput).toHaveValue(identifier);

  const passwordInput = page.locator('#password');
  await passwordInput.fill(password);
  await expect(passwordInput).toHaveValue(password);

  await page.locator('form button[type="submit"]').first().click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 20_000,
    waitUntil: 'domcontentloaded',
  });
}
