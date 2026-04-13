/**
 * useCanvasBlocks — Hook for managing canvas block CRUD operations
 *
 * Manages the blocks array state for the composite page editor,
 * including add, remove, move, update, and selection.
 *
 * @since 4.0.0
 */

import { useState, useCallback, useMemo } from 'react';
import type { CanvasBlock } from '~/plugins/core-designer/components/studio/domain/canvas/types';
import type { BlockLayoutConfig } from '~/framework/meta/schemas/types';
import { BlockRegistry } from '~/plugins/core-designer/components/studio/registry';

let blockCounter = 0;

/**
 * Generate a unique block ID
 */
function generateBlockId(): string {
  blockCounter += 1;
  return `blk_${Date.now()}_${blockCounter}`;
}

export interface UseCanvasBlocksReturn {
  /** Current list of blocks */
  blocks: CanvasBlock[];
  /** Currently selected block ID */
  selectedBlockId: string | null;
  /** Currently selected block (derived) */
  selectedBlock: CanvasBlock | null;
  /** Add a new block */
  addBlock: (blockType: string, index?: number, config?: Record<string, unknown>) => CanvasBlock;
  /** Add a new block at a specific grid position (col/colSpan) */
  addBlockAt: (blockType: string, col: number, colSpan: number, rowSpan?: number, config?: Record<string, unknown>) => CanvasBlock;
  /** Remove a block by ID */
  removeBlock: (id: string) => void;
  /** Move a block from one index to another */
  moveBlock: (fromIndex: number, toIndex: number) => void;
  /** Update a block by ID with a partial patch */
  updateBlock: (id: string, patch: Partial<CanvasBlock>) => void;
  /** Batch-update layout for multiple blocks */
  updateBlockLayouts: (updates: Array<{ id: string; layout: BlockLayoutConfig }>) => void;
  /** Set the selected block ID */
  setSelectedBlockId: (id: string | null) => void;
  /** Replace the entire blocks array */
  setBlocks: (blocks: CanvasBlock[]) => void;
}

/**
 * Hook for canvas block CRUD operations
 */
export function useCanvasBlocks(
  initialBlocks: CanvasBlock[] = [],
): UseCanvasBlocksReturn {
  const [blocks, setBlocks] = useState<CanvasBlock[]>(initialBlocks);
  const [selectedBlockId, setSelectedBlockId] = useState<string | null>(null);

  const selectedBlock = useMemo(
    () => blocks.find((b) => b.id === selectedBlockId) ?? null,
    [blocks, selectedBlockId],
  );

  const addBlock = useCallback(
    (blockType: string, index?: number, config?: Record<string, unknown>): CanvasBlock => {
      const colSpan = BlockRegistry.getDefaultColSpan(blockType);
      const blockId = generateBlockId();

      // Use setBlocks callback to read latest state (avoids stale closure)
      let createdBlock: CanvasBlock | null = null;
      setBlocks((prev) => {
        // Auto-place: find the next available column on the last row
        let col = 0;
        if (prev.length > 0) {
          const lastBlock = prev[prev.length - 1];
          const lastCol = lastBlock.layout?.col ?? 0;
          const lastSpan = lastBlock.layout?.colSpan ?? 12;
          const nextCol = lastCol + lastSpan;
          if (nextCol + colSpan <= 12) {
            col = nextCol; // Fits on the same row
          }
        }

        const newBlock: CanvasBlock = {
          id: blockId,
          blockType,
          config: config ?? {},
          layout: {
            col,
            colSpan,
            rowSpan: 1,
            order: prev.length,
          },
        };
        createdBlock = newBlock;

        const next = [...prev];
        if (index !== undefined && index >= 0 && index <= next.length) {
          next.splice(index, 0, newBlock);
        } else {
          next.push(newBlock);
        }
        return next;
      });
      setSelectedBlockId(blockId);
      return createdBlock!;
    },
    [], // No deps — uses setBlocks callback form for latest state
  );

  const addBlockAt = useCallback(
    (blockType: string, col: number, colSpan: number, rowSpan?: number, config?: Record<string, unknown>): CanvasBlock => {
      const newBlock: CanvasBlock = {
        id: generateBlockId(),
        blockType,
        config: config ?? {},
        layout: {
          col,
          colSpan,
          rowSpan: rowSpan ?? 1,
          order: blocks.length,
        },
      };
      setBlocks((prev) => [...prev, newBlock]);
      setSelectedBlockId(newBlock.id);
      return newBlock;
    },
    [blocks.length],
  );

  const removeBlock = useCallback(
    (id: string) => {
      setBlocks((prev) => prev.filter((b) => b.id !== id));
      setSelectedBlockId((prev) => (prev === id ? null : prev));
    },
    [],
  );

  const moveBlock = useCallback(
    (fromIndex: number, toIndex: number) => {
      setBlocks((prev) => {
        if (
          fromIndex < 0 ||
          fromIndex >= prev.length ||
          toIndex < 0 ||
          toIndex >= prev.length
        ) {
          return prev;
        }
        const next = [...prev];
        const [moved] = next.splice(fromIndex, 1);
        next.splice(toIndex, 0, moved);
        return next;
      });
    },
    [],
  );

  const updateBlock = useCallback(
    (id: string, patch: Partial<CanvasBlock>) => {
      setBlocks((prev) =>
        prev.map((b) => {
          if (b.id !== id) return b;
          return {
            ...b,
            ...patch,
            config: patch.config
              ? { ...b.config, ...patch.config }
              : b.config,
          };
        }),
      );
    },
    [],
  );

  const updateBlockLayouts = useCallback(
    (layoutUpdates: Array<{ id: string; layout: BlockLayoutConfig }>) => {
      setBlocks((prev) =>
        prev.map((block) => {
          const update = layoutUpdates.find((u) => u.id === block.id);
          if (!update) return block;
          return { ...block, layout: update.layout };
        }),
      );
    },
    [],
  );

  return {
    blocks,
    selectedBlockId,
    selectedBlock,
    addBlock,
    addBlockAt,
    removeBlock,
    moveBlock,
    updateBlock,
    updateBlockLayouts,
    setSelectedBlockId,
    setBlocks,
  };
}
