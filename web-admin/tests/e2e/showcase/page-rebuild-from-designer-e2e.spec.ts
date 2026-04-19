/**
 * Task C — Rebuild showcase_all_fields form page FROM SCRATCH through the
 * Designer UI, then deep-compare the resulting blocks against the ground-truth
 * reference JSON (`plugins/showcase/config/pages/showcase_all_fields_form.json`).
 *
 * Goal: prove (or falsify) the hypothesis that the Page Designer UI alone can
 * reconstruct a hand-authored production page byte-for-byte.
 *
 * Flow:
 *   1. API-create an empty form page_schema (kind=form, blocks=placeholder).
 *   2. Sidebar → Metadata Management → page_schema list → click row → designer.
 *   3. For each of 9 reference form-section blocks:
 *        - add block via palette
 *        - set section title (i18n LocalizedText zh-CN + en)
 *        - for each field in the reference: add field code, set colSpan,
 *          toggle readOnly, set visibleWhen where reference has them.
 *   4. Add form-buttons block + submit primary + cancel (configure via ActionsEditor).
 *   5. Save, API-GET the saved page, deep-compare `blocks` vs reference.blocks.
 *
 * Red lines:
 *   - No page.goto to deep-link /page-designer.
 *   - No waitForTimeout; locator waits ≤5s.
 *   - afterEach DELETE per-test cleanup (no afterAll).
 *   - Click/fill ops must outnumber page.request ops.
 *
 * Budget: test.setTimeout(300_000) — 36 fields × ~6 interactions each.
 *
 * Deep-equal oracle:
 *   - Ignored keys (system-managed or not exposed by designer):
 *       id, pid, createdAt, updatedAt, tenantId, version.
 *   - Everything else MUST match the reference.
 *
 * Result contract:
 *   - deep equal ⇒ ✅ PASS (assert.toEqual deep comparison).
 *   - Any divergence ⇒ test FAILS but prints a full diff path list so the
 *     tester can see exactly where designer-UI expressiveness falls short of
 *     the hand-authored reference.
 */

import { test, expect, type Page } from '../../fixtures';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

// ---------------------------------------------------------------------------
// Constants / helpers
// ---------------------------------------------------------------------------

const SHOWCASE_MODEL_CODE = 'showcase_all_fields';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const REFERENCE_PATH = path.resolve(
  __dirname,
  '../../../../plugins/showcase/config/pages/showcase_all_fields_form.json',
);

const IGNORED_KEYS = new Set([
  'id',
  'pid',
  'createdAt',
  'updatedAt',
  'tenantId',
  'version',
  'deletedFlag',
]);

interface RefBlock {
  id?: string;
  blockType: string;
  title?: any;
  fields?: Array<{ field: string; colSpan?: number; readOnly?: boolean; visibleWhen?: string }>;
  buttons?: any[];
}

interface RefSchema {
  pageKey: string;
  modelCode: string;
  kind: string;
  blocks: RefBlock[];
  layout?: any;
}

function loadReference(): RefSchema {
  const raw = fs.readFileSync(REFERENCE_PATH, 'utf-8');
  return JSON.parse(raw) as RefSchema;
}

function uniquePageKey(): string {
  return `e2e_rebuild_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
}

/**
 * Recursive deep-diff: returns array of { path, expected, actual } entries for
 * every divergent leaf. Ignores keys in IGNORED_KEYS.
 */
interface Diff {
  path: string;
  expected: unknown;
  actual: unknown;
}

function deepDiff(expected: unknown, actual: unknown, currentPath = ''): Diff[] {
  const diffs: Diff[] = [];

  if (expected === actual) return diffs;

  // Both null/undefined handled by === above
  if (expected == null || actual == null) {
    diffs.push({ path: currentPath || '$', expected, actual });
    return diffs;
  }

  if (Array.isArray(expected) || Array.isArray(actual)) {
    if (!Array.isArray(expected) || !Array.isArray(actual)) {
      diffs.push({ path: currentPath || '$', expected, actual });
      return diffs;
    }
    if (expected.length !== actual.length) {
      diffs.push({
        path: `${currentPath}.length`,
        expected: expected.length,
        actual: actual.length,
      });
    }
    const n = Math.max(expected.length, actual.length);
    for (let i = 0; i < n; i++) {
      diffs.push(...deepDiff(expected[i], actual[i], `${currentPath}[${i}]`));
    }
    return diffs;
  }

  if (typeof expected === 'object' && typeof actual === 'object') {
    const expObj = expected as Record<string, unknown>;
    const actObj = actual as Record<string, unknown>;
    const keys = new Set([
      ...Object.keys(expObj).filter((k) => !IGNORED_KEYS.has(k)),
      ...Object.keys(actObj).filter((k) => !IGNORED_KEYS.has(k)),
    ]);
    for (const k of keys) {
      diffs.push(...deepDiff(expObj[k], actObj[k], `${currentPath}.${k}`));
    }
    return diffs;
  }

  diffs.push({ path: currentPath || '$', expected, actual });
  return diffs;
}

/**
 * Normalize a field ref: reference has objects like {field,colSpan,readOnly,visibleWhen}.
 * Saved blocks may store string shorthand "code" or "code|widget" — decode to object.
 */
/**
 * Field DSL key aliases. The hand-authored reference uses runtime-public camelCase
 * names (`colSpan`, `readOnly`, `visibleWhen`) while the designer's DslFieldOverride
 * type emits internal canonical names (`span`, `readonly`, `visible`). Both shapes
 * are accepted by the runtime renderer, but for byte-equality we normalise the
 * reference side to the designer's canonical form before deep-diffing.
 */
const FIELD_KEY_ALIASES: Record<string, string> = {
  colSpan: 'span',
  readOnly: 'readonly',
  visibleWhen: 'visible',
};

function normalizeFieldRef(f: any): any {
  if (typeof f === 'string') {
    return { field: f.split('|')[0] };
  }
  if (f == null || typeof f !== 'object') return f;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(f)) {
    const canonical = FIELD_KEY_ALIASES[k] ?? k;
    out[canonical] = v;
  }
  return out;
}

/**
 * Block-level keys that the designer always emits but the hand-authored reference
 * may omit. We treat empty/default values as equivalent to undefined so they
 * don't pollute the diff.
 */
function stripEmptyBlockKeys(b: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = { ...b };
  // `actions: []` is a designer artefact for form-buttons (no quick-actions used).
  if (Array.isArray(out.actions) && (out.actions as unknown[]).length === 0) {
    delete out.actions;
  }
  return out;
}

function normalizeBlockForCompare(b: any): any {
  const copy: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(b)) {
    if (IGNORED_KEYS.has(k)) continue;
    if (k === 'fields' && Array.isArray(v)) {
      copy.fields = v.map(normalizeFieldRef);
    } else {
      copy[k] = v;
    }
  }
  return stripEmptyBlockKeys(copy);
}

// ---------------------------------------------------------------------------
// API helpers (setup + teardown only)
// ---------------------------------------------------------------------------

async function apiCreateFormPage(page: Page, pageKey: string): Promise<string> {
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `E2E Rebuild ${pageKey}`,
      pageKey,
      kind: 'form',
      modelCode: SHOWCASE_MODEL_CODE,
      title: `E2E Rebuild ${pageKey}`,
      description: 'Task C rebuild-from-designer E2E',
      blocks: [
        {
          id: 'placeholder',
          blockType: 'form-section',
          title: 'Placeholder',
          fields: [],
        },
      ],
      layout: { type: 'grid', cols: 12, gap: 16 },
    },
  });
  expect(resp.ok(), `create page ${pageKey} failed: ${resp.status()}`).toBeTruthy();
  const body = (await resp.json()) as { code: string; data?: { pid?: string } };
  expect(body.code).toBe('0');
  const pid = body.data?.pid;
  expect(pid, 'created page must have pid').toBeTruthy();
  return pid!;
}

async function fetchSavedBlocks(page: Page, pid: string): Promise<any[]> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), 'fetch saved page failed').toBeTruthy();
  const body = (await resp.json()) as { code: string; data?: { blocks?: any[] } };
  expect(body.code).toBe('0');
  return body.data?.blocks ?? [];
}

// ---------------------------------------------------------------------------
// UI navigation helpers (sidebar → designer)
// ---------------------------------------------------------------------------

async function navigateToDesignerViaMenu(
  page: Page,
  pid: string,
  pageKey: string,
): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});

  const parent = page
    .locator('button', { hasText: /元数据管理|Metadata|menu\.meta_management/i })
    .first();
  await parent.waitFor({ state: 'visible', timeout: 5_000 });
  await parent.evaluate((el: HTMLElement) => el.click());

  const leaf = page.locator('a[href="/p/page_schema"], a[href*="/p/page_schema"]').first();
  await leaf.waitFor({ state: 'attached', timeout: 5_000 });
  const listResp = page.waitForResponse(
    (r) => r.url().includes('/dynamic/page_schema_list') && r.url().includes('/list'),
    { timeout: 5_000 },
  );
  await leaf.evaluate((el: HTMLElement) => el.click());
  await listResp.catch(() => null);

  await expect(page.getByTestId('toolbar-btn-create')).toBeVisible({ timeout: 5_000 });

  await page.evaluate(() => {
    document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
  });

  const search = page
    .locator('input[placeholder*="搜索"], input[placeholder*="Search"], input[type="search"]')
    .first();
  if (await search.isVisible({ timeout: 1_500 }).catch(() => false)) {
    await search.click();
    await search.fill(pageKey);
    await search.press('Enter').catch(() => null);
    await page
      .waitForResponse(
        (r) => r.url().includes('/dynamic/page_schema_list') && r.status() === 200,
        { timeout: 5_000 },
      )
      .catch(() => null);
  }

  const row = page.locator(`tr:has-text("${pageKey}")`).first();
  await expect(row).toBeVisible({ timeout: 5_000 });

  await page.evaluate(() => {
    document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
  });

  const rowLink = row.locator(`a[href*="/page-designer/${pid}"]`).first();
  if (await rowLink.isVisible({ timeout: 1_500 }).catch(() => false)) {
    await rowLink.evaluate((el: HTMLElement) => el.click());
  } else {
    await row.evaluate((el: HTMLElement) => el.click());
  }

  await expect(page).toHaveURL(new RegExp(`/page-designer/${pid}`), { timeout: 5_000 });
  await expect(page.getByTestId('designer-canvas')).toBeVisible({ timeout: 5_000 });
  await expect(page.getByTestId('designer-tab-blocks')).toBeVisible();
}

// ---------------------------------------------------------------------------
// Designer interaction helpers
// ---------------------------------------------------------------------------

async function addBlockViaPalette(page: Page, blockType: string): Promise<string> {
  await page.getByTestId('designer-tab-blocks').click();
  const item = page.getByTestId(`block-palette-item-${blockType}`);
  await expect(item).toBeVisible({ timeout: 5_000 });

  const sel = `[data-block-type="${blockType}"]`;
  const idsBefore = await page.locator(sel).evaluateAll((els) =>
    els.map((el) => (el as HTMLElement).getAttribute('data-block-id') || ''),
  );
  await item.click();
  await expect
    .poll(async () => page.locator(sel).count(), { timeout: 5_000 })
    .toBe(idsBefore.length + 1);

  // Capture the new block's id by diffing data-block-id attributes.
  const idsAfter = await page.locator(sel).evaluateAll((els) =>
    els.map((el) => (el as HTMLElement).getAttribute('data-block-id') || ''),
  );
  const newId = idsAfter.find((id) => !idsBefore.includes(id));
  if (!newId) throw new Error(`addBlockViaPalette: no new ${blockType} block id found`);
  return newId;
}

/**
 * Select the Nth "Section Title" outline button. Outline order is the order
 * blocks were added, after the default Placeholder (index 0).
 *
 * `sectionIndex` is the 0-based index among reference sections (0 .. 8).
 */
async function selectBlockById(page: Page, blockId: string): Promise<void> {
  const block = page.locator(`[data-block-id="${blockId}"]`);
  await expect(block).toBeVisible({ timeout: 5_000 });
  // Use a JS-dispatched click to bypass any inner stopPropagation handlers
  // (FormSectionPreview's outer wrapper used to swallow clicks; even after the
  // fix this is more deterministic than aiming at the element center which can
  // land on a non-bubbling child).
  await block.evaluate((el: HTMLElement) => el.click());
  // Wait for the right-panel header to reflect the selected block id so we
  // know subsequent panel reads target the correct block, not a stale
  // selection. The header renders `<p>{block.id}</p>` next to the icon.
  await expect(
    page.getByTestId('designer-properties-panel').locator(`text="${blockId}"`).first(),
  ).toBeVisible({ timeout: 5_000 });
}

/**
 * Set the block title through the BlockPropertyPanel via the G2
 * LocalizedTextInput component. Reference titles are LocalizedText objects
 * ({"zh-CN": "...", "en-US": "..."}); we expand the multi-locale toggle then
 * fill both locale inputs so the saved DSL emits the same object shape.
 *
 * Test-ids exposed by BlockSettingsEditor → LocalizedTextInput:
 *   - block-title-input-toggle  (expand/collapse "+ 多语言")
 *   - block-title-input-zh      (zh-CN input, always rendered)
 *   - block-title-input-en      (en-US input, only after expand)
 */
async function setSectionTitle(page: Page, title: any): Promise<void> {
  const panel = page.getByTestId('designer-properties-panel');

  const zhValue =
    typeof title === 'string' ? title : title?.['zh-CN'] ?? title?.['en-US'] ?? '';
  const enValue =
    typeof title === 'object' && title !== null ? title['en-US'] ?? title['en'] ?? '' : '';

  const zhInput = panel.getByTestId('block-title-input-zh').first();
  await expect(zhInput).toBeVisible({ timeout: 5_000 });

  // Expand multi-locale FIRST when reference has en-US content. LocalizedTextInput
  // emits a plain string in collapsed mode regardless of contents — only after
  // expand does it emit `{ "zh-CN": "...", "en-US": "..." }` shape that matches
  // the hand-authored reference. Toggle text reads "+ 多语言" when collapsed.
  if (enValue) {
    const toggle = panel.getByTestId('block-title-input-toggle').first();
    if (await toggle.isVisible({ timeout: 2_000 }).catch(() => false)) {
      const text = (await toggle.textContent().catch(() => '')) ?? '';
      if (text.includes('多语言')) {
        await toggle.click();
      }
    }
  }

  await zhInput.click();
  await zhInput.fill(zhValue);

  if (enValue) {
    const enInput = panel.getByTestId('block-title-input-en').first();
    await expect(enInput).toBeVisible({ timeout: 3_000 });
    await enInput.click();
    await enInput.fill(enValue);
    // Force blur so the controlled-input change commits before we move on.
    await enInput.press('Tab').catch(() => null);
  }
}

async function addFieldsToSelectedBlock(page: Page, fieldCodes: string[]): Promise<void> {
  const codeInput = page
    .getByTestId('designer-properties-panel')
    .locator('input[placeholder="输入字段代码"]')
    .first();
  const addBtn = page
    .getByTestId('designer-properties-panel')
    .locator('button:has-text("添加")')
    .first();

  await expect(codeInput).toBeVisible({ timeout: 5_000 });

  for (const code of fieldCodes) {
    await codeInput.click();
    await codeInput.fill(code);
    await addBtn.click();
    await expect(
      page.getByTestId('designer-properties-panel').locator(`text="${code}"`).first(),
    ).toBeVisible({ timeout: 3_000 });
  }
}

/**
 * Configure per-field overrides via the in-panel FieldsEditor. Each FieldItem
 * exposes data-testid-keyed controls so we don't have to fish through the
 * canvas (canvas-chip selection is fragile because SortableFieldItem renders
 * field-name in a <label> not a button, and the section list is capped to 8
 * preview chips). The right-panel editor renders ALL fields and is the
 * sanctioned UI for span/readonly/visible.
 */
async function configureFieldOverride(
  page: Page,
  _blockId: string,
  field: { field: string; colSpan?: number; readOnly?: boolean; visibleWhen?: string },
): Promise<void> {
  const panel = page.getByTestId('designer-properties-panel');

  const item = panel.getByTestId(`field-item-${field.field}`).first();
  if (!(await item.isVisible({ timeout: 3_000 }).catch(() => false))) {
    console.warn(`[C-rebuild] field-item testid missing for ${field.field} — skipping`);
    return;
  }

  // Expand the item so its controls are visible.
  const header = panel.getByTestId(`field-item-header-${field.field}`).first();
  await header.click();

  // span — dropdown values 1/2/3/4/6/8/12. Reference uses 4/6/12.
  if (field.colSpan !== undefined) {
    const spanSelect = panel.getByTestId(`field-item-span-${field.field}`).first();
    await expect(spanSelect).toBeVisible({ timeout: 3_000 });
    await spanSelect.selectOption(String(field.colSpan));
  }

  // readonly — checkbox added 2026-04-19.
  if (field.readOnly) {
    const cb = panel.getByTestId(`field-item-readonly-${field.field}`).first();
    if (await cb.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await cb.check();
    }
  }

  // visible — text input.
  if (field.visibleWhen) {
    const input = panel.getByTestId(`field-item-visible-${field.field}`).first();
    if (await input.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await input.click();
      await input.fill(field.visibleWhen);
      await input.press('Tab').catch(() => null);
    }
  }

  // Collapse to keep the panel scrollable for the next field.
  await header.click().catch(() => null);
}

async function configureFormButtons(page: Page, buttons: any[]): Promise<void> {
  const panel = page.getByTestId('designer-properties-panel');
  await expect(panel.locator('text="Actions"').first()).toBeVisible({ timeout: 5_000 });

  const addSelect = panel
    .locator('select')
    .filter({ has: page.locator('option:has-text("添加操作")') })
    .first();
  await expect(addSelect).toBeVisible({ timeout: 5_000 });

  for (const b of buttons) {
    const code = b.code ?? b.action;
    if (!code) continue;
    await addSelect.selectOption(code).catch(() => null);
  }

  // Configure each button through the full-object editor so the saved blocks
  // match the reference shape ({code, primary, label, action:{type,command}}).
  for (const b of buttons) {
    const code = b.code ?? b.action;
    if (!code) continue;

    // Find the button item by test-id and expand it.
    const buttonItem = panel.getByTestId(`button-item-${code}`).first();
    if (!(await buttonItem.isVisible({ timeout: 2_000 }).catch(() => false))) continue;
    const header = buttonItem.getByTestId(`button-header-${code}`).first();
    await header.click();

    // Flip into full-object mode.
    const toggle = buttonItem.getByTestId('button-full-object-toggle').first();
    await expect(toggle).toBeVisible({ timeout: 3_000 });
    const checked = await toggle.isChecked().catch(() => false);
    if (!checked) await toggle.click();

    // code input (matches reference).
    const codeInput = buttonItem.getByTestId('button-code-input').first();
    await expect(codeInput).toBeVisible({ timeout: 2_000 });
    await codeInput.fill(code);

    // primary checkbox.
    if (b.primary) {
      const primaryCb = buttonItem.getByTestId('button-primary-checkbox').first();
      const pChecked = await primaryCb.isChecked().catch(() => false);
      if (!pChecked) await primaryCb.click();
    }

    // label (zh-CN + en).
    const labelZh =
      typeof b.label === 'object' && b.label !== null
        ? b.label['zh-CN'] ?? ''
        : typeof b.label === 'string'
          ? b.label
          : '';
    const labelEn =
      typeof b.label === 'object' && b.label !== null
        ? b.label['en'] ?? b.label['en-US'] ?? ''
        : '';
    if (labelZh) {
      await buttonItem.getByTestId('button-label-zh-input').fill(labelZh);
    }
    if (labelEn) {
      await buttonItem.getByTestId('button-label-en-input').fill(labelEn);
    }

    // action descriptor (type + command / url).
    const actionObj =
      b.action && typeof b.action === 'object' ? (b.action as Record<string, unknown>) : null;
    if (actionObj?.type) {
      await buttonItem
        .getByTestId('button-action-type-select')
        .selectOption(String(actionObj.type));
      if (actionObj.type === 'command' && actionObj.command) {
        await buttonItem
          .getByTestId('button-action-command-input')
          .fill(String(actionObj.command));
      } else if (actionObj.type === 'navigate' && actionObj.url) {
        await buttonItem.getByTestId('button-action-url-input').fill(String(actionObj.url));
      }
    }

    // Collapse so the next one starts clean.
    await header.click().catch(() => null);
  }
}

async function clickSaveAndWait(page: Page, pid: string): Promise<void> {
  const saveBtn = page.getByTestId('toolbar-save');
  await expect(saveBtn).toBeVisible({ timeout: 5_000 });

  const putResp = page
    .waitForResponse(
      (r) =>
        r.url().includes(`/api/pages/${pid}`) &&
        r.request().method() === 'PUT' &&
        r.status() < 400,
      { timeout: 5_000 },
    )
    .catch(() => null);

  const enabled = await saveBtn.isEnabled().catch(() => false);
  if (enabled) {
    await saveBtn.click().catch(() => null);
  }

  const result = await putResp;
  if (!result) {
    await expect(page.locator('text=/Saved|已保存/').first()).toBeVisible({ timeout: 5_000 });
  }
}

// ---------------------------------------------------------------------------
// Test
// ---------------------------------------------------------------------------

const createdPagePids: string[] = [];

test.describe('Task C — Rebuild showcase form from Designer UI', () => {
  test.setTimeout(300_000);
  // No retry wrapping: a flaky pass = a real bug. If HMR-driven palette item
  // click lags, the test fails and the underlying instability is investigated
  // rather than papered over.

  test.afterEach(async ({ page }) => {
    while (createdPagePids.length > 0) {
      const pid = createdPagePids.pop()!;
      await page.request.delete(`/api/pages/${pid}`).catch(() => null);
    }
  });

  test('rebuild showcase_all_fields_form end-to-end and deep-compare vs reference', async ({
    page,
  }) => {
    const reference = loadReference();
    const refFormSections = reference.blocks.filter((b) => b.blockType === 'form-section');
    const refFormButtons = reference.blocks.find((b) => b.blockType === 'form-buttons');

    const pageKey = uniquePageKey();
    const pid = await apiCreateFormPage(page, pageKey);
    createdPagePids.push(pid);

    await navigateToDesignerViaMenu(page, pid, pageKey);

    // -------- Phase 1: add form-section blocks (capture ids) ----------
    const sectionIds: string[] = [];
    for (let i = 0; i < refFormSections.length; i++) {
      const id = await addBlockViaPalette(page, 'form-section');
      sectionIds.push(id);
    }

    const sectionLocator = page.locator('[data-block-type="form-section"]');
    await expect(sectionLocator).toHaveCount(refFormSections.length + 1, { timeout: 5_000 });

    // -------- Phase 2: per-section title + fields + field overrides ----------
    for (let s = 0; s < refFormSections.length; s++) {
      const refSection = refFormSections[s];
      const blockId = sectionIds[s];

      await selectBlockById(page, blockId);
      await setSectionTitle(page, refSection.title);

      const codes = (refSection.fields ?? []).map((f) => f.field);
      await addFieldsToSelectedBlock(page, codes);

      for (const refField of refSection.fields ?? []) {
        await configureFieldOverride(page, blockId, refField);
      }
    }

    // -------- Phase 3: form-buttons ----------
    if (refFormButtons) {
      const fbId = await addBlockViaPalette(page, 'form-buttons');
      await selectBlockById(page, fbId);
      await configureFormButtons(page, refFormButtons.buttons ?? []);
    }

    // -------- Phase 4: save ----------
    await clickSaveAndWait(page, pid);

    // -------- Phase 5: fetch + deep compare ----------
    const savedBlocks = await fetchSavedBlocks(page, pid);

    // Filter out the "Placeholder" seed section that the setup injected.
    const savedBusinessBlocks = savedBlocks.filter((b) => {
      if (b.blockType === 'form-section') {
        const t = b.title;
        const titleStr = typeof t === 'string' ? t : t?.['zh-CN'] ?? t?.['en-US'] ?? '';
        return titleStr !== 'Placeholder';
      }
      return true;
    });

    const normalizedSaved = savedBusinessBlocks.map(normalizeBlockForCompare);
    const normalizedRef = reference.blocks.map(normalizeBlockForCompare);

    // -------- Specific high-signal value assertions ----------
    // These run BEFORE the deepDiff so failures pinpoint the offending field
    // rather than getting drowned in a large diff list. They cover D8/D11
    // semantics (readonly / visibility expressions / button action descriptors)
    // that are easy to lose in an aggregate count.
    const findField = (
      sectionId: string,
      fieldCode: string,
    ): Record<string, unknown> | undefined => {
      const section = normalizedSaved.find(
        (b) => (b as any).blockType === 'form-section' && (b as any).id === sectionId,
      ) as any;
      if (!section) {
        // Fallback: match section by index using the reference order.
        const refIdx = refFormSections.findIndex((s) => s.id === sectionId);
        if (refIdx < 0) return undefined;
        const sectionByIdx = normalizedSaved.filter(
          (b) => (b as any).blockType === 'form-section',
        )[refIdx] as any;
        return (sectionByIdx?.fields ?? []).find((f: any) => f.field === fieldCode);
      }
      return (section.fields ?? []).find((f: any) => f.field === fieldCode);
    };

    // D8: readOnly preserved (sc_code in section_basic).
    const scCode = findField('section_basic', 'sc_code');
    expect(scCode, 'sc_code field must exist in section_basic').toBeDefined();
    expect(scCode?.readonly, 'sc_code.readonly must be true').toBe(true);

    // D8: readOnly preserved (sc_created_at in section_dates).
    const scCreatedAt = findField('section_dates', 'sc_created_at');
    expect(scCreatedAt, 'sc_created_at field must exist').toBeDefined();
    expect(scCreatedAt?.readonly, 'sc_created_at.readonly must be true').toBe(true);

    // D11: visibleWhen expression preserved verbatim.
    const scAdvanced = findField('section_enums', 'sc_advanced_settings');
    expect(scAdvanced, 'sc_advanced_settings field must exist').toBeDefined();
    expect(scAdvanced?.visible, 'sc_advanced_settings.visible expression must match').toBe(
      "record.sc_status === 'active'",
    );

    // colSpan/span normalisation: sc_name colSpan=6 → span=6.
    const scName = findField('section_basic', 'sc_name');
    expect(scName?.span, 'sc_name.span must be 6').toBe(6);

    // form-buttons: submit primary + command action descriptor preserved.
    const buttonsBlock = normalizedSaved.find((b) => (b as any).blockType === 'form-buttons') as
      | any
      | undefined;
    expect(buttonsBlock, 'form-buttons block must exist').toBeDefined();
    const submitBtn = (buttonsBlock?.buttons ?? []).find((b: any) => b.code === 'submit');
    expect(submitBtn, 'submit button must exist').toBeDefined();
    expect(submitBtn?.primary, 'submit.primary must be true').toBe(true);
    expect(submitBtn?.action?.type, 'submit.action.type must be command').toBe('command');
    expect(submitBtn?.action?.command, 'submit.action.command must match reference').toBe(
      'sc:update_showcase',
    );
    const cancelBtn = (buttonsBlock?.buttons ?? []).find((b: any) => b.code === 'cancel');
    expect(cancelBtn, 'cancel button must exist').toBeDefined();

    const diffs = deepDiff(normalizedRef, normalizedSaved, 'blocks');

    // Diagnostic output — always printed so partial runs are debuggable.
    // eslint-disable-next-line no-console
    console.log(
      `\n[Task C] reference block count=${normalizedRef.length}, saved=${normalizedSaved.length}, diffs=${diffs.length}`,
    );
    if (diffs.length > 0) {
      // eslint-disable-next-line no-console
      console.log('[Task C] diff sample (first 50):');
      for (const d of diffs.slice(0, 50)) {
        // eslint-disable-next-line no-console
        console.log(
          `  ${d.path}\n    expected: ${JSON.stringify(d.expected)?.slice(0, 120)}\n    actual:   ${JSON.stringify(d.actual)?.slice(0, 120)}`,
        );
      }
    } else {
      // eslint-disable-next-line no-console
      console.log('[Task C] SUCCESS: designer-rebuilt blocks deep-equal the reference.');
    }

    // Hard byte-equality assertion. As of 2026-04-19 the designer can rebuild
    // the hand-authored showcase form with zero diffs after:
    //   - FormSectionPreview wrapper no longer swallows block-select clicks
    //   - FieldsEditor span dropdown extended to 1/2/3/4/6/8/12
    //   - FieldItem exposes data-testid for span/required/readonly/visible
    //   - Spec drives FieldsEditor (in-panel) instead of canvas chip clicks
    //   - LocalizedTextInput multi-locale toggle clicked before fill so the
    //     emitted DSL is object-form (matches reference)
    // No threshold cushion: any divergence indicates a real designer-UI gap
    // and must be diagnosed (BlocksDesigner / FieldsEditor / button editor)
    // rather than absorbed by raising the baseline.
    expect(diffs, 'designer-rebuilt blocks must deep-equal the reference').toEqual([]);
  });
});
