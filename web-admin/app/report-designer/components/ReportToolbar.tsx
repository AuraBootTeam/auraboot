/**
 * Report Designer Toolbar
 * Wraps shared DesignerToolbar with report-specific actions: Settings, Preview, Export PDF, Version History
 */

import React, { useState } from 'react';
import { Settings, FileSpreadsheet, History } from 'lucide-react';
import { useReportStore } from '../store/useReportStore';
import { DesignerToolbar } from '~/shared/designer/DesignerToolbar';
import type { PageConfig, PageSize, PageOrientation } from '../types';

interface ReportToolbarProps {
  onSave: () => void;
  onPreview: () => void;
  onExportPdf: () => void;
  onExportExcel?: () => void;
  onToggleVersionHistory?: () => void;
  versionCount?: number;
}

export const ReportToolbar: React.FC<ReportToolbarProps> = ({
  onSave,
  onPreview,
  onExportPdf,
  onExportExcel,
  onToggleVersionHistory,
  versionCount,
}) => {
  const { report, isDirty, isSaving, previewMode, updateTitle, updatePageSettings, canUndo, canRedo, undo, redo } =
    useReportStore();
  const [showSettings, setShowSettings] = useState(false);

  if (!report) return null;

  const titleInput = (
    <input
      type="text"
      value={report.title}
      onChange={(e) => updateTitle(e.target.value)}
      className="w-64 border-none bg-transparent text-lg font-semibold text-gray-900 outline-none focus:ring-0"
      placeholder="Report Title"
    />
  );

  return (
    <>
      <DesignerToolbar
        title={report.title}
        titleElement={titleInput}
        isDirty={isDirty}
        isSaving={isSaving}
        onSave={onSave}
        onUndo={undo}
        onRedo={redo}
        canUndo={canUndo()}
        canRedo={canRedo()}
        testId="report-designer-toolbar"
      >
        <button
          onClick={() => setShowSettings(true)}
          className="flex items-center gap-1 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
          title="Page Settings"
        >
          <Settings className="h-4 w-4" />
          Settings
        </button>

        <button
          onClick={onPreview}
          className={`rounded-md border px-3 py-1.5 text-sm ${
            previewMode
              ? 'border-blue-300 bg-blue-50 text-blue-700'
              : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
          }`}
        >
          {previewMode ? 'Edit' : 'Preview'}
        </button>

        <button
          onClick={onExportPdf}
          className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
        >
          Export PDF
        </button>

        {onExportExcel && (
          <button
            onClick={onExportExcel}
            className="flex items-center gap-1 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
          >
            <FileSpreadsheet className="h-4 w-4" />
            Export Excel
          </button>
        )}

        {onToggleVersionHistory && (
          <button
            onClick={onToggleVersionHistory}
            className="flex items-center gap-1 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
            title="Version History"
          >
            <History className="h-4 w-4" />
            {versionCount != null && versionCount > 0 && (
              <span className="text-xs text-gray-500">({versionCount})</span>
            )}
          </button>
        )}
      </DesignerToolbar>

      {/* Settings Dialog */}
      {showSettings && (
        <PageSettingsDialog
          page={report.page}
          onSave={(settings) => {
            updatePageSettings(settings);
            setShowSettings(false);
          }}
          onClose={() => setShowSettings(false)}
        />
      )}
    </>
  );
};

const PageSettingsDialog: React.FC<{
  page: PageConfig;
  onSave: (settings: Partial<PageConfig>) => void;
  onClose: () => void;
}> = ({ page, onSave, onClose }) => {
  const [size, setSize] = useState<PageSize>(page.size);
  const [orientation, setOrientation] = useState<PageOrientation>(page.orientation);
  const [margin, setMargin] = useState({ ...page.margin });

  return (
    <div
      className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="w-[400px] rounded-lg bg-white shadow-xl">
        <div className="border-b border-gray-200 px-6 py-4">
          <h2 className="text-lg font-semibold text-gray-900">Page Settings</h2>
        </div>
        <div className="space-y-4 p-6">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Page Size</label>
            <select
              value={size}
              onChange={(e) => setSize(e.target.value as PageSize)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              <option value="A4">A4 (210 x 297 mm)</option>
              <option value="A3">A3 (297 x 420 mm)</option>
              <option value="letter">Letter (8.5 x 11 in)</option>
              <option value="legal">Legal (8.5 x 14 in)</option>
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Orientation</label>
            <div className="flex gap-4">
              {(['portrait', 'landscape'] as PageOrientation[]).map((o) => (
                <label key={o} className="flex cursor-pointer items-center gap-2">
                  <input
                    type="radio"
                    name="orientation"
                    value={o}
                    checked={orientation === o}
                    onChange={() => setOrientation(o)}
                    className="text-blue-600"
                  />
                  <span className="text-sm">{o === 'portrait' ? 'Portrait' : 'Landscape'}</span>
                </label>
              ))}
            </div>
          </div>
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">Margins (mm)</label>
            <div className="grid grid-cols-2 gap-3">
              {(['top', 'right', 'bottom', 'left'] as const).map((side) => (
                <div key={side}>
                  <label className="mb-1 block text-xs text-gray-500 capitalize">{side}</label>
                  <input
                    type="number"
                    value={margin[side]}
                    onChange={(e) => setMargin({ ...margin, [side]: Number(e.target.value) })}
                    className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    min={0}
                    max={100}
                  />
                </div>
              ))}
            </div>
          </div>
        </div>
        <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
          <button
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={() => onSave({ size, orientation, margin })}
            className="rounded-md bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700"
          >
            Apply
          </button>
        </div>
      </div>
    </div>
  );
};
