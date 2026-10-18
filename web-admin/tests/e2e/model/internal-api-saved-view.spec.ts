import { test, expect, type Page, type TestInfo, type Response } from '@playwright/test';
import fs from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { provisionInternalApiUser } from '../../helpers/internal-api-owned-user';
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

test('generated SavedView sharing preserves the owner and anonymous browser journeys', async ({
  browser,
}, info) => {
  test.setTimeout(150_000);
  const baseURL = info.project.use.baseURL;
  if (!baseURL) throw new Error('Managed browser base URL is required');
  const actor = await provisionInternalApiUser(browser, baseURL);
  const anonymous = await browser.newContext({
    baseURL,
    storageState: { cookies: [], origins: [] },
    viewport: { width: 1440, height: 1000 },
    locale: 'en-US',
  });
  await anonymous.addCookies([{ name: 'locale', value: 'en-US', url: actor.origin }]);
  await anonymous.addInitScript(() => localStorage.setItem('locale', 'en-US'));
  const recipient = await anonymous.newPage();
  const page = actor.page;
  const name = `Shared settings ${randomUUID().slice(0, 8)}`;
  const teamName = `Owned team ${randomUUID().slice(0, 8)}`;
  const headers = { Origin: actor.origin };
  let pid: string | undefined, teamPid: string | undefined, journeyError: unknown;
  const cleanupErrors: unknown[] = [],
    rows: object[] = [];

  async function record(response: Response, method: string, path: string, status = 200) {
    const payload = await response.json();
    const shape = validate({
      method,
      path,
      status: response.status(),
      contentType: response.headers()['content-type'],
      payload,
    });
    rows.push({ method, path, status: response.status(), shape });
    expect(response.status()).toBe(status);
    expect(shape.errors).toEqual([]);
    expect(shape.valid).toBe(true);
    if (status === 200) expect(payload.code).toBe('0');
    else expect(payload.code).not.toBe('0');
    return payload.data;
  }
  async function openPanel() {
    await page.getByTestId('view-selector-trigger').click();
    await page.getByTestId('view-selector-manage').click();
    await expect(page.getByTestId('saved-view-manage-panel')).toBeVisible();
    await page.getByTestId(`saved-view-action-share-${pid}`).click();
  }
  function publicResponse(token: string) {
    return recipient.waitForResponse(
      (r) => new URL(r.url()).pathname === `/api/views/shared/${token}`,
    );
  }
  try {
    const createdTeam = await actor.context.request.post('/api/org/teams', {
      headers,
      data: { name: teamName, code: `ic_share_${randomUUID().replaceAll('-', '').slice(0, 12)}` },
    });
    const teamPayload = await createdTeam.json();
    if (typeof teamPayload.data?.pid === 'string') teamPid = teamPayload.data.pid;
    expect(createdTeam.status()).toBe(200);
    expect(teamPayload.code).toBe('0');
    const created = await actor.context.request.post('/api/views', {
      headers,
      data: {
        name,
        modelCode: 'ab_team',
        pageKey: 'ab_team_list',
        scope: 'global',
        viewType: 'table',
        viewConfig: {
          columns: [{ fieldCode: 'name', visible: true, order: 0 }],
          filters: [],
          sorts: [],
        },
      },
    });
    const payload = await created.json();
    if (typeof payload.data?.pid === 'string') pid = payload.data.pid;
    expect(created.status()).toBe(200);
    expect(payload.code).toBe('0');
    expect(pid).toBeTruthy();
    expect(payload.data.actions).toContain('share');
    const menu = page.locator('nav a[href="/organization/teams"]');
    if (!(await menu.isVisible()))
      await page
        .locator('nav')
        .getByRole('button', { name: /Organization|组织管理/ })
        .click();
    await menu.click();
    await expect(page.locator('tbody tr').filter({ hasText: teamName })).toBeVisible();
    await openPanel();
    await expect(page.getByTestId(`saved-view-share-generate-${pid}`)).toHaveText(
      'Generate share link',
    );
    await expect(page.getByTestId(`saved-view-share-state-${pid}`)).toContainText(
      'including saved filters',
    );
    await expect(page.getByTestId('saved-view-manage-panel')).not.toContainText(/[\u3400-\u9fff]/);
    await expect(page.getByRole('heading', { name: 'Manage views', exact: true })).toBeVisible();
    await capture('SHARE-01', page, info);
    const generatedResponse = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === `/api/views/${pid}/share` && r.request().method() === 'POST',
    );
    await page.getByTestId(`saved-view-share-generate-${pid}`).click();
    const generated = await record(await generatedResponse, 'post', '/api/views/{viewPid}/share');
    const link = await page.getByTestId(`saved-view-share-link-${pid}`).inputValue();
    expect(new URL(link, baseURL).pathname).toBe(`/share/${generated.token}`);
    await expect(page.getByTestId(`saved-view-share-state-${pid}`)).toContainText(
      'Business records still require sign-in',
    );
    await page.getByTestId(`saved-view-share-close-${pid}`).click();
    const reopenedStatus = page.waitForResponse(
      (r) => new URL(r.url()).pathname === `/api/views/${pid}/share/status`,
    );
    await page.getByTestId(`saved-view-action-share-${pid}`).click();
    expect(
      (await record(await reopenedStatus, 'get', '/api/views/{viewPid}/share/status')).token,
    ).toBe(generated.token);
    await expect(page.getByTestId(`saved-view-share-link-${pid}`)).toHaveValue(link);
    await capture('SHARE-02', page, info);
    let received = publicResponse(generated.token);
    await recipient.goto(`/share/${generated.token}`);
    const shared = await record(await received, 'get', '/api/views/shared/{shareToken}');
    expect(shared).toMatchObject({ name, modelCode: 'ab_team' });
    expect(shared).not.toHaveProperty('records');
    expect(shared.viewConfig).not.toHaveProperty('__share');
    await expect(recipient.getByRole('heading', { name, exact: true })).toBeVisible();
    await expect(recipient.getByTestId('public-shared-view').locator('dd')).toHaveText([
      String(shared.viewConfig.columns.length),
      String(shared.viewConfig.filters.length),
      String(shared.viewConfig.sorts.length),
    ]);
    await expect(recipient.locator('table')).toHaveCount(0);
    await expect(recipient.locator('body')).not.toContainText(teamName);
    await capture('SHARE-03', recipient, info);
    await page.getByTestId(`saved-view-share-revoke-${pid}`).click();
    await expect(page.getByTestId('confirm-dialog')).toContainText(
      'The current link will stop working immediately.',
    );
    await capture('SHARE-04', page, info);
    const revoke = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === `/api/views/${pid}/share` &&
        r.request().method() === 'DELETE',
    );
    await page.getByTestId('confirm-ok').click();
    await record(await revoke, 'delete', '/api/views/{viewPid}/share');
    await expect(page.getByTestId(`saved-view-share-generate-${pid}`)).toBeVisible();
    received = publicResponse(generated.token);
    await recipient.reload();
    await record(await received, 'get', '/api/views/shared/{shareToken}', 404);
    await expect(recipient.getByRole('alert')).toContainText('This shared link is unavailable.');
    await expect(recipient.getByTestId('public-shared-view')).toHaveCount(0);
    await capture('SHARE-05', recipient, info);
    // Protected/expired receiver fixtures use the producer API because the current owner UI
    // exposes generation/revocation only; these are not claims of owner-side password editing.
    const password = `Share-${randomUUID()}`;
    const protectedResponse = await actor.context.request.post(`/api/views/${pid}/share`, {
      headers,
      data: { password, expireHours: 1 },
    });
    const protectedPayload = await protectedResponse.json();
    expect(protectedResponse.status()).toBe(200);
    expect(protectedPayload.code).toBe('0');
    const token: string = protectedPayload.data.token;
    received = publicResponse(token);
    await recipient.goto(`/share/${token}`);
    await record(await received, 'get', '/api/views/shared/{shareToken}', 422);
    await expect(recipient.getByRole('heading', { name: 'Password required' })).toBeVisible();
    await capture('SHARE-06', recipient, info);
    await recipient.getByLabel('Access password').fill('wrong-controlled-fixture-password');
    received = publicResponse(token);
    await recipient.getByRole('button', { name: 'Open shared view' }).click();
    await record(await received, 'get', '/api/views/shared/{shareToken}', 422);
    await expect(recipient.getByRole('alert')).toHaveText(
      'The password is incorrect. Please try again.',
    );
    await expect(recipient.getByLabel('Access password')).toHaveAttribute('aria-invalid', 'true');
    await capture('SHARE-07', recipient, info);
    await recipient.getByLabel('Access password').fill(password);
    received = publicResponse(token);
    await recipient.getByRole('button', { name: 'Open shared view' }).click();
    await record(await received, 'get', '/api/views/shared/{shareToken}');
    await expect(recipient.getByRole('heading', { name, exact: true })).toBeVisible();
    await expect(recipient.locator('table')).toHaveCount(0);
    await capture('SHARE-08', recipient, info);
    const expiredResponse = await actor.context.request.post(`/api/views/${pid}/share`, {
      headers,
      data: { expireHours: -1 },
    });
    const expiredPayload = await expiredResponse.json();
    expect(expiredResponse.status()).toBe(200);
    expect(expiredPayload.code).toBe('0');
    received = publicResponse(expiredPayload.data.token);
    await recipient.goto(`/share/${expiredPayload.data.token}`);
    await record(await received, 'get', '/api/views/shared/{shareToken}', 422);
    await expect(recipient.getByRole('alert')).toContainText('This shared link has expired.');
    await expect(recipient.getByRole('button', { name: 'Retry' })).toBeEnabled();
    await capture('SHARE-09', recipient, info);
  } catch (error) {
    journeyError = error;
  } finally {
    if (pid) {
      try {
        const revoke = await actor.context.request.delete(`/api/views/${pid}/share`, { headers });
        expect(revoke.status()).toBe(200);
        expect((await revoke.json()).code).toBe('0');
        const removed = await actor.context.request.delete(`/api/views/${pid}`, { headers });
        expect(removed.status()).toBe(200);
        expect((await removed.json()).code).toBe('0');
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    if (teamPid) {
      try {
        const removed = await actor.context.request.delete(`/api/org/teams/${teamPid}`, {
          headers,
        });
        expect(removed.status()).toBe(200);
        expect((await removed.json()).code).toBe('0');
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    try {
      await actor.dispose();
    } catch (error) {
      cleanupErrors.push(error);
    }
    await anonymous.close();
    await info.attach('saved-view-browser-observations', {
      body: JSON.stringify(
        {
          claim: 'registered-sharing-browser-scenarios-only',
          rows,
          owned: { userPid: actor.userPid, viewPid: pid, teamPid },
          journeyError: journeyError ? String(journeyError) : null,
          cleanupErrors: cleanupErrors.map(String),
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
      'SavedView browser journey or cleanup failed',
    );
});
