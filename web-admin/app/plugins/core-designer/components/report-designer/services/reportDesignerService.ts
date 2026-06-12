/**
 * Report Designer Service
 * API client for saving/loading report schemas via PageSchema API
 */

import type { ReportDsl } from '../types';

const PAGES_API = '/api/pages';

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

export const reportDesignerService = {
  /**
   * Load report by page key
   */
  async loadByPageKey(pageKey: string): Promise<{ dsl: ReportDsl; pid: string }> {
    const record = await request<PageSchemaRecord>(`${PAGES_API}/key/${pageKey}`);
    const dsl = parseReportDsl(record);
    return { dsl, pid: record.pid };
  },

  /**
   * Load report by PID
   */
  async loadByPid(pid: string): Promise<{ dsl: ReportDsl; pid: string }> {
    const record = await request<PageSchemaRecord>(`${PAGES_API}/${pid}`);
    const dsl = parseReportDsl(record);
    return { dsl, pid: record.pid };
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

    if (existingPid) {
      const result = await request<PageSchemaRecord>(`${PAGES_API}/${existingPid}`, {
        method: 'put',
        body: JSON.stringify(payload),
      });
      return result.pid;
    } else {
      const result = await request<PageSchemaRecord>(PAGES_API, {
        method: 'post',
        body: JSON.stringify(payload),
      });
      return result.pid;
    }
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
