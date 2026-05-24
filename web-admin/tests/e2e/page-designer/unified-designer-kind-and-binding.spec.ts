/**
 * Regression spec for the unified-designer overhaul (PR feat/unified-designer-overhaul).
 *
 * Guards the three headline fixes on a real form-kind page:
 *  - canvas band shows the localized page-kind label (表单), not the old
 *    hardcoded "Composite canvas"
 *  - the Blocks palette collapses to the page kind: a form page exposes form
 *    blocks only (no List/Detail/Dashboard), and never the bare placeholder
 *    leaf blocks (field/column/filter-field)
 *  - dragging a model field from the Fields library binds it as a real field
 *    block via @dnd-kit (the drag layer that unit tests mock out)
 *
 * The form page is discovered from /api/pages so the spec is portable across
 * seeds. Default UI locale is zh-CN.
 *
 * Dimensions: D1 (auth/session), D6 (designer canvas), D9 (regression guard)
 */

import { test, expect } from '../../fixtures';

async function findFormPageKey(page: import('@playwright/test').Page): Promise<string | null> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  return page.evaluate(async () => {
    const res = await fetch('/api/pages?pageSize=300', { headers: { Accept: 'application/json' } });
    if (!res.ok) return null;
    const json = await res.json();
    const data = json?.data?.records ?? json?.data ?? [];
    const arr = Array.isArray(data) ? data : (data.records ?? []);
    const form = arr.find((p: { kind?: string }) => p?.kind === 'form');
    return form?.pageKey ?? null;
  });
}

/**
 * Open the unified designer for a page key. On a cold dev Vite the very first
 * navigation can race optimizeDeps and render a transient "Application Error";
 * reload once (Vite is warm by then) before asserting on the workbench.
 */
async function openDesigner(page: import('@playwright/test').Page, pageKey: string) {
  const workbench = page.getByTestId('unified-designer-workbench');
  const attempts = 4;
  for (let i = 0; i < attempts; i++) {
    if (i === 0) {
      await page.goto(`/unified-designer?pageKey=${pageKey}`, { waitUntil: 'domcontentloaded' });
    } else {
      // Cold dev Vite re-runs optimizeDeps on the heavy designer route and can
      // render a transient "Application Error" until it settles; reload until ready.
      await page.reload({ waitUntil: 'domcontentloaded' });
    }
    try {
      await workbench.waitFor({ state: 'visible', timeout: i === attempts - 1 ? 45000 : 15000 });
      return;
    } catch {
      if (i === attempts - 1) throw new Error('unified-designer-workbench never became visible');
      await page.waitForTimeout(2000);
    }
  }
}

test.describe('Unified designer — kind collapse, i18n, model binding', () => {
  // The designer route pulls a heavy dep graph (@dnd-kit, lucide, react-router
  // framework). On a cold dev Vite the first load triggers optimizeDeps and can
  // need a couple of reloads to settle, which exceeds the default per-test budget.
  test.describe.configure({ timeout: 120_000 });

  test('a form page collapses the palette and renders zh-CN copy', async ({ page }) => {
    const formKey = await findFormPageKey(page);
    test.skip(!formKey, 'no form-kind page seeded in this environment');

    await openDesigner(page, formKey!);

    // Canvas band shows the localized form kind label, not the old Composite text.
    const band = page.getByTestId('canvas-root-drop-zone');
    await expect(band).toContainText('表单');
    await expect(band).not.toContainText('组合页面');
    await expect(band).not.toContainText('Composite');

    // zh-CN designer chrome.
    await expect(page.getByTestId('resource-tab-blocks')).toHaveText('区块');

    // Palette collapses to the form kind; other page kinds + placeholder leaves absent.
    await page.getByTestId('resource-tab-blocks').click();
    await expect(page.getByTestId('palette-add-form-section')).toBeVisible();
    await expect(page.getByTestId('palette-add-list')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-detail')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-dashboard')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-field')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-column')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-filter-field')).toHaveCount(0);
  });

  test('dragging a model field into a section binds a field block via @dnd-kit', async ({ page }) => {
    const formKey = await findFormPageKey(page);
    test.skip(!formKey, 'no form-kind page seeded in this environment');

    await openDesigner(page, formKey!);

    // Wait for the outline tree to populate before querying it.
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible({
      timeout: 15000,
    });

    // Select the first form-section from the outline.
    const sectionItem = page
      .locator('button[data-testid^="outline-item-"]')
      .filter({ hasText: 'form-section' })
      .first();
    test.skip((await sectionItem.count()) === 0, 'form page has no form-section');
    const sectionTestId = await sectionItem.getAttribute('data-testid');
    const sectionId = sectionTestId!.replace('outline-item-', '');
    await sectionItem.click();

    // Open the Fields library; model fields load async, so wait before deciding.
    await page.getByTestId('resource-tab-fields').click();
    await page
      .locator('[data-testid^="model-field-"]')
      .first()
      .waitFor({ state: 'visible', timeout: 10000 })
      .catch(() => {});
    const fieldItem = page.locator('[data-testid^="model-field-"][data-used="false"]').first();
    test.skip((await fieldItem.count()) === 0, 'no bindable model field for this page');

    const beforeFields = await page.locator('[data-testid^="canvas-block-field_"]').count();

    // Real @dnd-kit pointer drag: field item -> section canvas block.
    const target = page.getByTestId(`canvas-block-${sectionId}`);
    const src = await fieldItem.boundingBox();
    const dst = await target.boundingBox();
    expect(src && dst).toBeTruthy();
    await page.mouse.move(src!.x + src!.width / 2, src!.y + src!.height / 2);
    await page.mouse.down();
    await page.mouse.move(src!.x + src!.width / 2 + 12, src!.y + src!.height / 2 + 12, { steps: 6 });
    await page.mouse.move(dst!.x + dst!.width / 2, dst!.y + dst!.height / 2, { steps: 14 });
    await page.mouse.move(dst!.x + dst!.width / 2 + 3, dst!.y + dst!.height / 2 + 3, { steps: 4 });
    await page.mouse.up();

    await expect
      .poll(async () => page.locator('[data-testid^="canvas-block-field_"]').count())
      .toBeGreaterThan(beforeFields);
  });

  // Guards Playwright `.dragTo()` compatibility with @dnd-kit. The wider designer
  // E2E suite (unified-designer-workbench UDW-*) drives drags via `.dragTo()`,
  // whose single jump-move pointerWithin can miss — the workbench's
  // pointerWithin→closestCenter fallback is what keeps those green.
  test('binds a model field via Playwright .dragTo() (UDW drag-driver guard)', async ({ page }) => {
    const formKey = await findFormPageKey(page);
    test.skip(!formKey, 'no form-kind page seeded in this environment');

    await openDesigner(page, formKey!);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible({ timeout: 15000 });

    const sectionItem = page
      .locator('button[data-testid^="outline-item-"]')
      .filter({ hasText: 'form-section' })
      .first();
    test.skip((await sectionItem.count()) === 0, 'form page has no form-section');
    const sectionId = (await sectionItem.getAttribute('data-testid'))!.replace('outline-item-', '');
    await sectionItem.click();

    await page.getByTestId('resource-tab-fields').click();
    const fieldItem = page.locator('[data-testid^="model-field-"][data-used="false"]').first();
    await fieldItem.waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
    test.skip((await fieldItem.count()) === 0, 'no bindable model field for this page');

    const before = await page.locator('[data-testid^="canvas-block-field_"]').count();
    await fieldItem.dragTo(page.getByTestId(`canvas-block-${sectionId}`));
    await expect
      .poll(() => page.locator('[data-testid^="canvas-block-field_"]').count())
      .toBeGreaterThan(before);
  });
});
