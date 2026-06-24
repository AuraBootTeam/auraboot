import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { FilterChipBar } from '../FilterChipBar';
import { FilterValuePopover } from '../FilterValuePopover';

const ZH = {
  common: {
    add_filter: '添加筛选',
    apply: '应用',
    cancel: '取消',
    clear_all: '清除全部',
    no: '否',
    select_placeholder: '请选择',
    yes: '是',
  },
  filter: {
    operator: {
      eq: '等于',
      like: '包含',
    },
    remove_filter: '移除筛选 {label}',
    remove_sort: '移除排序 {label}',
    value: {
      placeholder: '请输入筛选值',
    },
  },
};

function renderZh(ui: React.ReactElement) {
  return render(
    <I18nProvider initialLocale="zh-CN" initialData={ZH}>
      {ui}
    </I18nProvider>,
  );
}

describe('FilterValuePopover i18n', () => {
  it('renders Chinese labels and disables applying an empty value', () => {
    const onApply = vi.fn();

    renderZh(
      <FilterValuePopover
        open
        anchorEl={{ x: 0, y: 0 }}
        fieldCode="standardized_status"
        fieldLabel="标准化状态"
        fieldType="BOOLEAN"
        operator="eq"
        value={null}
        onApply={onApply}
        onCancel={vi.fn()}
      />,
    );

    expect(screen.getByText('等于')).toBeInTheDocument();
    expect(screen.getByText('请选择')).toBeInTheDocument();
    expect(screen.getByText('是')).toBeInTheDocument();
    expect(screen.getByText('否')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '取消' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '应用' })).toBeDisabled();
    expect(screen.queryByText('Equals')).not.toBeInTheDocument();
    expect(screen.queryByText('-- Select --')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '应用' }));
    expect(onApply).not.toHaveBeenCalled();
  });
});

describe('FilterChipBar i18n', () => {
  it('renders textual operators and actions in Chinese', () => {
    renderZh(
      <FilterChipBar
        filters={[{ fieldCode: 'name', operator: 'like', value: 'ACME' }]}
        sorts={[]}
        fieldMetadata={[{ fieldCode: 'name', label: '客户名称', fieldType: 'TEXT' }]}
        onFiltersChange={vi.fn()}
        onSortsChange={vi.fn()}
        onAddFilter={vi.fn()}
        onClearAll={vi.fn()}
      />,
    );

    expect(screen.getByText('包含')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /\+ 添加筛选/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '清除全部' })).toBeInTheDocument();
    expect(screen.queryByText('contains')).not.toBeInTheDocument();
    expect(screen.queryByText('Clear All')).not.toBeInTheDocument();
  });
});
