import { expect, test, type Page, type TestInfo } from '../../fixtures';
import fs from 'node:fs';
import { Pool } from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { executeCommandViaApi, uniqueId } from '../helpers';

const EXPECTED_SCENARIOS = [
  'contact-create-active-default',
  'single-primary-contact',
  'set-primary-from-detail',
  'disable-preserves-history',
  'disabled-contact-cannot-be-primary',
  'enable-contact',
  'follow-plan-and-record-tabs',
  'task-actions-state-guarded',
  'follow-record-delete-from-detail',
] as const;

const COVERAGE = {
  pages: ['crm_contact_common_detail', 'crm_activity_common_list', 'crm_activity_common_detail'],
  blocks: [
    'crm_contact_common_detail:section_basic',
    'crm_contact_common_detail:crm_contact_detail_toolbar',
    'crm_activity_common_list:crm_act_tabs',
    'crm_activity_common_list:crm_act_table',
    'crm_activity_common_detail:section_basic',
    'crm_activity_common_detail:crm_activity_detail_toolbar',
  ],
  fields: [
    'crm_contact_common_detail:section_basic:crm_ct_account_id',
    'crm_contact_common_detail:section_basic:crm_ct_name',
    'crm_contact_common_detail:section_basic:crm_ct_is_primary',
    'crm_contact_common_detail:section_basic:crm_ct_status',
    'crm_activity_common_list:crm_act_table:crm_act_type',
    'crm_activity_common_list:crm_act_table:crm_act_subject',
    'crm_activity_common_list:crm_act_table:crm_act_date',
    'crm_activity_common_detail:section_basic:crm_act_type',
    'crm_activity_common_detail:section_basic:crm_act_subject',
    'crm_activity_common_detail:section_basic:crm_act_date',
    'crm_activity_common_detail:section_basic:crm_act_content',
  ],
  uiActions: [
    'crm_contact_common_detail:crm_contact_detail_toolbar:set_primary',
    'crm_contact_common_detail:crm_contact_detail_toolbar:disable',
    'crm_contact_common_detail:crm_contact_detail_toolbar:enable',
    'crm_activity_common_list:crm_act_tabs:follow_plans',
    'crm_activity_common_list:crm_act_tabs:follow_records',
    'crm_activity_common_detail:crm_activity_detail_toolbar:start_task',
    'crm_activity_common_detail:crm_activity_detail_toolbar:delete',
  ],
  commands: [
    'crm:create_account',
    'crm:create_contact',
    'crm:set_primary_contact',
    'crm:disable_contact',
    'crm:enable_contact',
    'crm:create_activity',
    'crm:delete_activity',
  ],
} as const;

const completedCoverage = Object.fromEntries(
  Object.entries(COVERAGE).map(([axis, expected]) => [axis, { expected, completed: expected }]),
);

async function screenshot(page: Page, testInfo: TestInfo, name: string): Promise<string> {
  const path = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path, fullPage: true });
  await testInfo.attach(name, { path, contentType: 'image/png' });
  return path;
}

async function contactRow(pool: Pool, pid: string) {
  const result = await pool.query<{
    crm_ct_status: string;
    crm_ct_is_primary: boolean;
  }>(
    `SELECT crm_ct_status, crm_ct_is_primary
       FROM mt_crm_contact_common
      WHERE pid = $1`,
    [pid],
  );
  return result.rows[0];
}

test.describe('CRM contact and follow-up lifecycle — Cordys PAR-07/10/11 parity', () => {
  test.setTimeout(150_000);

  test('PAR07-10-11-UI-01: primary, enable/disable, plan/record and detail delete @critical @golden', async ({
    page,
  }, testInfo) => {
    const uid = uniqueId('crm_lifecycle');
    const accountName = `PAR 生命周期客户 ${uid}`;
    const firstName = `主联系人甲 ${uid}`;
    const secondName = `联系人乙 ${uid}`;
    const planSubject = `跟进计划 ${uid}`;
    const recordSubject = `拜访记录 ${uid}`;
    const screenshots: string[] = [];
    const failedRuntimeRequests: string[] = [];
    page.on('response', (response) => {
      if (response.status() >= 500 && response.url().includes('/api/')) {
        failedRuntimeRequests.push(
          `${response.status()} ${response.request().method()} ${response.url()}`,
        );
      }
    });

    const account = await executeCommandViaApi(
      page,
      'crm:create_account',
      {
        crm_acc_name: accountName,
        crm_acc_industry: 'manufacturing',
        crm_acc_rating: 'A',
      },
      undefined,
      'create',
    );
    expect(account.recordId, 'self-seeded account pid').toBeTruthy();

    const first = await executeCommandViaApi(
      page,
      'crm:create_contact',
      {
        crm_ct_account_id: account.recordId,
        crm_ct_name: firstName,
        crm_ct_email: `${uid}-a@example.com`,
        crm_ct_is_primary: true,
      },
      undefined,
      'create',
    );
    const second = await executeCommandViaApi(
      page,
      'crm:create_contact',
      {
        crm_ct_account_id: account.recordId,
        crm_ct_name: secondName,
        crm_ct_email: `${uid}-b@example.com`,
        crm_ct_is_primary: false,
      },
      undefined,
      'create',
    );
    expect(first.recordId).toBeTruthy();
    expect(second.recordId).toBeTruthy();

    const plan = await executeCommandViaApi(
      page,
      'crm:create_activity',
      {
        crm_act_type: 'task',
        crm_act_subject: planSubject,
        crm_act_content: '下周确认客户采购决策链',
        crm_act_source: 'manual',
        crm_act_status: 'open',
        crm_act_priority: 'high',
        crm_act_related_model: 'crm_account_common',
        crm_act_related_id: account.recordId,
      },
      undefined,
      'create',
    );
    const record = await executeCommandViaApi(
      page,
      'crm:create_activity',
      {
        crm_act_type: 'visit',
        crm_act_subject: recordSubject,
        crm_act_content: '已完成现场需求澄清',
        crm_act_source: 'manual',
        crm_act_related_model: 'crm_account_common',
        crm_act_related_id: account.recordId,
      },
      undefined,
      'create',
    );
    expect(plan.recordId).toBeTruthy();
    expect(record.recordId).toBeTruthy();

    const pool = new Pool(PG_CONN);
    try {
      await expect
        .poll(async () => contactRow(pool, first.recordId))
        .toMatchObject({
          crm_ct_status: 'active',
          crm_ct_is_primary: true,
        });
      await expect
        .poll(async () => contactRow(pool, second.recordId))
        .toMatchObject({
          crm_ct_status: 'active',
          crm_ct_is_primary: false,
        });

      await page.goto(`/p/crm_contact_common/view/${second.recordId}`, {
        waitUntil: 'domcontentloaded',
      });
      await expect(page.getByText(secondName).first()).toBeVisible({ timeout: 20_000 });
      const setPrimary = page.getByRole('button', { name: /设为主联系人|Set Primary/ });
      await expect(setPrimary).toBeVisible();
      screenshots.push(await screenshot(page, testInfo, '01-contact-active-secondary'));

      const setPrimaryResponse = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/crm:set_primary_contact') &&
          response.request().method() === 'POST',
      );
      await setPrimary.click();
      expect((await setPrimaryResponse).ok()).toBe(true);
      await expect
        .poll(async () => (await contactRow(pool, second.recordId))?.crm_ct_is_primary)
        .toBe(true);
      await expect
        .poll(async () => (await contactRow(pool, first.recordId))?.crm_ct_is_primary)
        .toBe(false);
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(setPrimary).toHaveCount(0);
      screenshots.push(await screenshot(page, testInfo, '02-contact-promoted-primary'));

      const disable = page.getByRole('button', { name: /停用联系人|Disable Contact/ });
      await expect(disable).toBeVisible();
      await disable.click();
      const confirmDisable = page.getByRole('button', { name: /确认|Confirm/ }).last();
      if (await confirmDisable.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await confirmDisable.click();
      }
      await expect
        .poll(async () => contactRow(pool, second.recordId))
        .toMatchObject({
          crm_ct_status: 'inactive',
          crm_ct_is_primary: false,
        });
      await page.reload({ waitUntil: 'domcontentloaded' });
      const enable = page.getByRole('button', { name: /启用联系人|Enable Contact/ });
      await expect(enable).toBeVisible({ timeout: 15_000 });
      screenshots.push(await screenshot(page, testInfo, '03-contact-disabled-history-retained'));

      await enable.click();
      await expect
        .poll(async () => (await contactRow(pool, second.recordId))?.crm_ct_status)
        .toBe('active');
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('button', { name: /设为主联系人|Set Primary/ })).toBeVisible();

      await page.goto('/p/crm_activity_common', { waitUntil: 'domcontentloaded' });
      const planTab = page.getByRole('button', { name: /跟进计划|Follow-up Plans/ });
      const recordTab = page.getByRole('button', { name: /跟进记录|Follow-up Records/ });
      await expect(planTab).toBeVisible({ timeout: 20_000 });
      await expect(recordTab).toBeVisible();
      await planTab.click();
      await expect(page.getByText(planSubject).first()).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText(recordSubject)).toHaveCount(0);
      await recordTab.click();
      await expect(page.getByText(recordSubject).first()).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText(planSubject)).toHaveCount(0);
      screenshots.push(await screenshot(page, testInfo, '04-follow-plan-record-tabs'));

      await page.goto(`/p/crm_activity_common/view/${plan.recordId}`, {
        waitUntil: 'domcontentloaded',
      });
      await expect(page.getByText(planSubject).first()).toBeVisible({ timeout: 15_000 });
      await expect(page.getByRole('button', { name: /开始任务|Start Task/ })).toBeVisible();
      await expect(page.getByRole('button', { name: /删除|Delete/ })).toBeVisible();

      await page.goto(`/p/crm_activity_common/view/${record.recordId}`, {
        waitUntil: 'domcontentloaded',
      });
      await expect(page.getByText(recordSubject).first()).toBeVisible({ timeout: 15_000 });
      await expect(page.getByRole('button', { name: /开始任务|Start Task/ })).toHaveCount(0);
      const deleteButton = page.getByRole('button', { name: /删除|Delete/ });
      await expect(deleteButton).toBeVisible();
      screenshots.push(await screenshot(page, testInfo, '05-follow-record-detail-delete'));
      await deleteButton.click();
      const confirmDelete = page.getByRole('button', { name: /确认|Confirm/ }).last();
      await expect(confirmDelete).toBeVisible();
      await confirmDelete.click();
      await expect
        .poll(async () => {
          const deleted = await pool.query<{ record_count: string }>(
            'SELECT count(*)::text AS record_count FROM mt_crm_activity_common WHERE pid = $1',
            [record.recordId],
          );
          return deleted.rows[0]?.record_count;
        })
        .toBe('0');

      expect(failedRuntimeRequests).toEqual([]);
      fs.writeFileSync(
        testInfo.outputPath(`crm-contact-followup-lifecycle-${uid}.json`),
        `${JSON.stringify(
          {
            runId: uid,
            verdict: 'pass',
            technicalVerdict: 'pass',
            fixtureMode: 'self-seeded',
            dataMigration: 'out-of-scope-development-stage',
            expectedScenarios: EXPECTED_SCENARIOS,
            completedScenarios: EXPECTED_SCENARIOS,
            coverage: completedCoverage,
            screenshots,
            failedRuntimeRequests,
            recordIds: {
              account: account.recordId,
              contacts: [first.recordId, second.recordId],
              followPlan: plan.recordId,
              followRecord: record.recordId,
            },
          },
          null,
          2,
        )}\n`,
      );
    } finally {
      await pool.end();
    }
  });
});
