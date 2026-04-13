/**
 * TreeView Component
 *
 * Renders a hierarchical tree view from flat data by building parent-child
 * relationships using a configurable parent ID field.
 * Follows the same adapter pattern as GanttView and KanbanView.
 */

import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import type { ViewConfig } from '~/framework/smart/types/savedView';
import type { FilterConfig } from '~/framework/smart/types/chart';
import { dynamicService } from '~/services/dynamicService';
import { cn } from '~/utils/cn';
import { ViewEmptyState } from './shared';

/**
 * Props for TreeView component
 */
export interface TreeViewProps {
  /** View configuration containing tree settings */
  viewConfig?: ViewConfig;
  /** Model code for data fetching */
  modelCode: string;
  /** Callback when a tree node (record) is clicked */
  onNodeClick?: (recordId: string) => void;
  /** External filter conditions */
  linkageFilters?: FilterConfig[];
  /** Custom CSS class */
  className?: string;
}

/**
 * Internal tree node representation
 */
interface TreeNode {
  id: string;
  record: Record<string, any>;
  children: TreeNode[];
  depth: number;
}

/**
 * Build a tree structure from a flat list of records using a parent ID field
 */
function buildTree(records: Record<string, any>[], parentField: string): TreeNode[] {
  const nodeMap = new Map<string, TreeNode>();
  const roots: TreeNode[] = [];

  // Create all nodes first — use pid (ULID) as key since REFERENCE fields store pid
  records.forEach((r) => {
    const id = String(r.pid || r.id);
    nodeMap.set(id, { id, record: r, children: [], depth: 0 });
  });

  // Link parent-child relationships
  records.forEach((r) => {
    const id = String(r.pid || r.id);
    const parentId = r[parentField];
    const node = nodeMap.get(id)!;
    if (parentId && nodeMap.has(String(parentId))) {
      nodeMap.get(String(parentId))!.children.push(node);
    } else {
      roots.push(node);
    }
  });

  // Assign depths via BFS
  const assignDepth = (nodes: TreeNode[], depth: number) => {
    nodes.forEach((n) => {
      n.depth = depth;
      assignDepth(n.children, depth + 1);
    });
  };
  assignDepth(roots, 0);

  return roots;
}

/**
 * Chevron icon for expand/collapse toggle
 */
const ChevronIcon: React.FC<{ expanded: boolean }> = ({ expanded }) => (
  <svg
    className={cn(
      'h-4 w-4 text-gray-400 transition-transform duration-150',
      expanded && 'rotate-90',
    )}
    fill="none"
    viewBox="0 0 24 24"
    stroke="currentColor"
    strokeWidth={2}
  >
    <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
  </svg>
);

/**
 * TreeView - Renders hierarchical data as an expandable tree
 */
export const TreeView: React.FC<TreeViewProps> = ({
  viewConfig,
  modelCode,
  onNodeClick,
  linkageFilters,
  className,
}) => {
  const [records, setRecords] = useState<Record<string, any>[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const parentField = viewConfig?.treeParentField;
  const titleField = viewConfig?.treeTitleField || 'name';
  const displayFields = viewConfig?.treeDisplayFields || [];

  // Fetch all records
  const fetchData = useCallback(async () => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(null);

    try {
      const result = await dynamicService.findByPage(modelCode, {
        page: 0,
        size: 1000,
      });

      if (controller.signal.aborted) return;
      setRecords(result.records);
    } catch (err) {
      if (controller.signal.aborted) return;
      setError(err instanceof Error ? err.message : 'Failed to fetch tree data');
    } finally {
      if (!controller.signal.aborted) {
        setLoading(false);
      }
    }
  }, [modelCode, linkageFilters]);

  useEffect(() => {
    fetchData();
    return () => {
      abortRef.current?.abort();
    };
  }, [fetchData]);

  // Build tree from flat records
  const tree = useMemo(
    () => buildTree(records, parentField || 'parent_id'),
    [records, parentField],
  );

  // Toggle expand/collapse for a node
  const toggleExpand = useCallback((nodeId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(nodeId)) {
        next.delete(nodeId);
      } else {
        next.add(nodeId);
      }
      return next;
    });
  }, []);

  // Handle node row click
  const handleNodeClick = useCallback(
    (nodeId: string) => {
      setSelectedId(nodeId);
      onNodeClick?.(nodeId);
    },
    [onNodeClick],
  );

  // Expand all nodes
  const expandAll = useCallback(() => {
    const allIds = new Set<string>();
    const collectIds = (nodes: TreeNode[]) => {
      nodes.forEach((n) => {
        if (n.children.length > 0) {
          allIds.add(n.id);
        }
        collectIds(n.children);
      });
    };
    collectIds(tree);
    setExpandedIds(allIds);
  }, [tree]);

  // Collapse all nodes
  const collapseAll = useCallback(() => {
    setExpandedIds(new Set());
  }, []);

  // Count total nodes
  const totalNodes = records.length;

  // Render a single tree node row
  const renderNode = (node: TreeNode): React.ReactNode => {
    const isExpanded = expandedIds.has(node.id);
    const hasChildren = node.children.length > 0;
    const isSelected = selectedId === node.id;
    const titleValue = String(node.record[titleField] ?? node.record['name'] ?? 'Untitled');

    return (
      <div key={node.id} data-testid={`tree-node-${node.id}`}>
        {/* Node row */}
        <div
          role="button"
          tabIndex={0}
          data-testid={`tree-row-${node.id}`}
          onClick={() => handleNodeClick(node.id)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              handleNodeClick(node.id);
            }
          }}
          className={cn(
            'flex cursor-pointer items-center gap-2 px-3 py-2 transition-colors duration-100',
            'border-b border-gray-100 hover:bg-blue-50',
            isSelected && 'border-l-2 border-l-blue-500 bg-blue-50',
          )}
          style={{ paddingLeft: node.depth * 24 + 12 }}
        >
          {/* Expand/collapse toggle */}
          <button
            type="button"
            data-testid={hasChildren ? `tree-expand-${node.id}` : undefined}
            onClick={(e) => (hasChildren ? toggleExpand(node.id, e) : undefined)}
            className={cn(
              'flex h-5 w-5 flex-shrink-0 items-center justify-center rounded',
              hasChildren ? 'cursor-pointer hover:bg-gray-200' : 'cursor-default',
            )}
            tabIndex={-1}
          >
            {hasChildren ? (
              <ChevronIcon expanded={isExpanded} />
            ) : (
              <span className="flex h-4 w-4 items-center justify-center text-gray-300">
                <svg className="h-2 w-2" fill="currentColor" viewBox="0 0 8 8">
                  <circle cx="4" cy="4" r="2" />
                </svg>
              </span>
            )}
          </button>

          {/* Title */}
          <span
            className={cn(
              'truncate text-sm font-medium',
              isSelected ? 'text-blue-700' : 'text-gray-800',
            )}
          >
            {titleValue}
          </span>

          {/* Display fields (secondary info) */}
          {displayFields.length > 0 && (
            <div className="ml-auto flex flex-shrink-0 items-center gap-3">
              {displayFields.map((field) => {
                const val = node.record[field];
                if (val === null || val === undefined || val === '') return null;
                return (
                  <span
                    key={field}
                    className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-500"
                  >
                    {String(val)}
                  </span>
                );
              })}
            </div>
          )}

          {/* Children count badge */}
          {hasChildren && (
            <span className="ml-1 flex-shrink-0 text-xs text-gray-400">
              ({node.children.length})
            </span>
          )}
        </div>

        {/* Render children if expanded */}
        {hasChildren && isExpanded && <div>{node.children.map(renderNode)}</div>}
      </div>
    );
  };

  // No parent field configured
  if (!parentField) {
    return (
      <ViewEmptyState
        variant="not-configured"
        title="Tree view not configured"
        description="Please configure the Parent ID Field to display the tree view."
        className={className}
      />
    );
  }

  // Error state
  if (error) {
    return (
      <ViewEmptyState
        variant="error"
        title="Failed to load tree data"
        error={error}
        onRetry={fetchData}
        className={className}
      />
    );
  }

  return (
    <div
      className={cn('rounded-lg border border-gray-200 bg-white', className)}
      data-testid="tree-view-container"
    >
      {/* Toolbar */}
      <div
        className="flex items-center justify-between border-b border-gray-200 px-4 py-2"
        data-testid="tree-toolbar"
      >
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-gray-700" data-testid="tree-node-count">
            {totalNodes} nodes
          </span>
          {loading && (
            <span className="h-4 w-4 animate-spin rounded-full border-2 border-blue-200 border-t-blue-600" />
          )}
        </div>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={expandAll}
            data-testid="tree-expand-all"
            className="rounded-md px-3 py-1 text-xs text-gray-500 transition-colors hover:bg-gray-100"
          >
            Expand All
          </button>
          <button
            type="button"
            onClick={collapseAll}
            data-testid="tree-collapse-all"
            className="rounded-md px-3 py-1 text-xs text-gray-500 transition-colors hover:bg-gray-100"
          >
            Collapse All
          </button>
        </div>
      </div>

      {/* Tree content */}
      <div className="overflow-auto" style={{ maxHeight: 600 }}>
        {tree.length > 0 ? (
          tree.map(renderNode)
        ) : !loading ? (
          <ViewEmptyState
            variant="no-data"
            title="No records found"
            description="Records will appear here when data is available."
          />
        ) : null}
      </div>
    </div>
  );
};

export default TreeView;
