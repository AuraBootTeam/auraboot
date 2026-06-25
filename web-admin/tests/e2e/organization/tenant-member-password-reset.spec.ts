import { test, expect } from '../../fixtures';
import { join } from 'node:path';

const evidenceDir = join(
  process.cwd(),
  '..',
  'docs/plans/2026-06/evidence/account-org-password/latest/screenshots',
);

test.describe('Tenant Member Password Reset', () => {
  test('MEM-RESET-001: admin can reset an active member password and see one-time temporary password @smoke', async ({
    page,
  }) => {
    await page.goto('/p/tenant_member', { waitUntil: 'domcontentloaded' });

    await expect(page.getByText(/企业成员管理|Tenant Members/i)).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid="row-action-more"]').first()).toBeVisible({
      timeout: 10000,
    });

    await page.locator('[data-testid="row-action-more"]').first().click({ force: true });
    await expect(page.locator('[data-testid="row-action-dropdown"]')).toContainText('重置密码');

    const resetResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/admin:reset_member_password') &&
        response.request().method() === 'POST',
      { timeout: 10000 },
    );

    await page.locator('[data-testid="row-action-reset-password"]').click({ force: true });
    await expect(page.getByText('确认为该成员生成新的临时密码？临时密码只会显示一次。')).toBeVisible({
      timeout: 5000,
    });
    await page.screenshot({
      path: join(evidenceDir, 'ui-07a-member-reset-confirm.png'),
      fullPage: true,
    });
    await page.getByRole('button', { name: /^确认$/ }).last().click({ force: true });

    const response = await resetResponse;
    expect(response.ok()).toBe(true);
    const body = await response.json();
    expect(body?.code).toBe('0');
    expect(body?.data?.data?.adminManaged).toBe(true);
    expect(typeof body?.data?.data?.tempPassword).toBe('string');
    expect(body.data.data.tempPassword.length).toBeGreaterThanOrEqual(8);

    await expect(page.getByRole('heading', { name: '临时密码已生成' })).toBeVisible({
      timeout: 10000,
    });
    await expect(page.getByText(/临时密码只显示一次：/)).toBeVisible();
    await page.screenshot({
      path: join(evidenceDir, 'ui-07-member-reset-temp-password.png'),
      fullPage: true,
    });
  });
});
