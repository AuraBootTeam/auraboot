/**
 * Deterministic AuraBot panel opener.
 *
 * The header toggle (`data-testid="ai-panel-toggle"`) dispatches `toggle_panel`,
 * which FLIPS `panelState` collapsed↔expanded (AuraBotProvider). The panel body
 * (`data-testid="aurabot-panel"`) is always in the DOM; its wrapper is
 * `display:none` when collapsed and `contents` when expanded (AdminLayout).
 *
 * The old per-spec helpers clicked the toggle a fixed number of times and raced a
 * short (2500ms) visibility check for an early break. Because the toggle is a
 * FLIP, that is non-deterministic: if a click's open is observed too late the
 * helper clicks again and toggles the panel back CLOSED — an even number of
 * effective flips leaves it hidden and the test fails ("aurabot-panel … unexpected
 * value hidden"). A single click reliably opens it in <1s on a healthy stack.
 *
 * Invariant that makes this deterministic: check visibility immediately BEFORE
 * every click, so we never toggle an already-open panel shut, and wait generously
 * AFTER a click so a slow open is not mistaken for "did not open".
 */
import { expect, type Locator, type Page } from '@playwright/test';

export async function openAuraBotPanel(page: Page): Promise<Locator> {
  const panel = page.locator('[data-testid="aurabot-panel"]');
  const toggle = page.locator('[data-testid="ai-panel-toggle"]');

  await expect(toggle).toBeVisible({ timeout: 10_000 });
  await expect(toggle).toBeEnabled({ timeout: 5_000 });

  for (let attempt = 0; attempt < 3; attempt += 1) {
    // Re-check before each click: the toggle flips state, so clicking an
    // already-open panel would close it.
    if (await panel.isVisible().catch(() => false)) return panel;
    await toggle.click();
    if (await panel.isVisible({ timeout: 6_000 }).catch(() => false)) return panel;
    // Still closed after a generous wait — the click likely did not register
    // (header still hydrating); loop re-checks visibility then retries.
  }

  // Last-resort recovery: a full reload resets panelState to collapsed, then a
  // single click opens it deterministically.
  if (!(await panel.isVisible().catch(() => false))) {
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(toggle).toBeVisible({ timeout: 10_000 });
    await expect(toggle).toBeEnabled({ timeout: 5_000 });
    if (!(await panel.isVisible().catch(() => false))) {
      await toggle.click();
    }
  }

  await expect(panel).toBeVisible({ timeout: 8_000 });
  return panel;
}
