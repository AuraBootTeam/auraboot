import { useMemo, useCallback } from 'react';
import type { CrossFieldRule, RuleOverride, RuleViolation } from './crossFieldRuleTypes';
import { mergeRules } from './ruleMerger';
import { evaluateCondition } from './conditionEvaluator';
import { evaluateAssert } from './assertEvaluator';
import { extractDependencies } from './dependencyExtractor';

function resolveMessage(template: string, data: Record<string, unknown>): string {
  return template.replace(/\{(\w+)\}/g, (_, key) => {
    const val = data[key];
    return val != null ? String(val) : key;
  });
}

/**
 * Hook for cross-field validation on DSL forms.
 * Merges model rules with command overrides, provides validate/validateField methods.
 */
export function useRuleValidation(
  modelRules: CrossFieldRule[] | undefined,
  commandOverrides: RuleOverride[] | undefined,
) {
  const finalRules = useMemo(
    () => mergeRules(modelRules ?? [], commandOverrides ?? []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [JSON.stringify(modelRules), JSON.stringify(commandOverrides)],
  );

  // Build dependency map: fieldCode → rules that depend on it
  const depMap = useMemo(() => {
    const map = new Map<string, CrossFieldRule[]>();
    for (const rule of finalRules) {
      const deps = extractDependencies(rule);
      deps.forEach((dep) => {
        if (!map.has(dep)) map.set(dep, []);
        map.get(dep)!.push(rule);
      });
    }
    return map;
  }, [finalRules]);

  // Validate all rules
  const validate = useCallback(
    (formValues: Record<string, unknown>): RuleViolation[] => {
      const violations: RuleViolation[] = [];
      for (const rule of finalRules) {
        const violation = evaluateRule(rule, formValues);
        if (violation) violations.push(violation);
      }
      return violations;
    },
    [finalRules],
  );

  // Validate only rules affected by a specific field change
  const validateField = useCallback(
    (fieldCode: string, formValues: Record<string, unknown>): RuleViolation[] => {
      const affectedRules = depMap.get(fieldCode) ?? [];
      const violations: RuleViolation[] = [];
      for (const rule of affectedRules) {
        const violation = evaluateRule(rule, formValues);
        if (violation) violations.push(violation);
      }
      return violations;
    },
    [depMap],
  );

  return { validate, validateField, rules: finalRules };
}

function evaluateRule(rule: CrossFieldRule, data: Record<string, unknown>): RuleViolation | null {
  // Evaluate when
  if (rule.when) {
    if (!evaluateCondition(rule.when, data)) return null;
  }

  // Evaluate assert
  const result = evaluateAssert(rule.assert, data);
  if (result.skipped || result.passed) return null;

  return {
    ruleId: rule.id,
    field: rule.targetField ?? rule.assert.field ?? null,
    message: resolveMessage(rule.message, data),
    severity: rule.severity ?? 'error',
  };
}
