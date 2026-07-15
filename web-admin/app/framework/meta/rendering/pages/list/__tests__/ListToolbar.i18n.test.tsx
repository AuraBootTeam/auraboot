/**
 * ListToolbar i18n test — verifies that toolbar labels (Sort / Fields /
 * quick filters / search placeholder / Filter toggle) consume i18n keys
 * instead of leaking English literals when zh-CN translations are loaded.
 *
 * Regression guard for the DSL list page i18n leakage incident where users
 * saw "Sort / Fields / My Records / Created Today" on `/p/scheduled_task`.
 */
import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { ListToolbar } from '../ListToolbar';
import { assembleQuickFilterChips, type QuickFilterChip } from '../quickFilterChips';
import { getQuickFilterPresetDefinitions } from '../quickFilterPresets';

const ZH_TRANSLATIONS = {
  common: {
    sort: '排序',
    fields: '字段',
    filter: '筛选',
    search: '搜索',
    add_filter: '添加筛选',
    my_records: '我的记录',
    created_today: '今日新建',
    modified_this_week: '本周修改',
    saved_view_save_preset_to_personal: '保存为我的视图',
  },
};

const zhT = (key: string, _vars?: unknown, fallback?: string) => {
  const [ns, k] = key.split('.');
  return (ZH_TRANSLATIONS as Record<string, Record<string, string>>)[ns]?.[k] ?? fallback ?? key;
};
const enT = (_key: string, _vars?: unknown, fallback?: string) => fallback ?? _key;

/** The three built-in preset chips, labels resolved through the given `t`. */
const presetChips = (t = zhT): QuickFilterChip[] =>
  assembleQuickFilterChips({
    presets: getQuickFilterPresetDefinitions(),
    t,
    savedViews: [],
    pins: [],
  });

function renderToolbar(extraProps: Partial<React.ComponentProps<typeof ListToolbar>> = {}) {
  const noop = () => {};
  return render(
    <I18nProvider initialData={ZH_TRANSLATIONS} initialLocale="zh-CN">
      <ListToolbar
        keyword=""
        onKeywordChange={noop}
        onSearch={noop}
        chips={presetChips()}
        activeQuickFilter={null}
        onActivateChip={noop}
        activeSorts={[]}
        onSortsChange={noop}
        sortableColumns={[]}
        onRowHeightChange={noop}
        onColumnSettingsOpen={noop}
        chipFilters={[]}
        onChipFiltersChange={noop}
        fieldMetadata={[]}
        onAddFilter={noop}
        onChipClick={noop}
        onClearAll={noop}
        hasFilterBlock
        onFilterFormToggle={noop}
        {...extraProps}
      />
    </I18nProvider>,
  );
}

describe('ListToolbar i18n', () => {
  it('renders Sort label from common.sort instead of English literal', () => {
    renderToolbar();
    expect(screen.getByTestId('sort-popover-trigger')).toHaveTextContent('排序');
    expect(screen.queryByText('Sort')).toBeNull();
  });

  it('renders Fields label from common.fields', () => {
    renderToolbar();
    expect(screen.getByTestId('column-settings-btn')).toHaveTextContent('字段');
    expect(screen.queryByText('Fields')).toBeNull();
  });

  it('renders Filter toggle label from common.filter', () => {
    renderToolbar();
    expect(screen.getByTestId('filters-toggle')).toHaveTextContent('筛选');
    expect(screen.queryByText('Filter', { selector: 'button' })).toBeNull();
  });

  it('renders quick filter labels from common.* namespace', () => {
    renderToolbar();
    expect(screen.getByTestId('quick-filter-my_records')).toHaveTextContent('我的记录');
    expect(screen.getByTestId('quick-filter-created_today')).toHaveTextContent('今日新建');
    expect(screen.getByTestId('quick-filter-modified_this_week')).toHaveTextContent('本周修改');
    expect(screen.queryByText('My Records')).toBeNull();
    expect(screen.queryByText('Created Today')).toBeNull();
    expect(screen.queryByText('Modified This Week')).toBeNull();
  });

  it('uses the toolbar quick filter group as the only preset interaction surface', () => {
    const onSaveActivePreset = vi.fn();
    renderToolbar({
      activeQuickFilter: 'my_records',
      onSaveActivePreset,
    });

    const myRecords = screen.getByTestId('quick-filter-my_records');
    expect(screen.queryByTestId('preset-view-bar')).toBeNull();
    expect(myRecords).toHaveAttribute('data-preset-active', 'true');
    expect(myRecords).toHaveAttribute('aria-pressed', 'true');

    const saveButton = screen.getByTestId('preset-view-save-as-personal');
    expect(saveButton).toHaveAttribute('aria-label', '保存为我的视图');
    fireEvent.click(saveButton);
    expect(onSaveActivePreset).toHaveBeenCalledTimes(1);
  });

  it('keeps quick filters inactive unless a toolbar preset is selected', () => {
    renderToolbar();

    const modifiedThisWeek = screen.getByTestId('quick-filter-modified_this_week');
    expect(modifiedThisWeek).toHaveAttribute('data-preset-active', 'false');
    expect(modifiedThisWeek).not.toHaveAttribute('data-preset-saved');
    expect(modifiedThisWeek).not.toHaveAttribute('data-preset-edited');
    expect(screen.queryByTestId('quick-filter-modified_this_week-saved')).toBeNull();
    expect(screen.queryByTestId('preset-view-reset-saved')).toBeNull();
    expect(screen.queryByTestId('preset-view-save-as-personal')).toBeNull();
  });

  it('renders a pinned SavedView as a view chip and activates it on click', () => {
    const onActivateChip = vi.fn();
    const viewChip: QuickFilterChip = {
      kind: 'view',
      viewPid: 'view-123',
      label: '开放订单',
      icon: '📦',
      order: 1,
    };
    renderToolbar({
      chips: [...presetChips(), viewChip],
      currentViewPid: 'view-123',
      onActivateChip,
    });

    const chip = screen.getByTestId('quick-filter-view-view-123');
    expect(chip).toHaveTextContent('开放订单');
    // currentViewPid matches -> the view chip is the active one.
    expect(chip).toHaveAttribute('data-preset-active', 'true');
    expect(chip).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(chip);
    expect(onActivateChip).toHaveBeenCalledWith(viewChip);
  });

  it('marks a view chip inactive when it is not the current view', () => {
    const viewChip: QuickFilterChip = {
      kind: 'view',
      viewPid: 'view-9',
      label: 'Other View',
      order: 1,
    };
    renderToolbar({ chips: [...presetChips(), viewChip], currentViewPid: 'someone-else' });
    expect(screen.getByTestId('quick-filter-view-view-9')).toHaveAttribute(
      'data-preset-active',
      'false',
    );
  });

  it('renders search placeholder from common.search', () => {
    renderToolbar();
    const input = screen.getByTestId('list-search-input') as HTMLInputElement;
    expect(input.placeholder).toBe('搜索...');
  });

  it('keeps toolbar controls responsive on narrow list pages', () => {
    renderToolbar();
    const toolbar = screen.getByTestId('list-toolbar');
    const input = screen.getByTestId('list-search-input');
    const inputWrap = input.parentElement;
    const quickFilters = screen.getByTestId('quick-filters');

    expect(toolbar).toHaveClass('flex-wrap');
    expect(inputWrap).toHaveClass('basis-full');
    expect(input).toHaveClass('w-full');
    expect(quickFilters).toHaveClass('overflow-x-auto');
  });

  it('renders the Add Filter toolbar button from common.add_filter', () => {
    // The entry point moved from FilterChipBar into the toolbar: an unfiltered
    // view used to render a full-width grey strip just to host this one button.
    renderToolbar();
    const button = screen.getByTestId('add-filter-btn');
    expect(button).toHaveTextContent('添加筛选');
    expect(button).not.toHaveTextContent('Add Filter');
  });

  it('hides the Add Filter button when filter chips are suppressed', () => {
    renderToolbar({ hideFilterChips: true });
    expect(screen.queryByTestId('add-filter-btn')).toBeNull();
  });

  it('can hide generic list chrome for focused workbench entry pages', () => {
    renderToolbar({
      hideSort: true,
      hideColumnSettings: true,
      hideQuickFilters: true,
      hideRowHeight: true,
      hideFilterChips: true,
    });
    expect(screen.queryByTestId('sort-popover-trigger')).toBeNull();
    expect(screen.queryByTestId('column-settings-btn')).toBeNull();
    expect(screen.queryByTestId('quick-filters')).toBeNull();
    expect(screen.queryByText(/\+ 添加筛选/)).toBeNull();
  });

  it('falls back to English when translations missing (no provider keys)', () => {
    // Provider with empty translation map — fallback strings must surface.
    const noop = () => {};
    render(
      <I18nProvider initialData={{ common: {} }} initialLocale="en-US">
        <ListToolbar
          keyword=""
          onKeywordChange={noop}
          onSearch={noop}
          chips={presetChips(enT)}
          activeQuickFilter={null}
          onActivateChip={noop}
          activeSorts={[]}
          onSortsChange={noop}
          sortableColumns={[]}
          onRowHeightChange={noop}
          onColumnSettingsOpen={noop}
          chipFilters={[]}
          onChipFiltersChange={noop}
          fieldMetadata={[]}
          onAddFilter={noop}
          onChipClick={noop}
          onClearAll={noop}
          hasFilterBlock
          onFilterFormToggle={noop}
        />
      </I18nProvider>,
    );
    expect(screen.getByTestId('sort-popover-trigger')).toHaveTextContent('Sort');
    expect(screen.getByTestId('column-settings-btn')).toHaveTextContent('Fields');
  });
});
