/**
 * Phase 2 — Page creation + kind-dispatch E2E (showcase plugin coverage matrix).
 *
 * Covers:
 *   P2.1 — `/p/page_schema/new` form happy path for all three kinds (list / form
 *          / detail). Validates that `model_code` is required and that the
 *          submit succeeds against `pgm:create_page_schema`.
 *   P2.2 — `/page-designer/{pid}` dispatches to the correct designer body for
 *          each kind:
 *              list   -> `list-config-panel`
 *              detail -> `detail-config-panel`
 *              form   -> `designer-canvas` + `designer-tab-fields`
 *
 * Plan: docs/plans/2026-04/2026-04-18-e2e-showcase-allfields-plan.md (Phase 2).
 *
 * Red lines honoured:
 *   - All navigation goes through the sidebar menu (NO page.goto to deep links;
 *     the row link inside the list opens the designer naturally via UI click).
 *   - No waitForTimeout (uses waitForResponse / toBeVisible).
 *   - Each created page_schema is deleted in afterEach via DELETE /api/pages/{pid}.
 *   - Test body click/fill counts > page.request counts (E2E, not API test).
 */

import { test, expect, type Page } from '../../fixtures';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function uniquePageKey(prefix: string): string {
  const ts = Date.now();
  const rnd = Math.random().toString(36).slice(2, 6);
  return `${prefix}_${ts}_${rnd}`;
}

const SHOWCASE_MODEL_CODE = 'showcase_all_fields';

/**
 * Navigate to the page_schema list page (`/p/page_schema`) through the sidebar
 * menu. Page schema management lives under the meta_management parent menu.
 */
async function navigateToPageSchemaListViaMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});

  // Expand the meta_management parent. i18n key may render as
  // "menu.meta_management" if the locale dict is not loaded.
  const parent = page
    .locator('button', { hasText: /元数据管理|Metadata|menu\.meta_management/i })
    .first();
  await parent.waitFor({ state: 'visible', timeout: 10_000 });
  await parent.evaluate((el: HTMLElement) => el.click());

  // Click the "页面配置" / "Page Configuration" leaf.
  const leaf = page.locator('a[href="/p/page_schema"], a[href*="/p/page_schema"]').first();
  await leaf.waitFor({ state: 'attached', timeout: 5_000 });
  const listResp = page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/page-render/dynamic/page_schema_list/list') ||
      (r.url().includes('/dynamic/page_schema_list') && r.url().includes('/list')),
    { timeout: 5_000 },
  );
  await leaf.evaluate((el: HTMLElement) => el.click());
  await listResp.catch(() => null);

  // The new-page toolbar button is the most stable signal that we landed.
  await expect(page.getByTestId('toolbar-btn-create')).toBeVisible({ timeout: 8_000 });

  // Dismiss any Vite HMR error overlay that intercepts pointer events.
  await page.evaluate(() => {
    document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
  });
}

// Field accessors. The page_schema_form route (a standard CRUD form) does NOT
// emit `data-testid="field-{code}"` at this commit — it uses accessible labels
// rendered as <textbox aria-label="..."> / <combobox>. We select by visible
// label text via `getByLabel` (matches HTML label & aria-label).
function nameInput(page: Page) {
  // ab_meta_field stores generic displayName "名称" (not page-manager-specific
  // "页面名称"); fields.json `displayName:zh-CN` localized variant is not
  // currently picked up by the import. Match both for forward-compat.
  return page.getByLabel(/^名称\*?$|^页面名称\*?$|^Name$|^Page Name$/).first();
}
function pageKeyInput(page: Page) {
  return page.getByLabel(/^页面标识\*?$|^Page Key$/).first();
}
function modelCodeInput(page: Page) {
  // Generic displayName is "模型编码"; "主 Model" / "Primary Model" not active.
  return page.getByLabel(/^模型编码\*?$|^Model Code$|主\s*Model|Primary Model/).first();
}
function descriptionInput(page: Page) {
  return page.getByLabel(/^描述$|^Description$/).first();
}
function kindCombobox(page: Page) {
  // Two comboboxes per <select> (visible + native): we want the native <select>.
  return page
    .locator('label:has-text("页面类型"), label:has-text("Page Kind")')
    .locator('..')
    .locator('select')
    .first();
}

/**
 * Click the "新建页面" toolbar button and wait for the form route to mount.
 */
async function clickCreateButton(page: Page): Promise<void> {
  const btn = page.getByTestId('toolbar-btn-create');
  await expect(btn).toBeVisible({ timeout: 5_000 });
  await btn.click();
  await expect(nameInput(page)).toBeVisible({ timeout: 8_000 });
}

interface CreatePageOptions {
  name: string;
  pageKey: string;
  kind: 'list' | 'form' | 'detail';
  modelCode: string;
  description?: string;
}

/**
 * Fill the create-page form, submit, and wait for backend acknowledgement.
 * Returns the create POST response so the test can assert + extract pid.
 */
async function fillAndSubmitCreateForm(page: Page, opts: CreatePageOptions): Promise<{
  pid: string;
  pageKey: string;
}> {
  // Name
  await nameInput(page).click();
  await nameInput(page).fill(opts.name);

  // Page key
  await pageKeyInput(page).click();
  await pageKeyInput(page).fill(opts.pageKey);

  // Kind enum (native <select>)
  await kindCombobox(page).selectOption(opts.kind);

  // Model code (free text)
  await modelCodeInput(page).click();
  await modelCodeInput(page).fill(opts.modelCode);

  if (opts.description) {
    const desc = descriptionInput(page);
    if (await desc.isVisible({ timeout: 1_000 }).catch(() => false)) {
      await desc.fill(opts.description);
    }
  }

  // Submit. The form-buttons block emits a "submit" button bound to
  // pgm:create_page_schema. After success the page redirects to
  // /page-designer/{pid} per pages.json `extension.afterSubmitRedirect`.
  const createResp = page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/commands/execute') &&
      r.request().method() === 'POST' &&
      r.status() < 500,
    { timeout: 15_000 },
  );

  const submitBtn = page
    .locator(
      'button:has-text("创建并进入编辑器"), button:has-text("Create & Open Editor"), button:has-text("submit"), button:has-text("Submit"), button:has-text("提交")',
    )
    .first();
  await submitBtn.click();
  const resp = await createResp;
  expect(resp.ok()).toBe(true);

  const body = await resp.json().catch(() => ({}) as Record<string, unknown>);
  // Command response shape:
  //   { code: '0', data: { commandCode, data: { recordId, ... } } }
  const outer = (body as { data?: { data?: { recordId?: string } } }).data;
  const pid = outer?.data?.recordId;
  expect(pid, 'create command response should include data.data.recordId').toBeTruthy();
  return { pid: pid!, pageKey: opts.pageKey };
}

// ---------------------------------------------------------------------------
// Cleanup tracking — DELETE per-test in afterEach (no afterAll allowed).
// ---------------------------------------------------------------------------

const createdPagePids: string[] = [];

test.describe('Phase 2 — Page creation + kind dispatch E2E', () => {
  test.afterEach(async ({ page }) => {
    while (createdPagePids.length > 0) {
      const pid = createdPagePids.pop()!;
      await page.request.delete(`/api/pages/${pid}`).catch(() => null);
    }
  });

  // -------------------------------------------------------------------------
  // P2.1a — modelCode required validation (negative path)
  // -------------------------------------------------------------------------
  test('P2.1a: modelCode required → submit blocked / error surfaced', async ({ page }) => {
    await navigateToPageSchemaListViaMenu(page);
    await clickCreateButton(page);

    // Fill everything except model_code.
    const pageKey = uniquePageKey('e2e_pg_neg');
    await nameInput(page).fill('E2E Negative');
    await pageKeyInput(page).fill(pageKey);
    await kindCombobox(page).selectOption('list');

    // Try to submit. The runtime form should either:
    //  - block submit (button disabled / no POST), OR
    //  - POST and the server returns a non-2xx with a validation message.
    let postFired = false;
    const onReq = (req: import('@playwright/test').Request) => {
      if (req.url().includes('/api/meta/commands/execute') && req.method() === 'POST') {
        postFired = true;
      }
    };
    page.on('request', onReq);

    // Submit button: the form-buttons block emits a button with literal text
    // "submit" / "cancel" (i18n key not resolved for these codes in this build).
    const submitBtn = page
      .locator(
        'button:has-text("创建并进入编辑器"), button:has-text("Create & Open Editor"), button:has-text("submit"), button:has-text("Submit"), button:has-text("提交")',
      )
      .first();
    await submitBtn.click().catch(() => null);

    // Validation should surface either as a field-level error indicator on
    // model_code or a global toast / banner. We assert via visible text.
    const errorLocator = page
      .locator('text=/必填|required|不能为空|cannot be (empty|null|blank)/i')
      .first();

    const errorVisible = await errorLocator
      .isVisible({ timeout: 3_000 })
      .catch(() => false);

    page.off('request', onReq);

    // We accept either: client-side validation (no POST) OR a visible error.
    // A "silent success" — POST fired AND success redirect — is a fail.
    const stillOnFormUrl = /\/p\/page_schema\/new(?:$|\?)/.test(page.url());
    expect(
      errorVisible || (!postFired && stillOnFormUrl),
      `Expected validation: errorVisible=${errorVisible} postFired=${postFired} url=${page.url()}`,
    ).toBe(true);
  });

  // -------------------------------------------------------------------------
  // P2.1b + P2.2 — Create one page per kind, then verify designer dispatch.
  // -------------------------------------------------------------------------
  for (const kind of ['list', 'form', 'detail'] as const) {
    test(`P2.1b/${kind} + P2.2/${kind}: create ${kind} page → designer dispatches correct panel`, async ({
      page,
    }) => {
      const pageKey = uniquePageKey(`e2e_pg_${kind}`);
      const name = `E2E ${kind} ${pageKey}`;

      // ---- P2.1b: create through the UI form ----
      await navigateToPageSchemaListViaMenu(page);
      await clickCreateButton(page);
      const { pid } = await fillAndSubmitCreateForm(page, {
        name,
        pageKey,
        kind,
        modelCode: SHOWCASE_MODEL_CODE,
        description: `Phase 2 E2E ${kind}`,
      });
      createdPagePids.push(pid);

      // ---- P2.2: navigate to the designer via the list-row click ----
      // The create form returns to the list. Find the new row by page_key and
      // click the row link — this exercises the DSL `detailUrl` config rather
      // than typing the URL ourselves.
      await expect(page).toHaveURL(/\/p\/page_schema(\?|$)/, { timeout: 10_000 });

      // Dismiss Vite overlay (HMR warnings can mask interaction in dev mode).
      await page.evaluate(() => {
        document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
      });

      // Search for the new page_key so the row appears on page 1.
      const keywordInput = page
        .locator('input[placeholder*="搜索"], input[placeholder*="Search"], input[type="search"]')
        .first();
      if (await keywordInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await keywordInput.click();
        await keywordInput.fill(pageKey);
        await keywordInput.press('Enter').catch(() => null);
        await page
          .waitForResponse(
            (r) => r.url().includes('/dynamic/page_schema_list') && r.status() === 200,
            { timeout: 5_000 },
          )
          .catch(() => null);
      }

      // The row's "edit" rowAction navigates to /page-designer/{pid} per pages.json.
      // Click the row containing our page_key (text appears in the page_key cell).
      const row = page.locator(`tr:has-text("${pageKey}")`).first();
      await expect(row).toBeVisible({ timeout: 8_000 });

      // Prefer clicking the visible row link; fallback to the edit rowAction button.
      const rowLink = row.locator('a[href*="/page-designer/"]').first();
      if (await rowLink.isVisible({ timeout: 1_000 }).catch(() => false)) {
        await rowLink.click();
      } else {
        await row.click();
      }

      await expect(page).toHaveURL(new RegExp(`/page-designer/${pid}`), {
        timeout: 10_000,
      });

      // Dismiss overlay again post-navigation.
      await page.evaluate(() => {
        document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
      });

      if (kind === 'list') {
        await expect(page.getByTestId('list-config-panel')).toBeVisible({ timeout: 10_000 });
        // detail-config-panel must NOT render for list pages — divergence assertion.
        await expect(page.getByTestId('detail-config-panel')).toHaveCount(0);
      } else if (kind === 'detail') {
        await expect(page.getByTestId('detail-config-panel')).toBeVisible({ timeout: 10_000 });
        await expect(page.getByTestId('list-config-panel')).toHaveCount(0);
      } else {
        // form -> BlocksDesigner: assert canvas + the fields tab button.
        await expect(page.getByTestId('designer-canvas')).toBeVisible({ timeout: 10_000 });
        await expect(page.getByTestId('designer-tab-fields')).toBeVisible();
        await expect(page.getByTestId('list-config-panel')).toHaveCount(0);
        await expect(page.getByTestId('detail-config-panel')).toHaveCount(0);
      }
    });
  }
});
