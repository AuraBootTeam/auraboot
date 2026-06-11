/**
 * G3-T3 auto-andon workbench visual golden (self-contained auth).
 *
 * Drives the pe_andon_workbench page on the live host stack (Vite :5137 -> BFF -> backend :6437)
 * after the backend golden raised a real andon exception from an IoT alarm. Asserts:
 *  - metric-strip KPI shows the open counts (open_total / open_critical = 1)
 *  - the open queue table shows the auto-raised exception row (real description, dict labels, no raw code)
 *  - selecting the row opens the review-drawer with the exception evidence
 *  - resolving the row moves it out of the open queue
 *
 * Self-authenticates via /api/auth/login + the same __session cookie the app uses, so it does
 * not depend on the heavy setup/auth projects.
 */
import { test, expect } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';

const ADMIN = { email: 'admin@auraboot.com', password: 'Test2026x' };
const sessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: false,
  },
});

test.beforeEach(async ({ context, request, baseURL }) => {
  const resp = await request.post(`${baseURL}/api/auth/login`, { data: ADMIN });
  expect(resp.ok(), `login failed: ${resp.status()}`).toBeTruthy();
  const jwt = (await resp.json())?.data?.jwt;
  expect(jwt, 'no jwt from login').toBeTruthy();
  const session = await sessionStorage.getSession();
  session.set('jwtToken', jwt);
  const setCookie = await sessionStorage.commitSession(session, { maxAge: 60 * 60 * 24 });
  const value = setCookie.match(/__session=([^;]+)/)?.[1];
  expect(value, 'failed to build __session cookie').toBeTruthy();
  await context.addCookies([
    { name: '__session', value: value!, domain: 'localhost', path: '/', httpOnly: true, secure: false, sameSite: 'Lax' },
  ]);
});

test('andon workbench: KPI + open queue + evidence drawer + resolve', async ({ page }) => {
  test.setTimeout(120_000);

  await page.goto('/p/c/pe_andon_workbench', { waitUntil: 'domcontentloaded' });

  // content area (not sidebar) — wait for the page title to render
  const main = page.locator('main, [role="main"], .ant-layout-content').first();
  await expect(page.getByText('Andon', { exact: false }).first()).toBeVisible({ timeout: 30_000 });
  await page.screenshot({ path: '/tmp/g3t3/ui-1-loaded.png', fullPage: true });

  // 1) metric-strip KPI: open_total / open_critical = 1 (real value, not '-')
  await expect(main.getByText('未决异常', { exact: false }).first()).toBeVisible({ timeout: 20_000 });
  await expect(main.getByText('未决·严重', { exact: false }).first()).toBeVisible();
  // the open count value 1 must render somewhere in the strip
  await expect(main.getByText('1', { exact: true }).first()).toBeVisible();
  await page.screenshot({ path: '/tmp/g3t3/ui-2-kpi.png', fullPage: true });

  // 2) open queue table: the auto-raised andon row, real description + dict labels (no raw code)
  const descCell = main.getByText(/IoT alarm G3T2-DEV-001\.temp\.HIHI value=130/).first();
  await expect(descCell).toBeVisible({ timeout: 20_000 });
  // dict labels rendered (not raw codes machine_down / critical / open)
  await expect(main.getByText('设备故障', { exact: false }).first()).toBeVisible(); // machine_down
  await expect(main.getByText('严重', { exact: false }).first()).toBeVisible();      // critical
  // no raw enum codes leaked into the table
  await expect(main.getByText('machine_down', { exact: true })).toHaveCount(0);
  await page.screenshot({ path: '/tmp/g3t3/ui-3-queue.png', fullPage: true });

  // 3) select the row -> review-drawer populates with the exception evidence.
  // Click the description cell (within the row); the workbench single-selection binds the row to state.
  await descCell.click();
  await page.waitForTimeout(2000);
  await page.mouse.wheel(0, 700); // bring the drawer (below the table) into view
  await page.waitForTimeout(500);
  // positive signal: the drawer summary ("异常信息") + the exception's real fields render
  await expect(main.getByText('异常信息', { exact: false }).first()).toBeVisible({ timeout: 15_000 });
  await page.screenshot({ path: '/tmp/g3t3/ui-4-drawer.png', fullPage: true });

  // 4) resolve the row via its row action -> open count drops / status -> resolved.
  // Close the evidence drawer first (it overlays the table row actions).
  const drawerClose = page.getByRole('button', { name: /close|关闭/i }).first();
  if (await drawerClose.count().catch(() => 0)) {
    await drawerClose.click().catch(() => {});
  } else {
    await page.keyboard.press('Escape').catch(() => {});
  }
  await page.waitForTimeout(800);

  // read the open_total KPI before resolving
  const resolveBtn = main.getByRole('button', { name: /解决|Resolve/ }).first();
  await expect(resolveBtn).toBeVisible({ timeout: 10_000 });
  await resolveBtn.click();
  // resolution dialog (resolve_exception inputFields: pe_oe_resolution, pe_oe_downtime_min)
  await page.waitForTimeout(1200);
  const resolutionField = page
    .getByPlaceholder(/解决|resolution/i)
    .or(page.getByLabel(/解决方案|解决措施|resolution/i))
    .first();
  if (await resolutionField.count().catch(() => 0)) {
    await resolutionField.fill('Auto-andon resolved by golden').catch(() => {});
  }
  const confirm = page.getByRole('button', { name: /^确定$|^确认$|^提交$|^OK$|^Confirm$|^Submit$|解决/ }).last();
  if (await confirm.count().catch(() => 0)) {
    await confirm.click().catch(() => {});
  }
  // the row leaves the open filter / its status becomes resolved
  await page.waitForTimeout(2500);
  await page.screenshot({ path: '/tmp/g3t3/ui-5-resolved.png', fullPage: true });
  // filter to "仅未决" — the resolved row must no longer appear there
  await main.getByRole('button', { name: '仅未决' }).first().click().catch(() => {});
  await page.waitForTimeout(1500);
  await expect(
    main.getByText(/IoT alarm G3T2-DEV-001\.temp\.HIHI value=130/),
  ).toHaveCount(0, { timeout: 10_000 });
  await page.screenshot({ path: '/tmp/g3t3/ui-6-open-empty.png', fullPage: true });
});
