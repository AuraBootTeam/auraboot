/**
 * Report Designer Main Component
 *
 * Three-panel layout: BlockPalette | ReportCanvas | BlockPropertyPanel
 * Features: auto-save, Ctrl+S, beforeunload, undo/redo, preview/export
 */

import React, { useCallback, useEffect, useRef } from 'react';
import { useReportStore } from './store/useReportStore';
import { ReportToolbar } from './components/ReportToolbar';
import { BlockPalette } from './components/BlockPalette';
import { ReportCanvas } from './components/ReportCanvas';
import { BlockPropertyPanel } from './components/BlockPropertyPanel';
import { ReportPageContent } from './renderers/ReportPageContent';
import { reportToHtml } from './services/reportToHtml';
import { fetchReportData } from './services/fetchReportData';
import { reportDesignerService } from './services/reportDesignerService';
import { useVersioning, VersionHistoryPanel } from '~/shared/versioning';
import { pageSchemaVersionService } from '~/shared/versioning/versionService';

const AUTO_SAVE_DELAY = 30000; // 30 seconds

interface ReportDesignerProps {
  reportId?: string;
  initialTitle?: string;
}

export const ReportDesigner: React.FC<ReportDesignerProps> = ({ reportId, initialTitle }) => {
  const {
    report,
    isDirty,
    isSaving,
    isLoading,
    previewMode,
    setPreviewMode,
    loadReportById,
    createReport,
    saveReport,
    reset,
  } = useReportStore();

  const autoSaveTimerRef = useRef<NodeJS.Timeout | null>(null);
  const lastSaveTimeRef = useRef<number>(0);

  // Version history management
  const versioning = useVersioning({
    service: pageSchemaVersionService,
    resourcePid: useReportStore.getState().pageId || undefined,
    onRollbackComplete: () => {
      // Reload report after rollback
      const pid = useReportStore.getState().pageId;
      if (pid) loadReportById(pid);
    },
  });

  // Load or create on mount
  useEffect(() => {
    if (reportId) {
      loadReportById(reportId);
    } else {
      createReport(initialTitle || 'Untitled Report');
    }

    return () => {
      reset();
    };
  }, [reportId, initialTitle, loadReportById, createReport, reset]);

  // Auto-save
  useEffect(() => {
    if (!isDirty || isSaving) {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
        autoSaveTimerRef.current = null;
      }
      return;
    }

    autoSaveTimerRef.current = setTimeout(async () => {
      const timeSinceLastSave = Date.now() - lastSaveTimeRef.current;
      if (timeSinceLastSave < AUTO_SAVE_DELAY || isSaving) return;
      try {
        await saveReport();
        lastSaveTimeRef.current = Date.now();
      } catch (error) {
        console.error('Auto-save failed:', error);
      }
    }, AUTO_SAVE_DELAY);

    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
        autoSaveTimerRef.current = null;
      }
    };
  }, [isDirty, isSaving, saveReport]);

  // Ctrl+S + Undo/Redo
  const handleSave = useCallback(async () => {
    try {
      await saveReport();
      lastSaveTimeRef.current = Date.now();
    } catch (error) {
      console.error('Save failed:', error);
      alert(error instanceof Error ? error.message : 'Save failed');
    }
  }, [saveReport]);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        handleSave();
      }
      if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        useReportStore.getState().undo();
      }
      if ((e.ctrlKey || e.metaKey) && (e.key === 'y' || (e.shiftKey && e.key === 'z'))) {
        e.preventDefault();
        useReportStore.getState().redo();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [handleSave]);

  // beforeunload
  useEffect(() => {
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      if (isDirty) {
        e.preventDefault();
        e.returnValue = '';
      }
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [isDirty]);

  // Preview toggle
  const handlePreview = useCallback(() => {
    setPreviewMode(!previewMode);
  }, [previewMode, setPreviewMode]);

  // Excel Export
  const handleExportExcel = useCallback(async () => {
    const state = useReportStore.getState();
    const pid = state.pageId;
    if (!pid) {
      alert('Please save the report before exporting to Excel.');
      return;
    }
    try {
      const blob = await reportDesignerService.exportExcel(pid);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${report?.title || 'report'}.xlsx`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (error) {
      console.error('Excel export failed:', error);
      alert(error instanceof Error ? error.message : 'Excel export failed');
    }
  }, [report]);

  // PDF Export
  const handleExportPdf = useCallback(async () => {
    if (!report) return;
    try {
      const dataSets = await fetchReportData(report);
      const html = reportToHtml(report, dataSets);
      const blob = await reportDesignerService.exportPdf(
        html,
        report.page.size,
        report.page.orientation,
        `${report.title || 'report'}.pdf`,
      );
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${report.title || 'report'}.pdf`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (error) {
      console.error('PDF export failed:', error);
      alert(error instanceof Error ? error.message : 'PDF export failed');
    }
  }, [report]);

  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-gray-50">
        <div className="flex flex-col items-center gap-4">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
          <p className="text-gray-600">Loading report...</p>
        </div>
      </div>
    );
  }

  // Preview mode: render as runtime
  if (previewMode && report) {
    return (
      <div className="flex h-screen flex-col bg-gray-50">
        <ReportToolbar
          onSave={handleSave}
          onPreview={handlePreview}
          onExportPdf={handleExportPdf}
          onExportExcel={handleExportExcel}
          onToggleVersionHistory={versioning.togglePanel}
          versionCount={versioning.versions.length}
        />
        <div className="flex-1 overflow-auto">
          <PreviewContent report={report} />
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-screen flex-col bg-gray-50">
      <ReportToolbar
        onSave={handleSave}
        onPreview={handlePreview}
        onExportPdf={handleExportPdf}
        onExportExcel={handleExportExcel}
        onToggleVersionHistory={versioning.togglePanel}
        versionCount={versioning.versions.length}
      />
      <div className="flex flex-1 overflow-hidden">
        <BlockPalette />
        <ReportCanvas />
        <BlockPropertyPanel />
      </div>

      {/* Version History Panel */}
      <VersionHistoryPanel
        isOpen={versioning.isOpen}
        onClose={versioning.closePanel}
        versions={versioning.versions}
        isLoading={versioning.isLoading}
        viewingVersionPid={versioning.viewingVersionPid}
        onPreview={versioning.previewVersion}
        onExitPreview={versioning.exitPreview}
        onRollback={versioning.rollbackToVersion}
        isRollingBack={versioning.isRollingBack}
      />
    </div>
  );
};

/**
 * Preview content fetches data and renders runtime view
 */
const PreviewContent: React.FC<{ report: import('./types').ReportDsl }> = ({ report }) => {
  const [dataSets, setDataSets] = React.useState<Record<string, Record<string, unknown>[]>>({});
  const [loading, setLoading] = React.useState(true);

  React.useEffect(() => {
    let mounted = true;
    fetchReportData(report)
      .then((data) => {
        if (mounted) setDataSets(data);
      })
      .catch(console.error)
      .finally(() => {
        if (mounted) setLoading(false);
      });
    return () => {
      mounted = false;
    };
  }, [report]);

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  return (
    <div className="mx-auto my-8 max-w-4xl rounded-lg bg-white p-8 shadow-sm">
      {report.header && (
        <>
          <div className="mb-4">
            {report.header.elements.map((el, i) => (
              <div
                key={i}
                style={{
                  textAlign: el.align || 'left',
                  fontSize: el.style?.fontSize ? `${el.style.fontSize}pt` : undefined,
                  fontWeight: el.style?.fontWeight,
                }}
              >
                {el.type === 'text'
                  ? el.content
                  : el.type === 'date'
                    ? new Date().toLocaleDateString()
                    : el.type === 'page-number'
                      ? 'Page 1'
                      : ''}
              </div>
            ))}
          </div>
          <hr className="mb-4" />
        </>
      )}

      {report.body.map((block) => (
        <div key={block.id} className="mb-6">
          {block.blockType === 'data-table' && (
            <div>
              {block.title && <h3 className="mb-2 text-base font-semibold">{block.title}</h3>}
              <table className="w-full border-collapse border border-gray-300 text-sm">
                {block.showHeader !== false && (
                  <thead>
                    <tr>
                      {block.columns.map((col, i) => (
                        <th
                          key={i}
                          className="border border-gray-300 bg-gray-100 px-3 py-2 font-semibold"
                          style={{ textAlign: col.align || 'left' }}
                        >
                          {col.label || col.field}
                        </th>
                      ))}
                    </tr>
                  </thead>
                )}
                <tbody>
                  {(dataSets[block.dataSource] || []).map((row, rowIdx) => (
                    <tr
                      key={rowIdx}
                      className={block.stripe !== false && rowIdx % 2 === 1 ? 'bg-gray-50' : ''}
                    >
                      {block.columns.map((col, colIdx) => (
                        <td
                          key={colIdx}
                          className="border border-gray-300 px-3 py-1.5"
                          style={{ textAlign: col.align || 'left' }}
                        >
                          {String(row[col.field] ?? '')}
                        </td>
                      ))}
                    </tr>
                  ))}
                  {(dataSets[block.dataSource] || []).length === 0 && (
                    <tr>
                      <td
                        colSpan={block.columns.length}
                        className="border border-gray-300 px-3 py-4 text-center text-gray-400"
                      >
                        No data
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ))}

      {report.footer && (
        <>
          <hr className="mt-4 mb-4" />
          <div>
            {report.footer.elements.map((el, i) => (
              <div
                key={i}
                style={{
                  textAlign: el.align || 'left',
                  fontSize: el.style?.fontSize ? `${el.style.fontSize}pt` : undefined,
                }}
              >
                {el.type === 'text'
                  ? el.content
                  : el.type === 'date'
                    ? new Date().toLocaleDateString()
                    : el.type === 'page-number'
                      ? 'Page 1'
                      : ''}
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
};

export default ReportDesigner;
