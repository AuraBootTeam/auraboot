/**
 * Picker Fill Round-Trip — UI Fill + Persist (P2.1)
 *
 * Drives the `showcase_all_fields` form with real DOM picker interactions
 * (no API seeding of picker fields) and verifies every value lands in the
 * dynamic record table.
 *
 * Pickers covered:
 *   - DatePicker         (sc_start_date, sc_end_date)  — native date input
 *   - TreeSelect         (sc_tree_node)                 — tree dict, single mode
 *   - OrganizationSelect (sc_department)                — hierarchical mock tree
 *   - MemberPicker       (sc_team_members, open/search/select only — see below)
 *   - CascadeSelect      (sc_cascade_category, interaction only — see below)
 *
 * GAP-258 (fixed 2026-04-17): ControlledFieldRenderer now adapts picker output
 * to backend field shape at the edge:
 *   - cascadeselect emits the deepest (leaf) value as a single string.
 *   - memberpicker with multiple:true serializes its string[] as a JSON string
 *     (e.g. '["pid-abc","pid-def"]'); callers parse back with JSON.parse.
 * Both fields are asserted end-to-end in PIK-1.
 *
 * Coverage dimensions (per docs/standards/testing-e2e-web.md):
 *   D1  Sidebar navigation (no shortcut via page.goto to /new)
 *   D4  Create — fill pickers via UI + submit
 *   D5  Form field types — each picker renders with its own testid contract
 *   D6  API verification — persisted values match what UI selected
 *   D8  Edit — reopen form, pickers show prior value, change, persist again
 *   D11 Cleanup — delete record
 *   D14 Toast — command response code=0 verified inline
 *
 * Testid contract (single source of truth lives with these specs):
 *   cascade-trigger-{name}-{level}        cascade-option-{name}-{level}-{value}
 *   tree-select-trigger                   tree-select-option-{value}
 *   member-picker-add                     member-picker-option-{id}
 *   organization-select-trigger-{name}    organization-select-option-{orgId}
 *   date-picker-input-{name}              form-btn-submit / form-btn-cancel
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  dateOffsetStr,
  executeCommandViaApi,
  queryFilteredList,
  waitForFormReady,
} from '../helpers/index';

test.describe.configure({ mode: 'serial' });

const UID = uniqueId('PIK');
const RECORD_NAME = `E2E Picker ${UID}`;
const RECORD_NAME_EDITED = `E2E Picker Edited ${UID}`;

let orgTech = '';
let orgEditTarget = '';

// sc_cascade_category_dict (tree) — 3 levels of items verified in dicts.json.
// Cascade is UI-only (see header re: backend contract gap).
const CASCADE_L0 = 'electronics';
const CASCADE_L1 = 'electronics_phone';
const CASCADE_L2 = 'electronics_phone_smart';

// sc_tree_dept_dict values
const TREE_VALUE = 'tech_frontend';
const TREE_VALUE_EDIT = 'tech_backend';

// sc_start_date / sc_end_date
const START_DATE = dateOffsetStr(3);
const END_DATE = dateOffsetStr(10);
const END_DATE_EDIT = dateOffsetStr(14);

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------

async function navigateToShowcaseList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 10_000 });

  // Expand root menu "能力展示 / Showcase"
  const rootBtn = nav.getByRole('button', { name: /Showcase|展示/i }).first();
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());

  // Click leaf "全字段类型"
  const leafLink = nav.locator('a[href*="showcase_all_fields"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  const listResp = page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/showcase_all_fields') &&
      r.url().includes('list') &&
      r.status() === 200,
    { timeout: 20_000 },
  );
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResp;

  await expect(
    page.locator('table, [data-testid="dynamic-list"]').first(),
  ).toBeVisible({ timeout: 15_000 });
}

async function openCreateForm(page: Page): Promise<void> {
  await navigateToShowcaseList(page);
  const createBtn = page
    .locator('[data-testid="toolbar-btn-create"]')
    .or(page.getByRole('button', { name: /新建|Create/i }))
    .first();
  await createBtn.waitFor({ state: 'visible', timeout: 8_000 });
  await createBtn.evaluate((el: HTMLElement) => el.click());
  await page
    .waitForURL(/showcase.all.fields.*form|\/new|\/create/, { timeout: 15_000 })
    .catch(() => null);
  await waitForFormReady(page, 15_000);
}

// ---------------------------------------------------------------------------
// Picker fill helpers — every helper fails loudly when picker cannot be driven.
// ---------------------------------------------------------------------------

async function pickCascade(page: Page, level0: string, level1: string, level2: string): Promise<void> {
  const name = 'sc_cascade_category';
  // Level 0
  const l0 = page.locator(`[data-testid="cascade-trigger-${name}-0"]`);
  await l0.scrollIntoViewIfNeeded();
  await expect(l0).toBeVisible({ timeout: 10_000 });
  await l0.click();
  const l0opt = page.locator(`[data-testid="cascade-option-${name}-0-${level0}"]`);
  await expect(l0opt).toBeVisible({ timeout: 5_000 });
  await l0opt.click();

  // Level 1 — wait until enabled (options load async after L0 selection)
  const l1 = page.locator(`[data-testid="cascade-trigger-${name}-1"]`);
  await expect(l1).toBeEnabled({ timeout: 5_000 });
  await l1.click();
  const l1opt = page.locator(`[data-testid="cascade-option-${name}-1-${level1}"]`);
  await expect(l1opt).toBeVisible({ timeout: 5_000 });
  await l1opt.click();

  // Level 2 — same: wait for options to load
  const l2 = page.locator(`[data-testid="cascade-trigger-${name}-2"]`);
  await expect(l2).toBeEnabled({ timeout: 5_000 });
  await l2.click();
  const l2opt = page.locator(`[data-testid="cascade-option-${name}-2-${level2}"]`);
  await expect(l2opt).toBeVisible({ timeout: 5_000 });
  await l2opt.click();
}

async function pickTreeSelect(page: Page, value: string): Promise<void> {
  // Narrow to the sc_tree_node field to avoid any stray tree-select elsewhere.
  const container = page.locator('[data-testid="form-field-sc_tree_node"]');
  await container.scrollIntoViewIfNeeded();
  const trigger = container.locator('[data-testid="tree-select-trigger"]').first();
  await expect(trigger).toBeVisible({ timeout: 10_000 });
  await trigger.click();

  // Parent node "tech" must be expanded to reveal leaf values like tech_frontend.
  // The target dict lists `tech` as a parent; we click its chevron by clicking the parent row first.
  const parent = page.locator('[data-testid="tree-select-option-tech"]').first();
  await expect(parent).toBeVisible({ timeout: 5_000 });
  // Click the expand chevron inside the parent row (the button is the first child button).
  const expandBtn = parent.locator('button').first();
  if (await expandBtn.isVisible({ timeout: 1_000 }).catch(() => false)) {
    await expandBtn.click();
  }

  const opt = page.locator(`[data-testid="tree-select-option-${value}"]`).first();
  await expect(opt).toBeVisible({ timeout: 5_000 });
  await opt.click();
}

async function pickMember(page: Page, memberId: string): Promise<void> {
  const container = page.locator('[data-testid="form-field-sc_team_members"]');
  await container.scrollIntoViewIfNeeded();

  // The trigger "div" is purely visual; the actual click target that opens the
  // popup is the inner <button data-testid="member-picker-add">.
  const addBtn = container.locator('[data-testid="member-picker-add"]').first();
  await expect(addBtn).toBeVisible({ timeout: 10_000 });
  await addBtn.click();

  const popup = container.locator('[data-testid="member-picker-popup"]').first();
  await expect(popup).toBeVisible({ timeout: 5_000 });

  const option = popup.locator(`[data-testid="member-picker-option-${memberId}"]`);
  await expect(option).toBeVisible({ timeout: 10_000 });
  await option.click();

  // Close popup to avoid overlap with subsequent pickers.
  await page.keyboard.press('Escape').catch(() => null);
}

async function pickOrganization(page: Page, orgId: string): Promise<void> {
  const container = page.locator('[data-testid="form-field-sc_department"]');
  await container.scrollIntoViewIfNeeded();
  const trigger = container.locator('[data-testid="organization-select-trigger-sc_department"]');
  await expect(trigger).toBeVisible({ timeout: 10_000 });
  await trigger.click();

  const opt = page.locator(`[data-testid="organization-select-option-${orgId}"]`).first();
  await expect(opt).toBeVisible({ timeout: 5_000 });
  await opt.click();
}

async function fillDate(page: Page, fieldName: string, value: string): Promise<void> {
  const input = page.locator(`[data-testid="date-picker-input-${fieldName}"]`);
  await input.scrollIntoViewIfNeeded();
  await expect(input).toBeVisible({ timeout: 10_000 });
  await input.fill(value);
  await input.dispatchEvent('change');
}

async function submitForm(page: Page): Promise<void> {
  await page.keyboard.press('Escape').catch(() => null);
  const saveBtn = page.locator('[data-testid="form-btn-submit"]');
  await saveBtn.scrollIntoViewIfNeeded();

  // Capture ALL commands/execute responses so we can diagnose validation rejections
  // (which return 400/422 and would otherwise time out the happy-path listener).
  const saveResp = page.waitForResponse(
    (r) => r.url().includes('/api/meta/commands/execute'),
    { timeout: 20_000 },
  );
  await saveBtn.click();
  const resp = await saveResp;
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.status() === 200,
    `Save must return HTTP 200 (got ${resp.status()}, body=${JSON.stringify(body).slice(0, 300)})`,
  ).toBeTruthy();
  expect(
    body?.code === '0' || body?.code === 0,
    `Save command must return code=0 (got ${body?.code}, message=${body?.message})`,
  ).toBeTruthy();
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('All-picker UI fill round-trip', { tag: ['@bpm-regression', '@picker'] }, () => {
  test.setTimeout(120_000);

  let recordPid = '';
  let memberId = '';

  test.beforeAll(async ({ browser }) => {
    // Resolve real fixture ids from the reset database. Pickers persist pids,
    // not the old mock ids used before OrganizationSelect became API-backed.
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const resp = await page.request.get('/api/admin/users/search?keyword=&size=5');
      const body = await resp.json();
      const users = body?.data?.content || body?.data || [];
      expect(Array.isArray(users) && users.length > 0, 'At least one user must exist').toBeTruthy();
      memberId = String(users[0].pid ?? users[0].id);
      expect(memberId, 'memberId must be resolved').toBeTruthy();

      const orgResp = await page.request.get('/api/org/departments/tree');
      const orgBody = await orgResp.json();
      const flatten = (items: Array<Record<string, any>>): Array<Record<string, any>> =>
        items.flatMap((item) => [item, ...flatten(item.children || [])]);
      const departments = flatten(orgBody?.data || []);
      orgTech = String(departments.find((item) => item.name === '技术部')?.pid ?? departments[0]?.pid ?? '');
      orgEditTarget = String(departments.find((item) => item.name === '财务部')?.pid ?? departments[1]?.pid ?? '');
      expect(orgTech, 'technical department pid must be resolved').toBeTruthy();
      expect(orgEditTarget, 'edit target department pid must be resolved').toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  test('PIK-1 @critical — UI fill all pickers + submit + verify persisted values', async ({ page, request }) => {
    await openCreateForm(page);

    // Required field — name
    const nameInput = page
      .locator('[data-testid="form-field-sc_name"] input')
      .first();
    await nameInput.fill(RECORD_NAME);

    // [D4/D5] DatePicker x 2 — value captured in submit payload.
    await fillDate(page, 'sc_start_date', START_DATE);
    await fillDate(page, 'sc_end_date', END_DATE);

    // [D4/D5] CascadeSelect — drive UI through all 3 levels. Post-GAP-258 the
    // renderer narrows onChange output to the deepest leaf (single string),
    // which matches sc_cascade_category dataType:string.
    await pickCascade(page, CASCADE_L0, CASCADE_L1, CASCADE_L2);

    // [D4/D5] TreeSelect — single-mode string. Persisted.
    await pickTreeSelect(page, TREE_VALUE);

    // [D4/D5] MemberPicker — exercises open + search + option click path.
    // Field is multiple:true; post-GAP-258 the renderer serializes the string[]
    // as a JSON string so sc_team_members (dataType:string, maxLength:2000) stores
    // '["pid-..."]'. Asserted below via JSON.parse.
    await pickMember(page, memberId);
    await expect(
      page.locator(`[data-testid="member-picker-selected-${memberId}"]`).first(),
    ).toBeVisible({ timeout: 5_000 });

    // [D4/D5] OrganizationSelect — single-mode string. Persisted.
    await pickOrganization(page, orgTech);

    // [D14] Submit — inline asserts 200 + code=0 with diagnostic body slice.
    await submitForm(page);

    // [D6] Locate the record via list API; name is unique (command validation)
    // so an equality filter uniquely resolves the row we just created.
    const rows = await queryFilteredList(
      page,
      'showcase_all_fields',
      'sc_name',
      RECORD_NAME,
      { operator: 'EQ' },
    );
    expect(rows.length, `Record "${RECORD_NAME}" must exist after UI save`).toBeGreaterThan(0);
    const row = rows[0] as Record<string, any>;
    recordPid = String(row.pid ?? row.id);
    expect(recordPid, 'Record pid resolvable').toBeTruthy();

    // Per-field persistence assertions for the pickers that are wired through.
    const detailResp = await request.get(`/api/dynamic/showcase_all_fields/${recordPid}`);
    expect(detailResp.ok()).toBeTruthy();
    const record = (await detailResp.json())?.data;
    expect(record, 'Record payload present').toBeTruthy();

    expect(String(record.sc_name)).toBe(RECORD_NAME);
    expect(String(record.sc_start_date), 'DatePicker sc_start_date persisted').toContain(START_DATE);
    expect(String(record.sc_end_date), 'DatePicker sc_end_date persisted').toContain(END_DATE);
    expect(String(record.sc_tree_node), 'TreeSelect persisted as leaf value').toBe(TREE_VALUE);
    expect(String(record.sc_department), 'OrganizationSelect persisted as org id').toBe(orgTech);

    // GAP-258: CascadeSelect now persists the deepest leaf value (single string).
    expect(
      String(record.sc_cascade_category),
      'CascadeSelect persisted as deepest leaf (single string)',
    ).toBe(CASCADE_L2);

    // GAP-258: MemberPicker(multiple) persists as JSON-serialized string[].
    expect(
      record.sc_team_members,
      'MemberPicker persisted as JSON string (dataType:string)',
    ).toBeTruthy();
    expect(
      JSON.parse(String(record.sc_team_members)),
      'MemberPicker JSON parses to the picked ids',
    ).toEqual([memberId]);
  });

  test('PIK-2 — Reopen edit form: pickers show initial values, edit, persist', async ({ page, request }) => {
    expect(recordPid, 'PIK-1 must have produced a record').toBeTruthy();

    // Open edit via URL (form page navigation — this is edit mode, D8).
    await page.goto(`/p/showcase_all_fields/edit/${recordPid}`, { waitUntil: 'domcontentloaded' });
    await waitForFormReady(page, 15_000);

    // [D5/D8] Initial date values pre-populated in pickers.
    await expect(
      page.locator('[data-testid="date-picker-input-sc_start_date"]'),
    ).toHaveValue(START_DATE, { timeout: 10_000 });
    await expect(
      page.locator('[data-testid="date-picker-input-sc_end_date"]'),
    ).toHaveValue(END_DATE, { timeout: 5_000 });

    // TreeSelect trigger reflects the persisted leaf label.
    const treeTrigger = page
      .locator('[data-testid="form-field-sc_tree_node"] [data-testid="tree-select-trigger"]')
      .first();
    await expect(treeTrigger).toContainText(/Frontend|前端/i, { timeout: 10_000 });

    // OrganizationSelect trigger reflects persisted org label.
    const orgTrigger = page.locator(
      '[data-testid="organization-select-trigger-sc_department"]',
    );
    await expect(orgTrigger).toContainText(/技术部|Tech/i, { timeout: 10_000 });

    // --- Edit: change end_date, tree node, organization ---
    // Tree: switch to backend.
    await treeTrigger.click();
    const techParent = page.locator('[data-testid="tree-select-option-tech"]').first();
    const expandBtn = techParent.locator('button').first();
    if (await expandBtn.isVisible({ timeout: 1_000 }).catch(() => false)) {
      await expandBtn.click().catch(() => null);
    }
    await page.locator(`[data-testid="tree-select-option-${TREE_VALUE_EDIT}"]`).click();

    await fillDate(page, 'sc_end_date', END_DATE_EDIT);

    // Organization: switch to 产品部.
    const orgContainer = page.locator('[data-testid="form-field-sc_department"]');
    await orgContainer.scrollIntoViewIfNeeded();
    await orgTrigger.click();
    await page
      .locator(`[data-testid="organization-select-option-${orgEditTarget}"]`)
      .first()
      .click();

    // Tweak name so the assertion clearly points at the right row.
    const nameInput = page.locator('[data-testid="form-field-sc_name"] input').first();
    await nameInput.fill(RECORD_NAME_EDITED);

    await submitForm(page);

    // [D6] Every edited picker value lands in the record.
    const resp = await request.get(`/api/dynamic/showcase_all_fields/${recordPid}`);
    const record = (await resp.json())?.data;
    expect(String(record.sc_name)).toBe(RECORD_NAME_EDITED);
    expect(String(record.sc_tree_node), 'TreeSelect edit persisted').toBe(TREE_VALUE_EDIT);
    expect(String(record.sc_end_date), 'DatePicker end_date edit persisted').toContain(END_DATE_EDIT);
    expect(String(record.sc_department), 'OrganizationSelect edit persisted').toBe(orgEditTarget);
    // start_date unchanged.
    expect(String(record.sc_start_date), 'DatePicker start_date unchanged').toContain(START_DATE);
  });

  test('PIK-3 — Cleanup: delete the record via command', async ({ page, request }) => {
    if (!recordPid) return; // PIK-1 failure already reported

    const result = await executeCommandViaApi(
      page,
      'sc:delete_showcase',
      {},
      recordPid,
      'delete',
      { allowHttpError: true },
    );
    expect(result.code === '0' || result.code === '200', 'Delete command should succeed').toBeTruthy();

    // Confirm record no longer resolvable
    const check = await request.get(`/api/dynamic/showcase_all_fields/${recordPid}`);
    if (check.ok()) {
      const body = await check.json();
      expect(body?.data, 'Record must be gone after delete').toBeFalsy();
    } else {
      expect([404, 400]).toContain(check.status());
    }
  });
});
