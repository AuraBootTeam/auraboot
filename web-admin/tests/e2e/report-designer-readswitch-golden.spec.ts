/**
 * Phase 4 slice 2b-2 — report READ-switch golden (real browser, host-first stack).
 *
 * Slice 2b-1 made the live designer dual-write every save into `ab_report` keyed by the SAME
 * page pid (code == pageKey). This slice switches the READ paths to read `ab_report` FIRST.
 * This golden proves the read switch end-to-end against the REAL backend:
 *
 *   1. Drive the actual designer Save → capture the dual-write PUT (pid + code + dsl it posted).
 *   2. loadByPid source: GET /api/report-definitions/{pid} returns the saved dsl from ab_report.
 *   3. loadByPageKey (viewer) source: GET /api/report-definitions/by-code/{code} returns the same
 *      dsl from ab_report (code == the report's pageKey).
 *   4. Export reads ab_report: POST /api/reports/export/pdf with that pid succeeds and streams a
 *      real PDF (the export service's loadReportDsl reads ab_report first).
 *
 * Robust-to-flaky-palette: like the sibling dual-write golden, we do NOT author a canvas block
 * (the palette block-add + title input are independently flaky in this host stack, documented in
 * #958). We seed via the designer's own dual-write PUT (driven by Save) and assert the READ paths
 * read it back — exactly what this slice changes.
 */
import { expect, test } from '@playwright/test';

test.describe('Report Designer — read switch (ab_report-first reads + export)', () => {
  test('designer save → reads + export resolve from ab_report (loadByPid, by-code, export pdf)', async ({
    page,
  }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('block-palette')).toBeVisible();
    await expect(page.getByTestId('report-canvas')).toBeVisible();

    // Capture the dual-write upsert RESPONSE (PUT /api/report-definitions/{pid}) the designer fires
    // after the canonical page save. Waiting on the response guarantees the shadow committed before
    // we read it back, and the request body is the source of truth for {pid, code, dsl}.
    const shadowRespPromise = page.waitForResponse(
      (resp) =>
        resp.request().method() === 'PUT' &&
        /\/api\/report-definitions\/[^/]+$/.test(new URL(resp.url()).pathname),
      { timeout: 20_000 },
    );

    await page.getByRole('button', { name: 'Save' }).click();

    const shadowResp = await shadowRespPromise;
    expect(shadowResp.status(), 'dual-write upsert PUT should succeed').toBe(200);
    const pid = new URL(shadowResp.url()).pathname.split('/').pop() as string;
    expect(pid, 'page pid captured from the dual-write response').toBeTruthy();

    const written = JSON.parse(shadowResp.request().postData() ?? '{}') as {
      code?: string;
      profile?: string;
      dsl?: { title?: string };
    };
    expect(written.code, 'dual-write sent the report code (== pageKey)').toBeTruthy();
    expect(written.dsl, 'dual-write sent the report dsl object').toBeTruthy();
    const code = written.code as string;
    const writtenTitle = written.dsl?.title;

    // ── 1. loadByPid source: GET /api/report-definitions/{pid} reads ab_report ───────────────
    const byPid = await page.request.get(`/api/report-definitions/${pid}`);
    expect(byPid.status(), `GET /api/report-definitions/${pid} should be 200 (ab_report)`).toBe(200);
    const byPidData = (await byPid.json()).data;
    expect(byPidData.pid).toBe(pid);
    expect(typeof byPidData.dsl, 'dsl is a real object from ab_report').toBe('object');
    expect(byPidData.dsl.title).toBe(writtenTitle);

    // ── 2. loadByPageKey (viewer) source: GET .../by-code/{code} reads ab_report ─────────────
    const byCode = await page.request.get(`/api/report-definitions/by-code/${code}`);
    expect(byCode.status(), `GET /api/report-definitions/by-code/${code} should be 200`).toBe(200);
    const byCodeData = (await byCode.json()).data;
    expect(byCodeData.pid).toBe(pid);
    expect(byCodeData.code).toBe(code);
    expect(byCodeData.dsl.title).toBe(writtenTitle);

    // ── 3. Export reads ab_report: POST /api/reports/export/pdf with that pid succeeds ───────
    const exportResp = await page.request.post('/api/reports/export/pdf', {
      data: { reportPid: pid },
    });
    expect(exportResp.status(), 'PDF export should succeed reading ab_report').toBe(200);
    const pdfBytes = await exportResp.body();
    // Real PDF magic header — proves the export rendered from the ab_report dsl, not a stub.
    expect(pdfBytes.length).toBeGreaterThan(100);
    expect(pdfBytes.subarray(0, 5).toString('latin1')).toBe('%PDF-');
  });
});
