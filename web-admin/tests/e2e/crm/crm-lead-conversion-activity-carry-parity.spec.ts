import { expect, test, type Page, type TestInfo } from '@playwright/test';
import fs from 'node:fs';
import { Pool } from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { executeCommandViaApi, uniqueId } from '../helpers';

const ACTIVITY_MODEL = 'crm_activity_common';
const EXPECTED_SCENARIOS = [
  'lead-context-history-visible-before-conversion',
  'conversion-confirmed-from-detail',
  'conversion-backlinks-visible',
  'direct-anchor-carried',
  'relation-anchor-carried',
  'account-history-visible',
  'contact-history-visible',
  'opportunity-history-visible',
  'activity-comment-preserved',
  'relation-graph-no-duplicates',
] as const;

const COVERAGE = {
  pages: [
    'crm_lead_common_detail',
    'crm_account_common_detail',
    'crm_contact_common_detail',
    'crm_opportunity_common_detail',
    'crm_activity_common_detail',
  ],
  commands: [
    'crm:create_lead',
    'crm:qualify_lead',
    'crm:create_activity',
    'crm:log_lead_activity',
    'crm:convert_lead',
  ],
  queries: ['crm_activities_by_object', 'crm_account_timeline'],
  blocks: [
    'crm_lead_common_detail:crm_lead_tabs',
    'crm_lead_common_detail:block_activities',
    'crm_lead_common_detail:block_conversion',
    'crm_lead_common_detail:crm_lead_detail_toolbar',
    'crm_account_common_detail:crm_account_tabs',
    'crm_account_common_detail:block_activities',
    'crm_contact_common_detail:crm_contact_tabs',
    'crm_contact_common_detail:block_activities',
    'crm_opportunity_common_detail:crm_opportunity_tabs',
    'crm_opportunity_common_detail:block_activities',
    'crm_activity_common_detail:activity_followup_comments',
  ],
  uiActions: [
    'crm_lead_common_detail:crm_lead_tabs:activities',
    'crm_lead_common_detail:crm_lead_detail_toolbar:convert',
    'crm_lead_common_detail:crm_lead_tabs:conversion',
    'crm_account_common_detail:crm_account_tabs:activities',
    'crm_contact_common_detail:crm_contact_tabs:activities',
    'crm_opportunity_common_detail:crm_opportunity_tabs:activities',
  ],
} as const;

type JsonResponse = {
  ok(): boolean;
  status(): number;
  json(): Promise<unknown>;
};

async function expectOk(response: JsonResponse, label: string): Promise<any> {
  const body: any = await response.json().catch(() => ({}));
  expect(
    response.ok() && ['0', '200', 'success'].includes(String(body?.code ?? '').toLowerCase()),
    `${label}: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 1000)}`,
  ).toBe(true);
  return body;
}

async function createLeadActivity(
  page: Page,
  leadPid: string,
  subject: string,
): Promise<string> {
  const response = await page.request.post('/api/meta/commands/execute/crm:log_lead_activity', {
    data: {
      payload: {
        sourceRecordPid: leadPid,
        crm_act_type: 'call',
        crm_act_subject: subject,
        crm_act_content: '通过线索上下文记录的客户沟通，转化后应继续可追溯。',
        crm_act_source: 'manual',
      },
      operationType: 'create',
    },
  });
  const body = await expectOk(response, 'create relation-anchored lead activity');
  const result = body?.data?.data ?? body?.data ?? {};
  const activityPid = String(
    result.recordId ?? result.recordPid ?? result.publicRecordId ?? result.pid ?? '',
  );
  expect(activityPid, 'contextual activity must return a public PID').toBeTruthy();
  return activityPid;
}

async function createComment(page: Page, activityPid: string, content: string): Promise<void> {
  const response = await page.request.post(
    `/api/records/${ACTIVITY_MODEL}/${encodeURIComponent(activityPid)}/comments`,
    { data: { content, mentionUserPids: [] } },
  );
  await expectOk(response, 'create pre-conversion activity comment');
}

async function screenshot(page: Page, testInfo: TestInfo, name: string): Promise<string> {
  const output = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  return output;
}

async function openActivities(
  page: Page,
  route: string,
  subjects: string[],
): Promise<void> {
  await page.goto(route, { waitUntil: 'domcontentloaded' });
  await page.getByRole('tab', { name: /活动记录|活动|Activities/ }).first().click();
  const timeline = page.getByTestId('activity-timeline').first();
  await expect(timeline).toBeVisible({ timeout: 20_000 });
  for (const subject of subjects) {
    const matches = timeline.getByText(subject, { exact: true });
    await expect(matches).toHaveCount(1, { timeout: 20_000 });
    await expect(matches).toBeVisible();
  }
}

test.describe('CRM lead conversion activity carry — Cordys PAR-03 parity', () => {
  test.setTimeout(180_000);

  test('PAR03-UI-01: conversion preserves follow-up graph and comments @critical @golden', async ({
    page,
  }, testInfo) => {
    const uid = uniqueId('crm_convert_activity');
    const company = `PAR-03 转化客户 ${uid}`;
    const contactName = `采购负责人 ${uid}`;
    const directSubject = `现场拜访记录 ${uid}`;
    const relatedSubject = `电话确认计划 ${uid}`;
    const commentText = `转化前协作结论 ${uid}`;
    const screenshots: string[] = [];
    const failedRuntimeRequests: string[] = [];

    page.on('response', (response) => {
      if (response.status() >= 400 && response.url().includes('/api/')) {
        failedRuntimeRequests.push(`${response.status()} ${response.request().method()} ${response.url()}`);
      }
    });

    const lead = await executeCommandViaApi(page, 'crm:create_lead', {
      crm_lead_company: company,
      crm_lead_contact_name: contactName,
      crm_lead_contact_email: `buyer-${uid}@example.test`,
      crm_lead_contact_phone: '13800000000',
      crm_lead_source: 'website',
      crm_lead_industry: 'technology',
      crm_lead_score: 92,
      crm_lead_requirement: '需要 CRM 升级方案，并保留完整的转化前沟通历史。',
    });
    expect(lead.recordId, 'self-seeded lead public PID').toBeTruthy();
    await executeCommandViaApi(page, 'crm:qualify_lead', {}, lead.recordId, 'update');

    const directActivity = await executeCommandViaApi(page, 'crm:create_activity', {
      crm_act_type: 'visit',
      crm_act_subject: directSubject,
      crm_act_content: '通过活动表直接关联到线索的历史记录。',
      crm_act_source: 'manual',
      crm_act_related_model: 'crm_lead_common',
      crm_act_related_id: lead.recordId,
    });
    expect(directActivity.recordId, 'direct activity public PID').toBeTruthy();
    const relatedActivityPid = await createLeadActivity(page, lead.recordId, relatedSubject);
    await createComment(page, relatedActivityPid, commentText);

    const subjects = [directSubject, relatedSubject];
    await openActivities(page, `/p/crm_lead_common/view/${lead.recordId}`, [relatedSubject]);
    await expect(page.getByTestId('activity-timeline').first().getByText(directSubject)).toHaveCount(0);
    screenshots.push(await screenshot(page, testInfo, '01-lead-history-before-conversion'));

    const convertButton = page.getByRole('button', { name: /转化线索|Convert Lead/ }).first();
    await expect(convertButton).toBeVisible();
    const conversionResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/api/meta/commands/execute/crm:convert_lead'),
      { timeout: 30_000 },
    );
    await convertButton.click();
    const confirmDialog = page.getByTestId('confirm-dialog');
    await expect(confirmDialog).toBeVisible();
    await expect(confirmDialog).toContainText(/客户、联系人、商机和客户需求|account, contact, opportunity, and customer request/i);
    screenshots.push(await screenshot(page, testInfo, '02-conversion-confirmation'));

    await page.getByTestId('confirm-ok').click();
    const conversionBody = await expectOk(await conversionResponsePromise, 'convert lead from detail');
    const conversion = conversionBody?.data?.data ?? conversionBody?.data ?? {};
    const accountPid = String(conversion.accountId ?? '');
    const contactPid = String(conversion.contactId ?? '');
    const opportunityPid = String(conversion.opportunityId ?? '');
    const requestPid = String(conversion.customerRequestId ?? '');
    expect([accountPid, contactPid, opportunityPid, requestPid].every(Boolean)).toBe(true);
    expect(Number(conversion.carriedActivityCount)).toBe(2);
    expect(Number(conversion.createdActivityRelationCount)).toBe(7);

    await expect
      .poll(async () => {
        const response = await page.request.get(`/api/dynamic/crm_lead_common/${lead.recordId}`);
        const body = await response.json().catch(() => ({}));
        return body?.data?.crm_lead_status;
      })
      .toBe('converted');
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('tab', { name: /转化结果|Conversion/ }).click();
    const main = page.locator('main, [role="main"]').first();
    await expect(main).toContainText(contactName);
    await expect(main).toContainText(/ACC-LEAD-/);
    await expect(main).toContainText(/OPP-LEAD-/);
    await expect(main).toContainText(/CR-LEAD-/);
    for (const internalPid of [accountPid, contactPid, opportunityPid, requestPid]) {
      await expect(main).not.toContainText(internalPid);
    }
    screenshots.push(await screenshot(page, testInfo, '03-conversion-backlinks'));

    const pool = new Pool(PG_CONN);
    try {
      const relationRows = await pool.query<{
        crm_ar_activity_id: string;
        crm_ar_object_type: string;
        crm_ar_object_id: string;
        edge_count: string;
      }>(
        `SELECT crm_ar_activity_id, crm_ar_object_type, crm_ar_object_id,
                count(*)::text AS edge_count
           FROM mt_crm_activity_relation_common
          WHERE crm_ar_activity_id = ANY($1::text[])
          GROUP BY crm_ar_activity_id, crm_ar_object_type, crm_ar_object_id
          ORDER BY crm_ar_activity_id, crm_ar_object_type`,
        [[directActivity.recordId, relatedActivityPid]],
      );
      expect(relationRows.rows, 'two activities each retain lead and gain account/contact/opportunity')
        .toHaveLength(8);
      expect(relationRows.rows.every((row) => row.edge_count === '1')).toBe(true);
      for (const activityPid of [directActivity.recordId, relatedActivityPid]) {
        const edges = relationRows.rows
          .filter((row) => row.crm_ar_activity_id === activityPid)
          .map((row) => `${row.crm_ar_object_type}:${row.crm_ar_object_id}`)
          .sort();
        expect(edges).toEqual([
          `account:${accountPid}`,
          `contact:${contactPid}`,
          `lead:${lead.recordId}`,
          `opportunity:${opportunityPid}`,
        ].sort());
      }

      const directAnchor = await pool.query<{
        crm_act_related_model: string;
        crm_act_related_id: string;
      }>(
        `SELECT crm_act_related_model, crm_act_related_id
           FROM mt_crm_activity_common
          WHERE pid = $1`,
        [directActivity.recordId],
      );
      expect(directAnchor.rows[0]).toEqual({
        crm_act_related_model: 'crm_lead_common',
        crm_act_related_id: lead.recordId,
      });

      const comments = await pool.query<{ content: string; deleted_flag: boolean }>(
        `SELECT content, deleted_flag
           FROM ab_record_comment
          WHERE model_code = $1 AND record_pid = $2`,
        [ACTIVITY_MODEL, relatedActivityPid],
      );
      expect(comments.rows).toEqual([{ content: commentText, deleted_flag: false }]);
    } finally {
      await pool.end();
    }

    await openActivities(page, `/p/crm_account_common/view/${accountPid}`, subjects);
    screenshots.push(await screenshot(page, testInfo, '04-account-carried-history'));

    await openActivities(page, `/p/crm_contact_common/view/${contactPid}`, subjects);
    screenshots.push(await screenshot(page, testInfo, '05-contact-carried-history'));

    await openActivities(page, `/p/crm_opportunity_common/view/${opportunityPid}`, subjects);
    screenshots.push(await screenshot(page, testInfo, '06-opportunity-carried-history'));

    await page.goto(`/p/${ACTIVITY_MODEL}/view/${relatedActivityPid}`, {
      waitUntil: 'domcontentloaded',
    });
    const thread = page.getByTestId('record-comments');
    await expect(thread).toBeVisible({ timeout: 20_000 });
    await expect(thread.getByText(commentText, { exact: true })).toBeVisible();
    await thread.scrollIntoViewIfNeeded();
    screenshots.push(await screenshot(page, testInfo, '07-preserved-followup-comment'));

    expect(failedRuntimeRequests).toEqual([]);
    fs.writeFileSync(
      testInfo.outputPath(`crm-lead-conversion-activity-carry-${uid}.json`),
      `${JSON.stringify(
        {
          runId: uid,
          verdict: 'pass',
          technicalVerdict: 'pass',
          fixtureMode: 'self-seeded',
          dataMigration: 'out-of-scope-development-stage',
          expectedScenarios: EXPECTED_SCENARIOS,
          completedScenarios: EXPECTED_SCENARIOS,
          coverage: Object.fromEntries(
            Object.entries(COVERAGE).map(([axis, expected]) => [axis, { expected, completed: expected }]),
          ),
          screenshots,
          failedRuntimeRequests,
          recordIds: {
            lead: lead.recordId,
            account: accountPid,
            contact: contactPid,
            opportunity: opportunityPid,
            customerRequest: requestPid,
            activities: [directActivity.recordId, relatedActivityPid],
          },
          conversion: {
            leadPid: lead.recordId,
            accountPid,
            contactPid,
            opportunityPid,
            customerRequestPid: requestPid,
            activities: [directActivity.recordId, relatedActivityPid],
            carriedActivityCount: Number(conversion.carriedActivityCount),
            createdActivityRelationCount: Number(conversion.createdActivityRelationCount),
          },
        },
        null,
        2,
      )}\n`,
    );
  });
});
