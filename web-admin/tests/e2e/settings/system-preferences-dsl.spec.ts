import { test, expect } from '../../fixtures';

test.describe('System Preferences DSL', () => {
  test('SP-001: should navigate by menu and save tenant display preferences via DSL UI', async ({
    page,
  }) => {
    await page.goto('/dashboard');
    await page.waitForLoadState('domcontentloaded');

    const menuLink = page.locator('a[href="/p/c/system_preferences_form"]').first();
    await expect(menuLink).toBeVisible({ timeout: 10000 });

    const loadPreferences = page.waitForResponse(
      (response) =>
        response.url().includes('/api/admin/system-preferences') &&
        response.request().method() === 'GET' &&
        response.status() < 400,
      { timeout: 15000 },
    );
    await menuLink.click();
    await expect(page).toHaveURL(/\/p\/c\/system_preferences_form/);

    const loadResponse = await loadPreferences;
    const loadBody = await loadResponse.json();
    expect(loadBody?.data?.datetimeFormat).toBeTruthy();
    expect(loadBody?.data?.timezone).toBeTruthy();

    await expect(page.getByRole('heading', { name: '系统偏好' })).toBeVisible({ timeout: 8000 });
    await expect(page.getByTestId('settings-card-system_preferences_display')).toBeVisible();
    await expect(page.getByText('设置租户级日期时间格式和默认时区')).toBeVisible();

    const datetimeInput = page.getByTestId('system-datetime-format-input');
    const timezoneTrigger = page.getByTestId('system-timezone-select-trigger');
    const statusInput = page.getByTestId('system-timezone-status');
    const saveButton = page.getByTestId('form-btn-save');

    await expect(datetimeInput).toHaveValue('YYYY-MM-DD HH:mm:ss');
    await expect(timezoneTrigger).toContainText('北京/上海');
    await expect(statusInput).toHaveValue(/租户默认时区/);
    await expect(saveButton).toHaveText('保存偏好');

    await timezoneTrigger.click();
    const timezoneSearch = page.getByTestId('system-timezone-select-search');
    await expect(timezoneSearch).toBeVisible({ timeout: 5000 });
    await timezoneSearch.fill('北京');
    await page.getByTestId('system-timezone-select-option-Asia/Shanghai').click();
    await expect(timezoneTrigger).toContainText('北京/上海');

    await datetimeInput.click();
    await datetimeInput.press('ControlOrMeta+A');
    await datetimeInput.fill('YYYY-MM-DD HH:mm:ss');

    const savePreferences = page.waitForResponse(
      (response) =>
        response.url().includes('/api/admin/system-preferences') &&
        response.request().method() === 'PUT' &&
        response.status() < 400,
      { timeout: 15000 },
    );
    await saveButton.click();
    const saveResponse = await savePreferences;
    const saveBody = await saveResponse.json();
    expect(saveBody?.data?.datetimeFormat).toBe('YYYY-MM-DD HH:mm:ss');
    expect(saveBody?.data?.timezone).toBe('Asia/Shanghai');
    expect(saveBody?.data?.timezoneConfigured).toBe(true);
  });
});
