/**
 * Report Designer Service
 * API client for saving/loading report schemas via PageSchema API
 */

import type { ReportDsl } from '../types';

const PAGES_API = '/api/pages';
const REPORT_DEFINITIONS_API = '/api/report-definitions';

interface ApiResponse<T> {
  code: number | string;
  message: string;
  desc?: string;
  data: T;
}

interface PageSchemaRecord {
  pid: string;
  pageKey: string;
  kind: string;
  profile?: string;
  dslSchema?: string | ReportDsl; // Legacy report schema location.
  extension?: {
    reportDsl?: string | ReportDsl;
    [key: string]: unknown;
  };
  title?: string | Record<string, unknown>;
  status?: string;
}

/**
 * Shape of `GET /api/report-definitions/{pid}` and `.../by-code/{code}` (Phase 4 slice 2b-2 read
 * path). `dsl` is returned as a JSON OBJECT (the whole ReportDsl), not an escaped string.
 */
interface ReportDefinitionRecord {
  pid: string;
  code?: string;
  title?: string;
  profile?: string;
  status?: string;
  version?: number;
  dsl?: ReportDsl;
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.desc || error.message || `Request failed: ${response.status}`);
  }

  const result: ApiResponse<T> = await response.json();
  const code = typeof result.code === 'string' ? parseInt(result.code, 10) : result.code;
  if (code !== 0 && code !== 200) {
    throw new Error(result.desc || result.message || 'Request failed');
  }

  return result.data;
}

function parseReportDsl(record: PageSchemaRecord): ReportDsl {
  const stored = record.extension?.reportDsl ?? record.dslSchema;
  if (!stored) {
    throw new Error(`Report DSL not found for page: ${record.pid}`);
  }
  return typeof stored === 'string' ? (JSON.parse(stored) as ReportDsl) : stored;
}

/**
 * Normalize a `ReportDefinitionRecord` (the ab_report read path) into the existing `{dsl, pid}`
 * return shape. The report-definitions GET returns `dsl` as a JSON object that is the SAME ReportDsl
 * the page-schema holds in `extension.reportDsl`, so callers see an identical shape from either
 * source. Throws if the record carries no dsl so the caller can fall back to the page-schema path.
 */
function fromReportDefinition(record: ReportDefinitionRecord): { dsl: ReportDsl; pid: string } {
  if (!record.dsl) {
    throw new Error(`Report DSL not found for report-definition: ${record.pid}`);
  }
  return { dsl: record.dsl, pid: record.pid };
}

/**
 * Best-effort dual-write of the report into the `ab_report` shadow table, keyed by the page `pid`
 * (Phase 4 slice 2b-1). NEVER throws — a failure is logged and swallowed so the canonical
 * page-schema save the caller already completed still succeeds (a later backfill reconciles drift).
 * `PUT /api/report-definitions/{pid}` is an idempotent upsert, so the same pid can be synced whether
 * or not the shadow row exists yet.
 */
async function syncReportShadow(pid: string, code: string, report: ReportDsl): Promise<void> {
  try {
    await request<unknown>(`${REPORT_DEFINITIONS_API}/${pid}`, {
      method: 'put',
      body: JSON.stringify({
        code,
        title: report.title,
        profile: 'paged-media',
        dsl: report as unknown as Record<string, unknown>,
      }),
    });
  } catch (error) {
    // Best-effort shadow: do NOT surface to the user; page-schema remains the source of truth.
    console.warn('[reportDesignerService] ab_report shadow dual-write failed (non-fatal):', error);
  }
}

export const reportDesignerService = {
  /**
   * Load report by page key (the runtime viewer read path).
   *
   * Phase 4 slice 2b-2: read the first-class `ab_report` store FIRST via
   * `GET /api/report-definitions/by-code/{pageKey}` (ab_report.code == the report's pageKey).
   * On a 404 (report not yet in ab_report) or ANY error, fall back to the legacy page-schema
   * read (`GET /api/pages/key/{pageKey}`) UNCHANGED, so no report becomes unreadable.
   */
  async loadByPageKey(pageKey: string): Promise<{ dsl: ReportDsl; pid: string }> {
    try {
      const record = await request<ReportDefinitionRecord>(
        `${REPORT_DEFINITIONS_API}/by-code/${pageKey}`,
      );
      return fromReportDefinition(record);
    } catch {
      // Fallback: legacy page-schema viewer read (unchanged behavior).
      const record = await request<PageSchemaRecord>(`${PAGES_API}/key/${pageKey}`);
      const dsl = parseReportDsl(record);
      return { dsl, pid: record.pid };
    }
  },

  /**
   * Load report by PID (the designer open path).
   *
   * Phase 4 slice 2b-2: read the first-class `ab_report` store FIRST via
   * `GET /api/report-definitions/{pid}`. On a 404 (report not yet in ab_report) or ANY error,
   * fall back to the legacy page-schema read (`GET /api/pages/{pid}`) UNCHANGED.
   */
  async loadByPid(pid: string): Promise<{ dsl: ReportDsl; pid: string }> {
    try {
      const record = await request<ReportDefinitionRecord>(`${REPORT_DEFINITIONS_API}/${pid}`);
      return fromReportDefinition(record);
    } catch {
      // Fallback: legacy page-schema read by pid (unchanged behavior).
      const record = await request<PageSchemaRecord>(`${PAGES_API}/${pid}`);
      const dsl = parseReportDsl(record);
      return { dsl, pid: record.pid };
    }
  },

  /**
   * Save report (create or update)
   * Returns the PID of the saved page schema
   */
  async save(report: ReportDsl, existingPid?: string): Promise<string> {
    const suffix = Date.now().toString(36);
    const titleSlug = report.title
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_|_$/g, '');
    const pageKey = `report_${titleSlug}_${suffix}`;

    const payload = {
      pageKey,
      kind: 'list',
      profile: 'report',
      title: report.title,
      name: `${report.title} (${suffix})`,
      blocks: [],
      extension: {
        reportDsl: report as unknown as Record<string, unknown>,
      },
      status: 'draft',
      semver: '0.1.0',
    };

    let pid: string;
    if (existingPid) {
      const result = await request<PageSchemaRecord>(`${PAGES_API}/${existingPid}`, {
        method: 'put',
        body: JSON.stringify(payload),
      });
      pid = result.pid;
    } else {
      const result = await request<PageSchemaRecord>(PAGES_API, {
        method: 'post',
        body: JSON.stringify(payload),
      });
      pid = result.pid;
    }

    // Phase 4 transition dual-write — ab_report shadow, reads still use /api/pages until slice 2b-2
    // switches them. The page-schema store above is canonical; ab_report is kept in sync as a shadow
    // keyed by the SAME pid (PUT /api/report-definitions/{pid} is an idempotent upsert). This is
    // best-effort: a shadow-write failure must NOT fail the user's save (a later backfill reconciles
    // any drift), so syncReportShadow swallows and only warns on errors.
    await syncReportShadow(pid, pageKey, report);

    return pid;
  },

  /**
   * Export report as PDF via report export endpoint
   */
  async exportPdf(reportPid: string, parameters?: Record<string, unknown>): Promise<Blob> {
    const response = await fetch('/api/reports/export/pdf', {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reportPid, parameters }),
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.desc || error.message || `PDF export failed: ${response.status}`);
    }

    return response.blob();
  },

  /**
   * Export report as Excel via report export endpoint
   */
  async exportExcel(reportPid: string, parameters?: Record<string, unknown>): Promise<Blob> {
    const response = await fetch('/api/reports/export/excel', {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reportPid, parameters }),
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.desc || error.message || `Excel export failed: ${response.status}`);
    }

    return response.blob();
  },

  /**
   * Export report as JSON via report export endpoint
   */
  async exportJson(reportPid: string, parameters?: Record<string, unknown>): Promise<Blob> {
    const response = await fetch('/api/reports/export/json', {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reportPid, parameters }),
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.desc || error.message || `JSON export failed: ${response.status}`);
    }

    return response.blob();
  },
};
