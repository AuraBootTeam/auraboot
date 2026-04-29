/**
 * ColumnSettingsPanel - Runtime column configuration panel
 *
 * Allows users to toggle column visibility, reorder columns via drag,
 * and adjust column widths. Changes are saved to the current SavedView.
 */

import React, { useState, useCallback, useEffect, useRef } from 'react';
import type { ColumnConfig as ViewColumnConfig } from '~/framework/smart/types/savedView';
import { cn } from '~/utils/cn';

interface ColumnDef {
  field: string;
  label: string;
}

export interface ColumnSettingsPanelProps {
  /** All available columns from DSL schema */
  allColumns: ColumnDef[];
  /** Current column config from SavedView (may be empty) */
  viewColumns?: ViewColumnConfig[];
  /** Callback when column config changes */
  onSave: (columns: ViewColumnConfig[]) => void;
  /** Whether the panel is open */
  open: boolean;
  /** Close the panel */
  onClose: () => void;
  /** i18n function */
  t?: (key: string) => string;
}

export const ColumnSettingsPanel: React.FC<ColumnSettingsPanelProps> = ({
  allColumns,
  viewColumns,
  onSave,
  open,
  onClose,
  t = (k) => k,
}) => {
  // Build initial state from viewColumns or allColumns
  const [columns, setColumns] = useState<Array<ViewColumnConfig & { label: string }>>([]);
  const dragItem = useRef<number | null>(null);
  const dragOverItem = useRef<number | null>(null);

  useEffect(() => {
    if (!open) return;
    const viewColMap = new Map((viewColumns || []).map((vc) => [vc.fieldCode, vc]));
    const sysFields = new Set(['created_at', 'updated_at', 'created_by', 'updated_by']);
    const ordered = allColumns.map((col, idx) => {
      const vc = viewColMap.get(col.field);
      const isSys = sysFields.has(col.field);
      return {
        fieldCode: col.field,
        label: col.label,
        visible: isSys ? vc?.visible === true : vc?.visible !== false,
        width: vc?.width,
        order: vc?.order ?? (isSys ? 900 + idx : idx),
      };
    });
    ordered.sort((a, b) => a.order - b.order);
    setColumns(ordered);
  }, [open, allColumns, viewColumns]);

  const toggleVisibility = useCallback((fieldCode: string) => {
    setColumns((prev) =>
      prev.map((col) => (col.fieldCode === fieldCode ? { ...col, visible: !col.visible } : col)),
    );
  }, []);

  const updateWidth = useCallback((fieldCode: string, width: string) => {
    const numWidth = width ? parseInt(width, 10) : undefined;
    setColumns((prev) =>
      prev.map((col) => (col.fieldCode === fieldCode ? { ...col, width: numWidth } : col)),
    );
  }, []);

  const handleDragStart = useCallback((index: number) => {
    dragItem.current = index;
  }, []);

  const handleDragEnter = useCallback((index: number) => {
    dragOverItem.current = index;
  }, []);

  const handleDragEnd = useCallback(() => {
    if (dragItem.current === null || dragOverItem.current === null) return;
    const from = dragItem.current;
    const to = dragOverItem.current;
    if (from === to) return;

    setColumns((prev) => {
      const next = [...prev];
      const [removed] = next.splice(from, 1);
      next.splice(to, 0, removed);
      return next;
    });
    dragItem.current = null;
    dragOverItem.current = null;
  }, []);

  const handleSave = useCallback(() => {
    const result: ViewColumnConfig[] = columns.map((col, idx) => ({
      fieldCode: col.fieldCode,
      visible: col.visible,
      width: col.width,
      order: idx,
    }));
    onSave(result);
    onClose();
  }, [columns, onSave, onClose]);

  const handleSelectAll = useCallback(() => {
    setColumns((prev) => prev.map((col) => ({ ...col, visible: true })));
  }, []);

  const handleDeselectAll = useCallback(() => {
    setColumns((prev) => prev.map((col) => ({ ...col, visible: false })));
  }, []);

  if (!open) return null;

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-40 bg-black/20" onClick={onClose} />

      {/* Panel */}
      <div
        data-testid="column-settings-panel"
        className="fixed top-0 right-0 z-50 flex h-full w-80 flex-col bg-white shadow-xl"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-4 py-3">
          <h3 className="text-sm font-semibold text-gray-900">
            {t('view.columnSettings') !== 'view.columnSettings'
              ? t('view.columnSettings')
              : 'Column Settings'}
          </h3>
          <button type="button" onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Quick actions */}
        <div className="flex gap-2 border-b border-gray-100 px-4 py-2">
          <button
            type="button"
            onClick={handleSelectAll}
            className="text-xs text-blue-600 hover:text-blue-800"
          >
            Select All
          </button>
          <span className="text-gray-300">|</span>
          <button
            type="button"
            onClick={handleDeselectAll}
            className="text-xs text-blue-600 hover:text-blue-800"
          >
            Deselect All
          </button>
        </div>

        {/* Column list */}
        <div className="flex-1 overflow-y-auto px-2 py-2">
          {(() => {
            const sysFields = new Set(['created_at', 'updated_at', 'created_by', 'updated_by']);
            const businessCols = columns.filter((c) => !sysFields.has(c.fieldCode));
            const systemCols = columns.filter((c) => sysFields.has(c.fieldCode));

            const renderColumn = (col: (typeof columns)[0], index: number) => (
              <div
                key={col.fieldCode}
                data-testid={`column-settings-row-${col.fieldCode}`}
                draggable
                onDragStart={() => handleDragStart(columns.indexOf(col))}
                onDragEnter={() => handleDragEnter(columns.indexOf(col))}
                onDragEnd={handleDragEnd}
                onDragOver={(e) => e.preventDefault()}
                className={cn(
                  'mb-1 flex cursor-grab items-center gap-2 rounded-md px-2 py-2',
                  'hover:bg-gray-50 active:bg-gray-100',
                  'transition-colors duration-100',
                )}
              >
                <svg
                  className="h-4 w-4 flex-shrink-0 text-gray-300"
                  fill="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path d="M8 6h2v2H8V6zm6 0h2v2h-2V6zM8 11h2v2H8v-2zm6 0h2v2h-2v-2zm-6 5h2v2H8v-2zm6 0h2v2h-2v-2z" />
                </svg>
                <input
                  type="checkbox"
                  checked={col.visible}
                  onChange={() => toggleVisibility(col.fieldCode)}
                  className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                  data-testid={`column-settings-visible-${col.fieldCode}`}
                />
                <span
                  className={cn(
                    'flex-1 truncate text-sm',
                    col.visible ? 'text-gray-900' : 'text-gray-400 line-through',
                  )}
                >
                  {col.label}
                </span>
                <input
                  type="number"
                  value={col.width ?? ''}
                  onChange={(e) => updateWidth(col.fieldCode, e.target.value)}
                  placeholder="px"
                  className="w-16 rounded border border-gray-200 px-1.5 py-1 text-right text-xs"
                  min={50}
                  max={800}
                  data-testid={`column-settings-width-${col.fieldCode}`}
                />
              </div>
            );

            return (
              <>
                {businessCols.map((col, idx) => renderColumn(col, idx))}
                {systemCols.length > 0 && (
                  <>
                    <div className="mt-3 mb-1 flex items-center gap-2 px-2">
                      <div className="h-px flex-1 bg-gray-200" />
                      <span className="text-[10px] font-medium tracking-wide text-gray-400 uppercase">
                        System Fields
                      </span>
                      <div className="h-px flex-1 bg-gray-200" />
                    </div>
                    {systemCols.map((col, idx) => renderColumn(col, idx))}
                  </>
                )}
              </>
            );
          })()}
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-2 border-t border-gray-200 px-4 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
            data-testid="column-settings-cancel"
          >
            {t('action.cancel') !== 'action.cancel' ? t('action.cancel') : 'Cancel'}
          </button>
          <button
            type="button"
            onClick={handleSave}
            className="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
            data-testid="column-settings-save"
          >
            {t('action.save') !== 'action.save' ? t('action.save') : 'Save'}
          </button>
        </div>
      </div>
    </>
  );
};

export default ColumnSettingsPanel;
