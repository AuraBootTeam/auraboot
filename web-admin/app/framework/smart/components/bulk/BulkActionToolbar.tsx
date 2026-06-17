/**
 * BulkActionToolbar Component
 *
 * Floating toolbar that appears when rows are selected in a table.
 * Provides bulk actions like edit, delete, and export.
 */

import React, { useCallback, useState } from 'react';
import { cn } from '~/utils/cn';
import { confirmDialog } from '~/utils/confirmDialog';
import type { ButtonConfig } from '~/framework/meta/schemas/types';

export interface BulkActionToolbarProps {
  /** Number of selected items */
  selectedCount: number;
  /** IDs of selected records */
  selectedIds: string[];
  /** Model code for the data source */
  modelCode: string;
  /** Callback for bulk edit action */
  onBulkEdit?: () => void;
  /** Callback for bulk delete action */
  onBulkDelete?: (ids: string[]) => Promise<void>;
  /** Callback for bulk export action */
  onBulkExport?: (ids: string[]) => void;
  /** DSL-configured business bulk actions */
  bulkActions?: ButtonConfig[];
  /** Callback for DSL-configured business bulk actions */
  onBulkAction?: (button: ButtonConfig, ids: string[]) => void | Promise<void>;
  /** Label resolver for DSL-configured business bulk actions */
  resolveActionLabel?: (button: ButtonConfig) => string;
  /** Callback to clear selection */
  onClearSelection?: () => void;
  /** Custom CSS class */
  className?: string;
  /** i18n translator (key, params, fallback). Defaults to the fallback. */
  t?: (key: string, params?: Record<string, any>, fallback?: string) => string;
}

/**
 * BulkActionToolbar - Floating toolbar for bulk operations
 */
export const BulkActionToolbar: React.FC<BulkActionToolbarProps> = ({
  selectedCount,
  selectedIds,
  onBulkEdit,
  onBulkDelete,
  onBulkExport,
  bulkActions = [],
  onBulkAction,
  resolveActionLabel,
  onClearSelection,
  className,
  t,
}) => {
  const [deleting, setDeleting] = useState(false);
  const [runningActionCode, setRunningActionCode] = useState<string | null>(null);
  const tr = useCallback(
    (key: string, fallback: string, params?: Record<string, any>) =>
      t ? t(key, params, fallback) : fallback,
    [t],
  );

  const handleBulkDelete = useCallback(async () => {
    if (!onBulkDelete || selectedIds.length === 0) return;

    const confirmed = await confirmDialog({
      content: tr(
        'list.bulk.deleteConfirm',
        `Are you sure you want to delete ${selectedIds.length} selected records? This action cannot be undone.`,
      ),
      variant: 'danger',
    });
    if (!confirmed) return;

    setDeleting(true);
    try {
      await onBulkDelete(selectedIds);
    } finally {
      setDeleting(false);
    }
  }, [onBulkDelete, selectedIds, tr]);

  const handleCustomBulkAction = useCallback(
    async (button: ButtonConfig) => {
      if (!onBulkAction || selectedIds.length === 0) return;
      setRunningActionCode(button.code);
      try {
        await onBulkAction(button, selectedIds);
      } finally {
        setRunningActionCode(null);
      }
    },
    [onBulkAction, selectedIds],
  );

  if (selectedCount === 0) return null;

  return (
    <div
      className={cn(
        'fixed bottom-6 left-1/2 z-40 -translate-x-1/2',
        'rounded-lg bg-gray-900 px-4 py-3 text-white shadow-xl',
        'flex items-center gap-4',
        'animate-in slide-in-from-bottom duration-200',
        className,
      )}
    >
      {/* Selected count */}
      <div className="flex items-center gap-2 text-sm">
        <div className="flex h-6 w-6 items-center justify-center rounded-full bg-blue-500 text-xs font-bold">
          {selectedCount}
        </div>
        <span className="text-gray-300">{tr('list.bulk.selected', 'selected')}</span>
      </div>

      {/* Divider */}
      <div className="h-6 w-px bg-gray-700" />

      {/* Actions */}
      <div className="flex items-center gap-2">
        {/* DSL-configured business bulk actions */}
        {bulkActions.map((button) => {
          const label = resolveActionLabel
            ? resolveActionLabel(button)
            : String(button.label ?? button.code);
          const isRunning = runningActionCode === button.code;
          const isDanger = button.danger || button.variant === 'danger';
          return (
            <button
              key={button.code}
              type="button"
              onClick={() => handleCustomBulkAction(button)}
              disabled={isRunning}
              className={cn(
                'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors',
                isDanger ? 'text-red-400 hover:bg-red-900/50' : 'hover:bg-gray-700',
                'disabled:cursor-not-allowed disabled:opacity-50',
              )}
            >
              {isRunning && (
                <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
              )}
              {label}
            </button>
          );
        })}

        {/* Bulk Edit */}
        {onBulkEdit && (
          <button
            type="button"
            onClick={onBulkEdit}
            className="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors hover:bg-gray-700"
            data-testid="bulk-edit-btn"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
              />
            </svg>
            {tr('common.edit', 'Edit')}
          </button>
        )}

        {/* Bulk Delete */}
        {onBulkDelete && (
          <button
            type="button"
            onClick={handleBulkDelete}
            disabled={deleting}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors',
              'text-red-400 hover:bg-red-900/50',
              'disabled:cursor-not-allowed disabled:opacity-50',
            )}
            data-testid="bulk-delete-btn"
          >
            {deleting ? (
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-red-400/30 border-t-red-400" />
            ) : (
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                />
              </svg>
            )}
            {tr('common.delete', 'Delete')}
          </button>
        )}

        {/* Bulk Export — exports only the selected records (T9). */}
        {onBulkExport && (
          <button
            type="button"
            onClick={() => onBulkExport(selectedIds)}
            className="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors hover:bg-gray-700"
            data-testid="bulk-export-selected-btn"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
              />
            </svg>
            {tr('list.bulk.exportSelected', 'Export selected')}
          </button>
        )}
      </div>

      {/* Divider */}
      <div className="h-6 w-px bg-gray-700" />

      {/* Clear Selection */}
      <button
        type="button"
        onClick={onClearSelection}
        className="rounded-md p-1.5 text-gray-400 transition-colors hover:bg-gray-700 hover:text-white"
        title={tr('list.bulk.clearSelection', 'Clear selection')}
        data-testid="bulk-clear-selection-btn"
      >
        <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M6 18L18 6M6 6l12 12"
          />
        </svg>
      </button>
    </div>
  );
};

export default BulkActionToolbar;
