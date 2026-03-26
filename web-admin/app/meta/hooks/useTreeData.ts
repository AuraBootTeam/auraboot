import { useState, useMemo, useCallback } from 'react';
import type { TreeConfig } from '~/meta/schemas/types';

export interface TreeRow extends Record<string, any> {
  _depth: number;
  _hasChildren: boolean;
  _expanded: boolean;
  _parentId: string | null;
}

export interface UseTreeDataResult {
  /** Depth-first ordered flat tree with metadata */
  flatTree: TreeRow[];
  /** Visible rows (filtered by expand state) */
  visibleRows: TreeRow[];
  /** Toggle expand/collapse for a node */
  toggleExpand: (id: string) => void;
  /** Currently expanded node IDs */
  expandedIds: Set<string>;
  /** Get children of a node */
  getChildren: (parentId: string | null) => TreeRow[];
}

/**
 * Build a tree structure from flat row data.
 * Input: flat array with parent_id references.
 * Output: depth-first ordered flatTree with _depth, _hasChildren, _expanded metadata.
 */
export function useTreeData(
  rows: Record<string, any>[],
  treeConfig: TreeConfig | undefined,
): UseTreeDataResult {
  const parentField = treeConfig?.parentField ?? 'parent_id';
  const defaultExpanded = treeConfig?.defaultExpanded ?? true;

  // Group rows by parent
  const childrenMap = useMemo(() => {
    const map = new Map<string | null, Record<string, any>[]>();
    for (const row of rows) {
      const parentId = row[parentField] ?? null;
      if (!map.has(parentId)) map.set(parentId, []);
      map.get(parentId)!.push(row);
    }
    // Sort each group by sort_order, then created_at
    map.forEach((children) => {
      children.sort((a, b) => {
        const sa = a.sort_order ?? 0;
        const sb = b.sort_order ?? 0;
        if (sa !== sb) return sa - sb;
        return String(a.created_at ?? '').localeCompare(String(b.created_at ?? ''));
      });
    });
    return map;
  }, [rows, parentField]);

  // Expand state
  const [expandedIds, setExpandedIds] = useState<Set<string>>(() => {
    if (!defaultExpanded) return new Set<string>();
    // Default: all nodes with children expanded
    const ids = new Set<string>();
    for (const row of rows) {
      const pid = row.pid ?? row.id;
      if (pid && childrenMap.has(pid)) {
        ids.add(pid);
      }
    }
    return ids;
  });

  // Build flat tree (depth-first)
  const flatTree = useMemo(() => {
    const result: TreeRow[] = [];

    function walk(parentId: string | null, depth: number) {
      const children = childrenMap.get(parentId) ?? [];
      for (const row of children) {
        const rowId = row.pid ?? row.id;
        const hasChildren = childrenMap.has(rowId) && (childrenMap.get(rowId)?.length ?? 0) > 0;
        result.push({
          ...row,
          _depth: depth,
          _hasChildren: hasChildren,
          _expanded: expandedIds.has(rowId),
          _parentId: parentId,
        });
        if (hasChildren && expandedIds.has(rowId)) {
          walk(rowId, depth + 1);
        }
      }
    }

    walk(null, 0);
    return result;
  }, [childrenMap, expandedIds]);

  // Visible rows = flatTree (already filtered by expand state in walk)
  const visibleRows = flatTree;

  const toggleExpand = useCallback((id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  const getChildren = useCallback(
    (parentId: string | null) => {
      return (childrenMap.get(parentId) ?? []) as TreeRow[];
    },
    [childrenMap],
  );

  return { flatTree, visibleRows, toggleExpand, expandedIds, getChildren };
}
