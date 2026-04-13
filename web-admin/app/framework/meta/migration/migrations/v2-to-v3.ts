/**
 * Migration v2 → v3: Flatten nested dslSchema into top-level blocks + layout
 */
export function migrateV2toV3(dsl: Record<string, any>): Record<string, any> {
  const result = { ...dsl };

  // If dslSchema is nested, extract blocks and layout
  if (result.dslSchema && typeof result.dslSchema === 'object') {
    const nested = result.dslSchema;
    if (Array.isArray(nested.blocks)) {
      result.blocks = nested.blocks;
    }
    if (nested.layout) {
      result.layout = nested.layout;
    }
    if (nested.dataSources) {
      result.dataSources = nested.dataSources;
    }
    delete result.dslSchema;
  }

  // Ensure blocks is an array
  if (!Array.isArray(result.blocks)) {
    result.blocks = [];
  }

  // Ensure layout exists
  if (!result.layout) {
    result.layout = { type: 'stack' };
  }

  result.schemaVersion = 3;
  return result;
}
