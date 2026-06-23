/**
 * ListToolbar — Unified toolbar for list pages.
 *
 * Layout: [SearchInput] | [Sort popover trigger] [Fields btn] ... [QuickFilters] [RowHeightSelector]
 * Below:  <FilterChipBar .../>
 */
import React, { useCallback } from 'react';
import { RowHeightSelector } from '~/framework/smart/components/view/RowHeightSelector';
import { FilterChipBar } from '~/framework/smart/components/view/FilterChipBar';
import type { SortConfig, ViewFilterConfig, RowHeight } from '~/framework/smart/types/savedView';
import { SortPopover, type SortableColumn } from './SortPopover';
import { useI18n } from '~/contexts/I18nContext';
import {
  type QuickFilterPresetKey,
  getQuickFilterPresetDefinitions,
} from './quickFilterPresets';

type QuickFilterKey = QuickFilterPresetKey;

export interface ListToolbarProps {
  /** Current keyword search value */
  keyword: string;
  onKeywordChange: (keyword: string) => void;
  onSearch: () => void;

  /** Quick filters */
  activeQuickFilter: QuickFilterKey | null;
  onQuickFilter: (key: QuickFilterKey) => void;

  /** Sort */
  activeSorts: SortConfig[];
  onSortsChange: (sorts: SortConfig[]) => void;
  sortableColumns: SortableColumn[];

  /** Row height */
  rowHeight?: RowHeight;
  onRowHeightChange: (height: RowHeight) => void;

  /** Column settings */
  onColumnSettingsOpen: () => void;

  /** Filter chip bar */
  chipFilters: ViewFilterConfig[];
  onChipFiltersChange: (filters: ViewFilterConfig[]) => void;
  fieldMetadata: Array<{
    fieldCode: string;
    label: string;
    fieldType: string;
    dictCode?: string;
  }>;
  onAddFilter: (e?: React.MouseEvent) => void;
  onChipClick: (idx: number, e: React.MouseEvent) => void;
  onClearAll: () => void;

  /** Filter form toggle */
  filterFormVisible?: boolean;
  onFilterFormToggle?: () => void;
  /** Whether a filter block exists in DSL */
  hasFilterBlock?: boolean;
  /** Resolve a filter's value to a localized label (e.g. dict code → label). */
  resolveChipValueLabel?: (filter: ViewFilterConfig) => string | undefined;
  hideQuickFilters?: boolean;
  hideSort?: boolean;
  hideColumnSettings?: boolean;
  hideRowHeight?: boolean;
  hideFilterChips?: boolean;
}

export function ListToolbar({
  keyword,
  onKeywordChange,
  onSearch,
  activeQuickFilter,
  onQuickFilter,
  activeSorts,
  onSortsChange,
  sortableColumns,
  rowHeight,
  onRowHeightChange,
  onColumnSettingsOpen,
  chipFilters,
  onChipFiltersChange,
  fieldMetadata,
  onAddFilter,
  onChipClick,
  onClearAll,
  filterFormVisible,
  onFilterFormToggle,
  hasFilterBlock,
  resolveChipValueLabel,
  hideQuickFilters,
  hideSort,
  hideColumnSettings,
  hideRowHeight,
  hideFilterChips,
}: ListToolbarProps) {
  const { t } = useI18n();
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter') onSearch();
    },
    [onSearch],
  );

  const quickFilterIcons: Record<string, string> = {
    my_records: '\uD83D\uDC64',
    created_today: '\uD83D\uDCC5',
    modified_this_week: '\uD83D\uDD50',
  };
  const quickFilters: Array<{ key: QuickFilterKey; label: string; icon: string }> =
    getQuickFilterPresetDefinitions().map((definition) => ({
      key: definition.key,
      label: t(definition.i18nKey, undefined, definition.fallbackLabel),
      icon: quickFilterIcons[definition.key] ?? '\u25CF',
    }));
  const showInlineControls =
    !hideSort ||
    !hideColumnSettings ||
    Boolean(hasFilterBlock && onFilterFormToggle) ||
    !hideQuickFilters ||
    !hideRowHeight;

  return (
    <>
      {/* Main toolbar row */}
      <div
        className="print-hide flex flex-wrap items-center gap-2 border-b border-gray-100 px-4 py-2 sm:px-6"
        data-print="hide"
        data-testid="list-toolbar"
      >
        {/* Search input */}
        <div className="relative min-w-0 flex-1 basis-full sm:flex-none sm:basis-auto">
          <input
            type="text"
            value={keyword}
            onChange={(e) => onKeywordChange(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={t('common.search', undefined, 'Search') + '...'}
            className="rounded-card border-border bg-subtle text-text-2 focus:bg-panel focus-visible:shadow-focus h-8 w-full border pr-3 pl-8 text-xs placeholder-gray-400 focus:outline-none sm:w-[240px]"
            data-testid="list-search-input"
          />
          <svg
            className="text-text-3 absolute top-1/2 left-2.5 h-3.5 w-3.5 -translate-y-1/2"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
            />
          </svg>
        </div>

        {/* Separator */}
        {showInlineControls && <div className="mx-1 hidden h-6 w-px bg-gray-200 sm:block" />}

        {/* Sort popover trigger */}
        {!hideSort && (
          <SortPopover
            activeSorts={activeSorts}
            onSortsChange={onSortsChange}
            sortableColumns={sortableColumns}
          >
            <button
              type="button"
              className={`rounded-card flex items-center gap-1 border px-3 py-1.5 text-xs font-medium transition-colors ${
                activeSorts.length > 0
                  ? 'bg-accent-weak text-accent border-blue-200 hover:bg-blue-100'
                  : 'border-border text-text-2 hover:bg-hover hover:text-text-2'
              }`}
              data-testid="sort-popover-trigger"
            >
              <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M3 4h13M3 8h9m-9 4h6m4 0l4-4m0 0l4 4m-4-4v12"
                />
              </svg>
              {t('common.sort', undefined, 'Sort')}
              {activeSorts.length > 0 && (
                <span className="rounded-pill bg-accent flex h-4 w-4 items-center justify-center text-[10px] font-bold text-white">
                  {activeSorts.length}
                </span>
              )}
            </button>
          </SortPopover>
        )}

        {/* Fields / Column settings button */}
        {!hideColumnSettings && (
          <button
            type="button"
            onClick={onColumnSettingsOpen}
            className="rounded-card border-border text-text-2 hover:bg-hover hover:text-text-2 flex items-center gap-1 border px-3 py-1.5 text-xs font-medium transition-colors"
            data-testid="column-settings-btn"
          >
            <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
              />
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
              />
            </svg>
            {t('common.fields', undefined, 'Fields')}
          </button>
        )}

        {/* Filter form toggle button — only shown when a filter block exists */}
        {hasFilterBlock && onFilterFormToggle && (
          <button
            type="button"
            onClick={onFilterFormToggle}
            className={`rounded-card flex items-center gap-1 border px-3 py-1.5 text-xs font-medium transition-colors ${
              filterFormVisible
                ? 'bg-accent-weak text-accent border-blue-200 hover:bg-blue-100'
                : 'border-border text-text-2 hover:bg-hover hover:text-text-2'
            }`}
            data-testid="filters-toggle"
          >
            <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z"
              />
            </svg>
            {t('common.filter', undefined, 'Filter')}
            <svg
              className={`h-3 w-3 transition-transform ${filterFormVisible ? 'rotate-180' : ''}`}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 9l-7 7-7-7"
              />
            </svg>
          </button>
        )}

        {/* Spacer */}
        <div className="hidden flex-1 sm:block" />

        {/* Quick filter chips */}
        {!hideQuickFilters && (
          <div
            className="flex max-w-full basis-full gap-1.5 overflow-x-auto sm:basis-auto"
            data-testid="quick-filters"
          >
            {quickFilters.map((qf) => (
              <button
                key={qf.key}
                type="button"
                onClick={() => onQuickFilter(qf.key)}
                data-testid={`quick-filter-${qf.key}`}
                className={`rounded-pill px-3 py-1 text-xs font-medium transition-colors ${
                  activeQuickFilter === qf.key
                    ? 'bg-accent-weak text-accent ring-1 ring-blue-300'
                    : 'bg-subtle text-text-2 hover:bg-hover'
                }`}
              >
                {qf.label}
              </button>
            ))}
          </div>
        )}

        {/* Row height */}
        {!hideRowHeight && <RowHeightSelector value={rowHeight} onChange={onRowHeightChange} />}
      </div>

      {/* Filter Chip Bar */}
      {!hideFilterChips && (
        <FilterChipBar
          filters={chipFilters}
          sorts={activeSorts}
          fieldMetadata={fieldMetadata}
          onFiltersChange={onChipFiltersChange}
          onSortsChange={onSortsChange}
          onAddFilter={onAddFilter}
          onChipClick={onChipClick}
          onClearAll={onClearAll}
          resolveValueLabel={resolveChipValueLabel}
        />
      )}
    </>
  );
}
