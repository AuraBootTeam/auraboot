/**
 * ViewManagePanel Component
 *
 * A slide-out panel for managing saved views.
 * Allows users to create, delete, duplicate, and set default views.
 */

import React, { useState, useCallback, useEffect, useMemo } from 'react';
import {
  VIEW_TYPE_CONFIGS,
  VIEW_TYPE_FIELD_REQUIREMENTS,
  type SavedView,
  type SavedViewCreateRequest,
  type ViewScope,
  type ViewType,
  type ViewConfig,
  type ViewFieldRequirement,
} from '~/smart/types/savedView';
import { KanbanConfigPanel, type FieldOption } from './KanbanConfigPanel';
import type { ModelFieldBinding } from '~/types/model';
import { fetchCurrentUserTeams, type TeamOption } from '~/services/teamService';
import { CalendarConfigPanel } from './CalendarConfigPanel';
import { GalleryConfigPanel } from './GalleryConfigPanel';
import { GanttConfigPanel } from './GanttConfigPanel';
import { TreeConfigPanel } from './TreeConfigPanel';
import { ViewFilterPanel } from './ViewFilterPanel';
import { ConditionalFormatPanel } from './ConditionalFormatPanel';
import { SparklesIcon } from '@heroicons/react/24/solid';
import { cn } from '~/utils/cn';
import { confirmDialog } from '~/utils/confirmDialog';
import { modelService } from '~/services/modelService';
import { createField } from '~/services/fieldService';
import { useViewRecommendations } from '~/smart/hooks/useViewRecommendations';

/**
 * Props for ViewManagePanel component
 */
export interface ViewManagePanelProps {
  /** Whether the panel is open */
  open: boolean;
  /** Callback to close the panel */
  onClose: () => void;
  /** List of available views */
  views: SavedView[];
  /** Currently selected view */
  currentView: SavedView | null;
  /** Callback to create a new view */
  onCreateView: (request: SavedViewCreateRequest) => Promise<SavedView>;
  /** Callback to delete a view */
  onDeleteView: (pid: string) => Promise<void>;
  /** Callback to duplicate a view */
  onDuplicateView: (pid: string, newName: string) => Promise<void>;
  /** Callback to set a view as default */
  onSetDefaultView: (pid: string) => Promise<void>;
  /** Callback when a view is selected */
  onSelectView: (pid: string) => void;
  /** Associated model code */
  modelCode: string;
  /** Associated page key (optional) */
  pageKey?: string;
  /** Open panel directly in create mode */
  startInCreateMode?: boolean;
  /** Current active view type for pre-selecting on create */
  activeViewType?: ViewType;
  /** Callback after create success for parent sync (e.g. active view type) */
  onCreateViewSuccess?: (view: SavedView) => void;
  /** Available model fields for kanban configuration */
  fields?: FieldOption[];
  /** Model PID for creating fields */
  modelPid?: string;
  /** Callback when fields are auto-created (to refresh parent) */
  onFieldsCreated?: () => void;
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
 * ViewManagePanel - A slide-out panel for managing saved views
 *
 * @example
 * <ViewManagePanel
 *   open={isPanelOpen}
 *   onClose={() => setIsPanelOpen(false)}
 *   views={savedViews}
 *   currentView={currentView}
 *   onCreateView={handleCreateView}
 *   onDeleteView={handleDeleteView}
 *   onDuplicateView={handleDuplicateView}
 *   onSetDefaultView={handleSetDefaultView}
 *   onSelectView={handleSelectView}
 *   modelCode="order"
 * />
 */
export const ViewManagePanel: React.FC<ViewManagePanelProps> = ({
  open,
  onClose,
  views,
  currentView,
  onCreateView,
  onDeleteView,
  onDuplicateView,
  onSetDefaultView,
  onSelectView,
  modelCode,
  pageKey,
  startInCreateMode = false,
  activeViewType,
  onCreateViewSuccess,
  fields = [],
  modelPid,
  onFieldsCreated,
}) => {
  // Form state for creating new view
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newViewName, setNewViewName] = useState('');
  const [newViewScope, setNewViewScope] = useState<ViewScope>('personal');
  const [newViewTeamId, setNewViewTeamId] = useState('');
  const [newViewType, setNewViewType] = useState<ViewType>(activeViewType || 'table');
  const [newViewConfig, setNewViewConfig] = useState<ViewConfig>({});
  const [teamOptions, setTeamOptions] = useState<TeamOption[]>([]);
  const [teamLoading, setTeamLoading] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const [modelFields, setModelFields] = useState<ModelFieldBinding[]>([]);
  const [autoCreateLoading, setAutoCreateLoading] = useState(false);

  const enabledViewTypes = VIEW_TYPE_CONFIGS.filter((c) => c.enabled);

  // Merge fields from props and loaded model fields for config panels
  const mergedFields: FieldOption[] = useMemo(() => {
    if (fields.length > 0) return fields;
    return modelFields.map((f) => ({
      code: f.fieldCode || f.code || '',
      name: f.fieldName || f.displayName || f.fieldCode || f.code || '',
      dataType: f.dataType || '',
    }));
  }, [fields, modelFields]);

  // AI view recommendations based on field types
  const recommendationFields = useMemo(() => {
    return mergedFields.map((f) => {
      const binding = modelFields.find((mf) => (mf.fieldCode || mf.code) === f.code);
      return {
        code: f.code,
        dataType: f.dataType,
        dictCode: binding?.dictCode,
        referenceModelCode: (binding as unknown as Record<string, unknown>)?.referenceModelCode as string | undefined,
      };
    });
  }, [mergedFields, modelFields]);

  const recommendations = useViewRecommendations(modelCode, recommendationFields);

  // Detect missing required fields for the selected view type
  const missingFields: ViewFieldRequirement[] = useMemo(() => {
    const reqs = VIEW_TYPE_FIELD_REQUIREMENTS[newViewType];
    if (!reqs) return [];
    const allFields = mergedFields;
    return reqs
      .filter((req) => req.required)
      .filter(
        (req) => !allFields.some((f) => req.acceptedTypes.includes(f.dataType?.toUpperCase())),
      );
  }, [newViewType, mergedFields]);

  // Loading states for operations
  const [loadingState, setLoadingState] = useState<{
    type: 'create' | 'delete' | 'duplicate' | 'setDefault' | null;
    pid?: string;
  }>({ type: null });

  /**
   * Reset form when panel closes
   */
  useEffect(() => {
    if (!open) {
      setShowCreateForm(false);
      setNewViewName('');
      setNewViewScope('personal');
      setNewViewTeamId('');
      setNewViewType(activeViewType || 'table');
      setNewViewConfig({});
      setCreateError(null);
      setLoadingState({ type: null });
    }
  }, [open, activeViewType]);

  useEffect(() => {
    if (!open) return;
    if (startInCreateMode) {
      setShowCreateForm(true);
      setNewViewType(activeViewType || 'table');
    }
  }, [open, startInCreateMode, activeViewType]);

  useEffect(() => {
    if (!open) {
      return;
    }
    let mounted = true;
    setTeamLoading(true);
    fetchCurrentUserTeams()
      .then((teams) => {
        if (!mounted) {
          return;
        }
        setTeamOptions(teams);
      })
      .catch(() => {
        if (!mounted) {
          return;
        }
        setTeamOptions([]);
      })
      .finally(() => {
        if (mounted) {
          setTeamLoading(false);
        }
      });
    return () => {
      mounted = false;
    };
  }, [open]);

  /**
   * Close panel on escape key
   */
  useEffect(() => {
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && open) {
        onClose();
      }
    };

    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('keydown', handleEscape);
    };
  }, [open, onClose]);

  /**
   * Load model fields when panel opens (if modelPid is available)
   */
  useEffect(() => {
    if (!open || !modelPid) return;
    let mounted = true;
    modelService
      .getModelFields(modelPid)
      .then((bindings) => {
        if (mounted) setModelFields(bindings);
      })
      .catch(() => {
        if (mounted) setModelFields([]);
      });
    return () => {
      mounted = false;
    };
  }, [open, modelPid]);

  /**
   * Auto-create missing fields for the selected view type
   */
  const handleAutoCreateFields = useCallback(async () => {
    if (!modelPid || missingFields.length === 0) return;

    setAutoCreateLoading(true);
    setCreateError(null);
    try {
      const createdCodes: Record<string, string> = {};

      for (const req of missingFields) {
        if (!req.autoCreateConfig) continue;
        await createField({
          code: req.autoCreateConfig.code,
          dataType: req.autoCreateConfig.dataType,
          modelPid,
          autoPublish: true,
        });
        createdCodes[req.key] = req.autoCreateConfig.code;
      }

      // Re-publish model to add new columns
      await modelService.publish(modelPid);

      // Reload model fields
      const updated = await modelService.getModelFields(modelPid);
      setModelFields(updated);

      // Auto-populate viewConfig with newly created fields
      setNewViewConfig((prev) => ({ ...prev, ...createdCodes }));

      // Notify parent to refresh
      onFieldsCreated?.();
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to create fields';
      setCreateError(message);
    } finally {
      setAutoCreateLoading(false);
    }
  }, [modelPid, missingFields, onFieldsCreated]);

  /**
   * Auto-suggest matching fields when view type changes
   */
  useEffect(() => {
    if (newViewType === 'table') return;
    const reqs = VIEW_TYPE_FIELD_REQUIREMENTS[newViewType];
    if (!reqs || mergedFields.length === 0) return;

    const suggestions: Record<string, string> = {};
    for (const req of reqs) {
      if (newViewConfig[req.key as keyof ViewConfig]) continue; // already set
      const match = mergedFields.find((f) => req.acceptedTypes.includes(f.dataType?.toUpperCase()));
      if (match) {
        suggestions[req.key] = match.code;
      }
    }
    if (Object.keys(suggestions).length > 0) {
      setNewViewConfig((prev) => ({ ...prev, ...suggestions }));
    }
  }, [newViewType, mergedFields]); // intentionally omit newViewConfig to avoid infinite loop

  /**
   * Handle create view submission
   */
  const handleCreateSubmit = useCallback(async () => {
    if (!newViewName.trim()) return;
    if (newViewScope === 'team' && !newViewTeamId.trim()) {
      setCreateError('Team scope requires a team ID');
      return;
    }

    setLoadingState({ type: 'create' });
    setCreateError(null);
    try {
      const createdView = await onCreateView({
        name: newViewName.trim(),
        modelCode,
        pageKey,
        scope: newViewScope,
        teamId: newViewScope === 'team' ? newViewTeamId.trim() : undefined,
        viewType: newViewType,
        viewConfig: Object.keys(newViewConfig).length > 0 ? newViewConfig : undefined,
      });
      onSelectView(createdView.pid);
      onCreateViewSuccess?.(createdView);
      setShowCreateForm(false);
      setNewViewName('');
      setNewViewScope('personal');
      setNewViewTeamId('');
      setNewViewType(activeViewType || 'table');
      setNewViewConfig({});
      setCreateError(null);
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to create view';
      setCreateError(message);
    } finally {
      setLoadingState({ type: null });
    }
  }, [
    newViewName,
    newViewScope,
    newViewTeamId,
    newViewType,
    newViewConfig,
    activeViewType,
    modelCode,
    pageKey,
    onCreateView,
    onSelectView,
    onCreateViewSuccess,
  ]);

  /**
   * Handle delete view
   */
  const handleDelete = useCallback(
    async (view: SavedView) => {
      const confirmed = await confirmDialog({
        content: `Are you sure you want to delete the view "${view.name}"? This action cannot be undone.`,
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
    [onDeleteView],
  );

  /**
   * Handle duplicate view
   */
  const handleDuplicate = useCallback(
    async (view: SavedView) => {
      const newName = window.prompt('Enter a name for the duplicated view:', `${view.name} (Copy)`);
      if (!newName?.trim()) return;

      setLoadingState({ type: 'duplicate', pid: view.pid });
      try {
        await onDuplicateView(view.pid, newName.trim());
      } finally {
        setLoadingState({ type: null });
      }
    },
    [onDuplicateView],
  );

  /**
   * Handle set default view
   */
  const handleSetDefault = useCallback(
    async (view: SavedView) => {
      if (view.isDefault) return;

      setLoadingState({ type: 'setDefault', pid: view.pid });
      try {
        await onSetDefaultView(view.pid);
      } finally {
        setLoadingState({ type: null });
      }
    },
    [onSetDefaultView],
  );

  /**
   * Handle view selection
   */
  const handleSelectView = useCallback(
    (pid: string) => {
      onSelectView(pid);
    },
    [onSelectView],
  );

  /**
   * Group views by scope
   */
  const groupedViews = SCOPE_CONFIGS.map((config) => ({
    ...config,
    views: views.filter((v) => v.scope === config.scope),
  })).filter((group) => group.views.length > 0);

  /**
   * Check if a view operation is loading
   */
  const isViewLoading = (pid: string) => {
    return loadingState.pid === pid && loadingState.type !== null;
  };

  if (!open) return null;

  return (
    <>
      {/* Backdrop overlay */}
      <div
        className="fixed inset-0 z-40 bg-black/50 transition-opacity duration-200"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Panel */}
      <div
        className={cn(
          'fixed top-0 right-0 z-50 h-full w-[min(95vw,52rem)] bg-white shadow-xl',
          'flex flex-col',
          'animate-in slide-in-from-right duration-200',
        )}
        role="dialog"
        aria-modal="true"
        aria-labelledby="view-manage-panel-title"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-4 py-3">
          <h2 id="view-manage-panel-title" className="text-lg font-semibold text-gray-900">
            View Management
          </h2>
          <button
            type="button"
            onClick={onClose}
            className={cn(
              'rounded-md p-2 text-gray-400 hover:bg-gray-100 hover:text-gray-500',
              'focus:ring-2 focus:ring-blue-500 focus:outline-none',
              'transition-colors duration-150',
            )}
            aria-label="Close panel"
          >
            <svg
              className="h-5 w-5"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
              aria-hidden="true"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto">
          {/* Create View Section */}
          <div className="border-b border-gray-200 p-4">
            {showCreateForm ? (
              <div className="space-y-3">
                <div>
                  <label
                    htmlFor="view-name"
                    className="mb-1 block text-sm font-medium text-gray-700"
                  >
                    View Name
                  </label>
                  <input
                    id="view-name"
                    type="text"
                    value={newViewName}
                    onChange={(e) => setNewViewName(e.target.value)}
                    placeholder="Enter view name"
                    className={cn(
                      'w-full rounded-md border border-gray-300 px-3 py-2 text-sm',
                      'focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none',
                      'placeholder:text-gray-400',
                    )}
                    autoFocus
                  />
                </div>

                <div>
                  <label
                    htmlFor="view-scope"
                    className="mb-1 block text-sm font-medium text-gray-700"
                  >
                    Scope
                  </label>
                  <select
                    id="view-scope"
                    value={newViewScope}
                    onChange={(e) => {
                      const scope = e.target.value as ViewScope;
                      setNewViewScope(scope);
                      if (scope !== 'team') {
                        setNewViewTeamId('');
                      }
                    }}
                    className={cn(
                      'w-full rounded-md border border-gray-300 px-3 py-2 text-sm',
                      'focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none',
                      'bg-white',
                    )}
                  >
                    <option value="personal">Personal (Only you)</option>
                    <option value="team">Team (Visible to your team)</option>
                    <option value="global">Global (Everyone)</option>
                  </select>
                </div>

                {newViewScope === 'team' && (
                  <div>
                    <label
                      htmlFor="view-team-id"
                      className="mb-1 block text-sm font-medium text-gray-700"
                    >
                      Team
                    </label>
                    {teamLoading ? (
                      <div className="flex items-center gap-2 px-3 py-2 text-sm text-gray-500">
                        <span className="h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
                        Loading teams...
                      </div>
                    ) : teamOptions.length > 0 ? (
                      <select
                        id="view-team-id"
                        value={newViewTeamId}
                        onChange={(e) => setNewViewTeamId(e.target.value)}
                        className={cn(
                          'w-full rounded-md border border-gray-300 px-3 py-2 text-sm',
                          'focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none',
                          'bg-white',
                        )}
                      >
                        <option value="">Select team</option>
                        {teamOptions.map((team) => (
                          <option key={team.id} value={team.id}>
                            {team.name}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <div className="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm text-gray-500">
                        You are not a member of any team. Join a team first to create team views.
                      </div>
                    )}
                  </div>
                )}

                {createError && (
                  <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
                    {createError}
                  </div>
                )}

                {/* View Type Selector */}
                {enabledViewTypes.length > 1 && (
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      View Type
                    </label>
                    <div className="grid grid-cols-3 gap-2">
                      {enabledViewTypes.map((vtConfig) => {
                        const rec = recommendations.find((r) => r.viewType === vtConfig.type);
                        return (
                          <button
                            key={vtConfig.type}
                            type="button"
                            onClick={() => {
                              setNewViewType(vtConfig.type);
                              setNewViewConfig({});
                            }}
                            className={cn(
                              'relative rounded-md border px-3 py-2 text-sm transition-colors duration-100',
                              newViewType === vtConfig.type
                                ? 'border-blue-500 bg-blue-50 font-medium text-blue-700'
                                : 'border-gray-300 bg-white text-gray-600 hover:bg-gray-50',
                            )}
                          >
                            {vtConfig.label}
                            {rec && (
                              <span className="absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full bg-blue-500">
                                <SparklesIcon className="h-2.5 w-2.5 text-white" />
                              </span>
                            )}
                          </button>
                        );
                      })}
                    </div>
                    {recommendations.length > 0 && (
                      <div className="mt-2 text-xs text-gray-500">
                        <SparklesIcon className="mr-1 inline h-3 w-3 text-blue-500" />
                        Recommended views are based on your model's field types.
                      </div>
                    )}
                  </div>
                )}

                {/* Missing Fields Warning Banner */}
                {missingFields.length > 0 && modelPid && (
                  <div className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm">
                    <div className="flex items-start gap-2">
                      <svg
                        className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-500"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"
                        />
                      </svg>
                      <div className="flex-1">
                        <p className="font-medium text-amber-800">
                          Missing required fields for {newViewType} view:
                        </p>
                        <ul className="mt-1 list-inside list-disc text-amber-700">
                          {missingFields.map((req) => (
                            <li key={req.key}>
                              {req.label} ({req.acceptedTypes.join(' / ')})
                            </li>
                          ))}
                        </ul>
                        {missingFields.some((r) => r.autoCreateConfig) && (
                          <button
                            type="button"
                            onClick={handleAutoCreateFields}
                            disabled={autoCreateLoading}
                            className={cn(
                              'mt-2 rounded-md bg-amber-100 px-3 py-1.5 text-xs font-medium text-amber-800',
                              'hover:bg-amber-200 focus:ring-2 focus:ring-amber-500 focus:outline-none',
                              'disabled:cursor-not-allowed disabled:opacity-50',
                              'transition-colors duration-150',
                            )}
                          >
                            {autoCreateLoading ? (
                              <span className="flex items-center gap-1">
                                <span className="h-3 w-3 animate-spin rounded-full border-2 border-amber-400 border-t-amber-700" />
                                Creating fields...
                              </span>
                            ) : (
                              'Auto-add missing fields'
                            )}
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                )}

                {/* Kanban Config Panel */}
                {newViewType === 'kanban' && (
                  <div className="rounded-md border border-gray-200 bg-gray-50 p-3">
                    <h4 className="mb-3 text-sm font-medium text-gray-700">Kanban Configuration</h4>
                    <KanbanConfigPanel
                      viewConfig={newViewConfig}
                      onChange={setNewViewConfig}
                      fields={mergedFields}
                    />
                  </div>
                )}

                {/* Calendar Config Panel */}
                {newViewType === 'calendar' && (
                  <div className="rounded-md border border-gray-200 bg-gray-50 p-3">
                    <h4 className="mb-3 text-sm font-medium text-gray-700">
                      Calendar Configuration
                    </h4>
                    <CalendarConfigPanel
                      viewConfig={newViewConfig}
                      onChange={setNewViewConfig}
                      fields={mergedFields}
                    />
                  </div>
                )}

                {/* Gallery Config Panel */}
                {newViewType === 'gallery' && (
                  <div className="rounded-md border border-gray-200 bg-gray-50 p-3">
                    <h4 className="mb-3 text-sm font-medium text-gray-700">
                      Gallery Configuration
                    </h4>
                    <GalleryConfigPanel
                      viewConfig={newViewConfig}
                      onChange={setNewViewConfig}
                      fields={mergedFields}
                    />
                  </div>
                )}

                {/* Gantt Config Panel */}
                {newViewType === 'gantt' && (
                  <div className="rounded-md border border-gray-200 bg-gray-50 p-3">
                    <h4 className="mb-3 text-sm font-medium text-gray-700">Gantt Configuration</h4>
                    <GanttConfigPanel
                      viewConfig={newViewConfig}
                      onChange={setNewViewConfig}
                      fields={mergedFields}
                    />
                  </div>
                )}

                {/* Tree Config Panel */}
                {newViewType === 'tree' && (
                  <div className="rounded-md border border-gray-200 bg-gray-50 p-3">
                    <h4 className="mb-3 text-sm font-medium text-gray-700">Tree Configuration</h4>
                    <TreeConfigPanel
                      viewConfig={newViewConfig}
                      onChange={setNewViewConfig}
                      fields={mergedFields}
                    />
                  </div>
                )}

                {/* Filter Configuration (available for all view types) */}
                {mergedFields.length > 0 && (
                  <div className="rounded-md border border-gray-200 bg-gray-50 p-3">
                    <h4 className="mb-3 text-sm font-medium text-gray-700">Filters (Optional)</h4>
                    <ViewFilterPanel
                      viewConfig={newViewConfig}
                      onChange={setNewViewConfig}
                      fields={mergedFields}
                    />
                  </div>
                )}

                {/* Conditional Formatting (TABLE view only) */}
                {newViewType === 'table' && mergedFields.length > 0 && (
                  <div className="rounded-md border border-gray-200 bg-gray-50 p-3">
                    <h4 className="mb-3 text-sm font-medium text-gray-700">Conditional Formatting (Optional)</h4>
                    <ConditionalFormatPanel
                      viewConfig={newViewConfig}
                      onChange={setNewViewConfig}
                      fields={mergedFields}
                    />
                  </div>
                )}

                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={handleCreateSubmit}
                    disabled={!newViewName.trim() || loadingState.type === 'create'}
                    className={cn(
                      'flex-1 rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white',
                      'hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none',
                      'disabled:cursor-not-allowed disabled:opacity-50',
                      'transition-colors duration-150',
                    )}
                  >
                    {loadingState.type === 'create' ? (
                      <span className="flex items-center justify-center gap-2">
                        <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
                        Creating...
                      </span>
                    ) : (
                      'Create'
                    )}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setShowCreateForm(false);
                      setNewViewName('');
                      setNewViewScope('personal');
                      setNewViewTeamId('');
                      setNewViewType(activeViewType || 'table');
                      setNewViewConfig({});
                      setCreateError(null);
                    }}
                    disabled={loadingState.type === 'create'}
                    className={cn(
                      'rounded-md border border-gray-300 bg-white px-3 py-2 text-sm font-medium text-gray-700',
                      'hover:bg-gray-50 focus:ring-2 focus:ring-gray-500 focus:ring-offset-2 focus:outline-none',
                      'disabled:cursor-not-allowed disabled:opacity-50',
                      'transition-colors duration-150',
                    )}
                  >
                    Cancel
                  </button>
                </div>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setShowCreateForm(true)}
                className={cn(
                  'flex w-full items-center justify-center gap-2 px-4 py-2',
                  'rounded-md bg-blue-50 text-sm font-medium text-blue-600',
                  'hover:bg-blue-100 focus:ring-2 focus:ring-blue-500 focus:outline-none',
                  'transition-colors duration-150',
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
          </div>

          {/* View List */}
          <div className="py-2">
            {groupedViews.length === 0 ? (
              <div className="px-4 py-8 text-center text-sm text-gray-500">
                No saved views available. Create your first view above.
              </div>
            ) : (
              groupedViews.map((group, groupIndex) => (
                <div key={group.scope}>
                  {/* Group Separator */}
                  {groupIndex > 0 && <div className="mx-4 my-2 h-px bg-gray-200" />}

                  {/* Group Header */}
                  <div className="px-4 py-2 text-xs font-medium tracking-wide text-gray-500 uppercase">
                    {group.icon} {group.label}
                  </div>

                  {/* Group Items */}
                  {group.views.map((view) => (
                    <div
                      key={view.pid}
                      className={cn(
                        'mx-2 rounded-md px-2 py-2',
                        'hover:bg-gray-50',
                        'transition-colors duration-100',
                        currentView?.pid === view.pid && 'bg-blue-50',
                      )}
                    >
                      <div className="flex items-start gap-2">
                        {/* View Info */}
                        <button
                          type="button"
                          onClick={() => handleSelectView(view.pid)}
                          className="min-w-0 flex-1 text-left"
                        >
                          <div className="flex items-center gap-2">
                            <span
                              className={cn(
                                'truncate text-sm font-medium',
                                currentView?.pid === view.pid ? 'text-blue-700' : 'text-gray-900',
                              )}
                            >
                              {view.name}
                            </span>
                            {view.viewType && view.viewType !== 'table' && (
                              <span className="flex-shrink-0 rounded bg-purple-100 px-1.5 py-0.5 text-xs font-medium text-purple-700">
                                {view.viewType}
                              </span>
                            )}
                            {view.isDefault && (
                              <span className="flex-shrink-0 rounded bg-green-100 px-1.5 py-0.5 text-xs font-medium text-green-700">
                                Default
                              </span>
                            )}
                          </div>
                          {view.description && (
                            <p className="mt-0.5 truncate text-xs text-gray-500">
                              {view.description}
                            </p>
                          )}
                          {view.teamName && view.scope === 'team' && (
                            <p className="mt-0.5 text-xs text-gray-400">Team: {view.teamName}</p>
                          )}
                          {view.ownerName && (
                            <p className="mt-0.5 text-xs text-gray-400">Owner: {view.ownerName}</p>
                          )}
                        </button>

                        {/* Action Buttons */}
                        <div className="flex flex-shrink-0 items-center gap-1">
                          {/* Set Default Button */}
                          <button
                            type="button"
                            onClick={() => handleSetDefault(view)}
                            disabled={view.isDefault || isViewLoading(view.pid)}
                            className={cn(
                              'rounded-md p-1.5',
                              'focus:ring-2 focus:ring-blue-500 focus:outline-none',
                              'transition-colors duration-150',
                              view.isDefault
                                ? 'cursor-default text-yellow-500'
                                : 'text-gray-400 hover:bg-gray-100 hover:text-yellow-500',
                            )}
                            title={view.isDefault ? 'Default view' : 'Set as default'}
                          >
                            {loadingState.type === 'setDefault' && loadingState.pid === view.pid ? (
                              <span className="block h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-yellow-500" />
                            ) : (
                              <svg
                                className="h-4 w-4"
                                fill={view.isDefault ? 'currentColor' : 'none'}
                                stroke="currentColor"
                                viewBox="0 0 24 24"
                                aria-hidden="true"
                              >
                                <path
                                  strokeLinecap="round"
                                  strokeLinejoin="round"
                                  strokeWidth={2}
                                  d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.197-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z"
                                />
                              </svg>
                            )}
                          </button>

                          {/* Duplicate Button */}
                          <button
                            type="button"
                            onClick={() => handleDuplicate(view)}
                            disabled={isViewLoading(view.pid)}
                            className={cn(
                              'rounded-md p-1.5 text-gray-400',
                              'hover:bg-gray-100 hover:text-blue-500',
                              'focus:ring-2 focus:ring-blue-500 focus:outline-none',
                              'disabled:cursor-not-allowed disabled:opacity-50',
                              'transition-colors duration-150',
                            )}
                            title="Duplicate view"
                          >
                            {loadingState.type === 'duplicate' && loadingState.pid === view.pid ? (
                              <span className="block h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
                            ) : (
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
                                  d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
                                />
                              </svg>
                            )}
                          </button>

                          {/* Share Button */}
                          <button
                            type="button"
                            onClick={async () => {
                              try {
                                const resp = await fetch(`/api/views/${view.pid}/share/status`);
                                const data = await resp.json();
                                const isShared = data?.data?.shared;
                                if (isShared) {
                                  const shareUrl = data?.data?.shareUrl || '';
                                  const action = window.confirm(`This view is shared.\n\nURL: ${shareUrl}\n\nClick OK to copy link, Cancel to revoke sharing.`);
                                  if (action) {
                                    navigator.clipboard.writeText(shareUrl);
                                  } else {
                                    await fetch(`/api/views/${view.pid}/share`, { method: 'delete' });
                                  }
                                } else {
                                  const password = window.prompt('Set a password (leave empty for no password):');
                                  const body: any = {};
                                  if (password) body.password = password;
                                  const resp2 = await fetch(`/api/views/${view.pid}/share`, {
                                    method: 'post',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify(body),
                                  });
                                  const result = await resp2.json();
                                  if (result?.data?.shareUrl) {
                                    navigator.clipboard.writeText(result.data.shareUrl);
                                    window.alert(`Share link copied!\n\n${result.data.shareUrl}`);
                                  }
                                }
                              } catch (e) {
                                console.error('Share error:', e);
                              }
                            }}
                            className={cn(
                              'rounded-md p-1.5 text-gray-400',
                              'hover:bg-gray-100 hover:text-green-500',
                              'focus:ring-2 focus:ring-green-500 focus:outline-none',
                              'transition-colors duration-150',
                            )}
                            title="Share view"
                          >
                            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z" />
                            </svg>
                          </button>

                          {/* Delete Button (for PERSONAL and TEAM views the user owns) */}
                          {(view.scope === 'personal' || view.scope === 'team') && (
                            <button
                              type="button"
                              onClick={() => handleDelete(view)}
                              disabled={isViewLoading(view.pid)}
                              className={cn(
                                'rounded-md p-1.5 text-gray-400',
                                'hover:bg-gray-100 hover:text-red-500',
                                'focus:ring-2 focus:ring-red-500 focus:outline-none',
                                'disabled:cursor-not-allowed disabled:opacity-50',
                                'transition-colors duration-150',
                              )}
                              title="Delete view"
                            >
                              {loadingState.type === 'delete' && loadingState.pid === view.pid ? (
                                <span className="block h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-red-500" />
                              ) : (
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
                                    d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                                  />
                                </svg>
                              )}
                            </button>
                          )}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </>
  );
};

export default ViewManagePanel;
