/**
 * Activity Timeline — E2E Tests
 *
 * Validates:
 * - Activity tab appears on DOCUMENT model detail pages
 * - System activities auto-recorded for CREATE/UPDATE/STATE_CHANGE
 * - User activities (NOTE) can be created via API and displayed
 * - Activity API returns correct data
 *
 * Uses e2et_order (DOCUMENT model) as the test subject.
 * Uses real database, NO MOCKING.
 *
 * @since 6.3.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, waitForDynamicPageLoad } from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';

test.describe.configure({ mode: 'serial' });

test.describe('Activity Timeline — DOCUMENT model', () => {
  const uid = uniqueId('act');
  let orderPid: string;
  let orderTitle: string;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);

    // 1. Create a draft order (triggers CREATE activity)
    orderTitle = `Activity Test ${uid}`;
    orderPid = await order.createViaApi({ e2et_order_title: orderTitle });

    // 2. Add an item so we can submit
    await order.child('item').createForParent(orderPid, {
      e2et_item_name: `Item ${uid}`,
      e2et_item_qty: 3,
      e2et_item_price: 15.0,
    });

    // 3. Submit the order (triggers STATE_CHANGE activity: draft → submitted)
    await order.transitionViaApi(orderPid, ['submit']);

    // 4. Create a user NOTE activity via API
    const noteResp = await page.request.post('/api/activities', {
      data: {
        objectModel: 'e2et_order',
        objectRecord: orderPid,
        activityType: 'note',
        subject: `Test note ${uid}`,
        content: 'This is a test activity note created via API.',
      },
    });
    expect(noteResp.ok()).toBe(true);

    await page.close();
    await context.close();
  });

  /**
   * ACT-001: Activity API returns activities for the record
   */
  test('ACT-001: GET /api/activities returns activities for the order', async ({ page }) => {
    const resp = await page.request.get(
      `/api/activities?objectModel=e2et_order&objectRecord=${orderPid}&limit=50`,
    );
    expect(resp.ok()).toBe(true);

    const body = await resp.json();
    expect(body.code).toBe('0');
    const activities = body.data;
    expect(Array.isArray(activities)).toBe(true);
    // At least 2: system activity from create_order + user NOTE
    expect(activities.length).toBeGreaterThanOrEqual(2);

    // Verify activity types present
    const types = activities.map((a: any) => a.activityType);
    expect(types).toContain('note');

    // Verify the NOTE we created
    const note = activities.find((a: any) => a.activityType === 'note');
    expect(note).toBeTruthy();
    expect(note.subject).toContain(uid);
    expect(note.content).toBe('This is a test activity note created via API.');
    expect(note.actorType).toBe('user');

    // System activity from command execution should also be present
    const systemActivities = activities.filter(
      (a: any) =>
        a.activityType === 'create' ||
        a.activityType === 'state_change' ||
        a.activityType === 'system',
    );
    const commandCodes = systemActivities.map((a: any) => String(a.commandCode ?? ''));
    expect(
      commandCodes.some((code: string) => /e2et:(create|submit)_order/.test(code)),
      `expected create/submit command activity, got ${JSON.stringify(commandCodes)}`,
    ).toBe(true);
  });

  /**
   * ACT-002: Activity count API returns correct count
   */
  test('ACT-002: GET /api/activities/count returns positive count', async ({ page }) => {
    const resp = await page.request.get(
      `/api/activities/count?objectModel=e2et_order&objectRecord=${orderPid}`,
    );
    expect(resp.ok()).toBe(true);

    const body = await resp.json();
    expect(body.code).toBe('0');
    expect(body.data).toBeGreaterThanOrEqual(2);
  });

  /**
   * ACT-003: Activity tab visible on DOCUMENT model detail page
   */
  test('ACT-003: Detail page shows Activity tab for DOCUMENT model', async ({ page }) => {
    // Navigate to the detail page
    await page.goto(`/p/e2et_order/view/${orderPid}`);
    await waitForDynamicPageLoad(page, 10000);

    // Look for the Activity tab
    const activityTab = page.locator('button, [role="tab"]').filter({
      hasText: /Activity|活动记录/,
    });
    await expect(activityTab.first()).toBeVisible({ timeout: 10000 });
  });

  /**
   * ACT-004: Clicking Activity tab loads and displays activities
   */
  test('ACT-004: Activity tab shows timeline with real data', async ({ page }) => {
    await page.goto(`/p/e2et_order/view/${orderPid}`);
    await waitForDynamicPageLoad(page, 10000);

    const activityTab = page.locator('button, [role="tab"]').filter({
      hasText: /Activity|活动记录/,
    });
    const noteText = page.getByText(new RegExp(`Test note ${uid}`));

    if (!(await noteText.isVisible().catch(() => false))) {
      const activityResponsePromise = page
        .waitForResponse(
          (resp) => resp.url().includes('/api/activities') && resp.status() === 200,
          {
            timeout: 10000,
          },
        )
        .catch(() => null);

      await activityTab.first().click();
      await activityResponsePromise;
    }

    // Verify timeline content is visible
    // Should see activity type badges (System from command, or Note from manual)
    const badges = page.locator('span').filter({
      hasText: /Created|创建|Note|备注|State Change|状态变更|System|系统/,
    });
    await expect(badges.first()).toBeVisible({ timeout: 10000 });

    // Should see the note subject we created
    await expect(noteText).toBeVisible({ timeout: 10000 });

    // Should see actor name
    const actorNames = page.locator('.font-medium.text-gray-700');
    await expect(actorNames.first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * ACT-005: Field History tab still visible alongside Activity tab
   */
  test('ACT-005: Field History tab coexists with Activity tab', async ({ page }) => {
    await page.goto(`/p/e2et_order/view/${orderPid}`);
    await waitForDynamicPageLoad(page, 10000);

    // Both tabs should be visible
    const activityTab = page.locator('button, [role="tab"]').filter({
      hasText: /Activity|活动记录/,
    });
    const historyTab = page.locator('button, [role="tab"]').filter({
      hasText: /Field History|变更历史/,
    });

    await expect(activityTab.first()).toBeVisible({ timeout: 10000 });
    await expect(historyTab.first()).toBeVisible({ timeout: 10000 });
  });
});
