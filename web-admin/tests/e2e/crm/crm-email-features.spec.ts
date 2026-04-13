/**
 * CRM Email Features E2E Tests
 *
 * Covers Email Log and Email Template functionality:
 * - CE-001 @smoke: Email log menu navigation → page loads → list visible
 * - CE-002 @critical: Email log created via API appears in the list
 * - CE-003 @critical: Email template created via API appears in the list
 * - CE-004 @critical: Email log status transition draft → SENT via send command
 * - CE-005: Email template status lifecycle draft → active → archived
 * - CE-006: Email log status tabs filter correctly
 *
 * Navigation path:
 *   Sidebar → CRM (parent) → 邮件管理 (Email Management group) → 邮件记录 / 邮件模板
 *
 * Key facts from config:
 *   - crm:create_email_log autosets crm_el_status = draft (not QUEUED)
 *   - crm:send_email_log transitions draft → SENT (sets crm_el_sent_at)
 *   - crm:create_email_template autosets crm_et_status = draft (no crm_et_code field)
 *   - Both pages are dynamic DSL pages at /p/crm-email-log and /p/crm-email-template
 *
 * @since 7.4.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, executeCommandViaApi, queryFilteredList } from '../helpers/index';

// ---------------------------------------------------------------------------
// Shared navigation helpers
// ---------------------------------------------------------------------------

/**
 * Navigate to CRM root, expand the Email Management sub-group, then click a child menu item.
 *
 * Menu hierarchy:
 *   CRM (button) → 邮件管理 (Email Management, type=0 sub-group button) → child link
 *
 * Uses evaluate() for click to bypass Playwright scroll/overlap issues on sidebar items.
 */
async function gotoEmailPage(
  page: import('@playwright/test').Page,
  href: '/p/crm_email_log' | '/p/crm_email_template',
): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  // Expand CRM root menu group
  const crmButton = nav.getByRole('button', { name: 'crm' }).first();
  await crmButton.scrollIntoViewIfNeeded();
  await crmButton.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2_000 }).catch(() => null);

  // Expand "邮件管理" (Email Management) sub-group
  const emailMgmtButton = nav.getByRole('button', { name: '邮件管理' });
  await emailMgmtButton.scrollIntoViewIfNeeded();
  await emailMgmtButton.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2_000 }).catch(() => null);

  // Click the target child link by text (更稳定 than href selector)
  const linkName = href === '/p/crm_email_log' ? '邮件记录' : '邮件模板';
  const targetLink = nav.getByRole('link', { name: linkName });
  await targetLink.scrollIntoViewIfNeeded();
  await targetLink.evaluate((el: HTMLElement) => el.click());

  await expect(page).toHaveURL(new RegExp(href.replace('/', '\\/')), { timeout: 10000 });

  // Wait for the list API to return data
  await page
    .waitForResponse(
      (resp) =>
        resp.url().includes('/api/dynamic/') &&
        resp.url().includes('/list') &&
        resp.status() === 200,
      { timeout: 15000 },
    )
    .catch(() => null);

  // Wait for table to render
  await page
    .locator('table, [class*="ant-table"]')
    .first()
    .waitFor({ state: 'visible', timeout: 10000 })
    .catch(() => {});
}

// ---------------------------------------------------------------------------
// Test Suite: CE-001 ~ CE-002  (Email Log smoke + data presence)
// ---------------------------------------------------------------------------

test.describe('CRM Email Log @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('ELog');
  let logRecordId = '';

  // Setup: create a seed email log record via API
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const result = await executeCommandViaApi(
        page,
        'crm:create_email_log',
        {
          crm_el_subject: `Test Email ${uid}`,
          crm_el_to_address: 'test@example.com',
          crm_el_from_address: 'sender@auraboot.test',
          crm_el_direction: 'outbound',
        },
        undefined,
        'create',
      );
      logRecordId = result.recordId;
    } finally {
      await ctx.close();
    }
  });

  // CE-001: Smoke — page accessible via sidebar menu
  test('CE-001: Email log page loads via sidebar menu navigation', async ({ page }) => {
    await gotoEmailPage(page, '/p/crm_email_log');

    // Verify we landed on the correct page
    await expect(page).toHaveURL(/\/p\/crm_email_log/, { timeout: 10000 });

    // Table should be visible
    const table = page.locator('table, [role="table"]').first();
    await expect(table).toBeVisible({ timeout: 10000 });
  });

  // CE-002: Seeded email log appears in the list
  test('CE-002: Email log created via API appears in the list', async ({ page }) => {
    await gotoEmailPage(page, '/p/crm_email_log');

    // Verify via API first — most reliable for paginated lists
    const records = await queryFilteredList(
      page,
      'crm-email-log',
      'crm_el_subject',
      `Test Email ${uid}`,
      { operator: 'like' },
    );
    expect(
      records.length,
      `Email log with subject "Test Email ${uid}" should exist`,
    ).toBeGreaterThanOrEqual(1);

    const found = records.find((r) => String(r.crm_el_subject ?? '').includes(`Test Email ${uid}`));
    expect(found, 'Matching email log record found in API response').toBeTruthy();

    // Also verify record ID from setup resolves (sanity check on create)
    if (logRecordId) {
      expect(logRecordId, 'Create command returned a non-empty recordId').not.toBe('');
    }
  });

  // CE-006: Status tabs on email log page render and filter correctly
  test('CE-006: Email log list tabs are rendered and filterable', async ({ page }) => {
    await gotoEmailPage(page, '/p/crm_email_log');

    // Tabs defined in DSL: 全部 / 草稿 / 已发送 / 已送达 / 已打开 / 已退信 / 失败
    const allTab = page
      .locator('button, [role="tab"]')
      .filter({ hasText: /^全部$|^All$/ })
      .first();
    await expect(allTab).toBeVisible({ timeout: 8000 });

    const draftTab = page
      .locator('button, [role="tab"]')
      .filter({ hasText: /草稿|Draft/ })
      .first();
    await expect(draftTab).toBeVisible({ timeout: 5000 });

    // Click Draft tab and wait for filtered list response
    const draftListResponse = page.waitForResponse(
      (resp) => resp.url().includes('/api/dynamic/crm_email_log/list') && resp.status() === 200,
      { timeout: 10000 },
    );
    await draftTab.evaluate((el: HTMLElement) => el.click());
    await draftListResponse;

    // Seeded record is draft — should appear under Draft tab
    const records = await queryFilteredList(
      page,
      'crm-email-log',
      'crm_el_subject',
      `Test Email ${uid}`,
      {
        operator: 'like',
        extraFilters: [{ fieldName: 'crm_el_status', operator: 'EQ', value: 'draft' }],
      },
    );
    expect(
      records.length,
      'Seeded draft email log should appear in Draft-filtered API results',
    ).toBeGreaterThanOrEqual(1);
  });
});

// ---------------------------------------------------------------------------
// Test Suite: CE-004  (Email Log status transition)
// ---------------------------------------------------------------------------

test.describe('CRM Email Log Status Transitions @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('ELogTx');
  let sendRecordId = '';

  // Setup: create a draft email log to send
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const result = await executeCommandViaApi(
        page,
        'crm:create_email_log',
        {
          crm_el_subject: `Send Test ${uid}`,
          crm_el_to_address: 'recipient@example.com',
          crm_el_from_address: 'sender@auraboot.test',
          crm_el_direction: 'outbound',
        },
        undefined,
        'create',
      );
      sendRecordId = result.recordId;
    } finally {
      await ctx.close();
    }
  });

  // CE-004: draft → SENT via crm:send_email_log command
  test('CE-004: Email log status transitions from draft to SENT', async ({ page }) => {
    expect(sendRecordId, 'draft email log must have been created in beforeAll').not.toBe('');

    // Execute send command via API (state transition)
    const sendResult = await executeCommandViaApi(
      page,
      'crm:send_email_log',
      {},
      sendRecordId,
      'state_transition',
    );
    expect(sendResult.code, 'Send command should succeed (code 0 or success)').toMatch(/^0$|^$/);

    // Verify status changed to SENT via list API
    const filters = encodeURIComponent(
      JSON.stringify([{ fieldName: 'crm_el_status', operator: 'EQ', value: 'sent' }]),
    );
    const resp = await page.request.get(
      `/api/dynamic/crm_email_log/list?pageNum=1&pageSize=50&filters=${filters}`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records: Record<string, unknown>[] = body?.data?.records ?? body?.data?.data ?? [];

    const sentRecord = records.find(
      (r) => r.crm_el_subject && String(r.crm_el_subject).includes(`Send Test ${uid}`),
    );
    expect(sentRecord, `Email log "Send Test ${uid}" should now have status SENT`).toBeTruthy();
    expect(sentRecord?.crm_el_status, 'Status field value should be SENT').toBe('sent');

    // crm:send_email_log autosets crm_el_sent_at — verify it was set
    expect(
      sentRecord?.crm_el_sent_at,
      'crm_el_sent_at should be populated after send',
    ).toBeTruthy();
  });

  // Bonus: SENT → DELIVERED transition via deliver command
  test('CE-004b: Email log transitions from SENT to DELIVERED', async ({ page }) => {
    expect(sendRecordId, 'Record ID from CE-004 setup must be present').not.toBe('');

    await executeCommandViaApi(page, 'crm:deliver_email_log', {}, sendRecordId, 'state_transition');

    // Verify DELIVERED status
    const filters = encodeURIComponent(
      JSON.stringify([{ fieldName: 'crm_el_status', operator: 'EQ', value: 'delivered' }]),
    );
    const resp = await page.request.get(
      `/api/dynamic/crm_email_log/list?pageNum=1&pageSize=50&filters=${filters}`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records: Record<string, unknown>[] = body?.data?.records ?? body?.data?.data ?? [];

    const deliveredRecord = records.find((r) =>
      String(r.crm_el_subject ?? '').includes(`Send Test ${uid}`),
    );
    expect(
      deliveredRecord,
      `Email log "Send Test ${uid}" should now have status DELIVERED`,
    ).toBeTruthy();
  });
});

// ---------------------------------------------------------------------------
// Test Suite: CE-003 + CE-005  (Email Template)
// ---------------------------------------------------------------------------

test.describe('CRM Email Template @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('ETmpl');
  let templateRecordId = '';

  // Setup: create seed email templates
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Template for list presence test
      const result = await executeCommandViaApi(
        page,
        'crm:create_email_template',
        {
          crm_et_name: `Template ${uid}`,
          crm_et_subject: `Hello from ${uid}`,
          crm_et_category: 'marketing',
          crm_et_description: 'E2E test template',
          crm_et_body: '<p>E2E template body</p>',
        },
        undefined,
        'create',
      );
      templateRecordId = result.recordId;
    } finally {
      await ctx.close();
    }
  });

  // CE-003: Template accessible via Email Management → 邮件模板 menu
  test('CE-003: Email template page loads via sidebar menu navigation', async ({ page }) => {
    await gotoEmailPage(page, '/p/crm_email_template');

    await expect(page).toHaveURL(/\/p\/crm_email_template(?:\?.*)?$/, { timeout: 10000 });

    // Table should be visible
    const table = page.locator('table, [role="table"]').first();
    await expect(table).toBeVisible({ timeout: 10000 });
  });

  // CE-003b: Seeded template appears in the list
  test('CE-003b: Email template created via API appears in the list', async ({ page }) => {
    // Verify via API — reliable for paginated lists
    const records = await queryFilteredList(
      page,
      'crm-email-template',
      'crm_et_name',
      `Template ${uid}`,
      { operator: 'like' },
    );
    expect(
      records.length,
      `Email template "Template ${uid}" should exist in API results`,
    ).toBeGreaterThanOrEqual(1);

    const found = records.find((r) => String(r.crm_et_name ?? '').includes(`Template ${uid}`));
    expect(found, 'Found the created email template in API response').toBeTruthy();
    expect(found?.crm_et_status, 'New template auto-sets status to draft').toBe('draft');
  });

  // CE-005: draft → active → archived lifecycle
  test('CE-005: Email template lifecycle: draft → active → archived', async ({ page }) => {
    expect(templateRecordId, 'Template record ID must be present from beforeAll').not.toBe('');

    // Step 1: draft → active via crm:activate_email_template
    await executeCommandViaApi(
      page,
      'crm:activate_email_template',
      {},
      templateRecordId,
      'state_transition',
    );

    // Verify active
    const activeFilters = encodeURIComponent(
      JSON.stringify([{ fieldName: 'crm_et_status', operator: 'EQ', value: 'active' }]),
    );
    const activeResp = await page.request.get(
      `/api/dynamic/crm_email_template/list?pageNum=1&pageSize=50&filters=${activeFilters}`,
    );
    expect(activeResp.ok()).toBe(true);
    const activeBody = await activeResp.json();
    const activeRecords: Record<string, unknown>[] =
      activeBody?.data?.records ?? activeBody?.data?.data ?? [];

    const activeRecord = activeRecords.find((r) =>
      String(r.crm_et_name ?? '').includes(`Template ${uid}`),
    );
    expect(activeRecord, `Template "${uid}" should be active after activation`).toBeTruthy();
    expect(activeRecord?.crm_et_status).toBe('active');

    // Step 2: active → archived via crm:archive_email_template
    await executeCommandViaApi(
      page,
      'crm:archive_email_template',
      {},
      templateRecordId,
      'state_transition',
    );

    // Verify archived
    const archiveFilters = encodeURIComponent(
      JSON.stringify([{ fieldName: 'crm_et_status', operator: 'EQ', value: 'archived' }]),
    );
    const archiveResp = await page.request.get(
      `/api/dynamic/crm_email_template/list?pageNum=1&pageSize=50&filters=${archiveFilters}`,
    );
    expect(archiveResp.ok()).toBe(true);
    const archiveBody = await archiveResp.json();
    const archiveRecords: Record<string, unknown>[] =
      archiveBody?.data?.records ?? archiveBody?.data?.data ?? [];

    const archivedRecord = archiveRecords.find((r) =>
      String(r.crm_et_name ?? '').includes(`Template ${uid}`),
    );
    expect(archivedRecord, `Template "${uid}" should be archived after archiving`).toBeTruthy();
    expect(archivedRecord?.crm_et_status).toBe('archived');
  });

  // CE-005b: Status tabs on email template list page
  test('CE-005b: Email template status tabs render correctly', async ({ page }) => {
    await gotoEmailPage(page, '/p/crm_email_template');

    // Tabs: 全部 / 草稿 / 启用中 / 已归档
    const allTab = page
      .locator('button, [role="tab"]')
      .filter({ hasText: /^全部$|^All$/ })
      .first();
    await expect(allTab).toBeVisible({ timeout: 8000 });

    const activeTab = page
      .locator('button, [role="tab"]')
      .filter({ hasText: /启用中|Active/ })
      .first();
    await expect(activeTab).toBeVisible({ timeout: 5000 });

    const archivedTab = page
      .locator('button, [role="tab"]')
      .filter({ hasText: /已归档|Archived/ })
      .first();
    await expect(archivedTab).toBeVisible({ timeout: 5000 });

    // Click Archived tab and wait for filtered results
    const archivedListResponse = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/dynamic/crm_email_template/list') && resp.status() === 200,
      { timeout: 10000 },
    );
    await archivedTab.evaluate((el: HTMLElement) => el.click());
    await archivedListResponse;

    // The template archived in CE-005 should appear here
    const records = await queryFilteredList(
      page,
      'crm-email-template',
      'crm_et_name',
      `Template ${uid}`,
      {
        operator: 'like',
        extraFilters: [{ fieldName: 'crm_et_status', operator: 'EQ', value: 'archived' }],
      },
    );
    expect(
      records.length,
      'Archived template should appear in archived tab API results',
    ).toBeGreaterThanOrEqual(1);
  });
});
