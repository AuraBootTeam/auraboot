/**
 * Page Designer Editor — Implementation
 *
 * Extracted from the route file so it can be React.lazy()-loaded,
 * keeping the entire Studio workbench out of the initial bundle.
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import { useParams, useNavigate } from 'react-router';
import { DesignerRouter } from '~/studio/workbench/designers';
import { DesignerToolbar } from '~/studio/workbench/components/toolbar';
import { AiPagePanel } from '~/studio/components/AiPagePanel';
import type { MergeMode } from '~/studio/components/ai-page-prompt';
import {
  SettingsPanel,
  ShortcutHelpPanel,
  VersionHistoryPanel,
  PreviewModal,
} from '~/studio/workbench/panels';
import type { AllSettings } from '~/studio/workbench/panels/settings/types';
import { useToolbarState } from '~/studio/hooks/workbench/useToolbarState';
import { useDslHistory } from '~/studio/hooks/useDslHistory';
import { useDesignerShortcuts } from '~/studio/hooks/shortcuts/useDesignerShortcuts';
import { pageManagerService } from '~/studio/services/page-manager';
import type { PageMeta } from '~/studio/services/page-manager';
import type { DslV4Schema } from '~/studio/domain/dsl/types';
import { DEVICE_PRESETS } from '~/studio/workbench/canvas/devices/presets';

/**
 * Build default DSL V4 schema for a page
 */
function buildDefaultDslV4(page: PageMeta): DslV4Schema {
  // Detect composite pages from page mode or existing DSL
  const rawSchema = page.dslSchema as Record<string, unknown> | null;
  const isComposite = page.mode === 'composite' || rawSchema?.kind === 'composite';

  if (isComposite) {
    return {
      $schema: 'auraboot://schemas/page/v4',
      version: '4.0.0',
      id: page.id,
      kind: 'composite',
      modelCode: page.viewModelCode || '',
      layout: { type: 'canvas' },
    } as DslV4Schema;
  }

  const kind = page.mode === 'form' ? 'form' : 'list';
  return {
    $schema: 'auraboot://schemas/page/v4',
    version: '4.0.0',
    id: page.id,
    kind,
    modelCode: page.viewModelCode || '',
    layout: { type: 'grid', columns: 12 },
    areas:
      kind === 'list'
        ? {
            filters: { blocks: [] },
            toolbar: { blocks: [] },
            main: { blocks: [] },
          }
        : {
            main: { blocks: [] },
          },
  };
}

/**
 * Page Designer Editor — full implementation with toolbar, canvas, and panels.
 */
export default function PageDesignerEditorImpl() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [page, setPage] = useState<PageMeta | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isCustomApiMode, setIsCustomApiMode] = useState(false);
  const [aiPanelOpen, setAiPanelOpen] = useState(false);
  const { showSuccessToast } = useToastContext();

  // DSL V4 state
  const [dsl, setDsl] = useState<DslV4Schema | null>(null);

  // DSL history for undo/redo — initialized with a placeholder, updated once DSL loads
  const dslHistory = useDslHistory(
    dsl || {
      $schema: 'auraboot://schemas/page/v4',
      version: '4.0.0',
      id: '',
      kind: 'list',
      modelCode: '',
      layout: { type: 'grid', columns: 12 },
    },
  );

  // Load page data
  useEffect(() => {
    if (!id) {
      setError('Page ID is required');
      setLoading(false);
      return;
    }

    // Ensure localStorage data is loaded on client side
    pageManagerService.ensureLoaded();

    pageManagerService
      .getPage(id)
      .then((result) => {
        if (result) {
          setPage(result);

          // Detect custom API mode before DSL cast
          const rawSchema = result.dslSchema as Record<string, any> | null;
          // CUSTOM pages have dataSource.type === 'api' — they may still have
          // a placeholder modelCode in the DSL, so we check dataSource type only
          const detectedCustomApi = !!(rawSchema && rawSchema.dataSource?.type === 'api');
          setIsCustomApiMode(detectedCustomApi);

          // Load DSL V4 or create default
          const rawDsl = result.dslSchema as unknown as DslV4Schema | null;
          if (rawDsl && rawDsl.kind && (rawDsl.areas || rawDsl.floors || rawDsl.kind === 'composite')) {
            setDsl(rawDsl);
          } else if (result.mode === 'composite') {
            // Composite page with empty/null dslSchema — build default canvas DSL
            setDsl(buildDefaultDslV4(result));
          } else {
            setDsl(buildDefaultDslV4(result));
          }
        } else {
          setError('Page not found');
        }
      })
      .catch((err) => {
        console.error('Failed to load page:', err);
        setError('Failed to load page');
      })
      .finally(() => {
        setLoading(false);
      });
  }, [id]);

  // Ref for debounced auto-save timer
  const autoSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const latestDslRef = useRef<DslV4Schema | null>(null);

  // Clean up debounce timer on unmount
  useEffect(() => {
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, []);

  const handleSave = useCallback(async () => {
    // Use latestDslRef for the most current DSL (avoids stale closure from debounced auto-save)
    const currentDsl = latestDslRef.current || dsl;
    if (!id || !currentDsl) return;

    // Count blocks — composite pages store blocks at top level
    const rawDsl = currentDsl as unknown as Record<string, unknown>;
    let blockCount: number;
    if (currentDsl.kind === 'composite' && Array.isArray(rawDsl.blocks)) {
      blockCount = (rawDsl.blocks as unknown[]).length;
    } else {
      blockCount = Object.values(currentDsl.areas || {}).reduce(
        (sum, area) => sum + (area.blocks?.length || 0),
        0,
      );
    }
    await pageManagerService.updatePageSchema(
      id,
      currentDsl as unknown as Record<string, unknown>,
      blockCount,
    );
  }, [id, dsl]);

  const handlePublish = useCallback(async () => {
    if (!id || !dsl) return;

    // First save the current DSL — composite pages store blocks at top level
    const rawPublishDsl = dsl as unknown as Record<string, unknown>;
    let blockCount: number;
    if (dsl.kind === 'composite' && Array.isArray(rawPublishDsl.blocks)) {
      blockCount = (rawPublishDsl.blocks as unknown[]).length;
    } else {
      blockCount = Object.values(dsl.areas || {}).reduce(
        (sum, area) => sum + (area.blocks?.length || 0),
        0,
      );
    }
    await pageManagerService.updatePageSchema(
      id,
      dsl as unknown as Record<string, unknown>,
      blockCount,
    );

    // Then publish the page
    const updatedPage = await pageManagerService.publishPage(id);
    if (updatedPage) {
      setPage(updatedPage);
      showSuccessToast('Page published successfully');
    }
  }, [id, dsl, showSuccessToast]);

  // Toolbar state
  const { state: toolbarState, actions: toolbarActions } = useToolbarState({
    pageMeta: page || undefined,
    onSave: handleSave,
    onPublish: handlePublish,
  });

  // Debounced auto-save — fires 2 seconds after the last DSL change.
  // Defined after toolbarActions so the save callback is always current.
  const scheduleAutoSave = useCallback(() => {
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
    }
    autoSaveTimerRef.current = setTimeout(() => {
      toolbarActions.save().catch(() => {
        // Auto-save failure is non-fatal — user can still manually save
      });
    }, 2000);
  }, [toolbarActions]);

  // DSL change handler — also pushes to history and schedules auto-save
  const handleDslChange = useCallback(
    (newDsl: DslV4Schema) => {
      setDsl(newDsl);
      latestDslRef.current = newDsl;
      dslHistory.pushState(newDsl);
      toolbarActions.markUnsaved();
      scheduleAutoSave();
    },
    [toolbarActions, dslHistory, scheduleAutoSave],
  );

  // Undo handler
  const handleUndo = useCallback(() => {
    const prev = dslHistory.undo();
    if (prev) {
      setDsl(prev);
      toolbarActions.markUnsaved();
    }
  }, [dslHistory, toolbarActions]);

  // Redo handler
  const handleRedo = useCallback(() => {
    const next = dslHistory.redo();
    if (next) {
      setDsl(next);
      toolbarActions.markUnsaved();
    }
  }, [dslHistory, toolbarActions]);

  // Settings -> DSL sync handler
  const handleSettingsChange = useCallback(
    (settings: AllSettings) => {
      if (!dsl) return;
      const updatedDsl: DslV4Schema = {
        ...dsl,
        enableMultiView: settings.page.enableMultiView,
      };
      handleDslChange(updatedDsl);
    },
    [dsl, handleDslChange],
  );

  // DSL save handler
  const handleDslSave = useCallback(
    async (newDsl: DslV4Schema) => {
      if (!id) return;
      const rawSaveDsl = newDsl as unknown as Record<string, unknown>;
      let blockCount: number;
      if (newDsl.kind === 'composite' && Array.isArray(rawSaveDsl.blocks)) {
        blockCount = (rawSaveDsl.blocks as unknown[]).length;
      } else {
        blockCount = Object.values(newDsl.areas || {}).reduce(
          (sum, area) => sum + (area.blocks?.length || 0),
          0,
        );
      }
      await pageManagerService.updatePageSchema(
        id,
        newDsl as unknown as Record<string, unknown>,
        blockCount,
      );
      toolbarActions.markSaved();
    },
    [id, toolbarActions],
  );

  const handleBack = useCallback(() => {
    navigate('/page-designer');
  }, [navigate]);

  // Reload page data after rollback
  const handleRollbackSuccess = useCallback(async () => {
    if (!id) return;
    const updatedPage = await pageManagerService.getPage(id);
    if (updatedPage) {
      setPage(updatedPage);
      // Also reload DSL
      const rawDsl = updatedPage.dslSchema as unknown as DslV4Schema | null;
      if (rawDsl && rawDsl.kind && rawDsl.areas) {
        setDsl(rawDsl);
      }
    }
  }, [id]);

  // Shared AI merge handler — used by both toolbar (legacy dialog) and AI panel
  const handleAiGenerated = useCallback(
    (generated: { kind: string; blocks: any[]; layout: any; schemaVersion: number; mergeMode?: MergeMode }) => {
      const mergeMode: MergeMode = generated.mergeMode || 'replace';
      const existingBlocks = (dsl as any)?.blocks || [];
      const mergedBlocks =
        mergeMode === 'append'
          ? [...existingBlocks, ...generated.blocks]
          : generated.blocks;

      const aiDsl: DslV4Schema = {
        ...(dsl || {}),
        $schema: 'auraboot://schemas/page/v4',
        version: '4.0.0',
        id: page?.id || '',
        kind: (generated.kind as any) || 'composite',
        modelCode: dsl?.modelCode || page?.viewModelCode || '',
        blocks: mergedBlocks,
        layout: generated.layout,
        schemaVersion: generated.schemaVersion,
      } as DslV4Schema;
      handleDslChange(aiDsl);
      showSuccessToast(
        mergeMode === 'append'
          ? `Added ${generated.blocks.length} block(s) to canvas`
          : 'Page layout generated by AI',
      );
    },
    [dsl, page, handleDslChange, showSuccessToast],
  );

  // Keyboard shortcuts — must be called before any early returns (React hooks rule)
  useDesignerShortcuts({
    onSave: handleSave,
    onUndo: handleUndo,
    onRedo: handleRedo,
    onZoomIn: toolbarActions.zoomIn,
    onZoomOut: toolbarActions.zoomOut,
    onZoomReset: toolbarActions.zoomReset,
  });

  // Loading state
  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-gray-50">
        <div className="flex flex-col items-center gap-4">
          <div className="h-12 w-12 animate-spin rounded-full border-4 border-blue-600 border-t-transparent" />
          <p className="text-gray-600">Loading page...</p>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="flex h-screen items-center justify-center bg-gray-50">
        <div className="flex max-w-md flex-col items-center gap-4 text-center">
          <div className="flex h-16 w-16 items-center justify-center rounded-full bg-red-100">
            <svg
              className="h-8 w-8 text-red-600"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
              />
            </svg>
          </div>
          <h2 className="text-xl font-semibold text-gray-900">{error}</h2>
          <p className="text-gray-500">
            The page you're looking for doesn't exist or couldn't be loaded.
          </p>
          <button
            onClick={handleBack}
            className="rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
          >
            Back to Pages
          </button>
        </div>
      </div>
    );
  }

  // Toolbar uses simple type IDs ('desktop', 'laptop', 'tablet', 'mobile').
  // Find the first preset whose id or type matches — type match is the fallback
  // when the toolbar passes a category name instead of a specific preset id.
  const deviceWidth = toolbarState.currentDevice
    ? (DEVICE_PRESETS.find(
        (p) => p.id === toolbarState.currentDevice || p.type === toolbarState.currentDevice,
      )?.width ?? null)
    : null;

  return (
    <div className="flex h-screen flex-col bg-gray-50">
      {/* Toolbar */}
      <DesignerToolbar
        pageMeta={page || undefined}
        hasUnsavedChanges={toolbarState.hasUnsavedChanges}
        canUndo={dslHistory.canUndo}
        canRedo={dslHistory.canRedo}
        zoomLevel={toolbarState.zoomLevel}
        autoSaveEnabled={toolbarState.autoSaveEnabled}
        lastSavedAt={toolbarState.lastSavedAt}
        isSaving={toolbarState.isSaving}
        isPublishing={toolbarState.isPublishing}
        onBack={handleBack}
        onUndo={handleUndo}
        onRedo={handleRedo}
        onClear={toolbarActions.clear}
        onZoomIn={toolbarActions.zoomIn}
        onZoomOut={toolbarActions.zoomOut}
        onZoomReset={toolbarActions.zoomReset}
        onZoomChange={toolbarActions.setZoomLevel}
        onDeviceChange={toolbarActions.setDevice}
        onVersionHistory={toolbarActions.toggleVersionHistory}
        onPreview={toolbarActions.togglePreview}
        onSave={() => toolbarActions.save()}
        onPublish={() => toolbarActions.publish()}
        onSettings={toolbarActions.toggleSettings}
        onShortcutHelp={toolbarActions.toggleShortcuts}
        aiPanelOpen={aiPanelOpen}
        onToggleAiPanel={() => setAiPanelOpen((prev) => !prev)}
        onAiGenerated={handleAiGenerated}
      />

      {/* Designer + AI Panel */}
      <div className="flex flex-1 overflow-hidden">
        <div className="flex-1 overflow-hidden">
          {dsl && (
            <DesignerRouter
              dsl={dsl}
              onDslChange={handleDslChange}
              onSave={handleDslSave}
              modelCode={dsl.modelCode || page?.viewModelCode}
              isCustomApiMode={isCustomApiMode}
              deviceWidth={deviceWidth}
            />
          )}
        </div>

        {/* AI Side Panel */}
        <AiPagePanel
          open={aiPanelOpen}
          onToggle={() => setAiPanelOpen(false)}
          onGenerated={handleAiGenerated}
          pageId={id || ''}
          modelCode={dsl?.modelCode || page?.viewModelCode}
          currentBlocks={(dsl as any)?.blocks}
          schemaVersion={(dsl as any)?.schemaVersion}
        />
      </div>

      {/* Settings Panel */}
      <SettingsPanel
        isOpen={toolbarState.showSettings}
        onClose={toolbarActions.toggleSettings}
        initialSettings={{ page: { enableMultiView: dsl?.enableMultiView ?? false } }}
        onSettingsChange={handleSettingsChange}
      />

      {/* Shortcuts Help Panel */}
      <ShortcutHelpPanel
        isOpen={toolbarState.showShortcuts}
        onClose={toolbarActions.toggleShortcuts}
      />

      {/* Version History Panel */}
      <VersionHistoryPanel
        isOpen={toolbarState.showVersionHistory}
        onClose={toolbarActions.toggleVersionHistory}
        pagePid={id!}
        pageTitle={page?.title}
        onRollbackSuccess={handleRollbackSuccess}
      />

      {/* Preview Modal */}
      {dsl && (
        <PreviewModal
          isOpen={toolbarState.showPreview}
          onClose={toolbarActions.togglePreview}
          schema={dsl}
          pageTitle={page?.title}
        />
      )}
    </div>
  );
}
