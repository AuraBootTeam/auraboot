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
import {
  SortableContext,
  horizontalListSortingStrategy,
  arrayMove,
} from '@dnd-kit/sortable';
import type { ColumnConfig, ButtonConfig } from '~/framework/meta/schemas/types';
import {
  ROW_HEIGHT_CONFIG,
  DEFAULT_ROW_HEIGHT,
  type SortConfig,
  type RowHeight,
} from '~/framework/smart/types/savedView';
import { DraggableColumnHeader } from './DraggableColumnHeader';
import { RowActionButtons } from './RowActionButtons';
import { InlineEditCell } from '~/framework/meta/rendering/components/InlineEditCell';
import { deriveTestId } from '~/framework/meta/rendering/utils/deriveTestId';

interface DictItem {
  value: string;
  label: string;
  extension?: Record<string, any>;
}

const DEFAULT_COLUMN_WIDTH = 160;
const SELECTION_COLUMN_WIDTH = 40;
const ACTION_COLUMN_WIDTH = 112;

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
}: ListTableProps) {
  const effectiveRowHeight = rowHeight || DEFAULT_ROW_HEIGHT;
  const rowHeightCfg = ROW_HEIGHT_CONFIG[effectiveRowHeight];
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const [containerWidth, setContainerWidth] = useState(0);

  const getColumnWidth = useCallback(
    (column: ColumnConfig) => {
      const configuredWidth = columnWidths[column.field] ?? column.width;
      const numericWidth =
        typeof configuredWidth === 'number'
          ? configuredWidth
          : Number(configuredWidth);
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
        typeof rawValue === 'string' || typeof rawValue === 'number'
          ? String(rawValue)
          : undefined;
      return (
        <div className="min-w-0 max-w-full truncate" title={title}>
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
    const dataWidth = orderedDataColumns.reduce(
      (sum, column) => sum + getColumnWidth(column),
      0,
    );
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
    return data.filter(
      (r) => !collapsedGroups.has(String(r[groupByField] ?? '(empty)')),
    );
  }, [data, groupedData, groupByField, collapsedGroups]);

  // Virtual scrolling threshold — only virtualize when we have many rows
  const VIRTUAL_THRESHOLD = 50;
  const enableVirtualization = !loading && visibleData.length > VIRTUAL_THRESHOLD && !groupedData;

  // Virtualizer — always created but only used when enabled
  const rowVirtualizer = useVirtualizer({
    count: enableVirtualization ? visibleData.length : 0,
    getScrollElement: () => scrollContainerRef.current,
    estimateSize: () => rowHeightCfg.px,
    overscan: 10,
  });

  return (
    <div
      ref={scrollContainerRef}
      className={`relative overflow-x-auto ${enableVirtualization ? 'overflow-y-auto' : ''}`}
      style={enableVirtualization ? { maxHeight: 'calc(100vh - 280px)' } : undefined}
      data-testid={deriveTestId('list', modelCode, 'table')}
    >
      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        onDragEnd={handleDragEnd}
      >
        <SortableContext items={sortableIds} strategy={horizontalListSortingStrategy}>
          <table
            className="min-w-full table-fixed divide-y divide-gray-200"
            style={{ minWidth: `${renderedTableWidth}px` }}
          >
            <colgroup>
              {enableSelection && <col style={{ width: `${SELECTION_COLUMN_WIDTH}px` }} />}
              {orderedDataColumns.map((column) => (
                <col
                  key={column.field}
                  style={{ width: `${getRenderedColumnWidth(column)}px` }}
                />
              ))}
              {actionColumn && <col style={{ width: `${actionColumnWidth}px` }} />}
            </colgroup>
            <thead className={`bg-gray-50 ${enableVirtualization ? 'sticky top-0 z-20' : ''}`}>
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
                      className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
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
                                  ? (activeSorts.findIndex((s) => s.fieldCode === column.field) + 1) ||
                                    undefined
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

            <tbody className="divide-y divide-gray-100 bg-white">
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
                    className="px-6 py-4 text-center text-gray-500"
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
                          className="cursor-pointer bg-gray-50 hover:bg-gray-100"
                          onClick={() => onToggleGroupCollapse(group.key)}
                        >
                          <td
                            colSpan={(columns.length || 1) + (enableSelection ? 1 : 0)}
                            className="px-6 py-2 text-sm font-medium text-gray-700"
                          >
                            <span className="mr-2 text-xs text-gray-400">
                              {isCollapsed ? '\u25B6' : '\u25BC'}
                            </span>
                            <span className="font-semibold">{groupByField}</span>
                            <span className="mx-1">:</span>
                            <span>{group.key}</span>
                            <span className="ml-2 text-xs text-gray-400">
                              ({group.count})
                            </span>
                          </td>
                        </tr>,
                      );
                    }
                  }
                  return rowElements;
                })()
              )}

              {/* Data rows — virtualized when row count exceeds threshold */}
              {!loading && data.length > 0 && enableVirtualization ? (
                <>
                  {/* Top spacer for virtual scroll offset */}
                  {rowVirtualizer.getVirtualItems().length > 0 && (
                    <tr>
                      <td
                        style={{ height: `${rowVirtualizer.getVirtualItems()[0]?.start ?? 0}px`, padding: 0, border: 'none' }}
                        colSpan={(columns.length || 1) + (enableSelection ? 1 : 0)}
                      />
                    </tr>
                  )}
                  {rowVirtualizer.getVirtualItems().map((virtualRow) => {
                    const index = virtualRow.index;
                    const record = visibleData[index]!;
                    const rowId = record.pid || record.id || '';
                    const cfInline = getRowStyle?.(record);
                    return (
                      <tr
                        key={rowId || index}
                        data-testid={`table-row-${index}`}
                        data-index={virtualRow.index}
                        className={`group cursor-pointer hover:bg-gray-50${selectedIds.has(rowId) ? ' bg-blue-50' : ''}${previewRecordId === rowId ? ' bg-blue-50/50' : ''}`}
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
                              className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
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
                              className={`px-6 ${rowHeightCfg.pyClass} text-sm whitespace-nowrap text-gray-700 ${
                                column.align === 'right'
                                  ? 'text-right'
                                  : column.align === 'center'
                                    ? 'text-center'
                                    : ''
                              } ${
                                tdFrozenRight
                                  ? 'sticky right-0 z-10 border-l border-gray-200 bg-white shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)] group-hover:bg-gray-50'
                                  : tdFrozenLeft
                                    ? 'sticky left-0 z-10 border-r border-gray-200 bg-white shadow-[2px_0_4px_-2px_rgba(0,0,0,0.1)] group-hover:bg-gray-50'
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
                            className={`px-2 ${rowHeightCfg.pyClass} sticky right-0 z-10 border-l border-gray-200 bg-white shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)] group-hover:bg-gray-50`}
                            style={{
                              width: `${actionColumnWidth}px`,
                              maxWidth: `${actionColumnWidth}px`,
                            }}
                          >
                            <div className="opacity-0 transition-opacity duration-150 group-hover:opacity-100 has-[[data-row-actions-open=true]]:opacity-100">
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
                !loading &&
                  data.length > 0 &&
                  visibleData.map((record, index) => {
                    const rowId = record.pid || record.id || '';
                    const cfInline = getRowStyle?.(record);
                    return (
                      <tr
                        key={rowId || index}
                        data-testid={`table-row-${index}`}
                        className={`group cursor-pointer hover:bg-gray-50${selectedIds.has(rowId) ? ' bg-blue-50' : ''}${previewRecordId === rowId ? ' bg-blue-50/50' : ''}`}
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
                              className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
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
                              className={`px-6 ${rowHeightCfg.pyClass} text-sm whitespace-nowrap text-gray-700 ${
                                column.align === 'right'
                                  ? 'text-right'
                                  : column.align === 'center'
                                    ? 'text-center'
                                    : ''
                              } ${
                                tdFrozenRight
                                  ? 'sticky right-0 z-10 border-l border-gray-200 bg-white shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)] group-hover:bg-gray-50'
                                  : tdFrozenLeft
                                    ? 'sticky left-0 z-10 border-r border-gray-200 bg-white shadow-[2px_0_4px_-2px_rgba(0,0,0,0.1)] group-hover:bg-gray-50'
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
                            className={`px-2 ${rowHeightCfg.pyClass} sticky right-0 z-10 border-l border-gray-200 bg-white shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)] group-hover:bg-gray-50`}
                            style={{
                              width: `${actionColumnWidth}px`,
                              maxWidth: `${actionColumnWidth}px`,
                            }}
                          >
                            <div className="opacity-0 transition-opacity duration-150 group-hover:opacity-100 has-[[data-row-actions-open=true]]:opacity-100">
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
          </table>
        </SortableContext>
      </DndContext>
    </div>
  );
});
