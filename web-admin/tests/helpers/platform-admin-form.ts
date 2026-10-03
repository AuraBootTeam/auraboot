/** Shared form drivers for OSS administration and independently installed BPM pages. */
import { test, expect } from '../fixtures';
import {
  navigateToDynamicPage,
  normalizeDynamicPageKey,
  waitForDynamicPageLoad,
  uniqueId,
  acceptConfirmDialog,
  findRowInPaginatedList,
  fillControlledInput,
  queryFilteredList,
  extractRecordId,
  clickRowActionByLocator,
} from '../e2e/helpers';
import { ErrorCodes } from '~/shared/services/http-client/types';
import { BASE_URL } from './environments';

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

const CREATE_COMMAND_BY_PAGE_KEY: Record<string, string> = {
  'sla-config': 'admin:create_sla_config',
  'bpm-domain-config': 'admin:create_bpm_domain_config',
  'data-permission': 'admin:create_data_permission',
  webhook: 'admin:create_webhook',
  'api-connector': 'admin:create_api_connector',
};
const EDIT_COMMAND_BY_PAGE_KEY: Record<string, string> = {
  'sla-config': 'admin:update_sla_config',
  'bpm-domain-config': 'admin:update_bpm_domain_config',
  'data-permission': 'admin:update_data_permission',
  webhook: 'admin:update_webhook',
  'api-connector': 'admin:update_api_connector',
};

export function annotateFallback(description: string) {
  test.info().annotations.push({
    type: 'fallback',
    description,
  });
}

/**
 * Wait for form page to be ready after navigation.
 * Create routes to /p/{model}/new, edit routes to /p/{model}/{id}/edit.
 */
function formFieldLocator(scope: import('@playwright/test').Locator, fieldCode: string) {
  return scope
    .locator(
      [
        `[data-testid="form-field-${fieldCode}"] input:not([type="hidden"])`,
        `[data-testid="form-field-${fieldCode}"] textarea`,
        `[data-testid="form-field-${fieldCode}"] select`,
        `[data-testid="form-field-${fieldCode}"] button[role="switch"]`,
        `[data-testid="field-${fieldCode}"] input:not([type="hidden"])`,
        `[data-testid="field-${fieldCode}"] textarea`,
        `[data-testid="field-${fieldCode}"] select`,
        `[data-testid="field-${fieldCode}"] button[role="switch"]`,
        `[data-field="${fieldCode}"] input:not([type="hidden"])`,
        `[data-field="${fieldCode}"] textarea`,
        `[data-field="${fieldCode}"] select`,
        `[data-field="${fieldCode}"] button[role="switch"]`,
        `input[name="${fieldCode}"]:not([type="hidden"])`,
        `textarea[name="${fieldCode}"]`,
        `select[name="${fieldCode}"]`,
      ].join(', '),
    )
    .first();
}

export async function waitForFormReady(
  page: import('@playwright/test').Page,
  expectedFieldCodes: string[] = [],
) {
  // Wait for URL to include /new or /edit
  await expect(page).toHaveURL(/\/(new|edit)/, { timeout: 10000 });

  await waitForDynamicPageLoad(page, 8000);

  const errorAlert = page
    .locator('text=common.loadError, text=Bad parameter, text=Failed to load, text=加载失败')
    .first();
  if (await errorAlert.isVisible({ timeout: 1000 }).catch(() => false)) {
    throw new Error('Form failed to load due to backend/schema error');
  }

  const main = page.locator('main, [role="main"]').first();
  await expect(main).toBeVisible({ timeout: 10000 });

  for (const fieldCode of expectedFieldCodes) {
    await expect(formFieldLocator(main, fieldCode)).toBeVisible({ timeout: 15000 });
  }

  if (expectedFieldCodes.length > 0) {
    return;
  }

  await main
    .locator(
      '[data-testid^="form-field-"] input:not([type="hidden"]), [data-testid^="form-field-"] textarea, [data-testid^="form-field-"] select, [data-testid^="form-field-"] button[role="switch"], [data-testid^="field-"] input:not([type="hidden"]), [data-testid^="field-"] textarea, [data-testid^="field-"] select, [data-testid^="field-"] button[role="switch"], [data-field] input:not([type="hidden"]), [data-field] textarea, [data-field] select, [data-field] button[role="switch"], form input:not([type="hidden"]), form select, form textarea, form button[role="switch"]',
    )
    .first()
    .waitFor({ state: 'visible', timeout: 15000 });
}

/** Fill a text input field on the form page */
export async function fillFormField(
  page: import('@playwright/test').Page,
  fieldCode: string,
  value: string,
) {
  // Strategy 1: data-testid
  const byTestId = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] input:not([type="hidden"]), [data-testid="form-field-${fieldCode}"] textarea, [data-testid="field-${fieldCode}"] input:not([type="hidden"]), [data-testid="field-${fieldCode}"] textarea`,
    )
    .first();
  if (await byTestId.isVisible({ timeout: 8000 }).catch(() => false)) {
    await fillControlledInput(byTestId, value);
    return;
  }
  // Strategy 2: data-field attribute
  const byField = page
    .locator(
      `[data-field="${fieldCode}"] input:not([type="hidden"]), [data-field="${fieldCode}"] textarea`,
    )
    .first();
  if (await byField.isVisible({ timeout: 8000 }).catch(() => false)) {
    await fillControlledInput(byField, value);
    return;
  }
  // Strategy 3: name attribute
  const byName = page
    .locator(`input[name="${fieldCode}"]:not([type="hidden"]), textarea[name="${fieldCode}"]`)
    .first();
  if (await byName.isVisible({ timeout: 8000 }).catch(() => false)) {
    await fillControlledInput(byName, value);
    return;
  }
  // Strategy 4: find by visible label text as a last resort
  const labelCandidates: Record<string, RegExp> = {
    domain_code: /域编码|Domain Code/i,
    domain_name: /域名称|Domain Name/i,
    name: /名称|Name/i,
    target_url: /目标.*URL|回调.*URL|Target URL|Webhook URL/i,
    event_type: /事件类型|Event Type/i,
  };
  const labelPattern = labelCandidates[fieldCode];
  if (labelPattern) {
    const byAccessibleLabel = page
      .getByLabel(labelPattern)
      .locator('input:not([type="hidden"]), textarea')
      .first();
    if (await byAccessibleLabel.isVisible({ timeout: 5000 }).catch(() => false)) {
      await fillControlledInput(byAccessibleLabel, value);
      return;
    }
    const label = page.locator('label').filter({ hasText: labelPattern }).first();
    if (await label.isVisible({ timeout: 5000 }).catch(() => false)) {
      const container = label.locator('xpath=ancestor::*[self::div or self::label][1]');
      const input = container.locator('input:not([type="hidden"]), textarea').first();
      if (await input.isVisible({ timeout: 3000 }).catch(() => false)) {
        await fillControlledInput(input, value);
        return;
      }
    }
  }
  throw new Error(`Could not find input field: ${fieldCode}`);
}

/** Select a value from a native <select> */
export async function selectFormField(
  page: import('@playwright/test').Page,
  fieldCode: string,
  value: string,
) {
  const anyField = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] select, [data-testid="field-${fieldCode}"] select, [data-field="${fieldCode}"] select, select[name="${fieldCode}"], [data-testid="form-field-${fieldCode}"] input, [data-testid="field-${fieldCode}"] input, [data-field="${fieldCode}"] input, input[name="${fieldCode}"], [data-testid="form-field-${fieldCode}"] textarea, [data-testid="field-${fieldCode}"] textarea, [data-field="${fieldCode}"] textarea, textarea[name="${fieldCode}"]`,
    )
    .first();
  await anyField.waitFor({ state: 'attached', timeout: 12000 }).catch(() => null);

  const select = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] select, [data-testid="field-${fieldCode}"] select, [data-field="${fieldCode}"] select, select[name="${fieldCode}"]`,
    )
    .first();
  if (await select.isVisible({ timeout: 8000 }).catch(() => false)) {
    await select.selectOption(value);
    return;
  }
  const input = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] input:not([type="hidden"]), [data-testid="form-field-${fieldCode}"] textarea, [data-testid="field-${fieldCode}"] input:not([type="hidden"]), [data-testid="field-${fieldCode}"] textarea, [data-field="${fieldCode}"] input:not([type="hidden"]), [data-field="${fieldCode}"] textarea, input[name="${fieldCode}"]:not([type="hidden"]), textarea[name="${fieldCode}"]`,
    )
    .first();
  if (await input.isVisible({ timeout: 8000 }).catch(() => false)) {
    await fillControlledInput(input, value);
    return;
  }
  const hiddenInput = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] input[type="hidden"], [data-testid="field-${fieldCode}"] input[type="hidden"], [data-field="${fieldCode}"] input[type="hidden"], input[name="${fieldCode}"][type="hidden"]`,
    )
    .first();
  if (await hiddenInput.count().then((count) => count > 0).catch(() => false)) {
    await hiddenInput.evaluate((el, nextValue) => {
      const inputEl = el as HTMLInputElement;
      inputEl.value = String(nextValue ?? '');
      inputEl.dispatchEvent(new Event('input', { bubbles: true }));
      inputEl.dispatchEvent(new Event('change', { bubbles: true }));
    }, value);
    return;
  }
  throw new Error(`Could not find select field: ${fieldCode}`);
}

/** Click the save button on form page and wait for command API response */
export async function clickSaveAndWait(
  page: import('@playwright/test').Page,
  options?: { expectedCommandCode?: string },
) {
  const saveBtn = page
    .locator(
      '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("保存"), button:has-text("Save")',
    )
    .first();
  await saveBtn.waitFor({ state: 'visible', timeout: 5000 });
  const currentUrl = new URL(page.url());
  const expectedCommand =
    options?.expectedCommandCode || currentUrl.searchParams.get('commandCode');

  // Listen for current form command execution only.
  const respPromise = page
    .waitForResponse(
      (r) => {
        if (!r.url().includes('/commands/execute/')) return false;
        if (!expectedCommand) return true;
        return r.url().includes(`/commands/execute/${expectedCommand}`);
      },
      { timeout: 15000 },
    )
    .catch(() => null);
  await saveBtn.click();
  const resp = await respPromise;
  if (!resp) {
    await expect
      .poll(
        async () => {
          const currentUrl = new URL(page.url());
          const onFormRoute = /\/(new|edit)(?:$|\/)/.test(currentUrl.pathname);
          const tableVisible = await page
            .locator('table, [role="table"], [data-testid="dynamic-list"]')
            .first()
            .isVisible({ timeout: 500 })
            .catch(() => false);
          return !onFormRoute || tableVisible;
        },
        { timeout: 8000, intervals: [500, 1000, 1500] },
      )
      .toBe(true)
      .catch(() => null);
    const currentUrl = new URL(page.url());
    const leftFormRoute = !/\/(new|edit)(?:$|\/)/.test(currentUrl.pathname);
    const tableVisible = await page
      .locator('table, [role="table"], [data-testid="dynamic-list"]')
      .first()
      .isVisible({ timeout: 1000 })
      .catch(() => false);
    if (leftFormRoute || tableVisible) {
      annotateFallback(
        `Save response listener missed ${expectedCommand || 'form command'}, but page navigated successfully`,
      );
      return {};
    }
    throw new Error(`Timed out waiting for command response: ${expectedCommand || 'unknown command'}`);
  }
  const status = resp.status();
  const body = await resp.json().catch(async () => ({ raw: await resp.text().catch(() => '') }));
  if (status !== 200) {
    const requestBody = resp.request().postData() || '';
    throw new Error(
      `Command response status for ${expectedCommand || 'unknown command'}: ${status}, body=${JSON.stringify(body)}, requestBody=${requestBody}`,
    );
  }
  // API payloads are not fully uniform across admin modules.
  // Prefer business code when present; otherwise only fail on explicit business failure.
  const code = String(body.code ?? body?.data?.code ?? '');
  if (code) {
    expect(code).toBe(ErrorCodes.SUCCESS);
  } else {
    const explicitFailure = body.success === false || body?.data?.success === false;
    expect(explicitFailure).toBe(false);
  }
  // Some command routes may return non-JSON or wrapper payload while still succeeding.
  // Downstream assertions (row visible/updated/deleted) remain the source of truth.
  return body;
}

/** Click the toolbar create button (uses data-testid) */
export async function clickCreateButton(page: import('@playwright/test').Page) {
  const createBtn = page.locator('[data-testid="toolbar-btn-create"]').first();
  await createBtn.waitFor({ state: 'visible', timeout: 5000 });
  const enteredCreateRoute = await expect
    .poll(
      async () => {
        await createBtn.click().catch(() => {});
        const url = new URL(page.url());
        return /\/new(?:$|\?)/.test(url.pathname + url.search);
      },
      { timeout: 8000, intervals: [100, 250, 500, 1000] },
    )
    .toBe(true)
    .then(() => true)
    .catch(() => false);

  if (enteredCreateRoute) return;

  const currentUrl = new URL(page.url());
  const modelSegment = currentUrl.pathname.match(/^\/p\/([^/]+)/)?.[1];
  if (!modelSegment) {
    throw new Error('Create button did not navigate and current page key could not be inferred');
  }
  const pageKey = modelSegment.replace(/_/g, '-');
  const createCommand = CREATE_COMMAND_BY_PAGE_KEY[pageKey];
  if (!createCommand) {
    throw new Error(`Create button did not navigate and no fallback command is defined for ${pageKey}`);
  }
  annotateFallback(`toolbar create did not navigate; fallback to direct create route for ${pageKey}`);
  await page.goto(
    `/p/${modelSegment}/new?commandCode=${encodeURIComponent(createCommand)}`,
    { waitUntil: 'domcontentloaded' },
  );
}

/** Click the row-level edit button (uses data-testid, handles "more actions" dropdown) */
export async function clickRowEditButton(row: import('@playwright/test').Locator) {
  await clickRowActionByLocator(row.page(), row, 'edit');
}

/** Click the row-level delete button (handles "more actions" dropdown), confirm, and wait for command */
export async function clickRowDeleteAndConfirm(
  page: import('@playwright/test').Page,
  row: import('@playwright/test').Locator,
) {
  // Set up command response listener BEFORE clicking to avoid race condition
  const cmdPromise = page
    .waitForResponse((r) => r.url().includes('/commands/execute/'), { timeout: 5000 })
    .catch(() => null);
  await clickRowActionByLocator(page, row, 'delete');
  // The delete action uses confirmMessageKey which shows a custom ConfirmDialog
  await acceptConfirmDialog(page);
  // Wait for command response or list refresh (whichever comes first).
  const listPromise = page
    .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 5000 })
    .catch(() => null);
  await Promise.race([cmdPromise, listPromise]).catch(() => null);
}

/** Navigate to edit form by recordId (UI route), with standard form readiness checks. */
export async function openEditFormByPid(
  page: import('@playwright/test').Page,
  pageKey: string,
  pid: string,
) {
  const cmd = EDIT_COMMAND_BY_PAGE_KEY[pageKey];
  const cmdQuery = cmd ? `?commandCode=${encodeURIComponent(cmd)}` : '';
  await page.goto(`/p/${normalizeDynamicPageKey(pageKey)}/${pid}/edit${cmdQuery}`, {
    waitUntil: 'domcontentloaded',
  });
  await waitForFormReady(page);
  await expect(page).toHaveURL(/\/edit(?:\?|$)/, { timeout: 5000 });
  if (cmd) {
    const currentUrl = new URL(page.url());
    expect(currentUrl.searchParams.get('commandCode')).toBe(cmd);
  }
}

/** Click delete on form page and confirm (UI), then wait for delete command response. */
export async function clickFormDeleteAndConfirm(page: import('@playwright/test').Page): Promise<boolean> {
  const deleteBtn = page
    .locator(
      '[data-testid="form-btn-delete"], [data-testid^="form-btn-"]:has-text("删除"), [data-testid^="form-btn-"]:has-text("Delete"), button:has-text("删除"), button:has-text("Delete")',
    )
    .first();
  const hasDeleteBtn = await deleteBtn.isVisible({ timeout: 5000 }).catch(() => false);
  if (!hasDeleteBtn) return false;
  const cmdPromise = page
    .waitForResponse((r) => r.url().includes('/commands/execute/'), { timeout: 5000 })
    .catch(() => null);
  await deleteBtn.click();
  await acceptConfirmDialog(page);
  const listPromise = page
    .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 5000 })
    .catch(() => null);
  await Promise.race([cmdPromise, listPromise]).catch(() => null);
  return true;
}
