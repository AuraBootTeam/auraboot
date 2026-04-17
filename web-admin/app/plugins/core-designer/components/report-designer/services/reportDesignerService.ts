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
  dslSchema: string; // JSON string of ReportDsl
  title?: string;
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

export const reportDesignerService = {
  /**
   * Load report by page key
   */
  async loadByPageKey(pageKey: string): Promise<{ dsl: ReportDsl; pid: string }> {
    const record = await request<PageSchemaRecord>(`${PAGES_API}/key/${pageKey}`);
    const dsl =
      typeof record.dslSchema === 'string'
        ? (JSON.parse(record.dslSchema) as ReportDsl)
        : (record.dslSchema as unknown as ReportDsl);
    return { dsl, pid: record.pid };
  },

  /**
   * Load report by PID
   */
  async loadByPid(pid: string): Promise<{ dsl: ReportDsl; pid: string }> {
    const record = await request<PageSchemaRecord>(`${PAGES_API}/${pid}`);
    const dsl =
      typeof record.dslSchema === 'string'
        ? (JSON.parse(record.dslSchema) as ReportDsl)
        : (record.dslSchema as unknown as ReportDsl);
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
      dslSchema: report as unknown as Record<string, unknown>,
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
   * Export report as PDF via render-html endpoint
   */
  async exportPdf(
    html: string,
    pageSize: string,
    orientation: string,
    filename: string,
  ): Promise<Blob> {
    const response = await fetch('/api/print/render-html', {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ html, pageSize, orientation, filename }),
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
};
