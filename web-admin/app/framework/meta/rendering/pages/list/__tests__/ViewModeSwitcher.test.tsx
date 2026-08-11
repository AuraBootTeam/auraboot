import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { ViewModeSwitcher } from '../ViewModeSwitcher';

const ZH = {
  common: {
    saved_view_type_table: '列表',
    saved_view_type_kanban: '看板',
    saved_view_mode_switch: '切换商机视图',
  },
};

function renderSwitcher(activeType: 'table' | 'kanban' = 'table', onChange = vi.fn()) {
  render(
    <I18nProvider initialData={ZH} initialLocale="zh-CN">
      <ViewModeSwitcher
        activeType={activeType}
        availableTypes={['table', 'kanban']}
        onChange={onChange}
      />
    </I18nProvider>,
  );
  return onChange;
}

describe('ViewModeSwitcher', () => {
  it('renders an accessible list/kanban segmented control', () => {
    renderSwitcher();

    expect(screen.getByRole('radiogroup', { name: '切换商机视图' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '列表' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: '看板' })).toHaveAttribute('aria-checked', 'false');
  });

  it('switches mode by click and arrow-key navigation', () => {
    const onChange = renderSwitcher('table');
    const table = screen.getByRole('radio', { name: '列表' });
    const kanban = screen.getByRole('radio', { name: '看板' });

    fireEvent.click(kanban);
    expect(onChange).toHaveBeenCalledWith('kanban');

    table.focus();
    fireEvent.keyDown(table, { key: 'ArrowRight' });
    expect(kanban).toHaveFocus();
    expect(onChange).toHaveBeenLastCalledWith('kanban');
  });

  it('stays hidden when only one view type is available', () => {
    render(
      <I18nProvider initialData={ZH} initialLocale="zh-CN">
        <ViewModeSwitcher activeType="table" availableTypes={['table']} onChange={() => {}} />
      </I18nProvider>,
    );

    expect(screen.queryByTestId('list-view-mode-switcher')).not.toBeInTheDocument();
  });
});
