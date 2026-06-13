import type { DslBlockV3, PageSchemaV3Kind } from '../types';
import type { BlockRegistryV3 } from '../registry/BlockRegistry';
import { getKindPolicy, isBlockTypeAllowedForKind } from '../registry/kindPolicy';
import { findBlockById } from '../utils/recursiveBlockWalker';

interface MoveGuardBase {
  blocks: DslBlockV3[];
  kind: PageSchemaV3Kind;
  blockRegistry: Pick<BlockRegistryV3, 'canContain' | 'get'>;
  movingBlockId: string;
}

interface BeforeTargetMoveGuard extends MoveGuardBase {
  targetBlockId: string;
}

interface ParentMoveGuard extends MoveGuardBase {
  parentBlockId: string;
}

export function canMoveExistingBlockBeforeTarget({
  blocks,
  kind,
  blockRegistry,
  movingBlockId,
  targetBlockId,
}: BeforeTargetMoveGuard): boolean {
  if (movingBlockId === targetBlockId) return false;

  const movingResult = findBlockById(blocks, movingBlockId);
  const targetResult = findBlockById(blocks, targetBlockId);
  if (!movingResult || !targetResult) return false;
  if (targetResult.path.some((item) => item.id === movingBlockId)) return false;

  const movingBlockType = movingResult.block.blockType;
  if (!isBlockTypeAllowedForKind(kind, movingBlockType)) return false;

  if (targetResult.path.length === 1) {
    const definition = blockRegistry.get(movingBlockType);
    if (definition?.category !== 'page') return false;
    const policy = getKindPolicy(kind);
    return !policy.rootBlockType || policy.rootBlockType === movingBlockType;
  }

  const parentBlock = targetResult.path[targetResult.path.length - 2].block;
  return blockRegistry.canContain(parentBlock.blockType, movingBlockType);
}

export function canMoveExistingBlockToParent({
  blocks,
  kind,
  blockRegistry,
  movingBlockId,
  parentBlockId,
}: ParentMoveGuard): boolean {
  if (movingBlockId === parentBlockId) return false;

  const movingResult = findBlockById(blocks, movingBlockId);
  const parentResult = findBlockById(blocks, parentBlockId);
  if (!movingResult || !parentResult) return false;
  if (parentResult.path.some((item) => item.id === movingBlockId)) return false;

  const movingBlockType = movingResult.block.blockType;
  if (!isBlockTypeAllowedForKind(kind, movingBlockType)) return false;

  return blockRegistry.canContain(parentResult.block.blockType, movingBlockType);
}
