import { ReportBandBlock } from './blocks/ReportBandBlock';
import { DesignerWorkspace } from '~/shared/designer/DesignerWorkspace';
import { useSmartText } from '~/utils/i18n';
import { useToastContext } from '~/contexts/ToastContext';
import { usePermission } from '~/contexts/AuthContext';
/**
 * Report Designer Main Component
 *
 * Three-panel layout: BlockPalette | ReportCanvas | BlockPropertyPanel
 * Features: auto-save, Ctrl+S, beforeunload, undo/redo, preview/export
 *
 * B1 Phase 2b (report-canvas swap): the report DOCUMENT + SELECTION + UNDO/REDO
 * HISTORY now live in the unified-designer kernels via `ReportDocumentProvider`.
 * `ReportDesigner` is a thin outer shell that mounts the provider; all context
 * consumption (and the load/save orchestration that wires the fetched ReportDsl
 * into the document kernel) lives in `ReportDesignerInner`.
 */

import React, { useCallback, useEffect, useRef } from 'react';
import { useReportStore } from './store/useReportStore';
import {
  ReportDocumentProvider,
  useReportDocument,
  ensurePageConfig,
} from './state/ReportDocumentProvider';
import { ReportToolbar } from './components/ReportToolbar';
import { ReportTemplateActions } from './components/ReportTemplateActions';
import { BlockPalette } from './components/BlockPalette';
import { ReportTableBlock } from './blocks/ReportTableBlock';
import { ReportGroupedTableBlock } from './blocks/ReportGroupedTableBlock';
import { ReportStatCardBlock } from './blocks/ReportStatCardBlock';
import { ReportRichTextBlock } from './blocks/ReportRichTextBlock';
import { ReportCrossTabBlock } from './blocks/ReportCrossTabBlock';
import { ReportChartBlock } from './blocks/ReportChartBlock';
import { ReportBarcodeBlock } from './blocks/ReportBarcodeBlock';
import { ReportWatermarkBlock } from './blocks/ReportWatermarkBlock';
import { ReportCanvas } from './components/ReportCanvas';
import { BlockPropertyPanel } from './components/BlockPropertyPanel';
import { useReportQuery, type ReportQuery } from './services/useReportQuery';
import { ReportQueryControls } from './components/ReportQueryControls';
import { reportDesignerService } from './services/reportDesignerService';
import { createEmptyReport, type ReportDsl } from './types';
import { useVersioning, VersionHistoryPanel } from '~/shared/versioning';
import { reportVersionService } from '~/shared/versioning/versionService';

const AUTO_SAVE_DELAY = 30000; // 30 seconds

interface ReportDesignerProps {
  reportId?: string;
  initialTitle?: string;
  onSaveComplete?: (reportPid: string) => void;
}

const ReportDesignerInner: React.FC<ReportDesignerProps> = ({ reportId, initialTitle, onSaveComplete }) => {
  const text = useSmartText();
  const { showErrorToast } = useToastContext();
  const notifyExportFailure = useCallback(() => {
    showErrorToast(
      text({
        zh: '导出未完成。请检查数据源和访问权限后重试。',
        en: 'Export could not be completed. Check the data source and access permissions, then retry.',
      }),
    );
  }, [text, showErrorToast]);
  const canManage = usePermission('report.definition.manage');
  const canExport = usePermission('report.export.execute');
  const [loadFailed, setLoadFailed] = React.useState(false);
  const { report, selectedBlockId, isDirty, loadDocument, markSaved, setDirty, undo, redo } = useReportDocument();
  const {
    isSaving,
    isLoading,
    previewMode,
    pageId,
    setPreviewMode,
    setPageId,
    setSaving,
    setLoading,
    reset,
  } = useReportStore();

  const [loadedTarget, setLoadedTarget] = React.useState<string | null>(null);
  const loadRevisionRef = useRef(0);
  const targetReady = loadedTarget === (reportId || '');

  const viewing = previewMode || !canManage;

  const autoSaveTimerRef = useRef<NodeJS.Timeout | null>(null);
  const lastSaveTimeRef = useRef<number>(0);

  // ── Load / save orchestration (service calls + document-kernel wiring) ──────
  const loadReportById = useCallback(
    async (pid: string) => {
      const revision = ++loadRevisionRef.current;
      setLoading(true);
      setLoadFailed(false);
      try {
        const result = await reportDesignerService.loadByPid(pid);
        if (revision !== loadRevisionRef.current) return;
        loadDocument(result.dsl);
        setPageId(result.pid);
        setLoadedTarget(pid);
        setLoading(false);
      } catch {
        if (revision !== loadRevisionRef.current) return;
        setLoading(false);
        setLoadFailed(true);
      }
    },
    [loadDocument, setPageId, setLoading],
  );

  const createReport = useCallback(
    (title: string) => {
      ++loadRevisionRef.current;
      setLoadFailed(false);
      loadDocument(createEmptyReport(title));
      setLoadedTarget('');
      setLoading(false);
      setPageId(null);
      // A freshly created (unsaved) report is dirty — preserves prior store behavior.
      setDirty(true);
    },
    [loadDocument, setPageId, setDirty, setLoading],
  );

  const saveReport = useCallback(async () => {
    if (!canManage) throw new Error('Report is read only');
    if (!targetReady) throw new Error('Report is still loading');
    if (!report) throw new Error('No report to save');
    setSaving(true);
    try {
      const pid = await reportDesignerService.save(report, pageId || undefined);
      setPageId(pid);
      markSaved();
      setSaving(false);
      onSaveComplete?.(pid);
      return pid;
    } catch (error) {
      setSaving(false);
      throw error;
    }
  }, [report, pageId, setSaving, setPageId, markSaved, canManage, onSaveComplete, targetReady]);

  // Version history management
  const versioning = useVersioning({
    service: reportVersionService,
    resourcePid: pageId || undefined,
    onRollbackComplete: () => {
      // Reload report after rollback
      if (pageId) loadReportById(pageId);
    },
  });

  const previewQuery = useReportQuery(report, viewing && !versioning.viewingVersionPid);

  // Load or create on mount
  useEffect(() => {
    if (reportId) {
      loadReportById(reportId);
    } else {
      createReport(initialTitle || text({ zh: '未命名报表', en: 'Untitled Report' }));
    }

    return () => {
      ++loadRevisionRef.current;
      reset();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reportId, initialTitle]);

  // Auto-save
  useEffect(() => {
    if (!canManage || !targetReady || !isDirty || isSaving || versioning.viewingVersionPid) {
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
  }, [isDirty, isSaving, saveReport, versioning.viewingVersionPid, canManage, targetReady]);

  // Ctrl+S + Undo/Redo
  const handleSave = useCallback(async () => {
    if (!canManage || !targetReady || versioning.viewingVersionPid) return;
    try {
      await saveReport();
      lastSaveTimeRef.current = Date.now();
    } catch (error) {
      console.error('Save failed:', error);
      showErrorToast(text({ zh: '保存失败，草稿已保留，请重试。', en: 'Save failed. Your draft is preserved. Please retry.' }));
    }
  }, [saveReport, versioning.viewingVersionPid, canManage, showErrorToast, text, targetReady]);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (
        (!canManage || !targetReady || versioning.viewingVersionPid) &&
        (e.ctrlKey || e.metaKey) &&
        ['s', 'z', 'y'].includes(e.key.toLowerCase())
      ) {
        e.preventDefault();
        return;
      }
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        handleSave();
      }
      if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        undo();
      }
      if ((e.ctrlKey || e.metaKey) && (e.key === 'y' || (e.shiftKey && e.key === 'z'))) {
        e.preventDefault();
        redo();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [handleSave, undo, redo, versioning.viewingVersionPid, canManage, targetReady]);

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
    if (!canExport || isDirty || isSaving || (viewing && !previewQuery.canExport)) return;
    if (!pageId) {
      alert('Please save the report before exporting to Excel.');
      return;
    }
    try {
      const blob = await reportDesignerService.exportExcel(
        pageId,
        viewing ? previewQuery.appliedParameters : undefined,
      );
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
      notifyExportFailure();
    }
  }, [
    notifyExportFailure,
    report,
    pageId,
    isDirty,
    isSaving,
    viewing,
    canExport,
    previewQuery.canExport,
    previewQuery.appliedParameters,
  ]);

  // JSON Export
  const handleExportJson = useCallback(async () => {
    if (!canExport || isDirty || isSaving || (viewing && !previewQuery.canExport)) return;
    if (!pageId) {
      alert('Please save the report before exporting to JSON.');
      return;
    }
    try {
      const blob = await reportDesignerService.exportJson(
        pageId,
        viewing ? previewQuery.appliedParameters : undefined,
      );
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${report?.title || 'report'}.report.json`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (error) {
      console.error('JSON export failed:', error);
      notifyExportFailure();
    }
  }, [
    notifyExportFailure,
    report,
    pageId,
    isDirty,
    isSaving,
    viewing,
    canExport,
    previewQuery.canExport,
    previewQuery.appliedParameters,
  ]);

  const handleExportText = useCallback(
    async (format: 'markdown' | 'html') => {
      if (!canExport || !pageId || isDirty || isSaving || (viewing && !previewQuery.canExport))
        return;
      try {
        const parameters = viewing ? previewQuery.appliedParameters : undefined;
        const blob =
          format === 'markdown'
            ? await reportDesignerService.exportMarkdown(pageId, parameters)
            : await reportDesignerService.exportHtml(pageId, parameters);
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = `${report?.title || 'report'}.${format === 'markdown' ? 'md' : 'html'}`;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        URL.revokeObjectURL(url);
      } catch {
        // CATCH: download failure has no persistence side effect; show the existing localized error.
        notifyExportFailure();
      }
    },
    [
      canExport,
      pageId,
      isDirty,
      isSaving,
      viewing,
      previewQuery.canExport,
      previewQuery.appliedParameters,
      report?.title,
      notifyExportFailure,
    ],
  );

  // PDF Export
  const handleExportPdf = useCallback(async () => {
    if (!canExport || isDirty || isSaving || (viewing && !previewQuery.canExport)) return;
    if (!pageId) {
      alert('Please save the report before exporting to PDF.');
      return;
    }
    try {
      const blob = await reportDesignerService.exportPdf(
        pageId,
        viewing ? previewQuery.appliedParameters : undefined,
      );
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${report?.title || 'report'}.pdf`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (error) {
      console.error('PDF export failed:', error);
      notifyExportFailure();
    }
  }, [
    notifyExportFailure,
    report,
    pageId,
    isDirty,
    isSaving,
    viewing,
    canExport,
    previewQuery.canExport,
    previewQuery.appliedParameters,
  ]);

  const historyPanel = (
    <VersionHistoryPanel
      isOpen={versioning.isOpen}
      onClose={versioning.closePanel}
      versions={versioning.versions}
      isLoading={versioning.isLoading}
      viewingVersionPid={versioning.viewingVersionPid}
      onPreview={versioning.previewVersion}
      onExitPreview={versioning.exitPreview}
      canRollback={canManage}
      onRollback={versioning.rollbackToVersion}
      isRollingBack={versioning.isRollingBack}
    />
  );

  if (loadFailed)
    return (
      <div role="alert" className="p-8 text-red-600">
        {text({
          zh: '报表加载失败，请检查访问权限后重新打开。',
          en: 'Report could not be loaded. Check access permissions and reopen it.',
        })}
      </div>
    );

  if (isLoading || !targetReady) {
    return (
      <div className="flex h-screen items-center justify-center bg-gray-50">
        <div className="flex flex-col items-center gap-4">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
          <p className="text-gray-600">{text({ zh: '正在加载报表…', en: 'Loading report...' })}</p>
        </div>
      </div>
    );
  }

  if (versioning.viewingVersionPid) {
    const historical = versioning.viewingSnapshot?.dsl as ReportDsl | undefined;
    const normalizedHistorical = historical ? ensurePageConfig(historical) : undefined;
    return (
      <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden bg-gray-50" data-testid="report-version-preview">
        <div className="border-b border-amber-200 bg-amber-50 p-4 pr-80">
          <h2 className="font-semibold">{historical?.title}</h2>
          <p className="mt-1 text-sm">
            {text({
              zh: '历史版本只读预览；数据按当前访问权限重新查询。',
              en: 'Read-only historical definition; data is queried using current access permissions.',
            })}
          </p>
          <button
            type="button"
            onClick={versioning.exitPreview}
            className="mt-2 text-sm text-blue-700 underline"
          >
            {text({ zh: '返回当前报表', en: 'Return to current report' })}
          </button>
        </div>
        <div className="flex-1 overflow-auto pr-80">
          {historical ? (
            <PreviewContent
              key={versioning.viewingVersionPid}
              report={normalizedHistorical as ReportDsl}
            />
          ) : (
            <p role="alert" className="p-6">
              {text({
                zh: '历史版本缺少报表定义，请返回当前报表。',
                en: 'This version has no report definition. Return to the current report.',
              })}
            </p>
          )}
        </div>
        {historyPanel}
      </div>
    );
  }

  // Preview mode: render as runtime
  if (viewing && report) {
    return (
      <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden bg-gray-50">
        <ReportToolbar
          readOnly={!canManage}
          exportAllowed={canExport}
          exportReady={previewQuery.canExport}
          onSave={handleSave}
          onPreview={handlePreview}
          onExportPdf={handleExportPdf}
          onExportExcel={handleExportExcel}
          onExportJson={handleExportJson}
          onExportMarkdown={() => handleExportText('markdown')}
          onExportHtml={() => handleExportText('html')}
          onToggleVersionHistory={versioning.togglePanel}
          versionCount={versioning.versions.length}
        />
        <div className="flex-1 overflow-auto">
          <PreviewContent report={report} query={previewQuery} />
        </div>
        {historyPanel}
      </div>
    );
  }

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden bg-gray-50">
      <ReportToolbar
        readOnly={!canManage}
        exportAllowed={canExport}
        onSave={handleSave}
        onPreview={handlePreview}
        onExportPdf={handleExportPdf}
        onExportExcel={handleExportExcel}
        onExportJson={handleExportJson}
        onExportMarkdown={() => handleExportText('markdown')}
        onExportHtml={() => handleExportText('html')}
        onToggleVersionHistory={versioning.togglePanel}
        versionCount={versioning.versions.length}
      />
      <ReportTemplateActions disabled={!canManage || isSaving || isLoading} />
      <DesignerWorkspace
        compactWidth={900}
        testIdPrefix="report"
        palette={<BlockPalette />}
        canvas={<ReportCanvas />}
        propertyPanel={<BlockPropertyPanel />}
        inspectorFocusKey={selectedBlockId}
        labels={{
          components: text({ zh: '组件库', en: 'Components' }),
          properties: text({ zh: '属性', en: 'Properties' }),
          close: text({ zh: '关闭面板', en: 'Close panel' }),
        }}
      />

      {/* Version History Panel */}
      {historyPanel}
    </div>
  );
};

export const ReportDesigner: React.FC<ReportDesignerProps> = (props) => {
  return (
    <ReportDocumentProvider>
      <ReportDesignerInner {...props} />
    </ReportDocumentProvider>
  );
};

/**
 * Preview content fetches data and renders runtime view
 */
const PreviewContent: React.FC<{ report: ReportDsl; query?: ReportQuery }> = ({
  report,
  query: supplied,
}) => {
  const text = useSmartText();
  const internal = useReportQuery(report, !supplied);
  const query = supplied ?? internal;
  const { dataSets } = query;

  if (query.failed && !query.hasResult) {
    return (
      <div data-testid="report-runtime-paper" className="relative isolate mx-auto my-8 max-w-4xl rounded-lg bg-white p-8 shadow-sm">
        <ReportQueryControls report={report} query={query} />
      </div>
    );
  }

  return (
    <div data-testid="report-runtime-paper" className="relative isolate mx-auto my-8 max-w-4xl rounded-lg bg-white p-8 shadow-sm">
      <ReportQueryControls report={report} query={query} />
      {Object.values(report.dataSources).some((source) => source.type === 'aggregate') && (
        <p className="mb-4 text-sm text-gray-500" data-testid="report-aggregate-limit-hint">
          {text({
            zh: '聚合数据按查询定义的范围和条数上限计算，预览与导出使用相同查询。',
            en: 'Aggregate data uses the scope and row limit defined by the query. Preview and export use the same query.',
          })}
        </p>
      )}
      {Object.values(report.dataSources).some(
        (source) => source.type === 'model' || source.type === 'namedQuery',
      ) && (
        <p className="mb-4 text-sm text-gray-500">
          {text({
            zh: '模型和命名查询预览最多 500 行，同步导出最多 1000 行；超过时请缩小筛选范围。',
            en: 'Model and named-query sources preview up to 500 rows and export up to 1000 rows. Narrow filters for larger results.',
          })}
        </p>
      )}
      {report.header && (
        <>
          <ReportBandBlock band={report.header} mode="runtime" position="header" />
          <hr className="mb-4" />
        </>
      )}

      {report.body.map((block) => (
        <div key={block.id} className="mb-6">
          {block.blockType === 'table' && (
            <ReportTableBlock
              block={block}
              mode="runtime"
              data={dataSets[block.dataSource] || []}
            />
          )}
          {block.blockType === 'grouped-table' && (
            <ReportGroupedTableBlock
              block={block}
              mode="runtime"
              data={dataSets[block.dataSource] || []}
            />
          )}
          {block.blockType === 'stat-card' && (
            <ReportStatCardBlock
              block={block}
              mode="runtime"
              data={dataSets[block.dataSource] || []}
            />
          )}
          {block.blockType === 'rich-text' && <ReportRichTextBlock block={block} mode="runtime" />}
          {block.blockType === 'cross-tab' && (
            <ReportCrossTabBlock
              block={block}
              mode="runtime"
              data={dataSets[block.dataSource] || []}
            />
          )}
          {block.blockType === 'chart' && (
            <ReportChartBlock
              block={block}
              mode="runtime"
              data={dataSets[block.dataSource] || []}
            />
          )}
          {block.blockType === 'barcode' && (
            <ReportBarcodeBlock
              block={block}
              mode="runtime"
              data={block.dataSource ? dataSets[block.dataSource] || [] : []}
            />
          )}
          {block.blockType === 'watermark' && <ReportWatermarkBlock block={block} mode="runtime" />}
        </div>
      ))}

      {report.footer && (
        <>
          <hr className="mt-4 mb-4" />
          <ReportBandBlock band={report.footer} mode="runtime" position="footer" />
        </>
      )}
    </div>
  );
};

export default ReportDesigner;
