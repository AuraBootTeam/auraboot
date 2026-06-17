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
 * This suite proves the new control end to end:
 *   - the dropdown lists real published models (ab_announcement is present),
 *   - selecting a model from the dropdown updates the binding,
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

// Two published platform/e2e meta-models present in every OSS stack.
const SEED_MODEL = 'ab_announcement'; // 系统公告
const SELECT_MODEL = 'e2et_order'; // 测试订单 — chosen via the dropdown
const MANUAL_MODEL = 'e2et_customer'; // 客户 — bound via the manual fallback

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
    // models. The seeded model is the current value; the target model is offered.
    await expect(modelSelect).toHaveValue(SEED_MODEL);
    await expect(modelSelect.locator(`option[value="${SELECT_MODEL}"]`)).toHaveCount(1);
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
    await modelSelect.selectOption(SELECT_MODEL);
    await expect(modelSelect).toHaveValue(SELECT_MODEL);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('d1-model-selected', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
    await selectBlock(page, FORM_ROOT);
    await expect(page.getByTestId('inspector-field-dataSource.model')).toHaveValue(SELECT_MODEL);
    await testInfo.attach('d1-model-reloaded', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    const persisted = await readPage(page, pid);
    const block = findBlockById(persisted.blocks, FORM_ROOT);
    expect(block).toMatchObject({
      blockType: 'form',
      dataSource: expect.objectContaining({ model: SELECT_MODEL }),
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
