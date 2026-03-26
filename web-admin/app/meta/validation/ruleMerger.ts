import type { CrossFieldRule, RuleOverride } from './crossFieldRuleTypes';

/**
 * Merge model-level rules with command-level overrides.
 * - disabled=true → remove rule
 * - matching id → replace
 * - new id → append
 */
export function mergeRules(
  modelRules: CrossFieldRule[],
  overrides: RuleOverride[],
): CrossFieldRule[] {
  if (!overrides || overrides.length === 0) {
    return [...modelRules];
  }

  const overrideMap = new Map<string, RuleOverride>();
  for (const o of overrides) {
    overrideMap.set(o.id, o);
  }

  const result: CrossFieldRule[] = [];

  for (const rule of modelRules) {
    const override = overrideMap.get(rule.id);
    if (override) {
      overrideMap.delete(rule.id);
      if (override.disabled) continue; // Remove
      result.push(override); // Replace
    } else {
      result.push(rule);
    }
  }

  // Append new overrides
  overrideMap.forEach((override) => {
    if (!override.disabled) {
      result.push(override);
    }
  });

  return result;
}
