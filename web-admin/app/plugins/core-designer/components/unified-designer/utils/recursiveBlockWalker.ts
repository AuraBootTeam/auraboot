import type { DslBlockV3, FindBlockResult } from '../types';

export function findBlockById(blocks: DslBlockV3[], blockId: string): FindBlockResult | null {
  return findBlockByIdInternal(blocks, blockId, []);
}

function findBlockByIdInternal(
  blocks: DslBlockV3[],
  blockId: string,
  parentPath: FindBlockResult['path'],
  parentId?: string,
): FindBlockResult | null {
  for (let index = 0; index < blocks.length; index += 1) {
    const block = blocks[index];
    const path = [...parentPath, { id: block.id, block, index, parentId }];
    if (block.id === blockId) {
      return { block, path };
    }
    if (block.blocks?.length) {
      const child = findBlockByIdInternal(block.blocks, blockId, path, block.id);
      if (child) return child;
    }
  }
  return null;
}

export function updateBlockById(
  blocks: DslBlockV3[],
  blockId: string,
  updater: (block: DslBlockV3) => DslBlockV3,
): DslBlockV3[] {
  let changed = false;
  const next = blocks.map((block) => {
    if (block.id === blockId) {
      changed = true;
      return updater(block);
    }
    if (block.blocks?.length) {
      const childBlocks = updateBlockById(block.blocks, blockId, updater);
      if (childBlocks !== block.blocks) {
        changed = true;
        return { ...block, blocks: childBlocks };
      }
    }
    return block;
  });
  return changed ? next : blocks;
}

export function moveBlockBefore(
  blocks: DslBlockV3[],
  movingBlockId: string,
  targetBlockId: string,
): DslBlockV3[] {
  if (movingBlockId === targetBlockId) return blocks;
  return moveWithinParent(blocks, movingBlockId, targetBlockId).blocks;
}

function moveWithinParent(
  blocks: DslBlockV3[],
  movingBlockId: string,
  targetBlockId: string,
): { blocks: DslBlockV3[]; changed: boolean } {
  const movingIndex = blocks.findIndex((block) => block.id === movingBlockId);
  const targetIndex = blocks.findIndex((block) => block.id === targetBlockId);

  if (movingIndex !== -1 && targetIndex !== -1) {
    const next = [...blocks];
    const [moving] = next.splice(movingIndex, 1);
    const adjustedTargetIndex = movingIndex < targetIndex ? targetIndex - 1 : targetIndex;
    next.splice(adjustedTargetIndex, 0, moving);
    return { blocks: next, changed: true };
  }

  let changed = false;
  const next = blocks.map((block) => {
    if (!block.blocks?.length) return block;
    const result = moveWithinParent(block.blocks, movingBlockId, targetBlockId);
    if (!result.changed) return block;
    changed = true;
    return { ...block, blocks: result.blocks };
  });

  return { blocks: changed ? next : blocks, changed };
}
