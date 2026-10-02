import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

async function save(page: Page) {
  const response = page.waitForResponse(response => response.url().endsWith('/api/admin/auth-appearance/draft') && response.request().method() === 'PUT');
  await page.getByRole('button', { name: 'Save draft', exact: true }).click();
  const body = await (await response).json();
  expect(String(body.code), JSON.stringify(body)).toBe('0');
  await expect(page.getByRole('button', { name: 'Publish', exact: true })).toBeEnabled();
  return body.data;
}
async function publish(page: Page) {
  await page.getByRole('button', { name: 'Publish', exact: true }).click();
  const response = page.waitForResponse(response => response.url().endsWith('/api/admin/auth-appearance/publish') && response.request().method() === 'POST');
  await page.getByRole('alertdialog').getByRole('button', { name: 'Confirm', exact: true }).click();
  const body = await (await response).json();
  expect(String(body.code), JSON.stringify(body)).toBe('0');
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  return body.data;
}

test('menu → draft → preview → publish → anonymous SSR → rollback', async ({ page, browser }, testInfo) => {
  expect(process.env.PLAYWRIGHT_BASE_URL, 'Dedicated runtime URL must be explicit').toBeTruthy();
  expect(process.env.PW_ADMIN_STORAGE_STATE, 'Dedicated platform administrator session must be explicit').toBeTruthy();
  await page.context().addCookies([{ name: 'locale', value: 'en-US', url: process.env.PLAYWRIGHT_BASE_URL! }]);
  await page.goto('/');
  const navigation = page.getByRole('navigation');
  // The platform sidebar opens its only top-level group by default.
  await expect(navigation.getByRole('link', { name: /Brand and Authentication Appearance|品牌与登录外观/ })).toBeVisible();
  await navigation.getByRole('link', { name: /Brand and Authentication Appearance|品牌与登录外观/ }).click();
  expect(new URL(page.url()).pathname).toBe('/p/c/auth_appearance');
  await expect(page.getByTestId('auth-appearance-editor')).toBeVisible();
  const original = await (await page.request.get('/api/admin/auth-appearance')).json();
  expect(String(original.code)).toBe('0');

  // Create a published baseline through the UI so rollback has a genuine release.
  await page.getByRole('combobox', { name: 'Layout template', exact: true }).selectOption('split');
  await page.getByRole('combobox', { name: 'Headline display mode', exact: true }).selectOption('custom');
  await page.getByRole('textbox', { name: 'Headline zh-CN', exact: true }).fill('品牌登录验收');
  await page.getByRole('textbox', { name: 'Headline en-US', exact: true }).fill('Authentication appearance acceptance');
  await save(page);
  const baseline = await publish(page);
  await page.getByRole('combobox', { name: 'Headline display mode', exact: true }).selectOption('hidden');
  await page.getByRole('combobox', { name: 'Highlighted text display mode', exact: true }).selectOption('hidden');
  const saved = await save(page);
  expect(saved.publishedVersion).toBe(baseline.publishedVersion);
  await page.reload();
  await expect(page.getByRole('combobox', { name: 'Headline display mode', exact: true })).toHaveValue('hidden');
  const preview = page.frameLocator('iframe[title="Authentication preview"]');
  await expect(preview.locator('[data-auth-template]')).toHaveAttribute('data-auth-template', 'split');
  await expect(preview.locator('.auth-brand-headline')).toHaveCount(0);

  const anonymous = await browser.newContext({ baseURL: process.env.PLAYWRIGHT_BASE_URL, storageState: { cookies: [], origins: [] }, locale: 'en-US' });
  try {
    await anonymous.addCookies([{ name: 'locale', value: 'en-US', url: process.env.PLAYWRIGHT_BASE_URL! }]);
    const login = await anonymous.newPage();
    await login.goto('/login');
    await expect(login.locator('[data-auth-template]')).toHaveAttribute('data-auth-template', 'split');
    await expect(login.locator('.auth-brand-headline').filter({ hasText: 'Authentication appearance acceptance' })).toBeVisible();
    const hiddenRelease = await publish(page);
    await login.reload();
    await expect(login.locator('[data-auth-template]')).toHaveAttribute('data-auth-template', 'split');
    await expect(login.locator('.auth-brand-headline')).toHaveCount(0);
    await page.getByRole('combobox', { name: 'Layout template', exact: true }).selectOption('centered');
    await save(page);
    const published = await publish(page);
    await login.reload();
    await expect(login.locator('[data-auth-template]')).toHaveAttribute('data-auth-template', 'centered');
    await expect(login.locator('.auth-brand-headline').filter({ hasText: 'Authentication appearance acceptance' })).toHaveCount(0);
    const publicState = await (await login.request.get('/api/auth/appearance')).json();
    expect(publicState.data.version).toBe(published.publishedVersion);
    expect(publicState.data).not.toHaveProperty('draft');
    expect(publicState.data).not.toHaveProperty('history');
    await testInfo.attach('published-state', { body: JSON.stringify({ original: original.data, baseline, saved, hiddenRelease, published, publicState }, null, 2), contentType: 'application/json' });
    for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
      await login.setViewportSize(viewport);
      await expect(login.locator('input#identifier, input#email').first()).toBeVisible();
      expect(await login.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
      await login.screenshot({ path: testInfo.outputPath(`centered-${viewport.width}.png`), fullPage: true });
      await login.goto('/forgot-password');
      await expect(login.getByTestId('forgot-password-disabled')).toBeVisible();
      await expect(login.locator('[data-auth-template]')).toHaveAttribute('data-auth-template', 'centered');
      await login.goto('/login');
    }
    await page.getByRole('combobox', { name: 'Rollback target', exact: true }).selectOption(String(baseline.publishedVersion));
    await page.getByRole('button', { name: 'Roll back', exact: true }).click();
    const response = page.waitForResponse(response => response.url().endsWith('/api/admin/auth-appearance/rollback'));
    await page.getByRole('alertdialog').getByRole('button', { name: 'Confirm', exact: true }).click();
    const restored = await (await response).json();
    expect(String(restored.code)).toBe('0');
    expect(restored.data.published).toEqual(baseline.published);
    await login.setViewportSize({ width: 1440, height: 900 });
    await login.reload();
    await expect(login.locator('[data-auth-template]')).toHaveAttribute('data-auth-template', 'split');
    await expect(login.locator('.auth-brand-headline').filter({ hasText: 'Authentication appearance acceptance' })).toBeVisible();
  } finally { await anonymous.close(); }
});

test('invalid draft, concurrent editor conflict, multilingual live copy and failed assets preserve usable authentication', async ({ page, browser }, testInfo) => {
  await page.context().addCookies([{ name: 'locale', value: 'en-US', url: process.env.PLAYWRIGHT_BASE_URL! }]);
  const openEditor = async (editor: Page) => {
    await editor.goto('/');
    await editor.getByRole('navigation').getByRole('link', { name: /Brand and Authentication Appearance|品牌与登录外观/ }).click();
    await expect(editor.getByTestId('auth-appearance-editor')).toBeVisible();
  };
  await openEditor(page);
  await page.getByRole('combobox', { name: 'Layout template', exact: true }).selectOption('split');
  await page.getByRole('combobox', { name: 'Headline display mode', exact: true }).selectOption('custom');
  await page.getByRole('textbox', { name: 'Headline zh-CN', exact: true }).fill('');
  await page.getByRole('textbox', { name: 'Headline en-US', exact: true }).fill('A usable workspace for every team');
  await page.getByRole('button', { name: 'Save draft', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Configuration is incomplete');
  await page.getByRole('textbox', { name: 'Headline zh-CN', exact: true }).fill('连接业务与团队，让每一次协作都有清晰的下一步');
  await page.getByRole('combobox', { name: 'Highlighted text display mode', exact: true }).selectOption('hidden');
  await save(page);
  const second = await page.context().newPage();
  await openEditor(second);
  await second.getByRole('combobox', { name: 'Corners', exact: true }).selectOption('rounded');
  await page.getByRole('combobox', { name: 'Authentication theme', exact: true }).selectOption('light');
  await save(page);
  const stable = await (await page.request.get('/api/admin/auth-appearance')).json();
  const conflictResponse = second.waitForResponse(r => r.url().endsWith('/api/admin/auth-appearance/draft') && r.request().method() === 'PUT');
  await second.getByRole('button', { name: 'Save draft', exact: true }).click();
  expect((await conflictResponse).status()).toBe(409);
  await expect(second.getByRole('alert')).toBeVisible();
  await expect(second.getByRole('combobox', { name: 'Corners', exact: true })).toHaveValue('rounded');
  const afterConflict = await (await page.request.get('/api/admin/auth-appearance')).json();
  expect(afterConflict.data).toEqual(stable.data);
  await second.screenshot({ path: testInfo.outputPath('version-conflict-retains-edit.png'), fullPage: true });
  await second.getByRole('button', { name: 'Reload', exact: true }).click();
  await expect(second.getByRole('alertdialog')).toContainText('discards unsaved changes');
  await second.getByRole('alertdialog').getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(second.getByRole('combobox', { name: 'Corners', exact: true })).toHaveValue(stable.data.draft.theme?.radius ?? 'soft');
  await second.close();
  await publish(page);
  const anonymous = await browser.newContext({ baseURL: process.env.PLAYWRIGHT_BASE_URL, storageState: { cookies: [], origins: [] }, viewport: { width: 1440, height: 900 } });
  try {
    const login = await anonymous.newPage();
    await login.goto('/login');
    await expect(login.locator('.auth-brand-headline')).toHaveText('连接业务与团队，让每一次协作都有清晰的下一步');
    await login.getByRole('button', { name: 'Switch language', exact: true }).click();
    await login.getByRole('button', { name: /English/ }).click();
    await expect(login.locator('.auth-brand-headline')).toHaveText('A usable workspace for every team');
    await login.screenshot({ path: testInfo.outputPath('live-english-copy.png'), fullPage: true });
    await login.goto('/signup');
    await expect(login).toHaveURL(/\/login/);
    await expect(login.locator('a[href*="signup"]')).toHaveCount(0);
    for (const [route, marker] of [['/forgot-password', 'forgot-password-disabled'], ['/reset-password?token=appearance-invalid', 'reset-password-disabled']]) {
      await login.goto(route);
      await expect(login.getByTestId(marker)).toBeVisible();
      await expect(login.getByText(/tenant administrator/i)).toBeVisible();
      await expect(login.locator('[data-auth-template]')).toHaveAttribute('data-auth-template', 'split');
      await login.screenshot({ path: testInfo.outputPath(`${marker}.png`), fullPage: true });
    }
    await page.getByRole('textbox', { name: 'logoUrl URL', exact: true }).fill('/appearance-missing-logo.png');
    await page.getByRole('textbox', { name: 'heroUrl URL', exact: true }).fill('/appearance-missing-hero.png');
    await page.getByRole('combobox', { name: 'Image mode', exact: true }).selectOption('illustration');
    await save(page);
    await publish(page);
    await login.goto('/login');
    await expect(login.locator('.auth-brand-story img')).toHaveCount(0);
    await expect(login.locator('#identifier')).toBeVisible();
    await login.locator('#identifier').fill(`missing-${randomUUID().slice(0, 8)}@e2e.local`);
    await login.locator('#password').fill('Invalid2026x');
    await login.locator('button[type="submit"]').click();
    await expect(login.getByRole('alert')).toBeVisible();
    await expect(login).toHaveURL(/\/login/);
    await expect(login.locator('#identifier')).toBeEnabled();
    await expect(login.locator('button[type="submit"]')).toBeEnabled();
    await login.screenshot({ path: testInfo.outputPath('missing-images-invalid-login.png'), fullPage: true });
  } finally { await anonymous.close(); }
});

test('HTTP permission boundaries reject tenant administrators, members and anonymous mutations without changing state', async ({ page, browser }, testInfo) => {
  const before = await (await page.request.get('/api/admin/auth-appearance')).json();
  expect(String(before.code)).toBe('0');
  const roles = await (await page.request.get('/api/roles/all')).json();
  expect(String(roles.code)).toBe('0');
  expect(roles.data.some((role: { code: string }) => role.code === 'tenant_admin')).toBe(true);
  const rows: unknown[] = [];
  for (const role of ['anonymous', 'tenant_admin', 'member']) {
    const context = await browser.newContext({ baseURL: process.env.PLAYWRIGHT_BASE_URL, extraHTTPHeaders: { Origin: process.env.PLAYWRIGHT_BASE_URL! }, storageState: { cookies: [], origins: [] } });
    try {
      const user = await context.newPage();
      if (role !== 'anonymous') {
        const email = `appearance-${role}-${randomUUID().slice(0, 12)}@e2e.local`;
        const create = await page.request.post('/api/admin/users', { headers: { Origin: process.env.PLAYWRIGHT_BASE_URL! }, data: { email, displayName: `Appearance ${role}`, initialPassword: DEFAULT_TEST_ACCOUNT.password, roleCodes: role === 'tenant_admin' ? ['tenant_admin'] : [], roleAssignmentMode: role === 'member' ? 'NONE' : 'EXPLICIT', sendInviteEmail: false } });
        const body = await create.json();
        expect(String(body.code), JSON.stringify(body)).toBe('0');
        expect(body.data.assignedRoles).toEqual(role === 'tenant_admin' ? ['tenant_admin'] : []);
        await user.goto('/login');
        await user.locator('#identifier').fill(email);
        await user.locator('#password').fill(DEFAULT_TEST_ACCOUNT.password);
        await user.locator('button[type="submit"]').click();
        await expect(user).not.toHaveURL(/\/login/);
      }
      for (const [method, path, data] of [
        ['GET', '/api/admin/auth-appearance', undefined],
        ['PUT', '/api/admin/auth-appearance/draft', { expectedVersion: before.data.version, appearance: before.data.draft }],
        ['POST', '/api/admin/auth-appearance/publish', { expectedVersion: before.data.version }],
        ['POST', '/api/admin/auth-appearance/rollback', { expectedVersion: before.data.version, targetVersion: before.data.publishedVersion }],
      ] as const) {
        const response = await user.request.fetch(path, { method, data });
        const body = await response.json();
        expect(response.status(), `${role} ${method} ${path}`).toBe(role === 'anonymous' ? 401 : 200);
        expect(String(body.code)).toBe(role === 'anonymous' ? '40001' : '409');
        if (role !== 'anonymous') expect(body.message).toBe('admin role required');
        expect(body.data).toBeNull();
        rows.push({ role, method, path, status: response.status(), code: body.code });
      }
      const upload = await user.request.post('/api/admin/auth-appearance/assets', { multipart: { file: { name: 'test.png', mimeType: 'image/png', buffer: readFileSync(`${process.env.PW_APPEARANCE_FIXTURE_ROOT}/logo.png`) } } });
      expect(upload.status()).toBe(role === 'anonymous' ? 401 : 200);
      const uploadBody = await upload.json();
      expect(String(uploadBody.code)).toBe(role === 'anonymous' ? '40001' : '409');
      expect(uploadBody.data).toBeNull();
      rows.push({ role, method: 'POST', path: '/api/admin/auth-appearance/assets', status: upload.status(), code: uploadBody.code });
      const publicState = await (await user.request.get('/api/auth/appearance')).json();
      expect(String(publicState.code)).toBe('0');
      expect(publicState.data).toEqual({ appearance: before.data.published, version: before.data.publishedVersion });
    } finally { await context.close(); }
  }
  const after = await (await page.request.get('/api/admin/auth-appearance')).json();
  expect(after.data).toEqual(before.data);
  await testInfo.attach('permission-boundaries', { body: JSON.stringify({ rows, unchanged: after.data.version === before.data.version }, null, 2), contentType: 'application/json' });
});

test('three templates, light and dark themes, desktop and mobile with uploaded assets', async ({ page, browser }, testInfo) => {
  test.setTimeout(240_000);
  const assetRoot = process.env.PW_APPEARANCE_FIXTURE_ROOT;
  expect(assetRoot, 'Owned asset fixtures must be explicit').toBeTruthy();
  await page.context().addCookies([{ name: 'locale', value: 'en-US', url: process.env.PLAYWRIGHT_BASE_URL! }]);
  await page.goto('/');
  await page.getByRole('navigation').getByRole('link', { name: /Brand and Authentication Appearance|品牌与登录外观/ }).click();
  await expect(page.getByTestId('auth-appearance-editor')).toBeVisible();
  for (const label of ['Headline', 'Highlighted text', 'Introduction', 'Features']) {
    await page.getByRole('combobox', { name: `${label} display mode`, exact: true }).selectOption('default');
  }
  const uploaded: Record<string, string> = {};
  for (const [name, key] of [['Desktop horizontal focus', 'Home'], ['Desktop vertical focus', 'End'], ['Mobile horizontal focus', 'End'], ['Mobile vertical focus', 'Home']]) {
    await page.getByRole('slider', { name, exact: true }).press(key);
  }
  for (const key of ['logoUrl', 'darkLogoUrl', 'heroUrl', 'darkHeroUrl', 'backgroundUrl', 'darkBackgroundUrl']) {
    const response = page.waitForResponse(r => r.url().endsWith('/api/admin/auth-appearance/assets') && r.request().method() === 'POST');
    await page.getByLabel(`${key} Upload image`, { exact: true }).setInputFiles(`${assetRoot}/${key.includes('Logo') || key === 'logoUrl' ? 'logo.png' : 'scene.png'}`);
    const result = await (await response).json();
    expect(String(result.code), JSON.stringify(result)).toBe('0');
    uploaded[key] = result.data.url;
  }
  const anonymous = await browser.newContext({ baseURL: process.env.PLAYWRIGHT_BASE_URL, storageState: { cookies: [], origins: [] } });
  const rows: unknown[] = [];
  try {
    const login = await anonymous.newPage();
    for (const url of Object.values(uploaded)) {
      const asset = await login.request.get(url);
      expect(asset.status()).toBe(200);
      expect(asset.headers()['content-type']).toContain('image/');
      expect(await asset.body()).toEqual(readFileSync(`${assetRoot}/${url === uploaded.logoUrl ? 'logo.png' : 'scene.png'}`));
    }
    for (const template of ['split', 'centered', 'background']) {
      await page.getByRole('combobox', { name: 'Layout template', exact: true }).selectOption(template);
      await page.getByRole('combobox', { name: 'Image mode', exact: true }).selectOption(template === 'background' ? 'background' : 'illustration');
      for (const theme of ['light', 'dark']) {
        await page.getByRole('combobox', { name: 'Authentication theme', exact: true }).selectOption(theme);
        await save(page);
        const release = await publish(page);
        for (const [device, viewport] of Object.entries({ desktop: { width: 1440, height: 900 }, mobile: { width: 390, height: 844 } })) {
          await login.setViewportSize(viewport);
          await login.goto('/login');
          const shell = login.locator('[data-auth-template]');
          await expect(shell).toHaveAttribute('data-auth-template', template);
          await expect(shell).toHaveAttribute('data-auth-theme', theme);
          await expect(login.locator('#identifier')).toBeVisible();
          await expect(login.locator('#password')).toBeVisible();
          await login.locator('#identifier').focus();
          expect(await login.locator('#identifier').evaluate(el => document.activeElement === el)).toBe(true);
          expect(await login.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
          const expectedAssets = template === 'background' ? '.auth-background img' : device === 'desktop' && template === 'split' ? '.auth-brand-hero' : '.auth-compact-brand img';
          await expect(login.locator(expectedAssets)).toBeVisible();
          expect(await login.locator(expectedAssets).evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth > 0)).toBe(true);
          if (template === 'background') expect(await login.locator(expectedAssets).evaluate(el => getComputedStyle(el).objectPosition)).toBe(device === 'desktop' ? '0% 100%' : '100% 0%');
          const name = `auth-${template}-${device}-${theme}`;
          const screenshot = testInfo.outputPath(`${name}.png`);
          await login.screenshot({ path: screenshot, fullPage: true });
          rows.push({ scenarioKey: name, requirement: 'A03', viewport, template, theme, publishedVersion: release.publishedVersion, screenshot, assertionStatus: 'passed', agentVision: null });
        }
      }
    }
    await testInfo.attach('visual-matrix', { body: JSON.stringify({ scenarios: rows, uploaded }, null, 2), contentType: 'application/json' });
    await page.getByRole('combobox', { name: 'Preview device', exact: true }).selectOption('mobile');
    await page.getByRole('combobox', { name: 'Preview theme', exact: true }).selectOption('dark');
    for (const route of ['login', 'signup', 'recovery']) {
      await page.getByRole('combobox', { name: 'Preview page', exact: true }).selectOption(route);
      const preview = page.frameLocator('iframe[title="Authentication preview"]');
      await expect(preview.locator('fieldset')).toHaveAttribute('disabled', '');
      if (route !== 'recovery') await expect(preview.locator('button[type="submit"]')).toBeDisabled();
      await expect(preview.locator('[data-auth-theme]')).toHaveAttribute('data-auth-theme', 'dark');
    }
    await page.screenshot({ path: testInfo.outputPath('editor-mobile-recovery-preview.png'), fullPage: true });
  } finally { await anonymous.close(); }
});
