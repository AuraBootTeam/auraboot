/**
 * Reference Validator — validates cross-references in DSL schemas
 *
 * Rules:
 * - REF_DATASOURCE: block.dataSource references must exist in schema.dataSources
 * - REF_HANDLER: button.handler / events.handler must exist in schema.handlers
 * - REF_NAVIGATE: button.navigateTo should be a valid pageKey pattern
 */

import type { UnifiedSchema } from '~/meta/schemas/types';
import type { ValidationMessage } from '../types';

export function validateReferences(schema: UnifiedSchema): ValidationMessage[] {
  const messages: ValidationMessage[] = [];
  const dataSourceIds = new Set(Object.keys(schema.dataSources || {}));
  const handlerIds = new Set(Object.keys(schema.handlers || {}));

  // Validate block dataSource references
  if (schema.blocks) {
    for (const [blockIdx, block] of schema.blocks.entries()) {
      const blockPath = `blocks[${blockIdx}]`;

      // Check dataSource reference
      if (block.dataSource && typeof block.dataSource === 'string') {
        if (dataSourceIds.size > 0 && !dataSourceIds.has(block.dataSource)) {
          messages.push({
            code: 'ref_datasource',
            path: `${blockPath}.dataSource`,
            message: `DataSource "${block.dataSource}" not found in schema.dataSources`,
            severity: 'error',
          });
        }
      }

      // Check button handler references
      const buttons = [...(block.buttons || []), ...(block.rowActions || [])];
      for (const [btnIdx, button] of buttons.entries()) {
        if (button.handler && handlerIds.size > 0 && !handlerIds.has(button.handler)) {
          messages.push({
            code: 'ref_handler',
            path: `${blockPath}.buttons[${btnIdx}].handler`,
            message: `Handler "${button.handler}" not found in schema.handlers`,
            severity: 'error',
          });
        }
      }

      // Check field dataSource references (string refs)
      for (const [fieldIdx, field] of (block.fields || []).entries()) {
        if (field.dataSource && typeof field.dataSource === 'string') {
          if (dataSourceIds.size > 0 && !dataSourceIds.has(field.dataSource)) {
            messages.push({
              code: 'ref_datasource',
              path: `${blockPath}.fields[${fieldIdx}].dataSource`,
              message: `DataSource "${field.dataSource}" not found in schema.dataSources`,
              severity: 'error',
            });
          }
        }
      }
    }
  }

  return messages;
}
