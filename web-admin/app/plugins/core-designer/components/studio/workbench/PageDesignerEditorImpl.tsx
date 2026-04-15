/**
 * Page Designer Editor — Implementation
 *
 * Extracted from the route file so it can be React.lazy()-loaded,
 * keeping the entire Studio workbench out of the initial bundle.
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import { useParams, useNavigate } from 'react-router';
import { DesignerRouter } from '~/plugins/core-designer/components/studio/workbench/designers';
import { DesignerToolbar } from '~/plugins/core-designer/components/studio/workbench/components/toolbar';
import { AiPagePanel } from '~/plugins/core-designer/components/studio/components/AiPagePanel';
import type { MergeMode } from '~/plugins/core-designer/components/studio/components/ai-page-prompt';
import {
  SettingsPanel,
  ShortcutHelpPanel,
  VersionHistoryPanel,
  PreviewModal,
} from '~/plugins/core-designer/components/studio/workbench/panels';
import type { AllSettings } from '~/plugins/core-designer/components/studio/workbench/panels/settings/types';
import { useToolbarState } from '~/plugins/core-designer/components/studio/hooks/workbench/useToolbarState';
import { useDslHistory } from '~/plugins/core-designer/components/studio/hooks/useDslHistory';
import { useDesignerShortcuts } from '~/plugins/core-designer/components/studio/hooks/shortcuts/useDesignerShortcuts';
import { pageManagerService } from '~/plugins/core-designer/components/studio/services/page-manager';
import type { PageMeta } from '~/plugins/core-designer/components/studio/services/page-manager';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { DEVICE_PRESETS } from '~/plugins/core-designer/components/studio/workbench/canvas/devices/presets';

/**
 * Page Designer Editor — full implementation with toolbar, canvas, and panels.
 */
export default function PageDesignerEditorImpl() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [meta, setMeta] = useState<PageMeta | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [aiPanelOpen, setAiPanelOpen] = useState(false);
  const { showSuccessToast } = useToastContext();

  // PageSchema V2 state
  const [schema, setSchema] = useState<PageSchema | null>(null);

  // DSL history for undo/redo — initialized with a placeholder, updated once schema loads
  const dslHistory = useDslHistory(
    schema || {
      schemaVersion: 2 as const,
      id: '',
      kind: 'list' as const,
      modelCode: '',
      layout: { type: 'grid' as const, cols: 12 },
      blocks: [],
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
        if (!result) {
          setError('Page not found');
          return;
        }
        setMeta(result.meta);
        setSchema(result.schema);
        dslHistory.pushState(result.schema);  // seed history with real loaded schema
      })
      .catch((err: unknown) => {
        console.error('Failed to load page:', err);
        const message = err instanceof Error ? err.message : 'Failed to load page';
        setError(message);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [id]);

  // Ref for debounced auto-save timer
  const autoSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const latestSchemaRef = useRef<PageSchema | null>(null);

  // Clean up debounce timer on unmount
  useEffect(() => {
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, []);

  const handleSave = useCallback(async () => {
    // Use latestSchemaRef for the most current schema (avoids stale closure from debounced auto-save)
    const currentSchema = latestSchemaRef.current || schema;
    if (!id || !currentSchema) return;

    await pageManagerService.updatePageSchema(id, currentSchema);
  }, [id, schema]);

  const handlePublish = useCallback(async () => {
    if (!id || !schema) return;

    // First save the current schema
    await pageManagerService.updatePageSchema(id, schema);

    // Then publish the page
    const updatedMeta = await pageManagerService.publishPage(id);
    if (updatedMeta) {
      setMeta(updatedMeta);
      showSuccessToast('Page published successfully');
    }
  }, [id, schema, showSuccessToast]);

  // Toolbar state
  const { state: toolbarState, actions: toolbarActions } = useToolbarState({
    pageMeta: meta || undefined,
    onSave: handleSave,
    onPublish: handlePublish,
  });

  // Debounced auto-save — fires 2 seconds after the last schema change.
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

  // Schema change handler — also pushes to history and schedules auto-save
  const handleSchemaChange = useCallback(
    (newSchema: PageSchema) => {
      setSchema(newSchema);
      latestSchemaRef.current = newSchema;
      dslHistory.pushState(newSchema);
      toolbarActions.markUnsaved();
      scheduleAutoSave();
    },
    [toolbarActions, dslHistory, scheduleAutoSave],
  );

  // Undo handler
  const handleUndo = useCallback(() => {
    const prev = dslHistory.undo();
    if (prev) {
      setSchema(prev);
      toolbarActions.markUnsaved();
    }
  }, [dslHistory, toolbarActions]);

  // Redo handler
  const handleRedo = useCallback(() => {
    const next = dslHistory.redo();
    if (next) {
      setSchema(next);
      toolbarActions.markUnsaved();
    }
  }, [dslHistory, toolbarActions]);

  // Settings -> schema sync handler
  const handleSettingsChange = useCallback(
    (settings: AllSettings) => {
      if (!schema) return;
      const updatedSchema: PageSchema = {
        ...schema,
        extension: {
          ...schema.extension,
          enableMultiView: settings.page.enableMultiView,
        },
      };
      handleSchemaChange(updatedSchema);
    },
    [schema, handleSchemaChange],
  );

  // Schema save handler (called by DesignerRouter sub-components on explicit save)
  const handleSchemaSave = useCallback(
    async (newSchema: PageSchema) => {
      if (!id) return;
      await pageManagerService.updatePageSchema(id, newSchema);
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
    const result = await pageManagerService.getPage(id);
    if (result) {
      setMeta(result.meta);
      setSchema(result.schema);
      dslHistory.pushState(result.schema);  // seed history with rolled-back schema
    } else {
      setError('Page not found after rollback');
    }
  }, [id, dslHistory]);

  // Shared AI merge handler — used by both toolbar (legacy dialog) and AI panel
  const handleAiGenerated = useCallback(
    (generated: { kind: PageSchema['kind']; blocks: PageSchema['blocks']; layout: PageSchema['layout']; schemaVersion: 2; mergeMode?: MergeMode }) => {
      const mergeMode: MergeMode = generated.mergeMode || 'replace';
      const existingBlocks = schema?.blocks ?? [];
      const mergedBlocks =
        mergeMode === 'append'
          ? [...existingBlocks, ...generated.blocks]
          : generated.blocks;

      const aiSchema: PageSchema = {
        ...(schema || {}),
        schemaVersion: 2 as const,
        id: meta?.id || '',
        kind: (generated.kind as PageSchema['kind']) || 'list',
        modelCode: schema?.modelCode || meta?.viewModelCode || '',
        blocks: mergedBlocks,
        layout: generated.layout,
      };
      handleSchemaChange(aiSchema);
      showSuccessToast(
        mergeMode === 'append'
          ? `Added ${generated.blocks.length} block(s) to canvas`
          : 'Page layout generated by AI',
      );
    },
    [schema, meta, handleSchemaChange, showSuccessToast],
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
        pageMeta={meta || undefined}
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
          {schema && (
            <DesignerRouter
              dsl={schema}
              onDslChange={handleSchemaChange}
              onSave={handleSchemaSave}
              modelCode={schema.modelCode || meta?.viewModelCode}
              isCustomApiMode={schema.extension?.customApi != null}
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
          modelCode={schema?.modelCode || meta?.viewModelCode}
          currentBlocks={schema?.blocks}
          schemaVersion={schema?.schemaVersion}
        />
      </div>

      {/* Settings Panel */}
      <SettingsPanel
        isOpen={toolbarState.showSettings}
        onClose={toolbarActions.toggleSettings}
        initialSettings={{
          page: { enableMultiView: schema?.extension?.enableMultiView === true },
        }}
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
        pageTitle={meta?.title}
        onRollbackSuccess={handleRollbackSuccess}
      />

      {/* Preview Modal */}
      {schema && (
        <PreviewModal
          isOpen={toolbarState.showPreview}
          onClose={toolbarActions.togglePreview}
          schema={schema}
          pageTitle={meta?.title}
        />
      )}
    </div>
  );
}
