import { test, expect, type Page } from '../../fixtures';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { ensureSidebarExpanded } from '../helpers';

const DETAIL_PATH = '/p/c/enterprise_info_detail';
const FORM_PATH = '/p/c/enterprise_info_form';

test.use({
  storageState:
    process.env.PW_ONLINE_TARGET === '1'
      ? process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json'
      : { cookies: [], origins: [] },
});

function testAccount(): { email: string; password: string } {
  return {
    email: process.env.ENTERPRISE_INFO_E2E_EMAIL ?? process.env.PW_ADMIN_EMAIL ?? DEFAULT_TEST_ACCOUNT.email,
    password:
      process.env.ENTERPRISE_INFO_E2E_PASSWORD ??
      process.env.PW_ADMIN_PASSWORD ??
      DEFAULT_TEST_ACCOUNT.password,
  };
}

async function ensureLoggedIn(page: Page): Promise<void> {
  await page.goto(DETAIL_PATH, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  if (!/\/login(?:$|[?#])/.test(page.url())) {
    return;
  }

  const account = testAccount();
  await page
    .locator(
      'input#identifier, input[name="identifier"], input#email, input[name="email"], input[type="email"]',
    )
    .first()
    .fill(account.email);
  await page.locator('input#password, input[name="password"], input[type="password"]').first().fill(account.password);
  await page.locator('button[type="submit"], button:has-text("登录"), button:has-text("Login")').first().click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });
  await page.goto(DETAIL_PATH, { waitUntil: 'domcontentloaded', timeout: 30_000 });
}

function formFieldInput(page: Page, field: string) {
  return page.locator(`[data-testid="form-field-${field}"] input, [data-testid="form-field-${field}"] textarea`).first();
}

test.describe('enterprise info DSL profile', () => {
  test.setTimeout(60_000);

  test('shows menu route, polished detail page, and singleton edit form contract', async ({ page }) => {
    await ensureLoggedIn(page);
    await ensureSidebarExpanded(page);

    const nav = page.locator('nav, aside, [role="navigation"]').first();
    await expect(nav.locator('a[href="/p/c/enterprise_info_detail"]').first()).toBeVisible({
      timeout: 10_000,
    });

    await page.goto(DETAIL_PATH, { waitUntil: 'domcontentloaded', timeout: 30_000 });
    const detail = page.getByTestId('ab:detail:enterprise_info_detail:container');
    await expect(detail).toBeVisible({ timeout: 15_000 });
    await expect(detail.getByRole('heading', { name: '企业信息' })).toBeVisible();
    await expect(detail.getByRole('heading', { name: '企业资料' })).toBeVisible();
    await expect(detail.getByRole('heading', { name: '联系信息' })).toBeVisible();
    await expect(detail.getByRole('heading', { name: '备注' })).toBeVisible();
    await expect(detail.getByText('审计信息')).toHaveCount(0);
    await expect(detail.getByText('创建时间')).toHaveCount(0);
    await expect(detail.getByText('更新时间')).toHaveCount(0);

    await detail.getByRole('button', { name: '编辑信息' }).click();
    await expect(page).toHaveURL(new RegExp(`${FORM_PATH}$`), { timeout: 10_000 });

    const form = page.getByTestId('ab:form:enterprise_info_form:container');
    await expect(form).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('dynamic-form-loading')).toHaveCount(0);
    await expect(form.getByRole('heading', { name: '编辑企业信息' })).toBeVisible();

    const nameInput = formFieldInput(page, 'name');
    await expect(nameInput).toBeVisible();
    await expect(nameInput).toHaveValue(/.+/);
    await expect(nameInput).toHaveJSProperty('readOnly', true);
    await expect(nameInput).toHaveAttribute('placeholder', '系统生成，不可编辑');

    await expect(formFieldInput(page, 'displayName')).toHaveAttribute('placeholder', '请输入企业名称');
    await expect(formFieldInput(page, 'logo')).toHaveAttribute('placeholder', '请输入 Logo 图片地址');
    await expect(formFieldInput(page, 'industry')).toHaveAttribute('placeholder', '请输入所属行业');
    const statusSelect = page.getByTestId('select-trigger-status');
    await expect(statusSelect).toBeVisible();
    await expect(statusSelect).toContainText(/启用|Active|active/);
    await expect(formFieldInput(page, 'contactEmail')).toHaveAttribute('placeholder', '请输入联系邮箱');
    await expect(formFieldInput(page, 'description')).toHaveAttribute('placeholder', '请输入企业描述或备注');

    await expect(form.getByRole('button', { name: '保存' })).toBeVisible();
    await expect(form.getByRole('button', { name: '取消' })).toBeVisible();

    await expect(form).toHaveCSS('max-width', '1040px');
    await expect(form.locator('.form-section').first()).toHaveCSS('border-radius', '8px');
  });
});
