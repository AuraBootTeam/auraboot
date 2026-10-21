import { expect, type Page } from '@playwright/test';
import { uniqueId } from '../e2e/helpers';

/** Provision a unique actor in the current test tenant, without sending email. */
export async function provisionOwnedTenantUser(page: Page, prefix: string, roleCodes: string[] = []) {
  const suffix = uniqueId(prefix).toLowerCase().replace(/[^a-z0-9_]/g, '_');
  const email = `${suffix}@e2e-test.local`;
  const password = 'OwnedUser2026x';
  const response = await page.request.post('/api/admin/users', {
    data: { email, displayName: suffix.slice(0, 50), initialPassword: password,
      roleCodes, roleAssignmentMode: roleCodes.length ? 'EXPLICIT' : 'NONE', sendInviteEmail: false },
  });
  expect(response.ok(), `Owned user provisioning returned ${response.status()}`).toBe(true);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  expect(body.data.email).toBe(email);
  expect(body.data.memberPid).toMatch(/^[0-9A-Z]{26}$/);
  expect(body.data.userPid).toMatch(/^[0-9A-Z]{26}$/);
  expect(body.data.mustChangePassword).toBe(false);
  return { email, password, memberPid: String(body.data.memberPid), userPid: String(body.data.userPid) };
}
