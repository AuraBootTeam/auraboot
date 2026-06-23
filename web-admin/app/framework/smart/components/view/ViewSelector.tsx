/**
 * ViewSelector Component
 *
 * A dropdown component for selecting saved views.
 * This release exposes personal saved views only; team/global scopes remain roadmap.
 */

import React, { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import { AlertTriangle, Check, ChevronDown, Lock, Plus, Settings, User } from 'lucide-react';
import { type SavedView, type ViewScope, type ViewType } from '~/framework/smart/types/savedView';
import type { ViewRecommendation } from '~/framework/smart/hooks/useViewRecommendations';
import {
  isImplicitSavedView,
  isSavedViewLockedPreset,
} from '~/framework/smart/utils/savedViewPersistence';
import { useI18n } from '~/contexts/I18nContext';
import { cn } from '~/utils/cn';

/**
 * Props for ViewSelector component
 */
export interface ViewSelectorProps {
  /** List of available views */
  views: SavedView[];
  /** Currently selected view */
  currentView: SavedView | null;
  /** Callback when a view is selected */
  onSelectView: (pid: string) => void;
  /** Callback when the user returns to the implicit default baseline */
  onSelectDefaultView?: () => void;
  /** Callback to open view management panel */
  onManageViews?: () => void;
  /** Callback to create a new view */
  onCreateView?: (viewType?: ViewType) => void;
  /** Currently active view type filter */
  activeViewType?: ViewType;
  /** Callback when view type filter changes */
  onViewTypeChange?: (viewType: ViewType) => void;
  /** AI view recommendations based on model fields */
  recommendations?: ViewRecommendation[];
  /** Loading state */
  loading?: boolean;
  /** Custom CSS class */
  className?: string;
}

/**
 * Scope configuration for display
 */
interface ScopeConfig {
  scope: ViewScope;
  labelKey: string;
  fallback: string;
  shortLabelKey: string;
  shortFallback: string;
  Icon: React.ComponentType<{ className?: string }>;
}

/**
 * Ordered scope configurations for grouping views.
 *
 * Personal-only release rule: do not render team/global groups from this selector.
 */
const SCOPE_CONFIGS: ScopeConfig[] = [
  {
    scope: 'personal',
    labelKey: 'common.saved_view_personal_group',
    fallback: '个人视图',
    shortLabelKey: 'common.saved_view_scope_personal',
    shortFallback: '我的',
    Icon: User,
  },
];

/**
 * Get scope configuration by scope type
 */
const getScopeConfig = (scope: ViewScope): ScopeConfig => {
  const config = SCOPE_CONFIGS.find((c) => c.scope === scope);
  return config ?? SCOPE_CONFIGS[0];
};

function isSavedViewCapabilityBlocked(view: SavedView | null | undefined): boolean {
  return view?.viewConfig?.meta?.capabilityStatus?.toLowerCase() === 'blocked';
}

/**
 * SVG icons for view types
 */
const ViewTypeIcon: React.FC<{ type: string; className?: string }> = ({
  type,
  className = 'w-4 h-4',
}) => {
  switch (type) {
    case 'table-cells':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M3 10h18M3 14h18M3 6h18M3 18h18"
          />
        </svg>
      );
    case 'view-columns':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M9 4h6v16H9V4zM3 4h4v16H3V4zM17 4h4v16h-4V4z"
          />
        </svg>
      );
    case 'calendar':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
          />
        </svg>
      );
    case 'squares-2x2':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M4 5a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM14 5a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1h-4a1 1 0 01-1-1V5zM4 15a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1H5a1 1 0 01-1-1v-4zM14 15a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1h-4a1 1 0 01-1-1v-4z"
          />
        </svg>
      );
    case 'bars-3-bottom-left':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M4 6h12M4 12h16M4 18h8"
          />
        </svg>
      );
    case 'list-bullet':
      return (
        <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"
          />
        </svg>
      );
    default:
      return null;
  }
};

/**
 * Get view type badge for a view item
 */
const getViewTypeIcon = (viewType?: string): string => {
  switch (viewType) {
    case 'kanban':
      return 'view-columns';
    case 'calendar':
      return 'calendar';
    case 'gallery':
      return 'squares-2x2';
    case 'gantt':
      return 'bars-3-bottom-left';
    case 'tree':
      return 'list-bullet';
    case 'form':
      return 'clipboard-document-list';
    default:
      return 'table-cells';
  }
};

/**
 * ViewSelector - A dropdown for selecting saved views
 *
 * @example
 * // Basic usage
 * <ViewSelector
 *   views={savedViews}
 *   currentView={currentView}
 *   onSelectView={(pid) => setCurrentViewPid(pid)}
 * />
 *
 * @example
 * // With management options
 * <ViewSelector
 *   views={savedViews}
 *   currentView={currentView}
 *   onSelectView={(pid) => setCurrentViewPid(pid)}
 *   onCreateView={() => setShowCreateModal(true)}
 *   onManageViews={() => setShowManagePanel(true)}
 * />
 */
export const ViewSelector: React.FC<ViewSelectorProps> = ({
  views,
  currentView,
  onSelectView,
  onSelectDefaultView,
  onManageViews,
  onCreateView,
  activeViewType,
  // onViewTypeChange kept in interface for new-view creation flow
  loading = false,
  className,
}) => {
  const { t } = useI18n();
  const [isOpen, setIsOpen] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const containerRef = useRef<HTMLDivElement>(null);
  const selectViewLabel = t('common.saved_view_select', undefined, '选择视图');
  const defaultLabel = t('common.saved_view_default', undefined, '默认');
  const defaultViewLabel = t('common.saved_view_default_view', undefined, '默认视图');
  const newViewLabel = t('common.saved_view_new_personal', undefined, '新建个人视图');
  const manageLabel = t('common.saved_view_manage', undefined, '管理视图');
  const emptyLabel = t('common.saved_view_empty', undefined, '暂无保存视图');
  const searchPlaceholder = t('common.saved_view_search_placeholder', undefined, '搜索我的视图...');
  const lockedPresetLabel = t('common.saved_view_locked_preset', undefined, '预置');
  const capabilityBlockedLabel = t('common.saved_view_capability_blocked', undefined, '需配置');
  const displayCurrentView =
    currentView?.scope === 'personal' && !isImplicitSavedView(currentView) ? currentView : null;
  const isDefaultBaselineActive = !displayCurrentView;
  const currentScopeConfig = getScopeConfig(displayCurrentView?.scope ?? 'personal');
  const CurrentScopeIcon = currentScopeConfig.Icon;
  const isCurrentViewLockedPreset = isSavedViewLockedPreset(displayCurrentView);
  const isCurrentViewCapabilityBlocked = isSavedViewCapabilityBlocked(displayCurrentView);
  const currentScopeLabel = t(
    currentScopeConfig.shortLabelKey,
    undefined,
    currentScopeConfig.shortFallback,
  );

  /**
   * Close dropdown when clicking outside
   */
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };

    if (isOpen) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => {
        document.removeEventListener('mousedown', handleClickOutside);
      };
    }
  }, [isOpen]);

  /**
   * Close dropdown on escape key
   */
  useEffect(() => {
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setIsOpen(false);
      }
    };

    if (isOpen) {
      document.addEventListener('keydown', handleEscape);
      return () => {
        document.removeEventListener('keydown', handleEscape);
      };
    }
  }, [isOpen]);

  /**
   * Click handler: open the selector dropdown from the title area.
   */
  const handleClick = useCallback(() => {
    if (!loading) {
      setIsOpen((prev) => !prev);
    }
  }, [loading]);

  /**
   * Handle view selection
   */
  const handleSelectView = useCallback(
    (pid: string) => {
      onSelectView(pid);
      setIsOpen(false);
    },
    [onSelectView],
  );

  const handleSelectDefaultView = useCallback(() => {
    onSelectDefaultView?.();
    setIsOpen(false);
  }, [onSelectDefaultView]);

  /**
   * Handle create view click
   */
  const handleCreateView = useCallback(() => {
    onCreateView?.(activeViewType);
    setIsOpen(false);
  }, [onCreateView, activeViewType]);

  /**
   * Handle manage views click
   */
  const handleManageViews = useCallback(() => {
    onManageViews?.();
    setIsOpen(false);
  }, [onManageViews]);

  /**
   * Group personal views only. Team/global views may exist in API responses for
   * historical data, but they are not part of the current user-facing release.
   */
  const groupedViews = useMemo(() => {
    const normalizedSearch = searchTerm.trim().toLowerCase();
    return SCOPE_CONFIGS.map((config) => ({
      ...config,
      label: t(config.labelKey, undefined, config.fallback),
      views: views
        .filter((v) => v.scope === 'personal' && !isImplicitSavedView(v))
        .filter((v) => {
          if (!normalizedSearch) return true;
          const haystack = `${v.name} ${v.description ?? ''} ${v.viewType ?? ''}`.toLowerCase();
          return haystack.includes(normalizedSearch);
        }),
    })).filter((group) => group.views.length > 0);
  }, [searchTerm, t, views]);

  const hasActions = onCreateView || onManageViews;

  return (
    <div ref={containerRef} className={cn('relative inline-flex items-center gap-1', className)}>
      {/* Trigger Button */}
      <button
        type="button"
        onClick={handleClick}
        disabled={loading}
        aria-label={selectViewLabel}
        className={cn(
          'border-border bg-panel text-text flex items-center gap-2 rounded-md border px-3 py-2 text-sm',
          'hover:bg-hover focus:border-accent focus:shadow-focus focus:outline-none',
          'disabled:cursor-not-allowed disabled:opacity-50',
          'transition-colors duration-150',
          'max-w-[240px] min-w-[140px]',
        )}
        aria-haspopup="listbox"
        aria-expanded={isOpen}
        data-testid="view-selector-trigger"
        data-current-view-name={displayCurrentView?.name || ''}
        data-current-view-type={displayCurrentView?.viewType || ''}
      >
        {loading ? (
          <>
            <span
              className="border-border-strong border-t-accent h-4 w-4 animate-spin rounded-full border-2"
              aria-hidden="true"
            />
            <span className="text-text-3">{t('common.loading', undefined, 'Loading...')}</span>
          </>
        ) : displayCurrentView ? (
          <>
            <CurrentScopeIcon className="text-text-3 h-4 w-4 flex-shrink-0" aria-hidden="true" />
            <span
              className="bg-subtle text-text-2 flex-shrink-0 rounded px-1.5 py-0.5 text-[11px] font-medium"
              data-testid="view-selector-scope-label"
            >
              {currentScopeLabel}
            </span>
            <span className="text-text flex-1 truncate text-left">{displayCurrentView.name}</span>
            {displayCurrentView.isDefault && (
              <span className="text-accent flex-shrink-0 text-xs font-medium" title={defaultLabel}>
                *
              </span>
            )}
            {isCurrentViewLockedPreset && (
              <Lock
                className="text-status-amber h-3.5 w-3.5 flex-shrink-0"
                aria-label={lockedPresetLabel}
              />
            )}
            {isCurrentViewCapabilityBlocked && (
              <AlertTriangle
                className="text-status-red h-3.5 w-3.5 flex-shrink-0"
                aria-label={capabilityBlockedLabel}
              />
            )}
          </>
        ) : (
          <>
            <ViewTypeIcon type="table-cells" className="text-text-3 h-3.5 w-3.5 flex-shrink-0" />
            <span className="text-text flex-1 truncate text-left">{defaultViewLabel}</span>
          </>
        )}
        <ChevronDown
          className={cn(
            'text-text-3 h-4 w-4 flex-shrink-0 transition-transform duration-200',
            isOpen && 'rotate-180',
          )}
          aria-hidden="true"
        />
      </button>

      {/* Dropdown Menu */}
      {isOpen && (
        <div
          className={cn(
            'border-border bg-panel shadow-pop absolute top-full left-0 z-50 mt-1 w-72 rounded-md border',
            'animate-in fade-in-0 zoom-in-95 duration-100',
          )}
          role="listbox"
          aria-label={selectViewLabel}
        >
          {/* View Groups */}
          <div className="max-h-64 overflow-y-auto py-1">
            <button
              type="button"
              onClick={handleSelectDefaultView}
              data-testid="view-option-default"
              className={cn(
                'text-text-2 flex w-full items-center gap-2 px-3 py-2 text-left text-sm',
                'hover:bg-hover focus:bg-hover focus:outline-none',
                'transition-colors duration-100',
                isDefaultBaselineActive && 'bg-accent-weak text-accent',
              )}
              role="option"
              aria-selected={isDefaultBaselineActive}
            >
              <ViewTypeIcon type="table-cells" className="text-text-3 h-3.5 w-3.5 flex-shrink-0" />
              <span className="flex-1 truncate">{defaultViewLabel}</span>
              {isDefaultBaselineActive && (
                <Check className="text-accent h-4 w-4 flex-shrink-0" aria-hidden="true" />
              )}
            </button>
            {groupedViews.length === 0 ? (
              <div>
                <div className="border-border border-b p-2">
                  <input
                    type="search"
                    value={searchTerm}
                    onChange={(event) => setSearchTerm(event.currentTarget.value)}
                    placeholder={searchPlaceholder}
                    aria-label={searchPlaceholder}
                    className="border-border bg-panel text-text placeholder:text-text-3 focus:border-accent focus:shadow-focus w-full rounded-md border px-2 py-1.5 text-sm outline-none"
                    data-testid="view-selector-search"
                  />
                </div>
                <div className="text-text-3 px-3 py-4 text-center text-sm">{emptyLabel}</div>
              </div>
            ) : (
              <>
                <div className="border-border border-b p-2">
                  <input
                    type="search"
                    value={searchTerm}
                    onChange={(event) => setSearchTerm(event.currentTarget.value)}
                    placeholder={searchPlaceholder}
                    aria-label={searchPlaceholder}
                    className="border-border bg-panel text-text placeholder:text-text-3 focus:border-accent focus:shadow-focus w-full rounded-md border px-2 py-1.5 text-sm outline-none"
                    data-testid="view-selector-search"
                  />
                </div>
                {groupedViews.map((group, groupIndex) => (
                  <div key={group.scope}>
                    {/* Group Separator */}
                    {groupIndex > 0 && <div className="bg-border mx-2 my-1 h-px" />}

                    <div className="text-text-3 flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium tracking-wide uppercase">
                      <group.Icon className="h-3.5 w-3.5" aria-hidden="true" />
                      {group.label}
                    </div>

                    {/* Group Items */}
                    {group.views.map((view) => (
                      <button
                        key={view.pid}
                        type="button"
                        onClick={() => handleSelectView(view.pid)}
                        data-testid={`view-option-${view.pid}`}
                        data-view-name={view.name}
                        data-view-type={view.viewType}
                        className={cn(
                          'text-text-2 flex w-full items-center gap-2 px-3 py-2 text-left text-sm',
                          'hover:bg-hover focus:bg-hover focus:outline-none',
                          'transition-colors duration-100',
                          displayCurrentView?.pid === view.pid && 'bg-accent-weak text-accent',
                        )}
                        role="option"
                        aria-selected={displayCurrentView?.pid === view.pid}
                      >
                        <ViewTypeIcon
                          type={getViewTypeIcon(view.viewType)}
                          className="text-text-3 h-3.5 w-3.5 flex-shrink-0"
                        />
                        <span className="flex-1 truncate">{view.name}</span>
                        {view.isDefault && (
                          <span
                            className="bg-accent-weak text-accent flex-shrink-0 rounded px-1.5 py-0.5 text-xs"
                            title={defaultLabel}
                          >
                            {defaultLabel}
                          </span>
                        )}
                        {isSavedViewLockedPreset(view) && (
                          <span
                            className="bg-status-amber-bg text-status-amber inline-flex flex-shrink-0 items-center gap-1 rounded px-1.5 py-0.5 text-xs"
                            title={lockedPresetLabel}
                          >
                            <Lock className="h-3 w-3" aria-hidden="true" />
                            {lockedPresetLabel}
                          </span>
                        )}
                        {isSavedViewCapabilityBlocked(view) && (
                          <span
                            className="bg-status-red-bg text-status-red inline-flex flex-shrink-0 items-center gap-1 rounded px-1.5 py-0.5 text-xs"
                            title={capabilityBlockedLabel}
                          >
                            <AlertTriangle className="h-3 w-3" aria-hidden="true" />
                            {capabilityBlockedLabel}
                          </span>
                        )}
                        {displayCurrentView?.pid === view.pid && (
                          <Check className="text-accent h-4 w-4 flex-shrink-0" aria-hidden="true" />
                        )}
                      </button>
                    ))}
                  </div>
                ))}
              </>
            )}
          </div>

          {/* Action Buttons */}
          {hasActions && (
            <>
              <div className="bg-border mx-2 h-px" />
              <div className="flex gap-2 p-2">
                {onCreateView && (
                  <button
                    type="button"
                    onClick={handleCreateView}
                    data-testid="view-selector-create"
                    className={cn(
                      'flex flex-1 items-center justify-center gap-1.5 px-3 py-2 text-sm font-medium',
                      'text-accent hover:bg-accent-weak rounded-md',
                      'focus:shadow-focus focus:outline-none',
                      'transition-colors duration-100',
                    )}
                  >
                    <Plus className="h-4 w-4" aria-hidden="true" />
                    {newViewLabel}
                  </button>
                )}
                {onManageViews && (
                  <button
                    type="button"
                    onClick={handleManageViews}
                    data-testid="view-selector-manage"
                    className={cn(
                      'flex flex-1 items-center justify-center gap-1.5 px-3 py-2 text-sm font-medium',
                      'text-text-2 hover:bg-hover rounded-md',
                      'focus:shadow-focus focus:outline-none',
                      'transition-colors duration-100',
                    )}
                  >
                    <Settings className="h-4 w-4" aria-hidden="true" />
                    {manageLabel}
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
};

export default ViewSelector;
