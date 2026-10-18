import { test, expect, type Page, type TestInfo } from '@playwright/test';
import fs from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { provisionInternalApiUser } from '../../helpers/internal-api-owned-user';
import { createResponseShapeValidator } from '../../../../scripts/internal-api/response-shape.mjs';

const catalog = JSON.parse(
  await fs.readFile(
    new URL('../../../../scripts/internal-api/catalog.json', import.meta.url),
    'utf8',
  ),
);
const document = JSON.parse(
  await fs.readFile(
    new URL('../../../../contracts/internal-web-admin/openapi.json', import.meta.url),
    'utf8',
  ),
);
const validate = createResponseShapeValidator(document);
test.use({ storageState: { cookies: [], origins: [] } });
async function capture(id: string, page: Page, info: TestInfo) {
  const scene = catalog.scenarios.find((row: { id: string }) => row.id === id);
  expect(scene?.test).toBe(info.title);
  const path = info.outputPath(scene.screenshot);
  await page.screenshot({ path, fullPage: true, animations: 'disabled' });
  await info.attach(id, { path, contentType: 'image/png' });
  info.annotations.push({ type: 'internal-api-scenario', description: id });
}

test('generated team memberships preserve the menu add reload and remove journey', async ({
  browser,
  baseURL,
}, info) => {
  test.setTimeout(120_000);
  if (!baseURL) throw new Error('A managed runtime is required');
  const actor = await provisionInternalApiUser(browser, baseURL);
  const { page, context, origin } = actor;
  const code = `ic_team_${randomUUID().replaceAll('-', '').slice(0, 16)}`;
  const name = `Contract team ${code.slice(-8)}`;
  const rows: object[] = [],
    cleanupErrors: unknown[] = [],
    pageErrors: string[] = [];
  let teamPid: string | undefined, journeyError: unknown;
  page.on('pageerror', (error) => pageErrors.push(error.message));
  async function checked(
    response: import('@playwright/test').APIResponse | import('@playwright/test').Response,
    method: string,
    path: string,
  ) {
    const payload = await response.json();
    const shape = validate({
      method,
      path,
      status: response.status(),
      contentType: response.headers()['content-type'],
      payload,
    });
    rows.push({ method, path, status: response.status(), shape });
    expect(response.status()).toBe(200);
    expect(shape.errors).toEqual([]);
    expect(payload.code).toBe('0');
    return payload.data;
  }
  try {
    const response = await context.request.post('/api/org/teams', {
      headers: { Origin: origin },
      data: { code, name },
    });
    const payload = await response.json();
    if (typeof payload.data?.pid === 'string') teamPid = payload.data.pid;
    expect(response.status()).toBe(200);
    expect(payload.code).toBe('0');
    expect(teamPid).toBeTruthy();
    const menu = page.locator('nav a[href="/organization/teams"]');
    if (!(await menu.isVisible()))
      await page
        .locator('nav')
        .getByRole('button', { name: /Organization|组织管理/i })
        .click();
    await menu.click();
    const search = page.getByTestId('list-search-input');
    await expect(search).toBeVisible();
    await search.fill(name);
    await search.press('Enter');
    const row = page.locator('tbody tr').filter({ hasText: name });
    await expect(row).toHaveCount(1);
    await row.hover();
    await row.getByTestId('row-action-members').click();
    await expect(page).toHaveURL(new RegExp(`/organization/teams/${teamPid}$`));
    await expect(page.getByText('No team members', { exact: true })).toBeVisible();
    await expect(page.getByTestId('report-generate-button')).toHaveText('Report');
    await capture('TEAM-01', page, info);
    await page.getByTestId('team-members-add').click();
    const select = page.getByTestId('team-members-select');
    await expect(select.locator('option').filter({ hasText: actor.username })).toHaveCount(1);
    const option = select.locator('option').filter({ hasText: actor.username });
    const tenantMemberPid = await option.getAttribute('value');
    expect(tenantMemberPid).toBeTruthy();
    await select.selectOption(tenantMemberPid!);
    await capture('TEAM-02', page, info);
    const addedResponse = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === `/api/org/teams/${teamPid}/members` &&
        r.request().method() === 'POST',
    );
    await page.getByTestId('team-members-confirm').click();
    const added = await addedResponse;
    expect(added.request().postDataJSON()).toEqual({ memberPid: tenantMemberPid, role: 'member' });
    const membership = await checked(added, 'post', '/api/org/teams/{teamPid}/members');
    expect(membership.userPid).toBe(actor.userPid);
    expect(membership.memberPid).toBe(tenantMemberPid);
    expect(membership.pid).not.toBe(tenantMemberPid);
    expect(membership.userEmail).toBeNull();
    await page.reload({ waitUntil: 'load' });
    const remove = page.getByTestId(`team-members-remove-${membership.pid}`);
    await expect(remove).toBeVisible();
    await expect(remove.locator('xpath=ancestor::tr')).toContainText(actor.username);
    await capture('TEAM-03', page, info);
    const removedResponse = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === `/api/org/teams/${teamPid}/members/${membership.pid}` &&
        r.request().method() === 'DELETE',
    );
    page.once('dialog', (dialog) => dialog.accept());
    await remove.click();
    await checked(await removedResponse, 'delete', '/api/org/teams/{teamPid}/members/{memberPid}');
    await page.reload({ waitUntil: 'load' });
    await expect(page.getByText('No team members', { exact: true })).toBeVisible();
    expect(
      await checked(
        await context.request.get(`/api/org/teams/${teamPid}/members`),
        'get',
        '/api/org/teams/{teamPid}/members',
      ),
    ).toEqual([]);
    expect(pageErrors).toEqual([]);
    await capture('TEAM-04', page, info);
  } catch (error) {
    journeyError = error;
  } finally {
    if (teamPid) {
      try {
        await checked(
          await context.request.delete(`/api/org/teams/${teamPid}`, {
            headers: { Origin: origin },
          }),
          'delete',
          '/api/org/teams/{pid}',
        );
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    try {
      await actor.dispose();
    } catch (error) {
      cleanupErrors.push(error);
    }
    await info.attach('team-browser-observations', {
      body: JSON.stringify({
        claim:
          'owned-member-menu-journey-and-listed-response-structures; no full tenant/role acceptance',
        rows,
        pageErrors,
        journeyError: journeyError ? String(journeyError) : null,
        cleanupErrors: cleanupErrors.map(String),
      }),
      contentType: 'application/json',
    });
  }
  if (journeyError || cleanupErrors.length)
    throw new AggregateError(
      [...(journeyError ? [journeyError] : []), ...cleanupErrors],
      'Team browser journey or cleanup failed',
    );
});
