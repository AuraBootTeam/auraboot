/**
 * Report Designer Toolbar
 * Wraps shared DesignerToolbar with report-specific actions: Settings, Preview, Export PDF, Version History
 */

import React, { useState } from 'react';
import { useSmartText } from '~/utils/i18n';
import { Settings, FileJson, FileSpreadsheet, History } from 'lucide-react';
import { useReportStore } from '../store/useReportStore';
import { useReportDocument } from '../state/ReportDocumentProvider';
import { DesignerToolbar } from '~/shared/designer/DesignerToolbar';
import type { PageConfig, PageSize, PageOrientation } from '../types';

interface ReportToolbarProps {
  onSave: () => void;
  onPreview: () => void;
  onExportPdf: () => void;
  onExportExcel?: () => void;
  onExportJson?: () => void;
  onToggleVersionHistory?: () => void;
  versionCount?: number;
  exportReady?: boolean;
  readOnly?: boolean;
  exportAllowed?: boolean;
}

export const ReportToolbar: React.FC<ReportToolbarProps> = ({
  onSave,
  onPreview,
  onExportPdf,
  onExportExcel,
  onExportJson,
  onToggleVersionHistory,
  versionCount,
  exportReady = true,
  readOnly = false,
  exportAllowed = true,
}) => {
  const { isSaving, previewMode, pageId } = useReportStore();
  const text = useSmartText();
  const { report, isDirty, updateTitle, updatePageSettings, canUndo, canRedo, undo, redo } =
    useReportDocument();
  const [showSettings, setShowSettings] = useState(false);

  if (!report) return null;

  const exportDisabled = isDirty || isSaving || !pageId || !exportReady || !exportAllowed;

  if (readOnly)
    return (
      <div className="border-b bg-white px-4 py-3" data-testid="report-reader-toolbar">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="min-w-0 text-lg font-semibold text-gray-900">{report.title}</h1>
          <div className="flex flex-wrap gap-2">
            {exportAllowed &&
              (
                [
                  ['PDF', onExportPdf],
                  ['Excel', onExportExcel],
                  ['JSON', onExportJson],
                ] as const
              ).map(
                ([format, action]) =>
                  action && (
                    <button
                      key={format}
                      onClick={action}
                      disabled={exportDisabled}
                      className="rounded-md border px-3 py-1.5 text-sm disabled:opacity-50"
                    >
                      {text({ zh: `导出 ${format}`, en: `Export ${format}` })}
                    </button>
                  ),
              )}
            {onToggleVersionHistory && (
              <button
                onClick={onToggleVersionHistory}
                className="rounded-md border px-3 py-1.5 text-sm"
              >
                {text({ zh: '版本历史', en: 'Version History' })}
              </button>
            )}
          </div>
        </div>
        <p className="mt-2 text-sm text-gray-600">
          {text({
            zh: '只读报表：可查看数据与历史版本，修改需管理权限。',
            en: 'Read-only report: view data and version history. Editing requires manage permission.',
          })}
        </p>
      </div>
    );

  const titleInput = (
    <input
      type="text"
      value={report.title}
      onChange={(e) => updateTitle(e.target.value)}
      className="w-64 border-none bg-transparent text-lg font-semibold text-gray-900 outline-none focus:ring-0"
      placeholder={text({ zh: '报表标题', en: 'Report Title' })}
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
        canUndo={canUndo}
        canRedo={canRedo}
        testId="report-designer-toolbar"
      >
        <button
          onClick={() => setShowSettings(true)}
          className="flex items-center gap-1 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
          title={text({ zh: '页面设置', en: 'Page Settings' })}
        >
          <Settings className="h-4 w-4" />
          {text({ zh: '设置', en: 'Settings' })}
        </button>

        <button
          onClick={onPreview}
          className={`rounded-md border px-3 py-1.5 text-sm ${
            previewMode
              ? 'border-blue-300 bg-blue-50 text-blue-700'
              : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
          }`}
        >
          {previewMode ? text({ zh: '编辑', en: 'Edit' }) : text({ zh: '预览', en: 'Preview' })}
        </button>

        <button
          onClick={onExportPdf}
          disabled={exportDisabled}
          aria-describedby={exportDisabled ? 'report-export-status' : undefined}
          className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {text({ zh: '导出 PDF', en: 'Export PDF' })}
        </button>

        {onExportExcel && (
          <button
            onClick={onExportExcel}
            disabled={exportDisabled}
            aria-describedby={exportDisabled ? 'report-export-status' : undefined}
            className="flex items-center gap-1 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <FileSpreadsheet className="h-4 w-4" />
            {text({ zh: '导出 Excel', en: 'Export Excel' })}
          </button>
        )}

        {onExportJson && (
          <button
            onClick={onExportJson}
            disabled={exportDisabled}
            aria-describedby={exportDisabled ? 'report-export-status' : undefined}
            className="flex items-center gap-1 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <FileJson className="h-4 w-4" />
            {text({ zh: '导出 JSON', en: 'Export JSON' })}
          </button>
        )}

        {onToggleVersionHistory && (
          <button
            onClick={onToggleVersionHistory}
            className="flex items-center gap-1 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
            title={text({ zh: '版本历史', en: 'Version History' })}
          >
            <History className="h-4 w-4" />
            {versionCount != null && versionCount > 0 && (
              <span className="text-xs text-gray-500">({versionCount})</span>
            )}
          </button>
        )}
      </DesignerToolbar>

      {(isDirty || isSaving || !pageId) && (
        <p
          id="report-export-status"
          role="status"
          className="border-b bg-amber-50 px-4 py-2 text-sm text-amber-900"
        >
          {text({
            zh: '请先保存当前修改，再导出报表。导出使用已保存的报表定义。',
            en: 'Save current changes before exporting. Exports use the saved report definition.',
          })}
        </p>
      )}

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
  const text = useSmartText();
  const [size, setSize] = useState<PageSize>(page.size);
  const [orientation, setOrientation] = useState<PageOrientation>(page.orientation);
  const [margin, setMargin] = useState({ ...page.margin });

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="w-[400px] rounded-lg bg-white shadow-xl">
        <div className="border-b border-gray-200 px-6 py-4">
          <h2 className="text-lg font-semibold text-gray-900">
            {text({ zh: '页面设置', en: 'Page Settings' })}
          </h2>
        </div>
        <div className="space-y-4 p-6">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              {text({ zh: '纸张尺寸', en: 'Page Size' })}
            </label>
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
            <label className="mb-1 block text-sm font-medium text-gray-700">
              {text({ zh: '方向', en: 'Orientation' })}
            </label>
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
                  <span className="text-sm">
                    {o === 'portrait'
                      ? text({ zh: '纵向', en: 'Portrait' })
                      : text({ zh: '横向', en: 'Landscape' })}
                  </span>
                </label>
              ))}
            </div>
          </div>
          <div>
            <label className="mb-2 block text-sm font-medium text-gray-700">
              {text({ zh: '页边距（毫米）', en: 'Margins (mm)' })}
            </label>
            <div className="grid grid-cols-2 gap-3">
              {(['top', 'right', 'bottom', 'left'] as const).map((side) => (
                <div key={side}>
                  <label className="mb-1 block text-xs text-gray-500 capitalize">
                    {text({
                      zh: { top: '上', right: '右', bottom: '下', left: '左' }[side],
                      en: side,
                    })}
                  </label>
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
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {text({ zh: '取消', en: 'Cancel' })}
          </button>
          <button
            onClick={() => onSave({ size, orientation, margin })}
            className="rounded-md bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700"
          >
            {text({ zh: '应用', en: 'Apply' })}
          </button>
        </div>
      </div>
    </div>
  );
};
