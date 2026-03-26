import type { RuleAssert } from './crossFieldRuleTypes';

type Data = Record<string, unknown>;

export interface AssertResult {
  passed: boolean;
  skipped: boolean;
  failedOperator?: string;
}

const PASSED: AssertResult = { passed: true, skipped: false };
const skipped: AssertResult = { passed: true, skipped: true };
function failed(op: string): AssertResult {
  return { passed: false, skipped: false, failedOperator: op };
}

function resolveValue(value: unknown, data: Data): unknown {
  if (value && typeof value === 'object' && 'ref' in value) {
    return data[(value as { ref: string }).ref];
  }
  return value;
}

function compareOp(left: unknown, right: unknown, op: string): boolean {
  if (right == null) return false;
  if (typeof left === 'number' && typeof right === 'number') {
    switch (op) {
      case 'eq':
        return left === right;
      case 'neq':
        return left !== right;
      case 'gt':
        return left > right;
      case 'gte':
        return left >= right;
      case 'lt':
        return left < right;
      case 'lte':
        return left <= right;
      default:
        return false;
    }
  }
  if (typeof left === 'string' && typeof right === 'string') {
    const cmp = left.localeCompare(right);
    switch (op) {
      case 'eq':
        return cmp === 0;
      case 'neq':
        return cmp !== 0;
      case 'gt':
        return cmp > 0;
      case 'gte':
        return cmp >= 0;
      case 'lt':
        return cmp < 0;
      case 'lte':
        return cmp <= 0;
      default:
        return false;
    }
  }
  return op === 'eq' ? left === right : op === 'neq' ? left !== right : false;
}

/**
 * Evaluate the assert clause of a cross-field rule.
 * Expression mode attempts best-effort JS evaluation.
 */
export function evaluateAssert(assertion: RuleAssert, data: Data): AssertResult {
  if (assertion.expr) {
    try {
      const fn = new Function(...Object.keys(data), `return !!(${assertion.expr})`);
      const result = fn(...Object.values(data));
      return result ? PASSED : failed('expr');
    } catch {
      return skipped; // Expression error → skip
    }
  }

  const field = assertion.field;
  if (!field) return PASSED;

  const value = data[field];

  // Required check — null/empty means FAIL
  if (assertion.required) {
    if (value == null || (typeof value === 'string' && value.trim() === '')) {
      return failed('required');
    }
  }

  // Non-required: null → skip
  if (value == null) return skipped;

  // String constraints
  if (
    assertion.maxLength != null &&
    typeof value === 'string' &&
    value.length > assertion.maxLength
  ) {
    return failed('maxLength');
  }
  if (
    assertion.minLength != null &&
    typeof value === 'string' &&
    value.length < assertion.minLength
  ) {
    return failed('minLength');
  }
  if (
    assertion.pattern != null &&
    typeof value === 'string' &&
    !new RegExp(assertion.pattern).test(value)
  ) {
    return failed('pattern');
  }

  // Comparison operators
  for (const op of ['eq', 'neq', 'gt', 'gte', 'lt', 'lte'] as const) {
    const rhs = assertion[op];
    if (rhs != null) {
      const resolved = resolveValue(rhs, data);
      if (resolved == null) return skipped;
      if (!compareOp(value, resolved, op)) return failed(op);
    }
  }

  // In / NotIn
  if (assertion.in != null) {
    if (!assertion.in.includes(value)) return failed('in');
  }
  if (assertion.notIn != null) {
    if (assertion.notIn.includes(value)) return failed('notIn');
  }

  return PASSED;
}
