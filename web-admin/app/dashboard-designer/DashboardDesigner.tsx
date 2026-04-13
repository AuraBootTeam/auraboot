/**
 * Dashboard Designer Main Component
 *
 * A full-featured dashboard designer with:
 * - Three-panel layout (palette, canvas, properties)
 * - Drag-and-drop widget placement
 * - Widget configuration editing
 * - Undo/redo support
 * - Save/publish workflow
 */

import React, { useCallback, useEffect, useState, useRef } from 'react';
import {
  DesignerToolbar,
  WidgetPalette,
  DesignerCanvas,
  WidgetPropertyPanel,
  BigScreenMode,
} from './components';
import { useDashboardStore } from './store/useDashboardStore';
import type { WidgetType, DashboardScope } from './types';
import { widgetRegistry } from './widgets/widgetRegistry';
import { useToast } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import { useVersioning, VersionHistoryPanel, dashboardVersionService } from '~/shared/versioning';
import { fetchCurrentUserTeams, type TeamOption } from '~/services/teamService';
import { useHydrated } from '~/hooks/useHydrated';

/** Auto-save delay in milliseconds */
const AUTO_SAVE_DELAY = 30000; // 30 seconds

interface DashboardDesignerProps {
  /** Dashboard PID for editing existing dashboard */
  dashboardId?: string;
  /** Initial title for new dashboard */
  initialTitle?: string;
  /** Callback when save is completed */
  onSaveComplete?: () => void;
  /** Callback when close is requested */
  onClose?: () => void;
}

export const DashboardDesigner: React.FC<DashboardDesignerProps> = ({
  dashboardId,
  initialTitle,
  onSaveComplete,
  onClose,
}) => {
  const hydrated = useHydrated();
  const {
    dashboard,
    isDirty,
    isLoading,
    isSaving,
    loadDashboard,
    createDashboard,
    saveDashboard,
    publishDashboard,
    unpublishDashboard,
    updateDashboardMeta,
    addWidget,
    reset,
    validate,
  } = useDashboardStore();

  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToast();
  const { t } = useI18n();

  const effectiveTitle = initialTitle || t('dashboard.designer.defaultTitle');

  const autoSaveTimerRef = useRef<NodeJS.Timeout | null>(null);
  const lastSaveTimeRef = useRef<number>(0);
  const canvasRef = useRef<HTMLDivElement>(null);

  // Version history management
  const versioning = useVersioning({
    service: dashboardVersionService,
    resourcePid: dashboard?.pid,
    onRollbackComplete: () => {
      // Reload the dashboard after rollback
      if (dashboard?.pid) {
        loadDashboard(dashboard.pid);
        showSuccessToast('Rollback successful');
      }
    },
  });

  const [showBigScreen, setShowBigScreen] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [titleInput, setTitleInput] = useState(effectiveTitle);
  const [descriptionInput, setDescriptionInput] = useState('');
  const [scopeInput, setScopeInput] = useState<DashboardScope>('personal');
  const [teamIdInput, setTeamIdInput] = useState('');
  const [teamOptions, setTeamOptions] = useState<TeamOption[]>([]);

  // Load or create dashboard on mount
  useEffect(() => {
    if (dashboardId) {
      loadDashboard(dashboardId);
    } else {
      createDashboard(effectiveTitle);
    }

    return () => {
      reset();
    };
  }, [dashboardId, effectiveTitle, loadDashboard, createDashboard, reset]);

  // Update inputs when dashboard loads
  useEffect(() => {
    if (dashboard) {
      setTitleInput(dashboard.title || '');
      setDescriptionInput(dashboard.description || '');
      setScopeInput(dashboard.scope || 'personal');
      setTeamIdInput(dashboard.teamId || '');
    }
  }, [dashboard]);

  useEffect(() => {
    let mounted = true;
    fetchCurrentUserTeams()
      .then((teams) => {
        if (!mounted) return;
        setTeamOptions(teams);
      })
      .catch(() => {
        if (!mounted) return;
        setTeamOptions([]);
      });
    return () => {
      mounted = false;
    };
  }, []);

  // Auto-save when dirty (debounced) — with mutual exclusion against manual save
  useEffect(() => {
    if (!isDirty || isSaving) {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
        autoSaveTimerRef.current = null;
      }
      return;
    }

    autoSaveTimerRef.current = setTimeout(async () => {
      // Skip auto-save if a manual save happened recently
      const timeSinceLastSave = Date.now() - lastSaveTimeRef.current;
      if (timeSinceLastSave < AUTO_SAVE_DELAY || isSaving) {
        return;
      }
      try {
        await saveDashboard();
        lastSaveTimeRef.current = Date.now();
        showInfoToast(t('dashboard.designer.autoSaveSuccess'));
      } catch (error) {
        console.error('Auto-save failed:', error);
      }
    }, AUTO_SAVE_DELAY);

    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
        autoSaveTimerRef.current = null;
      }
    };
  }, [isDirty, isSaving, saveDashboard, showInfoToast]);

  const handleSave = useCallback(async () => {
    // Validate before saving
    const validationResult = validate();
    if (!validationResult.valid) {
      const errorMessages = validationResult.errors
        .filter((e) => e.type === 'error')
        .map((e) => e.message)
        .join('；');
      showErrorToast(`${t('dashboard.designer.saveFailed')}: ${errorMessages}`);
      return;
    }

    // Show warnings if any
    const warnings = validationResult.errors.filter((e) => e.type === 'warning');
    if (warnings.length > 0) {
      showWarningToast(`${t('common.notice')}: ${warnings.map((w) => w.message).join('; ')}`);
    }

    try {
      await saveDashboard();
      lastSaveTimeRef.current = Date.now();
      showSuccessToast(t('common.saveSuccess'));
      versioning.refreshVersions();
      onSaveComplete?.();
    } catch (error) {
      console.error('Save failed:', error);
      const message =
        error instanceof Error ? error.message : t('dashboard.designer.saveFailedRetry');
      showErrorToast(message);
    }
  }, [
    saveDashboard,
    validate,
    showSuccessToast,
    showErrorToast,
    showWarningToast,
    onSaveComplete,
    t,
  ]);

  // Handle keyboard shortcuts
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        handleSave();
      }
      if ((e.ctrlKey || e.metaKey) && e.key === 'z') {
        e.preventDefault();
        useDashboardStore.getState().undo();
      }
      if ((e.ctrlKey || e.metaKey) && (e.key === 'y' || (e.shiftKey && e.key === 'z'))) {
        e.preventDefault();
        useDashboardStore.getState().redo();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [handleSave]);

  const handlePublish = useCallback(async () => {
    if (isDirty) {
      showWarningToast(t('dashboard.designer.saveFirst'));
      return;
    }

    // Validate before publishing
    const validationResult = validate();
    if (!validationResult.valid) {
      const errorMessages = validationResult.errors
        .filter((e) => e.type === 'error')
        .map((e) => e.message)
        .join('；');
      showErrorToast(`${t('dashboard.designer.publishFailed')}: ${errorMessages}`);
      return;
    }

    try {
      await publishDashboard();
      showSuccessToast(t('dashboard.designer.publishSuccess'));
      versioning.refreshVersions();
    } catch (error) {
      console.error('Publish failed:', error);
      const message =
        error instanceof Error ? error.message : t('dashboard.designer.publishFailedRetry');
      showErrorToast(message);
    }
  }, [isDirty, publishDashboard, validate, showSuccessToast, showErrorToast, showWarningToast]);

  const handleUnpublish = useCallback(async () => {
    try {
      await unpublishDashboard();
      showInfoToast(t('dashboard.designer.unpublishSuccess'));
      versioning.refreshVersions();
    } catch (error) {
      console.error('Unpublish failed:', error);
      showErrorToast(t('dashboard.designer.unpublishFailed'));
    }
  }, [unpublishDashboard, showInfoToast, showErrorToast]);

  const handleWidgetClick = useCallback(
    (widgetType: WidgetType) => {
      const widgetDef = widgetRegistry.get(widgetType);
      if (!widgetDef) return;

      addWidget({
        type: widgetType,
        componentType: widgetType,
        x: 0,
        y: Infinity, // Will be placed at the bottom
        w: widgetDef.defaultSize.w,
        h: widgetDef.defaultSize.h,
        minW: widgetDef.defaultSize.minW,
        minH: widgetDef.defaultSize.minH,
        maxW: widgetDef.defaultSize.maxW,
        maxH: widgetDef.defaultSize.maxH,
        props: {},
        config: {
          title: widgetDef.defaultConfig.title || widgetDef.label,
          dataSource: widgetDef.defaultConfig.dataSource || {
            type: 'aggregate',
            metrics: [{ field: 'id', aggregation: 'count' }],
          },
        },
      });
    },
    [addWidget],
  );

  // Confirm before leaving with unsaved changes
  useEffect(() => {
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      if (isDirty) {
        e.preventDefault();
        e.returnValue = '';
      }
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [isDirty]);

  if (!hydrated || isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-gray-50">
        <div className="flex flex-col items-center gap-4">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
          <p className="text-gray-600">{t('common.loading')}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-screen flex-col bg-gray-50">
      {/* Toolbar */}
      <DesignerToolbar
        onSave={handleSave}
        onPublish={handlePublish}
        onUnpublish={handleUnpublish}
        onSettings={() => setShowSettings(true)}
        onToggleVersionHistory={versioning.togglePanel}
        versionCount={versioning.versions.length}
        canvasRef={canvasRef}
        onPresentation={() => setShowBigScreen(true)}
      />

      {/* Main content area */}
      <div className="flex flex-1 overflow-hidden">
        {/* Widget palette */}
        <WidgetPalette onWidgetClick={handleWidgetClick} />

        {/* Canvas */}
        <div ref={canvasRef} className="flex min-w-0 flex-1 flex-col">
          <DesignerCanvas />
        </div>

        {/* Property panel */}
        <WidgetPropertyPanel />
      </div>

      {/* Version History Panel */}
      <VersionHistoryPanel
        isOpen={versioning.isOpen}
        onClose={versioning.closePanel}
        versions={versioning.versions}
        isLoading={versioning.isLoading}
        viewingVersionPid={versioning.viewingVersionPid}
        onPreview={versioning.previewVersion}
        onExitPreview={versioning.exitPreview}
        onRollback={versioning.rollbackToVersion}
        isRollingBack={versioning.isRollingBack}
      />

      {/* Big Screen Presentation Mode */}
      {showBigScreen && dashboard?.pid && (
        <BigScreenMode dashboardId={dashboard.pid} onExit={() => setShowBigScreen(false)} />
      )}

      {/* Settings dialog */}
      {showSettings && (
        <div
          className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black"
          role="dialog"
          aria-modal="true"
          aria-label="Dashboard Settings"
          onClick={(e) => {
            if (e.target === e.currentTarget) {
              setTitleInput(dashboard?.title || '');
              setDescriptionInput(dashboard?.description || '');
              setScopeInput(dashboard?.scope || 'personal');
              setTeamIdInput(dashboard?.teamId || '');
              setShowSettings(false);
            }
          }}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              setTitleInput(dashboard?.title || '');
              setDescriptionInput(dashboard?.description || '');
              setScopeInput(dashboard?.scope || 'personal');
              setTeamIdInput(dashboard?.teamId || '');
              setShowSettings(false);
            }
          }}
        >
          <div className="w-[480px] rounded-lg bg-white shadow-xl">
            <div className="border-b border-gray-200 px-6 py-4">
              <h2 className="text-lg font-semibold text-gray-900">
                {t('dashboard.designer.settings')}
              </h2>
            </div>
            <div className="space-y-4 p-6">
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  {t('common.title')} <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={titleInput}
                  onChange={(e) => setTitleInput(e.target.value)}
                  placeholder={t('dashboard.designer.titlePlaceholder')}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  {t('common.description')}
                </label>
                <textarea
                  value={descriptionInput}
                  onChange={(e) => setDescriptionInput(e.target.value)}
                  placeholder={t('dashboard.designer.descriptionPlaceholder')}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  rows={3}
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  {t('dashboard.designer.visibilityScope')}
                </label>
                <select
                  value={scopeInput}
                  onChange={(e) => {
                    const scope = e.target.value as DashboardScope;
                    setScopeInput(scope);
                    if (scope !== 'team') {
                      setTeamIdInput('');
                    }
                  }}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                >
                  <option value="personal">{t('dashboard.scope.personal')}</option>
                  <option value="team">{t('dashboard.scope.team')}</option>
                  <option value="global">{t('dashboard.scope.global')}</option>
                </select>
              </div>
              {scopeInput === 'team' && (
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    {t('dashboard.designer.team')} <span className="text-red-500">*</span>
                  </label>
                  {teamOptions.length > 0 ? (
                    <select
                      value={teamIdInput}
                      onChange={(e) => setTeamIdInput(e.target.value)}
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    >
                      <option value="">{t('dashboard.designer.selectTeam')}</option>
                      {teamOptions.map((team) => (
                        <option key={team.id} value={team.id}>
                          {team.name}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <input
                      type="text"
                      value={teamIdInput}
                      onChange={(e) => setTeamIdInput(e.target.value)}
                      placeholder={t('dashboard.designer.enterTeamId')}
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    />
                  )}
                </div>
              )}
            </div>
            <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
              <button
                onClick={() => {
                  // Reset to original values
                  setTitleInput(dashboard?.title || '');
                  setDescriptionInput(dashboard?.description || '');
                  setScopeInput(dashboard?.scope || 'personal');
                  setTeamIdInput(dashboard?.teamId || '');
                  setShowSettings(false);
                }}
                className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
              >
                {t('common.cancel')}
              </button>
              <button
                onClick={() => {
                  if (!titleInput.trim()) {
                    showErrorToast(t('dashboard.designer.titleRequired'));
                    return;
                  }
                  if (scopeInput === 'team' && !teamIdInput.trim()) {
                    showErrorToast(t('dashboard.designer.teamRequired'));
                    return;
                  }
                  updateDashboardMeta({
                    title: titleInput.trim(),
                    description: descriptionInput.trim(),
                    scope: scopeInput,
                    teamId: scopeInput === 'team' ? teamIdInput.trim() : undefined,
                  });
                  showSuccessToast(t('dashboard.designer.settingsUpdated'));
                  setShowSettings(false);
                }}
                className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
              >
                {t('common.save')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default DashboardDesigner;
