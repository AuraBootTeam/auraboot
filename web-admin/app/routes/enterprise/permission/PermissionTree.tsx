import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import {
  ChevronRightIcon,
  ChevronDownIcon,
  MagnifyingGlassIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { LoadingSpinner } from '~/components/LoadingSpinner';
import type { PermissionTreeNode } from './types';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface PermissionTreeProps {
  nodes: PermissionTreeNode[];
  selectedIds: (string | number)[];
  onSelectionChange: (ids: (string | number)[]) => void;
  loading?: boolean;
}

// ---------------------------------------------------------------------------
// Utility helpers (pure functions)
// ---------------------------------------------------------------------------

/** Collect all leaf IDs under a node. */
const getLeafIds = (node: PermissionTreeNode): (string | number)[] => {
  if (!node.children || node.children.length === 0) return [node.id];
  return node.children.flatMap(getLeafIds);
};

/** Return true when every leaf under `node` is present in `selectedIds`. */
const areAllChildrenSelected = (
  node: PermissionTreeNode,
  selectedIds: (string | number)[],
): boolean => {
  const leaves = getLeafIds(node);
  return leaves.length > 0 && leaves.every((id) => selectedIds.includes(id));
};

/** Return true when at least one (but not all) leaf under `node` is selected. */
const areSomeChildrenSelected = (
  node: PermissionTreeNode,
  selectedIds: (string | number)[],
): boolean => {
  const leaves = getLeafIds(node);
  const count = leaves.filter((id) => selectedIds.includes(id)).length;
  return count > 0 && count < leaves.length;
};

/** Collect every node code for expand/collapse-all. */
const collectAllCodes = (nodes: PermissionTreeNode[]): Set<string> => {
  const codes = new Set<string>();
  const walk = (list: PermissionTreeNode[]) => {
    for (const n of list) {
      if (n.children && n.children.length > 0) {
        codes.add(n.code);
      }
      if (n.children) walk(n.children);
    }
  };
  walk(nodes);
  return codes;
};

/**
 * Filter tree by search text. A node is visible if its name or code matches,
 * OR if any descendant matches (parent kept to preserve structure).
 */
const filterTree = (nodes: PermissionTreeNode[], search: string): PermissionTreeNode[] => {
  if (!search) return nodes;
  const lower = search.toLowerCase();

  const filter = (list: PermissionTreeNode[]): PermissionTreeNode[] => {
    const result: PermissionTreeNode[] = [];
    for (const node of list) {
      const selfMatch =
        node.name.toLowerCase().includes(lower) || node.code.toLowerCase().includes(lower);
      const filteredChildren = node.children ? filter(node.children) : [];

      if (selfMatch || filteredChildren.length > 0) {
        result.push({
          ...node,
          children: selfMatch ? node.children : filteredChildren,
        });
      }
    }
    return result;
  };

  return filter(nodes);
};

// ---------------------------------------------------------------------------
// Type badge colours
// ---------------------------------------------------------------------------

const TYPE_BADGE_STYLES: Record<string, string> = {
  MENU: 'bg-blue-100 text-blue-700',
  BUTTON: 'bg-purple-100 text-purple-700',
  API: 'bg-orange-100 text-orange-700',
};

const TypeBadge = ({ type }: { type: string }) => {
  const style = TYPE_BADGE_STYLES[type] || 'bg-gray-100 text-gray-600';
  return (
    <span
      className={`ml-2 inline-block rounded px-1.5 py-0.5 text-[10px] leading-none font-medium ${style}`}
    >
      {type}
    </span>
  );
};

// ---------------------------------------------------------------------------
// Indeterminate checkbox (needs imperative ref)
// ---------------------------------------------------------------------------

const IndeterminateCheckbox = ({
  checked,
  indeterminate,
  onChange,
}: {
  checked: boolean;
  indeterminate: boolean;
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
}) => {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = indeterminate;
  }, [indeterminate]);
  return (
    <input
      ref={ref}
      type="checkbox"
      className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
      checked={checked}
      onChange={onChange}
    />
  );
};

// ---------------------------------------------------------------------------
// Single tree node (recursive)
// ---------------------------------------------------------------------------

interface TreeNodeRowProps {
  node: PermissionTreeNode;
  level: number;
  expanded: Set<string>;
  toggleExpand: (code: string) => void;
  selectedIds: (string | number)[];
  onToggleNode: (node: PermissionTreeNode, checked: boolean) => void;
}

const TreeNodeRow = ({
  node,
  level,
  expanded,
  toggleExpand,
  selectedIds,
  onToggleNode,
}: TreeNodeRowProps) => {
  const hasChildren = node.children && node.children.length > 0;
  const isExpanded = expanded.has(node.code);
  const isChecked = hasChildren
    ? areAllChildrenSelected(node, selectedIds)
    : selectedIds.includes(node.id);
  const isIndeterminate = !!(
    hasChildren &&
    !isChecked &&
    areSomeChildrenSelected(node, selectedIds)
  );
  const isRoot = level === 0;

  return (
    <div>
      <div
        className={`flex items-center rounded px-2 py-1 ${isRoot ? 'mb-1 border border-gray-200 bg-gray-50' : ''}`}
        style={{ marginLeft: level * 24 }}
      >
        {/* Expand / collapse toggle */}
        {hasChildren ? (
          <button
            data-testid={`permission-tree-toggle-${node.code}`}
            onClick={() => toggleExpand(node.code)}
            className="mr-1 flex h-5 w-5 shrink-0 items-center justify-center rounded hover:bg-gray-200"
          >
            {isExpanded ? (
              <ChevronDownIcon className="h-4 w-4 text-gray-500" />
            ) : (
              <ChevronRightIcon className="h-4 w-4 text-gray-500" />
            )}
          </button>
        ) : (
          <span className="mr-1 inline-block h-5 w-5 shrink-0" />
        )}

        {/* Checkbox + label */}
        <label
          data-testid={`permission-tree-node-${node.code}`}
          className="flex cursor-pointer items-center gap-1.5 select-none"
        >
          <IndeterminateCheckbox
            checked={isChecked}
            indeterminate={isIndeterminate}
            onChange={(e) => onToggleNode(node, e.target.checked)}
          />
          <span className="text-sm text-gray-800">{node.name}</span>
          <span className="text-xs text-gray-400">({node.code})</span>
          <TypeBadge type={node.type} />
        </label>
      </div>

      {/* Children */}
      {hasChildren && isExpanded && (
        <div>
          {node.children!.map((child) => (
            <TreeNodeRow
              key={child.id}
              node={child}
              level={level + 1}
              expanded={expanded}
              toggleExpand={toggleExpand}
              selectedIds={selectedIds}
              onToggleNode={onToggleNode}
            />
          ))}
        </div>
      )}
    </div>
  );
};

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

export function PermissionTree({
  nodes,
  selectedIds,
  onSelectionChange,
  loading = false,
}: PermissionTreeProps) {
  const { t } = useI18n();
  const [search, setSearch] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  // Auto-expand root nodes when tree data changes
  useEffect(() => {
    if (nodes.length > 0) {
      setExpanded((prev) => {
        const next = new Set(prev);
        nodes.forEach((n) => next.add(n.code));
        return next;
      });
    }
  }, [nodes]);

  const filteredNodes = useMemo(() => filterTree(nodes, search), [nodes, search]);

  const toggleExpand = useCallback((code: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(code)) {
        next.delete(code);
      } else {
        next.add(code);
      }
      return next;
    });
  }, []);

  // Expand all / collapse all
  const expandAll = useCallback(() => {
    setExpanded(collectAllCodes(nodes));
  }, [nodes]);

  const collapseAll = useCallback(() => {
    setExpanded(new Set());
  }, []);

  // Select all (only leaf nodes — parent state is derived)
  const selectAll = useCallback(() => {
    const leafIds: (string | number)[] = [];
    const collectLeaves = (list: PermissionTreeNode[]) => {
      for (const n of list) {
        if (n.children && n.children.length > 0) {
          collectLeaves(n.children);
        } else {
          leafIds.push(n.id);
        }
      }
    };
    collectLeaves(nodes);
    onSelectionChange(leafIds);
  }, [nodes, onSelectionChange]);

  // Clear all
  const clearAll = useCallback(() => {
    onSelectionChange([]);
  }, [onSelectionChange]);

  // Toggle a single node with cascade — only leaf IDs are stored in selectedIds.
  // Parent/GROUP node checked state is derived from children.
  const onToggleNode = useCallback(
    (node: PermissionTreeNode, checked: boolean) => {
      const next = new Set(selectedIds);
      const leafIds = getLeafIds(node);

      if (checked) {
        leafIds.forEach((id) => next.add(id));
      } else {
        leafIds.forEach((id) => next.delete(id));
      }

      onSelectionChange([...next]);
    },
    [selectedIds, onSelectionChange],
  );

  if (loading) {
    return (
      <div data-testid="permission-tree" className="flex items-center justify-center py-12">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div data-testid="permission-tree" className="flex flex-col gap-2">
      {/* Toolbar: search + action buttons */}
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <MagnifyingGlassIcon className="pointer-events-none absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            data-testid="permission-tree-search"
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t('admin.permission.assign.search')}
            className="w-full rounded-md border border-gray-300 py-1.5 pr-3 pl-8 text-sm placeholder:text-gray-400 focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
          />
        </div>

        <button
          data-testid="permission-tree-select-all"
          onClick={selectAll}
          className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50"
        >
          {t('admin.permission.assign.selectAll')}
        </button>
        <button
          data-testid="permission-tree-clear-all"
          onClick={clearAll}
          className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50"
        >
          {t('admin.permission.assign.clearAll')}
        </button>
        <button
          onClick={expandAll}
          className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50"
        >
          {t('common.expandAll') || 'Expand All'}
        </button>
        <button
          onClick={collapseAll}
          className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50"
        >
          {t('common.collapseAll') || 'Collapse All'}
        </button>
      </div>

      {/* Tree */}
      <div className="max-h-[480px] overflow-y-auto rounded-md border border-gray-200 p-2">
        {filteredNodes.length === 0 ? (
          <p className="py-8 text-center text-sm text-gray-400">
            {search ? t('common.noSearchResults') || 'No results' : t('common.noData') || 'No data'}
          </p>
        ) : (
          filteredNodes.map((node) => (
            <TreeNodeRow
              key={node.id}
              node={node}
              level={0}
              expanded={expanded}
              toggleExpand={toggleExpand}
              selectedIds={selectedIds}
              onToggleNode={onToggleNode}
            />
          ))
        )}
      </div>
    </div>
  );
}

export default PermissionTree;
