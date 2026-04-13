/**
 * Resource Select Service
 *
 * Provides fetch helpers for cross-designer resource selection.
 * Each function returns a list of ResourceOption objects for use
 * with BaseResourceSelect.
 */

import { fetchResult } from '~/services/http-client';

interface ResourceOption {
  label: string;
  value: string;
  description?: string;
}

interface PageRecord {
  records: Array<{
    title?: string;
    name?: string;
    kind: string;
    code?: string;
    pid: string;
  }>;
}

interface DashboardRecord {
  records: Array<{
    title: string;
    code?: string;
    pid: string;
    status: string;
  }>;
}

interface ProcessRecord {
  records: Array<{
    name: string;
    key: string;
    version: number;
  }>;
}

interface AutomationRecord {
  records: Array<{
    name: string;
    pid: string;
    status: string;
  }>;
}

interface CommandRecord {
  records: Array<{
    displayName?: string;
    code: string;
    type: string;
  }>;
}

interface ModelRecord {
  records: Array<{
    pid: string;
    code: string;
    displayName?: string;
    description?: string;
    fieldCount?: number;
  }>;
}

interface FieldRecord {
  code: string;
  dataType: string;
  extension?: {
    displayName?: string;
  };
  dictCode?: string;
}

interface DictItemRecord {
  items: Array<{
    value: string;
    label: string;
    description?: string;
  }>;
}

export async function fetchPageOptions(): Promise<ResourceOption[]> {
  try {
    const result = await fetchResult<PageRecord>('/api/pages?size=100');
    return (result?.data?.records || []).map((p) => ({
      label: `${p.title || p.name} (${p.kind})`,
      value: p.code || p.pid,
      description: p.kind,
    }));
  } catch {
    return [];
  }
}

export async function fetchDashboardOptions(): Promise<ResourceOption[]> {
  try {
    const result = await fetchResult<DashboardRecord>('/api/dashboards?size=100');
    return (result?.data?.records || []).map((d) => ({
      label: `${d.title} (${d.code || d.pid})`,
      value: d.code || d.pid,
      description: d.status,
    }));
  } catch {
    return [];
  }
}

export async function fetchProcessOptions(): Promise<ResourceOption[]> {
  try {
    const result = await fetchResult<ProcessRecord>('/api/bpm/process-definitions?size=100');
    return (result?.data?.records || []).map((p) => ({
      label: `${p.name} (${p.key})`,
      value: p.key,
      description: `v${p.version}`,
    }));
  } catch {
    return [];
  }
}

export async function fetchAutomationOptions(): Promise<ResourceOption[]> {
  try {
    const result = await fetchResult<AutomationRecord>('/api/automations?size=100');
    return (result?.data?.records || []).map((a) => ({
      label: a.name,
      value: a.pid,
      description: a.status,
    }));
  } catch {
    return [];
  }
}

export async function fetchCommandOptions(): Promise<ResourceOption[]> {
  try {
    const result = await fetchResult<CommandRecord>('/api/meta/commands?size=200');
    return (result?.data?.records || []).map((c) => ({
      label: `${c.displayName || c.code} (${c.code})`,
      value: c.code,
      description: c.type,
    }));
  } catch {
    return [];
  }
}

export async function fetchModelOptions(): Promise<ResourceOption[]> {
  try {
    const result = await fetchResult<ModelRecord>(
      '/api/meta/models?size=500&currentOnly=true&status=published',
    );
    return (result?.data?.records || []).map((m) => ({
      label: m.displayName || m.code,
      value: m.code,
      description: m.description || m.code,
    }));
  } catch {
    return [];
  }
}

export async function fetchFieldOptions(modelCode: string): Promise<ResourceOption[]> {
  if (!modelCode) return [];
  try {
    const modelResult = await fetchResult<{ pid: string }>(
      `/api/meta/models/code/${encodeURIComponent(modelCode)}`,
    );
    const modelPid = modelResult?.data?.pid;
    if (!modelPid) return [];
    const fieldsResult = await fetchResult<FieldRecord[]>(`/api/meta/models/${modelPid}/fields`);
    return (fieldsResult?.data || []).map((f) => ({
      label: f.extension?.displayName || f.code,
      value: f.code,
      description: f.dataType,
    }));
  } catch {
    return [];
  }
}

export async function fetchDictOptions(dictCode: string): Promise<ResourceOption[]> {
  if (!dictCode) return [];
  try {
    const result = await fetchResult<DictItemRecord>(
      `/api/meta/dict/by-code/${encodeURIComponent(dictCode)}/data`,
    );
    return (result?.data?.items || []).map((item) => ({
      label: item.label || item.value,
      value: item.value,
      description: item.description,
    }));
  } catch {
    return [];
  }
}
