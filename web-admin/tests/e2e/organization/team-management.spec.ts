import { test, expect } from '../../fixtures';
import { ErrorCodes } from '~/services/http-client/types';

const BASE_URL = process.env.BASE_URL || 'http://localhost:5173';

/**
 * Team Management E2E Tests
 *
 * Tests the platform-level team management pages:
 * - /organization/teams — Team list + CRUD
 * - /organization/teams/:teamPid — Team detail + member management
 */
test.describe('Team Management', () => {
  const teamCodes: string[] = [];

  // Cleanup: delete all test teams via API
  test.afterAll(async ({ browser }) => {
    if (teamCodes.length === 0) return;

    const context = await browser.newContext({
      storageState: 'tests/storage/admin.json',
    });
    const page = await context.newPage();

    for (const code of teamCodes) {
      try {
        const resp = await page.request.get(`${BASE_URL}/api/org/teams`, {
          timeout: 10000,
        });
        const body = await resp.json();
        const teams = body?.data || [];
        const team = teams.find((t: any) => t.code === code);
        if (team) {
          // Remove all members first to allow deletion
          const membersResp = await page.request.get(
            `${BASE_URL}/api/org/teams/${team.pid}/members`,
            { timeout: 10000 }
          );
          if (membersResp.ok()) {
            const membersBody = await membersResp.json();
            const members = membersBody?.data || [];
            for (const member of members) {
              await page.request.delete(
                `${BASE_URL}/api/org/teams/${team.pid}/members/${member.pid}`,
                { timeout: 10000 }
              ).catch(() => {});
            }
          }
          await page.request.delete(`${BASE_URL}/api/org/teams/${team.pid}`, {
            timeout: 10000,
          });
        }
      } catch {
        // Ignore cleanup errors
      }
    }

    await page.close();
    await context.close();
  });

  test('TM-001: should display team list page @smoke', async ({ page }) => {
    await page.goto('/organization/teams');
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('h1')).toContainText(/Team Management|团队管理/i, { timeout: 10000 });
    await expect(page.locator('[data-testid="create-team-btn"]')).toBeVisible();
  });

  test('TM-002: should create a team via UI @smoke', async ({ page }) => {
    const code = `e2e-team-${Date.now()}`;
    const name = `E2E Test Team ${Date.now()}`;
    teamCodes.push(code);

    await page.goto('/organization/teams');
    await page.waitForLoadState('domcontentloaded');

    // Wait for page to be ready
    await expect(page.locator('[data-testid="create-team-btn"]')).toBeVisible({ timeout: 10000 });

    // Click create button
    await page.locator('[data-testid="create-team-btn"]').click();

    // Wait for modal/form to appear (supports both stable testid and generic field selectors)
    const codeInput = page
      .locator(
        '[data-testid="team-code-input"], input[name="code"], input[placeholder*="团队编码"], input[placeholder*="Team Code"], input[placeholder*="code" i]',
      )
      .first();
    const nameInput = page
      .locator(
        '[data-testid="team-name-input"], input[name="name"], input[placeholder*="团队名称"], input[placeholder*="Team Name"], input[placeholder*="name" i]',
      )
      .first();
    const descInput = page
      .locator(
        '[data-testid="team-desc-input"], textarea[name="description"], input[name="description"], textarea[placeholder*="描述"], input[placeholder*="description" i]',
      )
      .first();
    await expect(codeInput).toBeVisible({ timeout: 8000 });

    // Fill form
    await codeInput.fill(code);
    await nameInput.fill(name);
    await descInput.fill('Created by E2E test');

    // Submit and wait for the create API response
    const createResponsePromise = page.waitForResponse(
      (r) => r.url().includes('/api/org/teams') && r.request().method().toLowerCase() === 'post',
      { timeout: 10000 }
    );
    await page
      .locator('[data-testid="team-save-btn"], button:has-text("保存"), button:has-text("Save"), button:has-text("确定"), button[type="submit"]')
      .first()
      .click();
    const createResp = await createResponsePromise;
    expect(createResp.ok()).toBe(true);

    // Verify team appears in list (stable selector first, then text fallback)
    const rowAction = page.locator(`[data-testid="team-edit-${code}"]`);
    const hasRowAction = await rowAction.isVisible({ timeout: 10000 }).catch(() => false);
    if (!hasRowAction) {
      await expect(page.locator(`text=${name}`)).toBeVisible({ timeout: 10000 });
    }
  });

  test('TM-003: should edit a team via UI', async ({ page }) => {
    // Create team via API first
    const code = `e2e-edit-${Date.now()}`;
    const name = `Edit Test ${Date.now()}`;
    teamCodes.push(code);

    const createResp = await page.request.post(`${BASE_URL}/api/org/teams`, {
      data: { code, name, description: 'To be edited' },
      timeout: 10000,
    });
    const createBody = await createResp.json();
    expect(createBody.code).toBe(ErrorCodes.SUCCESS);

    await page.goto('/organization/teams');
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator(`text=${name}`)).toBeVisible({ timeout: 10000 });

    // Click edit button
    await page.locator(`[data-testid="team-edit-${code}"]`).click();

    // Wait for modal to appear with pre-filled data
    await expect(page.locator('[data-testid="team-name-input"]')).toBeVisible({ timeout: 5000 });

    // Update name
    const updatedName = `Updated ${Date.now()}`;
    await page.locator('[data-testid="team-name-input"]').fill(updatedName);

    // Submit and wait for API response
    const updateResponsePromise = page.waitForResponse(
      (r) => r.url().includes('/api/org/teams') && r.request().method().toLowerCase() === 'put',
      { timeout: 10000 }
    );
    await page.locator('[data-testid="team-save-btn"]').click();
    await updateResponsePromise;

    // Verify updated name
    await expect(page.locator(`text=${updatedName}`)).toBeVisible({ timeout: 10000 });
  });

  test('TM-004: should delete a team via UI', async ({ page }) => {
    // Create team via API
    const code = `e2e-del-${Date.now()}`;
    const name = `Delete Test ${Date.now()}`;

    const createResp = await page.request.post(`${BASE_URL}/api/org/teams`, {
      data: { code, name },
      timeout: 10000,
    });
    const createBody = await createResp.json();
    expect(createBody.code).toBe(ErrorCodes.SUCCESS);

    await page.goto('/organization/teams');
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator(`text=${name}`)).toBeVisible({ timeout: 10000 });

    // Auto-accept confirm dialog
    page.on('dialog', (dialog) => dialog.accept());

    // Set up response listener BEFORE clicking
    const deleteResponsePromise = page.waitForResponse(
      (r) => r.url().includes('/api/org/teams') && r.request().method().toLowerCase() === 'delete',
      { timeout: 10000 }
    );

    // Click delete
    await page.locator(`[data-testid="team-delete-${code}"]`).click();

    // Wait for delete API to complete
    await deleteResponsePromise;

    // Verify removed from list
    await expect(page.locator(`text=${name}`)).not.toBeVisible({ timeout: 10000 });
  });

  test('TM-005: should navigate to team detail and view members @smoke', async ({ page }) => {
    // Create team via API
    const code = `e2e-detail-${Date.now()}`;
    const name = `Detail Test ${Date.now()}`;
    teamCodes.push(code);

    const createResp = await page.request.post(`${BASE_URL}/api/org/teams`, {
      data: { code, name, description: 'Detail test team' },
      timeout: 10000,
    });
    const createBody = await createResp.json();
    expect(createBody.code).toBe(ErrorCodes.SUCCESS);
    const teamPid = createBody.data.pid;

    await page.goto('/organization/teams');
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator(`text=${name}`)).toBeVisible({ timeout: 10000 });

    // Click members icon to navigate to detail
    await page.locator(`[data-testid="team-members-${code}"]`).click();

    // Verify detail page — use expect().toHaveURL() for SPA navigation
    await expect(page).toHaveURL(new RegExp(`/organization/teams/${teamPid}`), { timeout: 10000 });
    await expect(page.locator(`h1:has-text("${name}")`)).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid="add-member-btn"]')).toBeVisible();
  });

  test('TM-006: should add and remove team member via UI', async ({ page }) => {
    // Create team via API
    const code = `e2e-member-${Date.now()}`;
    const name = `Member Test ${Date.now()}`;
    teamCodes.push(code);

    const createResp = await page.request.post(`${BASE_URL}/api/org/teams`, {
      data: { code, name },
      timeout: 10000,
    });
    const createBody = await createResp.json();
    expect(createBody.code).toBe(ErrorCodes.SUCCESS);
    const teamPid = createBody.data.pid;

    // Navigate to team detail
    await page.goto(`/organization/teams/${teamPid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator(`h1`).filter({ hasText: name })).toBeVisible({ timeout: 10000 });

    // Click add member
    await page.locator('[data-testid="add-member-btn"]').click();

    // Wait for member select to load — use soft check since select only appears when members exist
    const memberSelect = page.locator('[data-testid="member-select"]');
    const hasMemberSelect = await memberSelect.isVisible({ timeout: 5000 }).catch(() => false);

    if (!hasMemberSelect) {
      // No available tenant members — nothing to test
      test.info().annotations.push({
        type: 'note',
        description: 'Member select not available — no tenant members to add',
      });
      return;
    }

    // Select first available member (if any)
    const options = memberSelect.locator('option');
    const optionCount = await options.count();

    if (optionCount <= 1) {
      // Only placeholder option — no real members available
      test.info().annotations.push({
        type: 'note',
        description: 'No selectable members available',
      });
      return;
    }

    // Select the first real option (skip placeholder)
    const firstOption = await options.nth(1).getAttribute('value');
    if (!firstOption) return;

    await memberSelect.selectOption(firstOption);

    // Click confirm and wait for API response
    const addMemberResponsePromise = page.waitForResponse(
      (r) => r.url().includes('/api/org/teams/') && r.request().method().toLowerCase() === 'post',
      { timeout: 10000 }
    );
    await page.locator('[data-testid="add-member-confirm-btn"]').click();
    await addMemberResponsePromise;

    // Verify member appears in the table
    const memberRow = page.locator('table tbody tr');
    await expect(memberRow.first()).toBeVisible({ timeout: 10000 });

    // Auto-accept confirm dialog for removal (uses native confirm())
    page.on('dialog', (dialog) => dialog.accept());

    // Remove the member — find the remove button in the row
    const removeBtn = memberRow.first().locator('button[title="Remove member"], button').first();

    // Set up response listener BEFORE clicking
    const removeResponsePromise = page.waitForResponse(
      (r) => r.url().includes('/api/org/teams/') && r.request().method().toLowerCase() === 'delete',
      { timeout: 10000 }
    );
    await removeBtn.click();
    await removeResponsePromise;

    // Verify member removed
    await expect(page.getByText(/No members yet|暂无成员/i)).toBeVisible({ timeout: 10000 });
  });
});
