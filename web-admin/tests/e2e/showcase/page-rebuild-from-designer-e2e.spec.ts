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
function normalizeFieldRef(f: any): any {
  if (typeof f === 'string') {
    return { field: f.split('|')[0] };
  }
  return f;
}

function normalizeBlockForCompare(b: any): any {
  const copy: any = {};
  for (const [k, v] of Object.entries(b)) {
    if (IGNORED_KEYS.has(k)) continue;
    if (k === 'fields' && Array.isArray(v)) {
      copy.fields = v.map(normalizeFieldRef);
    } else {
      copy[k] = v;
    }
  }
  return copy;
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

async function addBlockViaPalette(page: Page, blockType: string): Promise<void> {
  await page.getByTestId('designer-tab-blocks').click();
  const item = page.getByTestId(`block-palette-item-${blockType}`);
  await expect(item).toBeVisible({ timeout: 5_000 });
  await item.click();
}

/**
 * Select the Nth "Section Title" outline button. Outline order is the order
 * blocks were added, after the default Placeholder (index 0).
 *
 * `sectionIndex` is the 0-based index among reference sections (0 .. 8).
 */
async function selectSectionViaOutline(page: Page, sectionIndex: number): Promise<void> {
  await page.getByTestId('designer-tab-outline').click();
  const outlineButtons = page.locator('button:has-text("Section Title")');
  await expect(outlineButtons.first()).toBeVisible({ timeout: 5_000 });
  await outlineButtons.nth(sectionIndex).click();
}

/**
 * Set the block title through the BlockPropertyPanel. The reference uses
 * LocalizedText objects {"zh-CN": "...", "en-US": "..."}. The designer's
 * title editor likely exposes a single string input (and perhaps a locale
 * switcher). We fill whichever locale inputs it surfaces — if only a single
 * raw string, we use the zh-CN value.
 */
async function setSectionTitle(page: Page, title: any): Promise<void> {
  const panel = page.getByTestId('designer-properties-panel');

  // Try label="标题" or placeholder containing "标题"
  const titleInput = panel
    .locator('label:has-text("标题") >> xpath=following-sibling::input[1], input[placeholder*="标题"]')
    .first();

  const zhValue = typeof title === 'string' ? title : title?.['zh-CN'] ?? '';
  if (await titleInput.isVisible({ timeout: 1_500 }).catch(() => false)) {
    await titleInput.click();
    await titleInput.fill(zhValue);
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
 * After adding fields to a section, select a specific field on the canvas to
 * open its FieldPropertyEditor, then configure colSpan / readOnly / visibleWhen.
 */
async function configureFieldOverride(
  page: Page,
  sectionIndex: number,
  field: { field: string; colSpan?: number; readOnly?: boolean; visibleWhen?: string },
): Promise<void> {
  // The Placeholder section is at canvas index 0, so reference section N is at
  // canvas index N+1.
  const block = page.locator('[data-block-type="form-section"]').nth(sectionIndex + 1);
  await expect(block).toBeVisible({ timeout: 5_000 });
  const fieldLabel = block.locator(`label:has-text("${field.field}")`).first();
  await expect(fieldLabel).toBeVisible({ timeout: 5_000 });
  await fieldLabel
    .locator('xpath=ancestor::div[contains(@class,"group/field")]')
    .first()
    .click();

  const panel = page.getByTestId('designer-properties-panel');
  await expect(panel.locator('text=字段属性')).toBeVisible({ timeout: 5_000 });

  // colSpan: if reference specifies a value, set it. Look for "列宽" or "colSpan"
  // input.
  if (field.colSpan !== undefined) {
    const colSpanInput = panel
      .locator(
        'label:has-text("列宽") >> xpath=following-sibling::input[1], label:has-text("colSpan") >> xpath=following-sibling::input[1], input[placeholder*="列"]',
      )
      .first();
    if (await colSpanInput.isVisible({ timeout: 1_500 }).catch(() => false)) {
      await colSpanInput.click();
      await colSpanInput.fill(String(field.colSpan));
    }
  }

  // readOnly: SmartSwitch labeled "只读" in validation or behavior section.
  if (field.readOnly) {
    const readOnlySwitch = panel
      .locator('span:has-text("只读") >> xpath=following-sibling::button[@role="switch"]')
      .first();
    if (await readOnlySwitch.isVisible({ timeout: 1_500 }).catch(() => false)) {
      await readOnlySwitch.click();
      await expect(readOnlySwitch)
        .toHaveAttribute('aria-checked', 'true', { timeout: 3_000 })
        .catch(() => null);
    }
  }

  // visibleWhen: expand 行为控制 then fill "可见性条件".
  if (field.visibleWhen) {
    const header = panel.locator('button:has-text("行为控制")').first();
    if (await header.isVisible({ timeout: 1_500 }).catch(() => false)) {
      await header.click();
    }
    const input = panel
      .locator('label:has-text("可见性条件") >> xpath=following-sibling::input[1]')
      .first();
    if (await input.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await input.click();
      await input.fill(field.visibleWhen);
    }
  }

  // Return to block view so next section interactions start clean.
  await panel
    .locator('button:has-text("返回 Block")')
    .first()
    .click()
    .catch(() => null);
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

  // Try to promote submit → primary (reference uses primary:true).
  const submit = buttons.find((b) => (b.code ?? b.action) === 'submit');
  if (submit?.primary) {
    const submitHeader = panel.locator('div.cursor-pointer:has-text("提交")').first();
    if (await submitHeader.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await submitHeader.click();
      const typeSelect = panel
        .locator('label:has-text("按钮类型") >> xpath=following-sibling::select[1]')
        .first();
      if (await typeSelect.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await typeSelect.selectOption('primary').catch(() => null);
      }
    }
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

    // -------- Phase 1: add form-section blocks ----------
    for (let i = 0; i < refFormSections.length; i++) {
      await addBlockViaPalette(page, 'form-section');
    }

    // Wait for canvas to show placeholder + N added sections = N+1 form-sections.
    const sectionLocator = page.locator('[data-block-type="form-section"]');
    await expect(sectionLocator).toHaveCount(refFormSections.length + 1, { timeout: 5_000 });

    // -------- Phase 2: per-section title + fields + field overrides ----------
    for (let s = 0; s < refFormSections.length; s++) {
      const refSection = refFormSections[s];

      await selectSectionViaOutline(page, s);
      await setSectionTitle(page, refSection.title);

      const codes = (refSection.fields ?? []).map((f) => f.field);
      await addFieldsToSelectedBlock(page, codes);

      for (const refField of refSection.fields ?? []) {
        await configureFieldOverride(page, s, refField);
      }
    }

    // -------- Phase 3: form-buttons ----------
    if (refFormButtons) {
      await addBlockViaPalette(page, 'form-buttons');
      await page.getByTestId('designer-tab-outline').click();
      const fbOutline = page
        .locator('button:has-text("form-buttons"), button:has-text("Form Buttons")')
        .first();
      await expect(fbOutline).toBeVisible({ timeout: 5_000 });
      await fbOutline.click();
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

    // Hard assertion: byte-for-byte equality (minus ignored system keys).
    expect(diffs, `designer-rebuilt blocks must deep-equal reference (diffs=${diffs.length})`).toEqual([]);
  });
});
