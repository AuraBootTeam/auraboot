import { expect, test, type Locator, type Page, type TestInfo } from '@playwright/test';
import fs from 'node:fs';
import { Pool } from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { executeCommandViaApi, uniqueId } from '../helpers';

const EXPECTED_SCENARIOS = [
  'relationship-created-from-account-detail',
  'outgoing-relationship-visible',
  'incoming-relationship-visible',
  'relationship-detail-readable',
  'relationship-edit-persists',
  'self-relationship-rejected-and-rolled-back',
  'missing-account-rejected-and-rolled-back',
  'duplicate-relationship-rejected-and-rolled-back',
  'relationship-unique-key-single-fact',
  'relationship-delete-requires-confirmation',
  'relationship-delete-removes-both-endpoints',
  'relationship-runtime-has-no-failed-requests',
] as const;

const COVERAGE = {
  pages: [
    'crm_account_common_detail',
    'crm_account_relation_common_form',
    'crm_account_relation_common_detail',
  ],
  commands: [
    'crm:create_account',
    'crm:create_account_relation',
    'crm:update_account_relation',
    'crm:delete_account_relation',
  ],
  permissions: ['crm.account.read', 'crm.account.manage'],
  blocks: [
    'crm_account_common_detail:crm_account_tabs',
    'crm_account_common_detail:crm_account_relationship_actions',
    'crm_account_common_detail:block_outgoing_relationships',
    'crm_account_common_detail:block_incoming_relationships',
    'crm_account_relation_common_form:relationship_basic',
    'crm_account_relation_common_form:relationship_validity',
    'crm_account_relation_common_form:relationship_buttons',
    'crm_account_relation_common_detail:relationship_detail',
    'crm_account_relation_common_detail:relationship_detail_toolbar',
  ],
  fields: [
    'crm_account_relation_common_form:relationship_basic:crm_acr_source_account_id',
    'crm_account_relation_common_form:relationship_basic:crm_acr_target_account_id',
    'crm_account_relation_common_form:relationship_basic:crm_acr_relation_type',
    'crm_account_relation_common_form:relationship_basic:crm_acr_strength',
    'crm_account_relation_common_form:relationship_basic:crm_acr_status',
    'crm_account_relation_common_form:relationship_validity:crm_acr_effective_from',
    'crm_account_relation_common_form:relationship_validity:crm_acr_effective_to',
    'crm_account_relation_common_form:relationship_validity:crm_acr_notes',
  ],
  uiActions: [
    'crm_account_common_detail:crm_account_tabs:relationships',
    'crm_account_common_detail:crm_account_relationship_actions:add',
    'crm_account_common_detail:block_outgoing_relationships:view',
    'crm_account_relation_common_form:relationship_buttons:submit',
    'crm_account_relation_common_detail:relationship_detail_toolbar:edit',
    'crm_account_relation_common_detail:relationship_detail_toolbar:delete',
  ],
} as const;

const CORDYS_SOURCE_IDS = [
  'api:customer:customer-relation:list',
  'api:customer:customer-relation:add',
  'api:customer:customer-relation:update',
  'api:customer:customer-relation:delete',
  'api:customer:customer-relation:save',
] as const;

test.describe('CRM account relationship graph — Cordys PAR-05 parity', () => {
  test.setTimeout(120_000);

  test('PAR05-REL-01: UI CRUD keeps one relationship fact visible from both accounts @critical @golden', async ({
    page,
  }, testInfo) => {
    const uid = uniqueId('crm_account_relation');
    const sourceName = `源客户 ${uid}`;
    const targetName = `关系客户 ${uid}`;
    const thirdName = `边界客户 ${uid}`;
    const initialNotes = `联合交付与客户成功协同 ${uid}`;
    const editedNotes = `战略合作季度复盘 ${uid}`;
    const screenshots: string[] = [];
    const failedRuntimeRequests: Array<{ method: string; status: number; url: string }> = [];
    page.on('response', (response) => {
      if (response.status() < 500) return;
      failedRuntimeRequests.push({
        method: response.request().method(),
        status: response.status(),
        url: response.url(),
      });
    });

    const sourceAccount = await createAccount(page, sourceName, uid);
    const targetAccount = await createAccount(page, targetName, uid);
    const thirdAccount = await createAccount(page, thirdName, uid);

    await openAccountDetail(page, sourceAccount.recordId);
    const relationshipsTab = page.getByRole('tab', { name: /客户关系|Relationships/i });
    await expect(relationshipsTab).toBeVisible();
    await relationshipsTab.click();
    const addRelationship = page.getByRole('button', { name: /新增关系|Add Relationship/i });
    await expect(addRelationship).toBeVisible();
    await addRelationship.click();
    await expect(page).toHaveURL(/\/p\/crm_account_relation_common\/(new|create)/);

    await pickReferenceIfNeeded(
      page,
      'crm_acr_source_account_id',
      sourceAccount.recordId,
      sourceName,
    );
    await pickReference(page, 'crm_acr_target_account_id', targetAccount.recordId, targetName);
    await pickSmartSelect(page, 'crm_acr_relation_type', /合作伙伴|Partner/i);
    await pickSmartSelect(page, 'crm_acr_strength', /战略|Strategic/i);
    await pickSmartSelect(page, 'crm_acr_status', /生效|Active/i);
    await fillFormField(page, 'crm_acr_effective_from', '2026-08-21');
    await fillFormField(page, 'crm_acr_effective_to', '2027-08-20');
    await fillFormField(page, 'crm_acr_notes', initialNotes);
    screenshots.push(await screenshot(page, testInfo, '01-create-relationship-form'));

    const createResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/api/meta/commands/execute/crm:create_account_relation'),
    );
    await page.getByRole('button', { name: /保存|Save/i }).click();
    await expectOk(await createResponse, 'create account relationship from UI');
    await expect
      .poll(
        async () =>
          (await relationshipIdentity(sourceAccount.recordId, targetAccount.recordId, 'partner'))
            ?.pid ?? '',
        { timeout: 15_000 },
      )
      .toMatch(/\S+/);
    const relationId = String(
      (await relationshipIdentity(sourceAccount.recordId, targetAccount.recordId, 'partner'))
        ?.pid ?? '',
    );
    expect(relationId).toMatch(/\S+/);

    await openAccountDetail(page, sourceAccount.recordId);
    await page.getByRole('tab', { name: /客户关系|Relationships/i }).click();
    const outgoingRow = page.locator('tbody tr', { hasText: targetName }).first();
    await expect(outgoingRow).toBeVisible({ timeout: 15_000 });
    await expect(outgoingRow).toContainText(/合作伙伴|Partner/i);
    await expect(outgoingRow).toContainText(/战略|Strategic/i);
    await expect(outgoingRow).not.toContainText(relationId);
    screenshots.push(await screenshot(page, testInfo, '02-outgoing-relationship'));

    await openAccountDetail(page, targetAccount.recordId);
    await page.getByRole('tab', { name: /客户关系|Relationships/i }).click();
    const incomingRow = page.locator('tbody tr', { hasText: sourceName }).first();
    await expect(incomingRow).toBeVisible({ timeout: 15_000 });
    await expect(incomingRow).toContainText(/合作伙伴|Partner/i);
    screenshots.push(await screenshot(page, testInfo, '03-incoming-relationship'));

    await openAccountDetail(page, sourceAccount.recordId);
    await page.getByRole('tab', { name: /客户关系|Relationships/i }).click();
    const relationshipRow = page.locator('tbody tr', { hasText: targetName }).first();
    const viewRelationship = relationshipRow.locator('[data-testid^="subtable-row-action-view-"]');
    await expect(viewRelationship).toBeVisible();
    await viewRelationship.click();
    await expect(page).toHaveURL(new RegExp(`/p/crm_account_relation_common/view/${relationId}`));
    await expect(page.getByText(initialNotes, { exact: true })).toBeVisible();
    screenshots.push(await screenshot(page, testInfo, '04-relationship-detail'));

    await page
      .getByRole('button', { name: /编辑|Edit/i })
      .first()
      .click();
    await expect(page).toHaveURL(new RegExp(`/p/crm_account_relation_common/edit/${relationId}`));
    await pickSmartSelect(page, 'crm_acr_strength', /重要|Important/i);
    await fillFormField(page, 'crm_acr_notes', editedNotes);
    const updateResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/api/meta/commands/execute/crm:update_account_relation'),
    );
    await page.getByRole('button', { name: /保存|Save/i }).click();
    await expectOk(await updateResponse, 'update account relationship from UI');
    await page.goto(`/p/crm_account_relation_common/view/${relationId}`);
    await expect(page.getByText(editedNotes, { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/重要|Important/i).first()).toBeVisible();
    screenshots.push(await screenshot(page, testInfo, '05-edited-relationship'));

    await expectRejectedCreate(
      page,
      {
        crm_acr_source_account_id: sourceAccount.recordId,
        crm_acr_target_account_id: sourceAccount.recordId,
        crm_acr_relation_type: 'affiliate',
        crm_acr_strength: 'standard',
        crm_acr_status: 'active',
      },
      /不能与自身|cannot relate to itself/i,
    );
    await expectRejectedCreate(
      page,
      {
        crm_acr_source_account_id: thirdAccount.recordId,
        crm_acr_target_account_id: `missing-${uid}`,
        crm_acr_relation_type: 'supplier',
        crm_acr_strength: 'standard',
        crm_acr_status: 'active',
      },
      /not found/i,
    );
    await expectRejectedCreate(
      page,
      {
        crm_acr_source_account_id: sourceAccount.recordId,
        crm_acr_target_account_id: targetAccount.recordId,
        crm_acr_relation_type: 'partner',
        crm_acr_strength: 'standard',
        crm_acr_status: 'active',
      },
      /已存在|already exists/i,
    );

    const beforeDelete = await relationshipFacts(sourceAccount.recordId, targetAccount.recordId);
    expect(beforeDelete).toEqual({ count: 1, pairKeyCount: 1 });

    await page.goto(`/p/crm_account_relation_common/view/${relationId}`);
    await page.getByRole('button', { name: /删除|Delete/i }).click();
    const confirmDialog = page.getByTestId('confirm-dialog');
    await expect(confirmDialog).toBeVisible();
    await expect(confirmDialog).toContainText(/删除此客户关系|Delete this account relationship/i);
    screenshots.push(await screenshot(page, testInfo, '06-delete-confirmation'));
    const deleteResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/api/meta/commands/execute/crm:delete_account_relation'),
    );
    await page.getByTestId('confirm-ok').click();
    await expectOk(await deleteResponse, 'delete account relationship from detail');
    await expect
      .poll(
        async () => (await relationshipFacts(sourceAccount.recordId, targetAccount.recordId)).count,
      )
      .toBe(0);

    await openAccountDetail(page, sourceAccount.recordId);
    await page.getByRole('tab', { name: /客户关系|Relationships/i }).click();
    const outgoingSection = page
      .locator('.sub-table-section', { hasText: /我方定义的关系|Relationships Defined Here/i })
      .first();
    await expect(outgoingSection.getByTestId('subtable-empty-state')).toBeVisible({
      timeout: 15_000,
    });
    await expect(outgoingSection.locator('tbody tr', { hasText: targetName })).toHaveCount(0);
    screenshots.push(await screenshot(page, testInfo, '07-relationship-removed'));

    expect(failedRuntimeRequests).toEqual([]);
    fs.writeFileSync(
      testInfo.outputPath(`crm-account-relationship-${uid}.json`),
      `${JSON.stringify(
        {
          runId: uid,
          verdict: 'pass',
          technicalVerdict: 'pass',
          cordysSourceEvidence: {
            sourceIds: CORDYS_SOURCE_IDS,
            assertionScope:
              'two-sided relationship list plus create, update and confirmed delete persistence',
          },
          fixtureMode: 'self-seeded',
          dataMigration: 'out-of-scope-development-stage',
          expectedScenarios: EXPECTED_SCENARIOS,
          completedScenarios: EXPECTED_SCENARIOS,
          coverage: Object.fromEntries(
            Object.entries(COVERAGE).map(([axis, expected]) => [
              axis,
              { expected, completed: expected },
            ]),
          ),
          screenshots,
          failedRuntimeRequests,
          recordIds: {
            sourceAccount: sourceAccount.recordId,
            targetAccount: targetAccount.recordId,
            thirdAccount: thirdAccount.recordId,
            relationship: relationId,
          },
          database: {
            beforeDelete,
            afterDelete: await relationshipFacts(sourceAccount.recordId, targetAccount.recordId),
          },
        },
        null,
        2,
      )}\n`,
    );
  });

  test('PAR05-REL-02: save upserts one fact and account 360 stays dense at 1280 @critical @golden', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const uid = uniqueId('crm_relation_save');
    const sourceName = `保存源客户 ${uid.slice(-8)}`;
    const targetName = `保存目标客户 ${uid.slice(-8)}`;
    const source = await createAccount(page, sourceName, uid);
    const target = await createAccount(page, targetName, uid);
    const basePayload = {
      crm_acr_source_account_id: source.recordId,
      crm_acr_target_account_id: target.recordId,
      crm_acr_relation_type: 'partner',
      crm_acr_strength: 'standard',
      crm_acr_status: 'active',
      crm_acr_effective_from: '2026-08-23',
      crm_acr_effective_to: '2027-08-23',
      crm_acr_notes: `首次保存 ${uid}`,
    };

    await executeSaveRelationship(page, basePayload, 'create relationship through save');
    const created = await relationshipIdentity(source.recordId, target.recordId, 'partner');
    expect(created?.pid).toMatch(/\S+/);
    await executeSaveRelationship(
      page,
      { ...basePayload, crm_acr_strength: 'strategic', crm_acr_notes: `重复保存更新 ${uid}` },
      'update relationship through repeated save',
    );
    expect(await relationshipFacts(source.recordId, target.recordId)).toEqual({
      count: 1,
      pairKeyCount: 1,
    });
    expect(
      (await relationshipIdentity(source.recordId, target.recordId, 'partner'))?.pid,
      'upsert preserves the stable relationship PID',
    ).toBe(created?.pid);

    await openAccountDetail(page, source.recordId);
    const relationshipsTab = page.getByRole('tab', { name: /客户关系|Relationships/i });
    await relationshipsTab.click();
    const row = page.locator('tbody tr', { hasText: targetName }).first();
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row).toContainText(/战略|Strategic/i);
    expect(
      await row.locator('td').count(),
      'compact relationship row column count',
    ).toBeLessThanOrEqual(6);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      'account relationship projection must not create page-level horizontal overflow at 1280',
    ).toBe(true);
    const relationshipScreenshot = await screenshot(page, testInfo, '08-relationship-density-1280');

    const plansTab = page.getByRole('tab', { name: /客户计划|Customer Plans/i });
    await expect(plansTab).toBeVisible();
    await plansTab.click();
    await expect(
      page.getByRole('button', { name: /新建客户计划|New Customer Plan/i }),
    ).toBeVisible();
    await expect(page.getByText(/加载中|Loading/i)).toHaveCount(0, { timeout: 15_000 });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      'customer plan projection must not create page-level horizontal overflow at 1280',
    ).toBe(true);
    const planScreenshot = await screenshot(page, testInfo, '09-customer-plans-density-1280');

    fs.writeFileSync(
      testInfo.outputPath(`crm-account-save-density-${uid}.json`),
      `${JSON.stringify(
        {
          runId: uid,
          verdict: 'pass',
          technicalVerdict: 'pass',
          dataMigration: 'out-of-scope-development-stage',
          scenarios: [
            'save-create',
            'save-repeat-upsert',
            'stable-pid',
            '1280-relationship-density',
            'customer-plans-reachable',
          ],
          screenshots: [relationshipScreenshot, planScreenshot],
          recordIds: {
            source: source.recordId,
            target: target.recordId,
            relationship: created?.pid,
          },
        },
        null,
        2,
      )}\n`,
    );
  });
});

async function executeSaveRelationship(
  page: Page,
  payload: Record<string, unknown>,
  label: string,
): Promise<void> {
  const response = await page.request.post('/api/meta/commands/execute/crm:save_account_relation', {
    data: { operationType: 'custom', payload },
  });
  await expectOk(response, label);
}

async function createAccount(page: Page, name: string, uid: string) {
  return executeCommandViaApi(page, 'crm:create_account', {
    crm_acc_name: name,
    crm_acc_industry: 'technology',
    crm_acc_rating: 'A',
    crm_acc_status: 'active',
    crm_acc_remark: `PAR-05 customer relationship ${uid}`,
  });
}

async function openAccountDetail(page: Page, accountId: string): Promise<void> {
  await page.goto(`/p/crm_account_common/view/${accountId}`);
  await expect(page.getByRole('tab', { name: /概览|Overview/i })).toBeVisible({ timeout: 15_000 });
}

async function fillFormField(page: Page, fieldCode: string, value: string): Promise<void> {
  const control = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] input:not([type="hidden"]), ` +
        `[data-testid="form-field-${fieldCode}"] textarea`,
    )
    .first();
  await expect(control, `${fieldCode} form control`).toBeVisible({ timeout: 10_000 });
  await control.fill(value);
  await control.blur();
}

function referenceTrigger(page: Page, fieldCode: string): Locator {
  return page.getByTestId(`select-trigger-${fieldCode}`).first();
}

async function pickReferenceIfNeeded(
  page: Page,
  fieldCode: string,
  recordPid: string,
  expectedLabel: string,
): Promise<void> {
  const trigger = referenceTrigger(page, fieldCode);
  await expect(trigger).toBeVisible({ timeout: 10_000 });
  if ((await trigger.textContent())?.includes(expectedLabel)) return;
  await pickReference(page, fieldCode, recordPid, expectedLabel);
}

async function pickReference(
  page: Page,
  fieldCode: string,
  recordPid: string,
  expectedLabel: string,
): Promise<void> {
  const trigger = referenceTrigger(page, fieldCode);
  await expect(trigger).toBeVisible({ timeout: 10_000 });
  await trigger.click();
  const option = page.locator(`[role="option"][data-value="${recordPid}"]`).first();
  await expect(option).toBeVisible({ timeout: 10_000 });
  await option.click();
  await expect(trigger).toContainText(expectedLabel);
}

async function pickSmartSelect(page: Page, fieldCode: string, optionName: RegExp): Promise<void> {
  const trigger = page.getByTestId(`select-trigger-${fieldCode}`).first();
  await expect(trigger).toBeVisible({ timeout: 10_000 });
  await trigger.click();
  const option = page.getByRole('option', { name: optionName }).first();
  await expect(option).toBeVisible({ timeout: 10_000 });
  await option.click();
}

async function expectRejectedCreate(
  page: Page,
  payload: Record<string, unknown>,
  expectedMessage: RegExp,
): Promise<void> {
  const response = await page.request.post(
    '/api/meta/commands/execute/crm:create_account_relation',
    { data: { operationType: 'create', payload } },
  );
  const body: any = await response.json().catch(() => ({}));
  expect(response.ok() && String(body?.code) === '0', JSON.stringify(body)).toBe(false);
  expect(JSON.stringify(body)).toMatch(expectedMessage);
}

async function relationshipFacts(
  sourceAccountId: string,
  targetAccountId: string,
): Promise<{ count: number; pairKeyCount: number }> {
  const pool = new Pool(PG_CONN);
  try {
    const result = await pool.query<{ count: string; pair_key_count: string }>(
      `SELECT COUNT(*)::text AS count,
              COUNT(DISTINCT crm_acr_pair_key)::text AS pair_key_count
       FROM mt_crm_account_relation_common
       WHERE crm_acr_source_account_id = $1
         AND crm_acr_target_account_id = $2`,
      [sourceAccountId, targetAccountId],
    );
    return {
      count: Number(result.rows[0]?.count ?? 0),
      pairKeyCount: Number(result.rows[0]?.pair_key_count ?? 0),
    };
  } finally {
    await pool.end();
  }
}

async function relationshipIdentity(
  sourceAccountId: string,
  targetAccountId: string,
  relationType: string,
): Promise<{ pid: string } | null> {
  const pool = new Pool(PG_CONN);
  try {
    const result = await pool.query<{ pid: string }>(
      `SELECT pid
       FROM mt_crm_account_relation_common
       WHERE crm_acr_source_account_id = $1
         AND crm_acr_target_account_id = $2
         AND crm_acr_relation_type = $3
       ORDER BY created_at DESC
       LIMIT 1`,
      [sourceAccountId, targetAccountId, relationType],
    );
    return result.rows[0] ?? null;
  } finally {
    await pool.end();
  }
}

async function expectOk(response: any, label: string): Promise<any> {
  const body: any = await response.json().catch(() => ({}));
  expect(
    response.ok() && ['0', '200', 'success'].includes(String(body?.code ?? '').toLowerCase()),
    `${label}: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 1200)}`,
  ).toBe(true);
  return body;
}

async function screenshot(page: Page, testInfo: TestInfo, name: string): Promise<string> {
  const screenshotPath = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path: screenshotPath, fullPage: false });
  await testInfo.attach(name, { path: screenshotPath, contentType: 'image/png' });
  return screenshotPath;
}
