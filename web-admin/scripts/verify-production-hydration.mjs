#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const localWebAdminRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
);

function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith('--')) continue;
    const value = argv[index + 1];
    if (!value || value.startsWith('--')) {
      throw new Error(`missing value for ${key}`);
    }
    result[key.slice(2)] = value;
    index += 1;
  }
  return result;
}

function normalizeBaseUrl(value) {
  if (!value) throw new Error('--base-url is required');
  const parsed = new URL(value);
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error(`unsupported base URL protocol: ${parsed.protocol}`);
  }
  return parsed.toString().replace(/\/$/, '');
}

function playwrightRequire(playwrightRoot) {
  const packageJson = path.join(path.resolve(playwrightRoot), 'package.json');
  if (!fs.existsSync(packageJson)) {
    throw new Error(`Playwright root package.json is missing: ${packageJson}`);
  }
  return createRequire(packageJson);
}

export async function runProductionHydrationSmoke({
  baseUrl,
  playwrightRoot = localWebAdminRoot,
  evidenceDir,
  timeoutMs = 20_000,
}) {
  const normalizedBaseUrl = normalizeBaseUrl(baseUrl);
  const resolvedEvidenceDir = path.resolve(evidenceDir);
  fs.mkdirSync(resolvedEvidenceDir, { recursive: true });
  const require = playwrightRequire(playwrightRoot);
  const { chromium } = require('@playwright/test');
  const summary = {
    schemaVersion: 1,
    baseUrl: normalizedBaseUrl,
    loginUrl: `${normalizedBaseUrl}/login`,
    httpStatus: null,
    passwordToggleBefore: null,
    passwordToggleAfter: null,
    pageErrors: [],
    consoleErrors: [],
    status: 'FAIL',
    error: null,
  };
  const summaryPath = path.join(resolvedEvidenceDir, 'summary.json');
  const screenshotPath = path.join(resolvedEvidenceDir, 'login.png');
  const browser = await chromium.launch({ args: ['--no-proxy-server'] });
  let page;
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    page.on('pageerror', (error) => summary.pageErrors.push(error.message));
    page.on('console', (message) => {
      if (message.type() === 'error') summary.consoleErrors.push(message.text());
    });
    const response = await page.goto(summary.loginUrl, {
      waitUntil: 'domcontentloaded',
      timeout: timeoutMs,
    });
    summary.httpStatus = response?.status() ?? null;
    if (!response || !response.ok()) {
      throw new Error(`login returned HTTP ${summary.httpStatus ?? 'no-response'}`);
    }
    await page.waitForTimeout(250);
    if (summary.pageErrors.length > 0) {
      throw new Error(`browser page errors: ${summary.pageErrors.join(' | ')}`);
    }
    const password = page.locator('#password');
    const toggle = page.locator('[data-testid="login-toggle-password"]');
    await password.waitFor({ state: 'visible', timeout: timeoutMs });
    await toggle.waitFor({ state: 'visible', timeout: timeoutMs });
    summary.passwordToggleBefore = await password.getAttribute('type');
    await toggle.click();
    await page.waitForFunction(
      () => document.querySelector('#password')?.getAttribute('type') === 'text',
      undefined,
      { timeout: timeoutMs },
    );
    summary.passwordToggleAfter = await password.getAttribute('type');
    await page.screenshot({ path: screenshotPath, fullPage: true });
    if (summary.pageErrors.length > 0) {
      throw new Error(`browser page errors: ${summary.pageErrors.join(' | ')}`);
    }
    if (summary.consoleErrors.length > 0) {
      throw new Error(
        `browser console errors: ${summary.consoleErrors.join(' | ')}`,
      );
    }
    if (
      summary.passwordToggleBefore !== 'password' ||
      summary.passwordToggleAfter !== 'text'
    ) {
      throw new Error('login React hydration interaction did not complete');
    }
    summary.status = 'PASS';
    return summary;
  } catch (error) {
    summary.error =
      summary.pageErrors.length > 0
        ? `browser page errors: ${summary.pageErrors.join(' | ')}`
        : error instanceof Error
          ? error.message
          : String(error);
    await page?.screenshot({ path: screenshotPath, fullPage: true }).catch(() => {});
    throw Object.assign(new Error(summary.error), { hydrationSummary: summary });
  } finally {
    fs.writeFileSync(summaryPath, `${JSON.stringify(summary, null, 2)}\n`);
    await browser.close();
  }
}

const isMain = process.argv[1]
  ? path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
  : false;

if (isMain) {
  const args = parseArgs(process.argv.slice(2));
  const evidenceDir =
    args['evidence-dir'] ||
    process.env.WEB_ADMIN_HYDRATION_EVIDENCE_DIR ||
    path.resolve('artifacts/web-admin-production-hydration');
  try {
    const summary = await runProductionHydrationSmoke({
      baseUrl: args['base-url'] || process.env.WEB_ADMIN_BASE_URL,
      playwrightRoot:
        args['playwright-root'] ||
        process.env.WEB_ADMIN_PLAYWRIGHT_NODE_ROOT ||
        localWebAdminRoot,
      evidenceDir,
      timeoutMs: Number(args['timeout-ms'] || 20_000),
    });
    console.log(
      JSON.stringify({
        status: summary.status,
        baseUrl: summary.baseUrl,
        httpStatus: summary.httpStatus,
        interaction: 'login-password-visibility-toggle',
        pageErrorCount: summary.pageErrors.length,
        consoleErrorCount: summary.consoleErrors.length,
      }),
    );
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
