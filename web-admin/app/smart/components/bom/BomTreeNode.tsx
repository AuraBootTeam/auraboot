/**
 * BomTreeNode Component
 *
 * Recursive tree node for displaying a single BOM entry with its children.
 * Supports expand/collapse toggle, node selection, and depth-based indentation.
 */

import React from 'react';
import { cn } from '~/utils/cn';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface BomNode {
  id: string;
  materialCode: string;
  materialName: string;
  quantity: number;
  unit: string;
  children?: BomNode[];
}

export interface BomTreeNodeProps {
  node: BomNode;
  depth: number;
  selectedId?: string;
  onSelect: (node: BomNode) => void;
  onToggle: (nodeId: string) => void;
  expandedIds: Set<string>;
}

// ---------------------------------------------------------------------------
// Sub-icons
// ---------------------------------------------------------------------------

/** Chevron icon rotates 90° when expanded */
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
    aria-hidden="true"
  >
    <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
  </svg>
);

/** Folder icon for assemblies (nodes with children) */
const FolderIcon: React.FC = () => (
  <svg
    className="h-4 w-4 flex-shrink-0 text-amber-500"
    fill="currentColor"
    viewBox="0 0 24 24"
    aria-hidden="true"
  >
    <path d="M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8l-2-2z" />
  </svg>
);

/** Component icon for raw materials (leaf nodes) */
const ComponentIcon: React.FC = () => (
  <svg
    className="h-4 w-4 flex-shrink-0 text-blue-400"
    fill="none"
    viewBox="0 0 24 24"
    stroke="currentColor"
    strokeWidth={2}
    aria-hidden="true"
  >
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
    <circle cx="8.5" cy="8.5" r="1.5" />
    <polyline points="21 15 16 10 5 21" />
  </svg>
);

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

/**
 * BomTreeNode - Renders one BOM node and recursively its children when expanded.
 */
export const BomTreeNode: React.FC<BomTreeNodeProps> = ({
  node,
  depth,
  selectedId,
  onSelect,
  onToggle,
  expandedIds,
}) => {
  const hasChildren = Boolean(node.children && node.children.length > 0);
  const isExpanded = expandedIds.has(node.id);
  const isSelected = selectedId === node.id;

  const handleRowClick = () => {
    onSelect(node);
  };

  const handleToggleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (hasChildren) {
      onToggle(node.id);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      onSelect(node);
    }
    if (e.key === 'ArrowRight' && hasChildren && !isExpanded) {
      e.preventDefault();
      onToggle(node.id);
    }
    if (e.key === 'ArrowLeft' && isExpanded) {
      e.preventDefault();
      onToggle(node.id);
    }
  };

  return (
    <div data-testid={`bom-tree-node-${node.id}`}>
      {/* Node row */}
      <div
        role="treeitem"
        aria-expanded={hasChildren ? isExpanded : undefined}
        aria-selected={isSelected}
        tabIndex={0}
        data-testid={`bom-tree-row-${node.id}`}
        onClick={handleRowClick}
        onKeyDown={handleKeyDown}
        className={cn(
          'flex cursor-pointer items-center gap-2 py-2 pr-4 transition-colors duration-100',
          'border-b border-gray-100 outline-none hover:bg-blue-50',
          isSelected && 'border-l-2 border-l-blue-500 bg-blue-50',
          !isSelected && 'border-l-2 border-l-transparent',
        )}
        style={{ paddingLeft: depth * 20 + 12 }}
      >
        {/* Expand/collapse toggle */}
        <button
          type="button"
          data-testid={hasChildren ? `bom-expand-${node.id}` : undefined}
          onClick={handleToggleClick}
          className={cn(
            'flex h-5 w-5 flex-shrink-0 items-center justify-center rounded',
            hasChildren
              ? 'cursor-pointer hover:bg-gray-200'
              : 'pointer-events-none cursor-default opacity-0',
          )}
          tabIndex={-1}
          aria-label={isExpanded ? 'Collapse' : 'Expand'}
        >
          {hasChildren && <ChevronIcon expanded={isExpanded} />}
        </button>

        {/* Node type icon */}
        {hasChildren ? <FolderIcon /> : <ComponentIcon />}

        {/* Material name */}
        <span
          className={cn(
            'flex-1 truncate text-sm font-medium',
            isSelected ? 'text-blue-700' : 'text-gray-800',
          )}
          data-testid={`bom-node-name-${node.id}`}
        >
          {node.materialName}
        </span>

        {/* Code badge */}
        {node.materialCode && (
          <span className="flex-shrink-0 rounded bg-gray-100 px-1.5 py-0.5 font-mono text-xs text-gray-400">
            {node.materialCode}
          </span>
        )}

        {/* Quantity + unit */}
        <span
          className="flex-shrink-0 text-sm text-gray-600 tabular-nums"
          data-testid={`bom-node-qty-${node.id}`}
        >
          {node.quantity} <span className="text-xs text-gray-400">{node.unit}</span>
        </span>

        {/* Children count badge */}
        {hasChildren && (
          <span className="ml-1 flex-shrink-0 text-xs text-gray-400">
            ({node.children!.length})
          </span>
        )}
      </div>

      {/* Render children when expanded */}
      {hasChildren && isExpanded && (
        <div role="group" data-testid={`bom-children-${node.id}`}>
          {node.children!.map((child) => (
            <BomTreeNode
              key={child.id}
              node={child}
              depth={depth + 1}
              selectedId={selectedId}
              onSelect={onSelect}
              onToggle={onToggle}
              expandedIds={expandedIds}
            />
          ))}
        </div>
      )}
    </div>
  );
};

export default BomTreeNode;
