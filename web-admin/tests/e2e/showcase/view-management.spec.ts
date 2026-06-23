/**
 * SavedView management smoke for the current Personal-only release.
 *
 * The old showcase test intentionally asserted the deprecated English
 * management chain. This smoke keeps the showcase coverage slot, but now
 * protects the Chinese Personal-only selector and management panel.
 */

import { expect, test } from '@playwright/test';
import { openSavedViewManagePanel, selectSavedViewByName, uniqueId } from '../helpers';
import { navigateToOrderViaSidebar } from '../saved-view/helpers';

const CLEANUP_PREFIXES = [
  'SV Showcase',
  'SV个人视图',
  'TL_',
  'Modified This Week preset_',
  'Default View',
  'E2E Calendar View',
  'E2E Kanban Board',
  'E2E Gantt Timeline',
  'FV_',
  'SV_Tree_',
  'SV Tree Table View',
  '树视图表格视图',
  '树视图视图',
  'UX_FormView_e2e_',
];

async function cleanupSavedViewFixtures(page: import('@playwright/test').Page): Promise<void> {
  const resp = await page.request.get(
    '/api/views/accessible?modelCode=e2et_order&pageKey=e2et_order_list',
  );
  if (!resp.ok()) return;
  const body = await resp.json().catch(() => ({}));
  for (const view of body.data ?? []) {
    if (
      view?.pid &&
      view.scope === 'personal' &&
      CLEANUP_PREFIXES.some((prefix) => String(view.name ?? '').startsWith(prefix))
    ) {
      await page.request.delete(`/api/views/${view.pid}`).catch(() => {});
    }
  }
}

test.describe('SavedView management panel', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  test('opens the Personal-only management panel without deprecated English UI', async ({
    page,
  }) => {
    await cleanupSavedViewFixtures(page);
    const viewName = `SV Showcase ${uniqueId()}`;
    const createResp = await page.request.post('/api/views', {
      data: {
        name: viewName,
        modelCode: 'e2et_order',
        pageKey: 'e2et_order_list',
        scope: 'personal',
        viewType: 'table',
        viewConfig: { rowHeight: 'medium' },
      },
    });
    expect(createResp.ok()).toBeTruthy();
    const createBody = await createResp.json();
    const viewPid = createBody.data?.pid ?? createBody.pid;

    try {
      await navigateToOrderViaSidebar(page);
      await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 20_000 });
      expect(await selectSavedViewByName(page, viewName)).toBe(true);
      await expect(page.getByTestId('quick-filters')).toHaveCount(1);

      await openSavedViewManagePanel(page);
      const panel = page.getByTestId('saved-view-manage-panel');
      await expect(panel).toBeVisible();
      await expect(panel.locator('#view-manage-panel-title')).toHaveText('管理视图');
      await expect(panel.getByTestId('saved-view-create-personal')).toContainText('新建个人视图');
      await expect(panel).toContainText('个人视图');
      await expect(panel).not.toContainText(
        /View Management|New View|Configure|Group By|Title Field|Skip|Done|Team Views|Global Views/,
      );

      await panel.getByTestId('saved-view-create-personal').click();
      await expect(panel.getByTestId('saved-view-quota-status')).toContainText('个人视图：');
      await expect(panel.getByTestId('saved-view-type-table')).toContainText('表格');
      await expect(panel.getByTestId('saved-view-type-kanban')).toContainText('看板');
      await expect(panel.getByTestId('saved-view-type-gallery')).toContainText('画册');
      await expect(panel.getByTestId('saved-view-type-table')).not.toContainText('Table');
      await expect(panel.getByTestId('saved-view-type-kanban')).not.toContainText('Kanban');
      await expect(panel.getByTestId('saved-view-type-gallery')).not.toContainText('Gallery');
      await expect(panel.getByTestId('saved-view-type-gantt')).not.toContainText('Gantt');
    } finally {
      if (viewPid) {
        await page.request.delete(`/api/views/${viewPid}`).catch(() => {});
      }
      await cleanupSavedViewFixtures(page);
    }
  });
});
