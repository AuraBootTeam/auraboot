import { expect, request as pwRequest, type APIRequestContext, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { authenticateBffSession } from './bff-auth-session';
import { BASE_URL } from './environments';
import { DEFAULT_TEST_ACCOUNT } from './test-accounts';

type StorageState = Awaited<ReturnType<APIRequestContext['storageState']>>;
export interface OpenPlatformSession { cookies: StorageState['cookies']; email: string }
const sessions = new Map<string, Promise<OpenPlatformSession>>();

/** Reuse actual issued sessions; a bad saved identity fails closed without another login. */
export function openPlatformSession(account: { email: string; password: string } = DEFAULT_TEST_ACCOUNT): Promise<OpenPlatformSession> {
  const key = `${new URL(BASE_URL).origin}|${account.email}`;
  let pending = sessions.get(key);
  if (!pending) {
    pending = (async () => {
      const admin = account.email === DEFAULT_TEST_ACCOUNT.email;
      const storageState: StorageState = admin
        ? JSON.parse(await readFile(process.env.PW_ADMIN_STORAGE_STATE || path.join(
            process.env.PW_STORAGE_DIR || path.resolve('tests/storage'), 'admin.json'), 'utf8'))
        : { cookies: [], origins: [] };
      const context = await pwRequest.newContext({ storageState });
      try {
        if (!admin) await authenticateBffSession(context, BASE_URL, account.email, account.password);
        const response = await context.get(`${BASE_URL}/api/auth/me`);
        expect(response.status(), 'Issued session must authorize the configured actor').toBe(200);
        const body = await response.json();
        expect(body.code).toBe('0');
        expect(body.data?.user?.email).toBe(account.email);
        expect(String(body.data?.user?.tenantId)).toMatch(/^\d+$/);
        const state = await context.storageState();
        const cookies = state.cookies.filter(cookie => cookie.name === '__session');
        expect(cookies, 'Exactly one BFF-issued session must exist').toHaveLength(1);
        return { cookies, email: account.email };
      } finally { await context.dispose(); }
    })();
    sessions.set(key, pending);
  }
  return pending;
}

export function sessionHeaders(session: OpenPlatformSession) {
  return { Cookie: session.cookies.map(cookie => `${cookie.name}=${cookie.value}`).join('; '),
    Origin: new URL(BASE_URL).origin };
}

export async function installOpenPlatformSession(page: Page, session: OpenPlatformSession, locale = 'zh-CN') {
  await page.context().clearCookies();
  await page.context().addCookies([...session.cookies,
    { name: 'locale', value: locale, url: BASE_URL, sameSite: 'Lax' }]);
  await page.addInitScript(value => localStorage.setItem('locale', value), locale);
}
