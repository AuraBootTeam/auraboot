/**
 * BPMN Designer Toolbar
 *
 * Wraps the shared DesignerToolbar with BPMN-specific actions:
 * validate, import/export, version history, deploy, and monitor mode toggle.
 */

import React from 'react';
import { DesignerToolbar } from '~/shared/designer';
import { ProcessStatusBadge } from '~/bpmn-designer/components/ProcessStatusBadge';
import { useBPMNStore } from '~/bpmn-designer/store/useBPMNStore';
import { useI18n } from '~/contexts/I18nContext';

export interface BPMNToolbarProps {
  processName: string;
  processKey: string;
  onProcessNameChange: (value: string) => void;
  onProcessKeyChange: (value: string) => void;
  onSave: () => void;
  onValidate: () => void;
  onImport: () => void;
  onExport: () => void;
  onDeploy: () => void;
  onMonitorToggle: () => void;
  onToggleVersionHistory?: () => void;
  versionCount?: number;
  isVersionPanelOpen?: boolean;
}

export function BPMNToolbar({
  processName,
  processKey,
  onProcessNameChange,
  onProcessKeyChange,
  onSave,
  onValidate,
  onImport,
  onExport,
  onDeploy,
  onMonitorToggle,
  onToggleVersionHistory,
  versionCount: _versionCount,
  isVersionPanelOpen: isVersionPanelOpenProp,
}: BPMNToolbarProps) {
  const { t } = useI18n();
  const {
    processDefinition,
    isDirty,
    isSaving,
    isDeploying,
    viewingVersionId,
    viewMode,
    canUndo,
    canRedo,
    undo,
    redo,
  } = useBPMNStore();

  const isVersionPanelOpen = isVersionPanelOpenProp ?? false;

  const isReadOnly = !!viewingVersionId;

  // Build a title element with editable name + key inputs + status badge
  const titleElement = (
    <div className="flex items-center gap-2">
      <h1 data-testid="bpmn-page-title" className="text-xl font-semibold text-gray-900">
        {t('bpmn.designer.title')}
      </h1>
      <input
        type="text"
        value={processName}
        onChange={(e) => onProcessNameChange(e.target.value)}
        className="rounded-md border border-gray-300 px-3 py-1.5 text-sm"
        placeholder={t('bpmn.designer.process_name')}
        data-testid="bpmn-field-name"
      />
      <input
        type="text"
        value={processKey}
        onChange={(e) => onProcessKeyChange(e.target.value)}
        readOnly={!!processDefinition?.id}
        className={`rounded-md border border-gray-300 px-3 py-1.5 font-mono text-sm ${
          processDefinition?.id ? 'bg-gray-50 text-gray-500' : ''
        }`}
        placeholder={t('bpmn.designer.process_key')}
        data-testid="bpmn-field-key"
      />
      <ProcessStatusBadge status={processDefinition?.status} />
    </div>
  );

  // BPMN-specific action buttons rendered as children of DesignerToolbar
  const bpmnActions = (
    <>
      <button
        onClick={onValidate}
        className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        disabled={isReadOnly}
        data-testid="bpmn-btn-validate"
      >
        {t('bpmn.designer.validate')}
      </button>
      <button
        onClick={onImport}
        className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        disabled={isReadOnly}
        data-testid="bpmn-btn-import"
      >
        {t('bpmn.designer.import')}
      </button>
      <button
        onClick={onExport}
        className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        data-testid="bpmn-btn-export"
      >
        {t('bpmn.designer.export')}
      </button>
      <button
        onClick={onToggleVersionHistory}
        className={`rounded-md border px-4 py-2 text-sm font-medium transition-colors ${
          isVersionPanelOpen
            ? 'border-blue-300 bg-blue-50 text-blue-700'
            : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
        }`}
        data-testid="bpmn-btn-version-history"
      >
        {t('bpmn.designer.version_history')}
      </button>
      {/* Deploy button (after Save, which is rendered by DesignerToolbar) */}
      <button
        onClick={onDeploy}
        disabled={isDeploying || isDirty || !processDefinition?.id || isReadOnly}
        className="rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
        data-testid="bpmn-btn-deploy"
      >
        {isDeploying ? t('bpmn.designer.deploying') : t('bpmn.designer.deploy')}
      </button>
      <div className="h-6 w-px bg-gray-300" />
      <button
        onClick={onMonitorToggle}
        className={`rounded-md border px-4 py-2 text-sm font-medium transition-colors ${
          viewMode === 'monitor'
            ? 'border-indigo-300 bg-indigo-50 text-indigo-700'
            : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
        }`}
      >
        {viewMode === 'monitor' ? t('bpmn.designer.exit_monitor') : t('bpmn.designer.monitor')}
      </button>
    </>
  );

  return (
    <DesignerToolbar
      title={t('bpmn.designer.title')}
      titleElement={titleElement}
      isDirty={isDirty}
      isSaving={isSaving}
      onUndo={isReadOnly ? undefined : undo}
      onRedo={isReadOnly ? undefined : redo}
      canUndo={canUndo()}
      canRedo={canRedo()}
      onSave={onSave}
      saveLabel={isSaving ? t('bpmn.designer.saving') : t('bpmn.designer.save')}
      testId="bpmn-toolbar"
    >
      {bpmnActions}
    </DesignerToolbar>
  );
}
