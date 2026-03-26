/**
 * Performance Linter — flags DSL patterns that may cause rendering bottlenecks
 *
 * Rules:
 * - PERF_COLUMNS: table has too many columns (> 20)
 * - PERF_FIELDS: form section has too many fields (> 15)
 * - PERF_TAB_DEPTH: tab nesting exceeds recommended depth (> 2)
 */

import type { UnifiedSchema } from '~/meta/schemas/types';
import type { ValidationMessage } from '../types';

const MAX_TABLE_COLUMNS = 20;
const MAX_FORM_FIELDS = 15;
const MAX_TAB_NESTING = 2;

export function lintPerformance(schema: UnifiedSchema): ValidationMessage[] {
  const messages: ValidationMessage[] = [];

  if (!schema.areas) return messages;

  for (const [areaId, area] of Object.entries(schema.areas)) {
    for (const [blockIdx, block] of (area.blocks || []).entries()) {
      const bp = `areas.${areaId}.blocks[${blockIdx}]`;

      // Check table column count
      if (
        (block.blockType === 'table' || block.blockType === 'data-table') &&
        Array.isArray(block.columns)
      ) {
        if (block.columns.length > MAX_TABLE_COLUMNS) {
          messages.push({
            code: 'perf_columns',
            path: `${bp}.columns`,
            message: `Table has ${block.columns.length} columns (max recommended: ${MAX_TABLE_COLUMNS})`,
            severity: 'warning',
            suggestion:
              'Consider using column groups, SavedView, or hiding less-used columns by default',
          });
        }
      }

      // Check form field count
      if (block.blockType === 'form-section' && block.fields) {
        if (block.fields.length > MAX_FORM_FIELDS) {
          messages.push({
            code: 'perf_fields',
            path: `${bp}.fields`,
            message: `Form section has ${block.fields.length} fields (max recommended: ${MAX_FORM_FIELDS})`,
            severity: 'warning',
            suggestion: 'Consider splitting into multiple form sections or using tabs',
          });
        }
      }

      // Check tab nesting depth
      if (block.blockType === 'tabs' && block.tabs) {
        checkTabNesting(block.tabs as any[], bp, 1, messages);
      }
    }
  }

  return messages;
}

function checkTabNesting(
  tabs: any[],
  parentPath: string,
  depth: number,
  messages: ValidationMessage[],
): void {
  if (depth > MAX_TAB_NESTING) {
    messages.push({
      code: 'perf_tab_depth',
      path: parentPath,
      message: `Tab nesting depth ${depth} exceeds maximum recommended ${MAX_TAB_NESTING}`,
      severity: 'warning',
      suggestion: 'Deep tab nesting harms usability — consider flattening navigation',
    });
    return;
  }

  for (const tab of tabs) {
    if (tab.blocks) {
      for (const block of tab.blocks) {
        if (block.blockType === 'tabs' && block.tabs) {
          checkTabNesting(block.tabs, `${parentPath}.tabs`, depth + 1, messages);
        }
      }
    }
  }
}
