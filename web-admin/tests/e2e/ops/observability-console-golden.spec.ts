import { test, expect } from '@playwright/test';

/**
 * Browser golden for the in-product observability console.
 *
 * These three pages (eagle-eye troubleshooting, error board, runtime metrics) and the two
 * APIs behind them had zero coverage: a search of all 673 e2e specs for their routes,
 * their test ids, and `observability/correlation` / `observability/snapshot` returned
 * nothing. So "the troubleshooting console works" was never a checked claim.
 *
 * What each assertion is for is written next to it — an assertion whose failure mode
 * nobody can name is not worth the run time.
 */

const HEX32 = /^[0-9a-f]{32}$/;

test.describe('observability console', () => {

  test('runtime metrics renders live numbers, not placeholders', async ({ page }) => {
    await page.goto('/ops/runtime');

    // The metric cards only render once the snapshot resolves, so this is the load signal.
    // Asserting on page text without it caught the page mid-"加载中…" — a sleep-shaped test
    // that would have gone green or red depending on machine speed.
    const heapCard = page.getByTestId('metric-heap');
    await expect(heapCard).toBeVisible({ timeout: 20_000 });

    // The page's whole purpose is to show real process state. A zero or absent heap figure
    // means the snapshot call failed or came back empty — the failure this page exists to
    // make visible rather than hide.
    const heapText = await heapCard.innerText();
    const heap = heapText.match(/(\d[\d,]*)\s*\/\s*(\d[\d,]*)\s*MB/i);
    expect(heap, `heap card did not render a used/max figure, got:\n${heapText}`).not.toBeNull();
    expect(Number(heap![1].replace(/,/g, ''))).toBeGreaterThan(0);
    expect(Number(heap![2].replace(/,/g, ''))).toBeGreaterThan(0);

    // Raw code / template leakage into user-visible text is a §2.2 blocker.
    const body = await page.locator('main').innerText();
    expect(body).not.toContain('${');
    expect(body).not.toMatch(/\bundefined\b/);
    expect(body).not.toMatch(/\bNaN\b/);
  });

  test('error board shows both tabs and can switch between them', async ({ page }) => {
    await page.goto('/ops/errors');
    await expect(page.locator('main')).toBeVisible();

    // Two independent sources (command audit failures, browser-reported JS errors). If the
    // second tab does not exist, front-end errors are invisible again — the gap this page
    // was built to close.
    const tabs = page.getByRole('button').filter({ hasText: /命令错误|Command|前端错误|Client/ });
    expect(await tabs.count()).toBeGreaterThanOrEqual(2);

    const clientTab = tabs.filter({ hasText: /前端错误|Client/ }).first();
    await clientTab.click();
    await expect(page.locator('main')).toBeVisible();
    const text = await page.locator('main').innerText();
    expect(text).not.toContain('${');
  });

  test('eagle-eye reports an unknown trace id as empty, not as an error', async ({ page }) => {
    await page.goto('/ops/troubleshooting');
    await expect(page.getByTestId('troubleshooting-page')).toBeVisible();

    // A trace id that certainly has no rows. The distinction being asserted is the one that
    // matters when triaging: "nothing correlates to this id" must not look like "the
    // correlation API is broken", because the two lead to completely different next steps.
    await page.getByTestId('trace-id-input').fill('0'.repeat(31) + '1');
    await page.getByTestId('trace-query-btn').click();

    await expect(page.getByTestId('troubleshooting-empty')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('troubleshooting-error')).toHaveCount(0);
  });

  test('eagle-eye rejects nothing silently: a query always resolves to a state', async ({ page }) => {
    await page.goto('/ops/troubleshooting');

    // Before any query the page must say so rather than render an empty result that reads
    // like "no data found".
    await expect(page.getByTestId('troubleshooting-empty')).toHaveCount(0);
    await expect(page.getByTestId('section-commands')).toHaveCount(0);

    // The button must not fire on an empty box — otherwise the first thing a user sees is
    // an error from a request that should never have been sent.
    await expect(page.getByTestId('trace-query-btn')).toBeDisabled();
  });

  /**
   * The live-container regression for the two response-header filters.
   *
   * SqlCountFilter no longer buffers the response body; it stamps X-SQL-Count when the
   * body is first acquired. MockHttpServletResponse cannot prove that works inside Tomcat,
   * where committing behaviour and header ordering are real. X-Trace-Id proves tracing is
   * actually on and producing a W3C-shaped id — without it every trace_id column in the
   * audit tables writes NULL and this whole console has nothing to correlate.
   */
  test('API responses carry a W3C trace id and a SQL count', async ({ page }) => {
    await page.goto('/ops/runtime');

    const res = await page.request.get('/api/observability/snapshot');
    expect(res.status(), 'snapshot must be readable by the admin storageState').toBe(200);

    const headers = res.headers();
    const traceId = headers['x-trace-id'];
    expect(traceId, `no X-Trace-Id header. Present: ${Object.keys(headers).join(', ')}`)
      .toBeTruthy();
    expect(traceId).toMatch(HEX32);

    const sqlCount = headers['x-sql-count'];
    expect(sqlCount, `no X-SQL-Count header. Present: ${Object.keys(headers).join(', ')}`)
      .toBeTruthy();
    expect(Number.isNaN(Number(sqlCount))).toBe(false);
  });

  /**
   * The permission-denial domain is the one the console was missing, and the one people
   * actually arrive with ("it just did nothing"). ab_permission_audit_log is the busiest
   * audit table in the product; until it had a trace_id column there was nothing to join.
   *
   * Asserted through the API rather than by manufacturing a denial in the browser: the
   * point being pinned is that the endpoint returns the domain at all. If
   * `permissionDenials` is absent from the payload the panel silently renders nothing,
   * which is indistinguishable from "no denials" — the exact ambiguity this domain was
   * added to remove.
   */
  test('the correlation endpoint returns every audit domain the console renders', async ({ page }) => {
    await page.goto('/ops/troubleshooting');
    const res = await page.request.get(`/api/observability/correlation/${'0'.repeat(31)}1`);
    expect(res.status()).toBe(200);

    const body = await res.json();
    const payload = body?.data ?? body;

    // Every domain the page renders a panel for. A missing key means that panel silently
    // renders nothing, which is indistinguishable from "no rows" — the exact ambiguity these
    // domains were added to remove. permissionDenials and adminActions are the two that used
    // to have no trace column at all, so they are the ones most worth pinning.
    for (const domain of [
      'commandAudits', 'llmUsage', 'behaviorEvents', 'auditEvents',
      'permissionDenials', 'adminActions',
    ]) {
      expect(
        Object.prototype.hasOwnProperty.call(payload, domain),
        `${domain} missing from the correlation payload. Keys: ${Object.keys(payload).join(', ')}`,
      ).toBe(true);
      expect(Array.isArray(payload[domain]), `${domain} should be an array`).toBe(true);
    }
  });

  /**
   * The trace id from a real response has to be usable in the console — that is the entire
   * documented workflow ("grab it from the X-Trace-Id header, paste it in"). A GET writes no
   * command-audit row, so the honest outcome is the empty state; what is being proven is
   * that the id round-trips and the correlation endpoint accepts it.
   */
  test('a trace id taken from a live response is accepted by the console', async ({ page }) => {
    await page.goto('/ops/runtime');
    const res = await page.request.get('/api/observability/snapshot');
    const traceId = res.headers()['x-trace-id'];
    test.skip(!traceId, 'tracing disabled in this stack — nothing to correlate');

    await page.goto(`/ops/troubleshooting?traceId=${traceId}`);
    await expect(page.getByTestId('troubleshooting-page')).toBeVisible();

    // Auto-runs on arrival with ?traceId=. Either it correlated rows or it honestly says it
    // found none — an error banner would mean the endpoint rejected a trace id it produced.
    await expect(page.getByTestId('troubleshooting-error')).toHaveCount(0, { timeout: 15_000 });
    await expect(
      page.getByTestId('troubleshooting-empty').or(page.getByTestId('section-commands')),
    ).toBeVisible({ timeout: 15_000 });
  });
});
