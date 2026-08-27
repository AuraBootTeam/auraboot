import fs from 'node:fs';
import path from 'node:path';
import { expect, test, type Page } from '../../fixtures';
import { executeCommandViaApi, waitForDynamicPageLoad } from '../helpers';

/**
 * PARR1 side-by-side capture: rebuilds the Cordys PARR1 fixture set under the
 * same business names on a fresh Aura runtime and captures desktop (1440x900)
 * and compact (390x844) screenshots for line-by-line pairing with the Cordys
 * material in .workspace/evidence/crm-cordys-parr1-batch-20260827/.
 * Fixture data mirrors the Cordys capture; no historical data migration.
 */

const ACCOUNT_A = 'PARR1 智造客户A';
const ACCOUNT_B = 'PARR1 智造客户B';
const CONTACT_NAME = 'PARR1 联系人甲';
const LEAD_A = 'PARR1 线索甲公司';
const LEAD_B = 'PARR1 线索乙公司';
const OPP_NAME = 'PARR1 智造升级项目';
const DESKTOP = { width: 1440, height: 900 };
const COMPACT = { width: 390, height: 844 };

const EVIDENCE_ROOT = path.join(
  process.env.AURA_EVIDENCE_ROOT || path.resolve(process.cwd(), '..'),
  'parr1-side-by-side',
  'aura',
);

async function capture(page: Page, name: string): Promise<void> {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  const suffix = (page.viewportSize()?.width ?? 1440) < 600 ? 'compact' : 'desktop';
  await page.screenshot({ path: path.join(EVIDENCE_ROOT, `${name}-${suffix}.png`), fullPage: false });
}

async function openList(page: Page, model: string): Promise<void> {
  await page.goto(`/p/${model}`, { waitUntil: 'domcontentloaded' });
  await waitForDynamicPageLoad(page, 15_000);
}

async function openDetail(page: Page, model: string, pid: string): Promise<void> {
  await page.goto(`/p/${model}/view/${pid}`, { waitUntil: 'domcontentloaded' });
  await waitForDynamicPageLoad(page, 15_000);
}

async function searchInList(page: Page, model: string, keyword: string): Promise<void> {
  const input = page
    .locator(
      '[data-testid="list-search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
    )
    .first();
  await expect(input).toBeVisible({ timeout: 12_000 });
  const response = page.waitForResponse(
    (candidate) => candidate.url().includes(`/api/dynamic/${model}/list`) && candidate.ok(),
    { timeout: 15_000 },
  );
  await input.fill(keyword);
  await input.press('Enter');
  await response;
}

test('capture PARR1 side-by-side material', async ({ page }) => {
  test.setTimeout(300_000);

  // ---- seed fixtures mirroring the Cordys PARR1 set (API-first, UI-verified) ----
  const accountA = await executeCommandViaApi(page, 'crm:create_account', {
    crm_acc_name: ACCOUNT_A,
    crm_acc_industry: 'tech',
    crm_acc_rating: 'A',
    crm_acc_remark: '苏州市工业园区智造大道 88 号',
  }, undefined, 'create');
  expect(accountA.recordId).toBeTruthy();

  const accountB = await executeCommandViaApi(page, 'crm:create_account', {
    crm_acc_name: ACCOUNT_B,
    crm_acc_industry: 'tech',
    crm_acc_rating: 'B',
  }, undefined, 'create');
  expect(accountB.recordId).toBeTruthy();

  const contact = await executeCommandViaApi(page, 'crm:create_contact', {
    crm_ct_account_id: accountA.recordId,
    crm_ct_name: CONTACT_NAME,
    crm_ct_mobile: '13800003041',
    crm_ct_is_primary: true,
  }, undefined, 'create');
  expect(contact.recordId).toBeTruthy();

  const leadA = await executeCommandViaApi(page, 'crm:create_lead', {
    crm_lead_company: LEAD_A,
    crm_lead_contact_name: 'PARR1 甲联系人',
    crm_lead_contact_phone: '13800003031',
    crm_lead_source: 'website',
    crm_lead_requirement: 'PARR1 需求：智造升级产线改造',
  }, undefined, 'create');
  expect(leadA.recordId).toBeTruthy();

  await executeCommandViaApi(page, 'crm:create_lead', {
    crm_lead_company: LEAD_B,
    crm_lead_contact_name: 'PARR1 乙联系人',
    crm_lead_contact_phone: '13800003032',
    crm_lead_source: 'website',
    crm_lead_requirement: 'PARR1 需求：仓储自动化',
  }, undefined, 'create');

  const opp = await executeCommandViaApi(page, 'crm:create_opportunity', {
    crm_opp_name: OPP_NAME,
    crm_opp_account_id: accountA.recordId,
    crm_opp_expected_amount: 280000,
  }, undefined, 'create');
  expect(opp.recordId).toBeTruthy();

  const record = await executeCommandViaApi(page, 'crm:create_activity', {
    sourceRecordPid: opp.recordId,
    crm_act_type: 'visit',
    crm_act_subject: 'PARR1 首次跟进',
    crm_act_content: '客户确认智造升级需求，预算已批。',
    crm_act_source: 'manual',
  }, undefined, 'create');
  expect(record.recordId).toBeTruthy();

  const plan = await executeCommandViaApi(page, 'crm:create_activity', {
    crm_act_type: 'task',
    crm_act_subject: 'PARR1 方案评审计划',
    crm_act_content: '组织方案评审并确认技术参数。',
    crm_act_source: 'manual',
    crm_act_status: 'open',
    crm_act_priority: 'high',
    crm_act_related_model: 'crm_opportunity_common',
    crm_act_related_id: opp.recordId,
  }, undefined, 'create');
  expect(plan.recordId).toBeTruthy();

  // ---- account module (PAR-05) ----
  await page.setViewportSize(DESKTOP);
  await openList(page, 'crm_account_common');
  await capture(page, 'a1-account-list');
  await page.setViewportSize(COMPACT);
  await capture(page, 'a1-account-list');

  await page.setViewportSize(DESKTOP);
  await page.goto(`/p/crm_account_common/new`, { waitUntil: 'domcontentloaded' });
  await waitForDynamicPageLoad(page, 12_000);
  await capture(page, 'a1-account-create-dialog');

  await openDetail(page, 'crm_account_common', accountA.recordId);
  await capture(page, 'a1-account-detail');
  await page.setViewportSize(COMPACT);
  await capture(page, 'a1-account-detail');

  // ---- lead module (PAR-03) ----
  await page.setViewportSize(DESKTOP);
  await openList(page, 'crm_lead_common');
  await capture(page, 'a2-lead-list');
  await page.setViewportSize(COMPACT);
  await capture(page, 'a2-lead-list');

  await page.setViewportSize(DESKTOP);
  await openDetail(page, 'crm_lead_common', leadA.recordId);
  await capture(page, 'a2-lead-detail');
  await page.setViewportSize(COMPACT);
  await capture(page, 'a2-lead-detail');

  // ---- opportunity module (PAR-08/19 surface) ----
  await page.setViewportSize(DESKTOP);
  await openList(page, 'crm_opportunity_common');
  await capture(page, 'a3-opp-list');
  await page.setViewportSize(COMPACT);
  await capture(page, 'a3-opp-list');

  await page.setViewportSize(DESKTOP);
  await openDetail(page, 'crm_opportunity_common', opp.recordId);
  await capture(page, 'a3-opp-detail');
  await page.setViewportSize(COMPACT);
  await capture(page, 'a3-opp-detail');

  // ---- follow-up record + plan detail (PAR-10/11) ----
  await page.setViewportSize(DESKTOP);
  await openDetail(page, 'crm_activity_common', record.recordId);
  await capture(page, 'a3-opp-record');
  await page.setViewportSize(COMPACT);
  await capture(page, 'a3-opp-record');

  await page.setViewportSize(DESKTOP);
  await openDetail(page, 'crm_activity_common', plan.recordId);
  await capture(page, 'a3-opp-plan');
  await page.setViewportSize(COMPACT);
  await capture(page, 'a3-opp-plan');

  // ---- manifest of produced artifacts ----
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  fs.writeFileSync(
    path.join(EVIDENCE_ROOT, 'capture-manifest.json'),
    JSON.stringify(
      {
        capturedAt: new Date().toISOString(),
        fixtures: {
          accountA: accountA.recordId,
          accountB: accountB.recordId,
          contact: contact.recordId,
          leadA: leadA.recordId,
          opportunity: opp.recordId,
          record: record.recordId,
          plan: plan.recordId,
        },
        note: 'Names mirror crm-cordys-parr1-batch-20260827; compact=390x844, desktop=1440x900.',
      },
      null,
      2,
    ),
  );
});
