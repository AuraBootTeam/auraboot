/**
 * Dashboard Designer Toolbar
 * Wraps shared DesignerToolbar with dashboard-specific actions:
 * Validate, Settings, Version History, PDF/Excel Export, Publish/Unpublish
 */

import React from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import { useDashboardStore } from '../store/useDashboardStore';
import { ExportPdfButton } from '~/smart/components/data-tools/ExportPdfButton';
import { DashboardExportExcel } from './DashboardExportExcel';
import { DesignerToolbar as SharedDesignerToolbar } from '~/shared/designer/DesignerToolbar';
import { Clock, Maximize2 } from 'lucide-react';

interface DashboardToolbarProps {
  onSave?: () => void;
  onPublish?: () => void;
  onUnpublish?: () => void;
  onSettings?: () => void;
  onToggleVersionHistory?: () => void;
  versionCount?: number;
  /** Ref to the canvas element for PDF export */
  canvasRef?: React.RefObject<HTMLDivElement | null>;
  /** Callback to enter big-screen presentation mode */
  onPresentation?: () => void;
}

export const DesignerToolbar: React.FC<DashboardToolbarProps> = ({
  onSave,
  onPublish,
  onUnpublish,
  onSettings,
  onToggleVersionHistory,
  versionCount,
  canvasRef,
  onPresentation,
}) => {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { dashboard, isDirty, isSaving, canUndo, canRedo, undo, redo, validate } =
    useDashboardStore();

  const handleValidate = () => {
    const result = validate();
    if (result.valid) {
      showSuccessToast('Validation passed');
    } else {
      const errorMsg = result.errors
        .map((e) => `[${e.type.toUpperCase()}] ${e.message}`)
        .join('; ');
      showErrorToast(`Validation: ${errorMsg}`);
    }
  };

  const statusText =
    dashboard?.status === 'published'
      ? '已发布'
      : dashboard?.status === 'draft'
        ? '草稿'
        : undefined;

  return (
    <SharedDesignerToolbar
      testId="designer-toolbar"
      title="Dashboard Designer"
      subtitle={dashboard?.title || 'Untitled Dashboard'}
      status={statusText}
      isDirty={isDirty}
      isSaving={isSaving}
      onUndo={undo}
      onRedo={redo}
      canUndo={canUndo()}
      canRedo={canRedo()}
      onSave={onSave}
    >
      {/* Validate */}
      <button
        onClick={handleValidate}
        className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        data-testid="toolbar-btn-validate"
      >
        Validate
      </button>

      {/* Settings */}
      <button
        onClick={onSettings}
        className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        data-testid="toolbar-btn-settings"
      >
        Settings
      </button>

      {/* Presentation Mode */}
      {dashboard?.pid && (
        <button
          onClick={onPresentation}
          title="Presentation Mode"
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
          data-testid="toolbar-btn-presentation"
        >
          <Maximize2 className="h-4 w-4" />
        </button>
      )}

      {/* Version History */}
      {dashboard?.pid && (
        <button
          onClick={onToggleVersionHistory}
          className="flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
          title="Version History"
        >
          <Clock className="h-4 w-4" />
          History
          {versionCount != null && versionCount > 0 && (
            <span className="text-xs text-gray-400">({versionCount})</span>
          )}
        </button>
      )}

      {/* PDF & Excel Export */}
      {canvasRef && dashboard?.pid && (
        <>
          <ExportPdfButton
            targetRef={canvasRef}
            fileName={dashboard?.title || 'dashboard'}
            orientation="landscape"
          />
          <DashboardExportExcel
            widgets={dashboard?.widgets || []}
            fileName={dashboard?.title || 'dashboard'}
          />
        </>
      )}

      {/* Publish / Unpublish */}
      {dashboard?.status === 'draft' ? (
        <button
          onClick={onPublish}
          disabled={isDirty || !dashboard?.pid}
          className="rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
          data-testid="toolbar-btn-publish"
        >
          Publish
        </button>
      ) : (
        <button
          onClick={onUnpublish}
          disabled={!dashboard?.pid}
          className="rounded-md border border-yellow-300 bg-yellow-100 px-4 py-2 text-sm font-medium text-yellow-700 hover:bg-yellow-200 disabled:cursor-not-allowed disabled:opacity-50"
          data-testid="toolbar-btn-unpublish"
        >
          Unpublish
        </button>
      )}
    </SharedDesignerToolbar>
  );
};

export default DesignerToolbar;
