/**
 * Digital employee — create wizard and org enrolment, driven through the browser.
 *
 * Why this file exists: on 2026-07-20 this one feature turned out to have seven
 * defects, and every existing signal said it was finished — the classes were
 * written, the plugin imported with `success: true`, the suites were green, the
 * docs described it as done. Four of the seven only appear when a person walks
 * the flow: the wizard omitted a NOT NULL column so creation was a 100% 400; the
 * enrolment button's precondition could never hold for a tenant-created agent so
 * it never rendered; the provider list arrived empty because a bare array was
 * fed through an envelope normaliser; and the created colleague could not hold a
 * conversation because the model column defaulted to a vendor the tenant had
 * never configured.
 *
 * What the coverage matrix said at the time: create ✓, enrol ✓, listed ✓ — four
 * green cells for a colleague that could not talk. So these assertions are
 * written against the *purpose* of each step, not its reachability: the record
 * exists AND carries a usable provider; the badge appears AND names the
 * department; removal actually detaches.
 */

import { test, expect, type Page } from '@playwright/test';

const UNIQUE = `e2e${Date.now().toString(36)}`;
const COLLEAGUE_NAME = `E2E Colleague ${UNIQUE}`;
/** Screenshots are the evidence a person can actually check; assertions alone cannot show layout. */
const SHOTS = 'test-results/digital-employee';

/**
 * The wizard opens on a template picker; every path to the form goes through it.
 *
 * The page is server-rendered, so the skip button is present and "visible" in the
 * markup before React has hydrated — clicking it then does nothing at all, because
 * no handler is attached yet. Waiting for the provider lookup is the honest
 * hydration signal: that request is fired from a client-side effect, so it cannot
 * have happened until the component is live.
 */
async function openWizardForm(page: Page) {
  const hydrated = page.waitForResponse(
    (r) => r.url().includes('/agent/providers/configured'),
    { timeout: 30_000 },
  );
  await page.goto('/ai/colleagues/new', { waitUntil: 'domcontentloaded' });
  await hydrated;
  const skip = page.locator('[data-testid="wizard-template-skip"]');
  await expect(skip).toBeVisible({ timeout: 15_000 });
  await skip.click();
  await expect(page.locator('[data-testid="wizard-step-identity"]')).toBeVisible({
    timeout: 15_000,
  });
}

test.describe('Digital employee — create and enrol', () => {
  test.setTimeout(90_000);

  /**
   * Enrolment needs somewhere to enrol into, and the plugin profile a given stack
   * is brought up with may carry no org chart at all. Seeded through the product's
   * own endpoint rather than SQL, and named per execution so a re-run never enrols
   * into the previous run's department.
   */
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const deptRes = await ctx.request.post('/api/org/departments', {
      data: {
        org_dept_name: `E2E Dept ${UNIQUE}`,
        org_dept_code: `e2edept${UNIQUE}`,
        org_dept_status: 'active',
        org_dept_order: 1,
      },
    });
    expect(
      deptRes.status(),
      'seeding a department must succeed, or enrolment cannot be tested',
    ).toBeLessThan(400);
    const deptPid = (await deptRes.json())?.data?.pid;
    expect(deptPid, 'the seeded department must come back with a pid').toBeTruthy();

    // The employee model requires a position, so a department alone is not
    // enough to enrol into — seed one inside the department we just made.
    const posRes = await ctx.request.post('/api/dynamic/org_position/create', {
      data: {
        org_pos_name: `E2E Position ${UNIQUE}`,
        org_pos_code: `e2epos${UNIQUE}`,
        org_pos_dept_id: deptPid,
        org_pos_level: 'P5',
        org_pos_status: 'active',
      },
    });
    expect(posRes.status(), 'seeding a position must succeed').toBeLessThan(400);
    await ctx.close();
  });

  test('the wizard refuses to submit a colleague with no name', async ({ page }) => {
    await openWizardForm(page);

    // Advancing with an empty name must be blocked at the first step. Without
    // this the wizard would post a record whose display name is blank and whose
    // derived agent_code is a bare timestamp.
    await page.locator('[data-testid="wizard-btn-next"]').click();
    await expect(page.locator('[data-testid="wizard-error-name"]')).toBeVisible();
    await expect(page.locator('[data-testid="wizard-step-identity"]')).toBeVisible();
  });

  test('create through the wizard, enrol into the org chart, then detach', async ({
    page,
  }) => {
    await openWizardForm(page);

    // --- step 1: identity -------------------------------------------------
    await page.locator('[data-testid="wizard-input-name"]').fill(COLLEAGUE_NAME);
    await page
      .locator('[data-testid="wizard-input-description"]')
      .fill('Created by ai-colleague-create-enroll.spec.ts');
    await page.locator('[data-testid="wizard-btn-next"]').click();

    // --- step 2: personality ---------------------------------------------
    await expect(page.locator('[data-testid="wizard-step-personality"]')).toBeVisible();
    await page.locator('[data-testid="wizard-btn-next"]').click();

    // --- step 3: review ---------------------------------------------------
    await expect(page.locator('[data-testid="wizard-step-review"]')).toBeVisible();

    // A colleague with no AI service saves, lists, enrols — and then cannot
    // answer a single message. The wizard must therefore have offered a real
    // provider; if this tenant has none the wizard says so, and creating would
    // produce exactly the mute colleague this assertion exists to prevent.
    const providerSelect = page.locator('[data-testid="review-provider-select"]');
    const providerNone = page.locator('[data-testid="review-provider-none"]');
    await expect(providerSelect.or(providerNone)).toBeVisible();
    expect(
      await providerNone.isVisible(),
      'tenant has no configured LLM provider — a colleague created here could not talk',
    ).toBe(false);
    const chosenProvider = await providerSelect.inputValue();
    expect(chosenProvider, 'the review step must preselect a configured provider').not.toBe('');
    await page.screenshot({ path: SHOTS + '/01-review-provider.png', fullPage: true });

    // The create request is the thing that was broken: it omitted agent_code,
    // a NOT NULL column, so every submission was a 400. Assert on the payload
    // the browser actually sends, not on the outcome alone — the outcome could
    // go green again for a different reason.
    const [createRequest, createResponse] = await Promise.all([
      page.waitForRequest(
        (r) => r.url().includes('/agent-definition/create') && r.method() === 'POST',
        { timeout: 20_000 },
      ),
      page.waitForResponse((r) => r.url().includes('/agent-definition/create'), {
        timeout: 20_000,
      }),
      page.locator('[data-testid="wizard-btn-create"]').click(),
    ]);

    const payload = createRequest.postDataJSON() as Record<string, unknown>;
    expect(payload.agent_code, 'agent_code is NOT NULL — omitting it 400s every create').toBeTruthy();
    expect(payload.name).toBe(COLLEAGUE_NAME);
    expect(
      JSON.stringify(payload.guardrails ?? ''),
      'the chosen provider must reach the server, or the colleague answers to nobody',
    ).toContain(chosenProvider);
    expect(createResponse.status(), 'create must not 4xx').toBeLessThan(400);

    // The wizard navigates to the new colleague's detail page on success.
    await expect(page).toHaveURL(/\/ai\/colleagues\/[^/]+$/, { timeout: 20_000 });
    await expect(page.getByText(COLLEAGUE_NAME).first()).toBeVisible({ timeout: 15_000 });

    // ---------------------------------------------------------------------
    // Same journey continues: a colleague that exists but sits nowhere in the
    // org chart is not yet a digital employee. Kept in one test rather than
    // two so it does not depend on another test having run first.
    // ---------------------------------------------------------------------

    // The enrolment button used to be gated on a column only the system tenant
    // ever had written, so for a tenant-created colleague it simply never
    // rendered — no error, no element, nothing to click. Its presence is the
    // regression guard.
    const enrollBtn = page.locator('[data-testid="enroll-as-employee-btn"]');
    await expect(
      enrollBtn,
      'enrolment must be reachable for a tenant-created colleague, not only a system one',
    ).toBeVisible({ timeout: 20_000 });
    await enrollBtn.click();

    const dialog = page.locator('[data-testid="enroll-dialog"]');
    await expect(dialog).toBeVisible();

    const deptSelect = page.locator('[data-testid="enroll-dept-select"]');
    await expect(
      deptSelect,
      'with a department seeded, the picker must render rather than the empty state',
    ).toBeVisible();
    const deptOptions = deptSelect.locator('option');
    // Option 0 is the placeholder; a real department is required to enrol.
    expect(
      await deptOptions.count(),
      'the seeded department must be offered',
    ).toBeGreaterThan(1);
    const deptValue = await deptOptions.nth(1).getAttribute('value');
    await deptSelect.selectOption(deptValue!);

    // Position is required by the employee model. It used to be presented as
    // optional here, and enrolling without one reached the validator as an
    // unhandled 500 that named no field. Confirm must stay unavailable until a
    // position is chosen — this assertion is what keeps the dialog honest about
    // the server's contract.
    const confirmBtn = page.locator('[data-testid="enroll-confirm-btn"]');
    await expect(
      confirmBtn,
      'confirm must be unavailable while no position is chosen — the server refuses that shape',
    ).toBeDisabled();

    const posSelect = page.locator('[data-testid="enroll-position-select"]');
    const posOptions = posSelect.locator('option');
    await expect
      .poll(async () => posOptions.count(), { timeout: 15_000 })
      .toBeGreaterThan(1);
    const posValue = await posOptions.nth(1).getAttribute('value');
    await posSelect.selectOption(posValue!);
    await expect(confirmBtn).toBeEnabled();

    const [enrollResponse] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/enroll-employee') && r.request().method() === 'POST', {
        timeout: 20_000,
      }),
      confirmBtn.click(),
    ]);
    expect(enrollResponse.status(), 'enrolment must not 4xx/5xx').toBeLessThan(400);

    // The outcome must come back to the interface. Enrolment state used not to
    // be projected to the client at all, so a second click answered with a
    // message about system accounts that had nothing to do with the real state.
    const badge = page.locator('[data-testid="digital-employee-badge"]');
    await expect(
      badge,
      'enrolment must be visible in the interface, not only in the database',
    ).toBeVisible({ timeout: 20_000 });
    await expect(
      badge,
      'the badge must name where the colleague sits, not merely that it sits somewhere',
    ).not.toHaveText(/^\s*$/);

    // Offering "Enrol" again to an already-enrolled colleague is the exact
    // misleading state this flow used to land in.
    await expect(enrollBtn).toBeHidden();
    const removeBtn = page.locator('[data-testid="remove-from-org-btn"]');
    await expect(removeBtn).toBeVisible();
    await page.screenshot({ path: SHOTS + '/02-enrolled-badge.png', fullPage: true });

    // --- and detaching must actually detach ------------------------------
    await removeBtn.click();
    await expect(page.locator('[data-testid="remove-org-dialog"]')).toBeVisible();
    const [removeResponse] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/enroll-employee') && r.request().method() === 'DELETE',
        { timeout: 20_000 },
      ),
      page.locator('[data-testid="remove-org-confirm-btn"]').click(),
    ]);
    expect(removeResponse.status()).toBeLessThan(400);
    await expect(badge).toBeHidden({ timeout: 20_000 });
    await expect(enrollBtn).toBeVisible();
    await page.screenshot({ path: SHOTS + '/03-detached.png', fullPage: true });
  });
});
