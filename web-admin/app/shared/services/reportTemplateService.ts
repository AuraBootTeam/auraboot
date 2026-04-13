/**
 * Report Template API Service
 *
 * Wraps backend /api/report-templates endpoints.
 */

import { get, post, put, del } from '~/shared/services/http-client';
import type { Result } from '~/shared/services/http-client';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface ReportParameter {
  name: string;
  type: 'string' | 'integer' | 'long' | 'double' | 'date' | 'boolean';
  required: boolean;
  defaultValue?: unknown;
  description?: string;
  format?: string;
}

export interface ReportTemplateDTO {
  pid: string;
  code: string;
  name: string;
  description?: string;
  category?: string;
  templateType: 'jrxml' | 'jasper';
  dataSourceType?: 'model' | 'named_query' | 'custom_sql';
  dataSourceConfig?: Record<string, unknown>;
  outputFormat: 'pdf' | 'xlsx' | 'docx' | 'html' | 'csv';
  pageSize: 'A4' | 'A3' | 'letter' | 'legal' | 'custom';
  orientation: 'portrait' | 'landscape';
  parameters?: ReportParameter[];
  status: 'draft' | 'published' | 'archived';
  hasInlineContent?: boolean;
  hasFileContent?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface ReportTemplateCreateRequest {
  code: string;
  name: string;
  description?: string;
  category?: string;
  templateType?: string;
  templateContent?: string;
  dataSourceType?: string;
  dataSourceConfig?: Record<string, unknown>;
  outputFormat?: string;
  pageSize?: string;
  orientation?: string;
  parameters?: ReportParameter[];
}

export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

export const reportTemplateService = {
  async list(params: {
    keyword?: string;
    category?: string;
    status?: string;
    page?: number;
    size?: number;
  }): Promise<Result<PageResult<ReportTemplateDTO>>> {
    return get<PageResult<ReportTemplateDTO>>('/api/report-templates', params);
  },

  async getByPid(pid: string): Promise<Result<ReportTemplateDTO>> {
    return get<ReportTemplateDTO>(`/api/report-templates/${pid}`);
  },

  async create(data: ReportTemplateCreateRequest): Promise<Result<ReportTemplateDTO>> {
    return post<ReportTemplateDTO>('/api/report-templates', data);
  },

  async update(pid: string, data: ReportTemplateCreateRequest): Promise<Result<ReportTemplateDTO>> {
    return put<ReportTemplateDTO>(`/api/report-templates/${pid}`, data);
  },

  async remove(pid: string): Promise<Result<void>> {
    return del<void>(`/api/report-templates/${pid}`);
  },

  async publish(pid: string): Promise<Result<ReportTemplateDTO>> {
    return post<ReportTemplateDTO>(`/api/report-templates/${pid}/publish`);
  },

  async archive(pid: string): Promise<Result<ReportTemplateDTO>> {
    return post<ReportTemplateDTO>(`/api/report-templates/${pid}/archive`);
  },

  async getPublished(): Promise<Result<ReportTemplateDTO[]>> {
    return get<ReportTemplateDTO[]>('/api/report-templates/published');
  },

  async getCategories(): Promise<Result<string[]>> {
    return get<string[]>('/api/report-templates/categories');
  },

  async checkCode(code: string, excludePid?: string): Promise<Result<boolean>> {
    return get<boolean>('/api/report-templates/check-code', { code, excludePid });
  },

  /** Upload JRXML file — uses FormData, bypasses normal JSON post */
  async uploadTemplate(pid: string, file: File): Promise<Result<ReportTemplateDTO>> {
    const formData = new FormData();
    formData.append('file', file);
    // Use fetch directly for multipart
    const resp = await fetch(`/api/report-templates/${pid}/upload`, {
      method: 'post',
      body: formData,
    });
    return resp.json();
  },

  /** Generate report — returns blob for download */
  async generate(templateCode: string, parameters?: Record<string, unknown>): Promise<Blob> {
    const resp = await fetch(`/api/report-templates/generate/${templateCode}`, {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ templateCode, parameters }),
    });
    if (!resp.ok) throw new Error(`Generate failed: ${resp.status}`);
    return resp.blob();
  },

  /** Preview report — returns PDF blob */
  async preview(templateCode: string, sampleParams?: Record<string, unknown>): Promise<Blob> {
    const resp = await fetch(`/api/report-templates/preview/${templateCode}`, {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(sampleParams ?? {}),
    });
    if (!resp.ok) throw new Error(`Preview failed: ${resp.status}`);
    return resp.blob();
  },
};
