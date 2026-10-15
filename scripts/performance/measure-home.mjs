#!/usr/bin/env node
import { createRequire } from 'node:module';
import { writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { installHomeReadiness } from './home-readiness.mjs';

const root = process.env.PERF_SOURCE_ROOT;
const base = process.env.PERF_WEB_BASE;
const output = process.env.PERF_OUTPUT;
const samples = Number(process.env.PERF_SAMPLES || 30);
const cacheMode = process.env.PERF_BROWSER_CACHE || 'warm';
if (!root || !base || !output || !Number.isInteger(samples) || samples < 20 || !['warm', 'cold'].includes(cacheMode)) {
  throw new Error('Explicit source/base/output, at least 20 samples and warm|cold cache mode required');
}
const require = createRequire(fileURLToPath(new URL('../../web-admin/package.json', import.meta.url)));
const { chromium, expect } = require('@playwright/test');
const browser = await chromium.launch({ headless: true });
const report = {
  schemaVersion: 2,
  commit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
  base, at: new Date().toISOString(), mode: process.env.PERF_SERVING_MODE || 'unspecified',
  conditions: { viewport: '1440x1000', locale: 'zh-CN', samples, warmup: 1, concurrency: 1, browserCache: cacheMode, tracing: process.env.PERF_TRACE_MODE || 'unspecified' },
  readiness: 'browser MutationObserver + animation frame: empty inbox, shortcuts, numeric zero stats; critical API success checked independently',
  runs: [],
};
const createContext = async () => {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 }, storageState: root + '/web-admin/tests/storage/admin.json' });
  await context.addInitScript(installHomeReadiness);
  return context;
};
let context;
try {
  for (let i = 0; i <= samples; i++) {
    if (!context || cacheMode === 'cold') { await context?.close(); context = await createContext(); }
    const page = context.pages()[0] || await context.newPage();
    const requests = [], resourcePaths = [], responses = [], errors = [];
    const onRequest = request => { const path = new URL(request.url()).pathname; resourcePaths.push(path); if (path.startsWith('/api/')) requests.push(path); };
    const onError = error => errors.push(error.message);
    const onResponse = response => {
      const path = new URL(response.url()).pathname;
      if (['/api/inbox', '/api/workbench/stats', '/api/user-engagement'].includes(path)) responses.push((async () => ({ path, status: response.status(), code: (await response.json()).code, timing: response.request().timing(), traceId: response.headers()['x-trace-id'] }))());
    };
    page.on('request', onRequest); page.on('response', onResponse); page.on('pageerror', onError);
    const response = await page.goto(base + '/home', { waitUntil: 'domcontentloaded' });
    if (!response?.ok()) throw new Error(`Home HTTP ${response?.status()}`);
    await page.waitForFunction(() => window.__perfHome?.ready !== undefined, undefined, { timeout: 60_000 });
    await expect(page.getByText('暂无待办事项', { exact: true })).toBeVisible();
    const observed = await page.evaluate(() => ({ marks: window.__perfHome, readAtMs: performance.now(), navigation: performance.getEntriesByType('navigation')[0].toJSON() }));
    const criticalResponses = await Promise.all(responses);
    for (const path of ['/api/inbox', '/api/workbench/stats', '/api/user-engagement']) {
      if (!criticalResponses.some(r => r.path === path && r.status === 200 && r.code === '0')) throw new Error(`Missing successful critical API ${path}`);
    }
    if (errors.length) throw new Error(errors.join('\n'));
    if (resourcePaths.some(path => path === '/@vite/client' || path.startsWith('/@fs/'))) throw new Error('Vite dev must not be called in production');
    const traceparent = await page.evaluate(() => document.querySelector('meta[name=traceparent]')?.getAttribute('content') ?? null);
    report.runs.push({ i, warmup: i === 0, structureMs: observed.marks.structure, readyMs: observed.marks.ready, ...observed, criticalResponses, requests, resourcePaths, traceparent });
    page.off('request', onRequest); page.off('response', onResponse); page.off('pageerror', onError);
    writeFileSync(output, JSON.stringify({ ...report, status: 'running' }, null, 2));
    console.log(JSON.stringify({ i, structureMs: observed.marks.structure, readyMs: observed.marks.ready, observerReadAtMs: observed.readAtMs }));
    if (i === samples) await page.screenshot({ path: output.replace(/\.json$/, '.png'), fullPage: true });
  }
  const percentile = (key, q) => report.runs.filter(r => !r.warmup).map(r => r[key]).sort((a, b) => a - b)[Math.ceil(samples * q) - 1];
  report.summary = Object.fromEntries(['structureMs', 'readyMs'].map(key => [key, { p50: percentile(key, .5), p95: percentile(key, .95) }]));
  writeFileSync(output, JSON.stringify({ ...report, status: 'measured' }, null, 2));
} finally { await browser.close(); }
