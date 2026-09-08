import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [grafanaUrl, traceId, artifacts, playwrightModule] = process.argv.slice(2);
if (![grafanaUrl, traceId, artifacts, playwrightModule].every(Boolean)) {
  throw new Error('usage: observability-grafana-browser.mjs <grafana-url> <trace-id> <artifacts> <playwright-module>');
}

const playwright = await import(pathToFileURL(playwrightModule).href);
const chromium = playwright.chromium ?? playwright.default?.chromium;
if (!chromium) throw new Error(`Playwright module does not export chromium: ${playwrightModule}`);
fs.mkdirSync(artifacts, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 960 } });

try {
  await page.goto(`${grafanaUrl}/login`, { waitUntil: 'domcontentloaded' });
  const username = page.locator('input[name="user"]');
  if (await username.count()) {
    await username.fill('admin');
    await page.locator('input[name="password"]').fill('auraboot-observability-ci');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL(url => !url.pathname.endsWith('/login'));
  }

  const left = {
    datasource: 'loki',
    queries: [{ refId: 'A', expr: `{service="auraboot-application"} |= "${traceId}"`, queryType: 'range' }],
    range: { from: 'now-15m', to: 'now' },
  };
  await page.goto(`${grafanaUrl}/explore?orgId=1&left=${encodeURIComponent(JSON.stringify(left))}`,
    { waitUntil: 'domcontentloaded' });
  await page.getByText(traceId, { exact: false }).first().waitFor({ timeout: 60_000 });
  await page.getByText(traceId, { exact: false }).first().click();

  const traceLink = page.locator(`a[href*="${traceId}"]`).first();
  await traceLink.waitFor({ timeout: 30_000 });
  const sourceHref = await traceLink.getAttribute('href');
  if (!sourceHref) throw new Error('Grafana rendered no derived Tempo link');
  await page.screenshot({ path: path.join(artifacts, 'grafana-loki-derived-trace-link.png'), fullPage: true });

  const sourceUrl = page.url();
  await Promise.all([
    page.waitForURL(url => url.href !== sourceUrl && url.href.includes('tempo'), { timeout: 30_000 }),
    traceLink.click(),
  ]);
  if (!page.url().includes('tempo')) throw new Error(`Grafana did not navigate to Tempo: ${page.url()}`);
  await page.getByText(traceId, { exact: false }).first().waitFor({ timeout: 60_000 });
  await page.screenshot({ path: path.join(artifacts, 'grafana-tempo-trace.png'), fullPage: true });
  fs.writeFileSync(path.join(artifacts, 'grafana-browser-summary.json'), JSON.stringify({
    contractVersion: 1,
    status: 'passed',
    traceId,
    sourceHref,
    finalUrl: page.url(),
    screenshots: ['grafana-loki-derived-trace-link.png', 'grafana-tempo-trace.png'],
  }, null, 2) + '\n');
} catch (error) {
  await page.screenshot({ path: path.join(artifacts, 'grafana-browser-failure.png'), fullPage: true });
  throw error;
} finally {
  await browser.close();
}
