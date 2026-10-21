/** Role membership uses an owned role and a provisioned tenant member, without an organization plugin. */
import { test, expect, type Page } from '../../fixtures';
import type { APIResponse } from '@playwright/test';
import { uniqueId } from '../helpers';
import { provisionOwnedTenantUser } from '../../helpers/owned-tenant-user';

test.describe.configure({ mode: 'serial' });
const uid = uniqueId('RM');
const roleCode = `e2e_rm_${uid}`;
let rolePid = '';
let memberPid = '';
let memberEmail = '';

async function success<T>(response: Pick<APIResponse, 'ok' | 'url' | 'status' | 'json'>): Promise<T> {
  expect(response.ok(), `${response.url()} returned ${response.status()}`).toBe(true);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  return body.data as T;
}

async function openMembers(page: Page) {
  await page.goto('/enterprise/permissions', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('permission-page')).toBeVisible();
  const role = page.getByTestId(`role-item-${roleCode}`);
  await expect(role).toBeVisible();
  await role.click();
  await page.getByTestId('permission-right-tab-members').click();
  await expect(page.getByTestId('role-member-tab')).toBeVisible();
}

async function members(page: Page) {
  const data = await success<{ records: Array<{ memberPid: string; email: string }> }>(
    await page.request.get(`/api/roles/${rolePid}/members`));
  return data.records;
}

async function addOwnedMember(page: Page) {
  const add = page.getByTestId('role-member-add-btn');
  await expect(page.getByTestId('role-member-empty')).toBeVisible();
  await expect(add).toBeVisible();
  await expect(add).toBeEnabled();
  await add.click();
  const dialog = page.getByTestId('add-member-dialog');
  await expect(dialog).toBeVisible();
  await page.getByTestId('add-member-tab-list').click();
  const loaded = page.waitForResponse(response => {
    const url = new URL(response.url());
    return url.pathname === `/api/roles/${rolePid}/members/candidates`
      && url.searchParams.get('keyword') === memberEmail;
  });
  await page.getByTestId('add-member-list-search').fill(memberEmail);
  const candidates = await success<Array<{ memberPid: string; email: string }>>(await loaded);
  expect(candidates.map(candidate => candidate.memberPid)).toEqual([memberPid]);
  const row = dialog.getByTestId(`candidate-row-${memberPid}`);
  await expect(row).toContainText(memberEmail);
  await row.getByRole('checkbox').check();
  const assigned = page.waitForResponse(response => new URL(response.url()).pathname === `/api/roles/${rolePid}/members/assign`
    && response.request().method() === 'POST');
  await page.getByTestId('add-member-confirm').click();
  const response = await assigned;
  expect(response.request().postDataJSON().memberPids).toEqual([memberPid]);
  await success(response);
  await expect(dialog).not.toBeVisible();
  await expect(page.getByTestId(`role-member-row-${memberPid}`)).toContainText(memberEmail);
  expect((await members(page)).map(member => member.memberPid)).toEqual([memberPid]);
}

test.describe('Permission Management — Role Members Tab', () => {
  test.beforeAll(async ({ browser, baseURL }) => {
    const context = await browser.newContext({ baseURL,
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();
    try {
      const role = await success<{ pid: string }>(await page.request.post('/api/roles', {
        data: { code: roleCode, name: `Owned role ${uid}`, type: 'custom', status: 'active' },
      }));
      rolePid = role.pid;
      expect(rolePid).toMatch(/^[0-9A-Z]{26}$/);
      const actor = await provisionOwnedTenantUser(page, 'role_candidate');
      memberPid = actor.memberPid;
      memberEmail = actor.email;
    } finally {
      await context.close();
    }
  });

  test('D1: switch to members tab and see member list or empty state', async ({ page }) => {
    await openMembers(page);
    await expect(page.getByTestId('role-member-empty')).toBeVisible();
    expect(await members(page)).toEqual([]);
  });

  test('add member to role via member list tab', async ({ page }) => {
    await openMembers(page);
    await addOwnedMember(page);
  });

  test('remove member from role', async ({ page }) => {
    await openMembers(page);
    const assigned = await members(page);
    if (!assigned.some(member => member.memberPid === memberPid)) await addOwnedMember(page);
    const row = page.getByTestId(`role-member-row-${memberPid}`);
    await expect(row).toContainText(memberEmail);
    await page.getByTestId(`role-member-remove-${memberPid}`).click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    const removed = page.waitForResponse(response => new URL(response.url()).pathname === `/api/roles/${rolePid}/members/remove`
      && response.request().method() === 'POST');
    await page.getByTestId('confirm-ok').click();
    const response = await removed;
    expect(response.request().postDataJSON()).toEqual([memberPid]);
    await success(response);
    await expect(row).toHaveCount(0);
    await expect(page.getByTestId('role-member-empty')).toBeVisible();
    expect(await members(page)).toEqual([]);
  });
});
