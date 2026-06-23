import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import type { SavedView } from '~/framework/smart/types/savedView';
import { ViewSelector } from '../ViewSelector';

const ZH = {
  common: {
    loading: '加载中...',
    saved_view_select: '选择视图',
    saved_view_personal_group: '个人视图',
    saved_view_team_group: '团队共享',
    saved_view_global_group: '全员视图',
    saved_view_scope_personal: '我的',
    saved_view_scope_team: '团队',
    saved_view_scope_global: '全员',
    saved_view_default: '默认',
    saved_view_default_view: '默认视图',
    saved_view_new: '新建视图',
    saved_view_new_personal: '新建个人视图',
    saved_view_manage: '管理视图',
    saved_view_empty: '暂无保存视图',
    saved_view_locked_preset: '预置',
    saved_view_capability_blocked: '需要配置',
  },
};

function makeView(overrides: Partial<SavedView> = {}): SavedView {
  return {
    pid: 'view-personal',
    name: '我的表格',
    modelCode: 'bom.project',
    pageKey: 'bom_project_list',
    scope: 'personal',
    viewType: 'table',
    viewConfig: {},
    ...overrides,
  };
}

function renderSelector(
  props: Partial<React.ComponentProps<typeof ViewSelector>> = {},
): ReturnType<typeof render> {
  const views = [
    makeView({ pid: 'view-personal', name: '我的表格', scope: 'personal', isDefault: true }),
    makeView({ pid: 'view-team', name: '研发团队看板', scope: 'team', teamName: '研发团队' }),
    makeView({ pid: 'view-global', name: '全员默认', scope: 'global' }),
  ];

  return render(
    <I18nProvider initialData={ZH} initialLocale="zh-CN">
      <ViewSelector
        views={views}
        currentView={views[0]}
        onSelectView={() => {}}
        onCreateView={() => {}}
        onManageViews={() => {}}
        {...props}
      />
    </I18nProvider>,
  );
}

describe('ViewSelector', () => {
  it('opens a personal-only dropdown from the title trigger without entering management', () => {
    const onManageViews = vi.fn();
    renderSelector({ onManageViews });

    fireEvent.click(screen.getByTestId('view-selector-trigger'));

    expect(onManageViews).not.toHaveBeenCalled();
    const listbox = screen.getByRole('listbox', { name: '选择视图' });
    expect(listbox).toHaveTextContent('个人视图');
    expect(listbox).toHaveTextContent('我的表格');
    expect(listbox).not.toHaveTextContent('团队共享');
    expect(listbox).not.toHaveTextContent('全员视图');
    expect(listbox).not.toHaveTextContent('研发团队看板');
    expect(listbox).not.toHaveTextContent('全员默认');
  });

  it('renders the implicit saved view as a default-view baseline instead of a personal view row', () => {
    const onSelectDefaultView = vi.fn();
    const implicitDefault = makeView({
      pid: 'implicit-default',
      name: 'Default View',
      isDefault: true,
      isImplicit: true,
    });

    renderSelector({
      views: [implicitDefault, makeView({ pid: 'personal-manual', name: '我的表格' })],
      currentView: implicitDefault,
      onSelectDefaultView,
    });

    const trigger = screen.getByTestId('view-selector-trigger');
    expect(trigger).toHaveTextContent('默认视图');
    expect(screen.queryByTestId('view-selector-scope-label')).not.toBeInTheDocument();

    fireEvent.click(trigger);
    expect(screen.getByTestId('view-option-default')).toHaveTextContent('默认视图');
    expect(screen.queryByTestId('view-option-implicit-default')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('view-option-default'));
    expect(onSelectDefaultView).toHaveBeenCalledOnce();
  });

  it('selects a view from the dropdown and closes it', () => {
    const onSelectView = vi.fn();
    renderSelector({ onSelectView });

    fireEvent.click(screen.getByTestId('view-selector-trigger'));
    fireEvent.click(screen.getByTestId('view-option-view-personal'));

    expect(onSelectView).toHaveBeenCalledWith('view-personal');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('shows personal scope in the collapsed trigger', () => {
    renderSelector();

    const trigger = screen.getByTestId('view-selector-trigger');
    expect(screen.getByTestId('view-selector-scope-label')).toHaveTextContent('我的');
    expect(trigger).toHaveTextContent('我的表格');
  });

  it('marks locked plugin presets in the trigger and dropdown', () => {
    const pluginView = makeView({
      pid: 'plugin-view',
      name: '插件预置表格',
      scope: 'personal',
      viewConfig: { meta: { managedBy: 'plugin', locked: true, allowUserCopy: true } },
    });
    renderSelector({ views: [pluginView], currentView: pluginView });

    expect(screen.getByLabelText('预置')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('view-selector-trigger'));
    expect(screen.getByTestId('view-option-plugin-view')).toHaveTextContent('预置');
  });

  it('marks imported advanced views that need capability setup', () => {
    const blockedView = makeView({
      pid: 'blocked-gantt',
      name: '甘特预置',
      scope: 'personal',
      viewType: 'gantt',
      viewConfig: { meta: { capabilityStatus: 'blocked' } },
    });
    renderSelector({ views: [blockedView], currentView: blockedView });

    expect(screen.getByLabelText('需要配置')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('view-selector-trigger'));
    expect(screen.getByTestId('view-option-blocked-gantt')).toHaveTextContent('需要配置');
  });

  it('keeps create and manage as explicit menu actions', () => {
    const onCreateView = vi.fn();
    const onManageViews = vi.fn();
    renderSelector({ onCreateView, onManageViews, activeViewType: 'gantt' });

    fireEvent.click(screen.getByTestId('view-selector-trigger'));
    fireEvent.click(screen.getByTestId('view-selector-create'));
    expect(onCreateView).toHaveBeenCalledWith('gantt');
    expect(screen.queryByText('新建视图')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('view-selector-trigger'));
    fireEvent.click(screen.getByTestId('view-selector-manage'));
    expect(onManageViews).toHaveBeenCalledOnce();
  });
});
