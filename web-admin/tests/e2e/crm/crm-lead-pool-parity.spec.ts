import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';

type CommandResult = Record<string, unknown>;

async function uiLogin(page: Page, email: string): Promise<void> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  // Do not type into the server-rendered form before React hydration; hydration
  // would legitimately replace the node and clear the pre-hydration value.
  await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {});
  const identifier = page
    .locator('input[placeholder*="用户名"], input[name="identifier"], input[type="email"]')
    .first();
  if (await identifier.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await identifier.fill(email);
    await page.getByRole('textbox', { name: '密码' }).fill(PASSWORD);
    await expect(identifier).toHaveValue(email);
    await page.getByRole('button', { name: '立即登录', exact: true }).click();
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });
    const loginResponse = await page.request.get('/api/auth/me');
    const loginBody = await loginResponse.json().catch(() => ({}));
    expect(
      loginResponse.ok() && String(loginBody?.code) === '0',
      `authenticated session ${email} failed: HTTP ${loginResponse.status()} ${JSON.stringify(loginBody).slice(0, 800)}`,
    ).toBe(true);
  }
}

async function command(
  page: Page,
  code: string,
  payload: Record<string, unknown> = {},
  targetRecordPid?: string,
  operationType?: string,
): Promise<CommandResult> {
  const response = await page.request.post(`/api/meta/commands/execute/${code}`, {
    data: {
      payload,
      ...(targetRecordPid ? { targetRecordPid } : {}),
      ...(operationType ? { operationType } : {}),
    },
    timeout: 20_000,
  });
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(body?.code) === '0',
    `${code} failed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 1_000)}`,
  ).toBe(true);
  return body?.data?.data ?? {};
}

function recordPid(result: CommandResult): string {
  return String(
    result.recordId ?? result.recordPid ?? result.publicRecordId ?? result.pid ?? result.id ?? '',
  );
}

async function provisionUser(
  page: Page,
  stamp: string,
  kind: 'sales' | 'manager',
  roleCodes: string[],
): Promise<{ email: string; pid: string }> {
  const email = `crm-lead-pool-${kind}-${stamp}@e2e.local`;
  const response = await page.request.post('/api/admin/users', {
    data: {
      email,
      displayName: `Lead Pool ${kind === 'sales' ? 'Sales' : 'Manager'} ${stamp}`,
      initialPassword: PASSWORD,
      roleCodes,
      sendInviteEmail: false,
    },
  });
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(body?.code) === '0',
    `provision ${kind} failed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
  const pid = String(body?.data?.pid ?? body?.data?.userPid ?? '');
  expect(pid, 'provisioned sales user public pid').toBeTruthy();
  return { email, pid };
}

async function createLeadAndMove(
  page: Page,
  poolPid: string,
  ownerPid: string,
  company: string,
  index: number,
): Promise<{ leadPid: string; itemPid: string }> {
  const lead = await command(
    page,
    'crm:create_lead',
    {
      crm_lead_company: company,
      crm_lead_contact_name: `联系人 ${index}`,
      crm_lead_contact_phone: `1390000${String(index).padStart(4, '0')}`,
      crm_lead_source: 'website',
      crm_lead_score: 90 - index,
      crm_lead_assigned_to: ownerPid,
    },
    undefined,
    'create',
  );
  const leadPid = recordPid(lead);
  expect(leadPid, `created lead ${company}`).toBeTruthy();
  const moved = await command(
    page,
    'crm:move_lead_to_pool',
    { poolId: poolPid, reason: 'CordysCRM W1 automated parity journey' },
    leadPid,
  );
  const itemPid = String(moved.poolItemId ?? '');
  expect(itemPid, `pool item for ${company}`).toBeTruthy();
  return { leadPid, itemPid };
}

async function openAs(browser: Browser, baseURL: string, email: string): Promise<{
  context: BrowserContext;
  page: Page;
}> {
  const context = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await uiLogin(page, email);
  return { context, page };
}

test.describe('CRM lead-pool Cordys parity W1', () => {
  test.setTimeout(180_000);

  test('member scope, single claim, capacity, daily quota, batch claim, tab state and audit persist on the real stack', async ({
    page,
    browser,
    baseURL,
  }, testInfo) => {
    await uiLogin(page, ADMIN_EMAIL);
    const stamp = `${Date.now()}`;
    const meResponse = await page.request.get('/api/auth/me');
    const meBody = await meResponse.json();
    const adminPid = String(meBody?.data?.user?.pid ?? '');
    expect(adminPid, 'admin pid from /api/auth/me').toBeTruthy();
    const sales = await provisionUser(page, stamp, 'sales', ['crm_sales']);
    const manager = await provisionUser(page, stamp, 'manager', ['crm_sales_manager']);

    const pool = await command(
      page,
      'crm:create_lead_pool',
      {
        crm_lp_name: `Cordys W1 Pool ${stamp}`,
        crm_lp_member_user_ids: JSON.stringify([sales.pid]),
        crm_lp_admin_user_ids: JSON.stringify([adminPid, manager.pid]),
        crm_lp_daily_pick_limit: 1,
        crm_lp_new_cooldown_days: 0,
        crm_lp_previous_owner_cooldown_days: 2,
        crm_lp_auto_recycle: true,
        crm_lp_recycle_after_days: 30,
        crm_lp_recycle_basis: 'recent_activity',
        crm_lp_description: 'CordysCRM parity automated acceptance pool',
      },
      undefined,
      'create',
    );
    const poolPid = recordPid(pool);
    expect(poolPid, 'created lead pool pid').toBeTruthy();

    const capacity = await command(
      page,
      'crm:create_lead_capacity',
      {
        crm_lcap_user_id: sales.pid,
        crm_lcap_capacity: 1,
        crm_lcap_status: 'active',
        crm_lcap_remark: 'W1 capacity boundary',
      },
      undefined,
      'create',
    );
    const capacityPid = recordPid(capacity);
    expect(capacityPid, 'created capacity pid').toBeTruthy();

    const singleA = `W1-Single-A-${stamp}`;
    const singleB = `W1-Single-B-${stamp}`;
    const assignA = `W1-Assign-A-${stamp}`;
    const batchA = `W1-Batch-A-${stamp}`;
    const batchB = `W1-Batch-B-${stamp}`;
    const a = await createLeadAndMove(page, poolPid, adminPid, singleA, 1);
    const b = await createLeadAndMove(page, poolPid, adminPid, singleB, 2);
    await createLeadAndMove(page, poolPid, adminPid, assignA, 3);
    await createLeadAndMove(page, poolPid, adminPid, batchA, 4);
    await createLeadAndMove(page, poolPid, adminPid, batchB, 5);

    const salesSession = await openAs(browser, baseURL ?? 'http://localhost:5251', sales.email);
    try {
      const salesPage = salesSession.page;
      const salesMeResponse = await salesPage.request.get('/api/auth/me');
      const salesMeBody = await salesMeResponse.json();
      expect(JSON.stringify(salesMeBody?.data?.permissions)).toContain('crm.lead_pool.read');
      expect(JSON.stringify(salesMeBody?.data?.permissions)).toContain('crm.lead_pool.pick');
      await salesPage.goto('/p/crm_lead_pool_item');
      await expect(salesPage).toHaveURL(/crm_lead_pool_item/, { timeout: 15_000 });
      await expect(salesPage.getByText(singleA, { exact: true })).toBeVisible({ timeout: 15_000 });
      await expect(salesPage.getByText(singleB, { exact: true })).toBeVisible({ timeout: 15_000 });

      const rowA = salesPage.getByRole('row', { name: new RegExp(singleA) });
      await rowA.getByRole('button', { name: 'More actions' }).click();
      await salesPage.getByTestId('row-action-claim').click();
      await expect(salesPage.getByText(singleA, { exact: true })).toHaveCount(0, { timeout: 15_000 });
      await testInfo.attach('sales-single-claim-available-tab', {
        body: await salesPage.screenshot(),
        contentType: 'image/png',
      });

      await salesPage.getByRole('button', { name: /已领取|Claimed/, exact: true }).click();
      await expect(salesPage.getByText(singleA, { exact: true })).toBeVisible();

      const capacityResponse = await salesPage.request.post(
        '/api/meta/commands/execute/crm:claim_pool_lead',
        { data: { targetRecordPid: b.itemPid, operationType: 'update', payload: {} } },
      );
      const capacityBody = await capacityResponse.json().catch(() => ({}));
      expect(JSON.stringify(capacityBody)).toContain('Lead capacity reached');

      await command(
        page,
        'crm:update_lead_capacity',
        {
          crm_lcap_capacity: 3,
          crm_lcap_status: 'active',
          crm_lcap_remark: 'W1 daily quota boundary',
        },
        capacityPid,
        'update',
      );
      const quotaResponse = await salesPage.request.post(
        '/api/meta/commands/execute/crm:claim_pool_lead',
        { data: { targetRecordPid: b.itemPid, operationType: 'update', payload: {} } },
      );
      const quotaBody = await quotaResponse.json().catch(() => ({}));
      expect(JSON.stringify(quotaBody)).toContain('Daily lead-pool claim limit reached');
    } finally {
      await salesSession.context.close();
    }

    await page.goto('/p/crm_lead_pool_item');
    const assignRow = page.getByRole('row', { name: new RegExp(assignA) });
    await assignRow.getByRole('button', { name: 'More actions' }).click();
    await page.getByTestId('row-action-assign').click();
    const assignDialog = page.getByRole('dialog');
    await expect(assignDialog.getByText(/分配线索|Assign Lead/)).toBeVisible();
    await assignDialog.getByTestId('member-picker-add').click();
    await assignDialog.getByTestId('member-picker-search-input').fill(sales.email);
    await assignDialog.getByTestId(`member-picker-option-${sales.pid}`).click();
    await assignDialog.getByRole('button', { name: /确认|确定|提交|Submit|Confirm/i }).click();
    await expect(page.getByText(assignA, { exact: true })).toHaveCount(0, { timeout: 15_000 });
    await testInfo.attach('admin-single-assign-result', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    const managerSession = await openAs(browser, baseURL ?? 'http://localhost:5251', manager.email);
    try {
      const managerPage = managerSession.page;
      await managerPage.goto('/p/crm_lead_pool_item');
      for (const company of [batchA, batchB]) {
        const row = managerPage.getByRole('row', { name: new RegExp(company) });
        await row.getByRole('checkbox').click();
      }
      await managerPage.getByRole('button', { name: /批量领取|Claim Selected/, exact: true }).click();
      await managerPage.getByRole('dialog').getByRole('button', { name: /确认|Confirm/, exact: true }).click();
      await expect(managerPage.getByText(/批量领取已完成.*成功 2 条|Claim.*2/i)).toBeVisible({
        timeout: 20_000,
      });
      await expect(managerPage.getByText(batchA, { exact: true })).toHaveCount(0);
      await expect(managerPage.getByText(batchB, { exact: true })).toHaveCount(0);
      await testInfo.attach('manager-batch-claim-result', {
        body: await managerPage.screenshot(),
        contentType: 'image/png',
      });
    } finally {
      await managerSession.context.close();
    }

    await page.goto('/p/crm_lead_owner_history');
    await expect(page.getByText(/领取|Claimed/).first()).toBeVisible();
    await expect(page.getByText('CordysCRM W1 automated parity journey').first()).toBeVisible();
    await testInfo.attach('lead-owner-history', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    const persistedLead = await page.request.get(`/api/dynamic/crm_lead_common/${a.leadPid}`);
    const persistedBody = await persistedLead.json();
    expect(String(persistedBody?.data?.crm_lead_assigned_to)).toBe(sales.pid);
    expect(String(persistedBody?.data?.crm_lead_pool_state)).toBe('owned');
  });
});
