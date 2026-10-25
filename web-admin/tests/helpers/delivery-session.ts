import { expect, type Page } from '@playwright/test';

/** Log in through the UI and verify the actual business space after selection. */
export async function loginDeliveryMember(
  page: Page,
  email = process.env.ADMIN_EMAIL!,
  tenant = process.env.AURA_ADMIN_TENANT_ID,
  password = process.env.ADMIN_PASSWORD!,
  expectedUserPid?: string,
) {
  expect(email, 'Configure the local delivery member').toBeTruthy();
  expect(password, 'Configure the local member password').toBeTruthy();
  await page.goto('/login');
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
  await page.locator('#identifier').fill(email);
  await page.locator('#password').fill(password);
  await page.getByRole('button', { name: '立即登录', exact: true }).click();
  await page.waitForURL(url => !url.pathname.includes('/login'));
  if (new URL(page.url()).pathname === '/tenant-selection') {
    expect(tenant, 'Bind the intended business tenant').toBeTruthy();
    await page.getByTestId(`space-business-${tenant}`).click();
    await expect(page).not.toHaveURL(/tenant-selection/);
  }
  const identity = async () => {
    const response = await page.request.get('/api/auth/me');
    expect(response.status()).toBe(200);
    const envelope = await response.json();
    expect(String(envelope.code)).toBe('0');
    return envelope.data;
  };
  let current = await identity();
  if (tenant && String(current.user.tenantId) !== String(tenant)) {
    await page.getByTestId('user-menu').getByRole('button').first().click();
    await expect(page.getByTestId('user-dropdown')).toBeVisible();
    const switched = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/_action/switch-space' && response.request().method() === 'POST');
    await page.getByTestId(`tenant-switch-${tenant}`).click();
    expect((await switched).status()).toBe(302);
    await page.waitForLoadState('load');
    current = await identity();
  }
  if (tenant) expect(String(current.user.tenantId)).toBe(String(tenant));
  // Username-only seeded members have no email in /auth/me; bind their provisioned PID explicitly.
  if (expectedUserPid) expect(current.user.pid).toBe(expectedUserPid);
  else expect(current.user.email).toBe(email);
  return current;
}
