/**
 * BomTreeToolbar Component
 *
 * Toolbar for the BOM tree editor.
 * Provides: expand-all / collapse-all buttons, keyword search input, and optional add-node button.
 */

import React from 'react';
import { cn } from '~/utils/cn';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface BomTreeToolbarProps {
  /** Current search keyword */
  searchKeyword: string;
  /** Callback when the search keyword changes */
  onSearchChange: (keyword: string) => void;
  /** Expand all nodes */
  onExpandAll: () => void;
  /** Collapse all nodes */
  onCollapseAll: () => void;
  /** Whether the tree is in read-only mode (hides Add Node button) */
  readOnly?: boolean;
  /** Optional callback for the Add Node button */
  onAddNode?: () => void;
  /** Loading state — disables controls */
  loading?: boolean;
  /** Total number of visible nodes (after search filter) */
  visibleCount?: number;
  /** Custom CSS class */
  className?: string;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

/**
 * BomTreeToolbar - Controls above the BOM tree panel.
 */
export const BomTreeToolbar: React.FC<BomTreeToolbarProps> = ({
  searchKeyword,
  onSearchChange,
  onExpandAll,
  onCollapseAll,
  readOnly = false,
  onAddNode,
  loading = false,
  visibleCount,
  className,
}) => {
  return (
    <div
      className={cn(
        'flex items-center gap-2 border-b border-gray-200 bg-gray-50 px-4 py-2',
        className,
      )}
      data-testid="bom-tree-toolbar"
    >
      {/* Search input */}
      <div className="relative max-w-xs flex-1">
        <svg
          className="absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2 text-gray-400"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <circle cx="11" cy="11" r="8" />
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M21 21l-4.35-4.35"
          />
        </svg>
        <input
          type="text"
          placeholder="Search by name or code..."
          value={searchKeyword}
          onChange={(e) => onSearchChange(e.target.value)}
          disabled={loading}
          data-testid="bom-tree-search"
          className={cn(
            'w-full rounded-md border border-gray-300 py-1.5 pr-3 pl-8 text-sm',
            'bg-white placeholder-gray-400',
            'focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none',
            'disabled:cursor-not-allowed disabled:opacity-50',
          )}
        />
        {searchKeyword && (
          <button
            type="button"
            onClick={() => onSearchChange('')}
            className="absolute top-1/2 right-2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
            aria-label="Clear search"
            data-testid="bom-tree-search-clear"
          >
            <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        )}
      </div>

      {/* Visible count */}
      {visibleCount !== undefined && (
        <span className="flex-shrink-0 text-xs text-gray-500" data-testid="bom-tree-count">
          {visibleCount} items
        </span>
      )}

      {/* Spacer */}
      <div className="flex-1" />

      {/* Loading spinner */}
      {loading && (
        <span
          className="h-4 w-4 flex-shrink-0 animate-spin rounded-full border-2 border-blue-200 border-t-blue-600"
          aria-label="Loading"
          data-testid="bom-tree-loading"
        />
      )}

      {/* Expand All */}
      <button
        type="button"
        onClick={onExpandAll}
        disabled={loading}
        data-testid="bom-tree-expand-all"
        className={cn(
          'rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs text-gray-600',
          'transition-colors hover:bg-gray-100',
          'disabled:cursor-not-allowed disabled:opacity-50',
          'flex items-center gap-1',
        )}
      >
        <svg
          className="h-3.5 w-3.5"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
        Expand All
      </button>

      {/* Collapse All */}
      <button
        type="button"
        onClick={onCollapseAll}
        disabled={loading}
        data-testid="bom-tree-collapse-all"
        className={cn(
          'rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs text-gray-600',
          'transition-colors hover:bg-gray-100',
          'disabled:cursor-not-allowed disabled:opacity-50',
          'flex items-center gap-1',
        )}
      >
        <svg
          className="h-3.5 w-3.5"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 15l7-7 7 7" />
        </svg>
        Collapse All
      </button>

      {/* Add Node button (edit mode only) */}
      {!readOnly && onAddNode && (
        <>
          <div className="h-5 w-px flex-shrink-0 bg-gray-300" />
          <button
            type="button"
            onClick={onAddNode}
            disabled={loading}
            data-testid="bom-tree-add-node"
            className={cn(
              'rounded-md bg-blue-600 px-3 py-1.5 text-xs text-white',
              'transition-colors hover:bg-blue-700',
              'disabled:cursor-not-allowed disabled:opacity-50',
              'flex items-center gap-1',
            )}
          >
            <svg
              className="h-3.5 w-3.5"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
              aria-hidden="true"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 4v16m8-8H4"
              />
            </svg>
            Add Node
          </button>
        </>
      )}
    </div>
  );
};

export default BomTreeToolbar;
