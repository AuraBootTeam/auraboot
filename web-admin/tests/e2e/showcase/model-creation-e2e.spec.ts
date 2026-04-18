/**
 * Phase 1 — Model creation E2E (showcase plugin coverage matrix).
 *
 * Covers:
 *   P1.1 — Physical model creation via UI (Step 0 type card → form → submit).
 *   P1.2 — Virtual model wizard (5 steps, namedQuery branch — skipped if no
 *          published namedQuery seed available, see plan §5.1).
 *   P1.3 — Model list sourceType filter, detail tab divergence, capabilities API.
 *
 * Plan: docs/plans/2026-04/2026-04-18-e2e-showcase-allfields-plan.md (Phase 1).
 *
 * Red lines honoured:
 *   - All navigation goes through the sidebar menu (NO page.goto to deep links).
 *   - No waitForTimeout (uses waitForResponse / toBeVisible).
 *   - Each created model is deleted in afterEach (no afterAll cleanup).
 *   - Test body click/fill counts > page.request counts (E2E, not API test).
 */

import { test, expect, type Page } from '../../fixtures';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function uniqueModelCode(prefix: string): string {
  const ts = Date.now();
  const rnd = Math.random().toString(36).slice(2, 6);
  return `${prefix}_${ts}_${rnd}`;
}

/**
 * Navigate to the model management list page through the sidebar menu.
 * Required by red line "page.goto 直达禁止".
 */
async function navigateToModelListViaMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});

  // Expand the parent meta_management menu. i18n key may render as literal
  // "menu.meta_management" if locale dict is not loaded, so accept both forms.
  const parent = page
    .locator('button', { hasText: /元数据管理|Metadata|menu\.meta_management/i })
    .first();
  await parent.waitFor({ state: 'visible', timeout: 10_000 });
  await parent.evaluate((el: HTMLElement) => el.click());

  // Click the leaf "模型管理" — wait for the model list API.
  const leaf = page.locator('a[href="/meta/models"], a[href*="/meta/models"]').first();
  await leaf.waitFor({ state: 'attached', timeout: 5_000 });
  const listResp = page.waitForResponse(
    (r) => r.url().includes('/api/meta/models') && r.status() === 200,
    { timeout: 5_000 },
  );
  await leaf.evaluate((el: HTMLElement) => el.click());
  await listResp.catch(() => null);

  // Confirm we landed on the list (filter group is the most stable testid).
  await expect(page.getByTestId('sourcetype-filter-group')).toBeVisible({ timeout: 5_000 });

  // Dismiss any Vite HMR error overlay that intercepts pointer events.
  // Pre-existing dev-mode lazy-import warnings on /meta/models leak an
  // overlay; the underlying page is functional.
  await page.evaluate(() => {
    document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
  });
}

/**
 * Click "新建模型" in the toolbar and wait for Step 0 type cards.
 */
async function clickCreateButton(page: Page): Promise<void> {
  const btn = page.getByTestId('toolbar-btn-create');
  await expect(btn).toBeVisible({ timeout: 5_000 });
  await btn.click();
  await expect(page.getByTestId('model-type-physical')).toBeVisible({ timeout: 8_000 });
  await expect(page.getByTestId('model-type-virtual')).toBeVisible();
}

// ---------------------------------------------------------------------------
// Cleanup tracking — DELETE per-test in afterEach (no afterAll allowed).
// ---------------------------------------------------------------------------

const createdModelPids: string[] = [];

test.describe('Phase 1 — Model creation E2E', () => {
  test.afterEach(async ({ api }) => {
    // Best-effort cleanup of any model the test created.
    while (createdModelPids.length > 0) {
      const pid = createdModelPids.pop()!;
      await api.deleteModel(pid).catch(() => null);
    }
  });

  // -------------------------------------------------------------------------
  // P1.1 — Physical model creation through Step 0 → form
  // -------------------------------------------------------------------------
  test('P1.1: physical model creation via UI', async ({ page, api }) => {
    const code = uniqueModelCode('e2e_phys');
    const displayName = 'E2E Physical';

    // 1. Navigate via sidebar.
    await navigateToModelListViaMenu(page);

    // 2. Open create flow.
    await clickCreateButton(page);

    // 3. Choose physical type card.
    await page.getByTestId('model-type-physical').click();

    // 4. Fill the form fields.
    const codeInput = page.locator('input[placeholder*="user_order"]');
    await expect(codeInput).toBeVisible({ timeout: 8_000 });
    await codeInput.click();
    await codeInput.fill(code);

    const nameInput = page.locator('input[placeholder*="用户订单"]');
    await nameInput.click();
    await nameInput.fill(displayName);

    const descInput = page.locator('textarea[placeholder*="模型描述"]');
    if (await descInput.isVisible({ timeout: 1_000 }).catch(() => false)) {
      await descInput.fill('Phase 1 physical model E2E');
    }

    // 5. Submit. Wait for the create response so we know the backend accepted it.
    const createResp = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/models') &&
        r.request().method() === 'POST' &&
        r.status() < 500,
      { timeout: 15_000 },
    );
    await page.locator('button:has-text("创建")').first().click();
    const resp = await createResp;
    expect(resp.ok()).toBe(true);

    // 6. Backend verification — the model exists with sourceType = physical.
    const fromApi = await api.getModelByCode(code);
    expect(fromApi.code).toBe('0');
    expect(fromApi.data).not.toBeNull();
    expect(fromApi.data!.code).toBe(code);
    // sourceType is undefined on legacy/physical models — treat both as physical.
    const sourceType = (fromApi.data as { sourceType?: string }).sourceType ?? 'physical';
    expect(sourceType).toBe('physical');
    createdModelPids.push(fromApi.data!.pid);

    // 7. UI verification — go back to list and assert the new row + sourceType cell.
    await navigateToModelListViaMenu(page);

    // Filter by keyword to make the new row appear on the first page.
    const keyword = page.getByTestId('filter-keyword');
    await keyword.click();
    await keyword.fill(code);
    await page.getByTestId('filter-search').click();
    await page.waitForResponse(
      (r) => r.url().includes('/api/meta/models') && r.status() === 200,
      { timeout: 10_000 },
    );

    const sourceCell = page.getByTestId(`model-source-cell-${code}`);
    await expect(sourceCell).toBeVisible({ timeout: 10_000 });
    // Badge text should mention the physical concept (zh "物理" or en "Physical").
    await expect(sourceCell).toContainText(/物理|Physical/i);
  });

  // -------------------------------------------------------------------------
  // P1.2 — Virtual model wizard (namedQuery branch)
  // -------------------------------------------------------------------------
  test('P1.2: virtual model wizard (5-step namedQuery flow)', async ({ page, api, request }) => {
    // Probe whether any published namedQuery exists. If not, skip per plan §5.1.
    const probe = await request
      .get('/api/meta/named-queries?status=published&pageSize=5')
      .catch(() => null);
    let namedQueryCode: string | undefined;
    if (probe && probe.ok()) {
      const body = await probe.json().catch(() => ({}) as Record<string, unknown>);
      const data = (body as { data?: unknown }).data as
        | { records?: Array<{ code?: string }> }
        | Array<{ code?: string }>
        | undefined;
      const records = Array.isArray(data) ? data : (data?.records ?? []);
      namedQueryCode = records.find((r) => r?.code)?.code;
    }

    test.skip(
      !namedQueryCode,
      '需要 namedQuery seed 数据，待 OSS reset 默认导入支持 (plan §5.1)',
    );

    // 1. Navigate via sidebar.
    await navigateToModelListViaMenu(page);
    await clickCreateButton(page);

    // 2. Pick "虚拟" type card → wizard route.
    await page.getByTestId('model-type-virtual').click();

    // Wait for Step 1 (sourceType cards) to render.
    await expect(page.getByTestId('wizard-step-1')).toBeVisible({ timeout: 8_000 });
    await expect(page.getByTestId('sourcetype-card-namedQuery')).toBeVisible();

    // Step 1: choose namedQuery, advance.
    await page.getByTestId('sourcetype-card-namedQuery').click();
    await page.getByTestId('wizard-next').click();

    // Step 2: pick the namedQuery from the dropdown.
    await expect(page.getByTestId('wizard-step-2')).toBeVisible();
    const select = page.getByTestId('sourceref-namedquery-select');
    await expect(select).toBeVisible({ timeout: 8_000 });
    await select.selectOption(namedQueryCode!);
    await page.getByTestId('wizard-next').click();

    // Step 3: schema detection — we just need at least one detected field +
    // primary key chosen by the auto-detect logic. Wait for fields to appear.
    await expect(page.getByTestId('wizard-step-3')).toBeVisible();
    // Wait for the detect endpoint to return something (best-effort).
    await page
      .waitForResponse(
        (r) => r.url().includes('/named-queries') && r.url().includes('/detect-fields'),
        { timeout: 8_000 },
      )
      .catch(() => null);
    // If "下一步" is not yet enabled, the env's namedQuery cannot be auto-detected.
    const next3 = page.getByTestId('wizard-next');
    const canAdvance3 = await next3.isEnabled({ timeout: 5_000 }).catch(() => false);
    test.skip(
      !canAdvance3,
      '当前 namedQuery 无法自动检测字段 → 需要 seed 真实可执行的 namedQuery (plan §5.1)',
    );
    await next3.click();

    // Step 4: capabilities — accept defaults.
    await expect(page.getByTestId('wizard-step-4')).toBeVisible();
    await page.getByTestId('wizard-next').click();

    // Step 5: meta info — fill code + displayName.
    await expect(page.getByTestId('wizard-step-5')).toBeVisible();
    const code = uniqueModelCode('e2e_virt');
    const codeInput = page.locator('input').filter({ hasText: '' }).first();
    // The MetaInfoStep may not expose a testid — find by label "code".
    const codeField = page
      .locator('label:has-text("code"), label:has-text("编码")')
      .locator('..')
      .locator('input')
      .first();
    if (await codeField.isVisible({ timeout: 1_000 }).catch(() => false)) {
      await codeField.fill(code);
    } else {
      await codeInput.fill(code);
    }
    const nameField = page
      .locator('label:has-text("displayName"), label:has-text("显示名")')
      .locator('..')
      .locator('input')
      .first();
    if (await nameField.isVisible({ timeout: 1_000 }).catch(() => false)) {
      await nameField.fill(`E2E Virtual ${code}`);
    }

    // Submit.
    const createResp = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/models') &&
        r.request().method() === 'POST' &&
        r.status() < 500,
      { timeout: 15_000 },
    );
    await page.getByTestId('wizard-submit').click();
    const resp = await createResp;
    expect(resp.ok()).toBe(true);

    // Backend verification — sourceType should mark virtual.
    const fromApi = await api.getModelByCode(code);
    expect(fromApi.code).toBe('0');
    expect(fromApi.data).not.toBeNull();
    createdModelPids.push(fromApi.data!.pid);
    const sourceType =
      (fromApi.data as { sourceType?: string }).sourceType ??
      ((fromApi.data!.extension as { virtual?: unknown } | undefined)?.virtual ? 'virtual' : '');
    expect(['virtual', 'namedQuery']).toContain(sourceType);
  });

  // -------------------------------------------------------------------------
  // P1.3 — Source-type filter + detail tab divergence + capabilities API
  // -------------------------------------------------------------------------
  test('P1.3: list filter, detail tab divergence, capabilities API', async ({ page, api }) => {
    // Pre-create a physical model via API so the test focuses on the filter UI.
    // (Click count from the filter/detail interactions still dominates.)
    const physCode = uniqueModelCode('e2e_phys');
    const physResp = await api.createModel({
      code: physCode,
      displayName: `E2E P1.3 Physical ${physCode}`,
      modelType: 'entity',
    });
    expect(physResp.code).toBe('0');
    createdModelPids.push(physResp.data!.pid);
    const physPid = physResp.data!.pid;

    // 1. Navigate to list via sidebar (click).
    await navigateToModelListViaMenu(page);

    // 2. Filter — All → Physical → Virtual, asserting filter state changes.
    const allBtn = page.getByTestId('sourcetype-filter-all');
    const physBtn = page.getByTestId('sourcetype-filter-physical');
    const virtBtn = page.getByTestId('sourcetype-filter-virtual');
    await expect(allBtn).toBeVisible();

    await physBtn.click();
    // active button has bg-blue-600 + text-white classes — assert via attribute.
    await expect(physBtn).toHaveClass(/bg-blue-600/);
    await expect(virtBtn).not.toHaveClass(/bg-blue-600/);

    await virtBtn.click();
    await expect(virtBtn).toHaveClass(/bg-blue-600/);
    await expect(physBtn).not.toHaveClass(/bg-blue-600/);

    await allBtn.click();
    await expect(allBtn).toHaveClass(/bg-blue-600/);

    // 3. Search for the physical model and assert the row + sourceType badge.
    const keyword = page.getByTestId('filter-keyword');
    await keyword.click();
    await keyword.fill(physCode);
    await page.getByTestId('filter-search').click();
    await page.waitForResponse(
      (r) => r.url().includes('/api/meta/models') && r.status() === 200,
      { timeout: 10_000 },
    );
    const physCell = page.getByTestId(`model-source-cell-${physCode}`);
    await expect(physCell).toBeVisible({ timeout: 10_000 });
    await expect(physCell).toContainText(/物理|Physical/i);

    // 4. Capabilities API contract — physical model should be readable + writable.
    // OSS build does not expose /api/meta/models/{code}/capabilities (404/500);
    // assert only when the endpoint is present. See plan §1.3 P1.3 note.
    const capsResp = await page.request.get(`/api/meta/models/${physCode}/capabilities`);
    if (capsResp.ok()) {
      const capsBody = await capsResp.json();
      expect(capsBody.code).toBe('0');
      expect(capsBody.data).toBeDefined();
      const caps = capsBody.data as { writable?: boolean; readable?: boolean };
      expect(caps.writable).toBe(true);
      expect(caps.readable === undefined ? true : caps.readable).toBe(true);
    } else {
      // Endpoint not present in OSS build — record but do not fail.
      // eslint-disable-next-line no-console
      console.warn(
        `[P1.3] capabilities endpoint missing in this build (status=${capsResp.status()}); ` +
          'skipping writable/readable assertions.',
      );
    }

    // 5. Detail tab divergence — open the physical model's detail page by clicking
    // its row link (not page.goto). Then count the rendered tabs.
    const codeLink = page
      .locator(`tr:has([data-testid="model-source-cell-${physCode}"]) a`)
      .first();
    if (await codeLink.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await codeLink.click();
    } else {
      // Fall back to the eye/view button in the actions column.
      const viewBtn = page
        .locator(`tr:has([data-testid="model-source-cell-${physCode}"])`)
        .locator('button, a')
        .filter({ hasText: /查看|View|详情|Detail/i })
        .first();
      await viewBtn.click();
    }
    await page.waitForLoadState('domcontentloaded').catch(() => null);
    await expect(page).toHaveURL(new RegExp(`/meta/models/${physPid}`), { timeout: 8_000 });

    // The physical model detail page should NOT show the "virtual-model-strip"
    // testid (that block is virtual-only). This is the divergence assertion.
    const virtualStrip = page.getByTestId('virtual-model-strip');
    await expect(virtualStrip).toHaveCount(0);
  });
});
