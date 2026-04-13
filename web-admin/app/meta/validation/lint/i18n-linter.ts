/**
 * i18n Linter — detects hardcoded CJK characters in DSL schemas
 *
 * Rule: I18N_HARDCODED
 *
 * Per project convention, all user-visible labels must use i18n keys
 * or LocalizedText objects, not raw CJK strings.
 */

import type { UnifiedSchema } from '~/meta/schemas/types';
import type { ValidationMessage } from '../types';

/** Detect CJK characters (Chinese, Japanese, Korean) */
const CJK_REGEX =
  /[\u4e00-\u9fff\u3400-\u4dbf\u3000-\u303f\u3040-\u309f\u30a0-\u30ff\uac00-\ud7af]/;

function hasCJK(value: unknown): boolean {
  if (typeof value === 'string') return CJK_REGEX.test(value);
  return false;
}

export function lintI18n(schema: UnifiedSchema): ValidationMessage[] {
  const messages: ValidationMessage[] = [];

  // Check schema title
  if (hasCJK(schema.title)) {
    messages.push({
      code: 'i18n_hardcoded',
      path: 'title',
      message: 'Schema title contains CJK characters — use i18n key or LocalizedText object',
      severity: 'warning',
    });
  }

  if (!schema.blocks) return messages;

  for (const [blockIdx, block] of schema.blocks.entries()) {
    const bp = `blocks[${blockIdx}]`;

    if (hasCJK(block.title)) {
      messages.push({
        code: 'i18n_hardcoded',
        path: `${bp}.title`,
        message: `Block title contains CJK characters: "${block.title}"`,
        severity: 'warning',
      });
    }

    // Check field labels
    for (const [fi, field] of (block.fields || []).entries()) {
      if (hasCJK(field.label)) {
        messages.push({
          code: 'i18n_hardcoded',
          path: `${bp}.fields[${fi}].label`,
          message: `Field label contains CJK characters: "${field.label}"`,
          severity: 'warning',
        });
      }
    }

    // Check button labels
    for (const [bi, button] of (block.buttons || []).entries()) {
      if (hasCJK(button.label)) {
        messages.push({
          code: 'i18n_hardcoded',
          path: `${bp}.buttons[${bi}].label`,
          message: `Button label contains CJK characters: "${button.label}"`,
          severity: 'warning',
        });
      }
      if (hasCJK(button.content)) {
        messages.push({
          code: 'i18n_hardcoded',
          path: `${bp}.buttons[${bi}].content`,
          message: `Button content contains CJK characters: "${button.content}"`,
          severity: 'warning',
        });
      }
    }

    // Check column labels
    const columns = Array.isArray(block.columns) ? block.columns : [];
    for (const [ci, col] of columns.entries()) {
      if (hasCJK(col.label)) {
        messages.push({
          code: 'i18n_hardcoded',
          path: `${bp}.columns[${ci}].label`,
          message: `Column label contains CJK characters: "${col.label}"`,
          severity: 'warning',
        });
      }
    }
  }

  return messages;
}
