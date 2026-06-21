/**
 * Phase 4 slice 2b-2 — frontend read-switch unit tests.
 *
 * The report read paths must prefer the first-class `ab_report` store and fall back to the legacy
 * page-schema store, returning the SAME `{dsl, pid}` shape from either source:
 *
 *  - `loadByPid(pid)`        → `GET /api/report-definitions/{pid}`, on 404/error → `GET /api/pages/{pid}`.
 *  - `loadByPageKey(pageKey)`→ `GET /api/report-definitions/by-code/{pageKey}`, on 404/error
 *                              → `GET /api/pages/key/{pageKey}`.
 *
 * The report-definitions GET returns `dsl` as a JSON OBJECT; the pages path returns
 * `extension.reportDsl`. Both are the same ReportDsl, normalized to `{dsl, pid}`.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import { reportDesignerService } from '../reportDesignerService';
import type { ReportDsl } from '../../types';

const PID = 'RPT_PID_01234567890123456';
const PAGE_KEY = 'report_q3_sales_x';

/** Build a `Response` carrying the platform `{code,message,data}` envelope. */
function apiResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ code: 0, message: 'ok', data }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** A distinctive ReportDsl so we can assert which source the dsl came from. */
function dsl(title: string): ReportDsl {
  return { title, body: [], dataSources: {} } as unknown as ReportDsl;
}

describe('reportDesignerService read-switch (ab_report-first + page-schema fallback)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  describe('loadByPid', () => {
    it('reads ab_report (report-definitions) FIRST and returns {dsl, pid}', async () => {
      const fetchMock = vi
        .fn()
        // GET /api/report-definitions/{pid} → ab_report record with dsl as a JSON object
        .mockResolvedValueOnce(apiResponse({ pid: PID, code: 'c', dsl: dsl('From ab_report') }));
      vi.stubGlobal('fetch', fetchMock);

      const result = await reportDesignerService.loadByPid(PID);

      expect(result.pid).toBe(PID);
      expect(result.dsl.title).toBe('From ab_report');
      // only the report-definitions endpoint was hit — no page-schema fallback
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(fetchMock.mock.calls[0][0]).toBe(`/api/report-definitions/${PID}`);
    });

    it('falls back to /api/pages/{pid} on a 404 from report-definitions', async () => {
      const fetchMock = vi
        .fn()
        // report-definitions GET → 404 (report not yet in ab_report)
        .mockResolvedValueOnce(apiResponse({ message: 'not found' }, 404))
        // fallback page-schema GET → extension.reportDsl
        .mockResolvedValueOnce(
          apiResponse({ pid: PID, pageKey: PAGE_KEY, extension: { reportDsl: dsl('From page-schema') } }),
        );
      vi.stubGlobal('fetch', fetchMock);

      const result = await reportDesignerService.loadByPid(PID);

      expect(result.pid).toBe(PID);
      expect(result.dsl.title).toBe('From page-schema');
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(fetchMock.mock.calls[0][0]).toBe(`/api/report-definitions/${PID}`);
      expect(fetchMock.mock.calls[1][0]).toBe(`/api/pages/${PID}`);
    });

    it('falls back to /api/pages/{pid} on a network rejection from report-definitions', async () => {
      const fetchMock = vi
        .fn()
        .mockRejectedValueOnce(new Error('network down'))
        .mockResolvedValueOnce(
          apiResponse({ pid: PID, pageKey: PAGE_KEY, extension: { reportDsl: dsl('Fallback OK') } }),
        );
      vi.stubGlobal('fetch', fetchMock);

      const result = await reportDesignerService.loadByPid(PID);

      expect(result.dsl.title).toBe('Fallback OK');
      expect(result.pid).toBe(PID);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('falls back when report-definitions returns a record WITHOUT a dsl', async () => {
      const fetchMock = vi
        .fn()
        // report-definitions resolves 200 but carries no dsl → must fall back
        .mockResolvedValueOnce(apiResponse({ pid: PID, code: 'c' }))
        .mockResolvedValueOnce(
          apiResponse({ pid: PID, pageKey: PAGE_KEY, extension: { reportDsl: dsl('Fallback no-dsl') } }),
        );
      vi.stubGlobal('fetch', fetchMock);

      const result = await reportDesignerService.loadByPid(PID);

      expect(result.dsl.title).toBe('Fallback no-dsl');
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });
  });

  describe('loadByPageKey', () => {
    it('reads ab_report by-code FIRST and returns {dsl, pid}', async () => {
      const fetchMock = vi
        .fn()
        // GET /api/report-definitions/by-code/{pageKey}
        .mockResolvedValueOnce(apiResponse({ pid: PID, code: PAGE_KEY, dsl: dsl('From ab_report by-code') }));
      vi.stubGlobal('fetch', fetchMock);

      const result = await reportDesignerService.loadByPageKey(PAGE_KEY);

      expect(result.pid).toBe(PID);
      expect(result.dsl.title).toBe('From ab_report by-code');
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(fetchMock.mock.calls[0][0]).toBe(`/api/report-definitions/by-code/${PAGE_KEY}`);
    });

    it('falls back to /api/pages/key/{pageKey} on a 404 from by-code', async () => {
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(apiResponse({ message: 'not found' }, 404))
        .mockResolvedValueOnce(
          apiResponse({ pid: PID, pageKey: PAGE_KEY, extension: { reportDsl: dsl('Viewer fallback') } }),
        );
      vi.stubGlobal('fetch', fetchMock);

      const result = await reportDesignerService.loadByPageKey(PAGE_KEY);

      expect(result.pid).toBe(PID);
      expect(result.dsl.title).toBe('Viewer fallback');
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(fetchMock.mock.calls[0][0]).toBe(`/api/report-definitions/by-code/${PAGE_KEY}`);
      expect(fetchMock.mock.calls[1][0]).toBe(`/api/pages/key/${PAGE_KEY}`);
    });

    it('returns identical {dsl, pid} shape whether sourced from ab_report or page-schema', async () => {
      // ab_report source
      const abFetch = vi
        .fn()
        .mockResolvedValueOnce(apiResponse({ pid: PID, code: PAGE_KEY, dsl: dsl('same') }));
      vi.stubGlobal('fetch', abFetch);
      const fromAb = await reportDesignerService.loadByPageKey(PAGE_KEY);

      // page-schema source (by-code 404 → fallback)
      const psFetch = vi
        .fn()
        .mockResolvedValueOnce(apiResponse({ message: 'not found' }, 404))
        .mockResolvedValueOnce(
          apiResponse({ pid: PID, pageKey: PAGE_KEY, extension: { reportDsl: dsl('same') } }),
        );
      vi.stubGlobal('fetch', psFetch);
      const fromPs = await reportDesignerService.loadByPageKey(PAGE_KEY);

      expect(fromAb).toEqual(fromPs);
      expect(fromAb).toEqual({ dsl: dsl('same'), pid: PID });
    });
  });
});
