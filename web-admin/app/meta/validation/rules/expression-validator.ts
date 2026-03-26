/**
 * Expression Validator — validates expression syntax for conditional fields
 *
 * Checks visibleWhen / enableWhen / disableWhen / readOnlyWhen / optionsWhen / valueWhen
 * expressions for basic syntax correctness (balanced parens, operator completeness).
 *
 * Rule: EXPR_SYNTAX
 */

import type { UnifiedSchema } from '~/meta/schemas/types';
import type { ValidationMessage } from '../types';

/** Basic expression syntax check — catches obvious mistakes */
function isExpressionValid(expr: string): { valid: boolean; error?: string } {
  if (!expr || expr.trim() === '') {
    return { valid: false, error: 'Expression is empty' };
  }

  // Check balanced parentheses
  let depth = 0;
  for (const ch of expr) {
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (depth < 0) return { valid: false, error: 'Unbalanced parentheses' };
  }
  if (depth !== 0) return { valid: false, error: 'Unbalanced parentheses' };

  // Check balanced brackets
  depth = 0;
  for (const ch of expr) {
    if (ch === '[') depth++;
    if (ch === ']') depth--;
    if (depth < 0) return { valid: false, error: 'Unbalanced brackets' };
  }
  if (depth !== 0) return { valid: false, error: 'Unbalanced brackets' };

  // Check for incomplete operators
  if (/[=!<>]=?\s*$/.test(expr)) {
    return { valid: false, error: 'Expression ends with incomplete operator' };
  }

  // Check for consecutive operators
  if (/[&|]{3,}/.test(expr)) {
    return { valid: false, error: 'Invalid operator sequence' };
  }

  return { valid: true };
}

/** Collect all expressions from schema */
function collectExpressions(schema: UnifiedSchema): Array<{ path: string; expr: string }> {
  const expressions: Array<{ path: string; expr: string }> = [];

  if (schema.areas) {
    for (const [areaId, area] of Object.entries(schema.areas)) {
      if (area.visibleWhen) {
        expressions.push({ path: `areas.${areaId}.visibleWhen`, expr: area.visibleWhen });
      }

      for (const [blockIdx, block] of (area.blocks || []).entries()) {
        const bp = `areas.${areaId}.blocks[${blockIdx}]`;

        if (block.visibleWhen) {
          expressions.push({ path: `${bp}.visibleWhen`, expr: block.visibleWhen });
        }

        // Field expressions
        for (const [fi, field] of (block.fields || []).entries()) {
          const fp = `${bp}.fields[${fi}]`;
          if (field.visibleWhen)
            expressions.push({ path: `${fp}.visibleWhen`, expr: field.visibleWhen });
          if (field.enableWhen)
            expressions.push({ path: `${fp}.enableWhen`, expr: field.enableWhen });
          if (field.disableWhen)
            expressions.push({ path: `${fp}.disableWhen`, expr: field.disableWhen });
          if (field.readOnlyWhen)
            expressions.push({ path: `${fp}.readOnlyWhen`, expr: field.readOnlyWhen });
          if (field.optionsWhen)
            expressions.push({ path: `${fp}.optionsWhen`, expr: field.optionsWhen });
          if (field.valueWhen) expressions.push({ path: `${fp}.valueWhen`, expr: field.valueWhen });
        }

        // Button expressions
        for (const [bi, button] of [
          ...(block.buttons || []),
          ...(block.rowActions || []),
        ].entries()) {
          const bbp = `${bp}.buttons[${bi}]`;
          if (button.visibleWhen)
            expressions.push({ path: `${bbp}.visibleWhen`, expr: button.visibleWhen });
          if (button.enableWhen)
            expressions.push({ path: `${bbp}.enableWhen`, expr: button.enableWhen });
          if (button.disableWhen)
            expressions.push({ path: `${bbp}.disableWhen`, expr: button.disableWhen });
        }
      }
    }
  }

  return expressions;
}

export function validateExpressions(schema: UnifiedSchema): ValidationMessage[] {
  const messages: ValidationMessage[] = [];
  const expressions = collectExpressions(schema);

  for (const { path, expr } of expressions) {
    const result = isExpressionValid(expr);
    if (!result.valid) {
      messages.push({
        code: 'expr_syntax',
        path,
        message: `Invalid expression: ${result.error} — "${expr}"`,
        severity: 'error',
      });
    }
  }

  return messages;
}
