/**
 * Report Designer Service
 * API client for the canonical report-definition store
 */

import type { ReportDsl } from '../types';

const REPORT_DEFINITIONS_API = '/api/report-definitions';

interface ApiResponse<T> {
  code: number | string;
  message: string;
  desc?: string;
  data: T;
}

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

function fromReportDefinition(record: ReportDefinitionRecord): { dsl: ReportDsl; pid: string } {
  if (!record.dsl) {
    throw new Error(`Report DSL not found for report-definition: ${record.pid}`);
  }
  return { dsl: record.dsl, pid: record.pid };
}

export const reportDesignerService = {
  async loadByPageKey(pageKey: string): Promise<{ dsl: ReportDsl; pid: string }> {
    return fromReportDefinition(
      await request<ReportDefinitionRecord>(
        `${REPORT_DEFINITIONS_API}/by-code/${encodeURIComponent(pageKey)}`,
      ),
    );
  },

  async loadByPid(pid: string): Promise<{ dsl: ReportDsl; pid: string }> {
    return fromReportDefinition(
      await request<ReportDefinitionRecord>(`${REPORT_DEFINITIONS_API}/${encodeURIComponent(pid)}`),
    );
  },

  async save(report: ReportDsl, existingPid?: string, sourceAnalysisId?: string): Promise<string> {
    const payload = {
      ...(!existingPid ? { code: `report_${crypto.randomUUID()}` } : {}),
      ...(!existingPid && sourceAnalysisId ? { sourceAnalysisId } : {}),
      title: report.title,
      profile: 'paged-media',
      dsl: report,
    };
    const result = await request<ReportDefinitionRecord>(
      existingPid
        ? `${REPORT_DEFINITIONS_API}/${encodeURIComponent(existingPid)}`
        : REPORT_DEFINITIONS_API,
      { method: existingPid ? 'put' : 'post', body: JSON.stringify(payload) },
    );
    return result.pid;
  },

  /**
   * Export report as PDF via report export endpoint
   */
  async exportPdf(reportPid: string, parameters?: Record<string, unknown>): Promise<Blob> {
    const response = await fetch('/api/reports/export/pdf', {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reportPid, parameters, usageId: crypto.randomUUID() }),
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
      body: JSON.stringify({ reportPid, parameters, usageId: crypto.randomUUID() }),
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
      body: JSON.stringify({ reportPid, parameters, usageId: crypto.randomUUID() }),
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.desc || error.message || `JSON export failed: ${response.status}`);
    }

    return response.blob();
  },
};
