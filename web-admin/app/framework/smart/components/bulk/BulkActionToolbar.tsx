/**
 * BulkActionToolbar Component
 *
 * Floating toolbar that appears when rows are selected in a table.
 * Provides bulk actions like edit, delete, and export.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
  /** Active locale, used for safe fallbacks when an i18n key is unavailable. */
  locale?: string;
  /** i18n translator (key, params, fallback). Defaults to the fallback. */
  t?: (key: string, params?: Record<string, any>, fallback?: string) => string;
}

const MAX_INLINE_ACTIONS = 3;

/**
 * Keep frequent, non-destructive actions scannable while protecting the
 * floating toolbar from growing wider than the page. Destructive actions are
 * intentionally relegated to the overflow menu.
 */
export function partitionBulkActions(
  bulkActions: ButtonConfig[],
  hasBulkEdit: boolean,
): { inlineActions: ButtonConfig[]; overflowActions: ButtonConfig[] } {
  const customActionCapacity = Math.max(0, MAX_INLINE_ACTIONS - (hasBulkEdit ? 1 : 0));
  const inlineActions = bulkActions
    .filter((button) => !button.danger && button.variant !== 'danger')
    .slice(0, customActionCapacity);
  const inlineCodes = new Set(inlineActions.map((button) => button.code));

  return {
    inlineActions,
    overflowActions: bulkActions.filter((button) => !inlineCodes.has(button.code)),
  };
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
  locale = 'en-US',
  t,
}) => {
  const [deleting, setDeleting] = useState(false);
  const [runningActionCode, setRunningActionCode] = useState<string | null>(null);
  const [moreOpen, setMoreOpen] = useState(false);
  const moreMenuRef = useRef<HTMLDivElement>(null);
  const isZh = locale.toLowerCase().startsWith('zh');
  const tr = useCallback(
    (key: string, zh: string, en: string, params?: Record<string, any>) => {
      const translated = t?.(key, params);
      return translated && translated !== key ? translated : isZh ? zh : en;
    },
    [isZh, t],
  );
  const { inlineActions, overflowActions } = useMemo(
    () => partitionBulkActions(bulkActions, Boolean(onBulkEdit)),
    [bulkActions, onBulkEdit],
  );
  const hasBuiltInOverflowActions = Boolean(onBulkDelete || onBulkExport);
  const showMoreButton = overflowActions.length > 0 || hasBuiltInOverflowActions;

  useEffect(() => {
    if (!moreOpen) return;

    const handlePointerDown = (event: MouseEvent) => {
      if (moreMenuRef.current && !moreMenuRef.current.contains(event.target as Node)) {
        setMoreOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMoreOpen(false);
    };

    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [moreOpen]);

  const handleBulkDelete = useCallback(async () => {
    if (!onBulkDelete || selectedIds.length === 0) return;

    setMoreOpen(false);

    const confirmed = await confirmDialog({
      content: tr(
        'list.bulk.deleteConfirm',
        `确定删除已选择的 ${selectedIds.length} 条记录吗？此操作无法撤销。`,
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
      setMoreOpen(false);
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
        'rounded-card bg-inverse text-inverse-text px-4 py-3 shadow-xl',
        'flex max-w-[calc(100vw-2rem)] items-center gap-3 whitespace-nowrap',
        'animate-in slide-in-from-bottom duration-200',
        className,
      )}
    >
      {/* Selected count */}
      <div className="flex shrink-0 items-center gap-2 text-sm">
        <div className="bg-accent flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold">
          {selectedCount}
        </div>
        <span className="text-inverse-muted">{tr('list.bulk.selected', '已选择', 'selected')}</span>
      </div>

      {/* Divider */}
      <div className="bg-inverse-border h-6 w-px shrink-0" />

      {/* Actions */}
      <div className="flex min-w-0 items-center gap-1.5">
        {/* DSL-configured business bulk actions */}
        {inlineActions.map((button) => {
          const label = resolveActionLabel
            ? resolveActionLabel(button)
            : String(button.label ?? button.code);
          const isRunning = runningActionCode === button.code;
          const isDanger = button.danger || button.variant === 'danger';
          return (
            <button
              key={button.code}
              type="button"
              data-testid={`bulk-action-${button.code}`}
              onClick={() => handleCustomBulkAction(button)}
              disabled={isRunning}
              className={cn(
                'rounded-control inline-flex shrink-0 items-center gap-1.5 px-3 py-1.5 text-sm transition-colors',
                isDanger ? 'text-status-red hover:bg-inverse-hover' : 'hover:bg-inverse-hover',
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
            className="rounded-control hover:bg-inverse-hover inline-flex shrink-0 items-center gap-1.5 px-3 py-1.5 text-sm transition-colors"
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
            {tr('common.edit', '编辑', 'Edit')}
          </button>
        )}

        {/* Secondary and destructive actions live in a compact overflow menu. */}
        {showMoreButton && (
          <div ref={moreMenuRef} className="relative shrink-0">
            <button
              type="button"
              data-testid="bulk-more-actions-btn"
              onClick={() => setMoreOpen((open) => !open)}
              aria-haspopup="menu"
              aria-expanded={moreOpen}
              className={cn(
                'rounded-control hover:bg-inverse-hover inline-flex items-center gap-1.5 px-3 py-1.5 text-sm transition-colors',
                'focus-visible:shadow-focus focus:outline-none',
              )}
            >
              <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
                <path d="M4 12a2 2 0 110-4 2 2 0 010 4zm6 0a2 2 0 110-4 2 2 0 010 4zm6 0a2 2 0 110-4 2 2 0 010 4z" />
              </svg>
              {tr('action.more', '更多', 'More')}
            </button>

            {moreOpen && (
              <div
                role="menu"
                data-testid="bulk-more-actions-menu"
                className="rounded-control border-border bg-panel absolute right-0 bottom-full z-50 mb-2 w-56 overflow-hidden border py-1 text-left shadow-xl"
              >
                {overflowActions.map((button) => {
                  const label = resolveActionLabel
                    ? resolveActionLabel(button)
                    : String(button.label ?? button.code);
                  const isRunning = runningActionCode === button.code;
                  const isDanger = button.danger || button.variant === 'danger';
                  return (
                    <button
                      key={button.code}
                      type="button"
                      role="menuitem"
                      data-testid={`bulk-action-${button.code}`}
                      onClick={() => handleCustomBulkAction(button)}
                      disabled={isRunning}
                      className={cn(
                        'flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm transition-colors',
                        isDanger
                          ? 'text-status-red hover:bg-status-red-bg'
                          : 'text-text-2 hover:bg-hover',
                        'disabled:cursor-not-allowed disabled:opacity-50',
                      )}
                    >
                      {isRunning && (
                        <span className="border-border-strong h-4 w-4 animate-spin rounded-full border-2 border-t-blue-500" />
                      )}
                      {label}
                    </button>
                  );
                })}

                {overflowActions.length > 0 && hasBuiltInOverflowActions && (
                  <div className="bg-border mx-2 my-1 h-px" />
                )}

                {/* Bulk Export — exports only the selected records (T9). */}
                {onBulkExport && (
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      setMoreOpen(false);
                      onBulkExport(selectedIds);
                    }}
                    className="text-text-2 hover:bg-hover flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm transition-colors"
                    data-testid="bulk-export-selected-btn"
                  >
                    <svg
                      className="text-text-3 h-4 w-4"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                      />
                    </svg>
                    {tr('list.bulk.exportSelected', '导出所选记录', 'Export selected')}
                  </button>
                )}

                {onBulkDelete && (
                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleBulkDelete}
                    disabled={deleting}
                    className={cn(
                      'text-status-red hover:bg-status-red-bg flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm transition-colors',
                      'disabled:cursor-not-allowed disabled:opacity-50',
                    )}
                    data-testid="bulk-delete-btn"
                  >
                    {deleting ? (
                      <span className="h-4 w-4 animate-spin rounded-full border-2 border-red-400/30 border-t-red-400" />
                    ) : (
                      <svg
                        className="h-4 w-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                        />
                      </svg>
                    )}
                    {tr('common.delete', '删除', 'Delete')}
                  </button>
                )}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Divider */}
      <div className="bg-inverse-border h-6 w-px shrink-0" />

      {/* Clear Selection */}
      <button
        type="button"
        onClick={onClearSelection}
        className="rounded-control text-inverse-muted hover:bg-inverse-hover hover:text-inverse-text shrink-0 p-1.5 transition-colors"
        title={tr('list.bulk.clearSelection', '清除选择', 'Clear selection')}
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
