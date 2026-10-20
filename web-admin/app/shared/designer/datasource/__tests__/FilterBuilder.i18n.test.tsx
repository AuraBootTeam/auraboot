import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FilterBuilder } from '../FilterBuilder';
import type { FilterCondition } from '../types';

const language = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: language.locale, t: (key: string) => key }),
}));

function Editor() {
  const [filters, setFilters] = useState<FilterCondition[]>([]);
  return <FilterBuilder value={filters} onChange={setFilters} />;
}

afterEach(() => {
  language.locale = 'zh-CN';
});

describe('shared designer filter localization and edits', () => {
  it('adds, edits and removes a filter using Chinese controls without changing its operator codes', () => {
    render(<Editor />);
    expect(screen.getByText('未设置筛选条件')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '添加条件' }));
    fireEvent.change(screen.getByRole('textbox', { name: '字段' }), {
      target: { value: 'amount' },
    });
    fireEvent.change(screen.getByRole('textbox', { name: '值' }), { target: { value: '100000' } });
    expect(screen.getByRole('combobox', { name: '运算符' })).toHaveValue('eq');
    expect(screen.getByRole('option', { name: '大于等于' })).toHaveValue('gte');
    fireEvent.change(screen.getByRole('combobox', { name: '运算符' }), {
      target: { value: 'isNull' },
    });
    expect(screen.queryByRole('textbox', { name: '值' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '移除条件' }));
    expect(screen.getByText('未设置筛选条件')).toBeVisible();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('renders English controls when the current locale is English', () => {
    language.locale = 'en-US';
    render(<Editor />);
    expect(screen.getByText('No filters configured')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: 'Add filter' }));
    expect(screen.getByRole('option', { name: 'Is not empty' })).toHaveValue('isNotNull');
    expect(screen.getByRole('button', { name: 'Remove filter' })).toBeVisible();
    expect(screen.queryByText('筛选条件')).not.toBeInTheDocument();
  });

  it('preserves an authored label and the field dropdown values', () => {
    render(
      <FilterBuilder
        value={[{ field: 'amount', operator: 'eq', value: '42' }]}
        onChange={vi.fn()}
        label="My business filter"
        fields={[{ code: 'amount', name: '合同金额', fieldType: 'DECIMAL' }]}
      />,
    );
    expect(screen.getByText('My business filter')).toBeVisible();
    expect(screen.getByRole('combobox', { name: '字段' })).toHaveValue('amount');
    expect(screen.getByRole('option', { name: '合同金额' })).toHaveValue('amount');
  });
});
