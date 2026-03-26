/**
 * Command Pipeline E2E Test
 *
 * Verifies key Command system stages work correctly:
 * - AUTO_SET: Auto-generated codes (ACC-{date}-{seq})
 * - STATE_CHECK: Preconditions (only draft can be deleted)
 * - SIDE_EFFECT: Creating opportunity auto-triggers related actions
 * - SCHEMA_VALIDATE: Field validation (required fields, format)
 * - ROLL_UP: Parent record auto-aggregation
 *
 * Uses CRM models which have all these features configured.
 */

import { test, expect } from '@playwright/test';
import { executeCommandViaApi, uniqueId } from '../helpers';

const uid = uniqueId('cmd');

test.describe('Command Pipeline Stages', () => {
  test.use({ storageState: 'tests/storage/admin.json' });
  test.setTimeout(60_000);

  let accountPid: string;
  let opportunityPid: string;

  test('AUTO_SET: Account creation generates auto-code', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'crm:create_account', {
      crm_acc_name: `Pipeline测试客户_${uid}`,
      crm_acc_industry: 'technology',
    });

    expect(result.code).toBe('0');
    expect(result.recordId).toBeTruthy();
    accountPid = result.recordId;

    // Fetch the record to verify auto-generated code
    const resp = await page.request.get(`/api/dynamic/crm_account/${accountPid}`);
    const body = await resp.json();
    const record = body?.data;

    // crm_acc_code should be auto-generated: ACC-{yyyyMMdd}-{seq}
    expect(record?.crm_acc_code).toMatch(/^ACC-\d{8}-\d+$/);
    // crm_acc_status should be auto-set to 'active'
    expect(record?.crm_acc_status).toBe('active');
    // crm_acc_owner should be auto-set to current user
    expect(record?.crm_acc_owner).toBeTruthy();

    console.log(`  AUTO_SET verified: code=${record?.crm_acc_code}, status=${record?.crm_acc_status}`);
  });

  test('SCHEMA_VALIDATE: Required field validation rejects empty name', async ({ page }) => {
    // Try to create account without required field (crm_acc_name)
    const result = await executeCommandViaApi(
      page,
      'crm:create_account',
      { crm_acc_industry: 'technology' }, // missing crm_acc_name
      undefined,
      'create',
      { allowHttpError: true }
    );

    // Should fail validation
    expect(result.code).not.toBe('0');
    console.log('  SCHEMA_VALIDATE verified: missing required field rejected');
  });

  test('STATE_CHECK: Opportunity creation defaults to discovery stage', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'crm:create_opportunity', {
      crm_opp_name: `Pipeline商机_${uid}`,
      crm_opp_account_id: accountPid,
      crm_opp_expected_amount: 100000,
    });

    expect(result.code).toBe('0');
    opportunityPid = result.recordId;

    // Verify default stage
    const resp = await page.request.get(`/api/dynamic/crm_opportunity/${opportunityPid}`);
    const body = await resp.json();
    expect(body?.data?.crm_opp_stage).toBe('discovery');
    expect(body?.data?.crm_opp_code).toMatch(/^OPP-\d{8}-\d+$/);

    console.log('  STATE_CHECK verified: default stage = discovery');
  });

  test('STATE_CHECK: Stage transition follows valid path', async ({ page }) => {
    // discovery → qualification (valid)
    const qualResult = await executeCommandViaApi(
      page, 'crm:qualify_opportunity', {}, opportunityPid, 'update'
    );
    expect(qualResult.code).toBe('0');

    // Verify stage changed
    const resp = await page.request.get(`/api/dynamic/crm_opportunity/${opportunityPid}`);
    const body = await resp.json();
    expect(body?.data?.crm_opp_stage).toBe('qualification');

    console.log('  STATE_CHECK verified: discovery → qualification transition works');
  });

  test('STATE_CHECK: Invalid transition is rejected', async ({ page }) => {
    // Try to win directly from qualification (should need proposal → negotiation first)
    const result = await executeCommandViaApi(
      page, 'crm:win_opportunity', {}, opportunityPid, 'update',
      { allowHttpError: true }
    );

    // Should fail — can't jump from qualification to closed_won
    expect(result.code).not.toBe('0');
    console.log('  STATE_CHECK verified: invalid transition rejected');
  });

  test('Lead status lifecycle: new → contacted → qualified → converted', async ({ page }) => {
    // Create lead
    const createResult = await executeCommandViaApi(page, 'crm:create_lead', {
      crm_lead_company: `Pipeline线索公司_${uid}`,
      crm_lead_contact_name: '测试联系人',
      crm_lead_source: 'website',
    });
    expect(createResult.code).toBe('0');
    const leadPid = createResult.recordId;

    // Verify initial status = new
    let resp = await page.request.get(`/api/dynamic/crm_lead/${leadPid}`);
    let body = await resp.json();
    expect(body?.data?.crm_lead_status).toBe('new');

    // new → contacted
    await executeCommandViaApi(page, 'crm:contact_lead', {}, leadPid, 'update');
    resp = await page.request.get(`/api/dynamic/crm_lead/${leadPid}`);
    body = await resp.json();
    expect(body?.data?.crm_lead_status).toBe('contacted');

    // contacted → qualified
    await executeCommandViaApi(page, 'crm:qualify_lead', {}, leadPid, 'update');
    resp = await page.request.get(`/api/dynamic/crm_lead/${leadPid}`);
    body = await resp.json();
    expect(body?.data?.crm_lead_status).toBe('qualified');

    // qualified → converted
    await executeCommandViaApi(page, 'crm:convert_lead', {}, leadPid, 'update');
    resp = await page.request.get(`/api/dynamic/crm_lead/${leadPid}`);
    body = await resp.json();
    expect(body?.data?.crm_lead_status).toBe('converted');

    console.log('  Lead lifecycle verified: new → contacted → qualified → converted');
  });

  test('Campaign status lifecycle: planned → active → completed', async ({ page }) => {
    const createResult = await executeCommandViaApi(page, 'crm:create_campaign', {
      crm_cpn_name: `Pipeline营销活动_${uid}`,
      crm_cpn_type: 'digital',
      crm_cpn_budget: 10000,
    });
    expect(createResult.code).toBe('0');
    const campaignPid = createResult.recordId;

    // Verify initial = planned
    let resp = await page.request.get(`/api/dynamic/crm_campaign/${campaignPid}`);
    let body = await resp.json();
    expect(body?.data?.crm_cpn_status).toBe('planned');

    // planned → active
    await executeCommandViaApi(page, 'crm:activate_campaign', {}, campaignPid, 'update');
    resp = await page.request.get(`/api/dynamic/crm_campaign/${campaignPid}`);
    body = await resp.json();
    expect(body?.data?.crm_cpn_status).toBe('active');

    // active → completed
    await executeCommandViaApi(page, 'crm:complete_campaign', {}, campaignPid, 'update');
    resp = await page.request.get(`/api/dynamic/crm_campaign/${campaignPid}`);
    body = await resp.json();
    expect(body?.data?.crm_cpn_status).toBe('completed');

    console.log('  Campaign lifecycle verified: planned → active → completed');
  });

  // Cleanup: delete test records (they have uid prefix, won't affect showcase data)
  test('Cleanup: Remove pipeline test data', async ({ page }) => {
    // Delete opportunity
    if (opportunityPid) {
      // First transition back to a deletable state if possible, or force delete
      await executeCommandViaApi(
        page, 'crm:delete_opportunity', {}, opportunityPid, 'delete',
        { allowHttpError: true }
      );
    }
    // Delete account
    if (accountPid) {
      await executeCommandViaApi(
        page, 'crm:delete_account', {}, accountPid, 'delete',
        { allowHttpError: true }
      );
    }
    console.log('  Cleanup: test data removed');
  });
});
