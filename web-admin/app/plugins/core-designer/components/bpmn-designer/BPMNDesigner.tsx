/**
 * BPMN Designer main component
 */

import React, { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import { BPMNPalette } from '~/plugins/core-designer/components/bpmn-designer/components/BPMNPalette';
import { BPMNCanvas } from '~/plugins/core-designer/components/bpmn-designer/components/BPMNCanvas';
import { BPMNPropertyPanel } from '~/plugins/core-designer/components/bpmn-designer/components/BPMNPropertyPanel';
import { BPMNToolbar } from '~/plugins/core-designer/components/bpmn-designer/components/BPMNToolbar';
import { SaveDialog, type ProcessMetadata } from '~/plugins/core-designer/components/bpmn-designer/components/SaveDialog';
import { useVersioning, VersionHistoryPanel } from '~/shared/versioning';
import { bpmnVersionService } from '~/shared/versioning/versionService';
import { useBPMNStore } from '~/plugins/core-designer/components/bpmn-designer/store/useBPMNStore';
import type { BPMNPaletteItem } from '~/plugins/core-designer/components/bpmn-designer/types';
import {
  createProcessDefinition,
  updateProcessDefinition,
  getProcessDefinitionById,
} from '~/plugins/core-designer/components/bpmn-designer/services/bpmnService';

export function BPMNDesigner() {
  const [searchParams] = useSearchParams();
  const { showSuccessToast, showErrorToast, showWarningToast } = useToastContext();
  const { t } = useI18n();
  const {
    processDefinition,
    nodes,
    edges,
    isDirty,
    isSaving,
    isDeploying,
    validationResult,
    viewingVersionId,
    viewMode,
    monitorInstanceId,
    setSaving,
    setDirty,
    validate,
    setProcessDefinition,
    importFromJSON,
    loadVersionData,
    backToCurrent,
    deployProcess,
    setViewMode,
    setMonitorInstanceId,
    fetchInstanceStatus,
    clearInstanceStatus,
    undo,
    redo,
  } = useBPMNStore();

  // Version history management (shared infrastructure)
  const versioning = useVersioning({
    service: bpmnVersionService,
    resourcePid: processDefinition?.key,
    onRollbackComplete: () => {
      // Reload the process definition after rollback
      // BPMN does not currently support rollback, so this is a no-op
    },
  });

  // Bridge: shared version panel preview → BPMN store canvas swap
  const handlePreviewVersion = useCallback(
    (versionPid: string) => {
      // Find the version in the shared hook's list (all have schemaSnapshot from adapter)
      const version = versioning.versions.find((v) => v.pid === versionPid);
      if (!version?.schemaSnapshot) return;

      const snapshot = version.schemaSnapshot as { nodes?: any[]; edges?: any[] };
      loadVersionData(versionPid, snapshot.nodes || [], snapshot.edges || []);
      // Also update the shared hook's tracking state
      versioning.previewVersion(versionPid);
    },
    [versioning, loadVersionData],
  );

  const handleExitPreview = useCallback(() => {
    backToCurrent();
    versioning.exitPreview();
  }, [backToCurrent, versioning]);

  const [processName, setProcessName] = useState(
    processDefinition?.name || t('bpmn.designer.new_process'),
  );
  const [processKey, setProcessKey] = useState(processDefinition?.key || 'process_' + Date.now());
  const [processDescription, setProcessDescription] = useState(
    processDefinition?.description || '',
  );
  const [processCategory, setProcessCategory] = useState(processDefinition?.category || '');
  const [showSaveDialog, setShowSaveDialog] = useState(false);
  const [isLoadingDefinition, setIsLoadingDefinition] = useState(false);
  const fileInputRef = React.useRef<HTMLInputElement>(null);

  // Expose store on window for E2E test seeding (non-production use only).
  // Tests can call (window.__bpmnDesignerStore).getState().addNode(...) to pre-populate a valid
  // process structure without depending on fragile react-flow drag/drop simulation.
  useEffect(() => {
    (window as any).__bpmnDesignerStore = useBPMNStore;
    return () => {
      if ((window as any).__bpmnDesignerStore === useBPMNStore) {
        delete (window as any).__bpmnDesignerStore;
      }
    };
  }, []);

  // Load existing process definition from URL ?pid= parameter
  useEffect(() => {
    const pid = searchParams.get('pid');
    if (!pid) return;
    // Skip if already loaded this pid
    if (processDefinition?.id === pid) return;

    let cancelled = false;
    setIsLoadingDefinition(true);

    getProcessDefinitionById(pid)
      .then((result) => {
        if (cancelled) return;
        if (result.data) {
          setProcessDefinition(result.data);
          setProcessName(result.data.name || '');
          setProcessKey(result.data.key || '');
          setProcessDescription(result.data.description || '');
          setProcessCategory(result.data.category || '');
        } else {
          showErrorToast(t('bpmn.designer.load_failed'));
        }
      })
      .catch((err) => {
        if (cancelled) return;
        console.error('Failed to load process definition:', err);
        showErrorToast(t('bpmn.designer.load_failed'));
      })
      .finally(() => {
        if (!cancelled) setIsLoadingDefinition(false);
      });

    return () => {
      cancelled = true;
    };
  }, [searchParams]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleDragStart = useCallback((event: React.DragEvent, item: BPMNPaletteItem) => {
    event.dataTransfer.setData('application/reactflow', JSON.stringify(item));
    event.dataTransfer.effectAllowed = 'move';
  }, []);

  const handleSave = useCallback(() => {
    // Validate process structure before saving
    const result = validate();
    if (!result.valid) {
      showErrorToast(
        `${t('bpmn.validate.failed')}: ${result.errors
          .map((e) => `- ${t(e.message, e.messageParams)}`)
          .join('; ')}`,
      );
      return;
    }

    // Open save dialog
    setShowSaveDialog(true);
  }, [validate, showErrorToast, t]);

  const handleSaveWithMetadata = async (metadata: ProcessMetadata) => {
    setSaving(true);
    try {
      const definition = {
        ...metadata,
        nodes,
        edges,
        status: 'draft' as const,
      };

      if (processDefinition?.id) {
        const result = await updateProcessDefinition(processDefinition.id, definition);
        if (result.data) {
          setProcessDefinition(result.data);
        }
      } else {
        const result = await createProcessDefinition(definition);
        if (result.data) {
          setProcessDefinition(result.data);
        }
      }

      // Update local state from save dialog
      setProcessName(metadata.name);
      setProcessKey(metadata.key);
      if (metadata.description !== undefined) setProcessDescription(metadata.description || '');
      if (metadata.category !== undefined) setProcessCategory(metadata.category || '');

      setDirty(false);
      setShowSaveDialog(false);
      showSuccessToast(t('bpmn.designer.save_success'));
    } catch (error) {
      console.error('Save failed:', error);
      showErrorToast(t('bpmn.designer.save_failed'));
      throw error; // Keep dialog open on failure
    } finally {
      setSaving(false);
    }
  };

  const handleValidate = () => {
    const result = validate();
    if (result.valid) {
      showSuccessToast(t('bpmn.validate.passed'));
    } else {
      const errorMsg = result.errors
        .map((e) => `[${e.type.toUpperCase()}] ${t(e.message, e.messageParams)}`)
        .join('; ');
      showErrorToast(`${t('bpmn.validate.result')}: ${errorMsg}`);
    }
  };

  const handleExport = () => {
    const data = {
      ...processDefinition,
      nodes,
      edges,
      name: processName,
      key: processKey,
    };
    const blob = new Blob([JSON.stringify(data, null, 2)], {
      type: 'application/json',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${processKey}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleDeploy = async () => {
    if (!processDefinition?.id) {
      showWarningToast(t('bpmn.designer.save_first'));
      return;
    }
    if (isDirty) {
      showWarningToast(t('bpmn.designer.save_first'));
      return;
    }
    try {
      await deployProcess();
      showSuccessToast(t('bpmn.designer.deploy_success'));
    } catch (error) {
      console.error('Deploy failed:', error);
      showErrorToast(t('bpmn.designer.deploy_failed'));
    }
  };

  const handleMonitorToggle = () => {
    if (viewMode === 'design') {
      setViewMode('monitor');
    } else {
      setViewMode('design');
    }
  };

  const handleFetchInstanceStatus = () => {
    if (monitorInstanceId.trim()) {
      fetchInstanceStatus(monitorInstanceId.trim());
    }
  };

  // Keyboard shortcuts for undo/redo/save
  useEffect(() => {
    if (viewingVersionId) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      const isMod = e.metaKey || e.ctrlKey;

      if (isMod && e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        undo();
        return;
      }

      if (isMod && (e.key === 'y' || (e.key === 'z' && e.shiftKey))) {
        e.preventDefault();
        redo();
        return;
      }

      if (isMod && e.key === 's') {
        e.preventDefault();
        if (isDirty) handleSave();
        return;
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [viewingVersionId, undo, redo, isDirty, handleSave]);

  const handleImportClick = () => {
    fileInputRef.current?.click();
  };

  const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;

    // Check file type
    if (!file.name.endsWith('.json')) {
      showWarningToast(t('bpmn.designer.import_json_only'));
      return;
    }

    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const content = e.target?.result as string;
        const json = JSON.parse(content);

        // Validate imported JSON has required BPMN structure
        if (!json || typeof json !== 'object') {
          showErrorToast('Invalid JSON: file must contain a JSON object');
          return;
        }
        if (!json.nodes && !json.edges && !json.name && !json.key) {
          showErrorToast('Invalid BPMN JSON: missing required fields (nodes, edges, name, or key)');
          return;
        }

        // Import data
        importFromJSON(json);

        // Update local state from imported data
        if (json.name) setProcessName(json.name);
        if (json.key) setProcessKey(json.key);
        if (json.description) setProcessDescription(json.description);
        if (json.category) setProcessCategory(json.category);

        showSuccessToast(t('bpmn.designer.import_success'));
      } catch (error) {
        console.error('Import failed:', error);
        showErrorToast(t('bpmn.designer.import_failed'));
      }
    };

    reader.onerror = () => {
      showErrorToast(t('bpmn.designer.file_read_error'));
    };

    reader.readAsText(file);

    // Reset input to allow re-selecting the same file
    event.target.value = '';
  };

  if (isLoadingDefinition) {
    return (
      <div className="flex h-screen items-center justify-center bg-gray-50">
        <div className="flex flex-col items-center gap-3">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-300 border-t-blue-600" />
          <div className="text-sm text-gray-500">{t('common.loading')}</div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-screen flex-col bg-gray-50">
      {/* Toolbar */}
      <BPMNToolbar
        processName={processName}
        processKey={processKey}
        onProcessNameChange={(v) => {
          setProcessName(v);
          setDirty(true);
        }}
        onProcessKeyChange={(v) => {
          if (!processDefinition?.id) setProcessKey(v);
        }}
        onSave={handleSave}
        onValidate={handleValidate}
        onImport={handleImportClick}
        onExport={handleExport}
        onDeploy={handleDeploy}
        onMonitorToggle={handleMonitorToggle}
        onToggleVersionHistory={versioning.togglePanel}
        versionCount={versioning.versions.length}
        isVersionPanelOpen={versioning.isOpen}
      />
      <input
        ref={fileInputRef}
        type="file"
        accept=".json"
        onChange={handleFileChange}
        className="hidden"
      />

      {/* Validation error banner */}
      {validationResult && validationResult.errors.length > 0 && (
        <div className="border-b border-red-200 bg-red-50 px-4 py-2">
          <div className="flex items-start gap-2">
            <svg className="mt-0.5 h-5 w-5 text-red-600" fill="currentColor" viewBox="0 0 20 20">
              <path
                fillRule="evenodd"
                d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                clipRule="evenodd"
              />
            </svg>
            <div className="flex-1">
              <p className="text-sm font-medium text-red-800">
                {t('bpmn.validate.errors_title', { count: String(validationResult.errors.length) })}
              </p>
              <ul className="mt-1 list-inside list-disc text-xs text-red-700">
                {validationResult.errors.slice(0, 3).map((error, index) => (
                  <li key={index}>{t(error.message, error.messageParams)}</li>
                ))}
              </ul>
            </div>
          </div>
        </div>
      )}

      {/* Version viewing banner */}
      {viewingVersionId && (
        <div className="flex items-center justify-between border-b border-yellow-300 bg-yellow-50 px-4 py-2">
          <div className="flex items-center gap-2">
            <svg className="h-5 w-5 text-yellow-600" fill="currentColor" viewBox="0 0 20 20">
              <path
                fillRule="evenodd"
                d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
                clipRule="evenodd"
              />
            </svg>
            <span className="text-sm font-medium text-yellow-800">
              Viewing historical version (read-only)
            </span>
          </div>
          <button
            type="button"
            onClick={handleExitPreview}
            className="rounded-md bg-blue-100 px-3 py-1 text-sm font-medium text-blue-700 transition-colors hover:bg-blue-200"
          >
            Back to Current
          </button>
        </div>
      )}

      {/* Monitor mode instance input bar */}
      {viewMode === 'monitor' && (
        <div className="flex items-center gap-3 border-b border-indigo-200 bg-indigo-50 px-4 py-2">
          <svg
            className="h-5 w-5 text-indigo-600"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"
            />
          </svg>
          <span className="text-sm font-medium text-indigo-800">
            {t('bpmn.designer.monitor_mode')}
          </span>
          <input
            type="text"
            value={monitorInstanceId}
            onChange={(e) => setMonitorInstanceId(e.target.value)}
            placeholder={t('bpmn.designer.instance_id_placeholder')}
            className="max-w-md flex-1 rounded-md border border-indigo-300 bg-white px-3 py-1.5 text-sm focus:ring-2 focus:ring-indigo-400 focus:outline-none"
          />
          <button
            onClick={handleFetchInstanceStatus}
            disabled={!monitorInstanceId.trim()}
            className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {t('bpmn.designer.query_status')}
          </button>
          <button
            onClick={clearInstanceStatus}
            className="rounded-md bg-indigo-100 px-3 py-1.5 text-sm font-medium text-indigo-700 hover:bg-indigo-200"
          >
            {t('bpmn.designer.clear')}
          </button>
        </div>
      )}

      {/* Main content area */}
      <div className="relative flex flex-1 overflow-hidden">
        {/* Palette */}
        <BPMNPalette onDragStart={handleDragStart} />

        {/* Canvas */}
        <BPMNCanvas />

        {/* Property panel */}
        <BPMNPropertyPanel
          processMetadata={{
            name: processName,
            processKey,
            description: processDescription,
            category: processCategory,
            isExisting: !!processDefinition?.id,
            onNameChange: (v) => {
              setProcessName(v);
              setDirty(true);
            },
            onDescriptionChange: (v) => {
              setProcessDescription(v);
              setDirty(true);
            },
            onCategoryChange: (v) => {
              setProcessCategory(v);
              setDirty(true);
            },
          }}
        />
      </div>

      {/* Version History Panel (shared) */}
      <VersionHistoryPanel
        isOpen={versioning.isOpen}
        onClose={versioning.closePanel}
        versions={versioning.versions}
        isLoading={versioning.isLoading}
        viewingVersionPid={versioning.viewingVersionPid}
        onPreview={handlePreviewVersion}
        onExitPreview={handleExitPreview}
        onRollback={versioning.rollbackToVersion}
        isRollingBack={versioning.isRollingBack}
      />

      {/* Save dialog */}
      <SaveDialog
        isOpen={showSaveDialog}
        onClose={() => setShowSaveDialog(false)}
        onSave={handleSaveWithMetadata}
        initialData={{
          id: processDefinition?.id,
          name: processName,
          key: processKey,
          version: processDefinition?.version,
          versionName: processDefinition?.versionName,
          description: processDescription,
          category: processCategory,
        }}
        isNew={!processDefinition?.id}
      />
    </div>
  );
}
