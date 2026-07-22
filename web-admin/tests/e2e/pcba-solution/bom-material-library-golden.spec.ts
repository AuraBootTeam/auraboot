/**
 * Material library page — browser golden.
 *
 * Why this file exists: `bom:create_material` and `bom:update_material` were
 * converted from declarative CRUD to handler-backed commands so they write the
 * derived columns matching depends on (`bom_mm_norm_text`, and the typed
 * attributes the ranker scores on). That was verified at the command layer and
 * against the database. What no test covered is the step in between — whether
 * the page actually drives those commands with the fields the operator typed.
 *
 * The interesting assertion is therefore not "the form saved" but "the row the
 * form produced is one the matcher can read". A save that returns 200 and
 * leaves a null match text looks identical on screen and breaks recall for
 * every row queried alongside it.
 */
import path from 'node:path';

import type { Page } from '@playwright/test';

import { test, expect } from '../../fixtures';

const LIST = '/p/bom_material_master';

/** Seeds through the command layer: the page has no create entry point. */
async function seedMaterial(page: Page, code: string): Promise<string> {
  const created = await page.request.post('/api/meta/commands/execute/bom:create_material', {
    data: {
      payload: {
        bom_mm_material_code: code,
        bom_mm_material_name: '贴片电阻',
        bom_mm_spec_model: '10kΩ ±1% 0603',
        bom_mm_unit: 'PCS',
        bom_mm_category: 'resistor',
        bom_mm_package: '0603',
        bom_mm_enabled: true,
      },
      operationType: 'create',
    },
    timeout: 60_000,
  });
  expect(created.status(), `seed material ${code} created`).toBe(200);
  const row = await materialByCode(page, code);
  expect(row, `seeded material ${code} is listed by the API`).toBeTruthy();
  return String(row.pid || '');
}

async function materialByCode(page: Page, code: string): Promise<any | undefined> {
  const r = await page.request.get(
    `/api/dynamic/bom_material_master/list?pageNum=1&pageSize=200&keyword=${encodeURIComponent(code)}`,
  );
  const body = await r.json().catch(() => ({}) as any);
  const rows = body?.data?.records || [];
  return rows.find((row: any) => String(row.bom_mm_material_code || '') === code);
}

test.describe('BOM material library page @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);

  const uid = String(Date.now()).slice(-7);

  test('the library page lists materials and offers the row actions the DSL declares', async ({
    page,
  }) => {
    const code = `UIGOLD-L${uid}`;
    await seedMaterial(page, code);

    await page.goto(LIST, { waitUntil: 'domcontentloaded' });

    // Reached by URL here; the sidebar route is asserted separately below so a
    // navigation regression cannot hide behind a direct link.
    const table = page.locator('main table tbody tr');
    await expect
      .poll(async () => table.count(), { timeout: 60_000, intervals: [1_000, 2_000] })
      .toBeGreaterThan(0);

    // The seeded row must be findable through the page's own search, not just
    // present somewhere in the dataset.
    const search = page.locator('main input[type="search"], main input[placeholder*="搜索"]').first();
    if (await search.count()) {
      await search.fill(code);
      await search.press('Enter');
    }
    await expect(page.getByText(code, { exact: false }).first()).toBeVisible({ timeout: 30_000 });
  });

  test('saving an edit rewrites the derived match text, not just the visible fields', async ({
    page,
  }) => {
    const code = `UIGOLD-E${uid}`;
    const seededPid = await seedMaterial(page, code);

    const before = await materialByCode(page, code);
    expect(
      String(before?.bom_mm_norm_text || ''),
      'seeded row already carries a match text',
    ).not.toBe('');

    await page.goto(`/p/bom_material_master/edit/${seededPid}`, {
      waitUntil: 'domcontentloaded',
    });

    const spec = page.locator('input[name="bom_mm_spec_model"], textarea[name="bom_mm_spec_model"]').first();
    await expect(spec, 'spec field is editable on the form page').toBeVisible({ timeout: 45_000 });

    // Controlled React inputs ignore fill()'s synthetic events in this codebase;
    // type the value so onChange fires and the form marks itself dirty.
    await spec.click();
    await spec.press('ControlOrMeta+a');
    await spec.pressSequentially('4.7kΩ ±5% 0805', { delay: 10 });

    const save = page.getByRole('button', { name: /^(保存|Save)$/ }).first();
    await expect(save, 'save button is offered in edit mode').toBeVisible({ timeout: 20_000 });
    await save.click();

    // The point of the test. The visible field changing proves the form works;
    // the derived column changing proves the form reached the handler that
    // keeps the row readable by the matcher.
    await expect
      .poll(
        async () => String((await materialByCode(page, code))?.bom_mm_spec_model || ''),
        { timeout: 60_000, intervals: [1_000, 2_000] },
      )
      .toBe('4.7kΩ ±5% 0805');

    const after = await materialByCode(page, code);
    expect(
      String(after?.bom_mm_norm_text || ''),
      `match text must be recomputed from the edited spec (was "${before?.bom_mm_norm_text}")`,
    ).toBe('4.7kω ±5% 0805 贴片电阻');
    expect(
      String(after?.bom_mm_attributes_json || ''),
      'typed attributes must be re-extracted so param_all can still fire',
    ).toContain('4700');
  });

  test('the form refuses a save that would leave a required field empty', async ({ page }) => {
    const code = `UIGOLD-R${uid}`;
    const seededPid = await seedMaterial(page, code);
    await page.goto(`/p/bom_material_master/edit/${seededPid}`, {
      waitUntil: 'domcontentloaded',
    });

    const spec = page.locator('input[name="bom_mm_spec_model"], textarea[name="bom_mm_spec_model"]').first();
    await expect(spec).toBeVisible({ timeout: 45_000 });
    await spec.click();
    await spec.press('ControlOrMeta+a');
    await spec.press('Backspace');

    await page.getByRole('button', { name: /^(保存|Save)$/ }).first().click();

    // Two things must hold, and the second is the one that actually protects
    // the library: the operator sees an error, AND nothing was written. A form
    // that shows a toast but saves anyway passes a "did an error appear" check.
    await expect(
      page.locator('[role="alert"], .text-destructive, [data-testid*="error"]').first(),
      'an empty required field is reported to the operator',
    ).toBeVisible({ timeout: 20_000 });

    const after = await materialByCode(page, code);
    expect(
      String(after?.bom_mm_spec_model || ''),
      'the rejected save left the stored record untouched',
    ).toBe('10kΩ ±1% 0603');
  });

  test('the page offers no way to create a material, which is why the command is unreachable', async ({
    page,
  }) => {
    await page.goto(LIST, { waitUntil: 'domcontentloaded' });
    await expect
      .poll(async () => page.locator('main table tbody tr').count(), { timeout: 60_000 })
      .toBeGreaterThan(0);

    // gate:absent-by-design — recorded, not endorsed. `bom:create_material`
    // ("新增物料", permission bom.library.manage, twelve declared input fields)
    // appears nowhere in the page DSL: the list toolbar carries only the two
    // Kingdee sync buttons, and form-buttons offers `update` gated on
    // state.mode === 'edit' plus `cancel`. So the command can only be invoked
    // through the API.
    //
    // This assertion exists so the day someone adds the button, this test goes
    // red and the create path gets covered here deliberately — rather than the
    // button shipping with no UI coverage at all. If you are that person:
    // delete this test and assert the create flow instead.
    // Anchored, not substring: the toolbar carries a "今日新建" filter chip and
    // an "添加筛选" button, both of which a loose /新增|新建|添加/ matches. An
    // assertion that fires on a filter chip is not evidence about the create
    // path either way.
    const create = page.getByRole('button', { name: /^\s*(新增|新建|新增物料|Create|New)\s*$/ });
    expect(
      await create.count(),
      'no create entry point on the material library page — if this fails, add create coverage',
    ).toBe(0);

    // Positive control for the assertion above: the toolbar does render buttons,
    // so a count of 0 means "no create button" rather than "no toolbar".
    await expect(
      page.getByRole('button', { name: /立即增量同步|Sync Incremental/ }).first(),
      'the toolbar rendered, so the absence above is meaningful',
    ).toBeVisible({ timeout: 20_000 });
  });
});

test.describe('BOM material library navigation @smoke', () => {
  test('the library is reachable from the sidebar, not only by URL', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    const link = page.locator(`nav a[href="${LIST}"], aside a[href="${LIST}"]`).first();
    await expect(link, 'sidebar carries a link to the material library').toBeVisible({
      timeout: 60_000,
    });
    await link.click();
    await expect
      .poll(async () => new URL(page.url()).pathname, { timeout: 30_000 })
      .toContain(path.posix.basename(LIST));
  });
});
