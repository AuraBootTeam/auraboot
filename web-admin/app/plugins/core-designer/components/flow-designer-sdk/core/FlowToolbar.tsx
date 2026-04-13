// web-admin/app/flow-designer-sdk/core/FlowToolbar.tsx
import React, { useState } from 'react';
import { useSmartText } from '~/utils/i18n';
import { useFlowStore } from '../store/useFlowStore';
import { useToastContext } from '~/contexts/ToastContext';
import { AutoSaveIndicator, type SaveStatus } from '~/shared/versioning/AutoSaveIndicator';
import { DesignerToolbar } from '~/shared/designer/DesignerToolbar';

export interface FlowToolbarProps {
  title?: string;
  onSave?: () => Promise<void>;
  onValidate?: () => void;
  readOnly?: boolean;
  className?: string;
  saveStatus?: SaveStatus;
}

export function FlowToolbar({
  title,
  onSave,
  onValidate,
  readOnly,
  className,
  saveStatus,
}: FlowToolbarProps) {
  const st = useSmartText();
  const { isDirty, validationResult, exportData, importData, undo, redo, canUndo, canRedo } =
    useFlowStore();
  const { showErrorToast } = useToastContext();
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    if (!onSave) return;
    setSaving(true);
    try {
      await onSave();
    } finally {
      setSaving(false);
    }
  };

  const handleExport = () => {
    const data = exportData();
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'flow.json';
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleImport = () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json';
    input.onchange = (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = (event) => {
        try {
          const data = JSON.parse(event.target?.result as string);
          if (data.nodes && data.edges) {
            importData(data);
          } else {
            showErrorToast(st('$i18n:flow.toolbar.importError') || 'Invalid file format');
          }
        } catch (err) {
          showErrorToast(st('$i18n:flow.toolbar.importError') || 'Failed to import file');
        }
      };
      reader.readAsText(file);
    };
    input.click();
  };

  return (
    <DesignerToolbar
      title={title || st('$i18n:flow.toolbar.title') || 'Flow Designer'}
      isDirty={isDirty}
      isSaving={saving}
      onUndo={readOnly ? undefined : undo}
      onRedo={readOnly ? undefined : redo}
      canUndo={!readOnly && canUndo()}
      canRedo={!readOnly && canRedo()}
      onSave={onSave && !readOnly ? handleSave : undefined}
      saveLabel={
        saving
          ? st('$i18n:flow.toolbar.saving') || 'Saving...'
          : st('$i18n:flow.toolbar.save') || 'Save'
      }
      className={className}
    >
      {/* AutoSave indicator (replaces dirty badge when present) */}
      {saveStatus && <AutoSaveIndicator status={saveStatus} />}

      {/* Validation errors badge */}
      {validationResult && !validationResult.valid && (
        <span className="rounded bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700">
          {st('$i18n:flow.toolbar.hasErrors') || 'Errors'} ({validationResult.errors.length})
        </span>
      )}

      {onValidate && (
        <button
          type="button"
          onClick={onValidate}
          className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50"
        >
          {st('$i18n:flow.toolbar.validate') || 'Validate'}
        </button>
      )}
      <button
        type="button"
        onClick={handleImport}
        disabled={readOnly}
        className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {st('$i18n:flow.toolbar.import') || 'Import'}
      </button>
      <button
        type="button"
        onClick={handleExport}
        className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50"
      >
        {st('$i18n:flow.toolbar.export') || 'Export'}
      </button>
    </DesignerToolbar>
  );
}

export default FlowToolbar;
