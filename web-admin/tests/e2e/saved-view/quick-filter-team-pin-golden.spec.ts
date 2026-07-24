/**
 * E2E Golden: a team lead pins a team-scoped SavedView to the team's quick-filter
 * chip row (M3 — team pin).
 *
 * The view-manage panel surfaces a "team views" section (only when the user can
 * manage team pins) with a per-view team-pin toggle
 * (saved-view-action-team-pin-<pid>). Pinning writes a scope='team' row in
 * ab_saved_view_chip_pin; every member of that team then sees the view as a
 * quick-filter-view-<pid> chip that switches to it on click. Unpinning removes it.
 *
 * The cross-user visibility contract (member sees / non-member does not) is proven
 * rigorously by the backend SavedViewChipPinTeamIT; this golden proves the
 * single-user authoring UI + team-scoped chip appearance end to end. It seeds its
 * own team (admin as a leader-member) + a team-scoped view via the authenticated
 * REST API, since a fresh golden stack bootstraps only the admin and no teams.
 */
import { test, expect, type Page } from '@playwright/test';
import {
  cleanupGeneratedSavedViews,
  createOrReuseSavedView,
  navigateToOrderViaSidebar,
} from './helpers';

import { acquireSavedViewLock, releaseSavedViewLock } from './_saved-view-lock';

// Serialize e2et_order saved-view specs — they share the model's per-user view
// state (active view / created views) under the shared admin storageState.
test.beforeAll(async () => { await acquireSavedViewLock('quick-filter-team-pin-golden'); });
test.afterAll(() => { releaseSavedViewLock('quick-filter-team-pin-golden'); });

const MODEL_CODE = 'e2et_order';
const PAGE_KEY = 'e2et_order_list';

async function jsonData(resp: { ok(): boolean; json(): Promise<any>; text(): Promise<string>; status(): number }) {
  if (!resp.ok()) {
    throw new Error(`request failed status=${resp.status()} body=${await resp.text()}`);
  }
  const body = await resp.json();
  return body.data ?? body;
}

async function openTeamPinToggle(page: Page, pid: string) {
  await page.getByTestId('view-selector-trigger').click();
  await expect(page.getByTestId('view-selector-search')).toBeVisible();
  await page.getByTestId('view-selector-manage').click();
  const panel = page.getByTestId('saved-view-manage-panel');
  await expect(panel).toBeVisible();
  await expect(panel.getByTestId('saved-view-team-group')).toBeVisible();
  return panel.getByTestId(`saved-view-action-team-pin-${pid}`);
}

async function closeManagePanel(page: Page) {
  await page.keyboard.press('Escape');
  await page
    .getByTestId('saved-view-manage-panel')
    .waitFor({ state: 'hidden', timeout: 5000 })
    .catch(() => {});
}

test.describe('Quick-filter team pin (M3)', () => {
  let teamPid = '';
  let teamViewPid = '';

  test.beforeEach(async ({ page }) => {
    teamPid = '';
    teamViewPid = '';
    await cleanupGeneratedSavedViews(page, { modelCode: MODEL_CODE, pageKey: PAGE_KEY });

    // Resolve the current admin's business pid.
    const me = await jsonData(await page.request.get('/api/auth/me'));
    const adminPid: string = me.user?.pid;
    expect(adminPid, 'admin pid from /api/auth/me').toBeTruthy();

    // Seed a team and make the admin a leader-member of it.
    const stamp = Date.now();
    const team = await jsonData(
      await page.request.post('/api/org/teams', {
        data: { code: `vc_team_${stamp}`, name: `VC Team ${stamp}` },
      }),
    );
    teamPid = team.pid ?? team.teamPid;
    expect(teamPid, 'created team pid').toBeTruthy();

    await jsonData(
      await page.request.post(`/api/org/teams/${teamPid}/members`, {
        data: { userPid: adminPid, role: 'leader' },
      }),
    );

    // Create a team-scoped view for that team. A `{ meta: {...} }` object config
    // is never reused (=== identity), so this always creates a fresh view.
    const { pid } = await createOrReuseSavedView(page, {
      modelCode: MODEL_CODE,
      pageKey: PAGE_KEY,
      name: `VC_TeamPin_${stamp}`,
      viewType: 'table',
      scope: 'team',
      teamId: teamPid,
      viewConfig: { meta: { m3TeamPinGolden: true } },
      expectSuccess: true,
    });
    teamViewPid = pid;
    expect(teamViewPid, 'created team view pid').toBeTruthy();
  });

  test.afterEach(async ({ page }) => {
    if (teamViewPid && teamPid) {
      await page.request
        .delete(`/api/views/${teamViewPid}/pin?scope=team&teamId=${teamPid}`)
        .catch(() => {});
    }
    await cleanupGeneratedSavedViews(page, { modelCode: MODEL_CODE, pageKey: PAGE_KEY });
    if (teamPid) {
      await page.request.delete(`/api/org/teams/${teamPid}`).catch(() => {});
    }
  });

  test('VC-T01: team-pin a team view -> chip appears for the member -> switches -> unpin -> gone', async ({
    page,
  }) => {
    await navigateToOrderViaSidebar(page);
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30000 });
    // Not pinned yet -> no chip.
    await expect(page.getByTestId(`quick-filter-view-${teamViewPid}`)).toHaveCount(0);

    // Pin for the team from the manage panel's team section.
    const teamPinBtn = await openTeamPinToggle(page, teamViewPid);
    await expect(teamPinBtn).toHaveAttribute('data-team-pinned', 'false');
    await teamPinBtn.click();
    await expect(teamPinBtn).toHaveAttribute('data-team-pinned', 'true');
    await closeManagePanel(page);

    // The member (admin, a team member) sees the chip; it switches the view.
    const chip = page.getByTestId(`quick-filter-view-${teamViewPid}`);
    await expect(chip).toBeVisible({ timeout: 15000 });
    await chip.click();
    await expect(page).toHaveURL(new RegExp(`view=${teamViewPid}`), { timeout: 10000 });

    // Unpin -> chip disappears.
    const teamPinBtn2 = await openTeamPinToggle(page, teamViewPid);
    await expect(teamPinBtn2).toHaveAttribute('data-team-pinned', 'true');
    await teamPinBtn2.click();
    await expect(teamPinBtn2).toHaveAttribute('data-team-pinned', 'false');
    await closeManagePanel(page);

    await expect(page.getByTestId(`quick-filter-view-${teamViewPid}`)).toHaveCount(0);
  });
});
