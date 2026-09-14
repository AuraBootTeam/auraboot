/** Dashboard designer golden ops: save toast, version rollback, publish cycle, BigScreen, settings scope. */
import { test, expect } from '@playwright/test';
import { DashboardDesignerPage } from '../../pages/DashboardDesignerPage';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json', locale: 'zh-CN' });

test.describe.configure({ mode: 'serial' });
test.setTimeout(60000);

let dp: DashboardDesignerPage;

test.describe('Dashboard designer ops golden', () => {
  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await dp.goto();
    await expect(dp.canvas).toBeVisible({ timeout: 30000 });
  });

  const nameViaSettings = async (page: import('@playwright/test').Page, title: string) => {
    await dp.settingsButton.click();
    const dialog = page.getByRole('dialog', { name: 'Dashboard Settings' });
    await expect(dialog).toBeVisible();
    await dialog.locator('input').first().fill(title);
    await dialog.getByRole('button', { name: /^(保存|Save)$/ }).click();
    await expect(dialog).toHaveCount(0);
  };

  test('DOPS-01 save creates versions, success toast is visible, rollback works', async ({ page, request }) => {
    await nameViaSettings(page, `dops_${Date.now()}`);
    await dp.addWidget('富文本');
    const postPromise = page.waitForResponse(
      (r) => r.url().includes('/dashboards') && r.request().method().toLowerCase() === 'post',
      { timeout: 30000 },
    );
    await dp.save();
    const post = await postPromise;
    await dp.waitUntilSaved();
    await expect(page.getByTestId('toast-stack').getByText('保存成功')).toBeVisible();

    const postBody = await post.json();
    const pid = String(postBody.data?.pid ?? '');
    expect(pid).toBeTruthy();
    // Second change + save creates the update version so a non-latest row exists.
    await nameViaSettings(page, `dops_v2_${Date.now()}`);
    await dp.save();
    await dp.waitUntilSaved();
    const versions = await (await request.get(`/api/dashboards/${pid}/versions`)).json();
    const list = versions.data?.records ?? versions.data ?? [];
    expect(list.length).toBeGreaterThanOrEqual(2);

    await page.locator('button', { hasText: /^History|历史$/ }).first().click();
    const panel = page.getByTestId('version-history-panel');
    await expect(panel).toBeVisible();
    const oldest = panel.locator('button').filter({ hasText: /由/ }).filter({ hasNotText: '最新' }).last();
    await oldest.click({ timeout: 20000 });
    await panel.getByRole('button', { name: /^回滚$/ }).click();
    await page.getByRole('button', { name: '确认回滚' }).click();
    // Rollback stores a backup of the pre-rollback state, so the count grows by one;
    // the panel stays open and refreshes in place.
    await expect
      .poll(
        async () => {
          const after = await (await request.get(`/api/dashboards/${pid}/versions`)).json();
          const afterList = after.data?.records ?? after.data ?? [];
          return afterList.length;
        },
        { timeout: 20000 },
      )
      .toBeGreaterThanOrEqual(list.length + 1);
  });

  test('DOPS-02 publish and unpublish cycle updates the status badge', async ({ page, request }) => {
    await nameViaSettings(page, `dops_pub_${Date.now()}`);
    await dp.addWidget('富文本');
    await dp.save();
    await dp.waitUntilSaved();
    await dp.publishButton.click();
    await expect(dp.statusBadge).toContainText(/已发布|Published/, { timeout: 20000 });
    await dp.unpublishButton.click();
    await expect(dp.statusBadge).toContainText(/草稿|Draft/, { timeout: 20000 });
  });

  test('DOPS-03 BigScreen presentation mode enters and exits', async ({ page }) => {
    await nameViaSettings(page, `dops_big_${Date.now()}`);
    await dp.addWidget('富文本');
    await dp.save();
    await dp.waitUntilSaved();
    await page.getByTestId('toolbar-btn-presentation').click();
    await expect(page.getByTestId('big-screen-exit')).toBeVisible({ timeout: 20000 });
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('big-screen-exit')).toHaveCount(0);
    await expect(dp.canvas).toBeVisible();
  });

  test('DOPS-04 settings dialog exposes personal, team and global scopes', async ({ page }) => {
    await dp.settingsButton.click();
    const dialog = page.getByRole('dialog', { name: 'Dashboard Settings' });
    await expect(dialog).toBeVisible();
    const scopeSelect = dialog.locator('select').first();
    const options = await scopeSelect.locator('option').allTextContents();
    expect(options).toHaveLength(3);
    expect(options.join('|')).toContain('个人');
    expect(options.join('|')).toContain('团队');
    expect(options.join('|')).toContain('全局');
    await dialog.getByRole('button', { name: /^(取消|Cancel)$/ }).click();
    await expect(dialog).toHaveCount(0);
  });
});
