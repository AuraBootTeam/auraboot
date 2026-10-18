import { expect, type Browser, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { PG_CONN } from './environments';
import { DEFAULT_TEST_ACCOUNT } from './test-accounts';
import { LoginPage } from '../pages/LoginPage';

export async function loginInternalApiUser(page: Page, username: string, password: string) {
  const login = new LoginPage(page);
  await login.gotoRoot();
  await login.waitForFormReady();
  await login.login(username, password);
  await login.expectLoggedIn();
}

/** Provision unique no-email accounts; revoke only sessions issued to these fresh contexts. */
export async function provisionInternalApiUser(
  browser: Browser,
  baseURL: string,
  role: string | null = 'tenant_admin',
) {
  expect(PG_CONN.database).toMatch(/^auraboot_\d+$/);
  const origin = new URL(baseURL).origin;
  const username = `ic_views_${randomUUID().replaceAll('-', '').slice(0, 16)}`;
  const password = `Aa7!${randomUUID()}`;
  const admin = await browser.newContext({
    baseURL: origin,
    storageState: { cookies: [], origins: [] },
  });
  const context = await browser.newContext({
    baseURL: origin,
    storageState: { cookies: [], origins: [] },
    locale: 'en-US',
    timezoneId: 'Asia/Shanghai',
    viewport: { width: 1440, height: 1000 },
  });
  let userPid: string | undefined;
  const sessionCleanup: Array<{
    context: string;
    beforeStatus: number;
    afterStatus: number;
    revoked: boolean;
  }> = [];
  async function dispose() {
    const errors: unknown[] = [];
    // Each context logged in independently. Revoke only its current server-issued
    // session, while the cookie is still present, and prove that token is rejected.
    for (const [label, client] of [
      ['owned-user', context],
      ['provisioner', admin],
    ] as const) {
      try {
        const before = await client.request.get('/api/auth/me');
        if (before.status() === 401) {
          sessionCleanup.push({
            context: label,
            beforeStatus: 401,
            afterStatus: 401,
            revoked: false,
          });
          continue;
        }
        expect(before.status()).toBe(200);
        const revoked = await client.request.delete('/api/user/sessions/current', {
          headers: { Origin: origin },
        });
        expect(revoked.status()).toBe(200);
        expect((await revoked.json()).code).toBe('0');
        const after = await client.request.get('/api/auth/me');
        sessionCleanup.push({
          context: label,
          beforeStatus: before.status(),
          afterStatus: after.status(),
          revoked: true,
        });
        expect(after.status()).toBe(401);
      } catch (error) {
        errors.push(error);
      }
    }
    try {
      if (userPid) {
        const db = new pg.Client(PG_CONN);
        await db.connect();
        try {
          const user = await db.query(
            'UPDATE ab_user SET is_enabled=false, deleted_flag=true, security_version=COALESCE(security_version,0)+1 WHERE pid=$1 AND user_name=$2 RETURNING id',
            [userPid, username],
          );
          expect(user.rowCount).toBe(1);
          await db.query('UPDATE ab_tenant_member SET deleted_flag=true WHERE user_id=$1', [
            user.rows[0].id,
          ]);
          const remaining = await db.query(
            'SELECT count(*)::int AS count FROM ab_tenant_member WHERE user_id=$1 AND deleted_flag=false',
            [user.rows[0].id],
          );
          expect(remaining.rows[0].count).toBe(0);
        } finally {
          await db.end();
        }
      }
    } catch (error) {
      errors.push(error);
    }
    for (const client of [context, admin]) {
      try {
        await client.close();
      } catch (error) {
        errors.push(error);
      }
    }
    if (errors.length)
      throw new AggregateError(errors, 'Owned user or exact-session cleanup failed');
  }

  try {
    const adminPage = await admin.newPage();
    await loginInternalApiUser(
      adminPage,
      DEFAULT_TEST_ACCOUNT.email,
      DEFAULT_TEST_ACCOUNT.password,
    );
    const response = await admin.request.post('/api/admin/users', {
      headers: { Origin: origin },
      data: {
        userName: username,
        displayName: username,
        initialPassword: password,
        roleCodes: role ? [role] : [],
        roleAssignmentMode: role ? 'EXPLICIT' : 'NONE',
        sendInviteEmail: false,
      },
    });
    const payload = await response.json();
    // Capture ownership before assertions, including schema or role failures.
    if (typeof payload.data?.userPid === 'string') userPid = payload.data.userPid;
    expect(response.status()).toBe(200);
    expect(payload.code).toBe('0');
    expect(userPid).toBeTruthy();
    expect(payload.data.assignedRoles).toEqual(role ? [role] : []);
    await context.addCookies([{ name: 'locale', value: 'en-US', url: origin }]);
    await context.addInitScript(() => localStorage.setItem('locale', 'en-US'));
    const page = await context.newPage();
    await loginInternalApiUser(page, username, password);
    return {
      context,
      page,
      userPid: userPid!,
      username,
      memberPid: payload.data.memberPid as string,
      role,
      origin,
      sessionCleanup,
      dispose,
    };
  } catch (error) {
    try {
      await dispose();
    } catch (cleanup) {
      throw new AggregateError([error, cleanup], 'User provisioning and cleanup failed');
    }
    throw error;
  }
}

export type InternalApiOwnedUser = Awaited<ReturnType<typeof provisionInternalApiUser>>;
