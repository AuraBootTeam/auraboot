import type { ChatBiResult } from './ChatBiResultCard';

// Storage and tenancy identities are diagnostic, not business query columns.
const STORAGE_COLUMNS = new Set([
  'pid', 'id', 'tenant_id', 'tenantId', 'deleted_flag', 'created_by', 'updated_by',
  'created_at', 'updated_at', 'row_version',
]);

/** Attach the model identity declared by a DSL list tool without changing its result data. */
export function toolQueryPresentation(result: ChatBiResult, toolName?: string): ChatBiResult {
  const model = /^list[_:]([a-z][a-z0-9_]*)$/.exec(toolName || '')?.[1];
  if (!model) return result;
  const columns = result.columns !== undefined
    ? result.columns
    : Object.keys(result.records?.[0] || {});
  return {
    ...result,
    modelCode: result.modelCode || model,
    chartType: result.chartType || 'table',
    columns: columns.filter((column) => !STORAGE_COLUMNS.has(column)),
  };
}

/** Deduplicate only the adjacent successful canonical list result, retaining legacy output. */
export function hasCanonicalListResult(toolName: string | undefined, next?: {
  type?: string;
  resultContract?: { skillCode?: string; status?: string };
}): boolean {
  const model = /^list[_:]([a-z][a-z0-9_]*)$/.exec(toolName || '')?.[1];
  return Boolean(model && next?.type === 'result_contract'
    && next.resultContract?.status === 'success'
    && next.resultContract.skillCode === 'list:' + model);
}
