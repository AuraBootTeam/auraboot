import { test, expect } from '../../fixtures';
import {
  uniqueId,
  acceptConfirmDialog,
  executeCommandViaApi,
  findRowInPaginatedList,
  clickRowActionByLocator,
} from '../helpers';
import { ErrorCodes } from '~/shared/services/http-client/types';
import { BASE_URL } from '../../helpers/environments';

/**
 * Team Management E2E Tests (DSL-migrated)
 *
 * `/organization/teams` is now a DSL page on the `ab_team` model
 * (`ab_team_list` / `ab_team_form` / `ab_team_detail`, wired to the custom paths in
 * `app/plugins/core-organization/resources.ts`). The previous custom `teams.tsx`
 * page (`create-team-btn` / `<h1>` / `team-code-input` / `team-save-btn`) is retired,
 * so these tests exercise the DSL list/form/detail flow instead — mirroring the
 * proven `org-department.spec.ts`.
 *
 * Commands: `org:create_team` / `org:update_team` / `org:delete_team`
 * (model `ab_team`, inputFields `code` / `name` / `description`).
 * The `/api/org/teams/:pid/members` REST API still backs member management.
 */
const TEAMS_PATH = '/organization/teams';

test.describe('Team Management', () => {
  test.setTimeout(60000);
  const createdPids: string[] = [];

  // Cleanup: delete all test teams via the model command API.
  test.afterAll(async ({ browser }, testInfo) => {
    if (createdPids.length === 0) return;

    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
      baseURL: testInfo.project.use.baseURL ?? BASE_URL,
    });
    const page = await context.newPage();

    for (const pid of [...createdPids].reverse()) {
      await executeCommandViaApi(page, 'org:delete_team', {}, pid, 'delete').catch(() => {});
    }

    await page.close();
    await context.close();
  });

  async function createTeamViaApi(page: import('@playwright/test').Page, label: string) {
    const code = `e2e-${label}-${Date.now()}`;
    const name = `${label} Team ${uniqueId(label[0].toUpperCase())}`;
    const result = await executeCommandViaApi(page, 'org:create_team', {
      code,
      name,
      description: `${label} target`,
    });
    expect(result.code, `create team via API failed — org plugin may not be imported`).toBe(
      ErrorCodes.SUCCESS,
    );
    createdPids.push(result.recordId);
    return { code, name, pid: result.recordId };
  }

  test('TM-001: should display team list page @smoke', async ({ page }) => {
    await page.goto(TEAMS_PATH, { waitUntil: 'domcontentloaded' });

    // DSL list page heading (h2, not the retired custom-page h1).
    await expect(page.locator('h2').first()).toBeVisible({ timeout: 10000 });
    await expect(
      page.locator('table, [role="table"], [data-testid="dynamic-list"]').first(),
    ).toBeVisible({ timeout: 10000 });
  });

  test('TM-002: should create a team via UI @smoke', async ({ page }) => {
    await page.goto(TEAMS_PATH, { waitUntil: 'domcontentloaded' });

    const addBtn = page
      .locator(
        '[data-testid="toolbar-btn-create"], button:has-text("新增"), button:has-text("新建"):not(:has-text("今日"))',
      )
      .first();
    await expect(addBtn).toBeVisible({ timeout: 10000 });
    await addBtn.click();

    await page.waitForURL((url) => url.pathname.includes('/new'), { timeout: 10000 });
    await page.locator('h2').first().waitFor({ state: 'visible', timeout: 10000 });

    const code = `e2e-team-${Date.now()}`;
    const name = `E2E Test Team ${uniqueId('T')}`;
    await page
      .locator('[data-testid="form-field-code"] input, input[name="code"]')
      .first()
      .fill(code);
    await page
      .locator('[data-testid="form-field-name"] input, input[name="name"]')
      .first()
      .fill(name);
    const descField = page
      .locator(
        '[data-testid="form-field-description"] textarea, [data-testid="form-field-description"] input, textarea[name="description"]',
      )
      .first();
    if (await descField.isVisible({ timeout: 2000 }).catch(() => false)) {
      await descField.fill('Created by E2E test');
    }

    // `status` is a required Select on the create form — open it and pick the
    // first option (edit reuses the record's existing status, so this is
    // create-only). Click the leaf [role="option"], not the Radix viewport.
    const statusTrigger = page
      .locator(
        '[data-testid="select-trigger-status"], [data-testid="form-field-status"] [role="combobox"], [data-testid="form-field-status"] button[role="combobox"]',
      )
      .first();
    if (await statusTrigger.isVisible({ timeout: 2000 }).catch(() => false)) {
      await statusTrigger.click();
      const statusOption = page.locator('[role="option"], [data-slot="select-item"]').first();
      await expect(statusOption).toBeVisible({ timeout: 5000 });
      await statusOption.click();
      await expect(statusOption).toBeHidden({ timeout: 3000 }).catch(() => undefined);
    }

    const cmdResponse = page
      .waitForResponse(
        (r) =>
          r.url().includes('/api/meta/commands/execute/') &&
          r.request().method().toLowerCase() === 'post',
        { timeout: 10000 },
      )
      .catch(() => null);
    await page
      .locator(
        '[data-testid="form-btn-submit"], [data-testid^="form-btn-"], button:has-text("保存"), button:has-text("提交")',
      )
      .first()
      .click();

    const resp = await cmdResponse;
    expect(resp, 'create command should fire').not.toBeNull();
    const body = await resp!.json();
    expect(String(body.code), `create team failed: ${JSON.stringify(body)}`).toBe(
      ErrorCodes.SUCCESS,
    );
    const pid = body?.data?.data?.recordPid;
    if (pid) createdPids.push(pid);

    await page
      .waitForURL((url) => !url.pathname.includes('/new'), { timeout: 10000 })
      .catch(() => {});

    // The new team is reachable via the list (search by the unique name).
    await page.goto(`${TEAMS_PATH}?pageNum=1&pageSize=200`, { waitUntil: 'domcontentloaded' });
    const row = await findRowInPaginatedList(page, name, 15000);
    await expect(row).toBeVisible({ timeout: 5000 });
  });

  test('TM-003: should edit a team via UI', async ({ page }) => {
    const { name } = await createTeamViaApi(page, 'edit');

    await page.goto(`${TEAMS_PATH}?pageNum=1&pageSize=200`, { waitUntil: 'domcontentloaded' });
    const row = await findRowInPaginatedList(page, name, 15000);
    await expect(row).toBeVisible({ timeout: 5000 });

    await clickRowActionByLocator(page, row, 'edit');
    await page.waitForURL((url) => url.pathname.includes('/edit'), { timeout: 10000 });
    await page.locator('h2').first().waitFor({ state: 'visible', timeout: 10000 });

    const updatedName = `Updated Team ${uniqueId('U')}`;
    await page
      .locator('[data-testid="form-field-name"] input, input[name="name"]')
      .first()
      .fill(updatedName);

    const cmdResponse = page
      .waitForResponse(
        (r) =>
          r.url().includes('/api/meta/commands/execute/') &&
          r.request().method().toLowerCase() === 'post',
        { timeout: 10000 },
      )
      .catch(() => null);
    await page
      .locator('[data-testid^="form-btn-"], button:has-text("保存"), button:has-text("提交")')
      .first()
      .click();

    const resp = await cmdResponse;
    expect(resp, 'update command should fire').not.toBeNull();
    const body = await resp!.json();
    expect(String(body.code), `update team failed: ${JSON.stringify(body)}`).toBe(
      ErrorCodes.SUCCESS,
    );

    // Verify the persisted rename is visible back in the list.
    await page.goto(`${TEAMS_PATH}?pageNum=1&pageSize=200`, { waitUntil: 'domcontentloaded' });
    const updatedRow = await findRowInPaginatedList(page, updatedName, 15000);
    await expect(updatedRow).toBeVisible({ timeout: 5000 });
  });

  test('TM-004: should delete a team via UI', async ({ page }) => {
    const { name, pid } = await createTeamViaApi(page, 'del');

    await page.goto(`${TEAMS_PATH}?pageNum=1&pageSize=200`, { waitUntil: 'domcontentloaded' });
    const row = await findRowInPaginatedList(page, name, 15000);
    await expect(row).toBeVisible({ timeout: 5000 });

    const deleteResponse = page
      .waitForResponse(
        (r) =>
          r.url().includes('/api/meta/commands/execute/') &&
          r.request().method().toLowerCase() === 'post',
        { timeout: 10000 },
      )
      .catch(() => null);
    await clickRowActionByLocator(page, row, 'delete');
    await acceptConfirmDialog(page);
    await deleteResponse;

    await expect(page.locator('tbody tr', { hasText: name })).toHaveCount(0, { timeout: 10000 });

    const idx = createdPids.indexOf(pid);
    if (idx >= 0) createdPids.splice(idx, 1);
  });

  test('TM-005: should navigate to team detail and view it @smoke', async ({ page }) => {
    const { name, pid } = await createTeamViaApi(page, 'detail');

    await page.goto(`${TEAMS_PATH}/${pid}`, { waitUntil: 'domcontentloaded' });

    // Detail page renders in the content area and shows the team name.
    await expect(page.locator('main').first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText(name).first()).toBeVisible({ timeout: 10000 });
  });

  test('TM-006: should add and remove a team member', async ({ page, request }) => {
    const { pid: teamPid } = await createTeamViaApi(page, 'member');

    // Resolve a real tenant member (its underlying user pid) to add.
    const membersSearch = await request.post(`${BASE_URL}/api/tenant/members/search`, {
      data: { keyword: '', pageNum: 1, pageSize: 5 },
    });
    expect(membersSearch.ok(), `member search: ${membersSearch.status()}`).toBe(true);
    const membersBody = await membersSearch.json();
    const searchRecords = membersBody?.data?.records ?? membersBody?.data ?? [];
    const userPid = searchRecords[0]?.user?.pid;
    expect(userPid, 'a tenant member with a user pid should exist').toBeTruthy();

    // Add the member via the team REST API — TeamMemberAddRequest accepts userPid
    // (the /api/org/teams member endpoints still back member management post-DSL).
    const addResp = await request.post(`${BASE_URL}/api/org/teams/${teamPid}/members`, {
      data: { userPid },
    });
    expect(addResp.ok(), `add member: ${addResp.status()} ${await addResp.text()}`).toBe(true);

    // Membership is reflected in the team members list.
    const afterAdd = await request.get(`${BASE_URL}/api/org/teams/${teamPid}/members`);
    expect(afterAdd.ok()).toBe(true);
    const addedMembers = (await afterAdd.json())?.data ?? [];
    const memberRow = addedMembers.find(
      (m: { userPid?: string }) => m.userPid === userPid,
    );
    expect(memberRow, `added member should appear in list: ${JSON.stringify(addedMembers)}`).toBeTruthy();

    // The detail page renders for the team with a member.
    await page.goto(`${TEAMS_PATH}/${teamPid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('main').first()).toBeVisible({ timeout: 10000 });

    // Remove the member (delete keys on the membership row pid) and confirm it is gone.
    const removeResp = await request.delete(
      `${BASE_URL}/api/org/teams/${teamPid}/members/${memberRow.pid}`,
    );
    expect(removeResp.ok(), `remove member: ${removeResp.status()}`).toBe(true);

    const afterRemove = await request.get(`${BASE_URL}/api/org/teams/${teamPid}/members`);
    const remaining = (await afterRemove.json())?.data ?? [];
    expect(remaining.some((m: { userPid?: string }) => m.userPid === userPid)).toBe(false);
  });
});
