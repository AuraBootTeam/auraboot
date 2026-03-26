/**
 * Page Designer - Editor Route
 *
 * Page editor for a specific page with full toolbar integration.
 * Uses DSL V4 AreasDesigner for page editing.
 *
 * @since 4.0.0
 */

import React, { useCallback, useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router';
import { DesignerRouter } from '~/studio/workbench/designers';
import { DesignerToolbar } from '~/studio/workbench/components/toolbar';
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

/**
 * Build default DSL V4 schema for a page
 */
function buildDefaultDslV4(page: PageMeta): DslV4Schema {
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
 * Page Designer Editor
 */
export default function PageDesignerEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [page, setPage] = useState<PageMeta | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isCustomApiMode, setIsCustomApiMode] = useState(false);

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
          const detectedCustomApi = !!(
            rawSchema &&
            rawSchema.dataSource?.type === 'api'
          );
          setIsCustomApiMode(detectedCustomApi);

          // Load DSL V4 or create default
          const rawDsl = result.dslSchema as unknown as DslV4Schema | null;
          if (rawDsl && rawDsl.kind && rawDsl.areas) {
            setDsl(rawDsl);
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

  const handleSave = useCallback(async () => {
    if (!id || !dsl) return;

    // Count blocks
    const blockCount = Object.values(dsl.areas || {}).reduce(
      (sum, area) => sum + (area.blocks?.length || 0),
      0,
    );
    await pageManagerService.updatePageSchema(
      id,
      dsl as unknown as Record<string, unknown>,
      blockCount,
    );
  }, [id, dsl]);

  const handlePublish = useCallback(async () => {
    if (!id || !dsl) return;

    // First save the current DSL
    const blockCount = Object.values(dsl.areas || {}).reduce(
      (sum, area) => sum + (area.blocks?.length || 0),
      0,
    );
    await pageManagerService.updatePageSchema(
      id,
      dsl as unknown as Record<string, unknown>,
      blockCount,
    );

    // Then publish the page
    const updatedPage = await pageManagerService.publishPage(id);
    if (updatedPage) {
      setPage(updatedPage);
    }
  }, [id, dsl]);

  // Toolbar state
  const { state: toolbarState, actions: toolbarActions } = useToolbarState({
    pageMeta: page || undefined,
    onSave: handleSave,
    onPublish: handlePublish,
  });

  // DSL change handler — also pushes to history
  const handleDslChange = useCallback(
    (newDsl: DslV4Schema) => {
      setDsl(newDsl);
      dslHistory.pushState(newDsl);
      toolbarActions.markUnsaved();
    },
    [toolbarActions, dslHistory],
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

  // Settings → DSL sync handler
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
      const blockCount = Object.values(newDsl.areas || {}).reduce(
        (sum, area) => sum + (area.blocks?.length || 0),
        0,
      );
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
      />

      {/* Designer */}
      <div className="flex-1 overflow-hidden">
        {dsl && (
          <DesignerRouter
            dsl={dsl}
            onDslChange={handleDslChange}
            onSave={handleDslSave}
            modelCode={dsl.modelCode || page?.viewModelCode}
            isCustomApiMode={isCustomApiMode}
          />
        )}
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
