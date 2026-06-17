/**
 * Unified Designer — `type: 'model'` inspector model-selector golden.
 *
 * Before this change the `dataSource.model` inspector field (declared
 * `type: 'model'` for form / detail / list / sub-table / widget blocks) fell
 * through to the generic text-input fallback in SchemaInspector, forcing authors
 * to hand-type a raw modelCode. SchemaInspector now renders a real dropdown for
 * `type: 'model'`: it lists the published meta-models (GET /api/meta/models →
 * `data.records[]`, value = modelCode, label = `displayName (code)`) and keeps a
 * manual-entry fallback (`inspector-field-<path>-manual`) so a draft / external /
 * not-yet-loaded code can still be bound (forward compatibility).
 *
 * This suite proves the new control end to end (seed-agnostic — it depends only
 * on the model it seeds itself plus ≥1 other published model, never on a
 * specific fixture model, so leaner plugin profiles still pass):
 *   - the dropdown is a real <select> listing published models (≥2 options),
 *   - selecting a different (non-seed) model from the dropdown updates the binding,
 *   - the binding persists through designer-save and a GET /api/pages readback,
 *   - the manual-entry fallback can bind a model not surfaced by the dropdown
 *     selection and that also persists.
 *
 * Pattern follows inspector-authoring-golden.spec.ts (seed schemaVersion 3 →
 * open /unified-designer → select block → edit inspector → designer-save →
 * reload + GET readback toMatchObject).
 *
 * @since 4.0.0
 */

import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const ADMIN_STORAGE_STATE =
  process.env.PW_ADMIN_STORAGE_STATE ||
  (process.env.PW_STORAGE_DIR ? `${process.env.PW_STORAGE_DIR}/admin.json` : './tests/storage/admin.json');

// The suite seeds its own page bound to this published platform model, so it is
// always present in the dropdown regardless of which plugin profile the stack
// imported. The "select a different model" test derives its target from the
// live dropdown options (seed-agnostic) rather than a hard-coded fixture code.
const SEED_MODEL = 'ab_announcement'; // 系统公告
// A code that is NOT surfaced as a real model option — used only to exercise the
// manual-entry fallback, which binds an arbitrary (draft / external) code.
const MANUAL_MODEL = 'e2et_customer';

interface DslBlock {
  id?: string;
  blockType?: string;
  widgetType?: string;
  title?: unknown;
  props?: Record<string, unknown>;
  layout?: Record<string, unknown>;
  dataSource?: Record<string, unknown>;
  blocks?: DslBlock[];
}

interface PageSchemaDto {
  pid: string;
  pageKey: string;
  kind?: string;
  blocks?: DslBlock[];
}

function findBlockById(blocks: DslBlock[] | undefined, id: string): DslBlock | null {
  for (const block of blocks ?? []) {
    if (block.id === id) return block;
    const nested = findBlockById(block.blocks, id);
    if (nested) return nested;
  }
  return null;
}

async function readPage(page: Page, pid: string): Promise<PageSchemaDto> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `GET /api/pages/${pid} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'read page API code').toBe('0');
  return body.data as PageSchemaDto;
}

async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/unified-designer?pageId=${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 45_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存', { timeout: 15_000 });
}

async function selectBlock(page: Page, blockId: string): Promise<void> {
  await page.getByTestId(`outline-item-${blockId}`).click();
  await expect(page.getByTestId('inspector-selected-id')).toContainText(blockId);
}

async function saveDesigner(page: Page, pid: string): Promise<void> {
  const saveButton = page.getByTestId('designer-save');
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
  await expect(saveButton).toBeEnabled({ timeout: 10_000 });
  await expect(async () => {
    const saveResp = page.waitForResponse(
      (r) => r.url().includes(`/api/pages/${pid}`) && r.request().method() === 'PUT',
      { timeout: 5_000 },
    );
    await saveButton.click();
    const resp = await saveResp;
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.code).toBe('0');
  }).toPass({ timeout: 30_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
}

const FORM_ROOT = 'pd_model_form_root';

test.describe.serial('Unified Designer inspector model-select golden', () => {
  test.describe.configure({ timeout: 120_000 });

  const uid = uniqueId('pdmodel');
  let pid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: ADMIN_STORAGE_STATE });
    const page = await ctx.newPage();

    const resp = await page.request.post('/api/pages', {
      data: {
        name: `Model select ${uid}`,
        pageKey: `pd_model_${uid}`.replace(/-/g, '_'),
        title: `Model select ${uid}`,
        kind: 'form',
        modelCode: SEED_MODEL,
        schemaVersion: 3,
        blocks: [
          {
            id: FORM_ROOT,
            blockType: 'form',
            title: 'Model select root',
            dataSource: { model: SEED_MODEL },
            layout: { span: 12 },
            blocks: [],
          },
        ],
        extension: { e2e: true, scenario: 'inspector-model-select-golden' },
      },
    });
    expect(resp.ok(), `seed page failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
    const body = await resp.json();
    expect(body.code, 'seed page API code').toBe('0');
    pid = String(body.data?.pid ?? '');
    expect(pid, 'seeded pid').toBeTruthy();

    await ctx.close();
  });

  test('D1: model dropdown lists published models and is the rendered control (not a bare text box)', async ({
    page,
  }, testInfo) => {
    await openDesigner(page, pid);
    await selectBlock(page, FORM_ROOT);

    // The dataSource.model field is now a <select>, not a text <input>.
    const modelSelect = page.getByTestId('inspector-field-dataSource.model');
    await expect(modelSelect).toBeVisible({ timeout: 10_000 });
    await expect(modelSelect).toHaveJSProperty('tagName', 'SELECT');

    // The dropdown is populated from /api/meta/models — it lists real published
    // models. Seed-agnostic: the seeded model (always created by this suite) is
    // the current value, and the control offers at least the seeded model option
    // plus one or more other published models (leaner seed stacks still pass —
    // we no longer hard-depend on a specific extra model like e2et_order being
    // present, only that the dropdown is populated from real models).
    await expect(modelSelect).toHaveValue(SEED_MODEL);
    // The option list is filled by an async GET /api/meta/models, so poll the
    // option count (≥2 = the empty-unset option + ≥1 real published model)
    // instead of reading it once, which could race the fetch under load.
    await expect
      .poll(() => modelSelect.locator('option').count(), { timeout: 15_000 })
      .toBeGreaterThanOrEqual(2);
    await expect(modelSelect.locator(`option[value="${SEED_MODEL}"]`)).toHaveCount(1);
    await testInfo.attach('d1-model-dropdown', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
  });

  test('D1: selecting a model from the dropdown persists dataSource.model and reloads', async ({
    page,
  }, testInfo) => {
    await openDesigner(page, pid);
    await selectBlock(page, FORM_ROOT);

    const modelSelect = page.getByTestId('inspector-field-dataSource.model');
    await expect(modelSelect).toBeVisible({ timeout: 10_000 });

    // Seed-agnostic: pick a real published model option from the dropdown that
    // is NOT the seeded model and NOT the empty-unset option, instead of a
    // hard-coded code (e2et_order) that only the full e2e-fixtures seed carries.
    // Leaner seed stacks (core + announcement) still expose ≥1 other model, so
    // this proves "select a real model from the dropdown" without binding the
    // test to a specific fixture model.
    //
    // The option list is populated by an async GET /api/meta/models, so poll
    // until a non-seed option is present (the fetch may lag under load — reading
    // the options eagerly would otherwise race the fetch and see only the seed).
    const readNonSeedOption = () =>
      page.evaluate(
        ([testid, seed]) => {
          const select = document.querySelector(`[data-testid="${testid}"]`) as HTMLSelectElement | null;
          if (!select) return null;
          for (const option of Array.from(select.options)) {
            if (option.value && option.value !== seed) return option.value;
          }
          return null;
        },
        ['inspector-field-dataSource.model', SEED_MODEL] as const,
      );
    await expect
      .poll(readNonSeedOption, {
        message: 'a non-seed published model is offered by the dropdown',
        timeout: 15_000,
      })
      .toBeTruthy();
    const targetModel = await readNonSeedOption();
    expect(targetModel, 'a non-seed published model is offered by the dropdown').toBeTruthy();

    await modelSelect.selectOption(targetModel!);
    await expect(modelSelect).toHaveValue(targetModel!);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('d1-model-selected', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, FORM_ROOT);
    await expect(page.getByTestId('inspector-field-dataSource.model')).toHaveValue(targetModel!);
    await testInfo.attach('d1-model-reloaded', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    const persisted = await readPage(page, pid);
    const block = findBlockById(persisted.blocks, FORM_ROOT);
    expect(block).toMatchObject({
      blockType: 'form',
      dataSource: expect.objectContaining({ model: targetModel! }),
    });
  });

  test('D1: manual-entry fallback binds a model code and persists', async ({ page }, testInfo) => {
    await openDesigner(page, pid);
    await selectBlock(page, FORM_ROOT);

    // The manual fallback lets an author bind any model code (draft / external /
    // not yet loaded) even when it is not chosen via the dropdown.
    const manual = page.getByTestId('inspector-field-dataSource.model-manual');
    await expect(manual).toBeVisible({ timeout: 10_000 });
    await manual.fill(MANUAL_MODEL);
    // The dropdown mirrors the value (added as a leading option when present).
    await expect(page.getByTestId('inspector-field-dataSource.model')).toHaveValue(MANUAL_MODEL);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('d1-manual-entry', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, FORM_ROOT);
    await expect(page.getByTestId('inspector-field-dataSource.model')).toHaveValue(MANUAL_MODEL);

    const persisted = await readPage(page, pid);
    const block = findBlockById(persisted.blocks, FORM_ROOT);
    expect(block).toMatchObject({
      blockType: 'form',
      dataSource: expect.objectContaining({ model: MANUAL_MODEL }),
    });
  });
});
