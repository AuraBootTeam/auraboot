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

/** Remove a block (and its subtree) by id, immutably. Returns the original array if not found. */
export function removeBlockById(blocks: DslBlockV3[], blockId: string): DslBlockV3[] {
  let changed = false;
  const next: DslBlockV3[] = [];
  for (const block of blocks) {
    if (block.id === blockId) {
      changed = true;
      continue;
    }
    if (block.blocks?.length) {
      const childBlocks = removeBlockById(block.blocks, blockId);
      if (childBlocks !== block.blocks) {
        changed = true;
        next.push({ ...block, blocks: childBlocks });
        continue;
      }
    }
    next.push(block);
  }
  return changed ? next : blocks;
}

export function moveBlockBefore(
  blocks: DslBlockV3[],
  movingBlockId: string,
  targetBlockId: string,
): DslBlockV3[] {
  if (movingBlockId === targetBlockId) return blocks;
  const movingResult = findBlockById(blocks, movingBlockId);
  const targetResult = findBlockById(blocks, targetBlockId);
  if (!movingResult || !targetResult) return blocks;
  if (targetResult.path.some((item) => item.id === movingBlockId)) return blocks;

  const extraction = extractBlockById(blocks, movingBlockId);
  if (!extraction) return blocks;

  const insertion = insertBlockBeforeId(extraction.blocks, targetBlockId, extraction.block);
  return insertion.changed ? insertion.blocks : blocks;
}

export function moveBlockToParent(
  blocks: DslBlockV3[],
  movingBlockId: string,
  parentBlockId: string,
): DslBlockV3[] {
  if (movingBlockId === parentBlockId) return blocks;
  const movingResult = findBlockById(blocks, movingBlockId);
  const parentResult = findBlockById(blocks, parentBlockId);
  if (!movingResult || !parentResult) return blocks;
  if (parentResult.path.some((item) => item.id === movingBlockId)) return blocks;

  const extraction = extractBlockById(blocks, movingBlockId);
  if (!extraction) return blocks;

  const insertion = insertBlockIntoParentId(extraction.blocks, parentBlockId, extraction.block);
  return insertion.changed ? insertion.blocks : blocks;
}

function extractBlockById(
  blocks: DslBlockV3[],
  blockId: string,
): { blocks: DslBlockV3[]; block: DslBlockV3 } | null {
  const directIndex = blocks.findIndex((block) => block.id === blockId);
  if (directIndex !== -1) {
    const next = [...blocks];
    const [block] = next.splice(directIndex, 1);
    return { blocks: next, block };
  }

  for (let index = 0; index < blocks.length; index += 1) {
    const block = blocks[index];
    if (!block.blocks?.length) continue;
    const extraction = extractBlockById(block.blocks, blockId);
    if (!extraction) continue;
    const next = [...blocks];
    next[index] = { ...block, blocks: extraction.blocks };
    return { blocks: next, block: extraction.block };
  }

  return null;
}

function insertBlockBeforeId(
  blocks: DslBlockV3[],
  targetBlockId: string,
  movingBlock: DslBlockV3,
): { blocks: DslBlockV3[]; changed: boolean } {
  const targetIndex = blocks.findIndex((block) => block.id === targetBlockId);
  if (targetIndex !== -1) {
    const next = [...blocks];
    next.splice(targetIndex, 0, movingBlock);
    return { blocks: next, changed: true };
  }

  for (let index = 0; index < blocks.length; index += 1) {
    const block = blocks[index];
    if (!block.blocks?.length) continue;
    const insertion = insertBlockBeforeId(block.blocks, targetBlockId, movingBlock);
    if (!insertion.changed) continue;
    const next = [...blocks];
    next[index] = { ...block, blocks: insertion.blocks };
    return { blocks: next, changed: true };
  }

  return { blocks, changed: false };
}

function insertBlockIntoParentId(
  blocks: DslBlockV3[],
  parentBlockId: string,
  movingBlock: DslBlockV3,
): { blocks: DslBlockV3[]; changed: boolean } {
  for (let index = 0; index < blocks.length; index += 1) {
    const block = blocks[index];
    if (block.id === parentBlockId) {
      const next = [...blocks];
      next[index] = { ...block, blocks: [...(block.blocks ?? []), movingBlock] };
      return { blocks: next, changed: true };
    }
    if (!block.blocks?.length) continue;
    const insertion = insertBlockIntoParentId(block.blocks, parentBlockId, movingBlock);
    if (!insertion.changed) continue;
    const next = [...blocks];
    next[index] = { ...block, blocks: insertion.blocks };
    return { blocks: next, changed: true };
  }

  return { blocks, changed: false };
}
