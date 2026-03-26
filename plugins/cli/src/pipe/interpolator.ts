/**
 * Variable interpolation engine for workflow templates.
 *
 * Supports `{{variable}}` syntax in strings and nested objects.
 * Variables can reference:
 *   - Workflow-level variables
 *   - Step output variables (populated at runtime)
 *   - Nested paths: `{{deals.0.amount}}` or `{{summary.insights}}`
 */

/**
 * Interpolate all `{{variable}}` references in a value using the variable context.
 */
export function interpolate(value: unknown, variables: Record<string, unknown>): unknown {
  if (typeof value === 'string') {
    return interpolateString(value, variables);
  }
  if (Array.isArray(value)) {
    return value.map(item => interpolate(item, variables));
  }
  if (value !== null && typeof value === 'object') {
    const result: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) {
      result[k] = interpolate(v, variables);
    }
    return result;
  }
  return value;
}

/**
 * Interpolate `{{variable}}` references in a string.
 *
 * If the entire string is a single `{{variable}}` reference, return the raw value
 * (preserving type — array, object, number, etc). Otherwise, do string substitution.
 */
export function interpolateString(template: string, variables: Record<string, unknown>): unknown {
  // Full-match: entire string is a single {{variable}} — return raw value
  const fullMatch = template.match(/^\{\{(\s*[\w.]+\s*)\}\}$/);
  if (fullMatch) {
    const key = fullMatch[1].trim();
    const resolved = resolveVariable(key, variables);
    return resolved !== undefined ? resolved : template;
  }

  // Partial interpolation: replace {{variable}} occurrences with string values
  return template.replace(/\{\{(\s*[\w.]+\s*)\}\}/g, (_match, key: string) => {
    const resolved = resolveVariable(key.trim(), variables);
    if (resolved === undefined) return `{{${key.trim()}}}`;
    if (typeof resolved === 'string') return resolved;
    return JSON.stringify(resolved);
  });
}

/**
 * Resolve a dotted variable path against the context.
 * E.g., "deals.0.amount" → variables.deals[0].amount
 */
export function resolveVariable(path: string, variables: Record<string, unknown>): unknown {
  const parts = path.split('.');
  let current: unknown = variables;

  for (const part of parts) {
    if (current === null || current === undefined) return undefined;
    if (typeof current === 'object') {
      current = (current as Record<string, unknown>)[part];
    } else {
      return undefined;
    }
  }

  return current;
}
