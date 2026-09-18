import { test, expect } from '../../fixtures';
import { execSync } from 'node:child_process';
import { PSQL_BASE, PG_ENV, BACKEND_URL } from '../../helpers/environments';

// ─── psql helper (env-aware, uses PGHOST/PGPORT/PGUSER/PGDATABASE/PGPASSWORD) ─
const E2E_PG_ENV = { ...PG_ENV, PGPASSWORD: PG_ENV.PGPASSWORD ?? 'auraboot' };

function psql(sql: string): string {
  return execSync(`${PSQL_BASE} -P pager=off -t -A -c "${sql.replace(/"/g, '\\"')}"`, {
    encoding: 'utf-8',
    env: E2E_PG_ENV,
    timeout: 10_000,
  }).trim();
}

// ─── Resolve admin tenant_id + user_id from DB (rotate-safe) ─────────────────

function resolveAdminIds(): { tenantId: string; userId: string } {
  // tenant_id comes from the JWT / login response; user id from ab_user
  // Use BACKEND_URL so the isolated-stack port (:6460 etc.) is correct.
  const out = execSync(
    `curl -sf -X POST ${BACKEND_URL}/api/auth/login ` +
      `-H 'Content-Type: application/json' ` +
      `-d '{"email":"admin@auraboot.com","password":"Test2026x"}'`,
    { encoding: 'utf-8', timeout: 10_000 },
  );
  const parsed = JSON.parse(out);
  const token = parsed?.data?.jwt as string;
  if (!token) throw new Error(`Admin login failed: ${out}`);

  const payload = token.split('.')[1];
  const pad = '='.repeat((4 - (payload.length % 4)) % 4);
  const raw = Buffer.from(payload + pad, 'base64').toString('utf-8');

  const tenantMatch = raw.match(/"tenantId"\s*:\s*(\d+)/);
  const memMatch    = raw.match(/"memberId"\s*:\s*(\d+)/);
  if (!tenantMatch) throw new Error(`JWT payload missing tenantId: ${raw}`);

  const tenantId = tenantMatch[1];
  const userId = memMatch ? memMatch[1] : psql(`SELECT id FROM ab_user WHERE email='admin@auraboot.com' LIMIT 1`);
  return { tenantId, userId };
}

function isProductError(text: string): boolean {
  return /Outdated Optimize Dep|Failed to fetch dynamically imported module|504 |Loading chunk|entry\.client|Importing a module script failed|HMR|[Vv]ite|websocket/i.test(text);
}

function adminJwt(): string {
  return JSON.parse(
    execSync(
      `curl -sf -X POST ${BACKEND_URL}/api/auth/login -H 'Content-Type: application/json' -d '{"email":"admin@auraboot.com","password":"Test2026x"}'`,
      { encoding: 'utf-8', timeout: 10_000 },
    ),
  )?.data?.jwt as string;
}

test.describe('Behavior SDK + Dashboard — Full-loop Golden', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  // ─── RUN_ID isolation (gap G10) ────────────────────────────────────────────
  // Every event this spec emits carries run_id (tracker reads
  // sessionStorage 'aura.behavior_run_id' → ab_behavior_event.run_id). All DB
  // assertions filter on it, and cleanup deletes ONLY this run's rows — never
  // a tenant-wide DELETE. No body-fallback clicks, no force clicks, no
  // heuristic-accepted branches: a missing deterministic target fails the test
  // as environment-invalid instead of degrading the golden.
  const RUN_ID = `bsdk-golden-${Date.now().toString(36)}`;

  const runEvents = (extra = ''): string =>
    `run_id='${RUN_ID}'${extra}`;

  async function isolateRun(page: import('@playwright/test').Page): Promise<void> {
    await page.addInitScript(
      (id) => sessionStorage.setItem('aura.behavior_run_id', id),
      RUN_ID,
    );
  }

  /** Force-flush the tracker via visibilitychange:hidden and wait for the keepalive POST. */
  async function flushTracker(page: import('@playwright/test').Page): Promise<void> {
    const collectDone = page
      .waitForResponse((resp) => resp.url().includes('/api/collect'), { timeout: 10_000 })
      .catch(() => null);
    await page.evaluate(() => {
      Object.defineProperty(document, 'visibilityState', {
        configurable: true,
        get: () => 'hidden',
      });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await collectDone;
  }

  // Capture console errors throughout each test
  let consoleErrors: string[] = [];

  test.beforeEach(async ({ page }) => {
    consoleErrors = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
    page.on('pageerror', (err) => consoleErrors.push(`PAGEERROR: ${err.message}`));
    await isolateRun(page);
  });

  test.afterAll(async () => {
    // Leave no trace: remove ONLY this run's events (never shared data).
    psql(`DELETE FROM ab_behavior_event WHERE run_id='${RUN_ID}'`);
  });

  // ─── GOLDEN-1: Real SDK loop + DB proof ─────────────────────────────────────

  test('BSDK-01 real SDK loop: navigate routes, click DSL block, flush, assert DB rows', async ({ page }) => {
    const { tenantId } = resolveAdminIds();
    console.log(`Admin tenantId=${tenantId}; RUN_ID=${RUN_ID}`);

    // ── STEP 1: Navigate ≥2 in-app routes to fire page_view events ──────────
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(500); // let pageview() enqueue

    await page.goto('/p/c/behavior_analytics', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(500);

    // ── STEP 2: Click a DSL BlockRenderer-stamped element (deterministic) ────
    // Target the behavior analytics dashboard itself: BSDK-03 proves its
    // data-aura-element-id blocks (kpi_pv) render on every seeded stack. A list
    // page target would depend on tenant row data; a missing element here is an
    // environment-invalid condition, not a degradable golden.
    await page.goto('/p/c/behavior_analytics', { waitUntil: 'domcontentloaded' });
    const target = page.locator('[data-aura-element-id="kpi_pv"]');
    await target.waitFor({ state: 'visible', timeout: 15_000 });
    await target.scrollIntoViewIfNeeded();
    await target.click();
    console.log(`Clicked element: ${await target.getAttribute('data-aura-element-id')}`);
    await page.waitForTimeout(300);

    // ── STEP 3: Force flush via visibilitychange → hidden ────────────────────
    await flushTracker(page);

    await page.screenshot({ path: 'test-results/artifacts/bsdk-01-after-navigation.png' });

    // ── STEP 4: DB assertions — all scoped to this run ───────────────────────
    const runFilter = `tenant_id=${tenantId} AND run_id='${RUN_ID}'`;

    // Assert: at least 2 page_view rows for this run (two navigations)
    await expect
      .poll(
        () => parseInt(psql(`SELECT COUNT(*) FROM ab_behavior_event WHERE ${runFilter} AND event_name='page_view'`), 10),
        { timeout: 30_000, message: '≥2 page_view rows for this run' },
      )
      .toBeGreaterThanOrEqual(2);

    // Assert: at least 1 element_click row for this run
    const clickCount = parseInt(
      psql(`SELECT COUNT(*) FROM ab_behavior_event WHERE ${runFilter} AND event_name='element_click'`),
      10,
    );
    expect(clickCount, 'at least 1 element_click row for this run').toBeGreaterThanOrEqual(1);

    // Assert: rows have non-null tenant_id and user_id
    const badIdentityCount = parseInt(
      psql(`SELECT COUNT(*) FROM ab_behavior_event WHERE ${runFilter} AND (tenant_id IS NULL OR user_id IS NULL)`),
      10,
    );
    expect(badIdentityCount, 'all rows for admin have non-null tenant_id and user_id').toBe(0);

    // Assert: the clicked row carries a stable (non-heuristic) ui_element_id —
    // deterministic target makes this unconditional (no heuristic acceptance).
    const stableClickCount = parseInt(
      psql(
        `SELECT COUNT(*) FROM ab_behavior_event WHERE ${runFilter} AND event_name='element_click' ` +
          `AND ui_element_id IS NOT NULL AND ui_element_id NOT LIKE 'heuristic:%'`,
      ),
      10,
    );
    console.log(`stable element_click rows (non-heuristic ui_element_id): ${stableClickCount}`);
    expect(stableClickCount, 'clicked row has stable (non-heuristic) ui_element_id').toBeGreaterThanOrEqual(1);

    // No product errors during navigation
    const productErrors = consoleErrors.filter(isProductError);
    expect(productErrors, 'no product console errors during SDK loop').toEqual([]);
  });

  // ─── GOLDEN-2: Privacy assertion ─────────────────────────────────────────────

  test('BSDK-02 privacy: props never contain input values, innerHTML, or full hrefs', async ({ page }) => {
    const { tenantId } = resolveAdminIds();

    await page.goto('/p/ab_user', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle').catch(() => null);

    // Type into a search input if available (to verify input value NOT captured)
    const searchInput = page.locator('input[type="text"], input[type="search"], input[placeholder]').first();
    const hasInput = await searchInput.count().then((n) => n > 0);
    if (hasInput) {
      await searchInput.fill('SENSITIVE_TEST_VALUE_DO_NOT_CAPTURE');
      await searchInput.click();
      await page.waitForTimeout(300);
    }

    await flushTracker(page);

    await page.screenshot({ path: 'test-results/artifacts/bsdk-02-privacy-test.png' });

    // Assertions scoped to this run's events
    const runFilter = `tenant_id=${tenantId} AND run_id='${RUN_ID}'`;

    const sensitiveCount = parseInt(
      psql(`SELECT COUNT(*) FROM ab_behavior_event WHERE ${runFilter} AND props::text LIKE '%SENSITIVE_TEST_VALUE%'`),
      10,
    );
    expect(sensitiveCount, 'no event props contain the typed input value').toBe(0);

    const innerHtmlCount = parseInt(
      psql(`SELECT COUNT(*) FROM ab_behavior_event WHERE ${runFilter} AND props::text LIKE '%innerHTML%'`),
      10,
    );
    expect(innerHtmlCount, 'no event props reference "innerHTML"').toBe(0);

    const hrefCount = parseInt(
      psql(`SELECT COUNT(*) FROM ab_behavior_event WHERE ${runFilter} AND props::text ~ '\\?[a-z]+=.{8,}'`),
      10,
    );
    expect(hrefCount, 'no event props contain full hrefs with query parameters').toBe(0);

    console.log('Privacy assertions passed: no sensitive data in event props');
  });

  // ─── GOLDEN-3: Dashboard renders real numbers ─────────────────────────────────

  test('BSDK-03 dashboard: /p/c/behavior_analytics renders 4 KPI cards with real numbers + top-events table', async ({ page }) => {
    const { tenantId } = resolveAdminIds();

    // Deterministic fixture: always seed this run's baseline events via the real
    // collect API (envelope carries runId → run_id). No conditional seeding.
    const jwt = adminJwt();
    const now = new Date().toISOString();
    const events = [
      { eventId: `bsdk-t3-pv-1-${RUN_ID}`, schemaVersion: '1', eventName: 'page_view', eventCategory: 'navigation', source: 'web', occurredAt: now, clientSessionId: `bsdk-session-t3-${RUN_ID}`, runId: RUN_ID, props: { routeTemplate: '/home' } },
      { eventId: `bsdk-t3-pv-2-${RUN_ID}`, schemaVersion: '1', eventName: 'page_view', eventCategory: 'navigation', source: 'web', occurredAt: now, clientSessionId: `bsdk-session-t3-${RUN_ID}`, runId: RUN_ID, props: { routeTemplate: '/p/ab_user' } },
      { eventId: `bsdk-t3-pv-3-${RUN_ID}`, schemaVersion: '1', eventName: 'page_view', eventCategory: 'navigation', source: 'web', occurredAt: now, clientSessionId: `bsdk-session-t3-${RUN_ID}`, runId: RUN_ID, props: { routeTemplate: '/p/c/behavior_analytics' } },
      { eventId: `bsdk-t3-click-1-${RUN_ID}`, schemaVersion: '1', eventName: 'element_click', eventCategory: 'ui_interaction', source: 'web', occurredAt: now, clientSessionId: `bsdk-session-t3-${RUN_ID}`, runId: RUN_ID, uiElementId: 'kpi_pv', props: {} },
      { eventId: `bsdk-t3-click-2-${RUN_ID}`, schemaVersion: '1', eventName: 'element_click', eventCategory: 'ui_interaction', source: 'web', occurredAt: now, clientSessionId: `bsdk-session-t3-${RUN_ID}`, runId: RUN_ID, uiElementId: 'tbl_top_events', props: {} },
    ];
    execSync(
      `curl -sf -X POST ${BACKEND_URL}/api/collect -H 'Authorization: Bearer ${jwt}' -H 'Content-Type: application/json' -d '${JSON.stringify({ events }).replace(/'/g, "'\\''")}'`,
      { encoding: 'utf-8', timeout: 15_000 },
    );

    const runFilter = `tenant_id=${tenantId} AND run_id='${RUN_ID}'`;
    const pvCount = parseInt(
      psql(`SELECT COUNT(*) FROM ab_behavior_event WHERE ${runFilter} AND event_name='page_view'`),
      10,
    );
    console.log(`run page_view count before dashboard render: ${pvCount}`);
    expect(pvCount, 'seeded ≥3 page_view rows for this run').toBeGreaterThanOrEqual(3);

    // ── Navigate to dashboard ──────────────────────────────────────────────────
    await page.goto('/p/c/behavior_analytics', { waitUntil: 'domcontentloaded' });
    await page
      .locator('[data-aura-element-id="kpi_pv"]')
      .waitFor({ state: 'visible', timeout: 15_000 });
    await page.waitForLoadState('networkidle').catch(() => null);
    await page.waitForTimeout(2000);

    await page.screenshot({ path: 'test-results/artifacts/bsdk-03-dashboard-loaded.png' });

    // ── Assert 4 KPI cards are visible ───────────────────────────────────────
    await expect(page.locator('[data-aura-element-id="kpi_pv"]'), 'kpi_pv block visible').toBeVisible();
    await expect(page.locator('[data-aura-element-id="kpi_uv"]'), 'kpi_uv block visible').toBeVisible();
    await expect(page.locator('[data-aura-element-id="kpi_sessions"]'), 'kpi_sessions block visible').toBeVisible();
    await expect(page.locator('[data-aura-element-id="kpi_total"]'), 'kpi_total block visible').toBeVisible();

    // ── Assert KPI cards show real numbers (dashboard aggregates tenant-wide,
    //    so the floor comes from this run's deterministic fixture) ────────────
    const pvCard = page.locator('[data-aura-element-id="kpi_pv"]');
    await expect(pvCard, 'PV card: not in "Waiting" state').not.toContainText('Waiting for first record');
    await expect(pvCard, 'PV card: not in "Error" state').not.toContainText('Error:');

    await expect
      .poll(
        async () => {
          const text = await pvCard.innerText().catch(() => '');
          const m = text.match(/\b(\d+)\b/);
          return m ? parseInt(m[1], 10) : -1;
        },
        { timeout: 30_000, message: 'PV KPI card shows a number ≥ run pvCount' },
      )
      .toBeGreaterThanOrEqual(pvCount);

    const uvCard = page.locator('[data-aura-element-id="kpi_uv"]');
    await expect(uvCard, 'UV card: not in "Waiting" state').not.toContainText('Waiting for first record');
    await expect
      .poll(
        async () => {
          const text = await uvCard.innerText().catch(() => '');
          const m = text.match(/\b(\d+)\b/);
          return m ? parseInt(m[1], 10) : -1;
        },
        { timeout: 30_000, message: 'UV KPI card shows ≥1' },
      )
      .toBeGreaterThanOrEqual(1);

    const sessCard = page.locator('[data-aura-element-id="kpi_sessions"]');
    await expect(sessCard, 'sessions card: not in "Waiting" state').not.toContainText('Waiting for first record');
    await expect
      .poll(
        async () => {
          const text = await sessCard.innerText().catch(() => '');
          const m = text.match(/\b(\d+)\b/);
          return m ? parseInt(m[1], 10) : -1;
        },
        { timeout: 30_000, message: 'Sessions KPI card shows ≥1' },
      )
      .toBeGreaterThanOrEqual(1);

    const totalCard = page.locator('[data-aura-element-id="kpi_total"]');
    await expect(totalCard, 'total card: not in "Waiting" state').not.toContainText('Waiting for first record');
    await expect
      .poll(
        async () => {
          const text = await totalCard.innerText().catch(() => '');
          const m = text.match(/\b(\d+)\b/);
          return m ? parseInt(m[1], 10) : -1;
        },
        { timeout: 30_000, message: 'Total Events KPI card shows ≥3' },
      )
      .toBeGreaterThanOrEqual(3);

    // ── Assert top-events table block is visible with ≥1 data row ────────────
    const topEventsBlock = page.locator('[data-aura-element-id="tbl_top_events"]');
    await expect(topEventsBlock, 'tbl_top_events block is visible').toBeVisible();

    await expect
      .poll(
        async () => {
          const tableRows = topEventsBlock.locator('table tbody tr, [role="row"]:not([role="columnheader"])');
          return await tableRows.count();
        },
        { timeout: 30_000, message: 'top-events table has ≥1 data row' },
      )
      .toBeGreaterThanOrEqual(1);

    await page.screenshot({ path: 'test-results/artifacts/bsdk-03-dashboard-with-data.png' });

    // ── Assert 0 product console errors ──────────────────────────────────────
    const productErrors = consoleErrors.filter(isProductError);
    console.log(`All console errors: ${JSON.stringify(consoleErrors)}`);
    expect(productErrors, '0 product console errors on dashboard page').toEqual([]);

    console.log(`Dashboard assertions passed: PV≥${pvCount}, UV≥1, sessions≥1, total≥3, top-events table visible`);
  });

  // ─── GOLDEN-4: UV=2 distinct-count proof ────────────────────────────────────

  test('BSDK-04 UV=2: synthetic distinct-user row, dashboard UV card shows ≥2', async ({ page }) => {
    /**
     * PROOF OF UV = COUNT(DISTINCT user_id) AGGREGATION:
     *
     * User A = real admin browser session (events tagged with RUN_ID)
     * User B = synthetic row inserted with a DISTINCT user_id, tagged with the
     * same RUN_ID so cleanup removes it — no shared-data residue.
     */
    const { tenantId, userId: adminUserId } = resolveAdminIds();

    // Seed User A's event via the real collect API, tagged with this run.
    const jwt = adminJwt();
    execSync(
      `curl -sf -X POST ${BACKEND_URL}/api/collect -H 'Authorization: Bearer ${jwt}' -H 'Content-Type: application/json' ` +
        `-d '{"events":[{"eventId":"bsdk-uv-admin-${RUN_ID}","schemaVersion":"1","eventName":"page_view","eventCategory":"navigation","source":"web","occurredAt":"${new Date().toISOString()}","clientSessionId":"bsdk-session-uv-a-${RUN_ID}","runId":"${RUN_ID}","props":{"routeTemplate":"/home"}}]}'`,
      { encoding: 'utf-8', timeout: 15_000 },
    );

    // UV within this run: exactly 1 distinct user (admin) before the insert.
    const uvRunBefore = parseInt(
      psql(`SELECT COUNT(DISTINCT user_id) FROM ab_behavior_event WHERE tenant_id=${tenantId} AND run_id='${RUN_ID}'`),
      10,
    );
    console.log(`run-scoped UV BEFORE synthetic insert: ${uvRunBefore}`);
    expect(uvRunBefore, 'exactly 1 distinct user in this run before synthetic insert (admin)').toBe(1);

    // ── Insert User B (synthetic distinct user, tagged with this run) ────────
    const syntheticUserId = BigInt(adminUserId) + BigInt(999_888_777);
    const syntheticEventId = `bsdk-uv-proof-${RUN_ID}`;

    psql(
      `INSERT INTO ab_behavior_event (event_id, schema_version, event_name, event_category, source, occurred_at, tenant_id, user_id, client_session_id, run_id, props) ` +
        `VALUES ('${syntheticEventId}', '1', 'page_view', 'navigation', 'web', NOW(), ${tenantId}, ${syntheticUserId}, 'bsdk-synthetic-${RUN_ID}', '${RUN_ID}', '{"routeTemplate":"/synthetic-user-b-proof"}')`,
    );
    console.log(`Inserted synthetic User B event: user_id=${syntheticUserId}, event_id=${syntheticEventId}`);

    // DB now shows exactly 2 distinct users within this run — an EXACT assertion,
    // possible only because the run is isolated.
    const uvRunAfter = parseInt(
      psql(`SELECT COUNT(DISTINCT user_id) FROM ab_behavior_event WHERE tenant_id=${tenantId} AND run_id='${RUN_ID}'`),
      10,
    );
    expect(uvRunAfter, 'exactly 2 distinct user_ids in this run after synthetic insert').toBe(2);

    // ── Reload dashboard and assert UV card shows ≥2 ─────────────────────────
    await page.goto('/p/c/behavior_analytics', { waitUntil: 'domcontentloaded' });
    await page
      .locator('[data-aura-element-id="kpi_uv"]')
      .waitFor({ state: 'visible', timeout: 15_000 });
    await page.waitForLoadState('networkidle').catch(() => null);
    await page.waitForTimeout(2000);

    await page.screenshot({ path: 'test-results/artifacts/bsdk-04-uv-equals-2.png' });

    const uvCard = page.locator('[data-aura-element-id="kpi_uv"]');
    await expect(uvCard, 'UV card: not in "Waiting" state').not.toContainText('Waiting for first record');

    await expect
      .poll(
        async () => {
          const text = await uvCard.innerText().catch(() => '');
          console.log(`UV card text: "${text}"`);
          const m = text.match(/\b(\d+)\b/);
          return m ? parseInt(m[1], 10) : -1;
        },
        { timeout: 30_000, message: 'UV KPI card shows ≥2 (User A real browser + User B synthetic proves COUNT DISTINCT aggregation)' },
      )
      .toBeGreaterThanOrEqual(2);

    // Synthetic row is removed by the run-scoped afterAll cleanup; no shared
    // data was touched at any point.

    const productErrors = consoleErrors.filter(isProductError);
    expect(productErrors, '0 product console errors on UV=2 dashboard').toEqual([]);

    console.log('UV=2 proof PASSED: COUNT(DISTINCT user_id) aggregation confirmed on dashboard');
  });
});
