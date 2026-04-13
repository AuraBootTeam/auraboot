import type { TreeRow } from '~/framework/meta/hooks/useTreeData';

export const INDENT_WIDTH = 24; // px per depth level

/**
 * Check if targetId is a descendant of draggedId.
 * Prevents dropping a node into its own subtree (cycle).
 */
export function isDescendant(draggedId: string, targetId: string, flatTree: TreeRow[]): boolean {
  let current = flatTree.find((r) => (r.pid ?? r.id) === targetId);
  while (current) {
    if (current._parentId === draggedId) return true;
    if (!current._parentId) return false;
    current = flatTree.find((r) => (r.pid ?? r.id) === current!._parentId);
  }
  return false;
}

export interface DropTarget {
  parentId: string | null;
  insertIndex: number;
}

/**
 * Compute drop target based on mouse position relative to the drop row.
 *
 * @param deltaX - horizontal mouse offset from drag start (px)
 * @param overRow - the row being hovered over
 * @param flatTree - all visible rows
 * @param maxDepth - maximum allowed depth
 */
export function computeDropTarget(
  deltaX: number,
  overRow: TreeRow,
  flatTree: TreeRow[],
  maxDepth: number,
): DropTarget {
  const overDepth = overRow._depth;
  const depthDelta = Math.round(deltaX / INDENT_WIDTH);
  const intendedDepth = Math.max(0, Math.min(overDepth + depthDelta, maxDepth - 1));
  const overRowId = overRow.pid ?? overRow.id;

  if (intendedDepth > overDepth) {
    // Reparent: become child of overRow
    return { parentId: overRowId, insertIndex: 0 };
  } else if (intendedDepth === overDepth) {
    // Same level: insert after overRow under same parent
    const siblings = flatTree.filter((r) => r._parentId === overRow._parentId);
    const idx = siblings.findIndex((r) => (r.pid ?? r.id) === overRowId);
    return { parentId: overRow._parentId, insertIndex: idx + 1 };
  } else {
    // Outdent: walk up to find ancestor at intended depth
    let ancestor: TreeRow | undefined = overRow;
    while (ancestor && ancestor._depth > intendedDepth) {
      ancestor = flatTree.find((r) => (r.pid ?? r.id) === ancestor!._parentId);
    }
    if (!ancestor) {
      return { parentId: null, insertIndex: flatTree.length };
    }
    const ancestorId = ancestor.pid ?? ancestor.id;
    const siblings = flatTree.filter((r) => r._parentId === ancestor!._parentId);
    const idx = siblings.findIndex((r) => (r.pid ?? r.id) === ancestorId);
    return { parentId: ancestor._parentId, insertIndex: idx + 1 };
  }
}

/**
 * Recompute sort_order values for siblings under the same parent.
 * Uses gap of 1000.
 */
export function recomputeSortOrder(
  rows: Record<string, any>[],
  parentId: string | null,
  parentField: string,
): { pid: string; sort_order: number; [key: string]: any }[] {
  const siblings = rows.filter((r) => (r[parentField] ?? null) === parentId);
  return siblings.map((r, i) => ({
    pid: r.pid ?? r.id,
    sort_order: (i + 1) * 1000,
  }));
}
