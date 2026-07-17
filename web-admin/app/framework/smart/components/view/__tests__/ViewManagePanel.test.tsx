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
    getShareStatus: vi.fn(async () => ({ shared: false })),
    shareView: vi.fn(async () => ({
      token: 'tok123',
      shareUrl: '/api/views/shared/tok123',
      expiresAt: null,
      passwordProtected: false,
    })),
    revokeShare: vi.fn(async () => undefined),
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
    saved_view_personal_quota: '个人视图：{count}/{limit}',
    saved_view_personal_quota_reached: '已达到 10 个个人视图上限，请删除或复用已有视图',
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
    expect(screen.getByTestId('saved-view-quota-status')).toHaveTextContent('个人视图：0/10');
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
    expect(screen.getByTestId('saved-view-quota-summary')).toHaveTextContent('个人视图：2/10');
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

    expect(screen.getByTestId('saved-view-quota-status')).toHaveTextContent('个人视图：10/10');
    expect(screen.getByTestId('saved-view-quota-limit-reached')).toHaveTextContent(
      '已达到 10 个个人视图上限',
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

    // The row actions only come back once the rename form has closed, and that happens after
    // onEditView resolves. Locally the state settles before the next line runs; on a slower machine
    // it does not, and the copy button is simply not in the DOM yet.
    await waitFor(() => expect(screen.getByLabelText('复制视图')).toBeInTheDocument());
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

// ---------------------------------------------------------------------------
// GAP-121 producer half: generate / copy / revoke a public share link.
// The consumer (/share/{token}) already existed; before this there was no UI
// that could ever create a link (canShareSavedView had zero call sites).
// ---------------------------------------------------------------------------

describe('ViewManagePanel public share link', () => {
  let writeText: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(confirmDialog).mockResolvedValue(true);
    vi.mocked(savedViewService.getShareStatus).mockResolvedValue({ shared: false });
    vi.mocked(savedViewService.shareView).mockResolvedValue({
      token: 'tok123',
      shareUrl: '/api/views/shared/tok123',
      expiresAt: null,
      passwordProtected: false,
    });
    vi.mocked(savedViewService.revokeShare).mockResolvedValue(undefined);

    writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
      writable: true,
    });
  });

  const shareableView = () =>
    makeView({ pid: 'personal-view', name: '我的默认视图', scope: 'personal' });

  it('exposes a share affordance on a shareable personal view', () => {
    renderPanel({ views: [shareableView()] });

    const shareBtn = screen.getByTestId('saved-view-action-share-personal-view');
    expect(shareBtn).toBeInTheDocument();
    expect(shareBtn).toBeEnabled();
  });

  it('shows no share affordance at all when canShareSavedView() is false (locked preset)', () => {
    renderPanel({
      views: [
        makeView({
          pid: 'personal-view',
          scope: 'personal',
          viewConfig: { meta: { locked: true } },
        }),
      ],
    });

    // Absent, not disabled. A greyed-out icon nobody can ever click and nothing explains is worse
    // than no icon: it advertises a capability the user does not have.
    expect(screen.queryByTestId('saved-view-action-share-personal-view')).not.toBeInTheDocument();
  });

  it('shows no share affordance when the backend denies the share action', () => {
    renderPanel({
      views: [makeView({ pid: 'personal-view', scope: 'personal', actions: ['view', 'copy'] })],
    });

    expect(screen.queryByTestId('saved-view-action-share-personal-view')).not.toBeInTheDocument();
  });

  it('shows the share affordance when the backend allows it', () => {
    renderPanel({
      views: [
        makeView({
          pid: 'personal-view',
          scope: 'personal',
          actions: ['view', 'copy', 'manage', 'share'],
        }),
      ],
    });

    // The plumbing is complete and waiting. The day a view becomes shareable — whether because the
    // policy opens up or because team/global views get surfaced here — the button appears on its own.
    expect(screen.getByTestId('saved-view-action-share-personal-view')).toBeEnabled();
  });

  it('opening the share panel reads current status from the status endpoint', async () => {
    renderPanel({ views: [shareableView()] });

    fireEvent.click(screen.getByTestId('saved-view-action-share-personal-view'));

    await waitFor(() =>
      expect(savedViewService.getShareStatus).toHaveBeenCalledWith('personal-view'),
    );
    await waitFor(() =>
      expect(screen.getByTestId('saved-view-share-generate-personal-view')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('saved-view-share-link-personal-view')).toBeNull();
  });

  it('generating a link calls POST .../share and shows the public /share/{token} URL', async () => {
    renderPanel({ views: [shareableView()] });

    fireEvent.click(screen.getByTestId('saved-view-action-share-personal-view'));
    await waitFor(() =>
      expect(screen.getByTestId('saved-view-share-generate-personal-view')).toBeInTheDocument(),
    );

    fireEvent.click(screen.getByTestId('saved-view-share-generate-personal-view'));

    await waitFor(() => expect(savedViewService.shareView).toHaveBeenCalledWith('personal-view'));

    const link = await screen.findByTestId('saved-view-share-link-personal-view');
    // Public page is the /share/{token} route, NOT the API path the backend returns.
    expect((link as HTMLInputElement).value).toBe(`${window.location.origin}/share/tok123`);
    expect((link as HTMLInputElement).value).not.toContain('/api/views/shared/');
    expect(link).toHaveAttribute('readonly');
  });

  it('copies the generated link to the clipboard and confirms', async () => {
    renderPanel({ views: [shareableView()] });

    fireEvent.click(screen.getByTestId('saved-view-action-share-personal-view'));
    await waitFor(() =>
      expect(screen.getByTestId('saved-view-share-generate-personal-view')).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId('saved-view-share-generate-personal-view'));
    await screen.findByTestId('saved-view-share-link-personal-view');

    fireEvent.click(screen.getByTestId('saved-view-share-copy-personal-view'));

    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1));
    expect(writeText.mock.calls[0][0]).toBe(`${window.location.origin}/share/tok123`);
    expect(await screen.findByText('已复制')).toBeInTheDocument();
  });

  it('shows the existing link when the view is already shared', async () => {
    vi.mocked(savedViewService.getShareStatus).mockResolvedValue({
      shared: true,
      token: 'existing-tok',
      expiresAt: '2026-08-01T00:00:00Z',
      passwordProtected: false,
    });
    renderPanel({ views: [shareableView()] });

    fireEvent.click(screen.getByTestId('saved-view-action-share-personal-view'));

    const link = await screen.findByTestId('saved-view-share-link-personal-view');
    expect((link as HTMLInputElement).value).toContain('/share/existing-tok');
    expect(screen.getByTestId('saved-view-share-expires-personal-view')).toBeInTheDocument();
    expect(savedViewService.shareView).not.toHaveBeenCalled();
  });

  it('revoking calls DELETE .../share after confirmation and drops back to the empty state', async () => {
    vi.mocked(savedViewService.getShareStatus).mockResolvedValue({
      shared: true,
      token: 'existing-tok',
    });
    renderPanel({ views: [shareableView()] });

    fireEvent.click(screen.getByTestId('saved-view-action-share-personal-view'));
    await screen.findByTestId('saved-view-share-link-personal-view');

    fireEvent.click(screen.getByTestId('saved-view-share-revoke-personal-view'));

    await waitFor(() => expect(confirmDialog).toHaveBeenCalled());
    await waitFor(() => expect(savedViewService.revokeShare).toHaveBeenCalledWith('personal-view'));
    await waitFor(() =>
      expect(screen.getByTestId('saved-view-share-generate-personal-view')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('saved-view-share-link-personal-view')).toBeNull();
  });

  it('does not revoke when the confirmation is cancelled', async () => {
    vi.mocked(savedViewService.getShareStatus).mockResolvedValue({
      shared: true,
      token: 'existing-tok',
    });
    vi.mocked(confirmDialog).mockResolvedValue(false);
    renderPanel({ views: [shareableView()] });

    fireEvent.click(screen.getByTestId('saved-view-action-share-personal-view'));
    await screen.findByTestId('saved-view-share-link-personal-view');

    fireEvent.click(screen.getByTestId('saved-view-share-revoke-personal-view'));

    await waitFor(() => expect(confirmDialog).toHaveBeenCalled());
    expect(savedViewService.revokeShare).not.toHaveBeenCalled();
    expect(screen.getByTestId('saved-view-share-link-personal-view')).toBeInTheDocument();
  });

  it('surfaces a backend failure instead of silently showing an empty link box', async () => {
    vi.mocked(savedViewService.shareView).mockRejectedValue(new Error('share denied'));
    renderPanel({ views: [shareableView()] });

    fireEvent.click(screen.getByTestId('saved-view-action-share-personal-view'));
    await waitFor(() =>
      expect(screen.getByTestId('saved-view-share-generate-personal-view')).toBeInTheDocument(),
    );

    fireEvent.click(screen.getByTestId('saved-view-share-generate-personal-view'));

    const alert = await screen.findByTestId('saved-view-share-error-personal-view');
    expect(alert).toHaveTextContent('share denied');
    expect(screen.queryByTestId('saved-view-share-link-personal-view')).toBeNull();
  });
});

describe('ViewManagePanel team quick-filter pin (M3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  const teamView = () =>
    makeView({ pid: 'team-1', name: '团队看板', scope: 'team', teamId: 'team-a' });

  it('hides the team section when the user cannot manage team pins', () => {
    // Team views are supplied separately from the personal `views` list.
    renderPanel({
      views: [makeView({ pid: 'personal-1', scope: 'personal' })],
      teamViews: [teamView()],
      canManageTeamPins: false,
      onTeamPinView: vi.fn(),
    });

    expect(screen.queryByTestId('saved-view-team-group')).toBeNull();
    expect(screen.queryByTestId('saved-view-action-team-pin-team-1')).toBeNull();
    expect(screen.queryByText('团队看板')).toBeNull();
  });

  it('does not derive team views from the personal `views` list', () => {
    // Regression guard: the list page passes a personal-only `views` list, so a
    // team-scoped view there must never surface a team section (only `teamViews`
    // does). This is what the M3 golden caught.
    renderPanel({
      views: [teamView()],
      teamViews: [],
      canManageTeamPins: true,
      onTeamPinView: vi.fn(),
    });

    expect(screen.queryByTestId('saved-view-team-group')).toBeNull();
    expect(screen.queryByTestId('saved-view-action-team-pin-team-1')).toBeNull();
  });

  it('pins a team view for its team when the user has team-manage', async () => {
    const onTeamPinView = vi.fn(async () => {});
    renderPanel({
      views: [],
      teamViews: [teamView()],
      canManageTeamPins: true,
      teamPinnedViewPids: [],
      onTeamPinView,
    });

    expect(screen.getByTestId('saved-view-team-group')).toBeInTheDocument();
    const toggle = screen.getByTestId('saved-view-action-team-pin-team-1');
    expect(toggle).toHaveAttribute('data-team-pinned', 'false');

    fireEvent.click(toggle);

    await waitFor(() => expect(onTeamPinView).toHaveBeenCalledWith('team-1', 'team-a'));
  });

  it('unpins a team view that is already team-pinned', async () => {
    const onTeamUnpinView = vi.fn(async () => {});
    renderPanel({
      views: [],
      teamViews: [teamView()],
      canManageTeamPins: true,
      teamPinnedViewPids: ['team-1'],
      onTeamPinView: vi.fn(),
      onTeamUnpinView,
    });

    const toggle = screen.getByTestId('saved-view-action-team-pin-team-1');
    expect(toggle).toHaveAttribute('data-team-pinned', 'true');

    fireEvent.click(toggle);

    await waitFor(() => expect(onTeamUnpinView).toHaveBeenCalledWith('team-1', 'team-a'));
  });
});
