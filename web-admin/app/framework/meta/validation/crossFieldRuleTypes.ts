/**
 * Cross-field validation rule types.
 * Mirrors backend DTOs: CrossFieldRule, RuleCondition, RuleAssert, RuleOverride.
 */

export interface RuleCondition {
  field?: string;
  eq?: unknown;
  neq?: unknown;
  gt?: unknown;
  gte?: unknown;
  lt?: unknown;
  lte?: unknown;
  in?: unknown[];
  notIn?: unknown[];
  expr?: string;
  and?: RuleCondition[];
  or?: RuleCondition[];
  not?: RuleCondition;
}

export interface RuleAssert {
  field?: string;
  eq?: unknown;
  neq?: unknown;
  gt?: unknown;
  gte?: unknown;
  lt?: unknown;
  lte?: unknown;
  in?: unknown[];
  notIn?: unknown[];
  required?: boolean;
  maxLength?: number;
  minLength?: number;
  pattern?: string;
  expr?: string;
}

export interface CrossFieldRule {
  id: string;
  when?: RuleCondition;
  assert: RuleAssert;
  message: string;
  severity?: 'error' | 'warning';
  targetField?: string;
  dependsOn?: string[];
}

export interface RuleOverride extends CrossFieldRule {
  disabled?: boolean;
}

export interface RuleViolation {
  ruleId: string;
  field: string | null;
  message: string;
  severity: 'error' | 'warning';
}
