/**
 * Best Practice Linter — flags patterns that are valid but suboptimal
 *
 * Rules:
 * - BP_SCRIPT: handler.type "script" is deprecated
 * - BP_UNKNOWN_BLOCK: unknown blockType may not render correctly
 * - BP_MISSING_ID: block without id may cause state management issues
 * - BP_VERSION: schema version 0.0.0 should be incremented for production
 */

import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import type { ValidationMessage } from '../types';
import { BLOCK_TYPES } from '../schemas/block.schema';

const blockTypeSet = new Set<string>(BLOCK_TYPES);

export function lintBestPractice(schema: UnifiedSchema): ValidationMessage[] {
  const messages: ValidationMessage[] = [];

  // Check handlers for deprecated patterns
  if (schema.handlers) {
    for (const [handlerId, handler] of Object.entries(schema.handlers)) {
      if (handler.type === 'script') {
        messages.push({
          code: 'bp_script',
          path: `handlers.${handlerId}`,
          message: 'handler.type "script" is deprecated — use "flow" or "builtin" instead',
          severity: 'warning',
          suggestion: 'Migrate to flow-based handlers for better maintainability',
        });
      }
    }
  }

  if (schema.blocks) {
    for (const [blockIdx, block] of schema.blocks.entries()) {
      const bp = `blocks[${blockIdx}]`;

      // Check for unknown blockType
      if (block.blockType && !blockTypeSet.has(block.blockType)) {
        messages.push({
          code: 'bp_unknown_block',
          path: `${bp}.blockType`,
          message: `Unknown block type "${block.blockType}" — may not render correctly`,
          severity: 'warning',
          suggestion: `Valid types: ${BLOCK_TYPES.join(', ')}`,
        });
      }

      // Check for missing block id
      if (!block.id) {
        messages.push({
          code: 'bp_missing_id',
          path: bp,
          message: 'Block is missing an id — this may cause issues with state management',
          severity: 'info',
        });
      }
    }
  }

  // Check version
  if (schema.version && schema.version === '0.0.0') {
    messages.push({
      code: 'bp_version',
      path: 'version',
      message: 'Schema version is 0.0.0 — consider incrementing for production use',
      severity: 'info',
    });
  }

  // Check schemaVersion — currently only version 1 is supported
  if (schema.schemaVersion != null && schema.schemaVersion > 1) {
    messages.push({
      code: 'bp_schema_version',
      path: 'schemaVersion',
      message: `DSL schema version ${schema.schemaVersion} is newer than supported (1) — may not render correctly`,
      severity: 'warning',
    });
  }

  return messages;
}
