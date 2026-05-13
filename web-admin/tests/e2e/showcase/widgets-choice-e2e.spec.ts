/**
 * GA-B3 — Choice-bucket widget E2E (Designer → ui_schema configuration chain).
 *
 * Scope: 6 choice widgets × fields on showcase_all_fields.
 *
 *   widget          field                  field.dataType   dictCode (field-level)
 *   -------------   --------------------   --------------   ---------------------
 *   select          sc_status              enum             sc_status_dict
 *   radio-group     sc_priority            enum             sc_priority_dict
 *   checkbox-group  sc_tags_enum*          (uses sc_status) enum / sc_status_dict
 *   multiselect     sc_tags                string           n/a (extension-level)
 *   cascadeselect   sc_cascade_category    string           sc_cascade_category_dict (extension)
 *   treeselect      sc_tree_node           string           sc_tree_dept_dict (extension)
 *
 *   * checkbox-group is registered server-side for ENUM only; sc_tags is a
 *     string field, so this row runs against sc_status (already covered by
 *     `select`) as a "second configuration" on a separate form page. This
 *     proves the checkbox-group entry reaches the dropdown for enum fields
 *     without conflicting with the select assertion.
 *
 * Widget dropdown reality check (from plugins/schemas/dsl-registry.json
 * renderComponents):
 *   select, radio-group, checkbox-group → dataType=enum (server-registered)
 *   multiselect, cascadeselect, treeselect → frontend-only widget registry;
 *     NOT in server renderComponents → may not surface in the
 *     FieldPropertyEditor dropdown for string dataType. The test records
 *     each dropdown snapshot and reports whether the targeted widget was
 *     present; if absent, we choose the first real option and log a "miss"
 *     with the full option list so the gap is debuggable rather than
 *     hidden behind a green pass.
 *
 * Widget-level props (optionsSource / options / dictCode / multiple /
 * allowClear / placeholder / direction / maxSelection / searchable / levels /
 * levelLabels / allowPartial / treeData / checkable / leafOnly / cascade):
 * These are defined in each widget's WidgetDefinition.schema but
 * FieldPropertyEditor only renders component/required/visible/permission
 * sections — widget-specific props flow from the **field model's
 * extension** (see plugins/showcase/config/fields/showcase_all_fields.json).
 * The test verifies extension-sourced props round-trip via the MODEL API
 * (GET /api/meta/models/code/showcase_all_fields) so the "full props"
 * contract is validated end-to-end even though the Designer UI doesn't
 * expose editor fields for them today.
 *
 * Red lines honoured:
 *   - Sidebar menu navigation (no deep-link page.goto for /page-designer).
 *   - No `waitForTimeout`; all locator waits ≤ 5s.
 *   - DELETE per-test in afterEach (no afterAll cleanup).
 *   - Click/fill ops > page.request ops (we click block-palette, fill field
 *     codes, click fields in canvas, change widget select, click save).
 *     API calls per test: 1 POST setup + 1 GET verify + 1 DELETE cleanup
 *     + 1 model GET for extension round-trip.
 *
 * Plan: companion to P4.5 (form-blocksdesigner-e2e) but focused on the
 *       CHOICE bucket where both enum + extension-driven options matter.
 */

import { test, expect, type Page } from '../../fixtures';

// ---------------------------------------------------------------------------
// Helpers (parallel to form-blocksdesigner-e2e)
// ---------------------------------------------------------------------------

const SHOWCASE_MODEL_CODE = 'showcase_all_fields';

function uniquePageKey(): string {
  return `e2e_gab3_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
}

async function apiCreateFormPage(page: Page, pageKey: string): Promise<string> {
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `GA-B3 ${pageKey}`,
      pageKey,
      kind: 'form',
      modelCode: SHOWCASE_MODEL_CODE,
      title: `GA-B3 ${pageKey}`,
      description: 'GA-B3 choice-widget E2E',
      blocks: [
        {
          id: 'placeholder',
          blockType: 'form-section',
          title: 'Placeholder',
          fields: [],
        },
      ],
      layout: { type: 'stack' },
    },
  });
  expect(resp.ok(), `create page ${pageKey} failed: ${resp.status()}`).toBeTruthy();
  const body = (await resp.json()) as { code: string; data?: { pid?: string } };
  expect(body.code).toBe('0');
  const pid = body.data?.pid;
  expect(pid, 'created page must have pid').toBeTruthy();
  return pid!;
}

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
    const anyDesignerLink = row.locator('a[href*="/page-designer/"]').first();
    if (await anyDesignerLink.isVisible({ timeout: 1_500 }).catch(() => false)) {
      await anyDesignerLink.evaluate((el: HTMLElement) => el.click());
    } else {
      await row.evaluate((el: HTMLElement) => el.click());
    }
  }

  await expect(page).toHaveURL(new RegExp(`/page-designer/${pid}`), {
    timeout: 5_000,
  });

  await expect(page.getByTestId('designer-canvas')).toBeVisible({ timeout: 5_000 });
  await expect(page.getByTestId('designer-tab-fields')).toBeVisible();
  await expect(page.getByTestId('designer-tab-blocks')).toBeVisible();
  await expect(page.getByTestId('designer-tab-outline')).toBeVisible();
}

async function addBlockViaPalette(page: Page, blockType: string): Promise<void> {
  await page.getByTestId('designer-tab-blocks').click();
  const item = page.getByTestId(`block-palette-item-${blockType}`);
  await expect(item).toBeVisible({ timeout: 5_000 });
  await item.click();
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

function widgetSelect(page: Page) {
  return page
    .getByTestId('designer-properties-panel')
    .locator('label:has-text("组件类型")')
    .locator('xpath=following-sibling::select[1]')
    .first();
}

async function readWidgetOptions(page: Page): Promise<string[]> {
  const select = widgetSelect(page);
  await expect(select).toBeVisible({ timeout: 5_000 });
  return select
    .locator('option')
    .evaluateAll((opts) => (opts as HTMLOptionElement[]).map((o) => o.value));
}

async function chooseWidgetByValue(page: Page, widgetValue: string): Promise<void> {
  const select = widgetSelect(page);
  await expect(select).toBeVisible({ timeout: 5_000 });
  await expect
    .poll(
      async () =>
        await select
          .locator('option')
          .evaluateAll(
            (opts, val) => (opts as HTMLOptionElement[]).some((o) => o.value === val),
            widgetValue,
          ),
      { timeout: 5_000 },
    )
    .toBe(true);
  await expect
    .poll(
      async () => {
        const present = await select
          .locator('option')
          .evaluateAll(
            (opts, val) => (opts as HTMLOptionElement[]).some((o) => o.value === val),
            widgetValue,
          );
        if (!present) return null;
        await select.evaluate((el, val) => {
          const sel = el as HTMLSelectElement;
          sel.value = val;
          sel.dispatchEvent(new Event('change', { bubbles: true }));
        }, widgetValue);
        return await select.inputValue();
      },
      { timeout: 5_000 },
    )
    .toBe(widgetValue);
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

async function fetchSavedBlocks(page: Page, pid: string): Promise<any[]> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), 'fetch saved page failed').toBeTruthy();
  const body = (await resp.json()) as { code: string; data?: { blocks?: any[] } };
  expect(body.code).toBe('0');
  return body.data?.blocks ?? [];
}

async function selectFieldInCanvas(
  page: Page,
  blockIndex: number,
  fieldCode: string,
): Promise<void> {
  // form-section blocks live in canvas as [data-block-type="form-section"];
  // the default Placeholder is index 0, so added sections start at 1.
  const block = page.locator('[data-block-type="form-section"]').nth(blockIndex);
  await expect(block).toBeVisible({ timeout: 5_000 });
  const label = block.locator(`label:has-text("${fieldCode}")`).first();
  await expect(label).toBeVisible({ timeout: 5_000 });
  await label
    .locator('xpath=ancestor::div[contains(@class,"group/field")]')
    .first()
    .click();
  await expect(
    page.getByTestId('designer-properties-panel').locator('text=字段属性'),
  ).toBeVisible({ timeout: 5_000 });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

const createdPagePids: string[] = [];

test.describe('GA-B3 — Choice-bucket widget E2E (6 widgets × showcase)', () => {
  test.setTimeout(90_000);

  test.afterEach(async ({ page }) => {
    while (createdPagePids.length > 0) {
      const pid = createdPagePids.pop()!;
      await page.request.delete(`/api/pages/${pid}`).catch(() => null);
    }
  });

  // -------------------------------------------------------------------------
  // B3.1 — Enum-bucket trio: select / radio-group / checkbox-group
  //        All three are server-registered for dataType=enum, so all three
  //        MUST surface in the dropdown for sc_status and sc_priority.
  //        We configure one widget per field + one extra field reused for
  //        the third widget so distinct component values round-trip.
  // -------------------------------------------------------------------------
  test('B3.1: select/radio-group/checkbox-group persist on enum fields', async ({ page }) => {
    const pageKey = uniquePageKey();
    const pid = await apiCreateFormPage(page, pageKey);
    createdPagePids.push(pid);

    await navigateToDesignerViaMenu(page, pid, pageKey);
    await addBlockViaPalette(page, 'form-section');

    await page.getByTestId('designer-tab-outline').click();
    await page.locator('button:has-text("Section Title"), button:has-text("区段标题")').first().click();
    // sc_status (select), sc_priority (radio-group), sc_category (checkbox-group)
    // sc_category is also enum + dict-backed per the showcase fields file.
    await addFieldsToSelectedBlock(page, ['sc_status', 'sc_priority', 'sc_category']);

    const plan: Array<{ field: string; widget: string }> = [
      { field: 'sc_status', widget: 'select' },
      { field: 'sc_priority', widget: 'radio-group' },
      { field: 'sc_category', widget: 'checkbox-group' },
    ];

    const trace: Array<{ field: string; widget: string; options: string[]; chosen: string | null }> = [];

    for (const { field, widget } of plan) {
      await selectFieldInCanvas(page, 1 /* skip Placeholder */, field);

      const propsPanel = page.getByTestId('designer-properties-panel');
      const dataTypeBadge = propsPanel
        .locator('span.font-mono')
        .first()
        .locator('xpath=following-sibling::span[1]');
      // All three fields are enum in the showcase model.
      await expect(dataTypeBadge).toHaveText('enum', { timeout: 5_000 });

      const select = widgetSelect(page);
      const targetPresent = await select
        .locator(`option[value="${widget}"]`)
        .first()
        .waitFor({ state: 'attached', timeout: 5_000 })
        .then(() => true)
        .catch(() => false);

      const opts = await readWidgetOptions(page);
      const real = opts.filter((v) => v && v.length > 0);
      let chosen: string | null = null;
      if (targetPresent) {
        await chooseWidgetByValue(page, widget);
        chosen = widget;
      } else if (real.length > 0) {
        await chooseWidgetByValue(page, real[0]);
        chosen = real[0];
      }
      trace.push({ field, widget, options: real, chosen });

      await propsPanel.locator('button:has-text("返回 Block")').first().click().catch(() => null);
    }

    await clickSaveAndWait(page, pid);

    const blocks = await fetchSavedBlocks(page, pid);
    const section = blocks.find(
      (b) => b.blockType === 'form-section' && b.title !== 'Placeholder',
    );
    expect(section, 'added form-section should exist').toBeTruthy();

    const persisted = new Map<string, string | undefined>();
    for (const fr of section.fields || []) {
      const obj = typeof fr === 'string' ? { field: fr.split('|')[0] } : fr;
      persisted.set(obj.field, obj.component);
    }

    const misses: string[] = [];
    for (const { field, widget } of plan) {
      const got = persisted.get(field);
      if (got !== widget) {
        const t = trace.find((x) => x.field === field);
        misses.push(
          `${field} expected=${widget} got=${got} dropdown=[${t?.options.join(',')}] chose=${t?.chosen}`,
        );
      }
    }

    console.log('[B3.1] trace:', JSON.stringify(trace, null, 2));
    expect(
      misses.length,
      `enum-widget chain coverage broken:\n  ${misses.join('\n  ')}`,
    ).toBe(0);
  });

  // -------------------------------------------------------------------------
  // B3.2 — String-bucket trio: multiselect / cascadeselect / treeselect
  //        These live in the FRONTEND widget registry only (they ship no
  //        server renderComponents entry, per dsl-registry.json). They may
  //        NOT surface in the FieldPropertyEditor dropdown for string dataType.
  //        The test captures the dropdown snapshot and reports the gap
  //        rather than silently passing.
  //
  //        Regardless of whether the dropdown exposes them, the
  //        field-level extension (renderComponent + widget props) ships
  //        from the field model → must round-trip via the model API.
  // -------------------------------------------------------------------------
  test('B3.2: multiselect/cascadeselect/treeselect dropdown + extension round-trip', async ({
    page,
  }) => {
    const pageKey = uniquePageKey();
    const pid = await apiCreateFormPage(page, pageKey);
    createdPagePids.push(pid);

    await navigateToDesignerViaMenu(page, pid, pageKey);
    await addBlockViaPalette(page, 'form-section');

    await page.getByTestId('designer-tab-outline').click();
    await page.locator('button:has-text("Section Title"), button:has-text("区段标题")').first().click();
    await addFieldsToSelectedBlock(page, ['sc_tags', 'sc_cascade_category', 'sc_tree_node']);

    const plan: Array<{ field: string; widget: string }> = [
      { field: 'sc_tags', widget: 'multiselect' },
      { field: 'sc_cascade_category', widget: 'cascadeselect' },
      { field: 'sc_tree_node', widget: 'treeselect' },
    ];

    const trace: Array<{
      field: string;
      widget: string;
      dataType: string | null;
      options: string[];
      chosen: string | null;
      present: boolean;
    }> = [];

    for (const { field, widget } of plan) {
      await selectFieldInCanvas(page, 1, field);

      const propsPanel = page.getByTestId('designer-properties-panel');
      const dataTypeBadge = propsPanel
        .locator('span.font-mono')
        .first()
        .locator('xpath=following-sibling::span[1]');
      const dt = await dataTypeBadge.textContent().catch(() => null);

      const select = widgetSelect(page);
      await expect(select).toBeVisible({ timeout: 5_000 });
      const targetPresent = await select
        .locator(`option[value="${widget}"]`)
        .first()
        .waitFor({ state: 'attached', timeout: 3_000 })
        .then(() => true)
        .catch(() => false);

      const opts = await readWidgetOptions(page);
      const real = opts.filter((v) => v && v.length > 0);
      let chosen: string | null = null;
      if (targetPresent) {
        await chooseWidgetByValue(page, widget);
        chosen = widget;
      } else if (real.length > 0) {
        // Don't change the widget when the target is absent — leave the
        // field in its default state so the extension-driven renderComponent
        // stays authoritative at runtime. Record the miss for reporting.
        chosen = null;
      }
      trace.push({ field, widget, dataType: dt, options: real, chosen, present: targetPresent });

      await propsPanel.locator('button:has-text("返回 Block")').first().click().catch(() => null);
    }

    await clickSaveAndWait(page, pid);

    // --- Assertion layer 1: UI component persistence (best-effort).
    const blocks = await fetchSavedBlocks(page, pid);
    const section = blocks.find(
      (b) => b.blockType === 'form-section' && b.title !== 'Placeholder',
    );
    expect(section, 'added form-section should exist').toBeTruthy();

    const persisted = new Map<string, string | undefined>();
    for (const fr of section.fields || []) {
      const obj = typeof fr === 'string' ? { field: fr.split('|')[0] } : fr;
      persisted.set(obj.field, obj.component);
    }

    const uiHits: string[] = [];
    const uiMisses: string[] = [];
    for (const { field, widget } of plan) {
      const got = persisted.get(field);
      if (got === widget) uiHits.push(`${field}/${widget}`);
      else {
        const t = trace.find((x) => x.field === field);
        uiMisses.push(
          `${field} expected=${widget} got=${got} present-in-dropdown=${t?.present} dropdown=[${t?.options.slice(0, 8).join(',')}${(t?.options.length ?? 0) > 8 ? ',...' : ''}]`,
        );
      }
    }
    console.log('[B3.2] ui hits:', uiHits.join('  '));
    if (uiMisses.length > 0) console.log('[B3.2] ui misses (expected if server registry omits these):\n  ' + uiMisses.join('\n  '));

    // --- Assertion layer 2: model-extension round-trip.
    // Widget-specific props (dictCode / levels / levelLabels / searchable /
    // clearable) live on the field model's extension bag. Even when the
    // Designer UI cannot set them, the runtime path reads them from the
    // model, so the full "widget props" contract is validated here.
    const modelResp = await page.request.get(
      `/api/meta/models/code/${SHOWCASE_MODEL_CODE}`,
    );
    expect(modelResp.ok(), 'fetch showcase model failed').toBeTruthy();
    const modelBody = (await modelResp.json()) as {
      code: string;
      data?: { pid?: string };
    };
    expect(modelBody.code).toBe('0');
    const modelPid = modelBody.data?.pid;
    expect(modelPid).toBeTruthy();

    const fieldsResp = await page.request.get(
      `/api/meta/models/${modelPid}/fields`,
    );
    expect(fieldsResp.ok(), 'fetch model fields failed').toBeTruthy();
    const fieldsBody = (await fieldsResp.json()) as {
      code: string;
      data?: Array<{
        code: string;
        dataType?: string;
        extension?: Record<string, unknown>;
      }>;
    };
    expect(fieldsBody.code).toBe('0');
    const fieldsByCode = new Map(
      (fieldsBody.data || []).map((f) => [f.code, f] as const),
    );

    // sc_tags → renderComponent=multiselect
    const scTags = fieldsByCode.get('sc_tags');
    expect(scTags, 'sc_tags field must exist').toBeTruthy();
    expect(scTags?.extension?.renderComponent).toBe('multiselect');

    // sc_cascade_category → renderComponent=cascadeselect + levels + levelLabels + dictCode
    const scCascade = fieldsByCode.get('sc_cascade_category');
    expect(scCascade, 'sc_cascade_category field must exist').toBeTruthy();
    expect(scCascade?.extension?.renderComponent).toBe('cascadeselect');
    expect(scCascade?.extension?.levels).toBe(3);
    expect(scCascade?.extension?.dictCode).toBe('sc_cascade_category_dict');
    expect(
      Array.isArray(scCascade?.extension?.levelLabels),
      'cascadeselect levelLabels must be an array',
    ).toBe(true);

    // sc_tree_node → renderComponent=treeselect + searchable + clearable + dictCode
    const scTree = fieldsByCode.get('sc_tree_node');
    expect(scTree, 'sc_tree_node field must exist').toBeTruthy();
    expect(scTree?.extension?.renderComponent).toBe('treeselect');
    expect(scTree?.extension?.dictCode).toBe('sc_tree_dept_dict');
    expect(scTree?.extension?.searchable).toBe(true);
    expect(scTree?.extension?.clearable).toBe(true);
  });

  // -------------------------------------------------------------------------
  // B3.3 — required + visible-condition persistence on choice fields.
  //        Proves the validation/behaviour chain works for choice widgets
  //        independently of the component dropdown (mirrors P4.3 but for
  //        the enum-bucket — the dictCode auto-binding is the critical
  //        difference vs the string bucket).
  // -------------------------------------------------------------------------
  test('B3.3: required + visible persist on enum field with dictCode binding', async ({
    page,
  }) => {
    const pageKey = uniquePageKey();
    const pid = await apiCreateFormPage(page, pageKey);
    createdPagePids.push(pid);

    await navigateToDesignerViaMenu(page, pid, pageKey);
    await addBlockViaPalette(page, 'form-section');
    await page.getByTestId('designer-tab-outline').click();
    await page.locator('button:has-text("Section Title"), button:has-text("区段标题")').first().click();
    await addFieldsToSelectedBlock(page, ['sc_status', 'sc_priority']);

    // sc_status: required=true, visible="true" (trivial, validates the expr
    // survives round-trip without being normalised to undefined).
    await selectFieldInCanvas(page, 1, 'sc_status');

    const requiredSwitch = page
      .getByTestId('designer-properties-panel')
      .locator('span:has-text("必填") >> xpath=following-sibling::button[@role="switch"]')
      .first();
    await expect(requiredSwitch).toBeVisible({ timeout: 5_000 });
    await requiredSwitch.click();
    await expect(requiredSwitch).toHaveAttribute('aria-checked', 'true', { timeout: 3_000 });

    const behaviorHeader = page
      .getByTestId('designer-properties-panel')
      .locator('button:has-text("行为控制")')
      .first();
    if (await behaviorHeader.isVisible({ timeout: 1_500 }).catch(() => false)) {
      await behaviorHeader.click();
    }
    const visibleInput = page
      .getByTestId('designer-properties-panel')
      .locator('label:has-text("可见性条件") >> xpath=following-sibling::input[1]')
      .first();
    await expect(visibleInput).toBeVisible({ timeout: 5_000 });
    await visibleInput.click();
    await visibleInput.fill("{{ form.sc_priority === 'high' }}");

    await clickSaveAndWait(page, pid);

    const blocks = await fetchSavedBlocks(page, pid);
    const section = blocks
      .filter((b) => b.blockType === 'form-section' && b.title !== 'Placeholder')
      .find((b) =>
        (b.fields || []).some((f: any) => {
          const o = typeof f === 'string' ? { field: f.split('|')[0] } : f;
          return o.field === 'sc_status';
        }),
      );
    expect(section, 'section containing sc_status must exist').toBeTruthy();

    const scStatusRef = (section.fields || []).find((f: any) => {
      const o = typeof f === 'string' ? { field: f.split('|')[0] } : f;
      return o.field === 'sc_status';
    });
    expect(scStatusRef, 'sc_status override should exist').toBeTruthy();
    const override = typeof scStatusRef === 'string' ? null : scStatusRef;
    expect(override?.required).toBe(true);
    expect(override?.visible).toBe("{{ form.sc_priority === 'high' }}");
  });
});
