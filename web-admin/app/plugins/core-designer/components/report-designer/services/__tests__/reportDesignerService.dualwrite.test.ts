/**
 * Phase 4 slice 2b-1 — frontend-orchestrated dual-write unit tests.
 *
 * `reportDesignerService.save()` must:
 *  1. persist the report to the canonical page-schema store (`/api/pages`), AND
 *  2. ALSO upsert an `ab_report` shadow keyed by the SAME page pid
 *     (`PUT /api/report-definitions/{pid}` with `{code, title, profile, dsl}`).
 *
 * The shadow write is best-effort: a shadow failure must NOT throw / must NOT change the
 * function's return (the page pid) — the page-schema store stays canonical and a later
 * backfill reconciles drift. These tests pin both calls + the best-effort contract.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { reportDesignerService } from '../reportDesignerService';
import { createEmptyReport, type ReportDsl } from '../../types';

const PAGE_PID = 'PAGE_PID_01234567890123456';

/** Build a `Response` carrying the platform `{code,message,data}` envelope. */
function apiResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ code: 0, message: 'ok', data }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function makeReport(): ReportDsl {
  const dsl = createEmptyReport('Q3 Sales');
  // give it something distinctive so we can assert the dsl body is forwarded to the shadow
  dsl.dataSources = { orders: { type: 'static', data: [{ id: 1 }] } };
  return dsl;
}

describe('reportDesignerService.save dual-write (page-schema + ab_report shadow)', () => {
  let consoleWarnSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    consoleWarnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('POSTs /api/pages then PUTs the ab_report shadow under the returned page pid (create path)', async () => {
    const fetchMock = vi
      .fn()
      // 1st call: POST /api/pages -> returns the minted page pid
      .mockResolvedValueOnce(apiResponse({ pid: PAGE_PID, pageKey: 'report_q3_sales_x', kind: 'list' }))
      // 2nd call: PUT /api/report-definitions/{pid} -> shadow upsert ok
      .mockResolvedValueOnce(apiResponse({ pid: PAGE_PID, code: 'c', title: 'Q3 Sales' }));
    vi.stubGlobal('fetch', fetchMock);

    const returnedPid = await reportDesignerService.save(makeReport());

    // return is the page pid (unchanged behavior)
    expect(returnedPid).toBe(PAGE_PID);

    // exactly two calls, in order: page-schema then shadow
    expect(fetchMock).toHaveBeenCalledTimes(2);

    const [firstUrl, firstInit] = fetchMock.mock.calls[0];
    expect(firstUrl).toBe('/api/pages');
    expect(String(firstInit.method).toLowerCase()).toBe('post');

    const [shadowUrl, shadowInit] = fetchMock.mock.calls[1];
    // shadow is keyed by the SAME pid returned by the page save
    expect(shadowUrl).toBe(`/api/report-definitions/${PAGE_PID}`);
    expect(String(shadowInit.method).toLowerCase()).toBe('put');

    const shadowBody = JSON.parse(shadowInit.body as string);
    expect(shadowBody.title).toBe('Q3 Sales');
    expect(shadowBody.profile).toBe('paged-media');
    expect(shadowBody.code).toBeTruthy();
    // the full reportDsl is forwarded as the shadow dsl
    expect(shadowBody.dsl.title).toBe('Q3 Sales');
    expect(shadowBody.dsl.dataSources.orders.data[0].id).toBe(1);
  });

  it('PUTs the page (existing pid) then PUTs the ab_report shadow under the returned pid (update path)', async () => {
    const fetchMock = vi
      .fn()
      // 1st call: PUT /api/pages/{existingPid}
      .mockResolvedValueOnce(apiResponse({ pid: PAGE_PID, pageKey: 'report_q3_sales_x', kind: 'list' }))
      // 2nd call: PUT shadow
      .mockResolvedValueOnce(apiResponse({ pid: PAGE_PID }));
    vi.stubGlobal('fetch', fetchMock);

    const returnedPid = await reportDesignerService.save(makeReport(), PAGE_PID);

    expect(returnedPid).toBe(PAGE_PID);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    const [firstUrl, firstInit] = fetchMock.mock.calls[0];
    expect(firstUrl).toBe(`/api/pages/${PAGE_PID}`);
    expect(String(firstInit.method).toLowerCase()).toBe('put');

    const [shadowUrl, shadowInit] = fetchMock.mock.calls[1];
    expect(shadowUrl).toBe(`/api/report-definitions/${PAGE_PID}`);
    expect(String(shadowInit.method).toLowerCase()).toBe('put');
  });

  it('is best-effort: a shadow-write HTTP failure does NOT throw and still returns the page pid', async () => {
    const fetchMock = vi
      .fn()
      // page save succeeds
      .mockResolvedValueOnce(apiResponse({ pid: PAGE_PID, pageKey: 'report_q3_sales_x', kind: 'list' }))
      // shadow upsert returns 500
      .mockResolvedValueOnce(apiResponse({ error: 'boom' }, 500));
    vi.stubGlobal('fetch', fetchMock);

    const returnedPid = await reportDesignerService.save(makeReport());

    // did NOT throw, return is still the page pid
    expect(returnedPid).toBe(PAGE_PID);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    // a warning was logged for the failed shadow
    expect(consoleWarnSpy).toHaveBeenCalled();
  });

  it('is best-effort: a shadow-write network rejection does NOT throw and still returns the page pid', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(apiResponse({ pid: PAGE_PID, pageKey: 'report_q3_sales_x', kind: 'list' }))
      // network-level failure on the shadow call
      .mockRejectedValueOnce(new Error('network down'));
    vi.stubGlobal('fetch', fetchMock);

    const returnedPid = await reportDesignerService.save(makeReport());

    expect(returnedPid).toBe(PAGE_PID);
    expect(consoleWarnSpy).toHaveBeenCalled();
  });

  it('still fails the save if the canonical page-schema write fails (page error is NOT swallowed)', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(apiResponse({ error: 'bad' }, 500));
    vi.stubGlobal('fetch', fetchMock);

    await expect(reportDesignerService.save(makeReport())).rejects.toThrow();
    // shadow is never attempted when the canonical save fails
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
