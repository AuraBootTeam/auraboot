/**
 * Resource Select Service
 *
 * Provides fetch helpers for cross-designer resource selection.
 * Each function returns a list of ResourceOption objects for use
 * with BaseResourceSelect.
 */

import { fetchResult } from '~/shared/services/http-client';

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

interface ProcessDefinitionRecord {
  name?: string;
  key?: string;
  processName?: string;
  processKey?: string;
  version: number;
}

interface AutomationRecord {
  records: Array<{
    name: string;
    pid: string;
    status: string;
  }>;
}

interface CommandItem {
  displayName?: string;
  code: string;
  type: string;
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
  const result = await fetchResult<PageRecord>('/api/pages?size=100');
  return (result?.data?.records || []).map((p) => ({
    label: `${p.title || p.name} (${p.kind})`,
    value: p.code || p.pid,
    description: p.kind,
  }));
}

export async function fetchDashboardOptions(): Promise<ResourceOption[]> {
  const result = await fetchResult<DashboardRecord>('/api/dashboards?size=100');
  return (result?.data?.records || []).map((d) => ({
    label: `${d.title} (${d.code || d.pid})`,
    value: d.code || d.pid,
    description: d.status,
  }));
}

export async function fetchProcessOptions(): Promise<ResourceOption[]> {
  const result = await fetchResult<{ records?: ProcessDefinitionRecord[] } | ProcessDefinitionRecord[]>(
    '/api/bpm/process-definitions/deployed',
  );
  const records = Array.isArray(result?.data) ? result.data : result?.data?.records || [];
  const options: ResourceOption[] = [];
  for (const p of records) {
    const key = p.key || p.processKey;
    const name = p.name || p.processName || key;
    if (!key) continue;
    options.push({
      label: `${name} (${key})`,
      value: key,
      description: `v${p.version}`,
    });
  }
  return options;
}

export async function fetchAutomationOptions(): Promise<ResourceOption[]> {
  const result = await fetchResult<AutomationRecord>('/api/automations?size=100');
  return (result?.data?.records || []).map((a) => ({
    label: a.name,
    value: a.pid,
    description: a.status,
  }));
}

export async function fetchCommandOptions(): Promise<ResourceOption[]> {
  // GET /api/meta/commands (no modelCode) lists every current command as a bare
  // array in `data` — the execute-command picker has no model scope. The earlier
  // `?size=200` + `data.records` read was doubly wrong: the endpoint required a
  // `modelCode` param (→ 500) and returns a List, not a paginated `{records}`
  // envelope, so the picker surfaced zero options. (golden FINDING-9)
  const result = await fetchResult<CommandItem[]>('/api/meta/commands');
  return (result?.data || []).map((c) => ({
    label: `${c.displayName || c.code} (${c.code})`,
    value: c.code,
    description: c.type,
  }));
}

export async function fetchModelOptions(): Promise<ResourceOption[]> {
  const result = await fetchResult<ModelRecord>(
    '/api/meta/models?size=500&currentOnly=true&status=published',
  );
  return (result?.data?.records || []).map((m) => ({
    label: m.displayName || m.code,
    value: m.code,
    description: m.description || m.code,
  }));
}

export async function fetchFieldOptions(modelCode: string): Promise<ResourceOption[]> {
  if (!modelCode) return [];
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
}

export async function fetchDictOptions(dictCode: string): Promise<ResourceOption[]> {
  if (!dictCode) return [];
  const result = await fetchResult<DictItemRecord>(
    `/api/meta/dict/by-code/${encodeURIComponent(dictCode)}/data`,
  );
  return (result?.data?.items || []).map((item) => ({
    label: item.label || item.value,
    value: item.value,
    description: item.description,
  }));
}

interface SemanticMetaRecord {
  models: Array<{
    code: string;
    label?: Record<string, string>;
    description?: string;
  }>;
}

/**
 * Fetch semantic model list from GET /api/semantic/meta.
 * Returns options suitable for BaseResourceSelect.
 */
export async function fetchSemanticModelOptions(): Promise<ResourceOption[]> {
  const result = await fetchResult<SemanticMetaRecord>('/api/semantic/meta');
  return (result?.data?.models || []).map((m) => ({
    label: m.label?.['zh-CN'] || m.label?.['en'] || m.code,
    value: m.code,
    description: m.description,
  }));
}
