/**
 * Personal SavedView management panel.
 *
 * This release intentionally exposes only personal views. Team/global sharing,
 * collaborators, and audit remain backend/roadmap capabilities outside the
 * current user-visible management chain.
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Check, Copy, Link2, Pencil, Pin, Plus, Search, Star, Trash2, X } from 'lucide-react';
import { useI18n } from '~/contexts/I18nContext';
import {
  type SavedView,
  type SavedViewCreateRequest,
  type SavedViewShareStatus,
  type ViewConfig,
  type ViewScope,
  type ViewType,
} from '~/framework/smart/types/savedView';
import { savedViewService } from '~/shared/services/savedViewService';
import type { FieldOption } from './KanbanConfigPanel';
import { cn } from '~/utils/cn';
import { confirmDialog } from '~/utils/confirmDialog';
import {
  checkSavedViewCapability,
  type SavedViewCapabilityResult,
} from '~/framework/smart/utils/savedViewCapability';
import {
  canCopySavedView,
  canDeleteSavedView,
  canManageSavedView,
  canSetDefaultSavedView,
  canShareSavedView,
  isSavedViewLockedPreset,
} from '~/framework/smart/utils/savedViewPersistence';

const PERSONAL_VIEW_LIMIT = 10;

type ConfigFieldKey = keyof ViewConfig;

function interpolateMessage(template: string, params?: Record<string, unknown>): string {
  if (!params) return template;
  return Object.entries(params).reduce(
    (message, [key, value]) => message.replaceAll(`{${key}}`, String(value ?? '')),
    template,
  );
}

interface ViewTypeFieldRequirement {
  key: ConfigFieldKey;
  labelKey: string;
  fallback: string;
  required: boolean;
}

const VIEW_TYPE_REQUIRED_FIELDS: Partial<Record<ViewType, ViewTypeFieldRequirement[]>> = {
  kanban: [
    {
      key: 'groupByField',
      labelKey: 'common.saved_view_field_groupByField',
      fallback: '分组字段',
      required: true,
    },
    {
      key: 'titleField',
      labelKey: 'common.saved_view_field_titleField',
      fallback: '标题字段',
      required: true,
    },
  ],
  calendar: [
    {
      key: 'calendarDateField',
      labelKey: 'common.saved_view_field_calendarDateField',
      fallback: '日期字段',
      required: true,
    },
    {
      key: 'calendarTitleField',
      labelKey: 'common.saved_view_field_calendarTitleField',
      fallback: '标题字段',
      required: false,
    },
  ],
  gantt: [
    {
      key: 'ganttStartDateField',
      labelKey: 'common.saved_view_field_ganttStartDateField',
      fallback: '开始日期字段',
      required: true,
    },
    {
      key: 'ganttEndDateField',
      labelKey: 'common.saved_view_field_ganttEndDateField',
      fallback: '结束日期字段',
      required: true,
    },
    {
      key: 'ganttTitleField',
      labelKey: 'common.saved_view_field_ganttTitleField',
      fallback: '标题字段',
      required: false,
    },
  ],
  gallery: [
    {
      key: 'galleryImageField',
      labelKey: 'common.saved_view_field_galleryImageField',
      fallback: '图片字段',
      required: true,
    },
    {
      key: 'galleryTitleField',
      labelKey: 'common.saved_view_field_galleryTitleField',
      fallback: '标题字段',
      required: false,
    },
  ],
  tree: [
    {
      key: 'treeParentField',
      labelKey: 'common.saved_view_field_treeParentField',
      fallback: '父级字段',
      required: true,
    },
    {
      key: 'treeTitleField',
      labelKey: 'common.saved_view_field_treeTitleField',
      fallback: '标题字段',
      required: false,
    },
  ],
  timeline: [
    {
      key: 'timelineStartField',
      labelKey: 'common.saved_view_field_timelineStartField',
      fallback: '开始日期字段',
      required: true,
    },
    {
      key: 'timelineResourceField',
      labelKey: 'common.saved_view_field_timelineResourceField',
      fallback: '泳道字段',
      required: true,
    },
    {
      key: 'timelineEndField',
      labelKey: 'common.saved_view_field_timelineEndField',
      fallback: '结束日期字段',
      required: false,
    },
    {
      key: 'timelineTitleField',
      labelKey: 'common.saved_view_field_timelineTitleField',
      fallback: '标题字段',
      required: false,
    },
  ],
};

const VIEW_TYPE_OPTIONS: Array<{
  type: ViewType;
  labelKey: string;
  fallback: string;
}> = [
  { type: 'table', labelKey: 'common.saved_view_type_table', fallback: '表格' },
  { type: 'kanban', labelKey: 'common.saved_view_type_kanban', fallback: '看板' },
  { type: 'calendar', labelKey: 'common.saved_view_type_calendar', fallback: '日历' },
  { type: 'gallery', labelKey: 'common.saved_view_type_gallery', fallback: '画册' },
  { type: 'gantt', labelKey: 'common.saved_view_type_gantt', fallback: '甘特图' },
  { type: 'tree', labelKey: 'common.saved_view_type_tree', fallback: '树视图' },
  { type: 'timeline', labelKey: 'common.saved_view_type_timeline', fallback: '时间线' },
  { type: 'form', labelKey: 'common.saved_view_type_form', fallback: '表单' },
];

const CAPABILITY_REASON_I18N: Record<string, { key: string; fallback: string }> = {
  missing_kanban_group_field: {
    key: 'common.saved_view_reason_missing_kanban_group_field',
    fallback: '缺少适合作为分组的字段，暂不能保存该视图。',
  },
  missing_title_field: {
    key: 'common.saved_view_reason_missing_title_field',
    fallback: '缺少适合作为标题的字段，暂不能保存该视图。',
  },
  missing_date_field: {
    key: 'common.saved_view_reason_missing_date_field',
    fallback: '缺少日期字段，暂不能保存该视图。',
  },
  missing_image_field: {
    key: 'common.saved_view_reason_missing_image_field',
    fallback: '缺少图片、附件、头像或封面字段，暂不能保存该视图。',
  },
  missing_tree_parent_field: {
    key: 'common.saved_view_reason_missing_tree_parent_field',
    fallback: '缺少父级、路径或层级字段，暂不能保存该视图。',
  },
  missing_timeline_resource_field: {
    key: 'common.saved_view_reason_missing_timeline_resource_field',
    fallback: '缺少适合作为泳道的资源字段，暂不能保存该视图。',
  },
  kanban_drag_command_missing: {
    key: 'common.saved_view_reason_kanban_drag_command_missing',
    fallback: '当前数据可生成看板，但未配置状态更新命令，拖拽将保持禁用。',
  },
  tree_reorder_command_missing: {
    key: 'common.saved_view_reason_tree_reorder_command_missing',
    fallback: '当前数据可生成树视图，但未配置更新命令，拖拽排序将保持禁用。',
  },
  single_date_field_reused: {
    key: 'common.saved_view_reason_single_date_field_reused',
    fallback: '当前只有一个日期字段，将同时作为开始和结束日期使用。',
  },
};

export interface ViewManagePanelProps {
  open: boolean;
  onClose: () => void;
  views: SavedView[];
  currentView: SavedView | null;
  onCreateView: (request: SavedViewCreateRequest) => Promise<SavedView>;
  onDeleteView: (pid: string) => Promise<void>;
  onDuplicateView: (pid: string, newName: string) => Promise<void>;
  onEditView?: (pid: string, name: string, description: string, scope: ViewScope) => Promise<void>;
  onSetDefaultView: (pid: string) => Promise<void>;
  onSelectView: (pid: string) => void;
  modelCode: string;
  pageKey?: string;
  startInCreateMode?: boolean;
  activeViewType?: ViewType;
  onCreateViewSuccess?: (view: SavedView) => void;
  fields?: FieldOption[];
  modelPid?: string;
  onFieldsCreated?: () => void;
  onViewConfigSaved?: () => void;
  /** View pids the current user has pinned to the quick-filter chip row. */
  pinnedViewPids?: string[];
  /** Pin a view to the current user's quick-filter chip row. */
  onPinView?: (pid: string) => Promise<void>;
  /** Remove the current user's pin of a view. */
  onUnpinView?: (pid: string) => Promise<void>;
}

function countPersonalManualViews(views: SavedView[]): number {
  return views.filter((view) => view.scope === 'personal' && !view.isImplicit).length;
}

/**
 * Public URL a recipient opens. The backend returns `shareUrl` = the API path
 * (/api/views/shared/{token}); the human-facing page is the /share/{token} route
 * (app/routes/share.$token.tsx).
 */
export function buildPublicShareLink(origin: string, token: string): string {
  return `${(origin || '').replace(/\/+$/, '')}/share/${token}`;
}

function nextAutoViewName(baseName: string, views: SavedView[]): string {
  const usedNames = new Set(
    views.map((view) => String(view.name ?? '').trim().toLowerCase()).filter(Boolean),
  );
  if (!usedNames.has(baseName.toLowerCase())) return baseName;

  let suffix = 2;
  while (usedNames.has(`${baseName} ${suffix}`.toLowerCase())) {
    suffix += 1;
  }
  return `${baseName} ${suffix}`;
}

export const ViewManagePanel: React.FC<ViewManagePanelProps> = ({
  open,
  onClose,
  views,
  currentView,
  onCreateView,
  onDeleteView,
  onDuplicateView,
  onEditView,
  onSetDefaultView,
  onSelectView,
  modelCode,
  pageKey,
  onCreateViewSuccess,
  startInCreateMode,
  fields = [],
  onViewConfigSaved,
  pinnedViewPids = [],
  onPinView,
  onUnpinView,
}) => {
  const { t } = useI18n();
  const tx = useCallback(
    (key: string, fallback: string, params?: Record<string, unknown>) =>
      interpolateMessage(t(key, params, fallback), params),
    [t],
  );
  const [showTypePicker, setShowTypePicker] = useState(false);
  const [configStep, setConfigStep] = useState<{
    viewType: ViewType;
    fields: Record<string, string>;
    capability: SavedViewCapabilityResult;
  } | null>(null);
  const [blockedCapability, setBlockedCapability] = useState<SavedViewCapabilityResult | null>(
    null,
  );
  const [editingView, setEditingView] = useState<SavedView | null>(null);
  const [editForm, setEditForm] = useState({ name: '', description: '' });
  const [duplicatingView, setDuplicatingView] = useState<SavedView | null>(null);
  const [duplicateName, setDuplicateName] = useState('');
  const [manageSearchTerm, setManageSearchTerm] = useState('');
  const [loadingState, setLoadingState] = useState<{
    type: 'create' | 'delete' | 'duplicate' | 'setDefault' | 'rename' | 'share' | 'pin' | null;
    pid?: string;
  }>({ type: null });

  // ── Public share link (GAP-121 producer half) ─────────────────────────────
  const [sharingView, setSharingView] = useState<SavedView | null>(null);
  const [shareStatus, setShareStatus] = useState<SavedViewShareStatus | null>(null);
  const [shareError, setShareError] = useState<string | null>(null);
  const [shareCopied, setShareCopied] = useState(false);

  useEffect(() => {
    if (!open) {
      setLoadingState({ type: null });
      setShowTypePicker(false);
      setConfigStep(null);
      setBlockedCapability(null);
      setEditingView(null);
      setDuplicatingView(null);
      setDuplicateName('');
      setManageSearchTerm('');
      setSharingView(null);
      setShareStatus(null);
      setShareError(null);
      setShareCopied(false);
    }
  }, [open]);

  useEffect(() => {
    if (open && startInCreateMode) {
      setShowTypePicker(true);
      setBlockedCapability(null);
    }
  }, [open, startInCreateMode]);

  useEffect(() => {
    if (!open) return;
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleEscape);
    return () => document.removeEventListener('keydown', handleEscape);
  }, [open, onClose]);

  const personalViews = useMemo(
    () => views.filter((view) => view.scope === 'personal'),
    [views],
  );
  const visiblePersonalViews = useMemo(() => {
    const query = manageSearchTerm.trim().toLowerCase();
    if (!query) return personalViews;
    return personalViews.filter((view) => {
      const viewTypeLabel =
        view.viewType && VIEW_TYPE_OPTIONS.find((option) => option.type === view.viewType)?.fallback;
      const haystack = [
        view.name,
        view.description,
        view.viewType,
        viewTypeLabel,
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      return haystack.includes(query);
    });
  }, [manageSearchTerm, personalViews]);
  const personalViewCount = countPersonalManualViews(views);
  const personalLimitReached = personalViewCount >= PERSONAL_VIEW_LIMIT;
  const quotaText = tx('common.saved_view_personal_quota', '个人视图：{count}/{limit}', {
    count: personalViewCount,
    limit: PERSONAL_VIEW_LIMIT,
  });

  const viewTypeLabels = useMemo(() => {
    return Object.fromEntries(
      VIEW_TYPE_OPTIONS.map((option) => [option.type, tx(option.labelKey, option.fallback)]),
    ) as Record<ViewType, string>;
  }, [tx]);

  const buildAutoViewName = useCallback(
    (viewType: ViewType) => {
      const typeLabel = viewTypeLabels[viewType] || viewType;
      return nextAutoViewName(
        tx('common.saved_view_auto_name', '{type}视图', { type: typeLabel }),
        views,
      );
    },
    [tx, viewTypeLabels, views],
  );

  const capabilityMessages = useCallback(
    (capability: SavedViewCapabilityResult): string[] => {
      if (capability.reasonCodes.length > 0) {
        return capability.reasonCodes.map((code) => {
          const item = CAPABILITY_REASON_I18N[code];
          return item ? tx(item.key, item.fallback) : code;
        });
      }
      return capability.reasons;
    },
    [tx],
  );

  const capabilityForType = useCallback(
    (viewType: ViewType) => checkSavedViewCapability(viewType, fields),
    [fields],
  );

  const createConfiguredStep = useCallback(
    (viewType: ViewType, capability: SavedViewCapabilityResult) => {
      setConfigStep({
        viewType,
        fields: Object.fromEntries(
          Object.entries(capability.suggestedConfig)
            .filter(([, value]) => typeof value === 'string' && value)
            .map(([key, value]) => [key, String(value)]),
        ),
        capability,
      });
      setBlockedCapability(null);
      setShowTypePicker(false);
    },
    [],
  );

  const handleQuickCreate = useCallback(
    async (viewType: ViewType) => {
      if (personalLimitReached) return;
      setBlockedCapability(null);

      const requiredFields = VIEW_TYPE_REQUIRED_FIELDS[viewType];
      if (requiredFields) {
        const capability = capabilityForType(viewType);
        if (capability.status === 'blocked') {
          setBlockedCapability(capability);
          return;
        }
        createConfiguredStep(viewType, capability);
        return;
      }

      setLoadingState({ type: 'create' });
      try {
      const createdView = await onCreateView({
          name: buildAutoViewName(viewType),
          modelCode,
          pageKey,
          scope: 'personal',
          viewType,
          isDefault: false,
        });
        if (createdView?.pid) onSelectView(createdView.pid);
        onCreateViewSuccess?.(createdView);
        setShowTypePicker(false);
        onClose();
      } finally {
        setLoadingState({ type: null });
      }
    },
    [
      capabilityForType,
      createConfiguredStep,
      modelCode,
      onClose,
      onCreateView,
      onCreateViewSuccess,
      onSelectView,
      pageKey,
      personalLimitReached,
      buildAutoViewName,
    ],
  );

  const handleFinishConfigStep = useCallback(async () => {
    if (!configStep || personalLimitReached) return;
    const requirements = VIEW_TYPE_REQUIRED_FIELDS[configStep.viewType] ?? [];
    const missingRequired = requirements
      .filter((field) => field.required)
      .some((field) => !configStep.fields[field.key]);
    if (missingRequired) return;

    const viewConfig = Object.entries(configStep.fields).reduce((acc, [key, value]) => {
      if (value) {
        (acc as Record<string, string>)[key] = value;
      }
      return acc;
    }, {} as Partial<ViewConfig>);

    setLoadingState({ type: 'create' });
    try {
      const createdView = await onCreateView({
        name: buildAutoViewName(configStep.viewType),
        modelCode,
        pageKey,
        scope: 'personal',
        viewType: configStep.viewType,
        viewConfig: {
          ...viewConfig,
          meta: {
            capabilityStatus: configStep.capability.status,
            capabilityReasonCodes: configStep.capability.reasonCodes,
          },
        },
        isDefault: false,
      });
      if (createdView?.pid) onSelectView(createdView.pid);
      onCreateViewSuccess?.(createdView);
      onViewConfigSaved?.();
      setConfigStep(null);
      setShowTypePicker(false);
      onClose();
    } finally {
      setLoadingState({ type: null });
    }
  }, [
    configStep,
    modelCode,
    onClose,
    onCreateView,
    onCreateViewSuccess,
    onSelectView,
    onViewConfigSaved,
    pageKey,
    personalLimitReached,
    buildAutoViewName,
  ]);

  const handleEdit = useCallback((view: SavedView) => {
    if (!canManageSavedView(view)) return;
    setEditingView(view);
    setEditForm({ name: view.name, description: view.description || '' });
    setDuplicatingView(null);
  }, []);

  const handleSaveEdit = useCallback(async () => {
    if (!editingView || !editForm.name.trim()) return;
    setLoadingState({ type: 'rename', pid: editingView.pid });
    try {
      await onEditView?.(
        editingView.pid,
        editForm.name.trim(),
        editForm.description.trim(),
        'personal',
      );
      setEditingView(null);
    } finally {
      setLoadingState({ type: null });
    }
  }, [editForm.description, editForm.name, editingView, onEditView]);

  const handleDuplicateStart = useCallback((view: SavedView) => {
    if (!canCopySavedView(view)) return;
    setDuplicatingView(view);
    setDuplicateName(
      tx('common.saved_view_copy_name', '{name} 副本', {
        name: view.name.trim() || tx('common.saved_view_untitled', '未命名视图'),
      }),
    );
    setEditingView(null);
  }, [tx]);

  const handleDuplicateSubmit = useCallback(async () => {
    if (!duplicatingView || !duplicateName.trim()) return;
    setLoadingState({ type: 'duplicate', pid: duplicatingView.pid });
    try {
      await onDuplicateView(duplicatingView.pid, duplicateName.trim());
      setDuplicatingView(null);
      setDuplicateName('');
    } finally {
      setLoadingState({ type: null });
    }
  }, [duplicateName, duplicatingView, onDuplicateView]);

  const handleTogglePin = useCallback(
    async (view: SavedView) => {
      const pinned = pinnedViewPids.includes(view.pid);
      setLoadingState({ type: 'pin', pid: view.pid });
      try {
        if (pinned) {
          await onUnpinView?.(view.pid);
        } else {
          await onPinView?.(view.pid);
        }
      } finally {
        setLoadingState({ type: null });
      }
    },
    [pinnedViewPids, onPinView, onUnpinView],
  );

  const handleSetDefault = useCallback(
    async (view: SavedView) => {
      if (!canSetDefaultSavedView(view)) return;
      setLoadingState({ type: 'setDefault', pid: view.pid });
      try {
        await onSetDefaultView(view.pid);
      } finally {
        setLoadingState({ type: null });
      }
    },
    [onSetDefaultView],
  );

  const handleDelete = useCallback(
    async (view: SavedView) => {
      if (!canDeleteSavedView(view)) return;
      const confirmed = await confirmDialog({
        content: tx(
          'common.saved_view_delete_confirm',
          '确定删除视图“{name}”？删除后无法恢复。',
          { name: view.name },
        ),
        variant: 'danger',
      });
      if (!confirmed) return;

      setLoadingState({ type: 'delete', pid: view.pid });
      try {
        await onDeleteView(view.pid);
      } finally {
        setLoadingState({ type: null });
      }
    },
    [onDeleteView, tx],
  );

  // ── Public share link handlers ────────────────────────────────────────────

  const handleShareStart = useCallback(
    async (view: SavedView) => {
      if (!canShareSavedView(view)) return;
      setEditingView(null);
      setDuplicatingView(null);
      setShareCopied(false);
      setShareError(null);
      setShareStatus(null);
      setSharingView(view);

      setLoadingState({ type: 'share', pid: view.pid });
      try {
        setShareStatus(await savedViewService.getShareStatus(view.pid));
      } catch (error) {
        setShareError(
          error instanceof Error
            ? error.message
            : tx('common.saved_view_share_status_failed', '获取分享状态失败'),
        );
      } finally {
        setLoadingState({ type: null });
      }
    },
    [tx],
  );

  const handleGenerateShareLink = useCallback(async () => {
    if (!sharingView) return;
    setShareError(null);
    setLoadingState({ type: 'share', pid: sharingView.pid });
    try {
      const result = await savedViewService.shareView(sharingView.pid);
      setShareStatus({
        shared: true,
        token: result.token,
        expiresAt: result.expiresAt ?? null,
        passwordProtected: result.passwordProtected,
      });
      setShareCopied(false);
    } catch (error) {
      setShareError(
        error instanceof Error
          ? error.message
          : tx('common.saved_view_share_create_failed', '生成分享链接失败'),
      );
    } finally {
      setLoadingState({ type: null });
    }
  }, [sharingView, tx]);

  const handleRevokeShareLink = useCallback(async () => {
    if (!sharingView) return;
    const confirmed = await confirmDialog({
      content: tx(
        'common.saved_view_share_revoke_confirm',
        '撤销后该链接立即失效，已拿到链接的人将无法访问。确定撤销？',
      ),
      variant: 'danger',
    });
    if (!confirmed) return;

    setShareError(null);
    setLoadingState({ type: 'share', pid: sharingView.pid });
    try {
      await savedViewService.revokeShare(sharingView.pid);
      setShareStatus({ shared: false });
      setShareCopied(false);
    } catch (error) {
      setShareError(
        error instanceof Error
          ? error.message
          : tx('common.saved_view_share_revoke_failed', '撤销分享链接失败'),
      );
    } finally {
      setLoadingState({ type: null });
    }
  }, [sharingView, tx]);

  const shareLink =
    shareStatus?.shared && shareStatus.token
      ? buildPublicShareLink(
          typeof window !== 'undefined' ? window.location.origin : '',
          shareStatus.token,
        )
      : '';

  const handleCopyShareLink = useCallback(async () => {
    if (!shareLink) return;
    try {
      await navigator.clipboard.writeText(shareLink);
      setShareCopied(true);
      window.setTimeout(() => setShareCopied(false), 2000);
    } catch {
      // Clipboard unavailable (insecure context / denied) — say so instead of
      // flashing a success state the user cannot act on.
      setShareError(tx('common.saved_view_share_copy_failed', '复制失败，请手动复制链接'));
    }
  }, [shareLink, tx]);

  const isViewLoading = (pid: string) => loadingState.pid === pid && loadingState.type !== null;

  if (!open) return null;

  return (
    <>
      <div
        className="fixed inset-0 z-[1100] bg-black/50 transition-opacity duration-200"
        onClick={onClose}
        aria-hidden="true"
      />

      <div
        className={cn(
          'fixed top-0 right-0 z-[1110] h-full w-[min(95vw,52rem)] bg-white shadow-xl',
          'flex flex-col animate-in slide-in-from-right duration-200',
        )}
        role="dialog"
        aria-modal="true"
        aria-labelledby="view-manage-panel-title"
        data-testid="saved-view-manage-panel"
      >
        <div className="flex items-center justify-between border-b border-gray-200 px-5 py-4">
          <div>
            <h2 id="view-manage-panel-title" className="text-lg font-semibold text-gray-900">
              {tx('common.saved_view_manage', '管理视图')}
            </h2>
            <p className="mt-0.5 text-xs text-gray-500">
              {tx('common.saved_view_panel_subtitle', '管理当前列表的个人视图')}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md p-2 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            aria-label={tx('common.close', 'Close')}
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto">
          {configStep ? (
            <div className="p-5">
              <div className="mb-3">
                <h3 className="text-base font-semibold text-gray-900">
                  {tx('common.saved_view_config_title', '配置{type}视图', {
                    type: viewTypeLabels[configStep.viewType] || configStep.viewType,
                  })}
                </h3>
                <p className="mt-1 text-sm text-gray-500">
                  {tx(
                    'common.saved_view_config_help',
                    '选择这个视图需要使用的字段。必填字段完成后才能保存。',
                  )}
                </p>
              </div>

              {configStep.capability.status === 'degraded' && (
                <div
                  className="mb-4 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800"
                  role="status"
                  data-testid={`view-capability-degraded-${configStep.viewType}`}
                >
                  {capabilityMessages(configStep.capability).join(' ')}
                </div>
              )}

              <div className="space-y-4">
                {(VIEW_TYPE_REQUIRED_FIELDS[configStep.viewType] ?? []).map((fieldDef) => {
                  const options = configStep.capability.fieldOptions[fieldDef.key] ?? fields;
                  const fieldId = `saved-view-config-${fieldDef.key}`;
                  return (
                    <div key={fieldDef.key}>
                      <label
                        htmlFor={fieldId}
                        className="mb-1 block text-sm font-medium text-gray-700"
                      >
                        {tx(fieldDef.labelKey, fieldDef.fallback)}
                        {fieldDef.required && <span className="ml-1 text-red-500">*</span>}
                      </label>
                      <select
                        id={fieldId}
                        data-testid={`saved-view-config-field-${fieldDef.key}`}
                        value={configStep.fields[fieldDef.key] || ''}
                        onChange={(event) =>
                          setConfigStep((prev) =>
                            prev
                              ? {
                                  ...prev,
                                  fields: {
                                    ...prev.fields,
                                    [fieldDef.key]: event.target.value,
                                  },
                                }
                              : null,
                          )
                        }
                        className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                      >
                        <option value="">
                          {tx('common.saved_view_select_field', '选择字段')}
                        </option>
                        {options.map((field) => (
                          <option key={field.code} value={field.code}>
                            {field.name} ({field.dataType || 'string'})
                          </option>
                        ))}
                      </select>
                    </div>
                  );
                })}
              </div>

              <div className="mt-6 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setConfigStep(null);
                    setShowTypePicker(true);
                  }}
                  className="rounded-md px-3 py-2 text-sm text-gray-600 hover:bg-gray-100"
                  data-testid="saved-view-config-cancel"
                >
                  {tx('common.saved_view_create_cancel', '取消')}
                </button>
                <button
                  type="button"
                  onClick={handleFinishConfigStep}
                  disabled={
                    loadingState.type === 'create' ||
                    personalLimitReached ||
                    (VIEW_TYPE_REQUIRED_FIELDS[configStep.viewType] ?? [])
                      .filter((field) => field.required)
                      .some((field) => !configStep.fields[field.key])
                  }
                  className="rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
                  data-testid="saved-view-config-save"
                >
                  {loadingState.type === 'create'
                    ? tx('common.saved_view_create_saving', '保存中...')
                    : tx('common.saved_view_create_save', '保存视图')}
                </button>
              </div>
            </div>
          ) : (
            <>
              <div className="border-b border-gray-200 p-5">
                {showTypePicker ? (
                  <div>
                    <div className="mb-3 flex items-center justify-between gap-3">
                      <div>
                        <div className="text-xs font-semibold tracking-wide text-gray-500 uppercase">
                          {tx('common.saved_view_choose_type', '选择视图类型')}
                        </div>
                        <div
                          className={cn(
                            'mt-2 rounded-md border px-3 py-2 text-xs',
                            personalLimitReached
                              ? 'border-red-200 bg-red-50 text-red-700'
                              : 'border-gray-200 bg-gray-50 text-gray-600',
                          )}
                          data-testid="saved-view-quota-status"
                        >
                          {quotaText}
                          {personalLimitReached && (
                            <span data-testid="saved-view-quota-limit-reached">
                              {' '}
                              {tx(
                                'common.saved_view_personal_quota_reached',
                                '已达到 {limit} 个个人视图上限，请删除或复用已有视图',
                                { limit: PERSONAL_VIEW_LIMIT },
                              )}
                            </span>
                          )}
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={() => {
                          setShowTypePicker(false);
                          setBlockedCapability(null);
                        }}
                        className="rounded-md px-2 py-1 text-sm text-gray-500 hover:bg-gray-100"
                        data-testid="saved-view-type-picker-cancel"
                      >
                        {tx('common.saved_view_cancel', '取消')}
                      </button>
                    </div>

                    {blockedCapability && (
                      <div
                        className="mb-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
                        role="alert"
                        data-testid={`view-capability-blocked-${blockedCapability.viewType}`}
                      >
                        {capabilityMessages(blockedCapability).join(' ')}
                      </div>
                    )}

                    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                      {VIEW_TYPE_OPTIONS.map((option) => {
                        const capability = VIEW_TYPE_REQUIRED_FIELDS[option.type]
                          ? capabilityForType(option.type)
                          : null;
                        const status =
                          capability?.status === 'blocked'
                            ? tx('common.saved_view_type_status_blocked', '不适合')
                            : capability?.status === 'degraded'
                              ? tx('common.saved_view_type_status_degraded', '需注意')
                              : tx('common.saved_view_type_status_available', '可创建');
                        return (
                          <button
                            key={option.type}
                            type="button"
                            onClick={() => handleQuickCreate(option.type)}
                            disabled={personalLimitReached}
                            data-testid={`saved-view-type-${option.type}`}
                            className={cn(
                              'flex min-h-20 flex-col items-start justify-between rounded-md border border-gray-200 px-3 py-2 text-left transition-colors',
                              'hover:border-blue-400 hover:bg-blue-50 focus:ring-2 focus:ring-blue-500 focus:outline-none',
                              'disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:border-gray-200 disabled:hover:bg-white',
                            )}
                          >
                            <span className="text-sm font-medium text-gray-900">
                              {tx(option.labelKey, option.fallback)}
                            </span>
                            <span
                              className={cn(
                                'mt-2 rounded px-1.5 py-0.5 text-[11px] font-medium',
                                capability?.status === 'blocked'
                                  ? 'bg-red-50 text-red-700'
                                  : capability?.status === 'degraded'
                                    ? 'bg-amber-50 text-amber-700'
                                    : 'bg-green-50 text-green-700',
                              )}
                            >
                              {status}
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                ) : (
                  <div className="space-y-2">
                    <button
                      type="button"
                      onClick={() => setShowTypePicker(true)}
                      className="flex w-full items-center justify-center gap-2 rounded-md border border-dashed border-blue-300 bg-blue-50 py-3 text-sm font-medium text-blue-700 transition-colors hover:bg-blue-100 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      data-testid="saved-view-create-personal"
                    >
                      <Plus className="h-4 w-4" aria-hidden="true" />
                      {tx('common.saved_view_new_personal', '新建个人视图')}
                    </button>
                    <div
                      className={cn(
                        'rounded-md border px-3 py-2 text-xs',
                        personalLimitReached
                          ? 'border-red-200 bg-red-50 text-red-700'
                          : 'border-gray-200 bg-gray-50 text-gray-600',
                      )}
                      data-testid="saved-view-quota-summary"
                    >
                      {quotaText}
                      {personalLimitReached && (
                        <span>
                          {' '}
                          {tx(
                            'common.saved_view_personal_quota_reached',
                            '已达到 {limit} 个个人视图上限，请删除或复用已有视图',
                            { limit: PERSONAL_VIEW_LIMIT },
                          )}
                        </span>
                      )}
                    </div>
                  </div>
                )}
              </div>

              <div className="py-2">
                <div className="border-b border-gray-100 px-5 py-3">
                  <label className="relative block">
                    <Search
                      className="pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-gray-400"
                      aria-hidden="true"
                    />
                    <input
                      type="search"
                      value={manageSearchTerm}
                      onChange={(event) => setManageSearchTerm(event.currentTarget.value)}
                      placeholder={tx(
                        'common.saved_view_manage_search_placeholder',
                        '搜索我的视图...',
                      )}
                      aria-label={tx(
                        'common.saved_view_manage_search_placeholder',
                        '搜索我的视图...',
                      )}
                      className="w-full rounded-md border border-gray-200 py-2 pr-3 pl-9 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                      data-testid="saved-view-manage-search"
                    />
                  </label>
                </div>
                <div className="px-5 py-2 text-xs font-semibold tracking-wide text-gray-500 uppercase">
                  {tx('common.saved_view_personal_group', '个人视图')}
                </div>
                {personalViews.length === 0 ? (
                  <div className="px-5 py-8 text-center text-sm text-gray-500">
                    <div>{tx('common.saved_view_empty', '暂无保存视图')}</div>
                    <div className="mt-1 text-xs">
                      {tx(
                        'common.saved_view_empty_hint',
                        '创建一个个人视图后，可保存当前筛选、字段和排序。',
                      )}
                    </div>
                  </div>
                ) : visiblePersonalViews.length === 0 ? (
                  <div
                    className="px-5 py-8 text-center text-sm text-gray-500"
                    data-testid="saved-view-manage-no-results"
                  >
                    {tx('common.saved_view_manage_no_results', '没有匹配的个人视图')}
                  </div>
                ) : (
                  visiblePersonalViews.map((view) => (
                    <div
                      key={view.pid}
                      className="px-3 py-1"
                      data-testid={`saved-view-row-${view.pid}`}
                    >
                      {editingView?.pid === view.pid ? (
                        <div className="rounded-md border border-blue-200 bg-blue-50 p-3">
                          <div className="space-y-3">
                            <label className="block">
                              <span className="mb-1 block text-xs font-medium text-gray-600">
                                {tx('common.saved_view_edit_name', '视图名称')}
                              </span>
                              <input
                                type="text"
                                data-testid={`saved-view-edit-name-${view.pid}`}
                                value={editForm.name}
                                onChange={(event) =>
                                  setEditForm((prev) => ({
                                    ...prev,
                                    name: event.target.value,
                                  }))
                                }
                                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                                autoFocus
                              />
                            </label>
                            <label className="block">
                              <span className="mb-1 block text-xs font-medium text-gray-600">
                                {tx('common.saved_view_edit_description', '说明')}
                              </span>
                              <input
                                type="text"
                                data-testid={`saved-view-edit-description-${view.pid}`}
                                value={editForm.description}
                                onChange={(event) =>
                                  setEditForm((prev) => ({
                                    ...prev,
                                    description: event.target.value,
                                  }))
                                }
                                placeholder={tx(
                                  'common.saved_view_edit_description_placeholder',
                                  '可选说明',
                                )}
                                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                              />
                            </label>
                            <div className="flex justify-end gap-2">
                              <button
                                type="button"
                                onClick={() => setEditingView(null)}
                                className="rounded-md px-3 py-2 text-sm text-gray-600 hover:bg-gray-100"
                                data-testid={`saved-view-edit-cancel-${view.pid}`}
                              >
                                {tx('common.saved_view_cancel', '取消')}
                              </button>
                              <button
                                type="button"
                                onClick={handleSaveEdit}
                                disabled={!editForm.name.trim() || loadingState.type === 'rename'}
                                className="rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                                data-testid={`saved-view-edit-save-${view.pid}`}
                              >
                                {tx('common.saved_view_edit_save', '保存')}
                              </button>
                            </div>
                          </div>
                        </div>
                      ) : duplicatingView?.pid === view.pid ? (
                        <div className="rounded-md border border-blue-200 bg-blue-50 p-3">
                          <h3 className="mb-3 text-sm font-semibold text-gray-900">
                            {tx('common.saved_view_duplicate_title', '复制个人视图')}
                          </h3>
                          <label className="block">
                            <span className="mb-1 block text-xs font-medium text-gray-600">
                              {tx('common.saved_view_duplicate_name', '新视图名称')}
                            </span>
                            <input
                              type="text"
                              data-testid={`saved-view-duplicate-name-${view.pid}`}
                              value={duplicateName}
                              onChange={(event) => setDuplicateName(event.target.value)}
                              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                              autoFocus
                            />
                          </label>
                          <div className="mt-3 flex justify-end gap-2">
                            <button
                              type="button"
                              onClick={() => setDuplicatingView(null)}
                              className="rounded-md px-3 py-2 text-sm text-gray-600 hover:bg-gray-100"
                              data-testid={`saved-view-duplicate-cancel-${view.pid}`}
                            >
                              {tx('common.saved_view_cancel', '取消')}
                            </button>
                            <button
                              type="button"
                              onClick={handleDuplicateSubmit}
                              disabled={!duplicateName.trim() || loadingState.type === 'duplicate'}
                              className="rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                              data-testid={`saved-view-duplicate-submit-${view.pid}`}
                            >
                              {tx('common.saved_view_duplicate_submit', '创建副本')}
                            </button>
                          </div>
                        </div>
                      ) : sharingView?.pid === view.pid ? (
                        <div
                          className="rounded-md border border-blue-200 bg-blue-50 p-3"
                          data-testid={`saved-view-share-panel-${view.pid}`}
                        >
                          <div className="mb-3 flex items-center justify-between">
                            <h3 className="text-sm font-semibold text-gray-900">
                              {tx('common.saved_view_share_title', '公开分享链接')}
                            </h3>
                            <button
                              type="button"
                              onClick={() => setSharingView(null)}
                              className="rounded-md p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
                              aria-label={tx('common.saved_view_cancel', '取消')}
                              data-testid={`saved-view-share-close-${view.pid}`}
                            >
                              <X className="h-4 w-4" aria-hidden="true" />
                            </button>
                          </div>

                          {shareError && (
                            <p
                              role="alert"
                              className="mb-2 rounded-md border border-red-200 bg-red-50 px-2 py-1.5 text-xs text-red-700"
                              data-testid={`saved-view-share-error-${view.pid}`}
                            >
                              {shareError}
                            </p>
                          )}

                          {loadingState.type === 'share' && loadingState.pid === view.pid ? (
                            <p
                              className="text-xs text-gray-500"
                              data-testid={`saved-view-share-loading-${view.pid}`}
                            >
                              {tx('common.saved_view_share_loading', '加载中...')}
                            </p>
                          ) : shareStatus?.shared && shareLink ? (
                            <div className="space-y-2">
                              <p
                                className="text-xs text-gray-600"
                                data-testid={`saved-view-share-state-${view.pid}`}
                              >
                                {tx('common.saved_view_share_active', '链接已开启，任何人可通过链接查看')}
                              </p>
                              <div className="flex items-center gap-2">
                                <input
                                  type="text"
                                  readOnly
                                  value={shareLink}
                                  onFocus={(event) => event.currentTarget.select()}
                                  className="w-full rounded-md border border-gray-300 bg-white px-3 py-2 font-mono text-xs text-gray-700"
                                  data-testid={`saved-view-share-link-${view.pid}`}
                                  aria-label={tx('common.saved_view_share_title', '公开分享链接')}
                                />
                                <button
                                  type="button"
                                  onClick={handleCopyShareLink}
                                  className="inline-flex flex-shrink-0 items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                                  data-testid={`saved-view-share-copy-${view.pid}`}
                                >
                                  {shareCopied ? (
                                    <Check className="h-4 w-4 text-green-600" aria-hidden="true" />
                                  ) : (
                                    <Copy className="h-4 w-4" aria-hidden="true" />
                                  )}
                                  <span>
                                    {shareCopied
                                      ? tx('common.saved_view_share_copied', '已复制')
                                      : tx('common.saved_view_share_copy', '复制')}
                                  </span>
                                </button>
                              </div>
                              {shareStatus.expiresAt && (
                                <p
                                  className="text-xs text-gray-500"
                                  data-testid={`saved-view-share-expires-${view.pid}`}
                                >
                                  {tx('common.saved_view_share_expires_at', '过期时间：{time}', {
                                    time: shareStatus.expiresAt,
                                  })}
                                </p>
                              )}
                              <div className="flex justify-end">
                                <button
                                  type="button"
                                  onClick={handleRevokeShareLink}
                                  className="rounded-md px-3 py-2 text-sm font-medium text-red-600 hover:bg-red-50"
                                  data-testid={`saved-view-share-revoke-${view.pid}`}
                                >
                                  {tx('common.saved_view_share_revoke', '撤销链接')}
                                </button>
                              </div>
                            </div>
                          ) : (
                            <div className="space-y-2">
                              <p
                                className="text-xs text-gray-600"
                                data-testid={`saved-view-share-state-${view.pid}`}
                              >
                                {tx(
                                  'common.saved_view_share_inactive',
                                  '该视图尚未生成公开链接。生成后，任何拿到链接的人无需登录即可查看。',
                                )}
                              </p>
                              <div className="flex justify-end">
                                <button
                                  type="button"
                                  onClick={handleGenerateShareLink}
                                  className="rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                                  data-testid={`saved-view-share-generate-${view.pid}`}
                                >
                                  {tx('common.saved_view_share_generate', '生成分享链接')}
                                </button>
                              </div>
                            </div>
                          )}
                        </div>
                      ) : (
                        <div
                          className={cn(
                            'rounded-md px-2 py-2 transition-colors hover:bg-gray-50',
                            currentView?.pid === view.pid && 'bg-blue-50',
                          )}
                        >
                          <div className="flex items-start gap-2">
                            <button
                              type="button"
                              onClick={() => onSelectView(view.pid)}
                              className="min-w-0 flex-1 text-left"
                              data-testid={`saved-view-select-${view.pid}`}
                            >
                              <div className="flex min-w-0 items-center gap-2">
                                <span
                                  className={cn(
                                    'truncate text-sm font-medium',
                                    currentView?.pid === view.pid
                                      ? 'text-blue-700'
                                      : 'text-gray-900',
                                  )}
                                >
                                  {view.name}
                                </span>
                                {view.viewType && view.viewType !== 'table' && (
                                  <span className="flex-shrink-0 rounded bg-purple-100 px-1.5 py-0.5 text-xs font-medium text-purple-700">
                                    {viewTypeLabels[view.viewType as ViewType] || view.viewType}
                                  </span>
                                )}
                                {view.isDefault && (
                                  <span className="flex-shrink-0 rounded bg-green-100 px-1.5 py-0.5 text-xs font-medium text-green-700">
                                    {tx('common.saved_view_default', '默认')}
                                  </span>
                                )}
                                {isSavedViewLockedPreset(view) && (
                                  <span
                                    className="flex-shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-xs font-medium text-gray-600"
                                    data-testid={`view-locked-preset-${view.pid}`}
                                  >
                                    {tx('common.saved_view_locked_preset', '预置')}
                                  </span>
                                )}
                              </div>
                              {view.description && (
                                <p className="mt-0.5 truncate text-xs text-gray-500">
                                  {view.description}
                                </p>
                              )}
                            </button>

                            <div className="flex flex-shrink-0 items-center gap-1">
                              {(onPinView || onUnpinView) && (
                                <button
                                  type="button"
                                  onClick={() => handleTogglePin(view)}
                                  disabled={isViewLoading(view.pid)}
                                  className="rounded-md p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-accent disabled:cursor-not-allowed disabled:opacity-50"
                                  data-testid={`saved-view-action-pin-${view.pid}`}
                                  data-pinned={pinnedViewPids.includes(view.pid) ? 'true' : 'false'}
                                  aria-label={
                                    pinnedViewPids.includes(view.pid)
                                      ? tx('common.saved_view_action_unpin_chip', '取消快捷筛选')
                                      : tx('common.saved_view_action_pin_chip', '钉为快捷筛选')
                                  }
                                  title={
                                    pinnedViewPids.includes(view.pid)
                                      ? tx('common.saved_view_action_unpin_chip', '取消快捷筛选')
                                      : tx('common.saved_view_action_pin_chip', '钉为快捷筛选')
                                  }
                                >
                                  {loadingState.type === 'pin' && loadingState.pid === view.pid ? (
                                    <span className="block h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-accent" />
                                  ) : (
                                    <Pin
                                      className={`h-4 w-4 ${
                                        pinnedViewPids.includes(view.pid)
                                          ? 'fill-current text-accent'
                                          : ''
                                      }`}
                                      aria-hidden="true"
                                    />
                                  )}
                                </button>
                              )}
                              <button
                                type="button"
                                onClick={() => handleSetDefault(view)}
                                disabled={isViewLoading(view.pid) || !canSetDefaultSavedView(view)}
                                className="rounded-md p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-yellow-500 disabled:cursor-not-allowed disabled:opacity-50"
                                data-testid={`saved-view-action-set-default-${view.pid}`}
                                aria-label={
                                  view.isDefault
                                    ? tx('common.saved_view_action_default', '默认视图')
                                    : tx('common.saved_view_action_set_default', '设为默认')
                                }
                                title={
                                  view.isDefault
                                    ? tx('common.saved_view_action_default', '默认视图')
                                    : tx('common.saved_view_action_set_default', '设为默认')
                                }
                              >
                                {loadingState.type === 'setDefault' &&
                                loadingState.pid === view.pid ? (
                                  <span className="block h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-yellow-500" />
                                ) : view.isDefault ? (
                                  <Star className="h-4 w-4 fill-current" aria-hidden="true" />
                                ) : (
                                  <Star className="h-4 w-4" aria-hidden="true" />
                                )}
                              </button>

                              {onEditView && (
                                <button
                                  type="button"
                                  onClick={() => handleEdit(view)}
                                  disabled={isViewLoading(view.pid) || !canManageSavedView(view)}
                                  className="rounded-md p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-green-600 disabled:cursor-not-allowed disabled:opacity-50"
                                  data-testid={`saved-view-action-edit-${view.pid}`}
                                  aria-label={tx('common.saved_view_action_edit', '重命名视图')}
                                  title={tx('common.saved_view_action_edit', '重命名视图')}
                                >
                                  <Pencil className="h-4 w-4" aria-hidden="true" />
                                </button>
                              )}

                              <button
                                type="button"
                                onClick={() => handleDuplicateStart(view)}
                                disabled={isViewLoading(view.pid) || !canCopySavedView(view)}
                                className="rounded-md p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-blue-600 disabled:cursor-not-allowed disabled:opacity-50"
                                data-testid={`saved-view-action-copy-${view.pid}`}
                                aria-label={tx('common.saved_view_action_copy', '复制视图')}
                                title={tx('common.saved_view_action_copy', '复制视图')}
                              >
                                <Copy className="h-4 w-4" aria-hidden="true" />
                              </button>

                              {/*
                                Rendered only when this view can actually be shared, not rendered
                                disabled. The backend offers the `share` action for team and global
                                views alone (SavedViewServiceImpl.resolveActions) while this panel
                                lists personal views, so a button rendered unconditionally is a
                                greyed-out icon on every row that nobody can ever click and nothing
                                explains. The plumbing below it is complete: the day a view becomes
                                shareable, canShareSavedView says so and the button appears.
                              */}
                              {canShareSavedView(view) && (
                                <button
                                  type="button"
                                  onClick={() => handleShareStart(view)}
                                  disabled={isViewLoading(view.pid)}
                                  className="rounded-md p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-blue-600 disabled:cursor-not-allowed disabled:opacity-50"
                                  data-testid={`saved-view-action-share-${view.pid}`}
                                  aria-label={tx('common.saved_view_action_share', '生成分享链接')}
                                  title={tx('common.saved_view_action_share', '生成分享链接')}
                                >
                                  <Link2 className="h-4 w-4" aria-hidden="true" />
                                </button>
                              )}

                              <button
                                type="button"
                                onClick={() => handleDelete(view)}
                                disabled={isViewLoading(view.pid) || !canDeleteSavedView(view)}
                                className="rounded-md p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-red-600 disabled:cursor-not-allowed disabled:opacity-50"
                                data-testid={`saved-view-action-delete-${view.pid}`}
                                aria-label={tx('common.saved_view_action_delete', '删除视图')}
                                title={tx('common.saved_view_action_delete', '删除视图')}
                              >
                                {loadingState.type === 'delete' && loadingState.pid === view.pid ? (
                                  <span className="block h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-red-500" />
                                ) : (
                                  <Trash2 className="h-4 w-4" aria-hidden="true" />
                                )}
                              </button>
                            </div>
                          </div>
                        </div>
                      )}
                    </div>
                  ))
                )}
              </div>
            </>
          )}
        </div>
      </div>
    </>
  );
};

export default ViewManagePanel;
