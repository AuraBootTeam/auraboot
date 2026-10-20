import { test, expect, type Page, type TestInfo, type APIResponse } from '@playwright/test';
import fs from 'node:fs/promises';
import { randomUUID, createHash } from 'node:crypto';
import pg from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { LoginPage } from '../../pages/LoginPage';
import { HeaderPage } from '../../pages/HeaderPage';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { revokeInternalApiCurrentSession, type InternalApiSessionCleanup } from '../../helpers/internal-api-owned-user';
import { createResponseShapeValidator } from '../../../../scripts/internal-api/response-shape.mjs';

const document = JSON.parse(
  await fs.readFile(
    new URL('../../../../contracts/internal-web-admin/openapi.json', import.meta.url),
    'utf8',
  ),
);
const catalog = JSON.parse(
  await fs.readFile(
    new URL('../../../../scripts/internal-api/catalog.json', import.meta.url),
    'utf8',
  ),
);
const validate = createResponseShapeValidator(document);
const imageBytes = await fs.readFile(
  new URL('../../../public/android-chrome-512x512.png', import.meta.url),
);
const hash = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');
test.use({
  storageState: { cookies: [], origins: [] },
  locale: 'zh-CN',
  timezoneId: 'Asia/Shanghai',
  viewport: { width: 1440, height: 1000 },
});

async function login(page: Page, identifier: string, password: string) {
  const form = new LoginPage(page);
  await form.gotoRoot();
  await form.waitForFormReady();
  await form.login(identifier, password);
  await form.expectLoggedIn();
}
async function capture(id: string, page: Page, info: TestInfo) {
  const row = catalog.scenarios.find((item: { id: string }) => item.id === id);
  expect(row, `Unregistered scenario ${id}`).toBeDefined();
  expect(row.test).toBe(info.title);
  await expect(page.locator('body')).not.toContainText('Page Unavailable');
  const path = info.outputPath(row.screenshot);
  await page.screenshot({ path, fullPage: true, animations: 'disabled' });
  await info.attach(id, { path, contentType: 'image/png' });
  info.annotations.push({ type: 'internal-api-scenario', description: id });
}

test.afterEach(async ({ page }, info) => {
  const baseURL = info.project.use.baseURL;
  if (!baseURL) throw new Error('Managed test base URL is required for session cleanup');
  const proof = await revokeInternalApiCurrentSession(
    page.request, new URL(baseURL).origin, 'test-fixture-page',
  );
  await info.attach('current-session-cleanup', {
    body: JSON.stringify(proof), contentType: 'application/json',
  });
});

test('generated profile operations persist an owned avatar and no-email profile', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(120_000);
  // Cleanup may only touch this test's explicitly named fixture in a managed development database.
  expect(PG_CONN.database).toMatch(/^auraboot_\d+$/);
  const db = new pg.Client(PG_CONN);
  await db.connect();
  const name = `ic_profile_${randomUUID().replaceAll('-', '').slice(0, 16)}`;
  const password = `Aa7!${randomUUID()}`;
  const rows: object[] = [],
    observed = new Set<string>(),
    files = new Set<string>();
  const cleanupErrors: unknown[] = [];
  const sessionCleanup: InternalApiSessionCleanup[] = [];
  let userPid: string | undefined, ownedPage: Page | undefined, journeyError: unknown;
  const baseURL = info.project.use.baseURL;
  if (!baseURL) throw new Error('A managed profile test requires a base URL');
  const headers = { Origin: new URL(baseURL).origin };
  const ownedContext = await browser.newContext({
    baseURL: headers.Origin,
    storageState: { cookies: [], origins: [] },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    viewport: { width: 1440, height: 1000 },
  });
  async function record(response: APIResponse, method: string, path: string, status = 200) {
    const payload = await response.json();
    // Record upload ownership before response assertions so malformed metadata still gets cleaned.
    if (path === '/api/user/avatar/upload' && typeof payload.data?.fileId === 'string')
      files.add(payload.data.fileId);
    const shape = validate({
      method,
      path,
      status: response.status(),
      contentType: response.headers()['content-type'],
      payload,
    });
    rows.push({ method, path, status: response.status(), payload, shape });
    expect(response.status()).toBe(status);
    expect(shape.errors).toEqual([]);
    expect(shape.valid).toBe(true);
    if (status === 200) expect(payload.code).toBe('0');
    else expect(payload.code).not.toBe('0');
    observed.add(`${method} ${path}`);
    return payload.data;
  }
  try {
    await login(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
    const adminBefore = (await (await page.request.get('/api/user/profile')).json()).data;
    const created = await page.request.post('/api/admin/users', {
      headers,
      data: {
        userName: name,
        displayName: name,
        initialPassword: password,
        roleCodes: ['tenant_admin'],
        sendInviteEmail: false,
      },
    });
    const provision = await created.json();
    if (typeof provision.data?.userPid === 'string') userPid = provision.data.userPid;
    expect(created.status()).toBe(200);
    expect(provision.code).toBe('0');
    expect(userPid).toBeTruthy();
    expect(provision.data.assignedRoles).toContain('tenant_admin');
    const seeded = await db.query(
      'UPDATE ab_user SET created_at=$1 WHERE pid=$2 AND user_name=$3 RETURNING pid',
      ['2026-10-01T01:02:03Z', userPid, name],
    );
    expect(seeded.rowCount).toBe(1);
    ownedPage = await ownedContext.newPage();
    await login(ownedPage, name, password);
    const initial = await record(
      await ownedPage.request.get('/api/user/profile'),
      'get',
      '/api/user/profile',
    );
    expect(initial.pid).toBe(userPid);
    expect(initial.email).toBeNull();
    // A hostile query identity must never select or mutate the administrator's profile.
    const spoofed = await record(
      await ownedPage.request.get(
        `/api/user/profile?userId=${encodeURIComponent(adminBefore.pid)}`,
      ),
      'get',
      '/api/user/profile',
    );
    expect(spoofed.pid).toBe(userPid);
    await ownedPage.goto('/personal/profile');
    await new HeaderPage(ownedPage).waitForHeader();
    await expect(ownedPage.getByRole('heading', { name: '个人资料', exact: true })).toBeVisible();
    await expect(ownedPage.locator('body')).toContainText('2026-10-01 09:02:03');
    await capture('PROFILE-01', ownedPage, info);
    await ownedPage.getByTestId('profile-edit-btn').click();
    await expect(ownedPage.getByLabel('邮箱', { exact: true })).toBeDisabled();
    await expect(ownedPage.getByLabel('邮箱', { exact: true })).toHaveValue('');
    await ownedPage.getByLabel('昵称', { exact: true }).fill('Owned contract profile');
    await ownedPage.getByLabel('地区', { exact: true }).fill('Contract area');
    await capture('PROFILE-02', ownedPage, info);
    const uploadResponse = ownedPage.waitForResponse(
      (r) => r.url().endsWith('/api/user/avatar/upload') && r.request().method() === 'POST',
    );
    await ownedPage
      .getByLabel('Upload avatar')
      .setInputFiles({ name: `${name}.png`, mimeType: 'image/png', buffer: imageBytes });
    const uploadedResponse = await uploadResponse;
    const uploadedPayload = await uploadedResponse.json();
    if (typeof uploadedPayload.data?.fileId === 'string') files.add(uploadedPayload.data.fileId);
    const uploadedShape = validate({
      method: 'post',
      path: '/api/user/avatar/upload',
      status: uploadedResponse.status(),
      contentType: uploadedResponse.headers()['content-type'],
      payload: uploadedPayload,
    });
    rows.push({
      method: 'post',
      path: '/api/user/avatar/upload',
      status: uploadedResponse.status(),
      payload: uploadedPayload,
      shape: uploadedShape,
    });
    expect(uploadedResponse.status()).toBe(200);
    expect(uploadedShape.errors).toEqual([]);
    expect(uploadedPayload.code).toBe('0');
    observed.add('post /api/user/avatar/upload');
    const fileId = uploadedPayload.data.fileId;
    expect(uploadedResponse.request().headers()['content-type']).toMatch(
      /^multipart\/form-data; boundary=/,
    );
    await expect(ownedPage.locator('input[name="imgId"]')).toHaveValue(fileId);
    await expect(ownedPage.getByRole('img', { name: '用户头像' })).toHaveAttribute(
      'src',
      uploadedPayload.data.url,
    );
    await expect(ownedPage.getByRole('alert').filter({ hasText: '头像上传成功' })).toHaveCSS(
      'opacity',
      '1',
    );
    await capture('PROFILE-03', ownedPage, info);
    const savedResponse = ownedPage.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /^\/personal\/profile(?:\.data)?$/.test(new URL(r.url()).pathname),
    );
    await ownedPage.getByTestId('profile-save-btn').click();
    await savedResponse;
    await expect(ownedPage.getByTestId('profile-edit-btn')).toBeVisible();
    const persisted = await record(
      await ownedPage.request.get('/api/user/profile'),
      'get',
      '/api/user/profile',
    );
    expect(persisted).toMatchObject({
      pid: userPid,
      email: null,
      imgId: fileId,
      nickName: 'Owned contract profile',
      area: 'Contract area',
    });
    const spoofWrite = await record(
      await ownedPage.request.put(
        `/api/user/profile?userId=${encodeURIComponent(adminBefore.pid)}`,
        { headers, data: { signature: 'Owned contract signature' } },
      ),
      'put',
      '/api/user/profile',
    );
    expect(spoofWrite.pid).toBe(userPid);
    const adminAfter = (await (await page.request.get('/api/user/profile')).json()).data;
    expect(adminAfter).toEqual(adminBefore);
    await ownedPage.reload();
    await new HeaderPage(ownedPage).waitForHeader();
    await expect(ownedPage.locator('body')).toContainText('Owned contract profile');
    await expect(ownedPage.locator('body')).toContainText('Owned contract signature');
    const avatar = ownedPage.getByRole('img', { name: '用户头像' });
    await expect(avatar).toHaveAttribute('src', persisted.avatarUrl);
    await expect
      .poll(() => avatar.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0))
      .toBe(true);
    const download = await ownedPage.request.get(persisted.avatarUrl);
    expect(download.status()).toBe(200);
    expect(hash(await download.body())).toBe(hash(imageBytes));
    await capture('PROFILE-04', ownedPage, info);
    await record(
      await ownedPage.request.post('/api/user/avatar/upload', {
        headers,
        multipart: {
          file: { name: 'bad.txt', mimeType: 'text/plain', buffer: Buffer.from('bad') },
        },
      }),
      'post',
      '/api/user/avatar/upload',
      400,
    );
    await record(
      await ownedPage.request.post('/api/user/avatar/upload', {
        headers,
        multipart: { file: { name: 'empty.png', mimeType: 'image/png', buffer: Buffer.alloc(0) } },
      }),
      'post',
      '/api/user/avatar/upload',
      422,
    );
    await record(
      await ownedPage.request.put('/api/user/password', {
        headers,
        data: {
          currentPassword: password,
          newPassword: `${password}x`,
          confirmPassword: `${password}x`,
        },
      }),
      'put',
      '/api/user/password',
      403,
    );
    expect([...observed].sort()).toEqual(
      catalog.profiles.profile.operations
        .map((row: { method: string; path: string }) => `${row.method} ${row.path}`)
        .sort(),
    );
  } catch (error) {
    journeyError = error;
  } finally {
    let ownedId: string | undefined;
    try {
      const fixture = await db.query('SELECT id, pid FROM ab_user WHERE user_name=$1', [name]);
      if (fixture.rowCount) {
        expect(fixture.rowCount).toBe(1);
        if (userPid) expect(fixture.rows[0].pid).toBe(userPid);
        userPid = fixture.rows[0].pid;
        ownedId = fixture.rows[0].id;
        const uploads = await db.query(
          'SELECT pid FROM ab_file WHERE created_by=$1 AND deleted_flag=false',
          [ownedId],
        );
        for (const file of uploads.rows) files.add(file.pid);
      }
    } catch (error) {
      cleanupErrors.push(error);
    }
    for (const fileId of files) {
      try {
        if (!ownedPage) throw new Error('Missing owner session for file cleanup');
        const removed = await ownedPage.request.delete(`/api/file/${encodeURIComponent(fileId)}`, {
          headers,
        });
        expect(removed.status()).toBe(200);
        expect((await removed.json()).data).toBe(true);
        const active = await db.query(
          'SELECT count(*)::int AS count FROM ab_file WHERE pid=$1 AND deleted_flag=false',
          [fileId],
        );
        expect(active.rows[0].count).toBe(0);
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    try {
      sessionCleanup.push(await revokeInternalApiCurrentSession(
        ownedContext.request, headers.Origin, 'owned-profile',
      ));
    } catch (error) { cleanupErrors.push(error); }
    if (ownedId) {
      try {
        const user = await db.query(
          'UPDATE ab_user SET is_enabled=false, deleted_flag=true, img_id=NULL, security_version=COALESCE(security_version,0)+1 WHERE pid=$1 AND user_name=$2 RETURNING id, is_enabled, deleted_flag',
          [userPid, name],
        );
        expect(user.rowCount).toBe(1);
        expect(user.rows[0]).toMatchObject({ is_enabled: false, deleted_flag: true });
        await db.query('UPDATE ab_tenant_member SET deleted_flag=true WHERE user_id=$1', [
          user.rows[0].id,
        ]);
        const active = await db.query(
          'SELECT count(*)::int AS count FROM ab_tenant_member WHERE user_id=$1 AND deleted_flag=false',
          [user.rows[0].id],
        );
        expect(active.rows[0].count).toBe(0);
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    await ownedContext.close();
    await db.end();
    await info.attach('profile-wire-observations', {
      body: JSON.stringify(
        {
          claim: 'registered-profile-scenarios-and-json-structures-only',
          userPid,
          operations: [...observed],
          rows,
          journeyError: journeyError ? String(journeyError) : null,
          cleanupErrors: cleanupErrors.map(String),
          imageSha256: hash(imageBytes),
          sessionCleanup,
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });
  }
  if (journeyError || cleanupErrors.length)
    throw new AggregateError(
      [...(journeyError ? [journeyError] : []), ...cleanupErrors],
      `Profile journey or cleanup failed: ${[journeyError, ...cleanupErrors].filter(Boolean).map(String).join(' | ')}`,
    );
});

test('anonymous profile access is refused', async ({ page }, info) => {
  const response = await page.request.get('/api/user/profile');
  expect([401, 403]).toContain(response.status());
  await page.goto('/personal/profile');
  await expect(page).toHaveURL(/\/login/);
  await expect(page.locator('input#identifier, input#email').first()).toBeVisible();
  await capture('PROFILE-05', page, info);
});

test('generated profile operations preserve an email-only account without a username', async ({ page, browser }, info) => {
  test.setTimeout(90_000);
  expect(PG_CONN.database).toMatch(/^auraboot_\d+$/);
  const origin = new URL(String(info.project.use.baseURL)).origin;
  const email = `ic_profile_${randomUUID().replaceAll('-', '')}@example.invalid`;
  const password = `Aa7!${randomUUID()}`;
  const context = await browser.newContext({ baseURL: origin, storageState: { cookies: [], origins: [] }, locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const rows: object[] = [], cleanupErrors: unknown[] = [];
  const sessionCleanup: InternalApiSessionCleanup[] = [];
  let userPid: string | undefined, journeyError: unknown;
  async function record(response: APIResponse, method: string) {
    const payload = await response.json();
    const shape = validate({ method, path: '/api/user/profile', status: response.status(), contentType: response.headers()['content-type'], payload });
    rows.push({ method, path: '/api/user/profile', status: response.status(), payload, shape });
    expect(response.status()).toBe(200);
    expect(payload.code).toBe('0');
    expect(shape.errors).toEqual([]);
    expect(shape.valid).toBe(true);
    expect(payload.data.pid).toBe(userPid);
    expect(payload.data.userName).toBeNull();
    expect(payload.data.email).toBe(email);
    return payload.data;
  }
  try {
    await login(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
    const created = await page.request.post('/api/admin/users', { headers: { Origin: origin }, data: { email, displayName: 'Owned email-only profile', initialPassword: password, roleCodes: ['tenant_admin'], sendInviteEmail: false } });
    const provision = await created.json();
    if (typeof provision.data?.userPid === 'string') userPid = provision.data.userPid;
    expect(created.status()).toBe(200);
    expect(provision.code).toBe('0');
    expect(userPid).toBeTruthy();
    const owned = await context.newPage();
    await login(owned, email, password);
    await record(await owned.request.get('/api/user/profile'), 'get');
    await owned.goto('/personal/profile');
    await new HeaderPage(owned).waitForHeader();
    await expect(owned.getByRole('heading', { name: '个人资料', exact: true })).toBeVisible();
    await owned.getByTestId('profile-edit-btn').click();
    await expect(owned.getByLabel('用户名', { exact: true })).toBeDisabled();
    await expect(owned.getByLabel('用户名', { exact: true })).toHaveValue('');
    await owned.getByLabel('昵称', { exact: true }).fill('Owned email-only edited');
    const saved = owned.waitForResponse(r => /^\/personal\/profile(?:\.data)?$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST');
    await owned.getByTestId('profile-save-btn').click();
    expect((await saved).ok()).toBe(true);
    await expect(owned.getByTestId('profile-edit-btn')).toBeVisible();
    const persisted = await record(await owned.request.get('/api/user/profile'), 'get');
    expect(persisted.nickName).toBe('Owned email-only edited');
    await owned.reload();
    await new HeaderPage(owned).waitForHeader();
    await expect(owned.getByRole('heading', { name: '个人资料', exact: true })).toBeVisible();
    await expect(owned.locator('body')).toContainText('Owned email-only edited');
    await capture('PROFILE-06', owned, info);
  } catch (error) { journeyError = error; }
  finally {
    try {
      sessionCleanup.push(await revokeInternalApiCurrentSession(
        context.request, origin, 'owned-email-profile',
      ));
    } catch (error) { cleanupErrors.push(error); }
    const db = new pg.Client(PG_CONN);
    try {
      await db.connect();
      const found = await db.query('SELECT id, pid FROM ab_user WHERE email=$1 AND user_name IS NULL', [email]);
      expect(found.rowCount).toBe(userPid ? 1 : 0);
      if (userPid) {
        expect(found.rows[0].pid).toBe(userPid);
        const updated = await db.query('UPDATE ab_user SET is_enabled=false, deleted_flag=true, security_version=COALESCE(security_version,0)+1 WHERE pid=$1 AND email=$2 AND user_name IS NULL RETURNING id', [userPid, email]);
        expect(updated.rowCount).toBe(1);
        await db.query('UPDATE ab_tenant_member SET deleted_flag=true WHERE user_id=$1', [updated.rows[0].id]);
        const remaining = await db.query('SELECT count(*)::int AS count FROM ab_tenant_member WHERE user_id=$1 AND deleted_flag=false', [updated.rows[0].id]);
        expect(remaining.rows[0].count).toBe(0);
      }
    } catch (error) { cleanupErrors.push(error); }
    finally { await db.end(); await context.close(); }
    await info.attach('profile-email-only-observations', { body: JSON.stringify({ claim: 'owned-email-only-profile-browser-journey', userPid, rows, sessionCleanup, journeyError: journeyError ? String(journeyError) : null, cleanupErrors: cleanupErrors.map(String) }), contentType: 'application/json' });
  }
  if (journeyError || cleanupErrors.length) throw new AggregateError([...(journeyError ? [journeyError] : []), ...cleanupErrors], 'Email-only profile journey or cleanup failed');
});
