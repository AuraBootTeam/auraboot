/**
 * Click-insert target resolution — the single source of truth for WHERE a
 * palette-added block lands.
 *
 * `canAddBlock` (button enabled?) and `handleAddBlock` (where does it go?) both
 * consume {@link resolveInsertTarget}, so the two can never drift apart again:
 * previously each duplicated its own priority chain and a tweak to one silently
 * changed whether the palette button was clickable vs. where the block appeared.
 *
 * The click gesture's priority chain (first match wins):
 *  1. the selected block can contain the type       → append inside it
 *  2. the page's kind-root container can contain it → append inside the root
 *     (palette stays usable with the page root selected — the default state
 *     after opening a page)
 *  3. the selected block's parent can take the type → insert before the selected
 *  4. the page root accepts the type                → append at the page root
 *  otherwise → null (palette button disabled)
 *
 * Drag-and-drop resolves a DIFFERENT priority on purpose (before-first, see
 * `resolveDragEndAction`): a drop lands where the pointer is, while a click
 * targets the container the user selected. Both gestures share the same
 * capability probes — only the ordering differs by gesture semantics.
 */

export type InsertTargetPlacement =
  | 'inside-selected'
  | 'inside-kind-root'
  | 'before-selected'
  | 'page-root';

export interface InsertTargetResolution {
  placement: InsertTargetPlacement;
  /** Parent block receiving the new block (`inside-*` / `before-selected`). */
  parentBlockId: string | null;
  /** Existing block the new block is inserted before (`before-selected`). */
  targetBlockId: string | null;
}

export interface InsertTargetInput {
  selectedBlockId: string | null;
  selectedBlockType: string | null;
  /** The page's single kind-root container (e.g. the `list` root), if present. */
  kindRootContainerId: string | null;
  kindRootContainerType: string | null;
  /** Whether a parent of this blockType may contain the new block type. */
  canContain: (parentBlockType: string, blockType: string) => boolean;
  /** Whether the selected block's parent accepts the type (insert-before probe). */
  canInsertBeforeSelected: boolean;
  /** Whether the page root itself accepts the type. */
  canAddBlockToRoot: boolean;
}

export function resolveInsertTarget(
  input: InsertTargetInput,
  blockType: string,
): InsertTargetResolution | null {
  const { selectedBlockId, selectedBlockType } = input;

  if (selectedBlockId && selectedBlockType && input.canContain(selectedBlockType, blockType)) {
    return { placement: 'inside-selected', parentBlockId: selectedBlockId, targetBlockId: null };
  }
  if (input.kindRootContainerId && input.kindRootContainerType
    && input.canContain(input.kindRootContainerType, blockType)) {
    return {
      placement: 'inside-kind-root',
      parentBlockId: input.kindRootContainerId,
      targetBlockId: null,
    };
  }
  if (selectedBlockId && input.canInsertBeforeSelected) {
    return { placement: 'before-selected', parentBlockId: null, targetBlockId: selectedBlockId };
  }
  if (input.canAddBlockToRoot) {
    return { placement: 'page-root', parentBlockId: null, targetBlockId: null };
  }
  return null;
}
