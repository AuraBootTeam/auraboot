// Generate a Playwright admin storageState for a deployed AuraBoot instance,
// so the showcase seed (run-showcase-seed-sequence.mjs) can run against it.
// The BFF turns the signed __session cookie into a Bearer token for /api/*.
//
// Usage: node gen-admin-storage.mjs <out.json>
//   env: SEED_BASE_URL, ADMIN_EMAIL, ADMIN_PASSWORD
//   Resolves @playwright/test from the web-admin workspace.
import { createRequire } from 'module';
import { existsSync } from 'fs';
import { dirname, resolve } from 'path';
import { fileURLToPath } from 'url';

const here = dirname(fileURLToPath(import.meta.url));
// scripts/deploy/oss-remote -> repo root -> web-admin
const webAdmin = resolve(here, '../../../web-admin');
const require = createRequire(resolve(webAdmin, 'package.json'));
const { chromium } = require('@playwright/test');

const BASE = process.env.SEED_BASE_URL || process.env.PLAYWRIGHT_BASE_URL;
const OUT = process.argv[2];
if (!BASE || !OUT) { console.error('usage: SEED_BASE_URL=<url> node gen-admin-storage.mjs <out.json>'); process.exit(2); }
const EMAIL = process.env.ADMIN_EMAIL || 'admin@auraboot.com';
const PASSWORD = process.env.ADMIN_PASSWORD || 'Test2026x';

const browser = await chromium.launch();
const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
const res = await ctx.request.post(`${BASE}/login`, {
  form: { identifier: EMAIL, password: PASSWORD, channelCode: 'email_password', redirectTo: '/' },
  maxRedirects: 0,
});
const jwt = await ctx.request.post(`${BASE}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } })
  .then(r => r.json()).then(j => j?.data?.jwt).catch(() => undefined);
const page = await ctx.newPage();
await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
if (jwt) await page.evaluate((t) => { localStorage.setItem('jwtToken', t); localStorage.setItem('jwt', t); }, jwt);
await ctx.storageState({ path: OUT });
const st = await ctx.storageState();
const ok = st.cookies.some(c => c.name === '__session');
console.log(`[gen-storage] login ${res.status()} __session=${ok} -> ${OUT}`);
await browser.close();
process.exit(ok ? 0 : 1);
