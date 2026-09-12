/** Report-definition reads and export contract through a saved report. */
import { expect, test } from '../../tests/fixtures';

test.describe('Report Designer — read switch (ab_report-only reads + export)', () => {
  test('designer save → reads + export resolve from ab_report (loadByPid, by-code, export pdf)', async ({
    page,
  }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('block-palette')).toBeVisible();
    await expect(page.getByTestId('report-canvas')).toBeVisible();

    // Capture the canonical save upsert RESPONSE (PUT /api/report-definitions/{pid}) the designer fires
    // after the canonical page save. Waiting on the response guarantees the saved committed before
    // we read it back, and the request body is the source of truth for {pid, code, dsl}.
    const savedRespPromise = page.waitForResponse(
      (resp) =>
        resp.request().method() === 'POST' &&
        /\/api\/report-definitions$/.test(new URL(resp.url()).pathname),
      { timeout: 20_000 },
    );

    await page.getByRole('button', { name: 'Save' }).click();

    const savedResp = await savedRespPromise;
    expect(savedResp.status(), 'canonical save upsert PUT should succeed').toBe(200);
    const pid = (await savedResp.json()).data.pid as string;
    expect(pid, 'page pid captured from the canonical save response').toBeTruthy();

    const written = JSON.parse(savedResp.request().postData() ?? '{}') as {
      code?: string;
      profile?: string;
      dsl?: { title?: string };
    };
    expect(written.code, 'canonical save sent the report code (== pageKey)').toBeTruthy();
    expect(written.dsl, 'canonical save sent the report dsl object').toBeTruthy();
    const code = written.code as string;
    const writtenTitle = written.dsl?.title;

    // ── 1. loadByPid source: GET /api/report-definitions/{pid} reads ab_report ───────────────
    const byPid = await page.request.get(`/api/report-definitions/${pid}`);
    expect(byPid.status(), `GET /api/report-definitions/${pid} should be 200 (ab_report)`).toBe(
      200,
    );
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
