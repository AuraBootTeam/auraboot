/**
 * Conditional Format Evaluator
 *
 * Evaluates conditional formatting rules against a record row.
 * Returns the first matching rule's style, or null if no rules match.
 */

import type { ConditionalFormatRule, ConditionalFormatStyle } from '~/smart/types/savedView';

/**
 * Evaluate a single rule against a record value.
 */
function evaluateCondition(
  rule: ConditionalFormatRule,
  record: Record<string, unknown>
): boolean {
  if (!rule.fieldCode) return false;

  const rawValue = record[rule.fieldCode];
  const { operator, value: ruleValue } = rule;

  // isNull / isNotNull don't need a value
  if (operator === 'isNull') {
    return rawValue === null || rawValue === undefined || rawValue === '';
  }
  if (operator === 'isNotNull') {
    return rawValue !== null && rawValue !== undefined && rawValue !== '';
  }

  // For other operators, both sides must be present
  if (rawValue === null || rawValue === undefined) return false;
  if (ruleValue === undefined || ruleValue === null || ruleValue === '') return false;

  const strValue = String(rawValue);
  const strRule = String(ruleValue);

  // Try numeric comparison first
  const numValue = Number(rawValue);
  const numRule = Number(ruleValue);
  const bothNumeric = !isNaN(numValue) && !isNaN(numRule);

  switch (operator) {
    case 'eq':
      return strValue === strRule;
    case 'ne':
      return strValue !== strRule;
    case 'gt':
      return bothNumeric ? numValue > numRule : strValue > strRule;
    case 'gte':
      return bothNumeric ? numValue >= numRule : strValue >= strRule;
    case 'lt':
      return bothNumeric ? numValue < numRule : strValue < strRule;
    case 'lte':
      return bothNumeric ? numValue <= numRule : strValue <= strRule;
    case 'like':
      return strValue.toLowerCase().includes(strRule.toLowerCase());
    default:
      return false;
  }
}

/**
 * Evaluate all conditional formatting rules against a record.
 * Returns the style of the first matching rule (top-to-bottom priority),
 * or null if no rules match.
 */
export function evaluateConditionalFormats(
  rules: ConditionalFormatRule[] | undefined,
  record: Record<string, unknown>
): ConditionalFormatStyle | null {
  if (!rules || rules.length === 0) return null;

  for (const rule of rules) {
    if (evaluateCondition(rule, record)) {
      return rule.style;
    }
  }

  return null;
}

/**
 * Build inline CSS style object from a ConditionalFormatStyle.
 */
export function buildConditionalStyle(
  style: ConditionalFormatStyle | null
): React.CSSProperties | undefined {
  if (!style) return undefined;

  const css: React.CSSProperties = {};
  if (style.backgroundColor) css.backgroundColor = style.backgroundColor;
  if (style.textColor) css.color = style.textColor;
  if (style.bold) css.fontWeight = 700;
  return Object.keys(css).length > 0 ? css : undefined;
}
