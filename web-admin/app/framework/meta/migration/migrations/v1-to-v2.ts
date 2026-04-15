/**
 * Migration v1 → v2: Convert pageType string to kind enum
 */
export function migrateV1toV2(dsl: Record<string, any>): Record<string, any> {
  const result = { ...dsl };

  // Convert pageType to kind
  if (result.pageType && !result.kind) {
    // kind=dashboard removed 2026-04-15 — dashboards live in ab_dashboard,
    // legacy DASHBOARD page_type entries fall through to 'list' default.
    const typeMap: Record<string, string> = {
      'LIST': 'list',
      'FORM': 'form',
      'DETAIL': 'detail',
      'list': 'list',
      'form': 'form',
      'detail': 'detail',
    };
    result.kind = typeMap[result.pageType] ?? 'list';
    delete result.pageType;
  }

  // Remove deprecated pageCategory
  delete result.pageCategory;

  result.schemaVersion = 2;
  return result;
}
