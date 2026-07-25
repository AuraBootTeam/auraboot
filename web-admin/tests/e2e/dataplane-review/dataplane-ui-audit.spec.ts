/**
 * Data-plane review — UI reachability + render audit.
 *
 * For each data-plane surface this drives the REAL page with a REAL backend and
 * records four things: did the route resolve, did the page body render, did the
 * console report errors, and does the visible text leak raw codes.
 *
 * It deliberately does NOT assert pass/fail per page — it emits a machine-readable
 * line per surface so the reviewer can classify. Screenshots go to
 * test-results/dataplane-review/ for visual review.
 */
import { expect, test } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';

const OUT = 'test-results/dataplane-review';

type Surface = { id: string; path: string; capability: string };

const SURFACES: Surface[] = [
  { id: 'named-query-list', path: '/meta/named-queries', capability: 'NamedQuery' },
  { id: 'named-query-new', path: '/meta/named-queries/new', capability: 'NamedQuery + 查询构建器' },
  { id: 'query-builder', path: '/query-builder', capability: '查询构建器' },
  { id: 'report-designer', path: '/report-designer', capability: '报表设计器' },
  { id: 'report-schedules', path: '/report-schedules', capability: '定时投递' },
  { id: 'list-page', path: '/p/e2et_order', capability: '筛选预设 + SavedView + 打印' },
];

// "Page Unavailable" / unmatched-route boundaries the shell renders.
const UNAVAILABLE = /Page Unavailable|Menu configuration not found|Page not found|404/i;
// Raw-code leakage: an i18n key or a bare snake_case token surfacing as user text.
const RAW_CODE = /\$i18n:|\bundefined\b|\[object Object\]/;

test.describe('Data-plane review — UI audit', () => {
  test.beforeAll(() => mkdirSync(OUT, { recursive: true }));

  for (const s of SURFACES) {
    test(`audit ${s.id} (${s.capability})`, async ({ page }) => {
      test.setTimeout(90_000);
      const consoleErrors: string[] = [];
      page.on('console', (m) => {
        if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 200));
      });
      const failedRequests: string[] = [];
      page.on('response', (r) => {
        if (r.status() >= 400 && r.url().includes('/api/'))
          failedRequests.push(`${r.status()} ${new URL(r.url()).pathname}`);
      });

      await page.goto(s.path, { waitUntil: 'domcontentloaded' });
      await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {});

      const main = page.locator('main').first();
      const hasMain = await main.count();
      const bodyText = (await page.locator('body').innerText().catch(() => '')) ?? '';

      const unavailable = UNAVAILABLE.test(bodyText);
      const rawCode = RAW_CODE.test(bodyText);
      // Interactive affordances actually present on the page.
      const buttons = await page.getByRole('button').count();
      const inputs = await page.locator('input, select, textarea').count();
      const tables = await page.locator('table, [role="table"], [role="grid"]').count();

      await page.screenshot({ path: `${OUT}/${s.id}.png`, fullPage: false });

      const record = {
        id: s.id,
        capability: s.capability,
        path: s.path,
        finalUrl: new URL(page.url()).pathname,
        reachable: !unavailable,
        hasMain: hasMain > 0,
        buttons,
        inputs,
        tables,
        rawCodeLeak: rawCode,
        consoleErrors: consoleErrors.slice(0, 5),
        failedApiRequests: [...new Set(failedRequests)].slice(0, 8),
        firstText: bodyText.replace(/\s+/g, ' ').slice(0, 180),
      };
      writeFileSync(`${OUT}/${s.id}.json`, JSON.stringify(record, null, 2));
      // eslint-disable-next-line no-console
      console.log(`[audit] ${JSON.stringify(record)}`);

      // The only hard assertion: the app shell rendered at all. Everything else is
      // recorded for classification rather than silently collapsed into a pass.
      expect(hasMain, `${s.path}: app shell should render a <main>`).toBeGreaterThan(0);
    });
  }
});
