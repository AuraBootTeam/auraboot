/**
 * ViewManagePanel Component
 *
 * A slide-out panel for managing saved views.
 * Allows users to create, delete, duplicate, and set default views.
 */

import React, { useState, useCallback, useEffect } from 'react';
import {
  type SavedView,
  type SavedViewAuditEvent,
  type SavedViewCreateRequest,
  type SavedViewTeamOption,
  type ViewConfig,
  type ViewScope,
  type ViewType,
} from '~/framework/smart/types/savedView';
import type { FieldOption } from './KanbanConfigPanel';
import { cn } from '~/utils/cn';
import { confirmDialog } from '~/utils/confirmDialog';
import { modelService } from '~/shared/services/modelService';
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
import { savedViewService } from '~/shared/services/savedViewService';

/**
 * View types that require field configuration after creation.
 * Table and Form views don't need configuration and close immediately.
 */
const VIEW_TYPE_REQUIRED_FIELDS: Record<string, Array<{
  key: string;
  label: string;
  required: boolean;
}>> = {
  kanban: [
    { key: 'groupByField', label: 'Group By', required: true },
    { key: 'titleField', label: 'Title Field', required: true },
  ],
  calendar: [
    { key: 'calendarDateField', label: 'Date Field', required: true },
    { key: 'calendarTitleField', label: 'Title Field', required: false },
  ],
  gantt: [
    { key: 'ganttStartDateField', label: 'Start Date', required: true },
    { key: 'ganttEndDateField', label: 'End Date', required: true },
    { key: 'ganttTitleField', label: 'Title Field', required: false },
  ],
  gallery: [
    { key: 'galleryImageField', label: 'Image Field', required: true },
    { key: 'galleryTitleField', label: 'Title Field', required: false },
  ],
  tree: [
    { key: 'treeParentField', label: 'Parent Field', required: true },
    { key: 'treeTitleField', label: 'Title Field', required: false },
  ],
  timeline: [
    { key: 'timelineStartField', label: 'Start Date', required: true },
    { key: 'timelineResourceField', label: 'Resource Field', required: true },
    { key: 'timelineEndField', label: 'End Date', required: false },
    { key: 'timelineTitleField', label: 'Title Field', required: false },
  ],
};

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
  /** Callback to edit a view (name, description, scope) */
  onEditView?: (pid: string, name: string, description: string, scope: ViewScope) => Promise<void>;
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
  /** Callback after view config is saved (e.g. after config step for Kanban) to reload views */
  onViewConfigSaved?: () => void;
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

function formatAuditTimestamp(timestamp?: string): string {
  if (!timestamp) {
    return '';
  }
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) {
    return timestamp;
  }
  return date.toLocaleString();
}

function getAuditSummary(event: SavedViewAuditEvent): string {
  const metadataSummary = event.metadata?.summary;
  if (typeof metadataSummary === 'string' && metadataSummary.trim()) {
    return metadataSummary;
  }
  if (event.changedFields && event.changedFields.length > 0) {
    return `Changed ${event.changedFields.join(', ')}`;
  }
  return event.commandCode || event.eventType || 'Saved view changed';
}

function nextAutoViewName(label: string, views: SavedView[]): string {
  const baseName = `${label} View`;
  const usedNames = new Set(
    views.map((view) => String(view.name ?? '').trim().toLowerCase()).filter(Boolean),
  );
  if (!usedNames.has(baseName.toLowerCase())) {
    return baseName;
  }

  let suffix = 2;
  while (usedNames.has(`${baseName} ${suffix}`.toLowerCase())) {
    suffix += 1;
  }
  return `${baseName} ${suffix}`;
}

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
  onEditView,
  onSetDefaultView,
  onSelectView,
  modelCode,
  pageKey,
  onCreateViewSuccess,
  startInCreateMode,
  modelPid,
  fields,
  onViewConfigSaved,
}) => {
  // Editing state for inline edit form
  const [editingView, setEditingView] = useState<SavedView | null>(null);
  const [editForm, setEditForm] = useState({ name: '', description: '', scope: 'personal' as ViewScope });

  // Loading states for operations
  const [loadingState, setLoadingState] = useState<{
    type: 'create' | 'delete' | 'duplicate' | 'setDefault' | 'rename' | null;
    pid?: string;
  }>({ type: null });
  const [teamOptions, setTeamOptions] = useState<SavedViewTeamOption[]>([]);
  const [teamLoading, setTeamLoading] = useState(false);
  const [createScope, setCreateScope] = useState<ViewScope>('personal');
  const [createTeamId, setCreateTeamId] = useState('');
  const [auditView, setAuditView] = useState<SavedView | null>(null);
  const [auditEvents, setAuditEvents] = useState<SavedViewAuditEvent[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);
  const [auditError, setAuditError] = useState<string | null>(null);

  /**
   * Reset state when panel closes
   */
  useEffect(() => {
    if (!open) {
      setLoadingState({ type: null });
      setConfigStep(null);
      setBlockedCapability(null);
      setCreateScope('personal');
      setCreateTeamId('');
      setAuditView(null);
      setAuditEvents([]);
      setAuditError(null);
    }
  }, [open]);

  useEffect(() => {
    if (open && startInCreateMode) {
      setShowTypePicker(true);
      setBlockedCapability(null);
    }
  }, [open, startInCreateMode]);

  useEffect(() => {
    if (!open) {
      return;
    }

    let cancelled = false;
    setTeamLoading(true);
    savedViewService
      .getMyTeams()
      .then((teams) => {
        if (cancelled) {
          return;
        }
        setTeamOptions(teams);
        setCreateTeamId((current) => current || teams[0]?.pid || '');
      })
      .catch(() => {
        if (cancelled) {
          return;
        }
        setTeamOptions([]);
        setCreateTeamId('');
      })
      .finally(() => {
        if (!cancelled) {
          setTeamLoading(false);
        }
      });

    return () => {
      cancelled = true;
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

  // Type picker expanded state
  const [showTypePicker, setShowTypePicker] = useState(false);

  // Config step state for view types that need field configuration
  const [configStep, setConfigStep] = useState<{
    viewType: ViewType;
    fields: Record<string, string>;
    capability: SavedViewCapabilityResult;
  } | null>(null);
  const [blockedCapability, setBlockedCapability] = useState<SavedViewCapabilityResult | null>(
    null,
  );

  // Model fields for config step dropdowns
  const [modelFields, setModelFields] = useState<FieldOption[]>([]);
  const [configSaving, setConfigSaving] = useState(false);

  // Use fields from props (already loaded by parent from DSL schema)
  // Fallback to API if props.fields is empty
  useEffect(() => {
    if (!configStep) {
      setModelFields([]);
      return;
    }
    // Prefer fields from props (from tableColumns in ListPageContent)
    if (fields && fields.length > 0) {
      setModelFields(fields);
      return;
    }
    // Fallback: fetch from API
    if (modelPid) {
      modelService
        .getModelFields(modelPid)
        .then((apiFields) => {
          setModelFields(
            apiFields.map((f) => ({
              code: f.fieldCode,
              name: f.displayName || f.fieldName || f.fieldCode,
              dataType: f.dataType || 'text',
            })),
          );
        })
        .catch(() => setModelFields([]));
    }
  }, [configStep, fields, modelPid]);

  const createTargetDisabled = createScope === 'team' && !createTeamId;

  const getCreateTargetPayload = useCallback((): Pick<SavedViewCreateRequest, 'scope' | 'teamId'> => {
    if (createScope === 'team') {
      return { scope: 'team', teamId: createTeamId };
    }
    return { scope: createScope };
  }, [createScope, createTeamId]);

  /**
   * One-click instant view creation (Feishu style).
   * Click type → immediately create and switch.
   */
  const handleQuickCreate = useCallback(async (viewType: ViewType = 'table') => {
    if (createTargetDisabled) return;

    const typeLabels: Record<string, string> = {
      table: 'Table', kanban: 'Kanban', calendar: 'Calendar', gallery: 'Gallery',
      gantt: 'Gantt', tree: 'Tree', timeline: 'Timeline', form: 'Form',
    };
    const label = typeLabels[viewType] || 'Table';
    const autoName = nextAutoViewName(label, views);
    const requiredFields = VIEW_TYPE_REQUIRED_FIELDS[viewType];

    setBlockedCapability(null);

    if (requiredFields) {
      const capability = checkSavedViewCapability(viewType, fields ?? []);
      if (capability.status === 'blocked') {
        setBlockedCapability(capability);
        return;
      }

      setConfigStep({
        viewType,
        fields: Object.fromEntries(
          Object.entries(capability.suggestedConfig)
            .filter(([, value]) => typeof value === 'string' && value)
            .map(([key, value]) => [key, String(value)]),
        ),
        capability,
      });
      setShowTypePicker(false);
      return;
    }

    setLoadingState({ type: 'create' });
    try {
      const createdView = await onCreateView({
        name: autoName,
        modelCode,
        pageKey,
        ...getCreateTargetPayload(),
        viewType,
        isDefault: false,
      });
      if (createdView?.pid) {
        onSelectView(createdView.pid);
      }
      onCreateViewSuccess?.(createdView);
      setShowTypePicker(false);
      onClose();
    } catch (err) {
      console.error('Failed to create view:', err);
    } finally {
      setLoadingState({ type: null });
    }
  }, [
    createTargetDisabled,
    fields,
    getCreateTargetPayload,
    modelCode,
    onClose,
    onCreateView,
    onCreateViewSuccess,
    onSelectView,
    pageKey,
    views,
  ]);

  const handleFinishConfigStep = useCallback(async () => {
    if (!configStep || createTargetDisabled) return;
    const requiredFields = VIEW_TYPE_REQUIRED_FIELDS[configStep.viewType] || [];
    const missingRequired = requiredFields
      .filter((field) => field.required)
      .some((field) => !configStep.fields[field.key]);
    if (missingRequired) return;

    const typeLabels: Record<string, string> = {
      table: 'Table', kanban: 'Kanban', calendar: 'Calendar', gallery: 'Gallery',
      gantt: 'Gantt', tree: 'Tree', timeline: 'Timeline', form: 'Form',
    };
    const label = typeLabels[configStep.viewType] || 'Table';
    const autoName = nextAutoViewName(label, views);
    const viewConfig = Object.entries(configStep.fields).reduce((acc, [key, value]) => {
      if (value) {
        (acc as Record<string, string>)[key] = value;
      }
      return acc;
    }, {} as Partial<ViewConfig>);

    setConfigSaving(true);
    setLoadingState({ type: 'create' });
    try {
      const createdView = await onCreateView({
        name: autoName,
        modelCode,
        pageKey,
        ...getCreateTargetPayload(),
        viewType: configStep.viewType,
        viewConfig,
        isDefault: false,
      });
      if (createdView?.pid) {
        onSelectView(createdView.pid);
      }
      onCreateViewSuccess?.(createdView);
      onViewConfigSaved?.();
      setConfigStep(null);
      setShowTypePicker(false);
      onClose();
    } catch (err) {
      console.error('Failed to create configured view:', err);
    } finally {
      setConfigSaving(false);
      setLoadingState({ type: null });
    }
  }, [
    configStep,
    createTargetDisabled,
    getCreateTargetPayload,
    modelCode,
    onClose,
    onCreateView,
    onCreateViewSuccess,
    onSelectView,
    onViewConfigSaved,
    pageKey,
    views,
  ]);

  /**
   * Handle delete view
   */
  const handleDelete = useCallback(
    async (view: SavedView) => {
      if (!canDeleteSavedView(view)) return;

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
   * Handle edit view — open inline edit form
   */
  const handleEdit = useCallback((view: SavedView) => {
    if (!canManageSavedView(view)) return;

    setEditingView(view);
    setEditForm({
      name: view.name,
      description: view.description || '',
      scope: view.scope,
    });
  }, []);

  /**
   * Save edited view metadata
   */
  const handleSaveEdit = useCallback(async () => {
    if (!editingView || !editForm.name.trim()) return;
    setLoadingState({ type: 'rename', pid: editingView.pid });
    try {
      await onEditView?.(editingView.pid, editForm.name.trim(), editForm.description.trim(), editForm.scope);
      setEditingView(null);
    } finally {
      setLoadingState({ type: null });
    }
  }, [editingView, editForm, onEditView]);

  /**
   * Cancel editing
   */
  const handleCancelEdit = useCallback(() => {
    setEditingView(null);
  }, []);

  /**
   * Handle duplicate view
   */
  const handleDuplicate = useCallback(
    async (view: SavedView) => {
      if (!canCopySavedView(view)) return;

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

  const handleOpenAudit = useCallback(async (view: SavedView) => {
    setAuditView(view);
    setAuditLoading(true);
    setAuditError(null);
    setAuditEvents([]);
    try {
      const events = await savedViewService.getAuditEvents(view.pid);
      setAuditEvents(events);
    } catch (err) {
      setAuditError(
        err instanceof Error ? err.message : 'Failed to fetch saved view audit events',
      );
    } finally {
      setAuditLoading(false);
    }
  }, []);

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
        className="fixed inset-0 z-[1100] bg-black/50 transition-opacity duration-200"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Panel */}
      <div
        className={cn(
          'fixed top-0 right-0 z-[1110] h-full w-[min(95vw,52rem)] bg-white shadow-xl',
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
          {/* Config Step — shown after creating a view that needs field configuration */}
          {configStep && (
            <div className="p-4">
              <div className="mb-3 flex items-center justify-between">
                <h4 className="text-sm font-semibold text-gray-900">
                  Configure {configStep.viewType.charAt(0).toUpperCase() + configStep.viewType.slice(1)} View
                </h4>
              </div>
              <p className="mb-4 text-xs text-gray-500">
                Select the fields to use for this view. Required fields must be set before you can finish.
              </p>
              {configStep.capability.status === 'degraded' && (
                <div
                  className="mb-4 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800"
                  role="status"
                  data-testid={`view-capability-degraded-${configStep.viewType}`}
                >
                  {configStep.capability.reasons.join(' ')}
                </div>
              )}
              <div className="space-y-3">
                {(VIEW_TYPE_REQUIRED_FIELDS[configStep.viewType] || []).map((fieldDef) => {
                  const options = configStep.capability.fieldOptions[fieldDef.key] ?? modelFields;
                  return (
                    <div key={fieldDef.key}>
                      <label className="mb-1 block text-xs font-medium text-gray-600">
                        {fieldDef.label} {fieldDef.required && <span className="text-red-500">*</span>}
                      </label>
                      <select
                        value={configStep.fields[fieldDef.key] || ''}
                        onChange={(e) =>
                          setConfigStep((prev) =>
                            prev
                              ? {
                                  ...prev,
                                  fields: { ...prev.fields, [fieldDef.key]: e.target.value },
                                }
                              : null,
                          )
                        }
                        className="w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                      >
                        <option value="">Select field...</option>
                        {options.map((f) => (
                          <option key={f.code} value={f.code}>
                            {f.name} ({f.dataType})
                          </option>
                        ))}
                      </select>
                    </div>
                  );
                })}
              </div>
              <div className="mt-4 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setConfigStep(null);
                    onClose();
                  }}
                  className="rounded-md px-3 py-1.5 text-xs text-gray-600 hover:bg-gray-100"
                >
                  Skip
                </button>
                <button
                  type="button"
                  onClick={handleFinishConfigStep}
                  disabled={
                    configSaving ||
                    createTargetDisabled ||
                    (VIEW_TYPE_REQUIRED_FIELDS[configStep.viewType] || [])
                      .filter((f) => f.required)
                      .some((f) => !configStep.fields[f.key])
                  }
                  className="rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                >
                  {configSaving ? 'Saving...' : 'Done'}
                </button>
              </div>
            </div>
          )}

          {/* New View Section — hidden when config step is active */}
          {!configStep && <div className="border-b border-gray-200 p-4">
            {showTypePicker ? (
              <div>
                <div className="mb-2 flex items-center justify-between">
                  <span className="text-xs font-semibold uppercase tracking-wider text-gray-500">Choose type</span>
                  <button type="button" onClick={() => setShowTypePicker(false)} className="text-xs text-gray-400 hover:text-gray-600">Cancel</button>
                </div>
                {loadingState.type === 'create' ? (
                  <div className="flex items-center justify-center gap-2 py-4 text-sm text-blue-600">
                    <span className="h-4 w-4 animate-spin rounded-full border-2 border-blue-300 border-t-blue-600" />
                    Creating...
                  </div>
                ) : (
                  <>
                    <div className="mb-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
                      <label className="block">
                        <span className="mb-1 block text-xs font-medium text-gray-600">Scope</span>
                        <select
                          aria-label="Scope"
                          value={createScope}
                          onChange={(e) => {
                            const nextScope = e.target.value as ViewScope;
                            setCreateScope(nextScope);
                            if (nextScope === 'team' && !createTeamId) {
                              setCreateTeamId(teamOptions[0]?.pid || '');
                            }
                          }}
                          className="w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                        >
                          <option value="personal">Personal</option>
                          <option value="team" disabled={teamLoading || teamOptions.length === 0}>
                            Team
                          </option>
                        </select>
                      </label>
                      <label className="block">
                        <span className="mb-1 block text-xs font-medium text-gray-600">Team</span>
                        <select
                          aria-label="Team"
                          value={createTeamId}
                          onChange={(e) => setCreateTeamId(e.target.value)}
                          disabled={createScope !== 'team' || teamLoading || teamOptions.length === 0}
                          className="w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm disabled:bg-gray-50 disabled:text-gray-400 focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                        >
                          {teamLoading && <option value="">Loading teams...</option>}
                          {!teamLoading && teamOptions.length === 0 && (
                            <option value="">No available teams</option>
                          )}
                          {!teamLoading &&
                            teamOptions.map((team) => (
                              <option key={team.pid} value={team.pid}>
                                {team.name}
                              </option>
                            ))}
                        </select>
                      </label>
                    </div>
                    {createScope === 'team' && createTargetDisabled && (
                      <div className="mb-3 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
                        Select a team before creating a shared view.
                      </div>
                    )}
                    {blockedCapability && (
                      <div
                        className="mb-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700"
                        role="alert"
                        data-testid={`view-capability-blocked-${blockedCapability.viewType}`}
                      >
                        {blockedCapability.reasons.join(' ')}
                      </div>
                    )}
                    <div className="grid grid-cols-4 gap-2">
                      {([
                      { type: 'table' as ViewType, icon: '≡', label: 'Table' },
                      { type: 'kanban' as ViewType, icon: '⊞', label: 'Kanban' },
                      { type: 'calendar' as ViewType, icon: '📅', label: 'Calendar' },
                      { type: 'gallery' as ViewType, icon: '▦', label: 'Gallery' },
                      { type: 'gantt' as ViewType, icon: '━', label: 'Gantt' },
                      { type: 'tree' as ViewType, icon: '⊟', label: 'Tree' },
                      { type: 'timeline' as ViewType, icon: '⏤', label: 'Timeline' },
                      { type: 'form' as ViewType, icon: '☐', label: 'Form' },
                    ]).map((vt) => (
                      <button
                        key={vt.type}
                        type="button"
                        onClick={() => handleQuickCreate(vt.type)}
                        disabled={createTargetDisabled}
                        className={cn(
                          'flex flex-col items-center gap-1 rounded-lg border border-gray-200 px-2 py-2.5 text-gray-600 transition-all',
                          'hover:border-blue-400 hover:bg-blue-50 hover:text-blue-600',
                          'disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:border-gray-200 disabled:hover:bg-white disabled:hover:text-gray-600',
                        )}
                      >
                        <span className="text-lg">{vt.icon}</span>
                        <span className="text-[10px] font-medium">{vt.label}</span>
                      </button>
                    ))}
                    </div>
                  </>
                )}
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setShowTypePicker(true)}
                className={cn(
                  'flex w-full items-center justify-center gap-2 rounded-lg',
                  'border-2 border-dashed border-gray-300 py-2.5',
                  'text-sm font-medium text-blue-600',
                  'transition-all hover:border-blue-400 hover:bg-blue-50',
                )}
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                </svg>
                New View
              </button>
            )}
          </div>}

          {/* View List — hidden when config step is active */}
          {!configStep && <div className="py-2">
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
                    <div key={view.pid}>
                    {editingView?.pid === view.pid ? (
                      /* Inline edit form */
                      <div className="mx-2 rounded-lg border border-blue-200 bg-blue-50 p-3">
                        <div className="space-y-3">
                          <div>
                            <label className="block text-xs font-medium text-gray-600 mb-1">Name</label>
                            <input
                              type="text"
                              value={editForm.name}
                              onChange={(e) => setEditForm(prev => ({ ...prev, name: e.target.value }))}
                              className="w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                              autoFocus
                            />
                          </div>
                          <div>
                            <label className="block text-xs font-medium text-gray-600 mb-1">Description</label>
                            <input
                              type="text"
                              value={editForm.description}
                              onChange={(e) => setEditForm(prev => ({ ...prev, description: e.target.value }))}
                              placeholder="Optional description"
                              className="w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                            />
                          </div>
                          <div>
                            <label className="block text-xs font-medium text-gray-600 mb-1">Scope</label>
                            <select
                              value={editForm.scope}
                              onChange={(e) => setEditForm(prev => ({ ...prev, scope: e.target.value as ViewScope }))}
                              className="w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                            >
                              <option value="personal">Personal</option>
                              <option value="team">Team</option>
                              <option value="global">Global</option>
                            </select>
                          </div>
                          <div className="flex justify-end gap-2">
                            <button
                              type="button"
                              onClick={handleCancelEdit}
                              className="rounded-md px-3 py-1.5 text-xs text-gray-600 hover:bg-gray-100"
                            >
                              Cancel
                            </button>
                            <button
                              type="button"
                              onClick={handleSaveEdit}
                              disabled={!editForm.name.trim() || loadingState.type === 'rename'}
                              className="rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50"
                            >
                              {loadingState.type === 'rename' ? 'Saving...' : 'Save'}
                            </button>
                          </div>
                        </div>
                      </div>
                    ) : (
                      /* Normal view row */
                    <div
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
                            {isSavedViewLockedPreset(view) && (
                              <span
                                className="flex-shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-xs font-medium text-gray-600"
                                data-testid={`view-locked-preset-${view.pid}`}
                              >
                                Preset
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
                            disabled={
                              isViewLoading(view.pid) ||
                              !canSetDefaultSavedView(view)
                            }
                            className={cn(
                              'rounded-md p-1.5',
                              'focus:ring-2 focus:ring-blue-500 focus:outline-none',
                              'transition-colors duration-150',
                              'disabled:cursor-not-allowed disabled:opacity-50',
                              view.isDefault
                                ? 'cursor-default text-yellow-500'
                                : 'text-gray-400 hover:bg-gray-100 hover:text-yellow-500',
                            )}
                            title={
                              isSavedViewLockedPreset(view)
                                ? 'Plugin preset is locked'
                                : view.isDefault
                                  ? 'Default view'
                                  : 'Set as default'
                            }
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

                          {/* Edit Button */}
                          {onEditView && (
                            <button
                              type="button"
                              onClick={() => handleEdit(view)}
                              disabled={isViewLoading(view.pid) || !canManageSavedView(view)}
                              className={cn(
                                'rounded-md p-1.5 text-gray-400',
                                'hover:bg-gray-100 hover:text-green-500',
                                'focus:ring-2 focus:ring-blue-500 focus:outline-none',
                                'disabled:cursor-not-allowed disabled:opacity-50',
                                'transition-colors duration-150',
                              )}
                              title={
                                isSavedViewLockedPreset(view)
                                  ? 'Plugin preset is locked'
                                  : 'Edit view'
                              }
                            >
                              {loadingState.type === 'rename' && loadingState.pid === view.pid ? (
                                <span className="block h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-green-500" />
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
                                    d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                                  />
                                </svg>
                              )}
                            </button>
                          )}

                          {/* Duplicate Button */}
                          <button
                            type="button"
                            onClick={() => handleDuplicate(view)}
                            disabled={isViewLoading(view.pid) || !canCopySavedView(view)}
                            className={cn(
                              'rounded-md p-1.5 text-gray-400',
                              'hover:bg-gray-100 hover:text-blue-500',
                              'focus:ring-2 focus:ring-blue-500 focus:outline-none',
                              'disabled:cursor-not-allowed disabled:opacity-50',
                              'transition-colors duration-150',
                            )}
                            title={canCopySavedView(view) ? 'Duplicate view' : 'Copy is disabled'}
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

                          {(view.scope === 'team' ||
                            view.scope === 'global' ||
                            isSavedViewLockedPreset(view)) && (
                            <button
                              type="button"
                              onClick={() => handleOpenAudit(view)}
                              disabled={auditLoading && auditView?.pid === view.pid}
                              className={cn(
                                'rounded-md p-1.5 text-gray-400',
                                'hover:bg-gray-100 hover:text-indigo-500',
                                'focus:ring-2 focus:ring-indigo-500 focus:outline-none',
                                'disabled:cursor-not-allowed disabled:opacity-50',
                                'transition-colors duration-150',
                              )}
                              title="View audit"
                              data-testid={`view-audit-${view.pid}`}
                            >
                              {auditLoading && auditView?.pid === view.pid ? (
                                <span className="block h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-indigo-500" />
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
                                    d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414A1 1 0 0119 9.414V19a2 2 0 01-2 2z"
                                  />
                                </svg>
                              )}
                            </button>
                          )}

                          {/* Share Button */}
                          <button
                            type="button"
                            onClick={async () => {
                              if (!canShareSavedView(view)) return;
                              try {
                                const resp = await fetch(`/api/views/${view.pid}/share/status`);
                                const data = await resp.json();
                                const isShared = data?.data?.shared;
                                if (isShared) {
                                  const shareUrl = data?.data?.shareUrl || '';
                                  const action = window.confirm(
                                    `This view is shared.\n\nURL: ${shareUrl}\n\nClick OK to copy link, Cancel to revoke sharing.`,
                                  );
                                  if (action) {
                                    navigator.clipboard.writeText(shareUrl);
                                  } else {
                                    await fetch(`/api/views/${view.pid}/share`, {
                                      method: 'delete',
                                    });
                                  }
                                } else {
                                  const password = window.prompt(
                                    'Set a password (leave empty for no password):',
                                  );
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
                            disabled={!canShareSavedView(view)}
                            className={cn(
                              'rounded-md p-1.5 text-gray-400',
                              'hover:bg-gray-100 hover:text-green-500',
                              'focus:ring-2 focus:ring-green-500 focus:outline-none',
                              'disabled:cursor-not-allowed disabled:opacity-50',
                              'transition-colors duration-150',
                            )}
                            title="Share view"
                          >
                            <svg
                              className="h-4 w-4"
                              fill="none"
                              stroke="currentColor"
                              viewBox="0 0 24 24"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"
                              />
                            </svg>
                          </button>

                          {/* Delete Button (for PERSONAL and TEAM views the user owns) */}
                          {(view.scope === 'personal' || view.scope === 'team') &&
                            !isSavedViewLockedPreset(view) && (
                            <button
                              type="button"
                              onClick={() => handleDelete(view)}
                              disabled={isViewLoading(view.pid) || !canDeleteSavedView(view)}
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
                    )}
                    </div>
                  ))}
                </div>
              ))
            )}
          </div>}

          {!configStep && auditView && (
            <div
              className="m-4 rounded-lg border border-gray-200 bg-gray-50 p-4"
              data-testid="saved-view-audit-panel"
            >
              <div className="mb-3 flex items-start justify-between gap-3">
                <div>
                  <h3 className="text-sm font-semibold text-gray-900">
                    Audit: {auditView.name}
                  </h3>
                  <p className="mt-0.5 text-xs text-gray-500">
                    Recent changes for shared or managed views.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    setAuditView(null);
                    setAuditEvents([]);
                    setAuditError(null);
                  }}
                  className="rounded-md p-1 text-gray-400 hover:bg-white hover:text-gray-600"
                  aria-label="Close audit panel"
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
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                </button>
              </div>

              {auditLoading ? (
                <div className="flex items-center gap-2 text-xs text-indigo-600">
                  <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-indigo-200 border-t-indigo-600" />
                  Loading audit events...
                </div>
              ) : auditError ? (
                <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
                  {auditError}
                </div>
              ) : auditEvents.length === 0 ? (
                <div className="text-xs text-gray-500">No audit events yet.</div>
              ) : (
                <div className="space-y-2">
                  {auditEvents.map((event, index) => (
                    <div
                      key={`${event.sequenceNo ?? event.entityPid ?? event.timestamp ?? index}`}
                      className="rounded-md border border-gray-200 bg-white px-3 py-2"
                      data-testid="saved-view-audit-event"
                    >
                      <div className="flex items-center justify-between gap-3 text-xs">
                        <span className="font-medium text-gray-900">
                          {event.operationType || event.commandCode || 'UPDATE'}
                        </span>
                        {event.timestamp && (
                          <span className="text-gray-400">
                            {formatAuditTimestamp(event.timestamp)}
                          </span>
                        )}
                      </div>
                      <div className="mt-1 text-xs text-gray-600">{getAuditSummary(event)}</div>
                      {event.actorName && (
                        <div className="mt-1 text-xs text-gray-400">By {event.actorName}</div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </>
  );
};

export default ViewManagePanel;
