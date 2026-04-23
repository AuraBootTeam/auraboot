/**
 * BomTreeEditor Component
 *
 * Two-panel editor for BOM (Bill of Materials) hierarchical data.
 *
 * Left panel  — scrollable tree view using BomTreeNode recursively.
 * Right panel — property panel for the currently selected node.
 *
 * Data flow:
 * 1. Fetch BOM lines from dynamic API filtered by bomId.
 * 2. Build tree from flat list using parent_id field (if present).
 * 3. If no parent_id field, display as a flat list.
 */

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ErrorCodes } from '~/shared/services/http-client/types';
import { cn } from '~/utils/cn';
import { BomTreeToolbar } from './BomTreeToolbar';
import type { BomNode } from './BomTreeNode';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface BomTreeEditorProps {
  /** BOM record PID */
  bomId: string;
  /** BOM model code (default: pe_bom) */
  modelCode?: string;
  /** BOM line model code (default: pe_bom_line) */
  lineModelCode?: string;
  /** Foreign key field linking lines to the parent BOM (default: pe_bl_bom_id) */
  foreignKey?: string;
  /** When true, hides the Add Node button and disables editing */
  readOnly?: boolean;
  /** Custom CSS class applied to the root container */
  className?: string;
}

/** Raw shape of a BOM line record from the dynamic API */
interface RawBomLine {
  pid?: string;
  id?: string;
  parent_id?: string;
  [key: string]: unknown;
}

/** Internal mutable node used during tree construction */
interface MutableBomNode {
  id: string;
  materialCode: string;
  materialName: string;
  quantity: number;
  unit: string;
  _children: MutableBomNode[];
}

/** Pagination result shape returned by the dynamic list API */
interface DynamicListResult {
  records?: RawBomLine[];
  total?: number;
}

// ---------------------------------------------------------------------------
// Helper: build tree from flat list
// ---------------------------------------------------------------------------

/**
 * Converts a flat list of BOM line records into a nested BomNode tree.
 *
 * Strategy:
 * - If records contain a `parent_id` field, use parent-child linking.
 * - Otherwise, all records become root-level nodes (flat display).
 *
 * Field mapping heuristic (tries known field name patterns in order):
 *   materialCode : pe_bom_line_material_code | material_code | code
 *   materialName : pe_bom_line_material_name | material_name | name
 *   quantity     : pe_bom_line_qty           | quantity       | qty
 *   unit         : pe_bom_line_unit          | unit
 */
function buildBomTree(records: RawBomLine[]): BomNode[] {
  if (records.length === 0) return [];

  // Determine if parent-child relationships exist
  const hasParentId = records.some((r) => r.parent_id != null);

  // Map each record to a mutable internal node
  const nodeMap = new Map<string, MutableBomNode>();

  records.forEach((r) => {
    const id = String(r.pid ?? r.id ?? Math.random());

    const materialCode = String(r.pe_bom_line_material_code ?? r.material_code ?? r.code ?? '');
    const materialName = String(r.pe_bom_line_material_name ?? r.material_name ?? r.name ?? id);
    const quantity = Number(r.pe_bom_line_qty ?? r.quantity ?? r.qty ?? 0);
    const unit = String(r.pe_bom_line_unit ?? r.unit ?? '');

    nodeMap.set(id, {
      id,
      materialCode,
      materialName,
      quantity,
      unit,
      _children: [],
    });
  });

  const roots: MutableBomNode[] = [];

  if (hasParentId) {
    records.forEach((r) => {
      const id = String(r.pid ?? r.id ?? '');
      const parentId = r.parent_id != null ? String(r.parent_id) : null;
      const node = nodeMap.get(id);
      if (!node) return;

      if (parentId && nodeMap.has(parentId)) {
        nodeMap.get(parentId)!._children.push(node);
      } else {
        roots.push(node);
      }
    });
  } else {
    nodeMap.forEach((node) => roots.push(node));
  }

  // Convert mutable internal nodes to immutable BomNode
  const finalize = (node: MutableBomNode): BomNode => ({
    id: node.id,
    materialCode: node.materialCode,
    materialName: node.materialName,
    quantity: node.quantity,
    unit: node.unit,
    children:
      node._children.length > 0 ? node._children.map((child) => finalize(child)) : undefined,
  });

  return roots.map(finalize);
}

// ---------------------------------------------------------------------------
// Helper: collect all node IDs (for Expand All)
// ---------------------------------------------------------------------------

function collectAllIds(nodes: BomNode[]): Set<string> {
  const ids = new Set<string>();
  const walk = (list: BomNode[]) => {
    list.forEach((n) => {
      if (n.children && n.children.length > 0) {
        ids.add(n.id);
        walk(n.children);
      }
    });
  };
  walk(nodes);
  return ids;
}

// ---------------------------------------------------------------------------
// Helper: filter tree by keyword
// ---------------------------------------------------------------------------

function filterTree(nodes: BomNode[], keyword: string): BomNode[] {
  if (!keyword.trim()) return nodes;
  const kw = keyword.toLowerCase();

  const matchNode = (node: BomNode): BomNode | null => {
    const nameMatch = node.materialName.toLowerCase().includes(kw);
    const codeMatch = node.materialCode.toLowerCase().includes(kw);
    const filteredChildren = (node.children ?? []).map(matchNode).filter(Boolean) as BomNode[];

    if (nameMatch || codeMatch || filteredChildren.length > 0) {
      return { ...node, children: filteredChildren.length > 0 ? filteredChildren : node.children };
    }
    return null;
  };

  return nodes.map(matchNode).filter(Boolean) as BomNode[];
}

// ---------------------------------------------------------------------------
// Helper: count all visible nodes recursively
// ---------------------------------------------------------------------------

function countNodes(nodes: BomNode[]): number {
  return nodes.reduce((sum, n) => sum + 1 + countNodes(n.children ?? []), 0);
}

// ---------------------------------------------------------------------------
// Property Panel
// ---------------------------------------------------------------------------

interface PropertyPanelProps {
  node: BomNode | null;
  readOnly: boolean;
}

const PropertyPanel: React.FC<PropertyPanelProps> = ({ node, readOnly }) => {
  if (!node) {
    return (
      <div
        className="flex h-full flex-col items-center justify-center gap-2 text-sm text-gray-400"
        data-testid="bom-property-empty"
      >
        <svg
          className="h-12 w-12 text-gray-200"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M13 10V3L4 14h7v7l9-11h-7z"
          />
        </svg>
        <span>Select a node to view properties</span>
      </div>
    );
  }

  const hasChildren = Boolean(node.children && node.children.length > 0);

  return (
    <div className="space-y-4 p-4" data-testid="bom-property-panel">
      {/* Header */}
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <p className="mb-0.5 text-xs tracking-wide text-gray-400 uppercase">
            {hasChildren ? 'Assembly' : 'Component'}
          </p>
          <h3 className="truncate text-base font-semibold text-gray-900" title={node.materialName}>
            {node.materialName}
          </h3>
          {node.materialCode && (
            <span className="font-mono text-xs text-gray-500">{node.materialCode}</span>
          )}
        </div>
      </div>

      <hr className="border-gray-200" />

      {/* Properties grid */}
      <div className="space-y-3">
        <PropertyRow
          label="Material Code"
          value={node.materialCode || '—'}
          testId="bom-prop-code"
        />
        <PropertyRow label="Material Name" value={node.materialName} testId="bom-prop-name" />
        <PropertyRow
          label="Quantity"
          value={`${node.quantity} ${node.unit || ''}`.trim()}
          testId="bom-prop-qty"
        />
        <PropertyRow label="Unit" value={node.unit || '—'} testId="bom-prop-unit" />
        {hasChildren && (
          <PropertyRow
            label="Sub-components"
            value={String(node.children!.length)}
            testId="bom-prop-children"
          />
        )}
      </div>

      {/* Edit hint */}
      {!readOnly && (
        <p className="text-xs text-gray-400 italic">
          Use the BOM form to edit quantities and materials.
        </p>
      )}
    </div>
  );
};

const PropertyRow: React.FC<{ label: string; value: string; testId?: string }> = ({
  label,
  value,
  testId,
}) => (
  <div className="flex items-start justify-between gap-2">
    <span className="w-32 flex-shrink-0 text-xs text-gray-500">{label}</span>
    <span className="text-right text-sm break-all text-gray-800" data-testid={testId}>
      {value}
    </span>
  </div>
);

// ---------------------------------------------------------------------------
// Main Component
// ---------------------------------------------------------------------------

/**
 * BomTreeEditor - Two-panel BOM tree viewer / editor.
 */
export const BomTreeEditor: React.FC<BomTreeEditorProps> = ({
  bomId,
  modelCode: _modelCode = 'pe_bom',
  lineModelCode = 'pe_bom_line',
  foreignKey = 'pe_bl_bom_id',
  readOnly = false,
  className,
}) => {
  const [tree, setTree] = useState<BomNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedNode, setSelectedNode] = useState<BomNode | null>(null);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [searchKeyword, setSearchKeyword] = useState('');
  const abortRef = useRef<AbortController | null>(null);

  // Fetch BOM lines and build tree
  const fetchLines = useCallback(async () => {
    if (!bomId) return;

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(null);

    try {
      const filters = JSON.stringify([{ fieldName: foreignKey, operator: 'EQ', value: bomId }]);

      const result = await fetchResult<DynamicListResult>(`/api/dynamic/${lineModelCode}/list`, {
        params: {
          pageNum: 1,
          pageSize: 500,
          filters,
        },
      });

      if (controller.signal.aborted) return;

      if (result.code !== ErrorCodes.SUCCESS || !result.data) {
        setError(result.desc || 'Failed to load BOM lines');
        return;
      }

      const rawRecords: RawBomLine[] = result.data.records ?? [];

      const builtTree = buildBomTree(rawRecords);
      setTree(builtTree);

      // Auto-expand root nodes
      const rootIds = new Set(builtTree.map((n) => n.id));
      setExpandedIds(rootIds);
    } catch (err) {
      if (controller.signal.aborted) return;
      setError(err instanceof Error ? err.message : 'Failed to load BOM lines');
    } finally {
      if (!controller.signal.aborted) {
        setLoading(false);
      }
    }
  }, [bomId, lineModelCode, foreignKey]);

  useEffect(() => {
    fetchLines();
    return () => {
      abortRef.current?.abort();
    };
  }, [fetchLines]);

  // Expand All
  const handleExpandAll = useCallback(() => {
    setExpandedIds(collectAllIds(tree));
  }, [tree]);

  // Collapse All
  const handleCollapseAll = useCallback(() => {
    setExpandedIds(new Set());
  }, []);

  // Toggle single node
  const handleToggle = useCallback((nodeId: string) => {
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

  // Select node
  const handleSelect = useCallback((node: BomNode) => {
    setSelectedNode((prev) => (prev?.id === node.id ? null : node));
  }, []);

  // Filtered tree (for search)
  const filteredTree = useMemo(() => filterTree(tree, searchKeyword), [tree, searchKeyword]);

  // When searching, expand all matching nodes
  const effectiveExpandedIds = useMemo(() => {
    if (!searchKeyword.trim()) return expandedIds;
    return collectAllIds(filteredTree);
  }, [searchKeyword, filteredTree, expandedIds]);

  const visibleCount = useMemo(() => countNodes(filteredTree), [filteredTree]);

  // Error state
  if (error) {
    return (
      <div
        className={cn(
          'flex flex-col items-center justify-center gap-3 rounded-lg border border-red-200 bg-white p-8',
          className,
        )}
        style={{ minHeight: 320 }}
        data-testid="bom-tree-error"
      >
        <svg
          className="h-10 w-10 text-red-400"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
          />
        </svg>
        <p className="text-sm text-red-600">{error}</p>
        <button
          type="button"
          onClick={fetchLines}
          className="rounded-md bg-red-500 px-4 py-2 text-sm text-white transition-colors hover:bg-red-600"
          data-testid="bom-tree-retry"
        >
          Retry
        </button>
      </div>
    );
  }

  return (
    <div
      className={cn(
        'flex flex-col overflow-hidden rounded-lg border border-gray-200 bg-white',
        className,
      )}
      style={{ minHeight: 400 }}
      data-testid="bom-tree-editor"
    >
      {/* Toolbar */}
      <BomTreeToolbar
        searchKeyword={searchKeyword}
        onSearchChange={setSearchKeyword}
        onExpandAll={handleExpandAll}
        onCollapseAll={handleCollapseAll}
        readOnly={readOnly}
        loading={loading}
        visibleCount={visibleCount}
      />

      {/* Main content: tree + property panel */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left: Tree panel */}
        <div
          className="flex-1 overflow-y-auto border-r border-gray-200"
          role="tree"
          aria-label="BOM tree"
          data-testid="bom-tree-panel"
        >
          {loading && tree.length === 0 ? (
            <div className="flex items-center justify-center gap-2 py-16 text-sm text-gray-400">
              <span className="h-5 w-5 animate-spin rounded-full border-2 border-blue-200 border-t-blue-600" />
              Loading BOM lines…
            </div>
          ) : filteredTree.length > 0 ? (
            filteredTree.map((node) => (
              <BomTreeNode
                key={node.id}
                node={node}
                depth={0}
                selectedId={selectedNode?.id}
                onSelect={handleSelect}
                onToggle={handleToggle}
                expandedIds={effectiveExpandedIds}
              />
            ))
          ) : !loading ? (
            <div
              className="flex flex-col items-center justify-center gap-2 py-16 text-sm text-gray-400"
              data-testid="bom-tree-empty"
            >
              <svg
                className="h-10 w-10 text-gray-200"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={1.5}
                  d="M9 13h6m-3-3v6m5 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                />
              </svg>
              {searchKeyword
                ? `No nodes matching "${searchKeyword}"`
                : 'No BOM lines found. Add components to get started.'}
            </div>
          ) : null}
        </div>

        {/* Right: Property panel */}
        <div className="w-64 flex-shrink-0 overflow-y-auto" data-testid="bom-property-sidebar">
          <PropertyPanel node={selectedNode} readOnly={readOnly} />
        </div>
      </div>
    </div>
  );
};

export default BomTreeEditor;
