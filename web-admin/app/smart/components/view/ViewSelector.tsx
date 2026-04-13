/**
 * ViewSelector Component
 *
 * A dropdown component for selecting saved views.
 * Displays views grouped by scope (GLOBAL, TEAM, PERSONAL) with management options.
 */

import React, { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import {
  type SavedView,
  type ViewScope,
  type ViewType,
} from '~/smart/types/savedView';
import type { ViewRecommendation } from '~/smart/hooks/useViewRecommendations';
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
  label: string;
  icon: string;
}

/**
 * Ordered scope configurations for grouping views
 */
const SCOPE_CONFIGS: ScopeConfig[] = [
  { scope: 'global', label: 'Global Views', icon: '🌐' },
  { scope: 'team', label: 'Team Views', icon: '👥' },
  { scope: 'personal', label: 'Personal Views', icon: '👤' },
];

/**
 * Get scope icon by scope type
 */
const getScopeIcon = (scope: ViewScope): string => {
  const config = SCOPE_CONFIGS.find((c) => c.scope === scope);
  return config?.icon ?? '📋';
};

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
  onManageViews,
  onCreateView,
  activeViewType,
  // onViewTypeChange kept in interface for new-view creation flow
  loading = false,
  className,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

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
   * Click handler: directly open manage panel (no dropdown)
   */
  const handleClick = useCallback(() => {
    if (!loading && onManageViews) {
      onManageViews();
    }
  }, [loading, onManageViews]);

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
   * Handle scope header click (PERSONAL opens management directly)
   */
  const handleScopeHeaderClick = useCallback(
    (scope: ViewScope) => {
      if (scope === 'personal' && onManageViews) {
        handleManageViews();
      }
    },
    [handleManageViews, onManageViews],
  );

  /**
   * Group views by scope
   */
  const filteredViews = useMemo(() => {
    if (!activeViewType) return views;
    return views.filter((v) => (v.viewType || 'table') === activeViewType);
  }, [views, activeViewType]);

  const groupedViews = SCOPE_CONFIGS.map((config) => ({
    ...config,
    views: filteredViews.filter((v) => v.scope === config.scope),
  })).filter((group) => group.views.length > 0);

  const hasActions = onCreateView || onManageViews;

  return (
    <div ref={containerRef} className={cn('relative inline-flex items-center gap-1', className)}>
      {/* Trigger Button */}
      <button
        type="button"
        onClick={handleClick}
        disabled={loading}
        className={cn(
          'flex items-center gap-2 rounded-md border border-gray-300 bg-white px-3 py-2 text-sm',
          'hover:bg-gray-50 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none',
          'disabled:cursor-not-allowed disabled:opacity-50',
          'transition-colors duration-150',
          'max-w-[240px] min-w-[140px]',
        )}
        aria-haspopup="listbox"
        aria-expanded={isOpen}
      >
        {loading ? (
          <>
            <span
              className="h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500"
              aria-hidden="true"
            />
            <span className="text-gray-400">Loading...</span>
          </>
        ) : currentView ? (
          <>
            <span className="flex-shrink-0" aria-hidden="true">
              {getScopeIcon(currentView.scope)}
            </span>
            <span className="flex-1 truncate text-left text-gray-900">{currentView.name}</span>
            {currentView.isDefault && (
              <span
                className="flex-shrink-0 text-xs font-medium text-blue-600"
                title="Default view"
              >
                *
              </span>
            )}
          </>
        ) : (
          <span className="text-gray-400">Select view</span>
        )}
        <svg
          className={cn(
            'h-4 w-4 flex-shrink-0 text-gray-400 transition-transform duration-200',
            isOpen && 'rotate-180',
          )}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {/* Dropdown Menu */}
      {isOpen && (
        <div
          className={cn(
            'absolute z-50 mt-1 w-64 rounded-md border border-gray-200 bg-white shadow-lg',
            'animate-in fade-in-0 zoom-in-95 duration-100',
          )}
          role="listbox"
          aria-label="Select view"
        >
          {/* View Groups */}
          <div className="max-h-64 overflow-y-auto py-1">
            {groupedViews.length === 0 ? (
              <div className="px-3 py-4 text-center text-sm text-gray-500">
                No saved views available
              </div>
            ) : (
              groupedViews.map((group, groupIndex) => (
                <div key={group.scope}>
                  {/* Group Separator */}
                  {groupIndex > 0 && <div className="mx-2 my-1 h-px bg-gray-200" />}

                  {/* Group Header */}
                  {group.scope === 'personal' && onManageViews ? (
                    <button
                      type="button"
                      onClick={() => handleScopeHeaderClick(group.scope)}
                      data-testid="view-group-personal-manage"
                      className={cn(
                        'w-full px-3 py-1.5 text-left text-xs font-medium tracking-wide uppercase',
                        'text-blue-600 hover:bg-blue-50',
                        'focus:ring-2 focus:ring-blue-500 focus:outline-none focus:ring-inset',
                        'transition-colors duration-100',
                      )}
                    >
                      {group.icon} {group.label}
                    </button>
                  ) : (
                    <div className="px-3 py-1.5 text-xs font-medium tracking-wide text-gray-500 uppercase">
                      {group.icon} {group.label}
                    </div>
                  )}

                  {/* Group Items */}
                  {group.views.map((view) => (
                    <button
                      key={view.pid}
                      type="button"
                      onClick={() => handleSelectView(view.pid)}
                      className={cn(
                        'flex w-full items-center gap-2 px-3 py-2 text-left text-sm',
                        'hover:bg-gray-100 focus:bg-gray-100 focus:outline-none',
                        'transition-colors duration-100',
                        currentView?.pid === view.pid && 'bg-blue-50 text-blue-700',
                      )}
                      role="option"
                      aria-selected={currentView?.pid === view.pid}
                    >
                      <ViewTypeIcon
                        type={getViewTypeIcon(view.viewType)}
                        className="h-3.5 w-3.5 flex-shrink-0 text-gray-400"
                      />
                      <span className="flex-1 truncate">{view.name}</span>
                      {view.isDefault && (
                        <span
                          className="flex-shrink-0 rounded bg-blue-100 px-1.5 py-0.5 text-xs text-blue-700"
                          title="Default view"
                        >
                          Default
                        </span>
                      )}
                      {currentView?.pid === view.pid && (
                        <svg
                          className="h-4 w-4 flex-shrink-0 text-blue-600"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                          aria-hidden="true"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M5 13l4 4L19 7"
                          />
                        </svg>
                      )}
                    </button>
                  ))}
                </div>
              ))
            )}
          </div>

          {/* Action Buttons */}
          {hasActions && (
            <>
              <div className="mx-2 h-px bg-gray-200" />
              <div className="flex gap-2 p-2">
                {onCreateView && (
                  <button
                    type="button"
                    onClick={handleCreateView}
                    className={cn(
                      'flex flex-1 items-center justify-center gap-1.5 px-3 py-2 text-sm font-medium',
                      'rounded-md text-blue-600 hover:bg-blue-50',
                      'focus:ring-2 focus:ring-blue-500 focus:outline-none focus:ring-inset',
                      'transition-colors duration-100',
                    )}
                  >
                    <svg
                      className="h-4 w-4"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                      aria-hidden="true"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M12 4v16m8-8H4"
                      />
                    </svg>
                    New View
                  </button>
                )}
                {onManageViews && (
                  <button
                    type="button"
                    onClick={handleManageViews}
                    className={cn(
                      'flex flex-1 items-center justify-center gap-1.5 px-3 py-2 text-sm font-medium',
                      'rounded-md text-gray-700 hover:bg-gray-100',
                      'focus:ring-2 focus:ring-gray-500 focus:outline-none focus:ring-inset',
                      'transition-colors duration-100',
                    )}
                  >
                    <svg
                      className="h-4 w-4"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                      aria-hidden="true"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                      />
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                      />
                    </svg>
                    Manage
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
