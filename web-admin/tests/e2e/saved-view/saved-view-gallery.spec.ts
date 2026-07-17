/**
 * E2E Test: SavedView GALLERY capability gate
 *
 * e2et_order intentionally has no image/file/avatar field. The gallery view
 * creation path must show a blocked diagnostic instead of creating a
 * half-configured SavedView that cannot render.
 *
 * @since 7.0.0
 */

import { test, expect, type APIRequestContext } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import {
  navigateToDynamicPage,
  openSavedViewManagePanel,
  uniqueId,
  viewSelectorTrigger,
} from '../helpers';

const ROUTE_PAGE_KEY = 'e2et_order';
const SHOWCASE_PAGE_KEY = 'showcase_all_fields';
const GALLERY_IMAGE_FIELD = 'sc_attachment_file';
const GALLERY_TITLE_FIELD = 'sc_name';
const SCREENSHOT_DIR = 'test-results/saved-view-vnext';
const GALLERY_IMAGE_DATA_URI =
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 2 2'%3E%3Cpath fill='%232563eb' d='M0 0h2v2H0z'/%3E%3C/svg%3E";

interface SeededShowcaseRecord {
  pid: string;
  scName: string;
}

async function seedShowcaseGalleryRecord(
  request: APIRequestContext,
  label: string,
): Promise<SeededShowcaseRecord> {
  const scName = `SV Gallery ${label} ${uniqueId('GALLERY')}`;
  const resp = await request.post('/api/meta/commands/execute/sc:create_showcase', {
    data: {
      operationType: 'create',
      payload: {
        sc_name: scName,
        sc_description: 'SavedView Gallery positive fixture with a native file data URI',
        sc_quantity: 7,
        sc_price: 12.34,
        sc_priority: 'medium',
        sc_category: 'electronics',
        [GALLERY_IMAGE_FIELD]: GALLERY_IMAGE_DATA_URI,
      },
    },
  });
  const body = await resp.json().catch(async () => resp.text().catch(() => null));
  expect(resp.ok(), `Create showcase gallery seed failed: ${resp.status()} ${JSON.stringify(body)}`).toBe(true);
  expect((body as { code?: string })?.code, `Create showcase seed non-zero: ${JSON.stringify(body)}`).toBe('0');
  const pid = (body as { data?: { data?: { recordPid?: string } } })?.data?.data?.recordPid;
  expect(pid, `Created showcase gallery seed missing recordPid: ${JSON.stringify(body)}`).toBeTruthy();
  return { pid: pid!, scName };
}

test.describe('SavedView — GALLERY View', () => {
  test('SV-030: GALLERY — blocks creation when no image field exists @smoke', async ({
    page,
  }) => {
    await navigateToDynamicPage(page, ROUTE_PAGE_KEY);
    await expect(
      page.locator('table, [role="table"], [data-testid="dynamic-list"]').first(),
    ).toBeVisible({ timeout: 15000 });

    const createRequests: string[] = [];
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (request.method() === 'POST' && url.pathname === '/api/views') {
        createRequests.push(request.postData() ?? '');
      }
    });

    const panel = await openSavedViewManagePanel(page);
    await panel.getByTestId('saved-view-create-personal').click();
    await expect(panel.getByTestId('saved-view-quota-status')).toContainText('个人视图：');
    await panel.getByTestId('saved-view-type-gallery').click();

    const blocked = panel.getByTestId('view-capability-blocked-gallery');
    await expect(blocked).toBeVisible({ timeout: 5000 });
    await expect(blocked).toContainText(/缺少|图片|附件|头像|封面/);
    expect(createRequests).toHaveLength(0);

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: `${SCREENSHOT_DIR}/gallery-capability-blocked.png`,
      fullPage: true,
    });
  });

  test('SV-032: GALLERY — creates and renders image cards when an image field exists @smoke', async ({
    page,
    request,
  }) => {
    const seed = await seedShowcaseGalleryRecord(request, 'Positive');

    await navigateToDynamicPage(page, SHOWCASE_PAGE_KEY);
    await expect(page.getByText(seed.scName)).toBeVisible({ timeout: 15000 });

    const createPayloads: Array<Record<string, unknown>> = [];
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (request.method() === 'POST' && url.pathname === '/api/views') {
        const body = request.postData();
        createPayloads.push(body ? JSON.parse(body) : {});
      }
    });

    const panel = await openSavedViewManagePanel(page);
    await panel.getByTestId('saved-view-create-personal').click();
    await expect(panel.getByTestId('saved-view-quota-status')).toContainText('个人视图：');
    await panel.getByTestId('saved-view-type-gallery').click();

    await expect(panel.getByText(/配置画册视图/)).toBeVisible({ timeout: 5000 });
    await panel.getByTestId('saved-view-config-field-galleryImageField').selectOption(GALLERY_IMAGE_FIELD);
    await panel.getByTestId('saved-view-config-field-galleryTitleField').selectOption(GALLERY_TITLE_FIELD);

    const save = panel.getByTestId('saved-view-config-save');
    await expect(save).toBeEnabled();

    const createResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/views',
      { timeout: 10000 },
    );

    await save.click();
    const createResponse = await createResponsePromise;
    expect(createResponse.ok(), `create Gallery view failed: ${createResponse.status()}`).toBe(true);

    await expect(panel).toBeHidden({ timeout: 10000 });
    expect(createPayloads).toHaveLength(1);
    expect(createPayloads[0]).toMatchObject({
      viewType: 'gallery',
      viewConfig: {
        galleryImageField: GALLERY_IMAGE_FIELD,
        galleryTitleField: GALLERY_TITLE_FIELD,
      },
    });

    await expect(viewSelectorTrigger(page)).toHaveAttribute('data-current-view-type', 'gallery', {
      timeout: 10000,
    });
    const gallery = page.getByTestId('gallery-view');
    await expect(gallery).toBeVisible({ timeout: 15000 });
    await expect(gallery.getByText(seed.scName)).toBeVisible({ timeout: 15000 });
    const image = gallery.getByRole('img', { name: seed.scName });
    await expect(image).toBeVisible();
    await expect(image).toHaveAttribute('src', /data:image\/svg\+xml/);
    await expect(page.getByTestId('ab:list:showcase_all_fields:table')).toBeHidden();

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: `${SCREENSHOT_DIR}/gallery-view-rendered.png`,
      fullPage: true,
    });
  });
});
