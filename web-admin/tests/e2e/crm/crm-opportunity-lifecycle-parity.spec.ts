import fs from 'node:fs';
import path from 'node:path';
import type { Page, Response, TestInfo } from '@playwright/test';
import { test, expect } from '../../fixtures';
import {
  ensureSidebarExpanded,
  executeCommandViaApi,
  selectSavedViewByName,
  uniqueId,
  waitForDynamicPageLoad,
} from '../helpers';

const MODEL = 'crm_opportunity_common';
const VIEW_PAGE_KEY = 'crm_opportunity_common_list';
const EVIDENCE_ROOT = path.join(
  process.env.AURA_EVIDENCE_ROOT || process.cwd(),
  't19-opportunity-lifecycle',
);

type OpportunityFixture = {
  accountPid: string;
  readyPid: string;
  readyName: string;
  conflictPid: string;
  conflictName: string;
  lossPid: string;
  lossName: string;
  bulkPids: string[];
  bulkNames: string[];
  viewPid: string;
  viewName: string;
};

function commandBodySucceeded(body: any): boolean {
  return body?.code === 0 || body?.code === '0' || body?.success === true;
}

async function responseBody(response: Response): Promise<any> {
  return response.json().catch(() => ({}));
}

async function record(page: Page, pid: string): Promise<Record<string, any>> {
  const response = await page.request.get(`/api/dynamic/${MODEL}/${pid}`);
  expect(response.ok(), `read opportunity ${pid}`).toBe(true);
  const body = await response.json();
  return body.data ?? body;
}

async function screenshot(page: Page, testInfo: TestInfo, name: string): Promise<string> {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  const output = path.join(EVIDENCE_ROOT, `${name}.png`);
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  return output;
}

async function openOpportunityListFromMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav.locator('a[href="/p/crm_opportunity_common"]').first();
  if (!(await link.isVisible({ timeout: 3_000 }).catch(() => false))) {
    await nav.getByRole('button', { name: /客户关系管理|CRM/i }).first().click();
  }
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(/\/p\/crm_opportunity_common(?:\?.*)?$/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page, 15_000);
}

async function openOpportunityDetail(page: Page, name: string): Promise<void> {
  await openOpportunityListFromMenu(page);
  const row = page.locator('tbody tr').filter({ hasText: name }).first();
  await expect(row).toBeVisible({ timeout: 12_000 });
  await row.getByText(/^(查看|View)$/).first().click();
  await expect(page).toHaveURL(/\/p\/crm_opportunity_common\/view\//, { timeout: 12_000 });
  await expect(page.getByText(name, { exact: true }).first()).toBeVisible({ timeout: 12_000 });
}

async function clickLifecycleAction(
  page: Page,
  buttonName: RegExp,
  command: string,
): Promise<{ response: Response; body: any }> {
  const button = page.getByRole('button', { name: buttonName }).first();
  await expect(button).toBeVisible({ timeout: 10_000 });
  const responsePromise = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'POST' &&
      candidate.url().includes(`/api/meta/commands/execute/${command}`),
    { timeout: 20_000 },
  );
  await button.click();
  const response = await responsePromise;
  return { response, body: await responseBody(response) };
}

async function confirmCommand(page: Page, command: string): Promise<{ response: Response; body: any }> {
  const responsePromise = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'POST' &&
      candidate.url().includes(`/api/meta/commands/execute/${command}`),
    { timeout: 20_000 },
  );
  await page.getByTestId('confirm-ok').click();
  const response = await responsePromise;
  return { response, body: await responseBody(response) };
}

async function seed(page: Page): Promise<OpportunityFixture> {
  const uid = uniqueId('opp_parity');
  const account = await executeCommandViaApi(
    page,
    'crm:create_account',
    {
      crm_acc_name: `机会治理客户 ${uid}`,
      crm_acc_industry: 'manufacturing',
      crm_acc_status: 'active',
    },
    undefined,
    'create',
  );
  const createOpportunity = async (name: string) =>
    executeCommandViaApi(
      page,
      'crm:create_opportunity',
      {
        crm_opp_name: name,
        crm_opp_account_id: account.recordId,
        crm_opp_expected_amount: 380_000,
        crm_opp_expected_close_date: '2026-12-31T18:00:00+08:00',
        crm_opp_probability: 20,
      },
      undefined,
      'create',
    );

  const readyName = `全链路赢单 ${uid}`;
  const conflictName = `审批冲突 ${uid}`;
  const lossName = `丢单复盘 ${uid}`;
  const bulkNames = [`批量丢单甲 ${uid}`, `批量丢单乙 ${uid}`];
  const [ready, conflict, loss, ...bulk] = await Promise.all(
    [readyName, conflictName, lossName, ...bulkNames].map(createOpportunity),
  );
  for (const opportunity of [conflict]) {
    for (const command of [
      'crm:qualify_opportunity',
      'crm:advance_opp_to_proposal',
      'crm:advance_opp_to_negotiation',
    ]) {
      await executeCommandViaApi(page, command, {}, opportunity.recordId, 'state_transition');
    }
  }
  await executeCommandViaApi(
    page,
    'crm:create_quote_summary',
    {
      crm_qs_account_id: account.recordId,
      crm_qs_opportunity_id: conflict.recordId,
      crm_qs_source_quote_type: 'service_proposal',
      crm_qs_source_quote_id: `PENDING-${uid}`,
      crm_qs_status: 'approval',
      crm_qs_quote_amount: 380_000,
      crm_qs_currency: 'CNY',
      crm_qs_approval_status: 'pending',
      crm_qs_won_lost_result: 'open',
    },
    undefined,
    'create',
  );

  const viewName = `机会阶段看板 ${uid}`;
  const viewResponse = await page.request.post('/api/views', {
    data: {
      name: viewName,
      modelCode: MODEL,
      pageKey: VIEW_PAGE_KEY,
      viewType: 'kanban',
      scope: 'personal',
      viewConfig: {
        groupByField: 'crm_opp_stage',
        groupByDictCode: 'crm_opp_stage',
        titleField: 'crm_opp_name',
        idField: 'pid',
      },
    },
  });
  expect(viewResponse.ok(), 'create exact opportunity kanban saved view').toBe(true);
  const viewBody = await viewResponse.json();
  return {
    accountPid: account.recordId,
    readyPid: ready.recordId,
    readyName,
    conflictPid: conflict.recordId,
    conflictName,
    lossPid: loss.recordId,
    lossName,
    bulkPids: bulk.map((item) => item.recordId),
    bulkNames,
    viewPid: viewBody.data?.pid ?? viewBody.pid,
    viewName,
  };
}

test.describe('CRM opportunity lifecycle — Cordys PAR-08/09 parity', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(240_000);

  test('menu → table → kanban → stages → win/conflict/loss/bulk loss @critical @golden', async ({
    page,
  }, testInfo) => {
    const fixture = await seed(page);
    const screenshots: string[] = [];
    const completed: string[] = [];
    const runtimeFailures: string[] = [];
    page.on('response', (response) => {
      if (response.status() >= 500 && !response.url().includes('crm:win_opportunity')) {
        runtimeFailures.push(`${response.status()} ${response.request().method()} ${response.url()}`);
      }
    });

    try {
      await openOpportunityListFromMenu(page);
      await expect(page.locator('tbody tr').filter({ hasText: fixture.readyName })).toHaveCount(1);
      completed.push('official-menu-table-list');

      expect(await selectSavedViewByName(page, fixture.viewName)).toBe(true);
      const board = page.locator('.flex.gap-4.overflow-x-auto').first();
      await expect(board).toBeVisible({ timeout: 12_000 });
      await expect(board.getByText(fixture.readyName, { exact: true })).toBeVisible();
      screenshots.push(await screenshot(page, testInfo, '01-opportunity-stage-kanban'));
      completed.push('saved-view-kanban');

      await openOpportunityDetail(page, fixture.readyName);
      for (const step of [
        { button: /资格确认|Qualify Opportunity/, command: 'crm:qualify_opportunity', stage: 'qualification' },
        { button: /推进到方案阶段|Advance to Proposal/, command: 'crm:advance_opp_to_proposal', stage: 'proposal' },
        { button: /推进到谈判阶段|Advance to Negotiation/, command: 'crm:advance_opp_to_negotiation', stage: 'negotiation' },
      ]) {
        const result = await clickLifecycleAction(page, step.button, step.command);
        expect(result.response.ok(), `${step.command} HTTP`).toBe(true);
        expect(commandBodySucceeded(result.body), `${step.command} body`).toBe(true);
        await expect.poll(async () => (await record(page, fixture.readyPid)).crm_opp_stage).toBe(step.stage);
      }
      screenshots.push(await screenshot(page, testInfo, '02-opportunity-negotiation-ready'));
      completed.push('discovery-to-negotiation');

      await page.getByRole('button', { name: /赢单|Win Opportunity/ }).first().click();
      await expect(page.getByTestId('confirm-dialog')).toBeVisible();
      screenshots.push(await screenshot(page, testInfo, '03-opportunity-win-confirmation'));
      const won = await confirmCommand(page, 'crm:win_opportunity');
      expect(won.response.ok(), 'win command HTTP').toBe(true);
      expect(commandBodySucceeded(won.body), 'win command body').toBe(true);
      await expect.poll(async () => (await record(page, fixture.readyPid)).crm_opp_stage).toBe('closed_won');
      await expect(page.getByRole('button', { name: /赢单|Win Opportunity/ })).toHaveCount(0);
      await expect(page.getByRole('button', { name: /丢单|Lose Opportunity/ })).toHaveCount(0);
      screenshots.push(await screenshot(page, testInfo, '04-opportunity-closed-won'));
      completed.push('approved-or-no-quote-win');

      await openOpportunityDetail(page, fixture.conflictName);
      await page.getByRole('button', { name: /赢单|Win Opportunity/ }).first().click();
      await expect(page.getByTestId('confirm-dialog')).toBeVisible();
      const blocked = await confirmCommand(page, 'crm:win_opportunity');
      expect(commandBodySucceeded(blocked.body), 'pending quote must block win').toBe(false);
      expect(JSON.stringify(blocked.body)).toMatch(/Quote approval conflict|报价审批冲突|审批.*冲突/i);
      await expect(page.getByText(/Quote approval conflict|报价.*审批|审批.*冲突/i).first()).toBeVisible({
        timeout: 10_000,
      });
      expect((await record(page, fixture.conflictPid)).crm_opp_stage).toBe('negotiation');
      screenshots.push(await screenshot(page, testInfo, '05-opportunity-approval-conflict-blocked'));
      completed.push('pending-quote-conflict');

      await openOpportunityDetail(page, fixture.lossName);
      await page.getByRole('button', { name: /丢单|Lose Opportunity/ }).first().click();
      await page.getByTestId('confirm-ok').click();
      const lossDialog = page.getByTestId('form-dialog');
      await expect(lossDialog).toBeVisible();
      await lossDialog.getByTestId('form-dialog-field-crm_opp_lost_reason_code').selectOption('competitor');
      await lossDialog.getByTestId('form-dialog-field-crm_opp_competitor').fill('Cordys 对标竞争对手');
      await lossDialog.getByTestId('form-dialog-field-crm_opp_lost_reason').fill('客户选择已有平台，进入季度复盘。');
      screenshots.push(await screenshot(page, testInfo, '06-opportunity-loss-reason-dialog'));
      const lossResponsePromise = page.waitForResponse((candidate) =>
        candidate.url().includes('/api/meta/commands/execute/crm:lose_opportunity'),
      );
      await lossDialog.getByTestId('form-dialog-submit').click();
      const lossResponse = await lossResponsePromise;
      const lossBody = await responseBody(lossResponse);
      expect(lossResponse.ok()).toBe(true);
      expect(commandBodySucceeded(lossBody)).toBe(true);
      await expect.poll(async () => (await record(page, fixture.lossPid)).crm_opp_stage).toBe('closed_lost');
      const lost = await record(page, fixture.lossPid);
      expect(lost.crm_opp_lost_reason_code).toBe('competitor');
      expect(lost.crm_opp_competitor).toBe('Cordys 对标竞争对手');
      expect(lost.crm_opp_lost_reason).toContain('季度复盘');
      screenshots.push(await screenshot(page, testInfo, '07-opportunity-closed-lost'));
      completed.push('loss-reason-persistence');

      await openOpportunityListFromMenu(page);
      for (const name of fixture.bulkNames) {
        const row = page.locator('tbody tr').filter({ hasText: name }).first();
        await expect(row).toBeVisible();
        await row.locator('input[type="checkbox"]').check();
      }
      await page.getByTestId('bulk-more-actions-btn').click();
      await page.getByTestId('bulk-action-bulk_mark_lost').click();
      await page.getByTestId('confirm-ok').click();
      const bulkDialog = page.getByTestId('form-dialog');
      await expect(bulkDialog).toBeVisible();
      await bulkDialog.getByTestId('form-dialog-field-crm_opp_lost_reason_code').selectOption('no_budget');
      await bulkDialog.getByTestId('form-dialog-field-crm_opp_lost_reason').fill('统一预算冻结复盘。');
      const bulkResponses: Response[] = [];
      const captureBulkResponse = (candidate: Response) => {
        if (candidate.url().includes('/api/meta/commands/execute/crm:lose_opportunity')) {
          bulkResponses.push(candidate);
        }
      };
      page.on('response', captureBulkResponse);
      await bulkDialog.getByTestId('form-dialog-submit').click();
      await expect.poll(() => bulkResponses.length, { timeout: 20_000 }).toBe(fixture.bulkPids.length);
      page.off('response', captureBulkResponse);
      expect(bulkResponses.every((response) => response.ok())).toBe(true);
      for (const pid of fixture.bulkPids) {
        const bulkLost = await record(page, pid);
        expect(bulkLost.crm_opp_stage).toBe('closed_lost');
        expect(bulkLost.crm_opp_lost_reason_code).toBe('no_budget');
      }
      screenshots.push(await screenshot(page, testInfo, '08-opportunity-bulk-loss-complete'));
      completed.push('bulk-loss-input-contract');

      const receipt = {
        verdict: 'pass',
        runtime: process.env.AURA_RUNTIME_NAME,
        dataMigration: 'out-of-scope-development-stage',
        fixtureMode: 'self-seeded-command-pipeline',
        completed,
        screenshots,
        runtimeFailures,
        records: {
          won: fixture.readyPid,
          conflict: fixture.conflictPid,
          lost: fixture.lossPid,
          bulkLost: fixture.bulkPids,
        },
      };
      expect(runtimeFailures, 'unexpected 5xx runtime requests').toEqual([]);
      fs.writeFileSync(
        path.join(EVIDENCE_ROOT, 't19-opportunity-lifecycle-final.json'),
        `${JSON.stringify(receipt, null, 2)}\n`,
      );
      await testInfo.attach('t19-opportunity-lifecycle-final.json', {
        body: Buffer.from(JSON.stringify(receipt, null, 2)),
        contentType: 'application/json',
      });
    } finally {
      if (fixture.viewPid) {
        await page.request.delete(`/api/views/${fixture.viewPid}`).catch(() => null);
      }
    }
  });
});
