/**
 * Migration v1 → v2: Convert pageType string to kind enum
 */
export function migrateV1toV2(dsl: Record<string, any>): Record<string, any> {
  const result = { ...dsl };

  // Convert pageType to kind
  if (result.pageType && !result.kind) {
    const typeMap: Record<string, string> = {
      'LIST': 'list',
      'FORM': 'form',
      'DETAIL': 'detail',
      'DASHBOARD': 'dashboard',
      'list': 'list',
      'form': 'form',
      'detail': 'detail',
      'dashboard': 'dashboard',
    };
    result.kind = typeMap[result.pageType] ?? 'list';
    delete result.pageType;
  }

  // Remove deprecated pageCategory
  delete result.pageCategory;

  result.schemaVersion = 2;
  return result;
}
