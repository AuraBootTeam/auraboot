#!/usr/bin/env node
/**
 * Browser golden for E5 — what a user actually sees when their organization is suspended.
 *
 * The backend golden proved login is refused and mints no token. This proves the OTHER half: the
 * refusal reaches the person at the keyboard as a sentence they can act on, not a raw code or a
 * generic "Business error". Drives the real login form (controlled React inputs, SSR session
 * route) against a stack whose tenant has been suspended, and asserts the localized message
 * ("该组织已被暂停…" / "This organization is suspended…") shows on the page — with screenshots.
 *
 * Usage:
 *   node tests/golden/suspended-tenant-login-ui.mjs \
 *     --base-url http://localhost:5173 --email admin@auraboot.com --password Test2026x --shots ./artifacts
 */
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { chromium } from '@playwright/test';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, arg, i, all) => {
    if (arg.startsWith('--')) acc.push([arg.slice(2), all[i + 1]]);
    return acc;
  }, []),
);
const BASE = args['base-url'] ?? 'http://localhost:5173';
const EMAIL = args.email ?? 'admin@auraboot.com';
const PASSWORD = args.password ?? 'Test2026x';
const SHOTS = args.shots ?? './artifacts/e5-suspend';
mkdirSync(SHOTS, { recursive: true });

const results = [];
const record = (name, ok, detail) => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};
const shot = (page, name) => page.screenshot({ path: join(SHOTS, `${name}.png`), fullPage: true });

const browser = await chromium.launch();
const page = await browser.newPage();

try {
  await page.goto(`${BASE}/login`);

  // Controlled React inputs: pressSequentially (fill() sets the value without firing the change
  // events React listens for). By role, not tag: the first <input> is a hidden channelCode field.
  const identifier = page.getByRole('textbox', { name: /用户名或邮箱|Username|Email/ });
  await identifier.waitFor({ state: 'visible', timeout: 30_000 });
  await identifier.click();
  await identifier.pressSequentially(EMAIL, { delay: 20 });
  const password = page.locator('input[type="password"]').first();
  await password.click();
  await password.pressSequentially(PASSWORD, { delay: 20 });
  record('login form accepts the suspended tenant admin credentials', true);
  await shot(page, '01-login-filled');

  await page.getByRole('button', { name: /立即登录|登录|Sign in|Login/i }).first().click();

  // The login must NOT succeed: we should still be on /login, and the suspended message should show.
  // Wait for either an error banner or the message text; do not wait to navigate away (it must not).
  const suspendedRe = /暂停|suspended/i;
  await page
    .waitForFunction((re) => new RegExp(re, 'i').test(document.body.innerText), '暂停|suspended', {
      timeout: 30_000,
    })
    .catch(() => {});

  const bodyText = (await page.locator('body').innerText()).trim();
  const stillOnLogin = new URL(page.url()).pathname.includes('/login');
  const showsSuspended = suspendedRe.test(bodyText);
  // The user must NOT be shown the raw i18n key or the generic code — a sentence, not scaffolding.
  const noRawKey = !/\$i18n:|tenant\.suspended|Business error/i.test(bodyText);

  record('login is refused — the browser stays on the login page', stillOnLogin, page.url());
  record(
    'the suspended-organization message is shown to the user',
    showsSuspended,
    showsSuspended ? JSON.stringify(bodyText.match(/[^。\n]*(?:暂停|suspended)[^。\n]*[。.]?/i)?.[0] ?? '') : bodyText.slice(0, 160),
  );
  record('the message is a sentence, not a raw code or i18n key', showsSuspended && noRawKey);
  await shot(page, '02-suspended-error');
} finally {
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed — screenshots in ${SHOTS}`);
process.exit(failed.length === 0 ? 0 : 1);
