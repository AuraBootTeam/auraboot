/**
 * P1' ACP platformization — vertical slice E2E.
 *
 * Validates the wd_leave_request AI fill UX end-to-end:
 *   AI-001 @smoke      — banner visible above form, button opens dialog
 *   AI-002 @critical   — NL input → /api/wd-leave-request/ai-fill → form fields
 *                        populated with AI-extracted values
 *
 * Requires:
 *   - workflow-demo plugin imported (with the ai-fill-banner block in form.json)
 *   - acp_ai_annotation table present (scripts/p1-ai-annotation-temp.sql)
 *   - LLM provider configured in CloudConfig (any apiFormat)
 *
 * P2' will replace this spec with a coverage matrix that drives both
 * wd_leave_request and at least one other business object through the
 * SafetyValveService SDK + chokepoint resume.
 */
import { test, expect, type Page } from '../../fixtures';

test.describe.configure({ mode: 'serial' });

test.describe('wd_leave_request — AI vertical slice (P1)', () => {
  test.setTimeout(90_000);

  let workflowDemoAvailable = true;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      await page.goto('/p/wd_leave_request');
      // The list page exists if and only if workflow-demo is imported.
      const found = await page
        .locator('table, [class*="ant-table"], [data-testid*="leave_request"]')
        .first()
        .isVisible({ timeout: 8_000 })
        .catch(() => false);
      workflowDemoAvailable = found;
    } finally {
      await ctx.close();
    }
  });

  test.beforeEach(async () => {
    test.skip(
      !workflowDemoAvailable,
      'workflow-demo plugin not imported in current environment',
    );
  });

  // ---------------------------------------------------------------------
  // AI-001: banner visible above form, button opens dialog
  // ---------------------------------------------------------------------
  test('AI-001 @smoke — banner visible above form, button opens dialog', async ({ page }) => {
    await navigateToCreateForm(page);

    const banner = page.locator('[data-testid="ai-fill-banner"]');
    await expect(banner).toBeVisible();
    await expect(banner.getByText(/智能填写|AI Fill/i)).toBeVisible();

    // Banner sits ABOVE the first form-section block (basic info).
    // Use bounding-box ordering rather than CSS structure to stay resilient
    // to layout changes.
    const firstSection = page
      .locator('[data-testid^="form-section-"], [data-testid="form-block-basic"]')
      .first();
    if (await firstSection.isVisible({ timeout: 3_000 }).catch(() => false)) {
      const bannerBox = await banner.boundingBox();
      const sectionBox = await firstSection.boundingBox();
      if (bannerBox && sectionBox) {
        expect(
          bannerBox.y,
          'AI fill banner should sit above the first form section',
        ).toBeLessThan(sectionBox.y);
      }
    }

    await page.locator('[data-testid="ai-fill-trigger"]').click();
    await expect(page.locator('[data-testid="ai-fill-dialog"]')).toBeVisible();
    await expect(page.locator('[data-testid="ai-fill-input"]')).toBeVisible();
    await expect(page.locator('[data-testid="ai-fill-confirm"]')).toBeDisabled();
  });

  // ---------------------------------------------------------------------
  // AI-002: NL input → AI fill API → form fields populated
  // ---------------------------------------------------------------------
  test('AI-002 @critical — NL input fills form fields via /ai-fill', async ({ page }) => {
    await navigateToCreateForm(page);

    await page.locator('[data-testid="ai-fill-trigger"]').click();
    await page.locator('[data-testid="ai-fill-input"]').fill('明天身体不舒服请病假 1 天');

    // Wait for the actual /ai-fill request so we know the LLM round-trip ran.
    const [response] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/api/wd-leave-request/ai-fill') && r.request().method() === 'POST',
        { timeout: 60_000 },
      ),
      page.locator('[data-testid="ai-fill-confirm"]').click(),
    ]);
    expect(response.ok()).toBeTruthy();

    // Dialog closes after a successful fill.
    await expect(page.locator('[data-testid="ai-fill-dialog"]')).not.toBeVisible({
      timeout: 10_000,
    });

    // Pick a deterministic field (leave type) and assert the picker shows the
    // AI-derived value. The LLM should map "病假" → wd_req_type=sick → 病假.
    const leaveTypeButton = page
      .locator(
        '[data-testid="form-field-wd_req_type"] [role="combobox"], ' +
          '[data-field="wd_req_type"] [role="combobox"]',
      )
      .first();
    await expect(leaveTypeButton).toBeVisible({ timeout: 8_000 });
    await expect(leaveTypeButton).toContainText(/病假|Sick/i);

    // Reason is free-text and almost always populated by the LLM for this input.
    const reasonInput = page
      .locator(
        '[data-testid="form-field-wd_req_reason"] textarea, ' +
          '[data-testid="form-field-wd_req_reason"] input, ' +
          '[data-field="wd_req_reason"] textarea, [data-field="wd_req_reason"] input',
      )
      .first();
    if (await reasonInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      const reasonValue = await reasonInput.inputValue().catch(() => '');
      expect(reasonValue.length, 'AI should populate the reason field').toBeGreaterThan(0);
    }
  });

  // AI-003 (safety escalation) intentionally NOT in this spec.
  //
  // Validating /safety-check via page.request would break the AGENTS.md rule
  // that test body page.click/fill calls must outnumber page.request. The
  // safety-check endpoint is exercised by the controller-level integration
  // test (WdLeaveAiControllerIntegrationTest, pending Stage C-3). The full
  // UI escalation flow (form submit → toast / banner showing
  // requiresEscalation) belongs in this spec but depends on Stage C-3
  // (form-submit-time safety check wiring), which is deferred.
});

// =====================================================================
// Helpers
// =====================================================================

async function navigateToCreateForm(page: Page): Promise<void> {
  await page.goto('/p/wd_leave_request');
  const createBtn = page
    .locator('[data-testid="toolbar-btn-create"]')
    .or(page.getByRole('button', { name: /^(新建|创建|Create)$/i }))
    .first();
  await expect(createBtn).toBeVisible({ timeout: 15_000 });
  await createBtn.evaluate((el: HTMLElement) => el.click());
  await page
    .waitForURL(/\/p\/wd_leave_request_form(?:\/new)?(?:\?|$)|\/new|\/create/, {
      timeout: 15_000,
    })
    .catch(() => null);
  await page.waitForSelector(
    '[data-testid="ai-fill-banner"], [data-testid^="form-section-"]',
    { timeout: 15_000 },
  );
}
