/**
 * ListTable — Extracted table rendering from ListPageContent.
 *
 * Wraps the <table> with DndContext + SortableContext from @dnd-kit for
 * column reordering via drag-and-drop.
 *
 * Renders:
 * - thead with DraggableColumnHeader for each data column
 * - tbody with loading state, empty state, grouped rows, and data rows
 * - Inline editing support, conditional format styles, row selection
 */
import React, { useMemo, useCallback, useRef, useState, useEffect } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import {
  DndContext,
  closestCenter,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { SortableContext, horizontalListSortingStrategy, arrayMove } from '@dnd-kit/sortable';
import type { ColumnConfig, ButtonConfig, TreeConfig } from '~/framework/meta/schemas/types';
import {
  ROW_HEIGHT_CONFIG,
  DEFAULT_ROW_HEIGHT,
  type SortConfig,
  type RowHeight,
} from '~/framework/smart/types/savedView';
import { DraggableColumnHeader } from './DraggableColumnHeader';
import { RowActionButtons } from './RowActionButtons';
import { SummaryRow, hasAnyAggregate } from './SummaryRow';
import { buildRowTree, flattenVisible, collectAllNodeIds } from './rowTree';
import { InlineEditCell } from '~/framework/meta/rendering/components/InlineEditCell';
import { deriveTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { getPublicRecordKey } from '~/framework/meta/utils/publicRecordId';

interface DictItem {
  value: string;
  label: string;
  extension?: Record<string, any>;
}

const DEFAULT_COLUMN_WIDTH = 160;
const SELECTION_COLUMN_WIDTH = 40;
const ACTION_COLUMN_WIDTH = 112;
// Per-depth left indent (px) for tree rows (T10). Matches the SubTable /
// TreeView indent feel; the chevron sits within this space.
const TREE_INDENT_PX = 20;

const normalizeRowHeight = (value: unknown): RowHeight =>
  typeof value === 'string' && value in ROW_HEIGHT_CONFIG ? (value as RowHeight) : DEFAULT_ROW_HEIGHT;

function isAutoFillColumn(column: ColumnConfig): boolean {
  if (column.isActionColumn) return false;
  const renderHint = String((column as any).renderType ?? column.valueType ?? '').toLowerCase();
  if (renderHint === 'tag' || renderHint === 'boolean' || renderHint === 'progress') return false;
  if (column.dictCode) return false;
  if (column.align === 'center' || column.align === 'right') return false;
  return true;
}

export interface ListTableProps {
  columns: ColumnConfig[];
  data: Record<string, any>[];
  loading: boolean;
  activeSorts: SortConfig[];
  selectedIds: Set<string>;
  rowHeight?: RowHeight;
  modelCode: string;
  columnOrder: string[];
  onColumnReorder: (newOrder: string[]) => void;
  onColumnResize: (field: string, width: number) => void;
  onToggleSort: (field: string, multiSort: boolean) => void;
  onSelectRow: (id: string, checked: boolean) => void;
  onSelectAll: (checked: boolean) => void;
  onRowClick: (record: Record<string, any>) => void;
  onContextMenu: (e: React.MouseEvent, column: ColumnConfig) => void;
  renderCellContent: (record: any, column: ColumnConfig, rowIndex: number) => React.ReactNode;
  evaluateVisibleWhen: (expr: string | undefined, record?: Record<string, any>) => boolean;
  canUseButton?: (button: ButtonConfig) => boolean;
  resolveButtonLabel: (button: ButtonConfig) => string;
  handleAction: (button: ButtonConfig, record?: Record<string, any>) => void;
  resolveColumnLabel: (column: ColumnConfig) => string;
  columnWidths: Record<string, number>;
  groupedData?: Array<{ key: string; rows: Record<string, any>[]; count: number }> | null;
  groupByField?: string;
  collapsedGroups: Set<string>;
  onToggleGroupCollapse: (key: string) => void;
  getRowStyle?: (record: Record<string, any>) => React.CSSProperties | undefined;
  previewRecordId?: string | null;
  t: (key: string) => string;
  onInlineSave?: (field: string, value: any, record: Record<string, any>) => Promise<void>;
  dictDataCache?: Map<string, DictItem[]>;
  enableSelection?: boolean;
  /**
   * Column summary/footer row (T10). `undefined` → auto (shown when any column
   * declares an `aggregate`); `false` suppresses it; `true` forces it.
   */
  showSummaryRow?: boolean;
  /** Active locale for summary number/currency formatting (default 'en'). */
  locale?: string;
  /**
   * Expandable tree rows (T10). DSL opt-in via the table block
   * (`table.treeConfig`). When set, self-referencing rows (by `treeConfig.
   * parentField`) render as an indented tree with expand/collapse chevrons in
   * the first data column. When unset, the table renders flat exactly as
   * before. Tree mode disables row virtualization (tree datasets are small and
   * virtualization assumes a flat, uniform list — same trade-off as grouping).
   */
  treeConfig?: TreeConfig;
}

export const ListTable = React.memo(function ListTable({
  columns,
  data,
  loading,
  activeSorts,
  selectedIds,
  rowHeight,
  modelCode,
  columnOrder,
  onColumnReorder,
  onColumnResize,
  onToggleSort,
  onSelectRow,
  onSelectAll,
  onRowClick,
  onContextMenu,
  renderCellContent,
  evaluateVisibleWhen,
  canUseButton = () => true,
  resolveButtonLabel,
  handleAction,
  resolveColumnLabel,
  columnWidths,
  groupedData,
  groupByField,
  collapsedGroups,
  onToggleGroupCollapse,
  getRowStyle,
  previewRecordId,
  t,
  onInlineSave,
  dictDataCache,
  enableSelection = true,
  showSummaryRow,
  locale = 'en',
  treeConfig,
}: ListTableProps) {
  const effectiveRowHeight = normalizeRowHeight(rowHeight);
  const rowHeightCfg = ROW_HEIGHT_CONFIG[effectiveRowHeight];
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const [containerWidth, setContainerWidth] = useState(0);

  const getColumnWidth = useCallback(
    (column: ColumnConfig) => {
      const configuredWidth = columnWidths[column.field] ?? column.width;
      const numericWidth =
        typeof configuredWidth === 'number' ? configuredWidth : Number(configuredWidth);
      return Number.isFinite(numericWidth) && numericWidth > 0
        ? numericWidth
        : DEFAULT_COLUMN_WIDTH;
    },
    [columnWidths],
  );

  const renderReadOnlyCellContent = useCallback(
    (record: Record<string, any>, column: ColumnConfig, rowIndex: number) => {
      const rawValue = record[column.field];
      const title =
        typeof rawValue === 'string' || typeof rawValue === 'number' ? String(rawValue) : undefined;
      return (
        <div className="max-w-full min-w-0 truncate" title={title}>
          {renderCellContent(record, column, rowIndex)}
        </div>
      );
    },
    [renderCellContent],
  );

  // Separate action column from data columns, then order data columns
  const { orderedDataColumns, actionColumn } = useMemo(() => {
    const actionCol = columns.find((c) => c.isActionColumn);
    const dataCols = columns.filter((c) => !c.isActionColumn);

    // Apply column order if provided
    if (columnOrder.length > 0) {
      const orderMap = new Map(columnOrder.map((field, idx) => [field, idx]));
      dataCols.sort((a, b) => {
        const idxA = orderMap.get(a.field) ?? 999;
        const idxB = orderMap.get(b.field) ?? 999;
        return idxA - idxB;
      });
    }

    return { orderedDataColumns: dataCols, actionColumn: actionCol };
  }, [columns, columnOrder]);

  // Sortable IDs for DndContext
  const sortableIds = useMemo(
    () => orderedDataColumns.map((col) => col.field),
    [orderedDataColumns],
  );

  const actionColumnWidth = actionColumn
    ? getColumnWidth({ ...actionColumn, width: actionColumn.width ?? ACTION_COLUMN_WIDTH })
    : 0;

  const baseTableWidth = useMemo(() => {
    const selectionWidth = enableSelection ? SELECTION_COLUMN_WIDTH : 0;
    const dataWidth = orderedDataColumns.reduce((sum, column) => sum + getColumnWidth(column), 0);
    return selectionWidth + dataWidth + actionColumnWidth;
  }, [actionColumnWidth, enableSelection, getColumnWidth, orderedDataColumns]);

  const renderedColumnWidths = useMemo(() => {
    const baseWidths = Object.fromEntries(
      orderedDataColumns.map((column) => [column.field, getColumnWidth(column)]),
    );
    const extraWidth = Math.max(0, containerWidth - baseTableWidth);
    if (extraWidth <= 0 || orderedDataColumns.length === 0) return baseWidths;

    const fillColumns = orderedDataColumns.filter(isAutoFillColumn);
    const targets = fillColumns.length > 0 ? fillColumns : orderedDataColumns;
    const targetBaseWidth = targets.reduce((sum, column) => sum + baseWidths[column.field], 0);
    const equalShare = extraWidth / targets.length;

    for (const column of targets) {
      const baseWidth = baseWidths[column.field];
      const weightedExtra =
        targetBaseWidth > 0 ? extraWidth * (baseWidth / targetBaseWidth) : equalShare;
      baseWidths[column.field] = Math.round(baseWidth + weightedExtra);
    }

    return baseWidths;
  }, [baseTableWidth, containerWidth, getColumnWidth, orderedDataColumns]);

  const getRenderedColumnWidth = useCallback(
    (column: ColumnConfig) => renderedColumnWidths[column.field] ?? getColumnWidth(column),
    [getColumnWidth, renderedColumnWidths],
  );

  const renderedTableWidth = useMemo(() => {
    const selectionWidth = enableSelection ? SELECTION_COLUMN_WIDTH : 0;
    const dataWidth = orderedDataColumns.reduce(
      (sum, column) => sum + getRenderedColumnWidth(column),
      0,
    );
    return selectionWidth + dataWidth + actionColumnWidth;
  }, [actionColumnWidth, enableSelection, getRenderedColumnWidth, orderedDataColumns]);

  const getCellStyle = useCallback(
    (column: ColumnConfig): React.CSSProperties => {
      const width = getRenderedColumnWidth(column);
      return {
        width: `${width}px`,
        maxWidth: `${width}px`,
      };
    },
    [getRenderedColumnWidth],
  );

  useEffect(() => {
    const element = scrollContainerRef.current;
    if (!element) return;

    const updateWidth = () => setContainerWidth(element.clientWidth);
    updateWidth();

    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', updateWidth);
      return () => window.removeEventListener('resize', updateWidth);
    }

    const observer = new ResizeObserver(updateWidth);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  // DnD sensors — PointerSensor with distance threshold to avoid accidental drags
  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: { distance: 5 },
    }),
  );

  // Handle drag end — reorder columns
  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      if (!over || active.id === over.id) return;

      const oldIndex = sortableIds.indexOf(String(active.id));
      const newIndex = sortableIds.indexOf(String(over.id));
      if (oldIndex === -1 || newIndex === -1) return;

      const newOrder = arrayMove(sortableIds, oldIndex, newIndex);
      onColumnReorder(newOrder);
    },
    [sortableIds, onColumnReorder],
  );

  // Checkbox state
  const allSelected = enableSelection && selectedIds.size > 0 && selectedIds.size === data.length;
  const someSelected = enableSelection && selectedIds.size > 0 && selectedIds.size < data.length;

  // Visible data when grouping is active (exclude collapsed groups)
  const visibleData = useMemo(() => {
    if (!groupedData || !groupByField) return data;
    return data.filter((r) => !collapsedGroups.has(String(r[groupByField] ?? '(empty)')));
  }, [data, groupedData, groupByField, collapsedGroups]);

  // Tree rows (T10) — only active when `treeConfig` is configured AND grouping
  // is not (the two are mutually exclusive layouts). Build the nested tree from
  // the current page's rows and flatten it down to the rows currently visible
  // given `expandedTreeIds`. The first data column gets per-depth indent + a
  // chevron for nodes that have children (see the row render below).
  const treeMode = !!treeConfig && !groupedData;
  const treeIdField = 'pid';
  const rowTree = useMemo(() => {
    if (!treeMode) return [];
    return buildRowTree(data, { idField: treeIdField, parentField: treeConfig!.parentField });
  }, [treeMode, data, treeConfig]);
  // Expanded node ids. Initialised to "all expanded" when the config opts in
  // (`defaultExpanded` defaults to true), recomputed when the underlying tree
  // identity changes so newly loaded data starts in the configured state.
  const [expandedTreeIds, setExpandedTreeIds] = useState<Set<string>>(new Set());
  const lastTreeSignatureRef = useRef<string>('');
  useEffect(() => {
    if (!treeMode) return;
    const signature = data.map((r) => String(r.pid ?? r.id ?? '')).join('|');
    if (signature === lastTreeSignatureRef.current) return;
    lastTreeSignatureRef.current = signature;
    const defaultExpanded = treeConfig?.defaultExpanded ?? true;
    setExpandedTreeIds(
      defaultExpanded
        ? collectAllNodeIds(rowTree, { idField: treeIdField, parentField: treeConfig!.parentField })
        : new Set<string>(),
    );
  }, [treeMode, data, rowTree, treeConfig]);
  const toggleTreeRow = useCallback((id: string) => {
    setExpandedTreeIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);
  const visibleTreeRows = useMemo(() => {
    if (!treeMode) return [];
    return flattenVisible(rowTree, expandedTreeIds, {
      idField: treeIdField,
      parentField: treeConfig!.parentField,
    });
  }, [treeMode, rowTree, expandedTreeIds, treeConfig]);

  // Column summary/footer row (T10). Auto-show when any column declares an
  // `aggregate`; an explicit `showSummaryRow` prop overrides. Aggregates cover
  // the rows currently rendered on this page (see SummaryRow / columnAggregation).
  const columnsHaveAggregate = useMemo(
    () => hasAnyAggregate(orderedDataColumns),
    [orderedDataColumns],
  );
  const renderSummaryRow =
    showSummaryRow === false ? false : (showSummaryRow ?? false) || columnsHaveAggregate;

  // Virtual scrolling threshold — only virtualize when we have many rows.
  // Tree mode opts out (rows aren't a flat uniform list — same as grouping).
  const VIRTUAL_THRESHOLD = 50;
  const enableVirtualization =
    !loading && visibleData.length > VIRTUAL_THRESHOLD && !groupedData && !treeMode;

  // Virtualizer — always created but only used when enabled
  const rowVirtualizer = useVirtualizer({
    count: enableVirtualization ? visibleData.length : 0,
    getScrollElement: () => scrollContainerRef.current,
    estimateSize: () => rowHeightCfg.px,
    overscan: 10,
  });

  // i18n aria-labels for the tree expand/collapse chevron (T10). The runtime
  // `t` returns the key unchanged when no resource is registered, so fall back
  // to an English default — matching the translateOrFallback pattern used by
  // the list layer elsewhere.
  const expandLabel = (() => {
    const key = 'list.expand_row';
    const resolved = t(key);
    return resolved && resolved !== key ? resolved : 'Expand row';
  })();
  const collapseLabel = (() => {
    const key = 'list.collapse_row';
    const resolved = t(key);
    return resolved && resolved !== key ? resolved : 'Collapse row';
  })();

  // First-column tree affordance (T10): a depth-proportional left indent plus a
  // chevron toggle for nodes that have children (aligned spacer otherwise so
  // labels line up). Token-styled (`text-text-3`); the chevron rotates on
  // expand. Returns the wrapped cell content for the tree's label column.
  const renderTreeAffordance = (
    content: React.ReactNode,
    depth: number,
    hasChildren: boolean,
    expanded: boolean,
    rowId: string,
  ): React.ReactNode => (
    <div
      className="flex min-w-0 items-center"
      style={{ paddingLeft: `${depth * TREE_INDENT_PX}px` }}
    >
      {hasChildren ? (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            toggleTreeRow(rowId);
          }}
          aria-label={expanded ? collapseLabel : expandLabel}
          aria-expanded={expanded}
          data-testid={`tree-toggle-${rowId}`}
          className="text-text-3 hover:text-text-2 focus-visible:shadow-focus mr-1 flex h-5 w-5 flex-none items-center justify-center rounded focus:outline-none"
        >
          <svg
            className={`h-3.5 w-3.5 transition-transform duration-150 ${expanded ? 'rotate-90' : ''}`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
            aria-hidden="true"
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
          </svg>
        </button>
      ) : (
        // Aligned spacer so leaf labels line up with siblings that have chevrons.
        <span className="mr-1 h-5 w-5 flex-none" aria-hidden="true" />
      )}
      <div className="min-w-0 flex-1 truncate">{content}</div>
    </div>
  );

  return (
    <div
      ref={scrollContainerRef}
      className={`relative overflow-x-auto ${enableVirtualization ? 'overflow-y-auto' : ''}`}
      style={enableVirtualization ? { maxHeight: 'calc(100vh - 280px)' } : undefined}
      data-testid={deriveTestId('list', modelCode, 'table')}
    >
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext items={sortableIds} strategy={horizontalListSortingStrategy}>
          <table
            className="divide-border min-w-full table-fixed divide-y"
            style={{ minWidth: `${renderedTableWidth}px` }}
          >
            <colgroup>
              {enableSelection && <col style={{ width: `${SELECTION_COLUMN_WIDTH}px` }} />}
              {orderedDataColumns.map((column) => (
                <col key={column.field} style={{ width: `${getRenderedColumnWidth(column)}px` }} />
              ))}
              {actionColumn && <col style={{ width: `${actionColumnWidth}px` }} />}
            </colgroup>
            <thead className={`bg-subtle ${enableVirtualization ? 'sticky top-0 z-20' : ''}`}>
              <tr>
                {enableSelection && (
                  <th className="print-hide w-10 px-3 py-3" data-print="hide">
                    <input
                      type="checkbox"
                      checked={allSelected}
                      ref={(el) => {
                        if (el) el.indeterminate = someSelected;
                      }}
                      onChange={() => onSelectAll(!allSelected)}
                      className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded focus:outline-none"
                      data-testid="select-all-checkbox"
                    />
                  </th>
                )}

                {/* Data columns — draggable */}
                {orderedDataColumns.map((column) => {
                  const sortInfo = activeSorts.find((s) => s.fieldCode === column.field);
                  const isSortable = column.sortable !== false;
                  const colWidth = getRenderedColumnWidth(column);

                  return (
                    <DraggableColumnHeader
                      key={column.field}
                      column={column}
                      label={resolveColumnLabel(column)}
                      sortable={isSortable}
                      sortInfo={
                        sortInfo
                          ? {
                              direction: sortInfo.direction,
                              priority:
                                activeSorts.length > 1
                                  ? activeSorts.findIndex((s) => s.fieldCode === column.field) +
                                      1 || undefined
                                  : undefined,
                            }
                          : undefined
                      }
                      onSort={onToggleSort}
                      onResize={onColumnResize}
                      onContextMenu={onContextMenu}
                      draggable
                      width={colWidth}
                    />
                  );
                })}

                {/* Action column — not draggable */}
                {actionColumn && (
                  <DraggableColumnHeader
                    column={actionColumn}
                    label={t('table.actions')}
                    sortable={false}
                    onSort={() => {}}
                    onResize={() => {}}
                    onContextMenu={() => {}}
                    draggable={false}
                  />
                )}
              </tr>
            </thead>

            <tbody className="bg-panel divide-y divide-gray-100">
              {/* Loading state */}
              {loading ? (
                <tr>
                  <td
                    colSpan={(columns.length || 1) + (enableSelection ? 1 : 0)}
                    className="px-6 py-4 text-center"
                  >
                    <div className="flex items-center justify-center">
                      <span className="loading loading-spinner loading-md mr-2"></span>
                      {t('message.loading')}
                    </div>
                  </td>
                </tr>
              ) : data.length === 0 ? (
                /* Empty state */
                <tr>
                  <td
                    colSpan={(columns.length || 1) + (enableSelection ? 1 : 0)}
                    className="text-text-2 px-6 py-4 text-center"
                    data-testid="empty-state"
                  >
                    {t('table.noData') || 'No data'}
                  </td>
                </tr>
              ) : (
                /* Group headers (if grouping is active) */
                (() => {
                  const rowElements: React.ReactNode[] = [];
                  if (groupedData) {
                    for (const group of groupedData) {
                      const isCollapsed = collapsedGroups.has(group.key);
                      rowElements.push(
                        <tr
                          key={`group-${group.key}`}
                          className="bg-subtle hover:bg-hover cursor-pointer"
                          onClick={() => onToggleGroupCollapse(group.key)}
                        >
                          <td
                            colSpan={(columns.length || 1) + (enableSelection ? 1 : 0)}
                            className="text-text-2 px-6 py-2 text-sm font-medium"
                          >
                            <span className="text-text-3 mr-2 text-xs">
                              {isCollapsed ? '\u25B6' : '\u25BC'}
                            </span>
                            <span className="font-semibold">{groupByField}</span>
                            <span className="mx-1">:</span>
                            <span>{group.key}</span>
                            <span className="text-text-3 ml-2 text-xs">({group.count})</span>
                          </td>
                        </tr>,
                      );
                    }
                  }
                  return rowElements;
                })()
              )}

              {/* Tree rows (T10) — depth-first flattened, indented, with a
                  chevron toggle in the first data column. Takes priority over
                  the flat/virtualized branches when `treeConfig` is set. */}
              {treeMode && !loading && data.length > 0
                ? visibleTreeRows.map((node, index) => {
                    const record = node.row;
                    const rowId = getPublicRecordKey(record) || '';
                    const cfInline = getRowStyle?.(record);
                    const firstColumnField = orderedDataColumns[0]?.field;
                    return (
                      <tr
                        key={rowId || index}
                        data-testid={`table-row-${index}`}
                        data-tree-depth={node.depth}
                        className={`group cursor-pointer hover:bg-hover${selectedIds.has(rowId) ? 'bg-accent-weak' : ''}${previewRecordId === rowId ? 'bg-accent-weak/50' : ''}`}
                        style={{ height: `${rowHeightCfg.px}px`, ...cfInline }}
                        onClick={() => onRowClick(record)}
                      >
                        {enableSelection && (
                          <td
                            className={`px-3 ${rowHeightCfg.pyClass} print-hide w-10`}
                            data-print="hide"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <input
                              type="checkbox"
                              checked={selectedIds.has(rowId)}
                              onChange={() => onSelectRow(rowId, !selectedIds.has(rowId))}
                              className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded focus:outline-none"
                              data-testid={`row-checkbox-${index}`}
                            />
                          </td>
                        )}

                        {orderedDataColumns.map((column) => {
                          const tdFrozenPos = column.fixed || (column as any).frozenPosition;
                          const tdFrozenLeft = tdFrozenPos === 'left';
                          const tdFrozenRight = tdFrozenPos === 'right';
                          const isFirstColumn = column.field === firstColumnField;
                          const cellInner =
                            column.editable && onInlineSave ? (
                              <InlineEditCell
                                column={column}
                                value={record[column.field]}
                                record={record}
                                onSave={onInlineSave}
                                editable
                                dictItems={
                                  column.dictCode && dictDataCache
                                    ? (dictDataCache.get(column.dictCode) ?? [])
                                    : undefined
                                }
                              >
                                {renderCellContent(record, column, index)}
                              </InlineEditCell>
                            ) : (
                              renderReadOnlyCellContent(record, column, index)
                            );
                          return (
                            <td
                              key={column.field}
                              style={getCellStyle(column)}
                              data-testid={`table-cell-${index}-${column.field}`}
                              className={`px-6 ${rowHeightCfg.pyClass} text-text-2 text-sm whitespace-nowrap ${
                                column.align === 'right'
                                  ? 'text-right'
                                  : column.align === 'center'
                                    ? 'text-center'
                                    : ''
                              } ${
                                tdFrozenRight
                                  ? 'border-border bg-panel group-hover:bg-hover sticky right-0 z-10 border-l shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)]'
                                  : tdFrozenLeft
                                    ? 'border-border bg-panel group-hover:bg-hover sticky left-0 z-10 border-r shadow-[2px_0_4px_-2px_rgba(0,0,0,0.1)]'
                                    : ''
                              }`}
                            >
                              {isFirstColumn
                                ? renderTreeAffordance(
                                    cellInner,
                                    node.depth,
                                    node.hasChildren,
                                    expandedTreeIds.has(rowId),
                                    rowId,
                                  )
                                : cellInner}
                            </td>
                          );
                        })}

                        {actionColumn && (
                          <td
                            data-testid={`table-cell-${index}-actions`}
                            className={`px-2 ${rowHeightCfg.pyClass} border-border bg-panel group-hover:bg-hover sticky right-0 z-10 border-l shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)]`}
                            style={{
                              width: `${actionColumnWidth}px`,
                              maxWidth: `${actionColumnWidth}px`,
                            }}
                          >
                            <div className="flex items-center">
                              <RowActionButtons
                                buttons={actionColumn.buttons || []}
                                record={record}
                                evaluateVisibleWhen={evaluateVisibleWhen}
                                canUseButton={canUseButton}
                                resolveButtonLabel={resolveButtonLabel}
                                handleAction={handleAction}
                              />
                            </div>
                          </td>
                        )}
                      </tr>
                    );
                  })
                : null}

              {/* Data rows — virtualized when row count exceeds threshold */}
              {!treeMode && !loading && data.length > 0 && enableVirtualization ? (
                <>
                  {/* Top spacer for virtual scroll offset */}
                  {rowVirtualizer.getVirtualItems().length > 0 && (
                    <tr>
                      <td
                        style={{
                          height: `${rowVirtualizer.getVirtualItems()[0]?.start ?? 0}px`,
                          padding: 0,
                          border: 'none',
                        }}
                        colSpan={(columns.length || 1) + (enableSelection ? 1 : 0)}
                      />
                    </tr>
                  )}
                  {rowVirtualizer.getVirtualItems().map((virtualRow) => {
                    const index = virtualRow.index;
                    const record = visibleData[index]!;
                    const rowId = getPublicRecordKey(record) || '';
                    const cfInline = getRowStyle?.(record);
                    return (
                      <tr
                        key={rowId || index}
                        data-testid={`table-row-${index}`}
                        data-index={virtualRow.index}
                        className={`group cursor-pointer hover:bg-hover${selectedIds.has(rowId) ? 'bg-accent-weak' : ''}${previewRecordId === rowId ? 'bg-accent-weak/50' : ''}`}
                        style={{ height: `${rowHeightCfg.px}px`, ...cfInline }}
                        onClick={() => onRowClick(record)}
                      >
                        {enableSelection && (
                          <td
                            className={`px-3 ${rowHeightCfg.pyClass} print-hide w-10`}
                            data-print="hide"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <input
                              type="checkbox"
                              checked={selectedIds.has(rowId)}
                              onChange={() => onSelectRow(rowId, !selectedIds.has(rowId))}
                              className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded focus:outline-none"
                              data-testid={`row-checkbox-${index}`}
                            />
                          </td>
                        )}
                        {orderedDataColumns.map((column) => {
                          const tdFrozenPos = column.fixed || (column as any).frozenPosition;
                          const tdFrozenLeft = tdFrozenPos === 'left';
                          const tdFrozenRight = tdFrozenPos === 'right';
                          return (
                            <td
                              key={column.field}
                              style={getCellStyle(column)}
                              data-testid={`table-cell-${index}-${column.field}`}
                              className={`px-6 ${rowHeightCfg.pyClass} text-text-2 text-sm whitespace-nowrap ${
                                column.align === 'right'
                                  ? 'text-right'
                                  : column.align === 'center'
                                    ? 'text-center'
                                    : ''
                              } ${
                                tdFrozenRight
                                  ? 'border-border bg-panel group-hover:bg-hover sticky right-0 z-10 border-l shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)]'
                                  : tdFrozenLeft
                                    ? 'border-border bg-panel group-hover:bg-hover sticky left-0 z-10 border-r shadow-[2px_0_4px_-2px_rgba(0,0,0,0.1)]'
                                    : ''
                              }`}
                            >
                              {column.editable && onInlineSave ? (
                                <InlineEditCell
                                  column={column}
                                  value={record[column.field]}
                                  record={record}
                                  onSave={onInlineSave}
                                  editable
                                  dictItems={
                                    column.dictCode && dictDataCache
                                      ? (dictDataCache.get(column.dictCode) ?? [])
                                      : undefined
                                  }
                                >
                                  {renderCellContent(record, column, index)}
                                </InlineEditCell>
                              ) : (
                                renderReadOnlyCellContent(record, column, index)
                              )}
                            </td>
                          );
                        })}
                        {actionColumn && (
                          <td
                            data-testid={`table-cell-${index}-actions`}
                            className={`px-2 ${rowHeightCfg.pyClass} border-border bg-panel group-hover:bg-hover sticky right-0 z-10 border-l shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)]`}
                            style={{
                              width: `${actionColumnWidth}px`,
                              maxWidth: `${actionColumnWidth}px`,
                            }}
                          >
                            <div className="flex items-center">
                              <RowActionButtons
                                buttons={actionColumn.buttons || []}
                                record={record}
                                evaluateVisibleWhen={evaluateVisibleWhen}
                                canUseButton={canUseButton}
                                resolveButtonLabel={resolveButtonLabel}
                                handleAction={handleAction}
                              />
                            </div>
                          </td>
                        )}
                      </tr>
                    );
                  })}
                  {/* Bottom spacer for virtual scroll offset */}
                  {rowVirtualizer.getVirtualItems().length > 0 && (
                    <tr>
                      <td
                        style={{
                          height: `${rowVirtualizer.getTotalSize() - (rowVirtualizer.getVirtualItems().at(-1)?.end ?? 0)}px`,
                          padding: 0,
                          border: 'none',
                        }}
                        colSpan={(columns.length || 1) + (enableSelection ? 1 : 0)}
                      />
                    </tr>
                  )}
                </>
              ) : (
                /* Non-virtualized data rows (small datasets or grouped data) */
                !treeMode &&
                !loading &&
                data.length > 0 &&
                visibleData.map((record, index) => {
                  const rowId = getPublicRecordKey(record) || '';
                  const cfInline = getRowStyle?.(record);
                  return (
                    <tr
                      key={rowId || index}
                      data-testid={`table-row-${index}`}
                      className={`group cursor-pointer hover:bg-hover${selectedIds.has(rowId) ? 'bg-accent-weak' : ''}${previewRecordId === rowId ? 'bg-accent-weak/50' : ''}`}
                      style={{ height: `${rowHeightCfg.px}px`, ...cfInline }}
                      onClick={() => onRowClick(record)}
                    >
                      {enableSelection && (
                        <td
                          className={`px-3 ${rowHeightCfg.pyClass} print-hide w-10`}
                          data-print="hide"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <input
                            type="checkbox"
                            checked={selectedIds.has(rowId)}
                            onChange={() => onSelectRow(rowId, !selectedIds.has(rowId))}
                            className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded focus:outline-none"
                            data-testid={`row-checkbox-${index}`}
                          />
                        </td>
                      )}

                      {/* Data cells — ordered */}
                      {orderedDataColumns.map((column) => {
                        const tdFrozenPos = column.fixed || (column as any).frozenPosition;
                        const tdFrozenLeft = tdFrozenPos === 'left';
                        const tdFrozenRight = tdFrozenPos === 'right';
                        return (
                          <td
                            key={column.field}
                            style={getCellStyle(column)}
                            data-testid={`table-cell-${index}-${column.field}`}
                            className={`px-6 ${rowHeightCfg.pyClass} text-text-2 text-sm whitespace-nowrap ${
                              column.align === 'right'
                                ? 'text-right'
                                : column.align === 'center'
                                  ? 'text-center'
                                  : ''
                            } ${
                              tdFrozenRight
                                ? 'border-border bg-panel group-hover:bg-hover sticky right-0 z-10 border-l shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)]'
                                : tdFrozenLeft
                                  ? 'border-border bg-panel group-hover:bg-hover sticky left-0 z-10 border-r shadow-[2px_0_4px_-2px_rgba(0,0,0,0.1)]'
                                  : ''
                            }`}
                          >
                            {column.editable && onInlineSave ? (
                              <InlineEditCell
                                column={column}
                                value={record[column.field]}
                                record={record}
                                onSave={onInlineSave}
                                editable
                                dictItems={
                                  column.dictCode && dictDataCache
                                    ? (dictDataCache.get(column.dictCode) ?? [])
                                    : undefined
                                }
                              >
                                {renderCellContent(record, column, index)}
                              </InlineEditCell>
                            ) : (
                              renderReadOnlyCellContent(record, column, index)
                            )}
                          </td>
                        );
                      })}

                      {/* Action column cell */}
                      {actionColumn && (
                        <td
                          data-testid={`table-cell-${index}-actions`}
                          className={`px-2 ${rowHeightCfg.pyClass} border-border bg-panel group-hover:bg-hover sticky right-0 z-10 border-l shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)]`}
                          style={{
                            width: `${actionColumnWidth}px`,
                            maxWidth: `${actionColumnWidth}px`,
                          }}
                        >
                          <div className="flex items-center">
                            <RowActionButtons
                              buttons={actionColumn.buttons || []}
                              record={record}
                              evaluateVisibleWhen={evaluateVisibleWhen}
                              canUseButton={canUseButton}
                              resolveButtonLabel={resolveButtonLabel}
                              handleAction={handleAction}
                            />
                          </div>
                        </td>
                      )}
                    </tr>
                  );
                })
              )}
            </tbody>

            {/* Column aggregation summary footer (T10) — current page rows */}
            {renderSummaryRow && !loading && visibleData.length > 0 && (
              <SummaryRow
                columns={orderedDataColumns}
                rows={visibleData}
                enableSelection={enableSelection}
                hasActionColumn={!!actionColumn}
                getColumnWidth={getRenderedColumnWidth}
                locale={locale}
                t={t}
              />
            )}
          </table>
        </SortableContext>
      </DndContext>
    </div>
  );
});
