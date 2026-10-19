/** Team membership journeys use the current DSL entry and real tenant members. */
import { test, expect, type Page } from '../../fixtures';
import type { APIResponse, TestInfo } from '@playwright/test';
import { BASE_URL } from '../../helpers/environments';
import {
  ensureSidebarExpanded, navigateToMenuByClick, findRowInPaginatedList,
  clickRowActionByLocator, acceptConfirmDialog, ensureFilterFormOpen,
} from '../helpers';

type Locale = 'zh-CN' | 'en-US';
type Team = { pid: string; code: string; name: string };
type Member = { pid: string; memberPid: string; userPid: string; userName: string; userEmail: string; role: string };
type Candidate = { pid: string; userPid?: string; email?: string; user?: { pid: string; email: string } };

async function success<T>(response: Pick<APIResponse, 'ok' | 'url' | 'status' | 'json'>): Promise<T> {
  expect(response.ok(), `${response.url()} returned HTTP ${response.status()}`).toBe(true);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  return body.data as T;
}
async function createTeam(page: Page, label: string): Promise<Team> {
  const code = `e2e-${label}-${Date.now()}`;
  const team = await success<Team>(await page.request.post('/api/org/teams', {
    data: { code, name: `Team ${code}`, description: 'Team membership acceptance' },
  }));
  expect(team.pid).toMatch(/^[0-9A-Z]{26}$/);
  return team;
}
async function candidates(page: Page): Promise<Candidate[]> {
  const body = await success<{ records: Candidate[] }>(await page.request.post('/api/tenant/members/search', {
    data: { status: 'active', pageNum: 1, pageSize: 100 },
  }));
  expect(body.records.length).toBeGreaterThanOrEqual(2);
  return body.records;
}
async function addFixture(page: Page, team: Team, candidate: Candidate): Promise<Member> {
  return success<Member>(await page.request.post(`/api/org/teams/${team.pid}/members`, {
    data: { memberPid: candidate.pid, role: 'member' },
  }));
}
async function members(page: Page, team: Team): Promise<Member[]> {
  return success<Member[]>(await page.request.get(`/api/org/teams/${team.pid}/members`));
}
async function openList(page: Page, locale: Locale): Promise<void> {
  await page.goto('/home');
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
  await ensureSidebarExpanded(page);
  await navigateToMenuByClick(page, locale === 'zh-CN' ? ['组织管理', '团队'] : ['Organization', 'Teams']);
  await expect(page).toHaveURL(/\/organization\/teams(?:[?#]|$)/);
  await expect(page.locator('table')).toBeVisible();
}
async function openTeam(page: Page, team: Team, locale: Locale, info?: TestInfo): Promise<void> {
  await openList(page, locale);
  const row = await findRowInPaginatedList(page, team.name);
  await expect(row).toBeVisible();
  if (info) await capture(page, info, `tm-001-${locale}.png`);
  await clickRowActionByLocator(page, row, 'members');
  await expect(page).toHaveURL(new RegExp(`/organization/teams/${team.pid}(?:[?#]|$)`));
  await expect(page.getByText(team.name, { exact: true }).first()).toBeVisible();
}
function memberSection(page: Page, locale: Locale) {
  return page.locator('section').filter({ has: page.getByRole('heading', {
    level: 3, name: locale === 'zh-CN' ? /^团队成员 \(/ : /^Team members \(/,
  }) });
}
async function capture(page: Page, info: TestInfo, name: string): Promise<void> {
  await expect(page.locator('vite-error-overlay')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: /出错了|Something went wrong/i })).toHaveCount(0);
  await page.screenshot({ path: info.outputPath(name), fullPage: true });
}

for (const locale of ['zh-CN', 'en-US'] as const) {
  test.describe(`Team Management Depth ${locale}`, () => {
    test.use({ locale, viewport: { width: 1440, height: 1000 } });
    test.setTimeout(90_000);
    test.beforeEach(async ({ context }) => {
      await context.addCookies([{ name: 'locale', value: locale, url: BASE_URL }]);
    });

    test('TM-010: add member to team via UI @smoke', async ({ page }, info) => {
      const team = await createTeam(page, 'add');
      const candidate = (await candidates(page))[0];
      await openTeam(page, team, locale, info);
      const add = page.getByTestId('team-members-add');
      await expect(add).toBeEnabled();
      await add.click();
      const dialog = page.getByRole('dialog', { name: locale === 'zh-CN' ? '添加团队成员' : 'Add team member' });
      await expect(dialog).toBeVisible();
      await dialog.getByTestId('team-members-select').selectOption(candidate.pid);
      await expect(dialog.getByTestId('team-members-confirm')).toBeEnabled();
      await capture(page, info, `tm-010-open-${locale}.png`);
      const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/org/teams/${team.pid}/members` && r.request().method() === 'POST');
      await dialog.getByTestId('team-members-confirm').click();
      const addedResponse = await response;
      expect(addedResponse.request().postDataJSON()).toEqual({ memberPid: candidate.pid, role: 'member' });
      const added = await success<Member>(addedResponse);
      expect(added.memberPid).toBe(candidate.pid);
      expect(added.pid).not.toBe(candidate.pid);
      await expect(dialog).toBeHidden();
      await page.reload();
      await expect(memberSection(page, locale).getByRole('row').filter({ hasText: added.userEmail })).toBeVisible();
      expect((await members(page, team)).map(member => member.pid)).toContain(added.pid);
      await capture(page, info, `tm-010-save-${locale}.png`);
    });

    test('TM-011: remove member from team via UI', async ({ page }, info) => {
      const team = await createTeam(page, 'remove');
      const added = await addFixture(page, team, (await candidates(page))[0]);
      await openTeam(page, team, locale);
      const row = memberSection(page, locale).getByRole('row').filter({ hasText: added.userEmail });
      await expect(row).toBeVisible();
      const remove = page.getByTestId(`team-members-remove-${added.pid}`);
      const deletes: string[] = [];
      page.on('request', request => { if (request.method() === 'DELETE' && new URL(request.url()).pathname.startsWith(`/api/org/teams/${team.pid}/members/`)) deletes.push(request.url()); });
      const expectedMessage = locale === 'zh-CN' ? `确认将 ${added.userName} 移出团队？` : `Remove ${added.userName} from the team?`;
      page.once('dialog', async dialog => {
        expect(dialog.type()).toBe('confirm'); expect(dialog.message()).toBe(expectedMessage);
        await info.attach('remove-confirm-cancel', { body: JSON.stringify({ message: dialog.message(), accepted: false }), contentType: 'application/json' });
        await dialog.dismiss();
      });
      await remove.click();
      await expect(row).toBeVisible();
      expect(deletes).toHaveLength(0);
      expect((await members(page, team)).map(member => member.pid)).toContain(added.pid);
      await capture(page, info, `tm-011-cancel-${locale}.png`);
      page.once('dialog', async dialog => { expect(dialog.message()).toBe(expectedMessage); await dialog.accept(); });
      const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/org/teams/${team.pid}/members/${added.pid}` && r.request().method() === 'DELETE');
      await remove.click();
      await success<boolean>(await response);
      expect(deletes).toHaveLength(1);
      await page.reload();
      await expect(memberSection(page, locale).getByText(locale === 'zh-CN' ? '暂无团队成员' : 'No team members', { exact: true })).toBeVisible();
      expect(await members(page, team)).toEqual([]);
      await capture(page, info, `tm-011-save-${locale}.png`);
    });

    test('TM-012: governed team leadership changes persist in the member role display', async ({ page }) => {
      const team = await createTeam(page, 'leader');
      const candidate = (await candidates(page))[0];
      const added = await addFixture(page, team, candidate);
      await openTeam(page, team, locale);
      const row = memberSection(page, locale).getByRole('row').filter({ hasText: added.userEmail });
      await expect(row.getByText(locale === 'zh-CN' ? '成员' : 'Member', { exact: true })).toBeVisible();
      // Leadership is governed by the team API; the membership picker adds ordinary members.
      const userPid = candidate.userPid ?? candidate.user?.pid;
      expect(userPid).toEqual(expect.any(String));
      await success<Team>(await page.request.put(`/api/org/teams/${team.pid}`, { data: { leaderId: userPid } }));
      expect((await members(page, team)).find(member => member.pid === added.pid)?.role).toBe('LEADER');
      await page.reload();
      await expect(row.getByText(locale === 'zh-CN' ? '负责人' : 'Leader', { exact: true })).toBeVisible();
    });

    test('TM-013: non-empty team can be cascade deleted', async ({ page }) => {
      const team = await createTeam(page, 'cascade');
      await addFixture(page, team, (await candidates(page))[0]);
      expect(await members(page, team)).toHaveLength(1);
      await openList(page, locale);
      const row = await findRowInPaginatedList(page, team.name);
      const response = page.waitForResponse(r => decodeURIComponent(new URL(r.url()).pathname).endsWith('/commands/execute/org:delete_team') && r.request().method() === 'POST');
      await clickRowActionByLocator(page, row, 'delete');
      await acceptConfirmDialog(page);
      const deleted = await response;
      expect(deleted.request().postDataJSON().targetRecordPid).toBe(team.pid);
      await success<unknown>(deleted);
      await expect(page.getByText(team.name, { exact: true })).toHaveCount(0);
      expect((await success<Team[]>(await page.request.get('/api/org/teams'))).some(item => item.pid === team.pid)).toBe(false);
    });

    test('TM-014: search teams on list page', async ({ page }) => {
      const team = await createTeam(page, 'search');
      await openList(page, locale);
      await expect(page.getByTestId('filters-toggle')).toBeVisible();
      await ensureFilterFormOpen(page);
      const input = page.locator('[data-authoring-node-id="name"] input').first();
      await expect(input).toBeVisible();
      await input.fill(team.name);
      const response = page.waitForResponse(r => r.url().includes('/api/dynamic/ab_team/list') && r.request().method() === 'GET');
      await page.getByTestId('filter-search').click();
      const body = await success<{ records: Array<{ name: string }> }>(await response);
      expect(body.records.map(item => item.name)).toEqual([team.name]);
      await expect(page.locator('tbody tr').filter({ hasText: team.name })).toBeVisible();
    });

    test('TM-015: team member list displays correctly @smoke', async ({ page }, info) => {
      const team = await createTeam(page, 'list');
      const options = await candidates(page);
      const added = await Promise.all(options.slice(0, 2).map(candidate => addFixture(page, team, candidate)));
      await openTeam(page, team, locale);
      const section = memberSection(page, locale);
      for (const member of added) {
        const row = section.getByRole('row').filter({ hasText: member.userEmail });
        await expect(row).toContainText(member.userName);
        await expect(row.getByText(locale === 'zh-CN' ? '成员' : 'Member', { exact: true })).toBeVisible();
      }
      await expect(section.getByRole('columnheader')).toHaveText(locale === 'zh-CN' ? ['用户', '邮箱', '角色', '加入时间', '操作'] : ['User', 'Email', 'Role', 'Joined', 'Actions']);
      await capture(page, info, `tm-015-${locale}.png`);
    });

    test('TM-readonly: the declared view capability exposes facts without member writes', async ({ page, browser }, info) => {
      const team = await createTeam(page, 'readonly');
      const added = await addFixture(page, team, (await candidates(page))[0]);
      const roleCode = `team_reader_${Date.now()}`;
      const role = await success<{ pid: string }>(await page.request.post('/api/roles', {
        data: { code: roleCode, name: roleCode, type: 'custom', status: 'active', scopeType: 'tenant', defaultDataScopeType: 'all' },
      }));
      await success<unknown>(await page.request.put(`/api/permission/capabilities?rolePid=${role.pid}`, {
        data: ['org.cap.team_view'],
      }));
      const email = `${roleCode}@e2e.local`;
      await success<unknown>(await page.request.post('/api/admin/users', {
        data: { email, displayName: 'Team reader', initialPassword: 'Test2026x', roleCodes: [roleCode], sendInviteEmail: false },
      }));
      const context = await browser.newContext({
        baseURL: BASE_URL, locale, viewport: { width: 1440, height: 1000 },
        storageState: { cookies: [], origins: [] }, extraHTTPHeaders: { Referer: `${BASE_URL}/` },
      });
      try {
        await context.addCookies([{ name: 'locale', value: locale, url: BASE_URL }]);
        const reader = await context.newPage();
        await reader.goto('/login');
        await expect(reader.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
        await reader.locator('input#identifier').fill(email);
        await reader.locator('input#password').fill('Test2026x');
        await reader.locator('form button[type="submit"]').click();
        await reader.waitForURL(url => !url.pathname.includes('/login'));
        const writes: string[] = [];
        reader.on('request', request => {
          if (request.method() !== 'GET' && new URL(request.url()).pathname.startsWith(`/api/org/teams/${team.pid}/members`)) writes.push(request.method());
        });
        await openTeam(reader, team, locale);
        await expect(memberSection(reader, locale).getByRole('row').filter({ hasText: added.userEmail })).toBeVisible();
        await expect(reader.getByTestId('team-members-add')).toHaveCount(0);
        await expect(reader.locator('[data-testid^="team-members-remove-"]')).toHaveCount(0);
        expect(writes).toEqual([]);
        await capture(reader, info, `tm-readonly-${locale}.png`);
        const denied = await reader.request.post(`/api/org/teams/${team.pid}/members`, {
          data: { memberPid: added.memberPid, role: 'member' },
        });
        expect(denied.status()).toBe(403);
        expect(String((await denied.json()).code)).toBe('403');
        expect((await members(page, team)).map(member => member.pid)).toEqual([added.pid]);
      } finally { await context.close(); }
    });

    test('TM-recovery: a failed member request requires explicit retry before adding', async ({ page }, info) => {
      const team = await createTeam(page, 'recovery');
      let fail = true;
      let finishLoading!: () => void;
      const loading = new Promise<void>(resolve => { finishLoading = resolve; });
      await page.route(`**/api/org/teams/${team.pid}/members`, async route => {
        if (fail) { await loading; await route.abort('failed'); }
        else await route.continue();
      });
      try {
        await openTeam(page, team, locale);
        await expect(memberSection(page, locale).getByRole('status')).toBeVisible();
        await expect(page.getByTestId('team-members-add')).toBeDisabled();
        await capture(page, info, `tm-loading-${locale}.png`);
        finishLoading();
        await expect(page.getByTestId('team-members-load-error')).toBeVisible();
        await expect(page.getByTestId('team-members-add')).toBeDisabled();
        await capture(page, info, `tm-recovery-${locale}.png`);
        fail = false;
        const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/org/teams/${team.pid}/members`);
        await page.getByTestId('team-members-retry').click();
        await success<Member[]>(await response);
        await expect(page.getByTestId('team-members-load-error')).toHaveCount(0);
        await expect(page.getByTestId('team-members-add')).toBeEnabled();
        await capture(page, info, `tm-recovered-${locale}.png`);
      } finally { finishLoading(); }
    });
  });
}
