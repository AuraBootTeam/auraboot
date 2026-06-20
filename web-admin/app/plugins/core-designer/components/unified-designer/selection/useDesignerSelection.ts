/**
 * useDesignerSelection — the reusable block-tree selection kernel.
 *
 * Extracted verbatim (behavior-preserving) from UnifiedDesignerWorkbench so
 * every block-tree-family designer surface (page designer today, report
 * designer next — DDR-2026-06-18) shares ONE selection model instead of
 * reinventing the modifier-click / marquee folding rules.
 *
 * Model (unchanged from the Workbench):
 *   - `selectedBlockId` is the PRIMARY selection — it is dual-purpose: the
 *     inspector target AND the drop-placement context (palette drops land
 *     inside / before it).
 *   - `multiSelectedIds` is an INDEPENDENT additive set. It never perturbs the
 *     primary (and therefore the drop context); it tracks its own ids.
 *
 * Raw `setSelectedBlockId` / `setMultiSelectedIds` are exposed because the host
 * drives the primary from many places (add / move / delete / reset / import /
 * AI apply); the three smart transitions encapsulate the click/marquee rules.
 */
import { useState, type Dispatch, type SetStateAction } from 'react';

export interface DesignerSelectionController {
  selectedBlockId: string | null;
  multiSelectedIds: Set<string>;
  setSelectedBlockId: Dispatch<SetStateAction<string | null>>;
  setMultiSelectedIds: Dispatch<SetStateAction<Set<string>>>;
  /**
   * Canvas click selection with modifier support. A plain click is a
   * single-select (clears multi). A shift/cmd/ctrl click toggles the block in /
   * out of the multi-selection AND makes it the primary. On the FIRST additive
   * click (multi-set empty) the existing primary is folded into the set first,
   * so "click A, shift+click B" yields {A, B}.
   */
  selectFromCanvas: (blockId: string, modifiers?: { additive?: boolean }) => void;
  /**
   * Box-select (marquee) result. A fresh marquee replaces the multi-selection:
   * empty clears it (primary untouched), exactly one behaves like a single
   * select, many sets the set and makes the last id primary.
   */
  selectFromMarquee: (blockIds: string[]) => void;
  /** Clear the multi-selection; the primary is left intact. */
  clearMultiSelection: () => void;
}

export function useDesignerSelection(): DesignerSelectionController {
  const [selectedBlockId, setSelectedBlockId] = useState<string | null>(null);
  const [multiSelectedIds, setMultiSelectedIds] = useState<Set<string>>(() => new Set());

  const clearMultiSelection = () => {
    setMultiSelectedIds((current) => (current.size === 0 ? current : new Set()));
  };

  const selectFromCanvas = (blockId: string, modifiers?: { additive?: boolean }) => {
    if (!modifiers?.additive) {
      setMultiSelectedIds((current) => (current.size === 0 ? current : new Set()));
      setSelectedBlockId(blockId);
      return;
    }
    const primaryId = selectedBlockId;
    setMultiSelectedIds((current) => {
      const next = new Set(current);
      if (next.size === 0 && primaryId && primaryId !== blockId) {
        next.add(primaryId);
      }
      if (next.has(blockId)) {
        next.delete(blockId);
      } else {
        next.add(blockId);
      }
      return next;
    });
    setSelectedBlockId(blockId);
  };

  const selectFromMarquee = (blockIds: string[]) => {
    if (blockIds.length === 0) {
      clearMultiSelection();
      return;
    }
    if (blockIds.length === 1) {
      setMultiSelectedIds((current) => (current.size === 0 ? current : new Set()));
      setSelectedBlockId(blockIds[0]);
      return;
    }
    setMultiSelectedIds(new Set(blockIds));
    setSelectedBlockId(blockIds[blockIds.length - 1]);
  };

  return {
    selectedBlockId,
    multiSelectedIds,
    setSelectedBlockId,
    setMultiSelectedIds,
    selectFromCanvas,
    selectFromMarquee,
    clearMultiSelection,
  };
}
