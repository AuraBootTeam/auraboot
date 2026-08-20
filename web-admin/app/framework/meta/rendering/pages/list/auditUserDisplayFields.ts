const AUDIT_USER_FIELDS = new Set(['created_by', 'updated_by']);

/**
 * Public list responses intentionally omit the raw internal audit-user ID and
 * expose only `<field>_display`. Use that safe projection as the cell value so
 * the normal null guard does not render a resolved user as `-`.
 */
export function resolveAuditUserCellValue(
  record: Record<string, unknown> | null | undefined,
  field: string | undefined,
  rawValue: unknown,
): unknown {
  if (!field || !AUDIT_USER_FIELDS.has(field) || rawValue !== null && rawValue !== undefined) {
    return rawValue;
  }
  return record?.[`${field}_display`] ?? rawValue;
}

/**
 * Return the audit-user fields that are visibly configured in a list table.
 * The dynamic list API uses this projection hint to avoid a user lookup on
 * pages that do not render creator/modifier names.
 */
export function resolveAuditUserDisplayFields(tableBlock: any): string | undefined {
  const columns = tableBlock?.table?.columns ?? tableBlock?.columns;
  if (!Array.isArray(columns)) return undefined;

  const fields = columns
    .filter((column) => column && column.visible !== false && AUDIT_USER_FIELDS.has(column.field))
    .map((column) => column.field);

  return fields.length > 0 ? [...new Set(fields)].join(',') : undefined;
}
