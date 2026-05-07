/**
 * ListToolbar i18n test — verifies that toolbar labels (Sort / Fields /
 * quick filters / search placeholder / Filter toggle) consume i18n keys
 * instead of leaking English literals when zh-CN translations are loaded.
 *
 * Regression guard for the DSL list page i18n leakage incident where users
 * saw "Sort / Fields / My Records / Created Today" on `/p/scheduled_task`.
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { ListToolbar } from '../ListToolbar';

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
  },
};

function renderToolbar(extraProps: Partial<React.ComponentProps<typeof ListToolbar>> = {}) {
  const noop = () => {};
  return render(
    <I18nProvider initialData={ZH_TRANSLATIONS} initialLocale="zh-CN">
      <ListToolbar
        keyword=""
        onKeywordChange={noop}
        onSearch={noop}
        activeQuickFilter={null}
        onQuickFilter={noop}
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

  it('renders search placeholder from common.search', () => {
    renderToolbar();
    const input = screen.getByTestId('list-search-input') as HTMLInputElement;
    expect(input.placeholder).toBe('搜索...');
  });

  it('renders Add Filter chip-bar button from common.add_filter', () => {
    renderToolbar();
    expect(screen.getByText(/\+ 添加筛选/)).toBeInTheDocument();
    expect(screen.queryByText(/\+ Add Filter/)).toBeNull();
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
          activeQuickFilter={null}
          onQuickFilter={noop}
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
