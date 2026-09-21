import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  executeCommand,
  readDynamicRecord,
  queryDynamicRecords,
  ensureQuoteRoleUser,
  openQuoteRolePage,
  seedQuoteForCorrectedBomUpload,
  type CreatedRows,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Release gate for QuoteOps surface authorization.
 *
 * This spec proves the two invariants that a page-only test would miss:
 *   1. quote root ACL still blocks users who did not receive the record;
 *   2. surface permission only broadens tab access after root access is allowed.
 *
 * Fixed smoke accounts keep the gate reproducible on an already-used stack. They are
 * test fixtures, not real employees. The quote itself is admin-owned and explicitly
 * shared through ReBAC, which isolates surface grants from ordinary owner/self scope.
 */

const QUOTE_ROLE_TEST_PASSWORD = 'Test2026x';

type SmokeKey = 'sales' | 'sales_b' | 'procurement' | 'approver';

const SMOKE_USERS: Record<SmokeKey, QuoteRoleUser> = {
  sales: {
    key: 'smoke_surface_sales',
    email: 'smoke-surface-sales@e2e.local',
    displayName: 'Smoke Surface Sales',
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes: ['qo_sales'],
  },
  sales_b: {
    key: 'smoke_surface_sales_b',
    email: 'smoke-surface-sales-b@e2e.local',
    displayName: 'Smoke Surface Sales B',
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes: ['qo_sales'],
  },
  procurement: {
    key: 'smoke_surface_proc',
    email: 'smoke-surface-proc@e2e.local',
    displayName: 'Smoke Surface Procurement',
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes: ['qo_procurement'],
  },
  approver: {
    key: 'smoke_surface_approver',
    email: 'smoke-surface-approver@e2e.local',
    displayName: 'Smoke Surface Approver',
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes: ['qo_commercial_approver'],
  },
};

const SHARED_SMOKE_KEYS: Array<SmokeKey> = ['sales', 'procurement', 'approver'];

const TAB_LABELS = {
  materials: '资料上传',
  bomPrice: 'BOM价格计算',
  processFee: '加工点数',
  output: '报价Excel',
};

type SurfaceProbe = { queryCode: string; expectedStatus: number };

const EXPECTED_SURFACES: Record<
  SmokeKey,
  { visibleTabs: string[]; hiddenTabs: string[]; probes: SurfaceProbe[] }
> = {
  sales: {
    visibleTabs: [TAB_LABELS.materials, TAB_LABELS.bomPrice],
    hiddenTabs: [TAB_LABELS.processFee, TAB_LABELS.output],
    probes: [
      { queryCode: 'qo_quote_bom_price_metrics', expectedStatus: 200 },
      { queryCode: 'qo_quote_process_fee_metrics', expectedStatus: 403 },
      { queryCode: 'qo_quote_output_readiness', expectedStatus: 403 },
      { queryCode: 'qo_quote_output_documents', expectedStatus: 403 },
    ],
  },
  procurement: {
    visibleTabs: [TAB_LABELS.materials, TAB_LABELS.bomPrice, TAB_LABELS.processFee],
    hiddenTabs: [TAB_LABELS.output],
    probes: [
      { queryCode: 'qo_quote_bom_price_metrics', expectedStatus: 200 },
      { queryCode: 'qo_quote_process_fee_metrics', expectedStatus: 200 },
      { queryCode: 'qo_quote_output_readiness', expectedStatus: 403 },
      { queryCode: 'qo_quote_output_documents', expectedStatus: 403 },
    ],
  },
  approver: {
    visibleTabs: [TAB_LABELS.materials, TAB_LABELS.processFee, TAB_LABELS.output],
    hiddenTabs: [TAB_LABELS.bomPrice],
    probes: [
      { queryCode: 'qo_quote_bom_price_metrics', expectedStatus: 403 },
      { queryCode: 'qo_quote_process_fee_metrics', expectedStatus: 200 },
      { queryCode: 'qo_quote_output_readiness', expectedStatus: 200 },
      { queryCode: 'qo_quote_output_documents', expectedStatus: 200 },
    ],
  },
  sales_b: {
    visibleTabs: [],
    hiddenTabs: [],
    probes: [{ queryCode: 'qo_quote_bom_price_metrics', expectedStatus: 403 }],
  },
};

test.describe('Quote surface permission release gate', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  let quote: CreatedRows;
  let sharePids: string[] = [];

  test.beforeAll(async ({ browser }) => {
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    quote = await seedQuoteForCorrectedBomUpload(adminPage);

    for (const user of Object.values(SMOKE_USERS)) {
      await ensureQuoteRoleUser(adminPage, user);
    }

    for (const key of SHARED_SMOKE_KEYS) {
      const user = SMOKE_USERS[key];
      const search = await adminPage.request.get(
        `/api/admin/users/search?keyword=${encodeURIComponent(user.email)}&size=5`,
      );
      const searchBody = await search.json().catch(() => ({}) as any);
      expect(
        search.ok(),
        `smoke user search ${key}: HTTP ${search.status()} ${JSON.stringify(searchBody)}`,
      ).toBe(true);
      const users = Array.isArray(searchBody?.data) ? searchBody.data : [];
      const userPid = users.find((item: any) => item?.email === user.email)?.pid;
      expect(userPid, `smoke user ${key} should expose a stable pid`).toBeTruthy();

      const share = await adminPage.request.post('/api/record-share', {
        data: {
          resourceCode: 'qo_quote_common',
          recordPid: quote.quoteId,
          subjectType: 'member',
          subjectPid: userPid,
          permissionMask: 'read',
        },
      });
      expect(
        share.ok(),
        `record share for ${key}: HTTP ${share.status()} ${await share.text()}`,
      ).toBe(true);
    }

    const shares = await adminPage.request.get(
      `/api/record-share?resourceCode=qo_quote_common&recordPid=${encodeURIComponent(quote.quoteId)}`,
    );
    const sharesBody = await shares.json().catch(() => ({}) as any);
    sharePids = (Array.isArray(sharesBody?.data) ? sharesBody.data : [])
      .map((item: any) => String(item?.pid ?? ''))
      .filter(Boolean);

    await adminContext.close();
  });

  test.afterAll(async ({ browser }) => {
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    if (sharePids.length > 0) {
      await adminPage.request.post('/api/record-share/batch-delete', {
        data: { sharePids },
      });
    }
    await cleanupRows(adminPage, quote);
    await adminContext.close();
  });

  for (const key of Object.keys(SMOKE_USERS) as Array<SmokeKey>) {
    test(`${SMOKE_USERS[key].key}: root ACL and surface authorization`, async ({ browser }) => {
      const { page } = await openQuoteRolePage(browser, SMOKE_USERS[key]);

      const root = await page.request.get(
        `/api/dynamic/qo_quote_common/${encodeURIComponent(quote.quoteId)}`,
      );
      expect(root.status(), `unshared smoke ${key} root visibility`).toBe(
        key === 'sales_b' ? 403 : 200,
      );

      for (const probe of EXPECTED_SURFACES[key].probes) {
        const response = await page.request.post(
          `/api/meta/named-queries/${probe.queryCode}/execute`,
          { data: { parameters: { quoteId: quote.quoteId } } },
        );
        expect(
          response.status(),
          `${key} ${probe.queryCode} HTTP status`,
        ).toBe(probe.expectedStatus);
      }

      await page.goto(
        `/p/qo_quote_common/view/${quote.quoteId}#bom_price`,
        { waitUntil: 'domcontentloaded' },
      );

      for (const label of EXPECTED_SURFACES[key].visibleTabs) {
        await expect(page.getByRole('tab', { name: label })).toBeVisible({ timeout: 20_000 });
      }
      for (const label of EXPECTED_SURFACES[key].hiddenTabs) {
        await expect(page.getByRole('tab', { name: label })).toHaveCount(0);
      }
    });
  }
});


test('formal quote approval: UI submit, explicit approval and immutable baseline after freshness change', async ({ page, browser }, info) => {
  test.setTimeout(120_000);
  const marker = `FORMAL-${Date.now()}`;
  const {scenarioId,quoteId} = await createFormalQuoteFixture(page,marker);
  const before = await readDynamicRecord(page,'qo_quote_common',quoteId);
  expect(before.qo_quote_formal_version).toBe(true);
  expect(before.qo_quote_status).toBe('priced');
  const tiersBefore = await queryDynamicRecords(page,'qo_price_tier_common',[{fieldName:'qo_pt_quote_id',operator:'EQ',value:quoteId}]);
  expect(tiersBefore).toHaveLength(1);
  await page.goto(`/p/qo_quote_common/view/${quoteId}#overview`,{waitUntil:'domcontentloaded'});
  await expect(page.getByRole('button',{name:'修改套数/价格系数',exact:true})).toHaveCount(0);
  const submitResponse=page.waitForResponse(r=>r.url().includes('qo_quote_common:submit_approval')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'提交正式报价审批',exact:true}).click();
  const submitted=await submitResponse;
  expect(submitted.status()).toBe(200);expect(String((await submitted.json()).code)).toBe('0');
  expect((await readDynamicRecord(page,'qo_quote_common',quoteId)).qo_quote_status).toBe('pending_approval');
  for (const key of ['sales', 'approver'] as const) {
    const user = SMOKE_USERS[key];
    await ensureQuoteRoleUser(page, user);
    const search = await page.request.get(`/api/admin/users/search?keyword=${encodeURIComponent(user.email)}&size=5`);
    expect(search.ok()).toBe(true);
    const userPid = (await search.json()).data.find((item: any) => item.email === user.email)?.pid;
    expect(userPid).toBeTruthy();
    const share = await page.request.post('/api/record-share', {data: {
      resourceCode:'qo_quote_common', recordPid:quoteId, subjectType:'member', subjectPid:userPid, permissionMask:'read',
    }});
    expect(share.ok()).toBe(true);
  }
  const sales = await openQuoteRolePage(browser, SMOKE_USERS.sales);
  await sales.page.goto(`/p/qo_quote_common/view/${quoteId}#overview`);
  await expect(sales.page.getByRole('heading',{name:'PCBA 报价单',exact:true})).toBeVisible();
  await expect(sales.page.getByRole('button',{name:'批准正式报价',exact:true})).toHaveCount(0);
  const pendingSnapshot=await readDynamicRecord(page,'qo_quote_common',quoteId);
  const caseFilter=[{fieldName:'crm_apc_source_model',operator:'EQ',value:'qo_quote_common'},
    {fieldName:'crm_apc_source_id',operator:'EQ',value:quoteId}];
  const pendingCases=await queryDynamicRecords(page,'crm_approval_case_common',caseFilter);
  const denied = await sales.page.request.post('/api/meta/commands/execute/qo_quote_common:approve', {
    data:{targetRecordId:quoteId,targetRecordPid:quoteId,operationType:'update',payload:{approved_by:'Unauthorized sales',decisionNote:'Must fail'}},
  });
  expect(denied.status()).toBe(403);
  const rejectDenied=await sales.page.request.post('/api/meta/commands/execute/qo_quote_common:reject', {
    data:{targetRecordId:quoteId,targetRecordPid:quoteId,operationType:'update',payload:{decisionNote:'Unauthorized rejection'}},
  });
  expect(rejectDenied.status()).toBe(403);
  expect(await readDynamicRecord(page,'qo_quote_common',quoteId)).toEqual(pendingSnapshot);
  expect(await queryDynamicRecords(page,'crm_approval_case_common',caseFilter)).toEqual(pendingCases);
  await sales.context.close();
  const approver = await openQuoteRolePage(browser, SMOKE_USERS.approver);
  const approvalPage = approver.page;
  await approvalPage.goto(`/p/qo_quote_common/view/${quoteId}#overview`);
  await approvalPage.getByRole('button',{name:'批准正式报价',exact:true}).click();
  const dialog=approvalPage.getByTestId('form-dialog');
  await expect(dialog).toBeVisible();
  await approvalPage.getByTestId('form-dialog-field-approved_by').fill('Local E2E commercial approver');
  await approvalPage.getByTestId('form-dialog-field-decisionNote').fill(`Reviewed ${marker}`);
  const approvalResponse=approvalPage.waitForResponse(r=>r.url().includes('qo_quote_common:approve')&&r.request().method()==='POST');
  await approvalPage.getByTestId('form-dialog-submit').click();
  const approval=await approvalResponse;
  expect(approval.status()).toBe(200);expect(String((await approval.json()).code)).toBe('0');
  const approved=await readDynamicRecord(page,'qo_quote_common',quoteId);
  expect(approved.qo_quote_status).toBe('approved');
  expect(approved.qo_quote_approved_by).toBe('Local E2E commercial approver');
  const approvedCases=await queryDynamicRecords(page,'crm_approval_case_common',caseFilter);
  const replay=await approvalPage.request.post('/api/meta/commands/execute/qo_quote_common:approve', {
    data:{targetRecordId:quoteId,targetRecordPid:quoteId,operationType:'update',expectedVersion:approved.row_version,
      payload:{approved_by:'Replay must not replace actor',decisionNote:'Replay must not replace reason'}},
  });
  expect([400,403,409,422]).toContain(replay.status());
  expect(String((await replay.json()).code)).not.toBe('0');
  expect(await readDynamicRecord(page,'qo_quote_common',quoteId)).toEqual(approved);
  expect(await queryDynamicRecords(page,'crm_approval_case_common',caseFilter)).toEqual(approvedCases);
  await approver.context.close();
  const mutation=await page.request.post('/api/meta/commands/execute/qo_quote_common:update', {
    data:{targetRecordId:quoteId,targetRecordPid:quoteId,operationType:'update',expectedVersion:approved.row_version,
      payload:{qo_quote_customer:'Must not mutate frozen customer',qo_quote_discount_pct:99}},
  });
  expect([400,403,409,422]).toContain(mutation.status());
  const mutationBody=await mutation.json();
  expect(String(mutationBody.code)).not.toBe('0');
  expect(JSON.stringify(mutationBody)).toMatch(/immutable|不可修改|不可变/i);
  expect(await readDynamicRecord(page,'qo_quote_common',quoteId)).toEqual(approved);
  expect(await queryDynamicRecords(page,'crm_approval_case_common',caseFilter)).toEqual(approvedCases);
  expect(await queryDynamicRecords(page,'qo_price_tier_common',[{fieldName:'qo_pt_quote_id',operator:'EQ',value:quoteId}])).toEqual(tiersBefore);
  await info.attach('formal-approval-protection',{body:JSON.stringify({quoteId,pendingSnapshot,approved,approvedCases,
    unauthorizedApproval:denied.status(),unauthorizedRejection:rejectDenied.status(),replay:replay.status(),mutation:mutation.status()}),contentType:'application/json'});
  await page.reload();
  await page.getByRole('button',{name:'检查过期与重算',exact:true}).click();
  const freshnessDialog = page.getByRole('dialog', {name:'检查过期与输入变化'});
  const freshnessFields = freshnessDialog.getByRole('textbox');
  await expect(freshnessFields).toHaveCount(2);
  await freshnessFields.nth(0).fill('2026-09-15');
  await freshnessFields.nth(1).fill(`${marker}-changed`);
  await expect(freshnessFields.nth(0)).toHaveValue('2026-09-15');
  await expect(freshnessFields.nth(1)).toHaveValue(`${marker}-changed`);
  const freshnessResponse=page.waitForResponse(r=>r.url().includes('qo_quote_common:assess_freshness')&&r.request().method()==='POST');
  await freshnessDialog.getByTestId('form-dialog-submit').click();
  const fresh=await freshnessResponse;
  expect(fresh.status()).toBe(200);expect(String((await fresh.json()).code)).toBe('0');
  const after=await readDynamicRecord(page,'qo_quote_common',quoteId);
  expect(after.qo_quote_status).toBe('needs_recalculation');
  expect(after.qo_quote_baseline_fingerprint).toBe(before.qo_quote_baseline_fingerprint);
  expect(after.qo_quote_customer_result).toEqual(before.qo_quote_customer_result);
  expect(await queryDynamicRecords(page,'qo_price_tier_common',[{fieldName:'qo_pt_quote_id',operator:'EQ',value:quoteId}])).toEqual(tiersBefore);
  await page.reload();
  await expect(page.getByRole('button',{name:'检查过期与重算',exact:true})).toBeVisible();
  await expect(page.getByText('需重算',{exact:true}).first()).toBeVisible();
  await expect(page.getByRole('button',{name:'提交正式报价审批',exact:true})).toHaveCount(0);
  await info.attach('formal-needs-recalculation', {body:await page.screenshot({fullPage:true}),contentType:'image/png'});
  info.annotations.push({type:'formal-quote',description:JSON.stringify({scenarioId,quoteId,before:before.qo_quote_status,after:after.qo_quote_status})});
});

async function createFormalQuoteFixture(page: import('@playwright/test').Page, marker: string, createQuote = true) {
  const payload: Record<string, unknown> = {
    qo_cs_name: marker, qo_cs_customer: marker, qo_cs_commitment_level: 'estimate', qo_cs_currency: 'CNY',
    qo_cs_valid_until: '2030-12-31', idempotency_key: marker,
    tiers: [{qo_cst_quantity: 100, qo_cst_currency: 'CNY', qo_cst_material_unit_cost: 10,
      qo_cst_process_unit_cost: 2, qo_cst_nre_total: 100, qo_cst_other_unit_cost: 1,
      qo_cst_risk_pct: 5, qo_cst_target_margin_pct: 20}], assumptions: [], gaps: [],
  };
  // API setup exercises the scenario contract; it does not certify an upstream MDP release.
  for (const field of ['mdp_id','mdp_version','mdp_hash','structure_ref','process_ref','pack_set_version',
    'price_snapshot_version','rate_card_version','fx_rate_version','rule_set_version','risk_version','assumption_set_version'])
    payload[`qo_cs_${field}`] = `${marker}-${field}`;
  const created = await executeCommand(page, 'qo_cost_scenario_common:create_from_mdp', payload, undefined, 'create');
  const scenarioId = String(created.recordId);
  expect(scenarioId).not.toBe('undefined');
  await executeCommand(page, 'qo_cost_scenario_common:calculate', {}, scenarioId, 'update');
  await executeCommand(page, 'qo_cost_scenario_common:freeze', {frozen_by:'Local E2E'}, scenarioId, 'update');
  if (!createQuote) return {scenarioId,quoteId:''};
  const result = await executeCommand(page, 'qo_quote_common:create_version_from_scenario', {scenario_id:scenarioId,qo_quote_customer:marker}, undefined, 'create');
  const quoteId = String(result.recordId);
  expect(quoteId).not.toBe('undefined');
  return {scenarioId,quoteId};
}

test('formal approval center: menu, approve with audit fields, reject, search and recovery', async ({page,browser}, info) => {
  test.setTimeout(120_000);
  const marker=`CENTER-${Date.now()}`;
  const fixtures=[];
  for (const suffix of ['approve','reject']) {
    const {quoteId}=await createFormalQuoteFixture(page,`${marker}-${suffix}`);
    await executeCommand(page,'qo_quote_common:submit_approval',{},quoteId,'update');
    fixtures.push(await readDynamicRecord(page,'qo_quote_common',quoteId));
  }
  await ensureQuoteRoleUser(page,SMOKE_USERS.approver);
  const searchUser=await page.request.get(`/api/admin/users/search?keyword=${encodeURIComponent(SMOKE_USERS.approver.email)}&size=5`);
  expect(searchUser.ok()).toBe(true);
  const userPid=(await searchUser.json()).data.find((u:any)=>u.email===SMOKE_USERS.approver.email)?.pid;
  expect(userPid).toBeTruthy();
  for (const quote of fixtures) {
    const response=await page.request.post('/api/record-share',{data:{resourceCode:'qo_quote_common',recordPid:quote.pid,subjectType:'member',subjectPid:userPid,permissionMask:'read'}});
    expect(response.ok()).toBe(true);
  }
  const actor=await openQuoteRolePage(browser,SMOKE_USERS.approver);
  const ui=actor.page;
  const listResponse=ui.waitForResponse(r=>r.url().includes('/api/dynamic/qo_quote_common/list'));
  await ui.getByRole('link',{name:'正式报价审批',exact:true}).click();
  const response=await listResponse;
  expect(response.status()).toBe(200);
  expect(decodeURIComponent(response.url())).toContain('qo_quote_formal_version');
  await expect(ui.getByRole('heading',{name:'正式报价版本审批',exact:true})).toBeVisible();
  const search=ui.getByTestId('list-search-input').first();
  await search.fill(marker);await search.press('Enter');
  const approveRow=ui.getByRole('row').filter({hasText:String(fixtures[0].qo_quote_code)});
  await expect(approveRow).toContainText('待审批');
  await approveRow.getByTestId('row-action-more').click();
  await ui.getByRole('menuitem',{name:'批准',exact:true}).last().click();
  await expect(ui.getByTestId('form-dialog')).toBeVisible();
  await ui.getByTestId('form-dialog-submit').click();
  await expect(ui.getByTestId('form-dialog').getByText(/请.*批准人/)).toBeVisible();
  await expect(ui.getByTestId('form-dialog').getByText(/请.*审批说明/)).toBeVisible();
  expect((await readDynamicRecord(page,'qo_quote_common',String(fixtures[0].pid))).qo_quote_status).toBe('pending_approval');
  await ui.getByTestId('form-dialog-field-approved_by').fill('Center commercial approver');
  await ui.getByTestId('form-dialog-field-decisionNote').fill(`Approval evidence ${marker}`);
  const approvalStartedAt=Date.now();
  const approvalResponse=ui.waitForResponse(r=>r.url().includes('qo_quote_common:approve')&&r.request().method()==='POST');
  await ui.getByTestId('form-dialog-submit').click();
  const approvedResponse=await approvalResponse;
  expect(approvedResponse.status()).toBe(200);expect(String((await approvedResponse.json()).code)).toBe('0');
  const approved=await readDynamicRecord(page,'qo_quote_common',String(fixtures[0].pid));
  expect(approved.qo_quote_status).toBe('approved');expect(approved.qo_quote_approved_by).toBe('Center commercial approver');
  const approvedAt=Date.parse(String(approved.qo_quote_approved_at));
  expect(Number.isFinite(approvedAt)).toBe(true);
  expect(approvedAt).toBeGreaterThanOrEqual(approvalStartedAt-1000);
  expect(approvedAt).toBeLessThanOrEqual(Date.now()+1000);
  expect(approved.qo_quote_baseline_fingerprint).toBe(fixtures[0].qo_quote_baseline_fingerprint);
  expect(approved.qo_quote_customer_result).toEqual(fixtures[0].qo_quote_customer_result);
  const cases=await queryDynamicRecords(page,'crm_approval_case_common',[
    {fieldName:'crm_apc_source_model',operator:'EQ',value:'qo_quote_common'},
    {fieldName:'crm_apc_source_id',operator:'EQ',value:String(fixtures[0].pid)},
  ]);
  expect(cases).toHaveLength(1);expect(cases[0].crm_apc_status).toBe('approved');
  expect(cases[0].crm_apc_decision_note).toBe(`Approval evidence ${marker}`);
  const rejectRow=ui.getByRole('row').filter({hasText:String(fixtures[1].qo_quote_code)});
  await expect(rejectRow).toContainText('待审批');
  await rejectRow.getByTestId('row-action-more').click();
  await ui.getByRole('menuitem',{name:'驳回',exact:true}).last().click();
  await ui.getByRole('button',{name:/确定|确认/,exact:true}).last().click();
  await expect(ui.getByTestId('form-dialog')).toBeVisible();
  await ui.getByTestId('form-dialog-submit').click();
  await expect(ui.getByTestId('form-dialog').getByText(/请.*驳回原因/)).toBeVisible();
  expect((await readDynamicRecord(page,'qo_quote_common',String(fixtures[1].pid))).qo_quote_status).toBe('pending_approval');
  const rejectionNote=`Pricing must be reviewed ${marker}`;
  await ui.getByTestId('form-dialog-field-decisionNote').fill(rejectionNote);
  await info.attach('formal-rejection-dialog',{body:await ui.screenshot({fullPage:true}),contentType:'image/png'});
  const rejectedResponse=ui.waitForResponse(r=>r.url().includes('qo_quote_common:reject')&&r.request().method()==='POST');
  await ui.getByTestId('form-dialog-submit').click();
  const rejected=await rejectedResponse;expect(rejected.status()).toBe(200);expect(String((await rejected.json()).code)).toBe('0');
  expect((await readDynamicRecord(page,'qo_quote_common',String(fixtures[1].pid))).qo_quote_status).toBe('revised');
  const rejectionCases=await queryDynamicRecords(page,'crm_approval_case_common',[
    {fieldName:'crm_apc_source_model',operator:'EQ',value:'qo_quote_common'},
    {fieldName:'crm_apc_source_id',operator:'EQ',value:String(fixtures[1].pid)},
  ]);
  expect(rejectionCases).toHaveLength(1);
  expect(rejectionCases[0].crm_apc_status).toBe('rejected');
  expect(rejectionCases[0].crm_apc_decision_note).toBe(rejectionNote);
  await expect(ui.getByRole('row').filter({hasText:String(fixtures[1].qo_quote_code)})).toContainText('已改版');
  await expect(ui.getByRole('row').filter({hasText:String(fixtures[1].qo_quote_code)}).getByTestId('row-action-more')).toHaveCount(0);
  await search.fill(`${marker}-no-record`);await search.press('Enter');
  await expect(ui.getByText('暂无数据',{exact:true}).first()).toBeVisible();
  await search.fill(marker);await search.press('Enter');
  await expect(ui.getByRole('row').filter({hasText:String(fixtures[0].qo_quote_code)})).toContainText('已批准');
  for (const label of ['已批准','已改版']) {
    const status=ui.getByText(label,{exact:true});
    await status.scrollIntoViewIfNeeded();
    const dimensions=await status.evaluate(element=>({scroll:element.scrollWidth,client:element.clientWidth}));
    expect(dimensions.client,`${label} must have visible width`).toBeGreaterThan(0);
    expect(dimensions.scroll,`${label} must not truncate`).toBeLessThanOrEqual(dimensions.client+1);
  }
  await info.attach('formal-approval-center',{body:await ui.screenshot({fullPage:true}),contentType:'image/png'});
  const dateCell=ui.getByRole('cell',{name:'2030-12-31',exact:true}).first();
  await dateCell.evaluate(element=>{
    let parent=element.parentElement;
    while(parent && !['auto','scroll'].includes(getComputedStyle(parent).overflowX)) parent=parent.parentElement;
    if(!parent) throw new Error('Expected a horizontally scrollable table');
    parent.scrollLeft=parent.scrollWidth;
  });
  await expect(dateCell).toBeInViewport();
  const dateBounds=await dateCell.boundingBox();
  const actionBounds=await ui.getByRole('cell',{name:'查看',exact:true}).first().boundingBox();
  expect(dateBounds).not.toBeNull();expect(actionBounds).not.toBeNull();
  expect(dateBounds!.x+dateBounds!.width).toBeLessThanOrEqual(actionBounds!.x+1);
  await expect(ui.getByRole('cell',{name:'1837.5',exact:true}).first()).toBeInViewport();
  await info.attach('formal-approval-center-right',{body:await ui.screenshot({fullPage:true}),contentType:'image/png'});

  await actor.context.close();
});

test('formal expiry: inclusive validity date, next-day expiration and no resubmission', async ({page},info) => {
  const {quoteId}=await createFormalQuoteFixture(page,`EXPIRY-${Date.now()}`);
  const before=await readDynamicRecord(page,'qo_quote_common',quoteId);
  const tiers=await queryDynamicRecords(page,'qo_price_tier_common',[{fieldName:'qo_pt_quote_id',operator:'EQ',value:quoteId}]);
  await page.goto(`/p/qo_quote_common/view/${quoteId}#overview`);
  for (const [date,freshness] of [['2030-12-31','fresh'],['2031-01-01','expired']]) {
    await page.getByRole('button',{name:'检查过期与重算',exact:true}).click();
    await page.getByTestId('form-dialog-field-as_of').fill(date);
    await page.getByTestId('form-dialog-field-current_input_fingerprint').fill(String(before.qo_quote_baseline_fingerprint));
    const responsePromise=page.waitForResponse(r=>r.url().includes('qo_quote_common:assess_freshness')&&r.request().method()==='POST');
    await page.getByTestId('form-dialog-submit').click();
    const response=await responsePromise;
    expect(response.status()).toBe(200);expect(String((await response.json()).code)).toBe('0');
    const record=await readDynamicRecord(page,'qo_quote_common',quoteId);
    expect(record.qo_quote_freshness_status).toBe(freshness);
    expect(record.qo_quote_status).toBe(freshness==='fresh'?'priced':'expired');
    expect(record.qo_quote_baseline_fingerprint).toBe(before.qo_quote_baseline_fingerprint);
    expect(record.qo_quote_customer_result).toEqual(before.qo_quote_customer_result);
    await page.reload();
    await expect(page.getByRole('button',{name:'检查过期与重算',exact:true})).toBeVisible();
    if (freshness==='fresh') await expect(page.getByRole('button',{name:'提交正式报价审批',exact:true})).toBeVisible();
  }
  await expect(page.getByText('已过期',{exact:true}).first()).toBeVisible();
  await expect(page.getByRole('button',{name:'提交正式报价审批',exact:true})).toHaveCount(0);
  const expired=await readDynamicRecord(page,'qo_quote_common',quoteId);
  const response=await page.request.post('/api/meta/commands/execute/qo_quote_common:submit_approval',{
    data:{targetRecordId:quoteId,targetRecordPid:quoteId,operationType:'update',expectedVersion:expired.row_version,payload:{}},
  });
  const body=await response.json();
  expect(String(body.code)).not.toBe('0');
  expect(JSON.stringify(body)).toMatch(/expired|过期/);
  await info.attach('expiry-rejection',{body:JSON.stringify({status:response.status(),body}),contentType:'application/json'});
  expect((await readDynamicRecord(page,'qo_quote_common',quoteId)).qo_quote_status).toBe('expired');
  expect(await queryDynamicRecords(page,'qo_price_tier_common',[{fieldName:'qo_pt_quote_id',operator:'EQ',value:quoteId}])).toEqual(tiers);
  await info.attach('formal-expiry',{body:await page.screenshot({fullPage:true}),contentType:'image/png'});
});


test('formal successor: UI version creation and replay preserve the old approved quote', async ({page},info) => {
  const marker=`SUCCESSOR-${Date.now()}`;
  const old=await createFormalQuoteFixture(page,`${marker}-old`);
  await executeCommand(page,'qo_quote_common:submit_approval',{},old.quoteId,'update');
  await executeCommand(page,'qo_quote_common:approve',{approved_by:'Historical approver',decisionNote:'Historical approval'},old.quoteId,'update');
  const before=await readDynamicRecord(page,'qo_quote_common',old.quoteId);
  const oldTiers=await queryDynamicRecords(page,'qo_price_tier_common',[{fieldName:'qo_pt_quote_id',operator:'EQ',value:old.quoteId}]);
  const next=await createFormalQuoteFixture(page,`${marker}-new`,false);
  await page.goto(`/p/qo_cost_scenario_common/view/${next.scenarioId}`);
  let newQuoteId='';
  for (const replay of [false,true]) {
    await page.getByRole('button',{name:'创建正式报价版本',exact:true}).click();
    await page.getByTestId('form-dialog-field-qo_quote_customer').fill(marker);
    await page.getByTestId('form-dialog-field-previous_quote_id').fill(old.quoteId);
    const responsePromise=page.waitForResponse(r=>r.url().includes('qo_quote_common:create_version_from_scenario')&&r.request().method()==='POST');
    await page.getByTestId('form-dialog-submit').click();
    const response=await responsePromise;expect(response.status()).toBe(200);
    const body=await response.json();expect(String(body.code)).toBe('0');
    const result=body.data.data;
    expect(result.replayed).toBe(replay);
    if(replay) expect(String(result.recordId)).toBe(newQuoteId);
    else newQuoteId=String(result.recordId);
    expect(newQuoteId).not.toBe('undefined');
  }
  const after=await readDynamicRecord(page,'qo_quote_common',old.quoteId);
  expect(after).toEqual(before);
  expect(await queryDynamicRecords(page,'qo_price_tier_common',[{fieldName:'qo_pt_quote_id',operator:'EQ',value:old.quoteId}])).toEqual(oldTiers);
  const successor=await readDynamicRecord(page,'qo_quote_common',newQuoteId);
  expect(Number(successor.qo_quote_version_no)).toBe(2);
  expect(successor.qo_quote_previous_version_id).toBe(old.quoteId);
  expect(successor.qo_quote_series_id).toBe(before.qo_quote_series_id);
  expect(successor.qo_quote_status).toBe('priced');
  const versions=await queryDynamicRecords(page,'qo_quote_common',[{fieldName:'qo_quote_series_id',operator:'EQ',value:before.qo_quote_series_id}]);
  expect(versions).toHaveLength(2);
  await page.goto(`/p/qo_quote_common/view/${newQuoteId}#overview`);
  await expect(page.getByRole('button',{name:'提交正式报价审批',exact:true})).toBeVisible();
  await info.attach('formal-successor',{body:JSON.stringify({oldQuoteId:old.quoteId,newQuoteId,series:before.qo_quote_series_id,versions:versions.map(v=>v.qo_quote_version_no)}),contentType:'application/json'});
  await info.attach('formal-successor-browser',{body:await page.screenshot({fullPage:true}),contentType:'image/png'});
});

test('formal invalid predecessor: missing quote is rejected without creating a first version', async ({page},info) => {
  const {scenarioId}=await createFormalQuoteFixture(page,`MISSING-PREV-${Date.now()}`,false);
  await page.goto(`/p/qo_cost_scenario_common/view/${scenarioId}`);
  await expect(page.getByText('已冻结', {exact:true})).toBeVisible();
  await expect(page.getByText('估算报价', {exact:true})).toBeVisible();
  await expect(page.getByText('已满足', {exact:true})).toHaveCount(3);
  await expect(page.getByText('frozen', {exact:true})).toHaveCount(0);
  await page.getByRole('button',{name:'创建正式报价版本',exact:true}).click();
  await page.getByTestId('form-dialog-field-previous_quote_id').fill('01ZZZZZZZZZZZZZZZZZZZZZZZZ');
  const responsePromise=page.waitForResponse(r=>r.url().includes('qo_quote_common:create_version_from_scenario')&&r.request().method()==='POST');
  await page.getByTestId('form-dialog-submit').click();
  const response=await responsePromise;
  const body=await response.json();
  expect(response.status()).toBe(400);
  expect(String(body.code)).not.toBe('0');
  expect(JSON.stringify(body)).toContain('Record not found: 01ZZZZZZZZZZZZZZZZZZZZZZZZ in model: qo_quote_common');
  expect(await queryDynamicRecords(page,'qo_quote_common',[{fieldName:'qo_quote_cost_scenario_id',operator:'EQ',value:scenarioId}])).toHaveLength(0);
  await expect(page.getByText('关联记录不存在或不可访问，请重新选择后再提交。', {exact:true}).first()).toBeVisible();
  await expect(page.locator('main')).not.toContainText('Bad parameter');
  await info.attach('missing-previous-rejection',{body:JSON.stringify({status:response.status(),body}),contentType:'application/json'});
  await info.attach('missing-previous-browser',{body:await page.screenshot({fullPage:true}),contentType:'image/png'});

  // 非法前驱:真实存在但非正式版本的报价同样拒绝,且不误创建首版
  const illegalSeed = await seedQuoteForCorrectedBomUpload(page);
  try {
    await page.reload();
    await expect(page.getByText('已冻结', {exact:true})).toBeVisible();
    await page.getByRole('button',{name:'创建正式报价版本',exact:true}).click();
    await page.getByTestId('form-dialog-field-previous_quote_id').fill(illegalSeed.quoteId);
    const illegalResponsePromise=page.waitForResponse(r=>r.url().includes('qo_quote_common:create_version_from_scenario')&&r.request().method()==='POST');
    await page.getByTestId('form-dialog-submit').click();
    const illegalResponse=await illegalResponsePromise;
    const illegalBody=await illegalResponse.json();
    expect(illegalResponse.status()).toBe(400);
    expect(String(illegalBody.code)).not.toBe('0');
    expect(JSON.stringify(illegalBody)).toContain('previous_quote_id must reference a formal Quote Version');
    expect(await queryDynamicRecords(page,'qo_quote_common',[{fieldName:'qo_quote_cost_scenario_id',operator:'EQ',value:scenarioId}])).toHaveLength(0);
    await info.attach('illegal-previous-rejection',{body:JSON.stringify({status:illegalResponse.status(),body:illegalBody}),contentType:'application/json'});
    await info.attach('illegal-previous-browser',{body:await page.screenshot({fullPage:true}),contentType:'image/png'});
  } finally {
    await cleanupRows(page, illegalSeed);
  }
});
