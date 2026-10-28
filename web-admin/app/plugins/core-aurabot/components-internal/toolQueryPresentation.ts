import type { ChatBiResult } from './ChatBiResultCard';

// Storage and tenancy identities are diagnostic, not business query columns.
const STORAGE_COLUMNS = new Set([
  'pid', 'id', 'tenant_id', 'tenantId', 'deleted_flag', 'created_by', 'updated_by',
]);

/** Attach the model identity declared by a DSL list tool without changing its result data. */
export function toolQueryPresentation(result: ChatBiResult, toolName?: string): ChatBiResult {
  const model = /^list_([a-z][a-z0-9_]*)$/.exec(toolName || '')?.[1];
  if (!model) return result;
  const columns = result.columns?.length
    ? result.columns
    : Object.keys(result.records?.[0] || {});
  return {
    ...result,
    modelCode: result.modelCode || model,
    columns: columns.filter((column) => !STORAGE_COLUMNS.has(column)),
  };
}
