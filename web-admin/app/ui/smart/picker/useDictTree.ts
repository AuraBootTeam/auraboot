/**
 * useDictTree — Shared hook for loading tree-structured dict data.
 *
 * Fetches dict items from `/api/meta/dict/by-code/{dictCode}/data`
 * and builds a tree from flat parentValue records.
 */

import { useState, useEffect } from 'react';
import { fetchResult } from '~/shared/services/http-client';

export interface DictTreeNode {
  value: string;
  label: string;
  code?: string;
  children?: DictTreeNode[];
  isLeaf?: boolean;
}

/**
 * Load and build tree options from a dict code.
 *
 * @param dictCode - The dict code to fetch tree data for
 * @param skip - Skip fetching (e.g., when external options are provided)
 * @returns Tree-structured options array (empty while loading or when skipped)
 */
export function useDictTree(
  dictCode: string | undefined | null,
  skip = false,
): DictTreeNode[] {
  const [options, setOptions] = useState<DictTreeNode[]>([]);

  useEffect(() => {
    if (!dictCode || skip) return;

    fetchResult<any>(`/api/meta/dict/by-code/${dictCode}/data`, { method: 'get' })
      .then((result) => {
        if (!result?.data?.items) return;
        const items = result.data.items as Array<{
          value: string;
          label: string;
          parentValue?: string | null;
        }>;

        // Build tree from flat items with parentValue
        const nodeMap = new Map<string, DictTreeNode>();
        const roots: DictTreeNode[] = [];

        for (const item of items) {
          nodeMap.set(item.value, {
            value: item.value,
            label: item.label,
            children: [],
          });
        }

        for (const item of items) {
          const node = nodeMap.get(item.value)!;
          if (item.parentValue && nodeMap.has(item.parentValue)) {
            nodeMap.get(item.parentValue)!.children!.push(node);
          } else {
            roots.push(node);
          }
        }

        // Mark leaf nodes
        const markLeaves = (nodes: DictTreeNode[]) => {
          for (const n of nodes) {
            if (!n.children || n.children.length === 0) {
              n.isLeaf = true;
              delete n.children;
            } else {
              markLeaves(n.children);
            }
          }
        };
        markLeaves(roots);
        setOptions(roots);
      })
      .catch(() => {});
  }, [dictCode, skip]);

  return options;
}
