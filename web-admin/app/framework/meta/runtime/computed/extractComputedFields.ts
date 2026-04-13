/**
 * Utility to extract ComputedFieldDef entries from schema FieldConfig.
 * Bridges the DSL schema field definitions to the ComputedFieldEngine.
 *
 * @since 3.7.0
 */

import type { FieldConfig } from '~/framework/meta/schemas/types';
import type { ComputedFieldDef, ComputedFieldType } from './types';

/**
 * Extract computed field definitions from a list of FieldConfig entries.
 * Fields with `valueWhen` expressions are treated as computed fields.
 */
export function extractComputedFields(fields: FieldConfig[]): ComputedFieldDef[] {
  const computedFields: ComputedFieldDef[] = [];

  for (const field of fields) {
    if (!field.valueWhen) continue;

    const dependencies = field.dependOn ?? inferDependencies(field.valueWhen);
    const type = inferComputedType(field);

    computedFields.push({
      fieldCode: field.field,
      label: typeof field.label === 'string' ? field.label : undefined,
      expression: field.valueWhen,
      dependencies,
      type,
      dataType: field.props?.dataType,
      debounceMs: field.props?.computeDebounce,
      fallbackValue: field.props?.computeFallback,
    });
  }

  return computedFields;
}

/**
 * Infer dependencies from an expression string by finding field references.
 * Supports patterns like: ${field1 + field2}, field1 * field2, #field1
 */
function inferDependencies(expression: string): string[] {
  const deps = new Set<string>();

  // Remove ${} wrapper if present
  let expr = expression.trim();
  if (expr.startsWith('${') && expr.endsWith('}')) {
    expr = expr.slice(2, -1);
  }

  // Match identifiers that are likely field references
  // Exclude: keywords, numbers, built-in objects
  const KEYWORDS = new Set([
    'true',
    'false',
    'null',
    'undefined',
    'NaN',
    'Infinity',
    'Math',
    'Number',
    'String',
    'Boolean',
    'Date',
    'json',
    'parseInt',
    'parseFloat',
    'isNaN',
    'isFinite',
    'if',
    'else',
    'return',
    'new',
    'typeof',
    'instanceof',
  ]);

  // Match word characters that start with a letter or underscore
  const identifierPattern = /\b([a-zA-Z_]\w*)\b/g;
  let match;
  while ((match = identifierPattern.exec(expr)) !== null) {
    const id = match[1];
    if (!KEYWORDS.has(id)) {
      deps.add(id);
    }
  }

  // Also match #fieldCode patterns (SpEL-style)
  const spelPattern = /#(\w+)/g;
  while ((match = spelPattern.exec(expr)) !== null) {
    deps.add(match[1]);
  }

  return [...deps];
}

/**
 * Infer the computed field type from FieldConfig properties.
 */
function inferComputedType(field: FieldConfig): ComputedFieldType {
  // Check explicit virtualType in props
  const virtualType = field.props?.virtualType;
  if (virtualType === 'materialized') return 'computed_materialized';
  if (virtualType === 'temp' || virtualType === 'input') return 'computed_temp';

  // If the field has readOnly or disable indicators, it's readonly computed
  if (field.readOnlyWhen || field.disableWhen) return 'computed_readonly';

  // Default to readonly
  return 'computed_readonly';
}
