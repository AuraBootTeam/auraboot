/**
 * Migration v3 → v4: Ensure blocks have BlockLayoutConfig compatible with Grid canvas
 * V4 adds optional `col` field to BlockLayoutConfig.
 * No data changes needed — just version bump. Blocks without `col` auto-flow.
 */
export function migrateV3toV4(dsl: Record<string, any>): Record<string, any> {
  const result = { ...dsl };
  result.schemaVersion = 4;
  return result;
}
