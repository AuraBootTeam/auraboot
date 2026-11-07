import { useState, useMemo, useCallback } from 'react';
import type { TreeConfig } from '~/framework/meta/schemas/types';

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
  const normalizedRows = useMemo(() => {
    if (!treeConfig?.childrenField) return rows;
    const result: Record<string, any>[] = [], seen = new Set<string>();
    const visit = (nodes: Record<string, any>[], parent: string | null, depth: number) => {
      if (depth > (treeConfig.maxDepth ?? 5)) throw new Error('Nested table tree exceeds maximum depth');
      for (const node of nodes) {
        if (typeof node.pid !== 'string' || !node.pid || seen.has(node.pid)) throw new Error('Nested table tree requires unique record identities');
        seen.add(node.pid);
        const { [treeConfig.childrenField!]: children, ...values } = node;
        result.push({ ...values, [parentField]: parent });
        if (children != null) {
          if (!Array.isArray(children)) throw new Error('Nested table children must be an array');
          if (children.length) visit(children, node.pid, depth + 1);
        }
      }
    };
    visit(rows, null, 0);
    return result;
  }, [rows, treeConfig?.childrenField, treeConfig?.maxDepth, parentField]);

  // Group rows by parent
  const childrenMap = useMemo(() => {
    const map = new Map<string | null, Record<string, any>[]>();
    for (const row of normalizedRows) {
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
  }, [normalizedRows, parentField]);

  // Defaults apply to asynchronously arriving nodes; only physical user choices override them.
  const [expansionChoices, setExpansionChoices] = useState<Map<string, boolean>>(() => new Map());
  const expandedIds = useMemo(() => {
    const ids = new Set<string>();
    for (const pid of childrenMap.keys()) {
      if (pid && (expansionChoices.get(pid) ?? defaultExpanded)) {
        ids.add(pid);
      }
    }
    return ids;
  }, [childrenMap, expansionChoices, defaultExpanded]);

  // Build flat tree (depth-first)
  const flatTree = useMemo(() => {
    const result: TreeRow[] = [];

    function walk(parentId: string | null, depth: number) {
      const children = childrenMap.get(parentId) ?? [];
      for (const row of children) {
        const rowId = row.pid;
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
    setExpansionChoices((prev) => {
      const next = new Map(prev);
      next.set(id, !(prev.get(id) ?? defaultExpanded));
      return next;
    });
  }, [defaultExpanded]);

  const getChildren = useCallback(
    (parentId: string | null) => {
      return (childrenMap.get(parentId) ?? []) as TreeRow[];
    },
    [childrenMap],
  );

  return { flatTree, visibleRows, toggleExpand, expandedIds, getChildren };
}
