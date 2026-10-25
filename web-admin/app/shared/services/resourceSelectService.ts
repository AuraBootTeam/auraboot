import { internalRequest } from '~/shared/services/http-client/internal-api';
import { dictionaryData } from '~/shared/services/dict-adapter';
/**
 * Resource Select Service
 *
 * Provides fetch helpers for cross-designer resource selection.
 * Each function returns a list of ResourceOption objects for use
 * with BaseResourceSelect.
 */

import { fetchResult } from '~/shared/services/http-client';
import { getKernel } from '~/framework/bootstrap';
import { getLocalizedText } from '~/utils/i18n';

export interface ResourceOption {
  readOnly?: boolean;
  label: string;
  value: string;
  description?: string;
  dictCode?: string;
}

interface PageRecord {
  total?: number;
  records: Array<{
    title?: string;
    name?: string;
    kind: string;
    code?: string;
    pid: string;
    modelCode?: string;
    profile?: string;
    pageKey?: string;
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
    readOnly?: boolean;
  };
  dictCode?: string;
}

export async function fetchPageOptions(): Promise<ResourceOption[]> {
  const result = await fetchResult<PageRecord>('/api/pages?size=100');
  return (result?.data?.records || []).map((p) => ({
    label: `${p.title || p.name} (${p.kind})`,
    value: p.code || p.pid,
    description: p.kind,
  }));
}

/** Dashboard navigation consumes runtime list routes, not page PIDs or authoring keys. */
export async function fetchPageNavigationOptions(locale = 'zh-CN'): Promise<ResourceOption[]> {
  const routes = new Map<string, ResourceOption>();
  let seen = 0;
  let pageNum = 1;
  let total = 0;
  do {
    const result = await fetchResult<PageRecord>(`/api/pages?kind=list&isPublished=true&pageNum=${pageNum}&pageSize=100`);
    if (String(result.code) !== '0' || !result.data || !Array.isArray(result.data.records))
      throw new Error('Unable to load published navigation pages');
    total = Number(result.data.total);
    if (!Number.isSafeInteger(total) || total < 0 || total > 10000 || (seen < total && !result.data.records.length))
      throw new Error('Published page inventory is incomplete or exceeds the navigation picker limit');
    for (const page of result.data.records) {
      if (page.kind !== 'list' || !page.modelCode || (page.profile && page.profile !== 'default')) continue;
      const value = `/p/${encodeURIComponent(page.modelCode)}`;
      routes.set(value, {value,label:getLocalizedText(page.name || page.title || page.modelCode,locale),description:value});
    }
    seen += result.data.records.length;
    pageNum++;
  } while (seen < total);
  return [...routes.values()];
}

export async function fetchDashboardOptions(): Promise<ResourceOption[]> {
  const result = await fetchResult<DashboardRecord>('/api/dashboards?size=100');
  return (result?.data?.records || []).map((d) => ({
    label: `${d.title} (${d.code || d.pid})`,
    value: d.code || d.pid,
    description: d.status,
  }));
}

export async function fetchWorkflowOptions(): Promise<ResourceOption[]> {
  const provider = getKernel().contributionRegistry.getPrimaryService('aura.resource.workflow')
    ?.provider as { fetchOptions?: () => Promise<ResourceOption[]> } | undefined;
  // Product packages own workflow discovery. An absent capability is empty;
  // errors from a registered provider must propagate to the caller.
  return provider?.fetchOptions ? provider.fetchOptions() : [];
}

export async function fetchAutomationOptions(): Promise<ResourceOption[]> {
  const result = await fetchResult<AutomationRecord>('/api/automations?size=100');
  return (result?.data?.records || []).map((a) => ({
    label: a.name,
    value: a.pid,
    description: a.status,
  }));
}

export async function fetchCommandOptions(modelCode?: string): Promise<ResourceOption[]> {
  // GET /api/meta/commands (no modelCode) lists every current command as a bare
  // array in `data` — the execute-command picker has no model scope. The earlier
  // `?size=200` + `data.records` read was doubly wrong: the endpoint required a
  // `modelCode` param (→ 500) and returns a List, not a paginated `{records}`
  // envelope, so the picker surfaced zero options. (golden FINDING-9)
  const result = await fetchResult<CommandItem[]>(modelCode
    ? `/api/meta/commands?modelCode=${encodeURIComponent(modelCode)}` : '/api/meta/commands');
  return (result?.data || []).map((c) => ({
    label: c.displayName || c.code,
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
    ...(f.extension?.readOnly ? { readOnly: true } : {}),
    ...(f.dictCode ? { dictCode: f.dictCode } : {}),
  }));
}

/** Resolve the selected model field's dictionary; field codes are not dictionary codes. */
export async function fetchFieldDictOptions(
  modelCode: string,
  fieldCode: string,
): Promise<ResourceOption[]> {
  if (!modelCode || !fieldCode) return [];
  const fields = await fetchFieldOptions(modelCode);
  const dictCode = fields.find((field) => field.value === fieldCode)?.dictCode;
  if (!dictCode) throw new Error('Selected field has no dictionary');
  return fetchDictOptions(dictCode);
}

export async function fetchDictOptions(dictCode: string): Promise<ResourceOption[]> {
  if (!dictCode) return [];
  const result = await internalRequest('get', '/api/meta/dict/by-code/{code}/data', {
    path: { code: dictCode },
  });
  if (result.code !== '0' || !result.data)
    throw new Error('Dictionary options could not be loaded');
  return dictionaryData(result.data).items.map((item) => ({
    label: item.label || item.value,
    value: item.value,
    description: item.description ?? undefined,
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
