import { join } from 'node:path';
import { test, expect } from '../../fixtures';
import {
  clickRowActionByLocator,
  executeCommandViaApi,
  findRowInPaginatedList,
  navigateToDynamicPage,
  uniqueId,
} from '../helpers';
import { ErrorCodes } from '~/shared/services/http-client/types';

const EMPLOYEE_PAGE_KEY = 'org_employee';
const MEMBER_PAGE_KEY = 'tenant_member';
const evidenceDir = join(
  process.cwd(),
  '..',
  'docs/plans/2026-06/evidence/account-org-password/latest/screenshots',
);

function unwrapCommandPayload(body: any) {
  return body?.data?.data && typeof body.data.data === 'object' ? body.data.data : body?.data;
}

async function createEmployeeForOpenAccount(page: import('@playwright/test').Page) {
  const suffix = uniqueId('OPEN');
  const deptName = `Open Account Dept ${suffix}`;
  const positionName = `Open Account Position ${suffix}`;
  const employeeName = `Open Account Employee ${suffix}`;
  const employeeEmail = `open-account-${suffix.toLowerCase()}@example.test`;

  const deptResult = await executeCommandViaApi(page, 'org:create_department', {
    org_dept_name: deptName,
    org_dept_code: `OA-DEPT-${Date.now()}`,
  });
  expect(deptResult.code).toBe(ErrorCodes.SUCCESS);

  const positionResult = await executeCommandViaApi(page, 'org:create_position', {
    org_pos_name: positionName,
    org_pos_code: `OA-POS-${Date.now()}`,
    org_pos_level: '1',
    org_pos_dept_id: deptResult.recordId,
  });
  expect(positionResult.code).toBe(ErrorCodes.SUCCESS);

  const employeeResult = await executeCommandViaApi(page, 'org:create_employee', {
    org_emp_name: employeeName,
    org_emp_email: employeeEmail,
    org_emp_phone: `139${Date.now().toString().slice(-8)}`,
    org_emp_dept_id: deptResult.recordId,
    org_emp_position_id: positionResult.recordId,
  });
  expect(employeeResult.code).toBe(ErrorCodes.SUCCESS);
  expect(employeeResult.recordId).toBeTruthy();

  return { employeeName, employeeEmail, employeePid: employeeResult.recordId };
}

test.describe('Account policy and employee account opening', () => {
  test('POLICY-001: admin can view read-only account security policy @smoke', async ({ page }) => {
    await page.goto('/settings/account-security-policy', { waitUntil: 'domcontentloaded' });

    await expect(page.getByTestId('account-security-policy-page')).toBeVisible({ timeout: 10000 });
    await expect(page.getByRole('heading', { name: 'Account Security Policy' })).toBeVisible();
    await expect(page.getByTestId('account-security-policy-mode')).toContainText(
      'Administrator managed',
    );
    await expect(page.getByText('Public registration', { exact: true })).toBeVisible();
    await expect(page.getByText('Self-service password', { exact: true })).toBeVisible();
    await expect(page.getByText('8-128 characters')).toBeVisible();
    await expect(page.getByText('5 recent passwords')).toBeVisible();
    await expect(page.getByText('5 attempts')).toBeVisible();

    await page.screenshot({
      path: join(evidenceDir, 'ui-14-account-security-policy.png'),
      fullPage: true,
    });
  });

  test('ORG-OPEN-001: admin opens a login account from an employee row @smoke', async ({
    page,
  }) => {
    test.setTimeout(45_000);
    const { employeeName } = await createEmployeeForOpenAccount(page);

    await navigateToDynamicPage(page, EMPLOYEE_PAGE_KEY);
    const row = await findRowInPaginatedList(page, employeeName, 15_000);
    await expect(row).toBeVisible();

    const openAccountResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/org:open_employee_account') &&
        response.request().method() === 'POST',
      { timeout: 15_000 },
    );

    await clickRowActionByLocator(page, row, 'open-account', '开通账号');
    await expect(page.getByText('确认为该人员开通登录账号？')).toBeVisible({ timeout: 5000 });
    await page.screenshot({
      path: join(evidenceDir, 'ui-15-employee-open-account-confirm.png'),
      fullPage: true,
    });
    await page.getByRole('button', { name: /^确认$/ }).last().click({ force: true });

    const response = await openAccountResponse;
    expect(response.ok()).toBe(true);
    const body = await response.json();
    expect(body?.code).toBe(ErrorCodes.SUCCESS);
    expect(body?.data?.data?.action).toBe('open_employee_account');
    expect(body?.data?.data?.adminManaged).toBe(true);
    expect(body?.data?.data?.createdMember).toBe(true);
    expect(typeof body?.data?.data?.memberPid).toBe('string');
    expect(typeof body?.data?.data?.tempPassword).toBe('string');
    expect(body.data.data.tempPassword.length).toBeGreaterThanOrEqual(8);

    await expect(page.getByRole('heading', { name: '临时密码已生成' })).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText(/临时密码只显示一次：/)).toBeVisible();
    await page.screenshot({
      path: join(evidenceDir, 'ui-15-employee-open-account-temp-password.png'),
      fullPage: true,
    });
  });

  test('MEM-04: admin provisions a tenant member from the account page @smoke', async ({
    page,
  }) => {
    test.setTimeout(45_000);
    const { employeeName } = await createEmployeeForOpenAccount(page);

    await navigateToDynamicPage(page, MEMBER_PAGE_KEY);
    const provisionButton = page.getByTestId('toolbar-btn-provision_from_employee');
    await expect(provisionButton).toBeVisible({ timeout: 10_000 });
    await provisionButton.click();

    const employeeSelect = page.getByTestId('form-dialog-field-employeePid');
    await expect(page.getByTestId('form-dialog')).toBeVisible({ timeout: 10_000 });
    await expect(employeeSelect).toBeVisible();
    await employeeSelect.selectOption({ label: employeeName });
    await page.screenshot({
      path: join(evidenceDir, 'ui-16-account-provision-from-employee-form.png'),
      fullPage: true,
    });

    const provisionResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/admin:provision_member_from_employee') &&
        response.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await page.getByTestId('form-dialog-submit').click();

    const response = await provisionResponse;
    expect(response.ok()).toBe(true);
    const body = await response.json();
    const data = unwrapCommandPayload(body);
    expect(body?.code).toBe(ErrorCodes.SUCCESS);
    expect(data?.action).toBe('provision_member_from_employee');
    expect(data?.adminManaged).toBe(true);
    expect(data?.createdMember).toBe(true);
    expect(typeof data?.employeePid).toBe('string');
    expect(typeof data?.memberPid).toBe('string');
    expect(typeof data?.tempPassword).toBe('string');
    expect(data.tempPassword.length).toBeGreaterThanOrEqual(8);

    await expect(page.getByRole('heading', { name: '临时密码已生成' })).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText(/临时密码只显示一次：/)).toBeVisible();
    await page.screenshot({
      path: join(evidenceDir, 'ui-16-account-provision-from-employee-temp-password.png'),
      fullPage: true,
    });
  });
});
