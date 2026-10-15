#!/usr/bin/env node
import { createRequire } from 'node:module';
import { writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Execute the same driver against frozen control and candidate, sequentially.
const root = process.env.PERF_SOURCE_ROOT;
const base = process.env.PERF_WEB_BASE;
const output = process.env.PERF_OUTPUT;
if (!root || !base || !output) throw new Error('PERF_SOURCE_ROOT, PERF_WEB_BASE and PERF_OUTPUT required');
const require = createRequire(fileURLToPath(new URL('../../web-admin/package.json', import.meta.url)));
const { chromium, expect } = require('@playwright/test');
const browser = await chromium.launch({ headless: true });
const report = {
  commit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
  base, at: new Date().toISOString(), mode: 'host-first Vite dev, warm browser cache',
  conditions: { viewport: '1440x1000', locale: 'zh-CN', samples: 10, warmup: 1, concurrency: 1, tracingSampleRate: 1 },
  readiness: 'empty inbox + shortcut row + pending/urgent numeric zero',
  runs: [],
};
try {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 }, storageState: root + '/web-admin/tests/storage/admin.json' });
  const page = await context.newPage();
  for (let i = 0; i < 11; i++) {
    const requests = [];
    const listener = request => {
      const url = new URL(request.url());
      if (url.pathname.startsWith('/api/')) requests.push(url.pathname);
    };
    page.on('request', listener);
    const start = performance.now();
    await page.goto(base + '/home');
    await expect(page.getByTestId('workbench-open-in-dashboard')).toBeVisible();
    await expect(page.locator('.react-grid-item').first()).toBeVisible();
    const structureMs = performance.now() - start;
    await expect(page.getByText('暂无待办事项', { exact: true })).toBeVisible({ timeout: 60_000 });
    await expect(page.getByTestId('shortcut-row').first()).toBeVisible();
    await expect(page.getByTestId('stat-card-inbox_pending').getByText('0', { exact: true })).toBeVisible();
    await expect(page.getByTestId('stat-card-inbox_urgent').getByText('0', { exact: true })).toBeVisible();
    const readyMs = performance.now() - start;
    const navigation = await page.evaluate(() => performance.getEntriesByType('navigation')[0].toJSON());
    const spanParent = await page.locator('meta[name=traceparent]').getAttribute('content');
    report.runs.push({ i, warmup: i === 0, structureMs, readyMs, requests, navigation, traceparent: spanParent });
    page.off('request', listener);
    writeFileSync(output, JSON.stringify({ ...report, status: 'running' }, null, 2));
    console.log(JSON.stringify({ i, structureMs, readyMs, workbenchConfigRequests: requests.filter(p => p === '/api/dashboards/workbench').length }));
  }
  await page.screenshot({ path: output.replace(/\.json$/, '.png'), fullPage: true });
  const percentile = (key, q) => report.runs.filter(r => !r.warmup).map(r => r[key]).sort((a, b) => a - b)[Math.ceil(10 * q) - 1];
  report.summary = Object.fromEntries(['structureMs', 'readyMs'].map(key => [key, { p50: percentile(key, .5), p95: percentile(key, .95) }]));
  report.status = 'measured';
  writeFileSync(output, JSON.stringify(report, null, 2));
} finally {
  await browser.close();
}
