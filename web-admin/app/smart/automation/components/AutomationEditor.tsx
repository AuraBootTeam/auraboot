// web-admin/app/smart/automation/components/AutomationEditor.tsx
import React, { useCallback, useState, useEffect, useRef } from 'react';
import { FlowDesigner, type FlowData } from '~/flow-designer-sdk';
import { automationNodes, automationCategoryOrder } from '../nodes';
import { useSmartText } from '~/utils/i18n';
import { useToastContext } from '~/contexts/ToastContext';
import { AutomationDebugger, useDebugSession } from '../debug';
import { DesignerToolbar } from '~/shared/designer/DesignerToolbar';

export interface AutomationEditorProps {
  automationId?: string;
  initialData?: {
    name: string;
    description?: string;
    flowData?: FlowData;
  };
  onSave?: (data: { name: string; description?: string; flowData: FlowData }) => Promise<void>;
  readOnly?: boolean;
  /** Auto-enter debug mode on mount (triggered by ?debug=true URL param) */
  initialDebugMode?: boolean;
}

export function AutomationEditor({
  automationId,
  initialData,
  onSave,
  readOnly = false,
  initialDebugMode = false,
}: AutomationEditorProps) {
  const st = useSmartText();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [name, setName] = useState(initialData?.name || '');
  const [description, setDescription] = useState(initialData?.description || '');
  const [flowData, setFlowData] = useState<FlowData | undefined>(initialData?.flowData);
  const [testRunning, setTestRunning] = useState(false);
  const [isDirty, setIsDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const initialised = useRef(false);

  const { isDebugMode, startDebug } = useDebugSession();

  useEffect(() => {
    if (initialDebugMode && automationId) {
      startDebug(automationId);
    }
  }, [initialDebugMode, automationId, startDebug]);

  useEffect(() => {
    if (initialData) {
      setName(initialData.name);
      setDescription(initialData.description || '');
      setFlowData(initialData.flowData);
      // Reset dirty on initial data load (but not on first mount before user edits)
      if (initialised.current) {
        setIsDirty(false);
      }
      initialised.current = true;
    }
  }, [initialData]);

  const handleNameChange = useCallback((value: string) => {
    setName(value);
    setIsDirty(true);
  }, []);

  const handleDescriptionChange = useCallback((value: string) => {
    setDescription(value);
    setIsDirty(true);
  }, []);

  const handleSave = useCallback(
    async (data: FlowData) => {
      if (!onSave) return;
      setSaving(true);
      try {
        await onSave({
          name,
          description,
          flowData: data,
        });
        setIsDirty(false);
      } finally {
        setSaving(false);
      }
    },
    [name, description, onSave],
  );

  /** Toolbar save: uses the latest flowData snapshot */
  const handleToolbarSave = useCallback(async () => {
    if (!onSave || !flowData) return;
    setSaving(true);
    try {
      await onSave({ name, description, flowData });
      setIsDirty(false);
    } finally {
      setSaving(false);
    }
  }, [onSave, name, description, flowData]);

  const handleChange = useCallback((data: FlowData) => {
    setFlowData(data);
    setIsDirty(true);
  }, []);

  const handleDebug = useCallback(() => {
    if (!automationId) return;
    startDebug(automationId);
  }, [automationId, startDebug]);

  const handleTestRun = useCallback(async () => {
    if (!automationId) return;
    setTestRunning(true);
    try {
      const response = await fetch(`/api/automations/${automationId}/trigger`, {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ context: {} }),
      });
      if (!response.ok) {
        const errData = await response.json().catch(() => null);
        throw new Error(errData?.desc || `Test run failed (${response.status})`);
      }
      const result = await response.json();
      const log = result.data;
      if (log?.status === 'success') {
        showSuccessToast(`Test run completed successfully (${log.durationMs || 0}ms)`);
      } else if (log?.status === 'failed') {
        showErrorToast(`Test run failed: ${log.errorMessage || 'Unknown error'}`);
      } else {
        showSuccessToast('Test run triggered. Check logs for results.');
      }
    } catch (err) {
      showErrorToast(err instanceof Error ? err.message : 'Test run failed');
    } finally {
      setTestRunning(false);
    }
  }, [automationId, showSuccessToast, showErrorToast]);

  const handleExport = useCallback(() => {
    if (!flowData) return;
    const exportData = {
      name,
      description,
      flowConfig: flowData,
      exportedAt: new Date().toISOString(),
    };
    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `automation-${name.replace(/\s+/g, '-').toLowerCase() || 'untitled'}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    showSuccessToast('Automation exported');
  }, [flowData, name, description, showSuccessToast]);

  // Debug mode: show debugger instead of editor
  if (isDebugMode) {
    return <AutomationDebugger />;
  }

  const config = {
    nodeDefinitions: automationNodes,
    categoryOrder: automationCategoryOrder,
    showMinimap: true,
    showControls: true,
  };

  const title = automationId
    ? `${st('$i18n:automation.editor.edit') || 'Edit Automation'}: ${name}`
    : st('$i18n:automation.editor.create') || 'Create Automation';

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* Header toolbar */}
      {!readOnly && (
        <DesignerToolbar
          title={title}
          titleElement={
            <div className="flex items-center gap-3">
              <input
                type="text"
                value={name}
                onChange={(e) => handleNameChange(e.target.value)}
                placeholder={st('$i18n:automation.editor.namePlaceholder') || 'Automation name'}
                className="w-64 rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
              <input
                type="text"
                value={description}
                onChange={(e) => handleDescriptionChange(e.target.value)}
                placeholder={
                  st('$i18n:automation.editor.descriptionPlaceholder') || 'Description (optional)'
                }
                className="w-64 rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            </div>
          }
          isDirty={isDirty}
          isSaving={saving}
          onSave={onSave ? handleToolbarSave : undefined}
          testId="automation-editor-toolbar"
        >
          <button
            onClick={handleExport}
            disabled={!flowData}
            className="shrink-0 rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
            data-testid="btn-export-automation"
          >
            {st('$i18n:automation.editor.export') || 'Export'}
          </button>
          {automationId && (
            <>
              <button
                onClick={handleTestRun}
                disabled={testRunning}
                className="shrink-0 rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
                data-testid="btn-test-run"
              >
                {testRunning
                  ? st('$i18n:automation.editor.testRunning') || 'Running...'
                  : st('$i18n:automation.editor.testRun') || 'Test Run'}
              </button>
              <button
                onClick={handleDebug}
                className="shrink-0 rounded-md bg-gray-800 px-4 py-2 text-sm text-white hover:bg-gray-900"
              >
                {st('$i18n:automation.editor.debug') || 'Debug'}
              </button>
            </>
          )}
        </DesignerToolbar>
      )}

      {/* Flow Designer */}
      <div className="flex-1">
        <FlowDesigner
          config={config}
          initialData={flowData}
          title={title}
          onSave={onSave ? handleSave : undefined}
          onChange={handleChange}
          readOnly={readOnly}
        />
      </div>
    </div>
  );
}

export default AutomationEditor;
