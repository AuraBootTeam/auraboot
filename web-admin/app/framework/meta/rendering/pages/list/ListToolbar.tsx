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
import type { ColumnConfig } from '~/framework/meta/schemas/types';
import { SortPopover } from './SortPopover';
import type { SortableColumn } from './SortPopover';

type QuickFilterKey = 'my_records' | 'created_today' | 'modified_this_week';

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
}: ListToolbarProps) {
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter') onSearch();
    },
    [onSearch],
  );

  const quickFilters: Array<{ key: QuickFilterKey; label: string; icon: string }> = [
    { key: 'my_records', label: 'My Records', icon: '\uD83D\uDC64' },
    { key: 'created_today', label: 'Created Today', icon: '\uD83D\uDCC5' },
    { key: 'modified_this_week', label: 'Modified This Week', icon: '\uD83D\uDD50' },
  ];

  return (
    <>
      {/* Main toolbar row */}
      <div
        className="print-hide flex items-center gap-2 border-b border-gray-100 px-6 py-2"
        data-print="hide"
        data-testid="list-toolbar"
      >
        {/* Search input */}
        <div className="relative flex-shrink-0">
          <input
            type="text"
            value={keyword}
            onChange={(e) => onKeywordChange(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Search..."
            className="h-8 w-[240px] rounded-lg border border-gray-200 bg-gray-50 pl-8 pr-3 text-xs text-gray-700 placeholder-gray-400 focus:border-blue-300 focus:bg-white focus:ring-2 focus:ring-blue-100 focus:outline-none"
            data-testid="list-search-input"
          />
          <svg
            className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-gray-400"
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
        <div className="mx-1 h-6 w-px bg-gray-200" />

        {/* Sort popover trigger */}
        <SortPopover
          activeSorts={activeSorts}
          onSortsChange={onSortsChange}
          sortableColumns={sortableColumns}
        >
          <button
            type="button"
            className={`flex items-center gap-1 rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
              activeSorts.length > 0
                ? 'border-blue-200 bg-blue-50 text-blue-600 hover:bg-blue-100'
                : 'border-gray-200 text-gray-500 hover:bg-gray-50 hover:text-gray-700'
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
            Sort
            {activeSorts.length > 0 && (
              <span className="flex h-4 w-4 items-center justify-center rounded-full bg-blue-600 text-[10px] font-bold text-white">
                {activeSorts.length}
              </span>
            )}
          </button>
        </SortPopover>

        {/* Fields / Column settings button */}
        <button
          type="button"
          onClick={onColumnSettingsOpen}
          className="flex items-center gap-1 rounded-lg border border-gray-200 px-3 py-1.5 text-xs font-medium text-gray-500 transition-colors hover:bg-gray-50 hover:text-gray-700"
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
          Fields
        </button>

        {/* Filter form toggle button — only shown when a filter block exists */}
        {hasFilterBlock && onFilterFormToggle && (
          <button
            type="button"
            onClick={onFilterFormToggle}
            className={`flex items-center gap-1 rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
              filterFormVisible
                ? 'border-blue-200 bg-blue-50 text-blue-600 hover:bg-blue-100'
                : 'border-gray-200 text-gray-500 hover:bg-gray-50 hover:text-gray-700'
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
            Filter
            <svg
              className={`h-3 w-3 transition-transform ${filterFormVisible ? 'rotate-180' : ''}`}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
            </svg>
          </button>
        )}

        {/* Spacer */}
        <div className="flex-1" />

        {/* Quick filter chips */}
        <div className="flex gap-1.5" data-testid="quick-filters">
          {quickFilters.map((qf) => (
            <button
              key={qf.key}
              type="button"
              onClick={() => onQuickFilter(qf.key)}
              data-testid={`quick-filter-${qf.key}`}
              className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                activeQuickFilter === qf.key
                  ? 'bg-blue-50 text-blue-700 ring-1 ring-blue-300'
                  : 'bg-gray-50 text-gray-600 hover:bg-gray-100'
              }`}
            >
              {qf.label}
            </button>
          ))}
        </div>

        {/* Row height */}
        <RowHeightSelector value={rowHeight} onChange={onRowHeightChange} />
      </div>

      {/* Filter Chip Bar */}
      <FilterChipBar
        filters={chipFilters}
        sorts={activeSorts}
        fieldMetadata={fieldMetadata}
        onFiltersChange={onChipFiltersChange}
        onSortsChange={onSortsChange}
        onAddFilter={onAddFilter}
        onChipClick={onChipClick}
        onClearAll={onClearAll}
      />
    </>
  );
}
