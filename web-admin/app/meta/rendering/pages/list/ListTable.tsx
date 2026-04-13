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
import React, { useMemo, useCallback, useRef } from 'react';
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
import type { ColumnConfig, ButtonConfig } from '~/meta/schemas/types';
import type { SortConfig, RowHeight } from '~/smart/types/savedView';
import { ROW_HEIGHT_CONFIG, DEFAULT_ROW_HEIGHT } from '~/smart/types/savedView';
import { DraggableColumnHeader } from './DraggableColumnHeader';
import { RowActionButtons } from './RowActionButtons';
import { InlineEditCell } from '~/meta/rendering/components/InlineEditCell';
import { deriveTestId } from '~/meta/rendering/utils/deriveTestId';

interface DictItem {
  value: string;
  label: string;
  extension?: Record<string, any>;
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
}: ListTableProps) {
  const effectiveRowHeight = rowHeight || DEFAULT_ROW_HEIGHT;
  const rowHeightCfg = ROW_HEIGHT_CONFIG[effectiveRowHeight];

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
  const allSelected = selectedIds.size > 0 && selectedIds.size === data.length;
  const someSelected = selectedIds.size > 0 && selectedIds.size < data.length;

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

  // Scroll container ref for the virtualizer
  const scrollContainerRef = useRef<HTMLDivElement>(null);

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
          <table className="min-w-full divide-y divide-gray-200">
            <thead className={`bg-gray-50 ${enableVirtualization ? 'sticky top-0 z-20' : ''}`}>
              <tr>
                {/* Checkbox column */}
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

                {/* Data columns — draggable */}
                {orderedDataColumns.map((column) => {
                  const sortInfo = activeSorts.find((s) => s.fieldCode === column.field);
                  const isSortable = column.sortable !== false;
                  const colWidth = columnWidths[column.field] ?? column.width;

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
                    colSpan={(columns.length || 1) + 1}
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
                    colSpan={(columns.length || 1) + 1}
                    className="px-6 py-4 text-center text-gray-500"
                  >
                    {t('table.noData')}
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
                            colSpan={(columns.length || 1) + 1}
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
                        colSpan={(columns.length || 1) + 1}
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
                        {orderedDataColumns.map((column) => {
                          const tdFrozenPos = column.fixed || (column as any).frozenPosition;
                          const tdFrozenLeft = tdFrozenPos === 'left';
                          const tdFrozenRight = tdFrozenPos === 'right';
                          return (
                            <td
                              key={column.field}
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
                                renderCellContent(record, column, index)
                              )}
                            </td>
                          );
                        })}
                        {actionColumn && (
                          <td
                            className={`px-2 ${rowHeightCfg.pyClass} sticky right-0 z-10 w-px border-l border-gray-200 bg-white shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)] group-hover:bg-gray-50`}
                          >
                            <div className="opacity-0 transition-opacity duration-150 group-hover:opacity-100">
                              <RowActionButtons
                                buttons={actionColumn.buttons || []}
                                record={record}
                                evaluateVisibleWhen={evaluateVisibleWhen}
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
                        colSpan={(columns.length || 1) + 1}
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
                        {/* Checkbox cell */}
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

                        {/* Data cells — ordered */}
                        {orderedDataColumns.map((column) => {
                          const tdFrozenPos = column.fixed || (column as any).frozenPosition;
                          const tdFrozenLeft = tdFrozenPos === 'left';
                          const tdFrozenRight = tdFrozenPos === 'right';
                          return (
                            <td
                              key={column.field}
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
                                renderCellContent(record, column, index)
                              )}
                            </td>
                          );
                        })}

                        {/* Action column cell */}
                        {actionColumn && (
                          <td
                            className={`px-2 ${rowHeightCfg.pyClass} sticky right-0 z-10 w-px border-l border-gray-200 bg-white shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)] group-hover:bg-gray-50`}
                          >
                            <div className="opacity-0 transition-opacity duration-150 group-hover:opacity-100">
                              <RowActionButtons
                                buttons={actionColumn.buttons || []}
                                record={record}
                                evaluateVisibleWhen={evaluateVisibleWhen}
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
