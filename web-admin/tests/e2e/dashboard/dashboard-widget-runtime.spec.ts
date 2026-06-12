/**
 * Dashboard Widget Runtime E2E Tests
 *
 * Covers representative Dashboard Viewer runtime semantics that are not proven
 * by designer saved-payload readback tests:
 * - static data widgets render computed values
 * - content/embed widgets pass authored props into real DOM nodes
 * - workbench widgets resolve through the shared runtime registry
 *
 * API calls in this spec are deterministic fixture setup/cleanup only. Product
 * evidence comes from opening the published `/dashboards/view/:code` route in
 * the browser and asserting the rendered DashboardViewer DOM.
 */

import { test, expect } from '../../fixtures';
import type { APIResponse, Page, Locator } from '@playwright/test';

type DashboardWidgetFixture = {
  id: string;
  type: string;
  title: string;
  x: number;
  y: number;
  w: number;
  h: number;
  config: Record<string, unknown>;
};

type CreatedDashboard = {
  pid: string;
  code: string;
  title: string;
};

const SVG_DATA_URL =
  'data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20width%3D%22480%22%20height%3D%22200%22%20viewBox%3D%220%200%20480%20200%22%3E%3Crect%20width%3D%22480%22%20height%3D%22200%22%20fill%3D%22%23eef6ff%22%2F%3E%3Ctext%20x%3D%22240%22%20y%3D%22108%22%20font-size%3D%2232%22%20text-anchor%3D%22middle%22%20fill%3D%22%231d4ed8%22%3ERuntime%20Image%3C%2Ftext%3E%3C%2Fsvg%3E';

function futureIsoDate(daysFromNow: number): string {
  return new Date(Date.now() + daysFromNow * 24 * 60 * 60 * 1000).toISOString();
}

function runtimeWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-progress',
      type: 'smart-progress',
      title: 'Runtime Progress',
      x: 0,
      y: 0,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Progress',
        dataSource: {
          type: 'static',
          staticData: [{ value: 75 }],
          metrics: [{ field: 'value', aggregation: 'sum', alias: 'value' }],
        },
        visualization: {
          target: 100,
          format: 'percent',
          shape: 'bar',
        },
      },
    },
    {
      id: 'runtime-leaderboard',
      type: 'smart-leaderboard',
      title: 'Runtime Leaderboard',
      x: 3,
      y: 0,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Leaderboard',
        dataSource: {
          type: 'static',
          staticData: [
            { region: 'North Zone', score: 9800 },
            { region: 'South Zone', score: 7200 },
            { region: 'West Zone', score: 4100 },
          ],
          dimensions: ['region'],
          metrics: [{ field: 'score', aggregation: 'sum', alias: 'score' }],
        },
        visualization: {
          rankField: 'region',
          valueField: 'score',
          maxItems: 3,
        },
      },
    },
    {
      id: 'runtime-rich-text',
      type: 'smart-rich-text',
      title: 'Runtime Rich Text Card',
      x: 6,
      y: 0,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Rich Text Card',
        dataSource: { type: 'static', staticData: [] },
        visualization: {
          format: 'html',
          content: '<h2>Runtime Rich Text</h2><p>Sanitized semantic content</p>',
        },
      },
    },
    {
      id: 'runtime-image',
      type: 'smart-image',
      title: 'Runtime Image',
      x: 9,
      y: 0,
      w: 3,
      h: 3,
      config: {
        title: 'Runtime Image',
        dataSource: { type: 'static', staticData: [] },
        visualization: {
          src: SVG_DATA_URL,
          alt: 'Runtime SVG Image',
          objectFit: 'contain',
        },
      },
    },
    {
      id: 'runtime-iframe',
      type: 'smart-iframe',
      title: 'Runtime Frame',
      x: 0,
      y: 3,
      w: 4,
      h: 3,
      config: {
        title: 'Runtime Frame',
        dataSource: { type: 'static', staticData: [] },
        visualization: {
          src: 'about:blank',
        },
      },
    },
    {
      id: 'runtime-countdown',
      type: 'smart-countdown',
      title: 'Runtime Countdown',
      x: 4,
      y: 3,
      w: 4,
      h: 3,
      config: {
        title: 'Runtime Countdown',
        dataSource: { type: 'static', staticData: [] },
        visualization: {
          targetDate: futureIsoDate(7),
          format: 'full',
          labels: {
            days: 'Days',
            hours: 'Hours',
            minutes: 'Minutes',
            seconds: 'Seconds',
          },
        },
      },
    },
    {
      id: 'runtime-stats-row',
      type: 'smart-stats-row',
      title: 'Runtime Stats Row',
      x: 0,
      y: 6,
      w: 12,
      h: 2,
      config: {
        title: 'Runtime Stats Row',
        dataSource: { type: 'static' },
      },
    },
    {
      id: 'runtime-stats-card',
      type: 'smart-stats-card',
      title: 'Runtime Stats Card',
      x: 0,
      y: 8,
      w: 3,
      h: 2,
      config: {
        title: 'Runtime Stats Card',
        dataSource: { type: 'static' },
        visualization: {
          statKey: 'inbox_pending',
        },
      },
    },
  ];
}

function quickNoteWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-quick-note',
      type: 'smart-quick-note',
      title: 'Runtime Quick Note',
      x: 0,
      y: 0,
      w: 4,
      h: 3,
      config: {
        title: 'Runtime Quick Note',
        dataSource: { type: 'static' },
      },
    },
  ];
}

function shortcutWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-shortcuts',
      type: 'smart-shortcuts',
      title: 'Runtime Shortcuts',
      x: 0,
      y: 0,
      w: 6,
      h: 2,
      config: {
        title: 'Runtime Shortcuts',
        dataSource: { type: 'static' },
        shortcuts: [
          {
            label: 'Runtime Dashboards',
            icon: '>',
            path: '/dashboards',
            color: 'bg-blue-50',
          },
        ],
      },
    },
  ];
}

function advancedRuntimeWidgets(): DashboardWidgetFixture[] {
  return [
    {
      id: 'runtime-wordcloud',
      type: 'smart-wordcloud-chart',
      title: 'Runtime Word Cloud',
      x: 0,
      y: 0,
      w: 4,
      h: 4,
      config: {
        title: 'Runtime Word Cloud',
        dataSource: {
          type: 'static',
          staticData: [
            { keyword: 'Runtime', weight: 42 },
            { keyword: 'Coverage', weight: 21 },
          ],
          dimensions: ['keyword'],
          metrics: [{ field: 'weight', aggregation: 'sum', alias: 'weight' }],
        },
        visualization: {
          colorTheme: 'brand',
          gridSize: 6,
        },
      },
    },
    {
      id: 'runtime-combo',
      type: 'smart-combo-chart',
      title: 'Runtime Combo',
      x: 4,
      y: 0,
      w: 4,
      h: 4,
      config: {
        title: 'Runtime Combo',
        dataSource: {
          type: 'static',
          staticData: [
            { quarter: 'Q1', revenue: 120, conversion: 30 },
            { quarter: 'Q2', revenue: 180, conversion: 42 },
          ],
          dimensions: ['quarter'],
          metrics: [
            { field: 'revenue', aggregation: 'sum', alias: 'revenue' },
            { field: 'conversion', aggregation: 'sum', alias: 'conversion' },
          ],
        },
        visualization: {
          seriesConfig: [
            { metricIndex: 0, chartType: 'bar', yAxisIndex: 0, showLabel: true },
            { metricIndex: 1, chartType: 'line', yAxisIndex: 1, showLabel: true },
          ],
          yAxisLeft: { name: 'Revenue' },
          yAxisRight: { name: 'Conversion' },
        },
      },
    },
    {
      id: 'runtime-nps',
      type: 'smart-nps-chart',
      title: 'Runtime NPS',
      x: 8,
      y: 0,
      w: 4,
      h: 4,
      config: {
        title: 'Runtime NPS',
        dataSource: {
          type: 'static',
          staticData: [{ score: 10 }, { score: 9 }, { score: 8 }, { score: 4 }],
          dimensions: ['score'],
          metrics: [{ field: 'score', aggregation: 'sum', alias: 'score' }],
        },
        visualization: {
          scoreField: 'score',
          showLegend: true,
          ringWidth: 28,
        },
      },
    },
    {
      id: 'runtime-gallery',
      type: 'smart-gallery',
      title: 'Runtime Gallery',
      x: 0,
      y: 4,
      w: 6,
      h: 4,
      config: {
        title: 'Runtime Gallery',
        dataSource: { type: 'static' },
        visualization: {
          staticItems: [
            {
              image: SVG_DATA_URL,
              title: 'Runtime Gallery Alpha',
              description: 'Gallery item from authored visualization props',
            },
          ],
          columns: 2,
          imageFit: 'contain',
        },
      },
    },
    {
      id: 'runtime-kanban',
      type: 'smart-kanban',
      title: 'Runtime Kanban',
      x: 6,
      y: 4,
      w: 6,
      h: 4,
      config: {
        title: 'Runtime Kanban',
        dataSource: {
          type: 'static',
          staticData: [
            {
              id: 'card-a',
              stage: 'Backlog',
              title: 'Runtime Card A',
              description: 'Backlog card rendered from static rows',
            },
            {
              id: 'card-b',
              stage: 'Done',
              title: 'Runtime Card B',
              description: 'Done card rendered from static rows',
            },
          ],
          dimensions: ['stage'],
          metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
        },
        visualization: {
          groupField: 'stage',
          titleField: 'title',
          descriptionField: 'description',
          columnOrder: ['Backlog', 'Done'],
          showCount: true,
        },
      },
    },
  ];
}

async function parseJsonResponse<T>(response: APIResponse, context: string): Promise<T> {
  const text = await response.text();
  expect(response.ok(), `${context} failed: status=${response.status()} body=${text}`).toBe(true);
  return JSON.parse(text) as T;
}

async function createPublishedDashboard(
  page: Page,
  widgets: DashboardWidgetFixture[] = runtimeWidgets(),
  titlePrefix = 'Runtime Widget Matrix',
): Promise<CreatedDashboard> {
  const title = `${titlePrefix} ${Date.now()}`;
  const createResponse = await page.request.post('/api/dashboards', {
    data: {
      title,
      scope: 'global',
      layoutConfig: {
        columns: 12,
        rowHeight: 96,
        gap: 12,
        compactType: 'vertical',
      },
      widgets,
    },
  });
  const createBody = await parseJsonResponse<{ data?: { pid?: string; code?: string } }>(
    createResponse,
    'create dashboard',
  );
  const pid = createBody.data?.pid;
  const code = createBody.data?.code;
  expect(pid, 'created dashboard pid').toBeTruthy();
  expect(code, 'created dashboard code').toBeTruthy();

  const publishResponse = await page.request.post(`/api/dashboards/${pid}/publish`);
  await parseJsonResponse(publishResponse, 'publish dashboard');

  return { pid: pid!, code: code!, title };
}

async function cleanupDashboard(page: Page, pid?: string): Promise<void> {
  if (!pid) return;
  await page.request.post(`/api/dashboards/${pid}/unpublish`).catch(() => undefined);
  await page.request.delete(`/api/dashboards/${pid}`).catch(() => undefined);
}

async function expectRuntimeBlock(page: Page, id: string, type: string): Promise<Locator> {
  const block = page.getByTestId(`dashboard-block-${id}`);
  await block.scrollIntoViewIfNeeded();
  await expect(block).toBeVisible({ timeout: 10_000 });
  await expect(block.locator(`[data-widget-type="${type}"]`)).toBeVisible({ timeout: 10_000 });
  await expect(block).not.toContainText('Unknown widget');
  return block;
}

async function expectRenderedChartSurface(block: Locator, label: string): Promise<void> {
  const surface = block.locator(
    '[data-widget-type] .echarts-for-react, [data-widget-type] canvas, [data-widget-type] svg',
  );
  await expect(surface.first(), `${label} should render an ECharts surface`).toBeVisible({
    timeout: 10_000,
  });
  const largestSurface = await surface.evaluateAll((nodes) =>
    nodes.reduce(
      (largest, node) => {
        const rect = node.getBoundingClientRect();
        const area = rect.width * rect.height;
        return area > largest.area
          ? { area, width: rect.width, height: rect.height }
          : largest;
      },
      { area: 0, width: 0, height: 0 },
    ),
  );
  expect(largestSurface.width, `${label} chart width`).toBeGreaterThan(40);
  expect(largestSurface.height, `${label} chart height`).toBeGreaterThan(40);
  await expect(block, `${label} should exit loading state`).not.toContainText('Loading...', {
    timeout: 10_000,
  });
  await expect(block, `${label} should not show chart error`).not.toContainText(
    /Failed to load|Please configure|No data/,
  );
}

async function currentUserNoteContent(page: Page): Promise<string> {
  const response = await page.request.get('/api/user-notes');
  const body = await parseJsonResponse<{ data?: { content?: string | null } }>(
    response,
    'read user note',
  );
  return body.data?.content ?? '';
}

async function saveQuickNoteThroughUi(page: Page, content: string): Promise<void> {
  const textarea = page.getByTestId('quick-note-textarea');
  await textarea.scrollIntoViewIfNeeded();
  await expect(textarea).toBeVisible({ timeout: 10_000 });

  const saveResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/user-notes') &&
      response.request().method() === 'PUT' &&
      response.status() === 200,
    { timeout: 10_000 },
  );
  await textarea.fill(content);
  await textarea.evaluate((node: HTMLTextAreaElement) => node.blur());
  await saveResponse;
}

test.describe('Dashboard Widget Runtime Semantics', () => {
  test('DWR-001: published viewer renders representative widget runtime semantics', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;
    try {
      dashboard = await createPublishedDashboard(page);

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      const progress = await expectRuntimeBlock(page, 'runtime-progress', 'smart-progress');
      await expect(progress).toContainText('75%');

      const leaderboard = await expectRuntimeBlock(
        page,
        'runtime-leaderboard',
        'smart-leaderboard',
      );
      await expect(leaderboard).toContainText('North Zone');
      await expect(leaderboard).toContainText('9.8K');
      await expect(leaderboard).toContainText('South Zone');

      const richText = await expectRuntimeBlock(page, 'runtime-rich-text', 'smart-rich-text');
      await expect(richText.getByRole('heading', { name: 'Runtime Rich Text' })).toBeVisible();
      await expect(richText).toContainText('Sanitized semantic content');

      const imageBlock = await expectRuntimeBlock(page, 'runtime-image', 'smart-image');
      const image = imageBlock.locator('img[alt="Runtime SVG Image"]');
      await expect(image).toBeVisible();
      await expect(image).toHaveAttribute('src', /data:image\/svg\+xml/);
      await expect(image).toHaveCSS('object-fit', 'contain');

      const iframeBlock = await expectRuntimeBlock(page, 'runtime-iframe', 'smart-iframe');
      const iframe = iframeBlock.locator('iframe[title="Runtime Frame"]');
      await expect(iframe).toBeVisible();
      await expect(iframe).toHaveAttribute('src', 'about:blank');

      const countdown = await expectRuntimeBlock(page, 'runtime-countdown', 'smart-countdown');
      await expect(countdown).toContainText('Runtime Countdown');
      await expect(countdown).toContainText('Days');
      await expect(countdown).toContainText('Hours');

      const statsRow = await expectRuntimeBlock(page, 'runtime-stats-row', 'smart-stats-row');
      await expect(statsRow.getByTestId('stats-row')).toBeVisible();
      await expect(statsRow.locator('[data-testid^="stat-card-"]')).toHaveCount(4);

      const statsCard = await expectRuntimeBlock(page, 'runtime-stats-card', 'smart-stats-card');
      const singleCard = statsCard.getByTestId('stat-card-inbox_pending');
      await expect(singleCard).toBeVisible();
      await expect(singleCard).not.toContainText('—', { timeout: 10_000 });
    } finally {
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-002: quick-note widget persists note through viewer interaction', async ({ page }) => {
    const originalNote = await currentUserNoteContent(page);
    const noteContent = `Quick note runtime ${Date.now()}`;
    let dashboard: CreatedDashboard | undefined;

    try {
      dashboard = await createPublishedDashboard(
        page,
        quickNoteWidgets(),
        'Runtime Quick Note Matrix',
      );

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      await expectRuntimeBlock(page, 'runtime-quick-note', 'smart-quick-note');
      await saveQuickNoteThroughUi(page, noteContent);
      await expect(page.getByText(/刚刚保存|Just saved|Last saved/)).toBeVisible({
        timeout: 10_000,
      });

      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('quick-note-textarea')).toHaveValue(noteContent, {
        timeout: 10_000,
      });
    } finally {
      if (dashboard) {
        await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' }).catch(
          () => undefined,
        );
        await saveQuickNoteThroughUi(page, originalNote).catch(() => undefined);
      }
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-003: shortcuts widget navigates from published viewer interaction', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;

    try {
      dashboard = await createPublishedDashboard(
        page,
        shortcutWidgets(),
        'Runtime Shortcuts Matrix',
      );

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      const shortcuts = await expectRuntimeBlock(page, 'runtime-shortcuts', 'smart-shortcuts');
      await expect(shortcuts.getByTestId('shortcuts-list')).toBeVisible();

      const shortcut = shortcuts.getByRole('link', { name: /Runtime Dashboards/ });
      await expect(shortcut).toHaveAttribute('href', /\/dashboards$/);

      await shortcut.click();
      await expect(page).toHaveURL(/\/dashboards$/);
    } finally {
      await cleanupDashboard(page, dashboard?.pid);
    }
  });

  test('DWR-004: published viewer renders advanced chart and view widgets', async ({ page }) => {
    let dashboard: CreatedDashboard | undefined;

    try {
      dashboard = await createPublishedDashboard(
        page,
        advancedRuntimeWidgets(),
        'Runtime Advanced Widget Matrix',
      );

      await page.goto(`/dashboards/view/${dashboard.code}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: dashboard.title })).toBeVisible({
        timeout: 15_000,
      });

      const wordCloud = await expectRuntimeBlock(
        page,
        'runtime-wordcloud',
        'smart-wordcloud-chart',
      );
      await expectRenderedChartSurface(wordCloud, 'word cloud');

      const combo = await expectRuntimeBlock(page, 'runtime-combo', 'smart-combo-chart');
      await expectRenderedChartSurface(combo, 'combo chart');

      const nps = await expectRuntimeBlock(page, 'runtime-nps', 'smart-nps-chart');
      await expectRenderedChartSurface(nps, 'NPS chart');

      const gallery = await expectRuntimeBlock(page, 'runtime-gallery', 'smart-gallery');
      await expect(gallery).toContainText('Runtime Gallery Alpha');
      await expect(gallery).toContainText('Gallery item from authored visualization props');
      await expect(gallery.locator('img[alt="Runtime Gallery Alpha"]')).toBeVisible();

      const kanban = await expectRuntimeBlock(page, 'runtime-kanban', 'smart-kanban');
      await expect(kanban).toContainText('Backlog');
      await expect(kanban).toContainText('Done');
      await expect(kanban).toContainText('Runtime Card A');
      await expect(kanban).toContainText('Runtime Card B');
    } finally {
      await cleanupDashboard(page, dashboard?.pid);
    }
  });
});
