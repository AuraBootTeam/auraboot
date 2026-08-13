import type { ColumnConfig } from '~/framework/meta/schemas/types';

export interface BuiltInBulkCapability {
  enabled?: boolean;
  permissionCode?: string;
}

export interface BuiltInBulkCapabilitiesConfig {
  edit?: boolean | BuiltInBulkCapability;
  delete?: boolean | BuiltInBulkCapability;
  export?: boolean | BuiltInBulkCapability;
}

export interface ResolvedBuiltInBulkCapabilities {
  edit: boolean;
  delete: boolean;
  export: boolean;
}

function resolveCapability(
  value: boolean | BuiltInBulkCapability | undefined,
  hasPermission: (permissionCode: string) => boolean,
) {
  if (value === undefined) return true;
  if (typeof value === 'boolean') return value;
  if (value.enabled === false) return false;
  return !value.permissionCode || hasPermission(value.permissionCode);
}

/**
 * Resolve DSL opt-in controls for the three generic bulk operations.
 *
 * An absent config preserves the historical behavior for existing pages. Once
 * a page declares the config, each operation is permission-gated and bulk edit
 * is hidden when no column explicitly opts into editing.
 */
export function resolveBuiltInBulkCapabilities(
  config: BuiltInBulkCapabilitiesConfig | undefined,
  hasPermission: (permissionCode: string) => boolean,
  editableFieldCount: number,
): ResolvedBuiltInBulkCapabilities {
  const strict = config !== undefined;
  return {
    edit: resolveCapability(config?.edit, hasPermission) && (!strict || editableFieldCount > 0),
    delete: resolveCapability(config?.delete, hasPermission),
    export: resolveCapability(config?.export, hasPermission),
  };
}

/**
 * Legacy pages exposed every visible column in the generic bulk editor. Pages
 * declaring bulkCapabilities use the safer contract: only `editable: true`
 * columns may be changed, so lifecycle fields cannot bypass commands.
 */
export function selectBulkEditableColumns(
  columns: ColumnConfig[],
  strict: boolean,
): ColumnConfig[] {
  return columns.filter(
    (column) =>
      !column.isActionColumn && Boolean(column.field) && (!strict || column.editable === true),
  );
}
