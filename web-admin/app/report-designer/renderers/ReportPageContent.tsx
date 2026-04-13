/**
 * ReportPageContent — runtime report viewer
 * Loads report DSL, fetches data, renders the report, and provides export options
 * Phase 2c: adds ParametersBar, cross-tab, chart, all block types
 */

import React, { useEffect, useState, useCallback } from 'react';
import type { ReportDsl } from '../types';
import { reportDesignerService } from '../services/reportDesignerService';
import { fetchReportData } from '../services/fetchReportData';
import { reportToHtml } from '../services/reportToHtml';
import { ReportTableBlockRenderer } from './ReportTableBlockRenderer';
import { ReportBandRenderer } from './ReportBandRenderer';
import { ReportPageSkeleton } from './ReportPageSkeleton';
import { ParametersBar } from '../components/ParametersBar';
import { ReportGroupedTableBlock } from '../blocks/ReportGroupedTableBlock';
import { ReportStatCardBlock } from '../blocks/ReportStatCardBlock';
import { ReportRichTextBlock } from '../blocks/ReportRichTextBlock';
import { ReportCrossTabBlock } from '../blocks/ReportCrossTabBlock';
import { ReportChartBlock } from '../blocks/ReportChartBlock';

interface ReportPageContentProps {
  pageKey: string;
}

export const ReportPageContent: React.FC<ReportPageContentProps> = ({ pageKey }) => {
  const [report, setReport] = useState<ReportDsl | null>(null);
  const [dataSets, setDataSets] = useState<Record<string, Record<string, unknown>[]>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const [paramValues, setParamValues] = useState<Record<string, string>>({});

  const loadData = useCallback(async (dsl: ReportDsl, params?: Record<string, string>) => {
    // Apply parameter bindings to data source filters
    const dslWithFilters = applyParameterBindings(dsl, params || {});
    const data = await fetchReportData(dslWithFilters);
    setDataSets(data);
  }, []);

  useEffect(() => {
    let mounted = true;

    async function load() {
      try {
        const result = await reportDesignerService.loadByPageKey(pageKey);
        if (!mounted) return;
        setReport(result.dsl);

        // Initialize default param values
        const defaults: Record<string, string> = {};
        (result.dsl.parameters || []).forEach((p) => {
          if (p.defaultValue) defaults[p.name] = p.defaultValue;
        });
        setParamValues(defaults);

        await loadData(result.dsl, defaults);
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
  }, [pageKey, loadData]);

  const handleApplyParams = useCallback(async () => {
    if (!report) return;
    setLoading(true);
    try {
      await loadData(report, paramValues);
    } finally {
      setLoading(false);
    }
  }, [report, paramValues, loadData]);

  const handleExportPdf = useCallback(async () => {
    if (!report) return;
    setExporting(true);
    try {
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
    } catch (err) {
      console.error('PDF export failed:', err);
      alert(err instanceof Error ? err.message : 'PDF export failed');
    } finally {
      setExporting(false);
    }
  }, [report, dataSets]);

  const handlePrint = useCallback(() => {
    window.print();
  }, []);

  if (loading && !report) return <ReportPageSkeleton />;
  if (error) return <div className="mx-auto max-w-4xl p-8 text-red-600">Error: {error}</div>;
  if (!report) return <div className="mx-auto max-w-4xl p-8 text-gray-500">Report not found</div>;

  return (
    <div className="mx-auto max-w-4xl p-8">
      {/* Toolbar */}
      <div className="mb-6 flex items-center justify-between print:hidden">
        <h1 className="text-xl font-semibold text-gray-900">{report.title}</h1>
        <div className="flex gap-2">
          <button
            onClick={handleExportPdf}
            disabled={exporting}
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

      {/* Parameters Bar */}
      {report.parameters && report.parameters.length > 0 && (
        <ParametersBar
          parameters={report.parameters}
          values={paramValues}
          onChange={setParamValues}
          onApply={handleApplyParams}
        />
      )}

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

/**
 * Apply parameter bindings to data source filters
 * Creates a modified copy of the DSL with parameter values injected as filters
 */
function applyParameterBindings(dsl: ReportDsl, paramValues: Record<string, string>): ReportDsl {
  const params = dsl.parameters || [];
  const boundParams = params.filter((p) => p.bindTo && paramValues[p.name]);

  if (boundParams.length === 0) return dsl;

  const modifiedDs = { ...dsl.dataSources };
  for (const param of boundParams) {
    const { dataSource, field, operator } = param.bindTo!;
    const value = paramValues[param.name];
    if (!value || !modifiedDs[dataSource]) continue;

    const ds = { ...modifiedDs[dataSource] };
    const filters = [...(ds.filters || [])];
    filters.push({ field, operator, value });
    ds.filters = filters;
    modifiedDs[dataSource] = ds;
  }

  return { ...dsl, dataSources: modifiedDs };
}
