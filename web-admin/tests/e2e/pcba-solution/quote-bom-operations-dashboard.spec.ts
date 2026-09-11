import type { Browser, Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { ensureSidebarExpanded, uniqueId } from '../helpers';
import {
  ensureQuoteRoleUser,
  makeQuoteRoleUser,
  openQuoteRolePage,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

const DASHBOARD_PATH = '/dashboards/view/qo_tool_admin_dashboard';
const HOME_PATH = '/home';
const TREND_QUERIES = ['qo_tool_quote_weekly_trend', 'qo_tool_bom_weekly_trend'] as const;
const PEOPLE_QUERIES = [
  'qo_tool_quote_people_workload',
  'qo_tool_bom_people_workload',
] as const;
const PLATFORM_BASE_CAPABILITY = 'sys.cap.member_base';

async function queryRecords(page: Page, code: string): Promise<Record<string, unknown>[]> {
  const response = await page.request.get(
    `/api/datasource/list?datasourceId=nq:${encodeURIComponent(code)}&format=records&maxItems=100`,
  );
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok(),
    `${code} HTTP ${response.status()}: ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
  const records = (body as any)?.data?.records;
  expect(Array.isArray(records), `${code} should return records`).toBe(true);
  return records as Record<string, unknown>[];
}

async function expectFourCharts(page: Page, path: string): Promise<void> {
  const expectedSeries: number[][] = [];
  const trends = TREND_QUERIES.map((code) => page.waitForResponse((response) => {
    if (response.request().method() !== 'POST') return false;
    try { return response.request().postDataJSON()?.queryCode === code; }
    catch { return false; }
  }));
  await page.goto(path, { waitUntil: 'domcontentloaded' });
  for (const pending of trends) {
    const response = await pending;
    expect(response.ok()).toBe(true);
    const payload = response.request().postDataJSON();
    const body = await response.json();
    const rows = body.data.rows;
    expect(rows).toHaveLength(12);
    expectedSeries.push(rows.map((row: any) => Number(row.quote_count ?? row.bom_count)));
    const labels = rows.map((row: any) => String(row.week_label));
    expect(labels, 'Chart aggregate results must be chronological, not the inner source query alone').toEqual([...labels].sort());
    expect(payload.orderBy).toEqual([{ field: 'week_label', direction: 'asc' }]);
    const dates = labels.map((label: string) => {
      expect(label).toMatch(/^\d{4}-\d{2}-\d{2}(（本周）)?$/);
      return Date.parse(label.slice(0, 10));
    });
    for (let i = 1; i < dates.length; i++) expect(dates[i] - dates[i - 1]).toBe(7 * 86400000);
    expect(labels[11]).toContain('（本周）');
  }
  await expect(page.locator('canvas').first()).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('canvas')).toHaveCount(4);
  for (let index = 0; index < expectedSeries.length; index++) {
    const chart = page.locator('[_echarts_instance_]').nth(index);
    // Inspect the actual canvas: a populated option or display list alone can
    // pass while duplicate zrender Path classes silently suppress all ink.
    await expect.poll(async () => chart.evaluate((element) => {
      let coloredPixels = 0;
      for (const canvas of element.querySelectorAll('canvas')) {
        const context = canvas.getContext('2d');
        if (!context) throw new Error('Trend chart must expose a 2D canvas');
        const { data } = context.getImageData(0, 0, canvas.width, canvas.height);
        for (let i = 0; i < data.length; i += 4) {
          if (data[i + 2] > data[i] + 40 && data[i + 2] > data[i + 1] + 30 && data[i + 3] > 100) coloredPixels++;
        }
      }
      return coloredPixels;
    }), { message: `${path} trend ${index} must paint its blue line, including zero-valued weeks` }).toBeGreaterThan(100);
    // Hover the current week and compare the user-visible tooltip to the real
    // chart response, rather than accepting unrelated colored pixels.
    const rendered = await chart.evaluate((element) => {
      const fiberKey = Object.keys(element).find((key) => key.startsWith('__reactFiber'));
      let fiber = (element as any)[fiberKey!];
      while (fiber && !fiber.stateNode?.getEchartsInstance) fiber = fiber.return;
      if (!fiber) throw new Error('ECharts component instance missing');
      const instance = fiber.stateNode.getEchartsInstance();
      const option = instance.getOption();
      return { values: option.series[0].data, points: option.series[0].data.map((value: number, week: number) => instance.convertToPixel({ seriesIndex: 0 }, [week, value])) };
    });
    expect(rendered.values).toEqual(expectedSeries[index]);
    const samples = rendered.points.slice(1).flatMap((end: number[], i: number) =>
      [0.25, 0.5, 0.75].map((fraction) => [
        rendered.points[i][0] + (end[0] - rendered.points[i][0]) * fraction,
        rendered.points[i][1] + (end[1] - rendered.points[i][1]) * fraction,
      ]));
    await expect.poll(() => chart.evaluate((element, positions: number[][]) => {
      const canvases = [...element.querySelectorAll('canvas')];
      return positions.filter(([x, y]) => canvases.some((canvas) => {
        const scaleX = canvas.width / canvas.clientWidth;
        const scaleY = canvas.height / canvas.clientHeight;
        const left = Math.max(0, Math.round(x * scaleX) - 3);
        const top = Math.max(0, Math.round(y * scaleY) - 3);
        const pixels = canvas.getContext('2d')!.getImageData(left, top, 7, 7).data;
        for (let i = 0; i < pixels.length; i += 4) {
          if (pixels[i + 2] > pixels[i] + 40 && pixels[i + 2] > pixels[i + 1] + 30 && pixels[i + 3] > 100) return true;
        }
        return false;
      })).length;
    }, samples), { message: `${path} trend ${index} must draw every line segment, not only symbols` }).toBe(samples.length);
    const bounds = await chart.boundingBox();
    expect(bounds).not.toBeNull();
    const last = rendered.points[11];
    await page.mouse.move(bounds!.x + last[0], bounds!.y + last[1]);
    await expect(chart).toContainText(`创建数量`);
    await expect(chart).toContainText(String(expectedSeries[index][11]));
    await page.mouse.move(0, 0);
  }
  if (path === '/home') {
    await expect(page.getByRole('heading', { name: '工作台' })).toBeVisible();
  } else {
    await expect(page.getByRole('heading', { name: '报价与 BOM 运营概览' })).toBeVisible();
  }
}

async function createOrdinaryRole(page: Page, roleCode: string): Promise<void> {
  const createResponse = await page.request.post('/api/roles', {
    data: {
      code: roleCode,
      name: `E2E Operations Viewer ${roleCode.slice(-10)}`,
      description: 'E2E tenant member without Quote/BOM analytics administration',
      type: 'custom',
      status: 'active',
      scopeType: 'tenant',
    },
  });
  const createBody = await createResponse.json().catch(() => ({}));
  expect(createResponse.ok(), JSON.stringify(createBody).slice(0, 800)).toBe(true);
  const rolePid = String((createBody as any)?.data?.pid ?? '');
  expect(rolePid).toBeTruthy();

  const grantResponse = await page.request.put(
    `/api/permission/capabilities?rolePid=${encodeURIComponent(rolePid)}`,
    { data: [PLATFORM_BASE_CAPABILITY] },
  );
  expect(grantResponse.ok()).toBe(true);
}

async function withUserPage(
  browser: Browser,
  user: QuoteRoleUser,
  run: (page: Page) => Promise<void>,
): Promise<void> {
  const { context, page } = await openQuoteRolePage(browser, user);
  try {
    await run(page);
  } finally {
    await context.close();
  }
}

test.describe('Quote and BOM operations dashboard @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);

  const uid = uniqueId('qo_ops').replace(/_/g, '-');
  const roleCode = `e2e_qo_ops_viewer_${uid.replace(/[^a-z0-9]+/gi, '_')}`.slice(0, 60);
  const ordinaryUser = makeQuoteRoleUser('qo_ops_viewer', uid, [roleCode]);

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      await createOrdinaryRole(page, roleCode);
      await ensureQuoteRoleUser(page, ordinaryUser);
    } finally {
      await context.close();
    }
  });

  test('admin gets one menu link and the same four charts on dashboard and home', async ({ page }, testInfo) => {
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);
    await expect(page.getByTestId('sidebar').locator(`a[href="${HOME_PATH}"]`)).toHaveCount(1);

    await expectFourCharts(page, '/home');
    await page.screenshot({ path: testInfo.outputPath('home-weekly-order.png'), fullPage: true });
    await expectFourCharts(page, DASHBOARD_PATH);
    await page.screenshot({ path: testInfo.outputPath('dashboard-weekly-order.png'), fullPage: true });

    for (const code of TREND_QUERIES) {
      const records = await queryRecords(page, code);
      expect(records, `${code} should include all twelve Monday-based weeks`).toHaveLength(12);
      expect(
        records.some((row) => Number(row.quote_count ?? row.bom_count ?? 0) > 0),
        `${code} should prove non-zero seeded business data`,
      ).toBe(true);
    }
    for (const code of PEOPLE_QUERIES) {
      const records = await queryRecords(page, code);
      expect(records.length, `${code} should include at least one active creator`).toBeGreaterThan(0);
      expect(
        records.every((row) => Number(row.quote_count ?? row.created_count ?? 0) > 0),
        `${code} admin rows should contain active creators only`,
      ).toBe(true);
    }
  });

  test('ordinary employee sees home contribution without admin menu and personnel data is self-only', async ({
    browser,
  }) => {
    await withUserPage(browser, ordinaryUser, async (page) => {
      await expectFourCharts(page, '/home');
      await ensureSidebarExpanded(page);
      await expect(page.getByTestId('sidebar').locator(`a[href="${HOME_PATH}"]`)).toHaveCount(1);

      for (const code of TREND_QUERIES) {
        const records = await queryRecords(page, code);
        expect(records).toHaveLength(12);
      }
      for (const code of PEOPLE_QUERIES) {
        const records = await queryRecords(page, code);
        expect(records, `${code} ordinary employee must receive only one self row`).toHaveLength(1);
        expect(String(records[0]?.creator_name ?? '')).toBeTruthy();
      }
    });
  });
});
