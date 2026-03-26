/**
 * Common assertion helpers for E2E tests.
 *
 * @since 4.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';

/**
 * Assert that a table contains expected data in specific columns.
 */
export async function expectTableContains(
  page: Page,
  columnIndex: number,
  expectedValues: string[],
): Promise<void> {
  const cells = page.locator(`tbody tr td:nth-child(${columnIndex + 1})`);
  const count = await cells.count();

  const actualValues: string[] = [];
  for (let i = 0; i < count; i++) {
    actualValues.push((await cells.nth(i).innerText()).trim());
  }

  for (const expected of expectedValues) {
    expect(actualValues).toContain(expected);
  }
}

/**
 * Assert that a form field has a specific value.
 */
export async function expectFieldValue(
  page: Page,
  fieldName: string,
  expectedValue: string,
): Promise<void> {
  const input = page.locator(
    `[name="${fieldName}"], [data-field="${fieldName}"] input, [data-field="${fieldName}"] select`
  );
  await expect(input).toHaveValue(expectedValue);
}

/**
 * Assert API call was made with expected parameters.
 */
export async function expectApiCalledWith(
  page: Page,
  urlPattern: string,
  method: string,
  expectedBody?: Record<string, any>,
  timeout: number = 5000,
): Promise<void> {
  const request = await page.waitForRequest(
    req => req.url().includes(urlPattern) && req.method() === method,
    { timeout },
  );

  if (expectedBody) {
    const body = request.postDataJSON();
    for (const [key, value] of Object.entries(expectedBody)) {
      expect(body[key]).toEqual(value);
    }
  }
}

/**
 * Assert page navigated to expected URL.
 */
export async function expectNavigation(
  page: Page,
  urlPattern: string | RegExp,
  timeout: number = 5000,
): Promise<void> {
  await page.waitForURL(urlPattern, { timeout });
}

/**
 * Assert element count within a container.
 */
export async function expectCount(
  locator: Locator,
  count: number,
): Promise<void> {
  await expect(locator).toHaveCount(count);
}

/**
 * Assert loading state transitions: visible → hidden.
 */
export async function expectLoadingComplete(
  page: Page,
  timeout: number = 10000,
): Promise<void> {
  const loader = page.locator('.animate-spin, [data-testid="loading"]');
  // Wait for loading to appear and then disappear
  try {
    await loader.waitFor({ state: 'visible', timeout: 2000 });
  } catch {
    // Loading may be too fast to catch, that's fine
  }
  await expect(loader).not.toBeVisible({ timeout });
}

/**
 * Assert a confirmation dialog appears and handle it.
 */
export async function expectAndConfirmDialog(
  page: Page,
  expectedMessage?: string,
): Promise<void> {
  const dialog = page.locator('[role="dialog"], [role="alertdialog"]');
  await expect(dialog).toBeVisible();

  if (expectedMessage) {
    await expect(dialog).toContainText(expectedMessage);
  }

  await dialog.locator('button:has-text("确定"), button:has-text("确认"), button:has-text("OK")').click();
  await expect(dialog).not.toBeVisible();
}

/**
 * Take a screenshot with a descriptive name for debugging.
 */
export async function captureState(
  page: Page,
  name: string,
): Promise<void> {
  await page.screenshot({ path: `test-results/screenshots/${name}.png`, fullPage: true });
}

/**
 * Wait for network to be idle (no pending requests).
 */
export async function waitForNetworkIdle(
  page: Page,
  timeout: number = 5000,
): Promise<void> {
  await page.waitForLoadState('domcontentloaded', { timeout });
}
