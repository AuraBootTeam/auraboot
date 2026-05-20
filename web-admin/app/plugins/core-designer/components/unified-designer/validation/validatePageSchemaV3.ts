import { createDefaultBlockRegistryV3, type BlockRegistryV3 } from '../registry/BlockRegistry';
import type { DslBlockV3, PageSchemaV3, PageSchemaV3Kind } from '../types';

export interface PageSchemaV3ValidationError {
  code: string;
  message: string;
  path?: string;
  blockId?: string;
}

export interface PageSchemaV3ValidationResult {
  valid: boolean;
  errors: PageSchemaV3ValidationError[];
}

const VALID_KINDS = new Set<PageSchemaV3Kind>([
  'list',
  'detail',
  'form',
  'dashboard',
  'composite',
]);

export function validatePageSchemaV3(
  schema: PageSchemaV3,
  registry: BlockRegistryV3 = createDefaultBlockRegistryV3(),
): PageSchemaV3ValidationResult {
  const errors: PageSchemaV3ValidationError[] = [];
  const seenBlockIds = new Set<string>();

  if (schema.schemaVersion !== 3) {
    errors.push({
      code: 'schema_version',
      message: 'PageSchema V3 documents must use schemaVersion 3.',
      path: 'schemaVersion',
    });
  }

  if (!schema.id?.trim()) {
    errors.push({
      code: 'missing_id',
      message: 'PageSchema V3 documents must have a stable id.',
      path: 'id',
    });
  }

  if (!VALID_KINDS.has(schema.kind)) {
    errors.push({
      code: 'invalid_kind',
      message: `Unsupported PageSchema V3 kind: ${String(schema.kind)}.`,
      path: 'kind',
    });
  }

  if (!Array.isArray(schema.blocks) || schema.blocks.length === 0) {
    errors.push({
      code: 'empty_blocks',
      message: 'PageSchema V3 documents must contain at least one root block.',
      path: 'blocks',
    });
  }

  schema.blocks?.forEach((block, index) => {
    validateBlock({
      block,
      path: `blocks.${index}`,
      parentBlockType: null,
      registry,
      seenBlockIds,
      errors,
    });
  });

  return { valid: errors.length === 0, errors };
}

function validateBlock({
  block,
  path,
  parentBlockType,
  registry,
  seenBlockIds,
  errors,
}: {
  block: DslBlockV3;
  path: string;
  parentBlockType: string | null;
  registry: BlockRegistryV3;
  seenBlockIds: Set<string>;
  errors: PageSchemaV3ValidationError[];
}) {
  if (!block.id?.trim()) {
    errors.push({
      code: 'missing_block_id',
      message: 'Every V3 block must have a stable id.',
      path,
    });
  } else if (seenBlockIds.has(block.id)) {
    errors.push({
      code: 'duplicate_block_id',
      message: `Duplicate block id: ${block.id}.`,
      path: `${path}.id`,
      blockId: block.id,
    });
  } else {
    seenBlockIds.add(block.id);
  }

  if (!block.blockType?.trim()) {
    errors.push({
      code: 'missing_block_type',
      message: 'Every V3 block must declare blockType.',
      path: `${path}.blockType`,
      blockId: block.id,
    });
  }

  if (block.blockType && !registry.get(block.blockType)) {
    errors.push({
      code: 'unknown_block_type',
      message: `Unknown V3 blockType: ${block.blockType}.`,
      path: `${path}.blockType`,
      blockId: block.id,
    });
  }

  if (parentBlockType && !registry.canContain(parentBlockType, block.blockType)) {
    errors.push({
      code: 'invalid_child_block',
      message: `${parentBlockType} cannot contain ${block.blockType}.`,
      path,
      blockId: block.id,
    });
  }

  validateLayout(block, path, errors);

  block.blocks?.forEach((child, index) => {
    validateBlock({
      block: child,
      path: `${path}.blocks.${index}`,
      parentBlockType: block.blockType,
      registry,
      seenBlockIds,
      errors,
    });
  });
}

function validateLayout(
  block: DslBlockV3,
  path: string,
  errors: PageSchemaV3ValidationError[],
) {
  const { layout } = block;
  if (!layout) return;

  if (typeof layout.span === 'number' && (layout.span < 1 || layout.span > 12)) {
    errors.push({
      code: 'invalid_span',
      message: 'layout.span must be between 1 and 12.',
      path: `${path}.layout.span`,
      blockId: block.id,
    });
  }

  if (block.blockType !== 'widget') return;

  const width = layout.w;
  const height = layout.h;
  if (typeof width === 'number' && (width < 1 || width > 12)) {
    errors.push({
      code: 'invalid_widget_layout',
      message: 'Widget layout.w must be between 1 and 12.',
      path: `${path}.layout.w`,
      blockId: block.id,
    });
  }
  if (typeof height === 'number' && height < 1) {
    errors.push({
      code: 'invalid_widget_layout',
      message: 'Widget layout.h must be greater than zero.',
      path: `${path}.layout.h`,
      blockId: block.id,
    });
  }
}
