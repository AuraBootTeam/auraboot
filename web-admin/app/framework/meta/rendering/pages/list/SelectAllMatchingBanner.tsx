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
}: SelectAllMatchingBannerProps) {
  if (!enabled) return null;

  // All-matching mode: summarise the full-set selection + offer to clear.
  if (allMatchingSelected) {
    return (
      <div
        className="bg-accent-weak text-accent flex flex-wrap items-center justify-center gap-2 px-4 py-2 text-xs"
        data-testid="select-all-matching-banner"
        role="status"
      >
        <span data-testid="select-all-matching-summary">
          {t('list.select.allMatchingSelected', { count: total }, `All ${total} records selected`)}
        </span>
        <button
          type="button"
          onClick={onClearSelection}
          className="focus-visible:shadow-focus rounded-card font-medium underline underline-offset-2 hover:no-underline focus:outline-none"
          data-testid="select-all-matching-clear"
        >
          {t('list.select.clearSelection', undefined, 'Clear selection')}
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
          `${pageSelectedCount} on this page selected`,
        )}
      </span>
      <button
        type="button"
        onClick={onSelectAllMatching}
        className="text-accent focus-visible:shadow-focus rounded-card font-medium underline underline-offset-2 hover:no-underline focus:outline-none"
        data-testid="select-all-matching-action"
      >
        {t('list.select.selectAllMatching', { count: total }, `Select all ${total} matching`)}
      </button>
    </div>
  );
}
