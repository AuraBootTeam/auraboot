import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import type { SavedView } from '~/framework/smart/types/savedView';
import { savedViewService } from '~/shared/services/savedViewService';
import { confirmDialog } from '~/utils/confirmDialog';
import { ViewManagePanel } from '../ViewManagePanel';

vi.mock('~/shared/services/savedViewService', () => ({
  savedViewService: {
    getMyTeams: vi.fn(async () => [{ pid: 'team-a', name: 'Team A' }]),
    getAuditEvents: vi.fn(async () => []),
    searchUsers: vi.fn(async () => []),
    updateView: vi.fn(),
  },
}));

vi.mock('~/utils/confirmDialog', () => ({
  confirmDialog: vi.fn(async () => true),
}));

const ZH = {
  common: {
    saved_view_manage: '管理视图',
    saved_view_panel_subtitle: '管理当前列表的个人视图',
    saved_view_new_personal: '新建个人视图',
    saved_view_personal_group: '个人视图',
    saved_view_scope_personal: '我的',
    saved_view_personal_quota: '个人视图: {count}/{limit}',
    saved_view_personal_quota_reached: '已达到个人视图上限，请删除不需要的视图后再新建。',
    saved_view_choose_type: '选择视图类型',
    saved_view_cancel: '取消',
    saved_view_create_cancel: '取消',
    saved_view_create_saving: '保存中...',
    saved_view_create_save: '保存视图',
    saved_view_create_not_saveable: '不可保存',
    saved_view_empty: '暂无保存视图',
    saved_view_empty_hint: '创建一个个人视图后，可保存当前筛选、字段和排序。',
    saved_view_manage_search_placeholder: '搜索我的视图...',
    saved_view_manage_no_results: '没有匹配的个人视图',
    saved_view_default: '默认',
    saved_view_locked_preset: '预置',
    saved_view_type_table: '表格',
    saved_view_type_kanban: '看板',
    saved_view_type_calendar: '日历',
    saved_view_type_gallery: '画册',
    saved_view_type_gantt: '甘特图',
    saved_view_type_tree: '树视图',
    saved_view_type_timeline: '时间线',
    saved_view_type_form: '表单',
    saved_view_config_title: '配置{type}视图',
    saved_view_config_help: '选择这个视图需要使用的字段。必填字段完成后才能保存。',
    saved_view_field_groupByField: '分组字段',
    saved_view_field_titleField: '标题字段',
    saved_view_field_calendarDateField: '日期字段',
    saved_view_field_calendarTitleField: '标题字段',
    saved_view_field_ganttStartDateField: '开始日期字段',
    saved_view_field_ganttEndDateField: '结束日期字段',
    saved_view_field_ganttTitleField: '标题字段',
    saved_view_field_galleryImageField: '图片字段',
    saved_view_field_galleryTitleField: '标题字段',
    saved_view_field_treeParentField: '父级字段',
    saved_view_field_treeTitleField: '标题字段',
    saved_view_field_timelineStartField: '开始日期字段',
    saved_view_field_timelineEndField: '结束日期字段',
    saved_view_field_timelineResourceField: '泳道字段',
    saved_view_field_timelineTitleField: '标题字段',
    saved_view_select_field: '选择字段',
    saved_view_reason_missing_date_field: '缺少日期字段，暂不能保存该视图。',
    saved_view_reason_kanban_drag_command_missing: '当前数据可生成看板，但未配置状态更新命令，拖拽将保持禁用。',
    saved_view_action_set_default: '设为默认',
    saved_view_action_default: '默认视图',
    saved_view_action_edit: '重命名视图',
    saved_view_action_copy: '复制视图',
    saved_view_action_delete: '删除视图',
    saved_view_edit_name: '视图名称',
    saved_view_edit_description: '说明',
    saved_view_edit_description_placeholder: '可选说明',
    saved_view_edit_save: '保存',
    saved_view_duplicate_title: '复制个人视图',
    saved_view_duplicate_name: '新视图名称',
    saved_view_duplicate_submit: '创建副本',
    saved_view_delete_confirm: '确定删除视图“{name}”？删除后无法恢复。',
    saved_view_type_status_available: '可创建',
    saved_view_type_status_degraded: '需注意',
    saved_view_type_status_blocked: '不适合',
  },
};

function makeView(overrides: Partial<SavedView> = {}): SavedView {
  return {
    pid: 'view-1',
    name: '我的默认视图',
    modelCode: 'order',
    pageKey: 'order_list',
    scope: 'personal',
    viewType: 'table',
    viewConfig: {},
    ...overrides,
  };
}

function renderPanel(
  overrides: Partial<React.ComponentProps<typeof ViewManagePanel>> = {},
): React.ComponentProps<typeof ViewManagePanel> {
  const props: React.ComponentProps<typeof ViewManagePanel> = {
    open: true,
    onClose: vi.fn(),
    views: [],
    currentView: null,
    onCreateView: vi.fn(async (request) => ({
      pid: 'new-view',
      name: request.name,
      modelCode: request.modelCode,
      pageKey: request.pageKey,
      scope: request.scope ?? 'personal',
      viewType: request.viewType,
      viewConfig: request.viewConfig ?? {},
    }) as SavedView),
    onDeleteView: vi.fn(),
    onDuplicateView: vi.fn(),
    onEditView: vi.fn(),
    onSetDefaultView: vi.fn(),
    onSelectView: vi.fn(),
    modelCode: 'order',
    pageKey: 'order_list',
    fields: [],
    ...overrides,
  };

  render(
    <I18nProvider initialData={ZH} initialLocale="zh-CN">
      <ViewManagePanel {...props} />
    </I18nProvider>,
  );
  return props;
}

describe('ViewManagePanel personal-only release', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(savedViewService.getMyTeams).mockResolvedValue([
      { pid: 'team-a', name: 'Team A' },
    ]);
    vi.mocked(confirmDialog).mockResolvedValue(true);
  });

  it('renders a Chinese personal-only management panel without shared controls', () => {
    renderPanel({
      views: [
        makeView({ pid: 'personal-1', name: '我的默认视图', scope: 'personal' }),
        makeView({ pid: 'team-1', name: '团队看板', scope: 'team' }),
        makeView({ pid: 'global-1', name: '全员默认', scope: 'global' }),
      ],
      currentView: makeView(),
    });

    expect(screen.getByRole('dialog', { name: '管理视图' })).toBeInTheDocument();
    expect(screen.getByText('管理当前列表的个人视图')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '新建个人视图' })).toBeInTheDocument();
    expect(screen.getByText('个人视图')).toBeInTheDocument();
    expect(screen.getByText('我的默认视图')).toBeInTheDocument();
    expect(screen.queryByText('团队看板')).not.toBeInTheDocument();
    expect(screen.queryByText('全员默认')).not.toBeInTheDocument();
    expect(screen.queryByText(/View Management|New View|Configure|Skip|Done/i)).toBeNull();
    expect(screen.queryByTitle(/Share|Audit/i)).toBeNull();
    expect(savedViewService.getMyTeams).not.toHaveBeenCalled();
  });

  it('creates table views as personal views and never sends team or global scope', async () => {
    const props = renderPanel();

    fireEvent.click(screen.getByRole('button', { name: '新建个人视图' }));
    expect(screen.getByTestId('saved-view-quota-status')).toHaveTextContent('个人视图: 0/10');
    fireEvent.click(screen.getByRole('button', { name: /^表格/ }));

    await waitFor(() => expect(props.onCreateView).toHaveBeenCalledOnce());
    expect(props.onCreateView).toHaveBeenCalledWith(
      expect.objectContaining({
        modelCode: 'order',
        pageKey: 'order_list',
        scope: 'personal',
        viewType: 'table',
      }),
    );
    expect(props.onCreateView).toHaveBeenCalledWith(
      expect.not.objectContaining({ teamId: expect.anything() }),
    );
  });

  it('filters the personal management list without changing quota counts', () => {
    renderPanel({
      views: [
        makeView({ pid: 'personal-1', name: '我的默认视图', scope: 'personal' }),
        makeView({ pid: 'personal-2', name: '本周修改', scope: 'personal' }),
        makeView({ pid: 'team-1', name: '团队看板', scope: 'team' }),
      ],
    });

    expect(screen.getByText('我的默认视图')).toBeInTheDocument();
    expect(screen.getByText('本周修改')).toBeInTheDocument();

    fireEvent.change(screen.getByTestId('saved-view-manage-search'), {
      target: { value: '本周' },
    });

    expect(screen.queryByText('我的默认视图')).not.toBeInTheDocument();
    expect(screen.getByText('本周修改')).toBeInTheDocument();
    expect(screen.getByTestId('saved-view-quota-summary')).toHaveTextContent('个人视图: 2/10');
    expect(screen.queryByText('团队看板')).not.toBeInTheDocument();

    fireEvent.change(screen.getByTestId('saved-view-manage-search'), {
      target: { value: '不存在' },
    });
    expect(screen.getByTestId('saved-view-manage-no-results')).toHaveTextContent(
      '没有匹配的个人视图',
    );
  });

  it('disables creation at the personal quota limit', () => {
    const personalViews = Array.from({ length: 10 }, (_, index) =>
      makeView({
        pid: `personal-${index}`,
        name: `个人视图 ${index}`,
        scope: 'personal',
        isImplicit: false,
      }),
    );
    const props = renderPanel({ views: personalViews });

    fireEvent.click(screen.getByRole('button', { name: '新建个人视图' }));

    expect(screen.getByTestId('saved-view-quota-status')).toHaveTextContent('个人视图: 10/10');
    expect(screen.getByTestId('saved-view-quota-limit-reached')).toHaveTextContent(
      '已达到个人视图上限',
    );
    const tableButton = screen.getByRole('button', { name: /^表格/ });
    expect(tableButton).toBeDisabled();
    fireEvent.click(tableButton);
    expect(props.onCreateView).not.toHaveBeenCalled();
  });

  it('blocks unsupported advanced views with localized capability reasons', () => {
    const props = renderPanel({
      fields: [{ code: 'name', name: '名称', dataType: 'text' }],
    });

    fireEvent.click(screen.getByRole('button', { name: '新建个人视图' }));
    fireEvent.click(screen.getByRole('button', { name: /^日历/ }));

    expect(screen.getByTestId('view-capability-blocked-calendar')).toHaveTextContent(
      '缺少日期字段',
    );
    expect(props.onCreateView).not.toHaveBeenCalled();
  });

  it('creates advanced views only after required field mapping is complete', async () => {
    const props = renderPanel({
      fields: [
        { code: 'start_date', name: '开始日期', dataType: 'date' },
        { code: 'end_date', name: '结束日期', dataType: 'date' },
        { code: 'title', name: '标题', dataType: 'text' },
      ],
    });

    fireEvent.click(screen.getByRole('button', { name: '新建个人视图' }));
    fireEvent.click(screen.getByRole('button', { name: /^甘特图/ }));

    expect(props.onCreateView).not.toHaveBeenCalled();
    expect(screen.getByText('配置甘特图视图')).toBeInTheDocument();
    expect(screen.getByText('开始日期字段')).toBeInTheDocument();
    expect(screen.getByText('结束日期字段')).toBeInTheDocument();

    const selects = screen.getAllByRole('combobox');
    fireEvent.change(selects[0], { target: { value: 'start_date' } });
    fireEvent.change(selects[1], { target: { value: 'end_date' } });
    fireEvent.change(selects[2], { target: { value: 'title' } });
    fireEvent.click(screen.getByRole('button', { name: '保存视图' }));

    await waitFor(() => expect(props.onCreateView).toHaveBeenCalledOnce());
    expect(props.onCreateView).toHaveBeenCalledWith(
      expect.objectContaining({
        scope: 'personal',
        viewType: 'gantt',
        viewConfig: expect.objectContaining({
          ganttStartDateField: 'start_date',
          ganttEndDateField: 'end_date',
          ganttTitleField: 'title',
        }),
      }),
    );
  });

  it('supports rename, duplicate, set-default, and delete for personal views', async () => {
    const personalView = makeView({ pid: 'personal-view', name: '我的默认视图' });
    const props = renderPanel({ views: [personalView], currentView: personalView });

    fireEvent.click(screen.getByLabelText('重命名视图'));
    fireEvent.change(screen.getByLabelText('视图名称'), { target: { value: '我的订单视图' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() =>
      expect(props.onEditView).toHaveBeenCalledWith(
        'personal-view',
        '我的订单视图',
        '',
        'personal',
      ),
    );

    fireEvent.click(screen.getByLabelText('复制视图'));
    expect(screen.getByText('复制个人视图')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('新视图名称'), { target: { value: '我的订单副本' } });
    fireEvent.click(screen.getByRole('button', { name: '创建副本' }));
    await waitFor(() =>
      expect(props.onDuplicateView).toHaveBeenCalledWith('personal-view', '我的订单副本'),
    );

    fireEvent.click(screen.getByLabelText('设为默认'));
    await waitFor(() => expect(props.onSetDefaultView).toHaveBeenCalledWith('personal-view'));

    fireEvent.click(screen.getByLabelText('删除视图'));
    await waitFor(() =>
      expect(confirmDialog).toHaveBeenCalledWith(
        expect.objectContaining({
          content: '确定删除视图“我的默认视图”？删除后无法恢复。',
        }),
      ),
    );
    await waitFor(() => expect(props.onDeleteView).toHaveBeenCalledWith('personal-view'));
  });
});
