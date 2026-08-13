/**
 * SelectAllMatchingBanner — cross-page "select all N matching" affordance (T9).
 *
 * Sits between the toolbar and the table. The header checkbox only selects the
 * current page; once the whole page is selected and more matching records exist
 * beyond it, this banner offers to extend the selection to the entire filtered
 * set. While in all-matching mode it summarises the count and offers to clear.
 *
 * Design-system tokenized (semantic accent/text tokens, no hardcoded colors)
 * and fully i18n'd (no hardcoded user-facing strings).
 */
import React from 'react';

export interface SelectAllMatchingBannerProps {
  /** Whether row selection is enabled for this page at all. */
  enabled: boolean;
  /** Every loaded row on the current page is selected. */
  pageFullySelected: boolean;
  /** The user opted into "select all N matching". */
  allMatchingSelected: boolean;
  /** Count selected on the current page (explicit mode). */
  pageSelectedCount: number;
  /** Total records matching the current filter (server total). */
  total: number;
  /** Extend the selection to every matching record. */
  onSelectAllMatching: () => void;
  /** Drop the selection entirely. */
  onClearSelection: () => void;
  /** i18n translator (key, params, fallback). */
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
  /** Active UI locale, used to keep fallbacks consistent when a key is absent. */
  locale: string;
}

export function SelectAllMatchingBanner({
  enabled,
  pageFullySelected,
  allMatchingSelected,
  pageSelectedCount,
  total,
  onSelectAllMatching,
  onClearSelection,
  t,
  locale,
}: SelectAllMatchingBannerProps) {
  if (!enabled) return null;
  const isZhLocale = locale.toLowerCase().startsWith('zh');

  // All-matching mode: summarise the full-set selection + offer to clear.
  if (allMatchingSelected) {
    return (
      <div
        className="bg-accent-weak text-accent flex flex-wrap items-center justify-center gap-2 px-4 py-2 text-xs"
        data-testid="select-all-matching-banner"
        role="status"
      >
        <span data-testid="select-all-matching-summary">
          {t(
            'list.select.allMatchingSelected',
            { count: total },
            isZhLocale ? `已选择全部 ${total} 条记录` : `All ${total} records selected`,
          )}
        </span>
        <span className="text-text-2" data-testid="select-all-matching-safety-note">
          {t(
            'list.select.allMatchingExportOnly',
            undefined,
            isZhLocale
              ? '导出将覆盖全部匹配记录；写操作仍需逐条明确选择'
              : 'Export applies to all matching records; write actions require explicit selection',
          )}
        </span>
        <button
          type="button"
          onClick={onClearSelection}
          className="focus-visible:shadow-focus rounded-card font-medium underline underline-offset-2 hover:no-underline focus:outline-none"
          data-testid="select-all-matching-clear"
        >
          {t('list.select.clearSelection', undefined, isZhLocale ? '清除选择' : 'Clear selection')}
        </button>
      </div>
    );
  }

  // Page is fully selected and more matching records exist beyond this page —
  // offer to extend the selection to the whole filtered set.
  const hasMoreBeyondPage = total > pageSelectedCount;
  if (!pageFullySelected || !hasMoreBeyondPage) return null;

  return (
    <div
      className="bg-subtle text-text-2 flex flex-wrap items-center justify-center gap-2 px-4 py-2 text-xs"
      data-testid="select-all-matching-banner"
      role="status"
    >
      <span data-testid="select-all-matching-summary">
        {t(
          'list.select.pageSelected',
          { count: pageSelectedCount },
          isZhLocale
            ? `已选择本页 ${pageSelectedCount} 条记录`
            : `${pageSelectedCount} on this page selected`,
        )}
      </span>
      <button
        type="button"
        onClick={onSelectAllMatching}
        className="text-accent focus-visible:shadow-focus rounded-card font-medium underline underline-offset-2 hover:no-underline focus:outline-none"
        data-testid="select-all-matching-action"
      >
        {t(
          'list.select.selectAllMatching',
          { count: total },
          isZhLocale ? `选择全部 ${total} 条匹配记录` : `Select all ${total} matching`,
        )}
      </button>
    </div>
  );
}
