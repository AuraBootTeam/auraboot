import React, { useState, useCallback, useEffect, useMemo } from 'react';
import type { SubTableColumn, SubTableConfig, SubTableSummaryConfig } from './types';
import { useTreeData } from '~/framework/meta/hooks/useTreeData';
import { DndSubTableWrapper } from '~/framework/meta/components/subtable/DndSubTableWrapper';
import { isDescendant, INDENT_WIDTH } from '~/framework/meta/components/subtable/dndUtils';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

interface SubTableProps extends SubTableConfig {
  value?: Record<string, any>[];
  onChange?: (rows: Record<string, any>[]) => void;
  disabled?: boolean;
  className?: string;
}

/**
 * SubTable - inline sub-table editor for master-detail forms.
 * Supports add/remove/edit rows with column-type-specific editors.
 * Supports Tab/Shift+Tab/Enter/Escape keyboard navigation.
 * Supports optional column aggregate footer via `summary` prop.
 * Supports @dnd-kit drag-and-drop sorting and tree hierarchy.
 */
export const SubTable: React.FC<SubTableProps> = ({
  columns,
  value = [],
  onChange,
  maxRows = 50,
  minRows = 0,
  addLabel = '+ 添加行',
  sortable = false,
  sortField = 'sort_order',
  showIndex = true,
  disabled = false,
  className = '',
  summary,
  treeConfig,
}) => {
  const [rows, setRows] = useState<Record<string, any>[]>(value);

  useEffect(() => {
    setRows(value);
  }, [value]);

  const updateRows = useCallback(
    (newRows: Record<string, any>[]) => {
      setRows(newRows);
      onChange?.(newRows);
    },
    [onChange],
  );

  // Tree data integration
  const { visibleRows: treeRows, toggleExpand } = useTreeData(rows, treeConfig);
  const displayRows = treeConfig ? treeRows : rows;

  // DnD item IDs
  const dndItems = useMemo(() => displayRows.map((r) => getRowId(r)), [displayRows]);

  const isSortable = sortable && !disabled;

  const addRow = useCallback(() => {
    if (rows.length >= maxRows) return;
    const newRow: Record<string, any> = { _key: generateKey() };
    for (const col of columns) {
      newRow[col.field] = getDefaultValue(col.type);
    }
    if (sortField) {
      newRow[sortField] = (rows.length + 1) * 1000;
    }
    updateRows([...rows, newRow]);
    return newRow;
  }, [rows, maxRows, columns, updateRows, sortField]);

  const removeRow = useCallback(
    (rowIndex: number) => {
      if (rows.length <= minRows) return;
      const targetRow = displayRows[rowIndex];
      const targetId = getRowId(targetRow);
      updateRows(rows.filter((r) => getRowId(r) !== targetId));
    },
    [rows, minRows, updateRows, displayRows],
  );

  const updateCell = useCallback(
    (rowIndex: number, field: string, cellValue: any) => {
      const targetRow = displayRows[rowIndex];
      const targetId = getRowId(targetRow);
      updateRows(
        rows.map((row) => (getRowId(row) === targetId ? { ...row, [field]: cellValue } : row)),
      );
    },
    [rows, updateRows, displayRows],
  );

  // DnD drag end handler — local array reorder
  const handleDragEnd = useCallback(
    (activeId: string, overId: string, _deltaX: number) => {
      if (!isSortable) return;

      // Tree mode: check for cycle
      if (treeConfig && isDescendant(activeId, overId, treeRows)) return;

      // Reorder in the original rows array
      const reordered = [...rows];
      const movedIdx = reordered.findIndex((r) => getRowId(r) === activeId);
      const targetIdx = reordered.findIndex((r) => getRowId(r) === overId);
      if (movedIdx === -1 || targetIdx === -1) return;

      const [moved] = reordered.splice(movedIdx, 1);
      reordered.splice(targetIdx, 0, moved);

      updateRows(reordered.map((r, i) => ({ ...r, [sortField]: (i + 1) * 1000 })));
    },
    [isSortable, treeRows, treeConfig, rows, sortField, updateRows],
  );

  // Keyboard navigation
  const editableColIndices = useMemo(() => {
    return columns
      .map((col, idx) => ({ col, idx }))
      .filter(({ col }) => !disabled && col.editable !== false)
      .map(({ idx }) => idx);
  }, [columns, disabled]);

  const handleCellKeyDown = useCallback(
    (e: React.KeyboardEvent, rowIndex: number, colIndex: number) => {
      if (e.key === 'Escape') {
        (e.target as HTMLElement).blur();
        return;
      }
      if (e.key === 'Tab') {
        const ci = editableColIndices.indexOf(colIndex);
        if (ci === -1) return;
        if (e.shiftKey) {
          if (ci > 0) {
            e.preventDefault();
            focusCell(rowIndex, editableColIndices[ci - 1]);
          } else if (rowIndex > 0) {
            e.preventDefault();
            focusCell(rowIndex - 1, editableColIndices[editableColIndices.length - 1]);
          }
        } else {
          if (ci < editableColIndices.length - 1) {
            e.preventDefault();
            focusCell(rowIndex, editableColIndices[ci + 1]);
          } else if (rowIndex < displayRows.length - 1) {
            e.preventDefault();
            focusCell(rowIndex + 1, editableColIndices[0]);
          }
        }
        return;
      }
      if (e.key === 'Enter') {
        if ((e.target as HTMLElement).tagName === 'select') return;
        e.preventDefault();
        if (rowIndex < displayRows.length - 1) {
          focusCell(rowIndex + 1, colIndex);
        } else if (rows.length < maxRows) {
          addRow();
          requestAnimationFrame(() => focusCell(rowIndex + 1, colIndex));
        }
      }
    },
    [editableColIndices, displayRows.length, rows.length, maxRows, addRow],
  );

  const summaryValues = useMemo(() => computeSummary(rows, summary), [rows, summary]);

  // Render row content (cells + actions)
  const renderRowContent = (row: Record<string, any>, rowIndex: number) => (
    <>
      {columns.map((col, colIndex) => (
        <div
          key={col.field}
          className="flex-1 px-1 py-1"
          style={col.width ? { flex: `0 0 ${col.width}px` } : undefined}
        >
          <CellEditor
            column={col}
            value={row[col.field]}
            onChange={(val) => updateCell(rowIndex, col.field, val)}
            disabled={disabled || col.editable === false}
            rowIndex={rowIndex}
            colIndex={colIndex}
            onKeyDown={handleCellKeyDown}
          />
        </div>
      ))}
      {!disabled && (
        <div className="flex w-16 shrink-0 items-center justify-center px-1 py-1">
          <button
            onClick={() => removeRow(rowIndex)}
            disabled={rows.length <= minRows}
            data-testid={`subtable-remove-${rowIndex}`}
            className="p-0.5 text-gray-400 hover:text-red-500 disabled:opacity-30"
            title="删除行"
          >
            <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
              />
            </svg>
          </button>
        </div>
      )}
    </>
  );

  return (
    <div
      className={`overflow-hidden rounded-lg border border-gray-200 ${className}`}
      data-testid="subtable"
    >
      {/* Header */}
      <div className="flex border-b border-gray-200 bg-gray-50">
        {isSortable && <div className="w-10 shrink-0 px-2 py-2" />}
        {showIndex && !isSortable && (
          <div className="w-10 shrink-0 px-2 py-2 text-center text-[10px] font-medium text-gray-500">
            #
          </div>
        )}
        {columns.map((col) => (
          <div
            key={col.field}
            className="flex-1 px-2 py-2 text-xs font-medium text-gray-600"
            style={col.width ? { flex: `0 0 ${col.width}px` } : undefined}
          >
            {col.label}
            {col.required && <span className="ml-0.5 text-red-500">*</span>}
          </div>
        ))}
        {!disabled && (
          <div className="w-16 shrink-0 px-2 py-2 text-center text-xs font-medium text-gray-500">
            操作
          </div>
        )}
      </div>

      {/* Body */}
      <div className="divide-y divide-gray-100">
        {displayRows.length === 0 && (
          <div className="py-6 text-center text-xs text-gray-400">暂无数据，点击下方添加</div>
        )}
        {isSortable ? (
          <DndSubTableWrapper items={dndItems} onDragEnd={handleDragEnd}>
            {displayRows.map((row, rowIndex) => (
              <SortableRow
                key={getRowId(row)}
                id={getRowId(row)}
                depth={row._depth || 0}
                hasChildren={row._hasChildren || false}
                expanded={row._expanded || false}
                onToggleExpand={() => toggleExpand(getRowId(row))}
              >
                {renderRowContent(row, rowIndex)}
              </SortableRow>
            ))}
          </DndSubTableWrapper>
        ) : (
          displayRows.map((row, rowIndex) => (
            <div
              key={getRowId(row)}
              data-testid={`subtable-row-${rowIndex}`}
              className="flex items-center hover:bg-gray-50/50"
            >
              {showIndex && (
                <div className="w-10 shrink-0 px-2 py-1.5 text-center text-[10px] text-gray-400">
                  {rowIndex + 1}
                </div>
              )}
              {renderRowContent(row, rowIndex)}
            </div>
          ))
        )}
      </div>

      {/* Summary footer */}
      {summaryValues.length > 0 && rows.length > 0 && (
        <div className="flex border-t border-gray-200 bg-gray-50" data-testid="subtable-summary">
          <div className="w-10 shrink-0 px-2 py-2" />
          {columns.map((col, colIndex) => {
            const sv = summaryValues.find((s) => s.field === col.field);
            const isFirstCol = colIndex === 0;
            const hasAggInFirst = summaryValues.some((s) => s.field === columns[0]?.field);
            return (
              <div
                key={col.field}
                className="flex-1 px-2 py-2 text-xs font-semibold"
                style={col.width ? { flex: `0 0 ${col.width}px` } : undefined}
              >
                {sv ? (
                  <span className="text-gray-900">
                    {sv.label && <span className="mr-1 text-gray-500">{sv.label}:</span>}
                    {formatNumber(sv.value)}
                  </span>
                ) : isFirstCol && !hasAggInFirst ? (
                  <span className="text-gray-500">合计</span>
                ) : null}
              </div>
            );
          })}
          {!disabled && <div className="w-16 shrink-0 px-2 py-2" />}
        </div>
      )}

      {/* Add row */}
      {!disabled && rows.length < maxRows && (
        <div className="border-t border-gray-200">
          <button
            onClick={addRow}
            data-testid="subtable-add-row"
            className="w-full py-2 text-xs text-gray-400 transition-colors hover:bg-gray-50 hover:text-blue-500"
          >
            {addLabel}
          </button>
        </div>
      )}
    </div>
  );
};

/**
 * SortableRow — div-based sortable row for SubTable (flex layout).
 * Uses useSortable from @dnd-kit. Renders drag handle + tree indent.
 */
const SortableRow: React.FC<{
  id: string;
  depth?: number;
  hasChildren?: boolean;
  expanded?: boolean;
  onToggleExpand?: () => void;
  children: React.ReactNode;
}> = ({ id, depth = 0, hasChildren = false, expanded = false, onToggleExpand, children }) => {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id,
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`flex items-center hover:bg-gray-50/50 ${isDragging ? 'bg-blue-50' : ''}`}
      data-testid={`sortable-row-${id}`}
    >
      {/* Drag handle + tree indent */}
      <div
        className="flex w-10 shrink-0 items-center gap-0.5 px-1"
        style={{ paddingLeft: depth * INDENT_WIDTH + 4 }}
      >
        {hasChildren ? (
          <button
            type="button"
            onClick={onToggleExpand}
            className="flex h-3.5 w-3.5 items-center justify-center text-[10px] text-gray-400 hover:text-gray-600"
            data-testid={`tree-toggle-${id}`}
          >
            {expanded ? '▼' : '▶'}
          </button>
        ) : (
          <span className="w-3.5" />
        )}
        <button
          type="button"
          className="cursor-grab text-xs text-gray-300 hover:text-gray-500"
          {...attributes}
          {...listeners}
          data-testid={`drag-handle-${id}`}
        >
          ⠿
        </button>
      </div>
      {children}
    </div>
  );
};

function getRowId(row: Record<string, any>): string {
  return row.pid || row._key || String(row.id || '');
}

function focusCell(rowIndex: number, colIndex: number): void {
  const el = document.querySelector<HTMLElement>(`[data-cell="row-${rowIndex}-col-${colIndex}"]`);
  if (el) el.focus();
}

const CellEditor: React.FC<{
  column: SubTableColumn;
  value: any;
  onChange: (value: any) => void;
  disabled: boolean;
  rowIndex: number;
  colIndex: number;
  onKeyDown: (e: React.KeyboardEvent, rowIndex: number, colIndex: number) => void;
}> = ({ column, value, onChange, disabled, rowIndex, colIndex, onKeyDown }) => {
  const baseClass =
    'w-full px-2 py-1 text-xs border border-transparent rounded focus:border-blue-300 focus:outline-none bg-transparent hover:bg-white hover:border-gray-200';
  const cellId = `row-${rowIndex}-col-${colIndex}`;
  const handleKeyDown = (e: React.KeyboardEvent) => onKeyDown(e, rowIndex, colIndex);

  switch (column.type) {
    case 'number':
      return (
        <input
          type="number"
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}
          placeholder={column.placeholder ?? ''}
          disabled={disabled}
          className={baseClass}
          data-cell={cellId}
          onKeyDown={handleKeyDown}
        />
      );
    case 'select':
      return (
        <select
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
          className={`${baseClass} cursor-pointer`}
          data-cell={cellId}
          onKeyDown={handleKeyDown}
        >
          <option value="">{column.placeholder ?? '选择'}</option>
          {column.options?.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      );
    case 'date':
      return (
        <input
          type="date"
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
          className={baseClass}
          data-cell={cellId}
          onKeyDown={handleKeyDown}
        />
      );
    case 'boolean':
      return (
        <div className="flex items-center justify-center">
          <input
            type="checkbox"
            checked={!!value}
            onChange={(e) => onChange(e.target.checked)}
            disabled={disabled}
            className="rounded text-blue-500"
            data-cell={cellId}
            onKeyDown={handleKeyDown}
          />
        </div>
      );
    default:
      return (
        <input
          type="text"
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
          placeholder={column.placeholder ?? ''}
          disabled={disabled}
          className={baseClass}
          data-cell={cellId}
          onKeyDown={handleKeyDown}
        />
      );
  }
};

function computeSummary(
  rows: Record<string, any>[],
  summary?: SubTableSummaryConfig,
): Array<{ field: string; value: number; label?: string }> {
  if (!summary?.fields || rows.length === 0) return [];
  return summary.fields.map((sf) => {
    const values = rows.map((r) => Number(r[sf.field]) || 0);
    let value = 0;
    switch (sf.aggregation) {
      case 'sum':
        value = values.reduce((a, b) => a + b, 0);
        break;
      case 'avg':
        value = values.reduce((a, b) => a + b, 0) / values.length;
        break;
      case 'count':
        value = values.length;
        break;
      case 'min':
        value = Math.min(...values);
        break;
      case 'max':
        value = Math.max(...values);
        break;
    }
    return { field: sf.field, value, label: sf.label };
  });
}

function formatNumber(num: number): string {
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
}

function generateKey(): string {
  return `row_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
}

function getDefaultValue(type: string): any {
  switch (type) {
    case 'number':
      return null;
    case 'boolean':
      return false;
    case 'date':
      return '';
    default:
      return '';
  }
}
