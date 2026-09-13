import { useSmartText } from '~/utils/i18n';
import { useToastContext } from '~/contexts/ToastContext';
/**
 * ReportPageContent — runtime report viewer
 * Loads report DSL, fetches data, renders the report, and provides export options
 * Phase 2c: adds ParametersBar, cross-tab, chart, all block types
 */

import React, { useEffect, useState, useCallback } from 'react';
import type { ReportDsl } from '../types';
import { reportDesignerService } from '../services/reportDesignerService';
import { useReportQuery } from '../services/useReportQuery';
import { ReportQueryControls } from '../components/ReportQueryControls';
import { ReportTableBlockRenderer } from './ReportTableBlockRenderer';
import { ReportBandRenderer } from './ReportBandRenderer';
import { ReportPageSkeleton } from './ReportPageSkeleton';
import { ReportGroupedTableBlock } from '../blocks/ReportGroupedTableBlock';
import { ReportStatCardBlock } from '../blocks/ReportStatCardBlock';
import { ReportRichTextBlock } from '../blocks/ReportRichTextBlock';
import { ReportCrossTabBlock } from '../blocks/ReportCrossTabBlock';
import { ReportChartBlock } from '../blocks/ReportChartBlock';

interface ReportPageContentProps {
  pageKey: string;
}

export const ReportPageContent: React.FC<ReportPageContentProps> = ({ pageKey }) => {
  const text = useSmartText();
  const { showErrorToast } = useToastContext();
  const [report, setReport] = useState<ReportDsl | null>(null);
  const [reportPid, setReportPid] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const query = useReportQuery(report);
  const { dataSets } = query;

  useEffect(() => {
    let mounted = true;

    async function load() {
      setLoading(true);
      setError(null);
      try {
        const result = await reportDesignerService.loadByPageKey(pageKey);
        if (!mounted) return;
        setReport(result.dsl);
        setReportPid(result.pid);
      } catch (err) {
        if (!mounted) return;
        setError(err instanceof Error ? err.message : 'Failed to load report');
      } finally {
        if (mounted) setLoading(false);
      }
    }

    load();
    return () => {
      mounted = false;
    };
  }, [pageKey]);

  const handleExportPdf = useCallback(async () => {
    if (!report || !reportPid || !query.canExport) return;
    setExporting(true);
    try {
      const blob = await reportDesignerService.exportPdf(reportPid, query.appliedParameters);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${report.title || 'report'}.pdf`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error('PDF export failed:', err);
      showErrorToast(text({ zh: '导出未完成。请检查数据源和访问权限后重试。', en: 'Export could not be completed. Check the data source and access permissions, then retry.' }));
    } finally {
      setExporting(false);
    }
  }, [report, reportPid, query.canExport, query.appliedParameters, text, showErrorToast]);

  const handlePrint = useCallback(() => {
    window.print();
  }, []);

  if (loading && !report) return <ReportPageSkeleton />;
  if (error)
    return (
      <div role="alert" className="mx-auto max-w-4xl p-8 text-red-600">
        {text({
          zh: '报表加载失败，请检查数据源和访问权限后重新打开。',
          en: 'Report could not be loaded. Check the data source and access permissions, then reopen the report.',
        })}
      </div>
    );
  if (!report) return <div className="mx-auto max-w-4xl p-8 text-gray-500">Report not found</div>;

  return (
    <div className="mx-auto max-w-4xl p-8">
      {/* Toolbar */}
      <div className="mb-6 flex items-center justify-between print:hidden">
        <h1 className="text-xl font-semibold text-gray-900">{report.title}</h1>
        <div className="flex gap-2">
          <button
            onClick={handleExportPdf}
            disabled={exporting || !query.canExport}
            className="rounded-md bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {exporting ? 'Exporting...' : 'Export PDF'}
          </button>
          <button
            onClick={handlePrint}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            Print
          </button>
        </div>
      </div>

      {report.description && (
        <p className="mb-6 text-sm text-gray-600 print:hidden">{report.description}</p>
      )}

      <ReportQueryControls report={report} query={query} />

      <p className="mb-4 text-sm text-gray-500 print:hidden">
        {text({
          zh: '模型和命名查询预览最多 500 行，同步导出最多 1000 行；超过时请缩小筛选范围。',
          en: 'Model and named-query sources preview up to 500 rows and export up to 1000 rows. Narrow filters for larger results.',
        })}
      </p>
      {/* Report content */}
      <div className="rounded-lg bg-white p-8 shadow-sm print:p-0 print:shadow-none">
        {/* Header */}
        {report.header && (
          <>
            <ReportBandRenderer band={report.header} position="header" />
            <hr className="my-4 border-gray-300" />
          </>
        )}

        {/* Body blocks */}
        {report.body.map((block) => (
          <div key={block.id} className="mb-6">
            {block.blockType === 'table' && (
              <ReportTableBlockRenderer block={block} data={dataSets[block.dataSource] || []} />
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
            {block.blockType === 'rich-text' && (
              <ReportRichTextBlock block={block} mode="runtime" />
            )}
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
          </div>
        ))}

        {/* Footer */}
        {report.footer && (
          <>
            <hr className="my-4 border-gray-300" />
            <ReportBandRenderer band={report.footer} position="footer" />
          </>
        )}
      </div>
    </div>
  );
};
