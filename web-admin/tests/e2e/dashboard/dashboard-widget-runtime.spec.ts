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
});
