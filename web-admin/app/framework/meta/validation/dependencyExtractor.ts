import type { CrossFieldRule, RuleCondition } from './crossFieldRuleTypes';

/**
 * Extract all field dependencies from a rule.
 * Declarative mode: auto-extract from assert.field, ref values, when.field.
 * Expression mode: use explicit dependsOn.
 */
export function extractDependencies(rule: CrossFieldRule): Set<string> {
  const deps = new Set<string>();

  // Expression mode: use explicit dependsOn
  if (rule.assert.expr || rule.when?.expr) {
    if (rule.dependsOn) {
      rule.dependsOn.forEach((d) => deps.add(d));
    }
    return deps;
  }

  // Declarative assert
  if (rule.assert.field) deps.add(rule.assert.field);
  extractRefsFromOperators(rule.assert as unknown as Record<string, unknown>, deps);

  // When condition
  if (rule.when) {
    extractFromCondition(rule.when, deps);
  }

  return deps;
}

function extractFromCondition(cond: RuleCondition, deps: Set<string>): void {
  if (cond.field) deps.add(cond.field);
  extractRefsFromOperators(cond, deps);

  if (cond.and) cond.and.forEach((c) => extractFromCondition(c, deps));
  if (cond.or) cond.or.forEach((c) => extractFromCondition(c, deps));
  if (cond.not) extractFromCondition(cond.not, deps);
}

function extractRefsFromOperators(
  obj: Record<string, unknown> | RuleCondition,
  deps: Set<string>,
): void {
  const o = obj as Record<string, unknown>;
  for (const op of ['eq', 'neq', 'gt', 'gte', 'lt', 'lte'] as const) {
    const val = o[op];
    if (val && typeof val === 'object' && 'ref' in (val as object)) {
      deps.add((val as { ref: string }).ref);
    }
  }
}
