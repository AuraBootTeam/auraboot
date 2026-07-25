/**
 * Data-plane review golden — report schedule id precision.
 *
 * WHY: `GET /api/report-schedules` returns the snowflake primary key as a JSON
 * *number* (`id: 339377482211921920`). That value exceeds Number.MAX_SAFE_INTEGER
 * (2^53-1), so `JSON.parse` in the browser silently rounds it. The list page
 * (`app/routes/report-schedules/index.tsx`) then builds its action URLs from that
 * rounded value — `handleTestSend(schedule.id)` / `handleDelete(schedule.id)` —
 * so every row action targets an id that does not exist.
 *
 * This golden drives the REAL page with a REAL backend and asserts on the actual
 * outbound request URL, not on a reimplementation of the arithmetic.
 *
 * Falsifiability: if the payload were emitted as a string (or the UI switched to
 * `pid`), `sentId === trueId` would hold and this spec would fail — it is not a
 * tautology. The control assertion below pins that the row itself rendered, so a
 * page that renders nothing cannot make this pass vacuously.
 */
import { expect, test } from '@playwright/test';

const SCHEDULE_NAME = `DP66 precision probe ${Date.now()}`;

test.describe('Data-plane review — report schedule row actions', () => {
  // KNOWN PRODUCT DEFECT — kept as fixme, not as a red gate.
  //
  // This spec currently FAILS by design: it pins the bug described in
  // auraboot-enterprise/docs/handover/HANDOVER-2026-07-25-oss-dataplane-capability-review.md
  // §P0-1 (snowflake id emitted as a JSON number → browser rounds it → every row
  // action targets a nonexistent id). Landing it green-by-weakening would destroy the
  // evidence; landing it red would make the suite born-red and mask real failures
  // (AGENTS §"born-red gate = 负资产"). So it is fixme until P0-1 is fixed — flip this
  // to `test(...)` in the same commit that fixes the controller/UI and it must go green.
  test.fixme('test-send sends a JS-rounded id that the backend cannot resolve', async ({
    page,
    request,
  }) => {
    // --- arrange: create a report definition + schedule through the real API ----
    const defResp = await request.post('/api/report-definitions', {
      data: {
        code: `dp66_precision_${Date.now()}`,
        title: 'DP66 Precision Probe',
        profile: 'paged-media',
        dsl: { blocks: [], title: 'DP66 Precision Probe' },
      },
    });
    expect(defResp.ok(), `create report definition: ${defResp.status()}`).toBe(true);
    const reportPid = (await defResp.json()).data.pid as string;

    const schedResp = await request.post('/api/report-schedules', {
      data: {
        name: SCHEDULE_NAME,
        reportId: reportPid,
        scheduleCron: '0 0 3 * * ?',
        recipients: ['nobody@invalid.example'],
        format: 'pdf',
        enabled: true,
      },
    });
    expect(schedResp.ok(), `create schedule: ${schedResp.status()}`).toBe(true);

    // The id as the SERVER means it. Read it out of the raw text so this test's own
    // JSON.parse cannot be the thing that rounds it.
    const rawSchedule = await schedResp.text();
    const trueId = rawSchedule.match(/"id"\s*:\s*(\d+)/)?.[1];
    expect(trueId, 'schedule id present in raw response').toBeTruthy();
    expect(
      Number(trueId) > Number.MAX_SAFE_INTEGER,
      `precondition: id ${trueId} must exceed 2^53-1, else this spec proves nothing`,
    ).toBe(true);

    // --- act: drive the real list page ----------------------------------------
    await page.goto('/report-schedules', { waitUntil: 'domcontentloaded' });

    // Control: the row must actually render, otherwise the assertion below is vacuous.
    const row = page.getByText(SCHEDULE_NAME, { exact: false }).first();
    await expect(row, 'the schedule row rendered on the page').toBeVisible({ timeout: 15_000 });
    await row.scrollIntoViewIfNeeded();

    const testSendReq = page.waitForRequest(
      (req) => req.method() === 'POST' && /\/api\/report-schedules\/\d+\/test-send$/.test(req.url()),
      { timeout: 15_000 },
    );
    const testSendResp = page.waitForResponse(
      (resp) => /\/api\/report-schedules\/\d+\/test-send$/.test(resp.url()),
      { timeout: 15_000 },
    );

    await page.getByRole('button', { name: /test send/i }).first().click();

    const sentUrl = (await testSendReq).url();
    const sentId = sentUrl.match(/report-schedules\/(\d+)\/test-send/)?.[1];
    const status = (await testSendResp).status();

    await page.screenshot({
      path: 'test-results/dataplane-review/report-schedule-test-send.png',
      fullPage: false,
    });

    // --- assert ----------------------------------------------------------------
    // eslint-disable-next-line no-console
    console.log(`[dp66] trueId=${trueId} sentId=${sentId} status=${status}`);

    expect(sentId, 'the UI must send the id the server issued').toBe(trueId);
    expect(status, 'test-send must resolve the schedule').toBeLessThan(400);
  });
});
