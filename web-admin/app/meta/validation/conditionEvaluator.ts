import type { RuleCondition } from './crossFieldRuleTypes';

type Data = Record<string, unknown>;

function resolveValue(value: unknown, data: Data): unknown {
  if (value && typeof value === 'object' && 'ref' in value) {
    return data[(value as { ref: string }).ref];
  }
  return value;
}

function compareOp(left: unknown, right: unknown, op: string): boolean {
  if (right == null) return false;

  // Numeric comparison
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

  // String/comparable comparison
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

  // Fallback: equality only
  switch (op) {
    case 'eq':
      return left === right;
    case 'neq':
      return left !== right;
    default:
      return false;
  }
}

function evaluateSingle(cond: RuleCondition, data: Data): boolean {
  const fieldName = cond.field;
  if (!fieldName) return true;

  const fieldValue = data[fieldName];
  if (fieldValue == null) return false; // null → condition is false

  if (cond.eq != null && !compareOp(fieldValue, resolveValue(cond.eq, data), 'eq')) return false;
  if (cond.neq != null && !compareOp(fieldValue, resolveValue(cond.neq, data), 'neq')) return false;
  if (cond.gt != null && !compareOp(fieldValue, resolveValue(cond.gt, data), 'gt')) return false;
  if (cond.gte != null && !compareOp(fieldValue, resolveValue(cond.gte, data), 'gte')) return false;
  if (cond.lt != null && !compareOp(fieldValue, resolveValue(cond.lt, data), 'lt')) return false;
  if (cond.lte != null && !compareOp(fieldValue, resolveValue(cond.lte, data), 'lte')) return false;
  if (cond.in != null && !cond.in.includes(fieldValue)) return false;
  if (cond.notIn != null && cond.notIn.includes(fieldValue)) return false;

  return true;
}

/**
 * Evaluate a declarative condition.
 * Expression mode (expr) attempts best-effort JS evaluation.
 */
export function evaluateCondition(condition: RuleCondition, data: Data): boolean {
  if (condition.and) {
    return condition.and.every((c) => evaluateCondition(c, data));
  }
  if (condition.or) {
    return condition.or.some((c) => evaluateCondition(c, data));
  }
  if (condition.not) {
    return !evaluateCondition(condition.not, data);
  }
  if (condition.expr) {
    return evaluateExpressionBestEffort(condition.expr, data);
  }
  return evaluateSingle(condition, data);
}

function evaluateExpressionBestEffort(expr: string, data: Data): boolean {
  try {
    const fn = new Function(...Object.keys(data), `return !!(${expr})`);
    return fn(...Object.values(data));
  } catch {
    return true; // Expression error → skip (best-effort)
  }
}
