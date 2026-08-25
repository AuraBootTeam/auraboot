import type { TestInfo } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { expect, test, type Page } from '../../fixtures';
import {
  clickRowActionByLocator,
  executeCommandViaApi,
  uniqueId,
  waitForFormReady,
} from '../helpers';

const SOURCE_IDS = [
  'api:clue:clue-follow-record:add',
  'api:clue:clue-follow-record:update',
  'api:clue:clue-follow-record:pool-list',
  'api:clue:clue-follow-record:delete-record',
  'api:customer:customer-follow-record:update',
  'api:customer:customer-follow-record:pool-list',
  'api:customer:customer-follow-record:list',
  'api:follow:follow-up-record:get-module-form-config',
  'api:follow:follow-up-record:get-tab-enable-config',
  'api:follow:follow-up-record:update',
  'api:opportunity:opportunity-follow-record:add',
  'api:opportunity:opportunity-follow-record:update',
  'api:opportunity:opportunity-follow-record:list',
  'api:opportunity:opportunity-follow-record:get',
  'api:opportunity:opportunity-follow-record:delete-record',
] as const;

const completed = new Set<string>();
const screenshots: string[] = [];
const failedRuntimeRequests: string[] = [];

async function login(page: Page): Promise<void> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {});
  const identifier = page
    .locator('input[placeholder*="用户名"], input[name="identifier"], input[type="email"]')
    .first();
  if (await identifier.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await identifier.fill('admin@auraboot.com');
    await page.getByRole('textbox', { name: '密码' }).fill('Test2026x');
    await page.getByRole('button', { name: '立即登录', exact: true }).click();
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });
  }
  const me = await page.request.get('/api/auth/me');
  expect(me.ok(), 'the golden journey requires an authenticated admin session').toBe(true);
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const output = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path: output, fullPage: true });
  screenshots.push(output);
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
}

async function openMenu(page: Page, href: string): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const link = page.locator(`nav a[href="${href}"]`).first();
  await expect(link).toBeVisible({ timeout: 15_000 });
  await link.click();
  await expect(page).toHaveURL(new RegExp(`${href.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`));
}

async function search(page: Page, modelCode: string, keyword: string): Promise<void> {
  const input = page
    .locator(
      '[data-testid="list-search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
    )
    .first();
  await expect(input).toBeVisible({ timeout: 15_000 });
  const response = page.waitForResponse(
    (candidate) => candidate.url().includes(`/api/dynamic/${modelCode}/list`) && candidate.ok(),
  );
  await input.fill(keyword);
  await input.press('Enter');
  await response;
}

function row(page: Page, text: string) {
  return page.locator('tbody tr').filter({ hasText: text });
}

async function dynamicRecord(page: Page, modelCode: string, recordId: string) {
  const response = await page.request.get(`/api/dynamic/${modelCode}/${recordId}`);
  expect(response.ok()).toBe(true);
  return (await response.json()).data as Record<string, unknown>;
}

async function createFollowRecord(
  page: Page,
  commandCode: string,
  sourceRecordPid: string,
  subject: string,
) {
  return executeCommandViaApi(
    page,
    commandCode,
    {
      sourceRecordPid,
      crm_act_type: 'visit',
      crm_act_subject: subject,
      crm_act_content: `Exact persisted follow-up for ${subject}`,
      crm_act_source: 'manual',
    },
    undefined,
    'create',
  );
}

async function assertObjectTimeline(
  page: Page,
  href: string,
  modelCode: string,
  objectLabel: string,
  activitySubject: string,
  actionLabel: RegExp,
): Promise<void> {
  await openMenu(page, href);
  await search(page, modelCode, objectLabel);
  await clickRowActionByLocator(page, row(page, objectLabel), 'view', '查看');
  await expect(page.getByText(objectLabel, { exact: true }).first()).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByRole('button', { name: actionLabel }).first()).toBeVisible();
  const activityTab = page
    .getByRole('tab', { name: /活动记录|活动与协作|活动|Activities/i })
    .first();
  if (await activityTab.isVisible().catch(() => false)) await activityTab.click();
  await expect(page.getByText(activitySubject, { exact: true }).first()).toBeVisible({
    timeout: 15_000,
  });
}

async function updateFromActivityDetail(
  page: Page,
  recordId: string,
  updatedSubject: string,
): Promise<void> {
  await page.goto(`/p/crm_activity_common/view/${recordId}`, { waitUntil: 'domcontentloaded' });
  const edit = page.getByRole('button', { name: /编辑|Edit/i }).first();
  await expect(edit).toBeVisible({ timeout: 15_000 });
  await edit.click();
  await waitForFormReady(page, 15_000);
  await page.getByTestId('form-field-crm_act_subject').locator('input').fill(updatedSubject);
  const response = page.waitForResponse(
    (candidate) =>
      candidate.url().includes('/api/meta/commands/execute/crm:update_activity') && candidate.ok(),
  );
  await page.getByTestId('form-btn-submit').click();
  await response;
  await expect
    .poll(async () =>
      String((await dynamicRecord(page, 'crm_activity_common', recordId)).crm_act_subject),
    )
    .toBe(updatedSubject);
  await expect(page.getByText(updatedSubject, { exact: true }).first()).toBeVisible({
    timeout: 15_000,
  });
}

async function deleteFromActivityDetail(page: Page, recordId: string): Promise<void> {
  await page.goto(`/p/crm_activity_common/view/${recordId}`, { waitUntil: 'domcontentloaded' });
  const remove = page.getByRole('button', { name: /删除记录|Delete Record/i });
  await expect(remove).toBeVisible({ timeout: 15_000 });
  await remove.click();
  await page
    .getByRole('button', { name: /确认|Confirm/i })
    .last()
    .click();
  await expect
    .poll(async () => {
      const response = await page.request.get(`/api/dynamic/crm_activity_common/${recordId}`);
      return response.ok();
    })
    .toBe(false);
}

test.describe.serial('CRM follow-up record lifecycle — Cordys PAR-10 source-bound parity', () => {
  test.setTimeout(240_000);

  test.afterAll(() => {
    const evidenceRoot = process.env.AURA_EVIDENCE_ROOT;
    if (!evidenceRoot) return;
    const sourceIds = SOURCE_IDS.map((sourceId) => ({
      sourceId,
      verdict: completed.has(sourceId) ? 'pass' : 'untested',
    }));
    const verdict =
      sourceIds.every((row) => row.verdict === 'pass') && failedRuntimeRequests.length === 0
        ? 'pass'
        : 'fail';
    fs.mkdirSync(evidenceRoot, { recursive: true });
    fs.writeFileSync(
      path.join(evidenceRoot, `crm-follow-record-full-lifecycle-${Date.now()}.json`),
      `${JSON.stringify(
        {
          schemaVersion: 1,
          runtime: process.env.AURA_RUNTIME_NAME ?? null,
          verdict,
          technicalVerdict: verdict,
          dataMigration: 'not-required-development-stage',
          runner: { browser: 'chromium', workers: 1, retries: 0 },
          sourceIds,
          screenshots,
          failedRuntimeRequests,
        },
        null,
        2,
      )}\n`,
    );
  });

  test('lead, account and opportunity records create, aggregate, edit and delete @critical @golden', async ({
    page,
  }, testInfo) => {
    await login(page);
    page.on('response', (response) => {
      if (response.status() >= 500 && response.url().includes('/api/')) {
        failedRuntimeRequests.push(
          `${response.request().method()} ${response.url()} HTTP ${response.status()}`,
        );
      }
    });
    const uid = uniqueId('crm_follow_record');
    const leadLabel = `Follow Lead ${uid}`;
    const accountLabel = `Follow Account ${uid}`;
    const opportunityLabel = `Follow Opportunity ${uid}`;
    const lead = await executeCommandViaApi(
      page,
      'crm:create_lead',
      {
        crm_lead_company: leadLabel,
        crm_lead_contact_name: `Lead Contact ${uid}`,
        crm_lead_contact_phone: '13800005551',
        crm_lead_source: 'website',
        crm_lead_score: 60,
      },
      undefined,
      'create',
    );
    const account = await executeCommandViaApi(
      page,
      'crm:create_account',
      {
        crm_acc_name: accountLabel,
        crm_acc_industry: 'manufacturing',
        crm_acc_rating: 'A',
      },
      undefined,
      'create',
    );
    const opportunity = await executeCommandViaApi(
      page,
      'crm:create_opportunity',
      {
        crm_opp_name: opportunityLabel,
        crm_opp_account_id: account.recordId,
        crm_opp_stage: 'qualification',
        crm_opp_expected_amount: 180000,
      },
      undefined,
      'create',
    );

    const leadSubject = `Lead Visit ${uid}`;
    const accountSubject = `Account Visit ${uid}`;
    const opportunitySubject = `Opportunity Visit ${uid}`;
    const leadActivity = await createFollowRecord(
      page,
      'crm:log_lead_activity',
      lead.recordId,
      leadSubject,
    );
    completed.add('api:clue:clue-follow-record:add');
    const accountActivity = await createFollowRecord(
      page,
      'crm:log_account_activity',
      account.recordId,
      accountSubject,
    );
    const opportunityActivity = await createFollowRecord(
      page,
      'crm:log_opp_activity',
      opportunity.recordId,
      opportunitySubject,
    );
    completed.add('api:opportunity:opportunity-follow-record:add');

    await assertObjectTimeline(
      page,
      '/p/crm_lead_common',
      'crm_lead_common',
      leadLabel,
      leadSubject,
      /记录跟进|Log Follow-up/i,
    );
    completed.add('api:clue:clue-follow-record:pool-list');
    await shot(page, testInfo, '01-lead-follow-record-timeline');

    await assertObjectTimeline(
      page,
      '/p/crm_account_common',
      'crm_account_common',
      accountLabel,
      accountSubject,
      /记录跟进|Log Follow-up/i,
    );
    completed.add('api:customer:customer-follow-record:list');
    completed.add('api:customer:customer-follow-record:pool-list');
    await shot(page, testInfo, '02-account-follow-record-timeline');

    await assertObjectTimeline(
      page,
      '/p/crm_opportunity_common',
      'crm_opportunity_common',
      opportunityLabel,
      opportunitySubject,
      /记录活动|Log Activity/i,
    );
    completed.add('api:opportunity:opportunity-follow-record:list');
    completed.add('api:opportunity:opportunity-follow-record:get');
    await shot(page, testInfo, '03-opportunity-follow-record-timeline');

    await openMenu(page, '/p/crm_activity_common');
    await expect(page.getByRole('button', { name: /跟进记录|Follow-up Records/i })).toBeVisible();
    completed.add('api:follow:follow-up-record:get-tab-enable-config');
    await page.getByRole('button', { name: /跟进记录|Follow-up Records/i }).click();
    await expect(page.getByText(accountSubject, { exact: true })).toBeVisible({ timeout: 15_000 });
    await page
      .getByRole('button', { name: /新建|Create/i })
      .first()
      .click();
    await waitForFormReady(page, 15_000);
    for (const field of ['crm_act_type', 'crm_act_subject', 'crm_act_date', 'crm_act_content']) {
      await expect(page.getByTestId(`form-field-${field}`)).toBeVisible();
    }
    completed.add('api:follow:follow-up-record:get-module-form-config');
    await page.getByTestId('form-back-link').click();

    await updateFromActivityDetail(page, leadActivity.recordId, `Lead Updated ${uid}`);
    completed.add('api:clue:clue-follow-record:update');
    completed.add('api:follow:follow-up-record:update');
    await updateFromActivityDetail(page, accountActivity.recordId, `Account Updated ${uid}`);
    completed.add('api:customer:customer-follow-record:update');
    await updateFromActivityDetail(
      page,
      opportunityActivity.recordId,
      `Opportunity Updated ${uid}`,
    );
    completed.add('api:opportunity:opportunity-follow-record:update');
    await shot(page, testInfo, '04-follow-record-updated-detail');

    await deleteFromActivityDetail(page, leadActivity.recordId);
    completed.add('api:clue:clue-follow-record:delete-record');
    await deleteFromActivityDetail(page, accountActivity.recordId);
    await deleteFromActivityDetail(page, opportunityActivity.recordId);
    completed.add('api:opportunity:opportunity-follow-record:delete-record');

    expect(failedRuntimeRequests).toEqual([]);
    expect([...completed].sort()).toEqual([...SOURCE_IDS].sort());
  });
});

const PLAN_SOURCE_IDS = [
  'api:clue:clue-follow-plan:add',
  'api:clue:clue-follow-plan:update',
  'api:clue:clue-follow-plan:list',
  'api:clue:clue-follow-plan:get',
  'api:clue:clue-follow-plan:cancel-plan',
  'api:clue:clue-follow-plan:delete-plan',
  'api:clue:clue-follow-plan:update-status',
  'api:customer:customer-follow-plan:update',
  'api:customer:customer-follow-plan:list',
  'api:customer:customer-follow-plan:cancel-plan',
  'api:customer:customer-follow-plan:delete-plan',
  'api:customer:customer-follow-plan:update-status',
  'api:follow:follow-up-plan:get-module-form-config',
  'api:follow:follow-up-plan:get-tab-enable-config',
  'api:follow:follow-up-plan:delete',
  'api:follow:follow-up-plan:update-status',
  'api:follow:follow-up-plan:update',
  'api:opportunity:opportunity-follow-plan:add',
  'api:opportunity:opportunity-follow-plan:update',
  'api:opportunity:opportunity-follow-plan:list',
  'api:opportunity:opportunity-follow-plan:get',
  'api:opportunity:opportunity-follow-plan:cancel-plan',
  'api:opportunity:opportunity-follow-plan:delete-plan',
  'api:opportunity:opportunity-follow-plan:update-status',
] as const;

async function createFollowPlan(
  page: Page,
  relatedModel: string,
  relatedId: string,
  subject: string,
) {
  return executeCommandViaApi(
    page,
    'crm:create_activity',
    {
      crm_act_type: 'task',
      crm_act_subject: subject,
      crm_act_content: `Completion criteria for ${subject}`,
      crm_act_source: 'manual',
      crm_act_status: 'open',
      crm_act_priority: 'high',
      crm_act_related_model: relatedModel,
      crm_act_related_id: relatedId,
    },
    undefined,
    'create',
  );
}

async function transitionPlan(page: Page, recordId: string, commandCode: string, expected: string) {
  const response = await page.request.post(`/api/meta/commands/execute/${commandCode}`, {
    data: { payload: {}, targetRecordPid: recordId, operationType: 'update' },
  });
  expect(response.ok(), `${commandCode} must accept the exact plan state`).toBe(true);
  await expect
    .poll(async () =>
      String((await dynamicRecord(page, 'crm_activity_common', recordId)).crm_act_status),
    )
    .toBe(expected);
}

async function deletePlan(page: Page, recordId: string): Promise<void> {
  await page.goto(`/p/crm_activity_common/view/${recordId}`, { waitUntil: 'domcontentloaded' });
  const remove = page.getByRole('button', { name: /删除计划|Delete Plan/i });
  await expect(remove).toBeVisible({ timeout: 15_000 });
  await remove.click();
  await page
    .getByRole('button', { name: /确认|Confirm/i })
    .last()
    .click();
  await expect
    .poll(async () => {
      const response = await page.request.get(`/api/dynamic/crm_activity_common/${recordId}`);
      return response.ok();
    })
    .toBe(false);
}

test.describe.serial('CRM follow-up plan lifecycle — Cordys PAR-11 source-bound parity', () => {
  test.setTimeout(240_000);
  const planCompleted = new Set<string>();
  const planScreenshots: string[] = [];
  const planFailedRuntimeRequests: string[] = [];

  test.afterAll(() => {
    const evidenceRoot = process.env.AURA_EVIDENCE_ROOT;
    if (!evidenceRoot) return;
    const sourceIds = PLAN_SOURCE_IDS.map((sourceId) => ({
      sourceId,
      verdict: planCompleted.has(sourceId) ? 'pass' : 'untested',
    }));
    const verdict =
      sourceIds.every((entry) => entry.verdict === 'pass') && planFailedRuntimeRequests.length === 0
        ? 'pass'
        : 'fail';
    fs.mkdirSync(evidenceRoot, { recursive: true });
    fs.writeFileSync(
      path.join(evidenceRoot, `crm-follow-plan-full-lifecycle-${Date.now()}.json`),
      `${JSON.stringify(
        {
          schemaVersion: 1,
          runtime: process.env.AURA_RUNTIME_NAME ?? null,
          verdict,
          technicalVerdict: verdict,
          dataMigration: 'not-required-development-stage',
          runner: { browser: 'chromium', workers: 1, retries: 0 },
          sourceIds,
          screenshots: planScreenshots,
          failedRuntimeRequests: planFailedRuntimeRequests,
        },
        null,
        2,
      )}\n`,
    );
  });

  test('lead, account and opportunity plans edit, transition, isolate and delete @critical @golden', async ({
    page,
  }, testInfo) => {
    await login(page);
    page.on('response', (response) => {
      if (response.status() >= 500 && response.url().includes('/api/')) {
        planFailedRuntimeRequests.push(
          `${response.request().method()} ${response.url()} HTTP ${response.status()}`,
        );
      }
    });
    const uid = uniqueId('crm_follow_plan');
    const leadLabel = `Plan Lead ${uid}`;
    const accountLabel = `Plan Account ${uid}`;
    const opportunityLabel = `Plan Opportunity ${uid}`;
    const lead = await executeCommandViaApi(
      page,
      'crm:create_lead',
      {
        crm_lead_company: leadLabel,
        crm_lead_contact_name: `Plan Contact ${uid}`,
        crm_lead_contact_phone: '13800005552',
        crm_lead_source: 'website',
        crm_lead_score: 65,
      },
      undefined,
      'create',
    );
    const account = await executeCommandViaApi(
      page,
      'crm:create_account',
      { crm_acc_name: accountLabel, crm_acc_industry: 'manufacturing', crm_acc_rating: 'A' },
      undefined,
      'create',
    );
    const opportunity = await executeCommandViaApi(
      page,
      'crm:create_opportunity',
      {
        crm_opp_name: opportunityLabel,
        crm_opp_account_id: account.recordId,
        crm_opp_stage: 'qualification',
        crm_opp_expected_amount: 220000,
      },
      undefined,
      'create',
    );

    const leadSubject = `Lead Plan ${uid}`;
    const accountSubject = `Account Plan ${uid}`;
    const opportunitySubject = `Opportunity Plan ${uid}`;
    const leadPlan = await createFollowPlan(page, 'crm_lead_common', lead.recordId, leadSubject);
    const accountPlan = await createFollowPlan(
      page,
      'crm_account_common',
      account.recordId,
      accountSubject,
    );
    const accountCancelPlan = await createFollowPlan(
      page,
      'crm_account_common',
      account.recordId,
      `Account Cancel Plan ${uid}`,
    );
    const opportunityPlan = await createFollowPlan(
      page,
      'crm_opportunity_common',
      opportunity.recordId,
      opportunitySubject,
    );
    planCompleted.add('api:clue:clue-follow-plan:add');
    planCompleted.add('api:opportunity:opportunity-follow-plan:add');

    await assertObjectTimeline(
      page,
      '/p/crm_lead_common',
      'crm_lead_common',
      leadLabel,
      leadSubject,
      /记录跟进|Log Follow-up/i,
    );
    planCompleted.add('api:clue:clue-follow-plan:list');
    planCompleted.add('api:clue:clue-follow-plan:get');
    const leadShot = testInfo.outputPath('05-lead-follow-plan-timeline.png');
    await page.screenshot({ path: leadShot, fullPage: true });
    planScreenshots.push(leadShot);
    await testInfo.attach('05-lead-follow-plan-timeline', {
      path: leadShot,
      contentType: 'image/png',
    });

    await assertObjectTimeline(
      page,
      '/p/crm_account_common',
      'crm_account_common',
      accountLabel,
      accountSubject,
      /新建计划|New Plan/i,
    );
    planCompleted.add('api:customer:customer-follow-plan:list');

    await assertObjectTimeline(
      page,
      '/p/crm_opportunity_common',
      'crm_opportunity_common',
      opportunityLabel,
      opportunitySubject,
      /安排下一任务|Schedule Next Task|记录活动|Log Activity/i,
    );
    planCompleted.add('api:opportunity:opportunity-follow-plan:list');
    planCompleted.add('api:opportunity:opportunity-follow-plan:get');

    await openMenu(page, '/p/crm_activity_common');
    const planTab = page.getByRole('button', { name: /跟进计划|Follow-up Plans/i });
    await expect(planTab).toBeVisible();
    await planTab.click();
    await expect(page.getByText(leadSubject, { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(accountSubject, { exact: true })).toBeVisible();
    await expect(page.getByText(opportunitySubject, { exact: true })).toBeVisible();
    planCompleted.add('api:follow:follow-up-plan:get-tab-enable-config');
    await page
      .getByRole('button', { name: /新建|Create/i })
      .first()
      .click();
    await waitForFormReady(page, 15_000);
    for (const field of [
      'crm_act_type',
      'crm_act_subject',
      'crm_act_status',
      'crm_act_priority',
      'crm_act_due_date',
      'crm_act_assignee',
    ]) {
      await expect(page.getByTestId(`form-field-${field}`)).toBeVisible();
    }
    planCompleted.add('api:follow:follow-up-plan:get-module-form-config');
    await page.getByTestId('form-back-link').click();

    await updateFromActivityDetail(page, leadPlan.recordId, `Lead Plan Updated ${uid}`);
    planCompleted.add('api:clue:clue-follow-plan:update');
    planCompleted.add('api:follow:follow-up-plan:update');
    await updateFromActivityDetail(page, accountPlan.recordId, `Account Plan Updated ${uid}`);
    planCompleted.add('api:customer:customer-follow-plan:update');
    await updateFromActivityDetail(
      page,
      opportunityPlan.recordId,
      `Opportunity Plan Updated ${uid}`,
    );
    planCompleted.add('api:opportunity:opportunity-follow-plan:update');

    await transitionPlan(page, leadPlan.recordId, 'crm:cancel_task', 'cancelled');
    planCompleted.add('api:clue:clue-follow-plan:cancel-plan');
    planCompleted.add('api:clue:clue-follow-plan:update-status');
    await transitionPlan(page, accountPlan.recordId, 'crm:start_task', 'in_progress');
    await transitionPlan(page, accountPlan.recordId, 'crm:complete_task', 'done');
    planCompleted.add('api:customer:customer-follow-plan:update-status');
    await transitionPlan(page, accountCancelPlan.recordId, 'crm:cancel_task', 'cancelled');
    planCompleted.add('api:customer:customer-follow-plan:cancel-plan');
    await transitionPlan(page, opportunityPlan.recordId, 'crm:start_task', 'in_progress');
    await transitionPlan(page, opportunityPlan.recordId, 'crm:cancel_task', 'cancelled');
    planCompleted.add('api:opportunity:opportunity-follow-plan:cancel-plan');
    planCompleted.add('api:opportunity:opportunity-follow-plan:update-status');
    planCompleted.add('api:follow:follow-up-plan:update-status');

    const terminalTransition = await page.request.post(
      '/api/meta/commands/execute/crm:complete_task',
      { data: { payload: {}, targetRecordPid: opportunityPlan.recordId, operationType: 'update' } },
    );
    expect(terminalTransition.ok(), 'cancelled plans must reject completion').toBe(false);
    const detailShot = testInfo.outputPath('06-follow-plan-terminal-detail.png');
    await page.goto(`/p/crm_activity_common/view/${opportunityPlan.recordId}`, {
      waitUntil: 'domcontentloaded',
    });
    await page.screenshot({ path: detailShot, fullPage: true });
    planScreenshots.push(detailShot);
    await testInfo.attach('06-follow-plan-terminal-detail', {
      path: detailShot,
      contentType: 'image/png',
    });

    await deletePlan(page, leadPlan.recordId);
    planCompleted.add('api:clue:clue-follow-plan:delete-plan');
    await deletePlan(page, accountPlan.recordId);
    await deletePlan(page, accountCancelPlan.recordId);
    planCompleted.add('api:customer:customer-follow-plan:delete-plan');
    await deletePlan(page, opportunityPlan.recordId);
    planCompleted.add('api:opportunity:opportunity-follow-plan:delete-plan');
    planCompleted.add('api:follow:follow-up-plan:delete');

    expect(planFailedRuntimeRequests).toEqual([]);
    expect([...planCompleted].sort()).toEqual([...PLAN_SOURCE_IDS].sort());
  });
});
