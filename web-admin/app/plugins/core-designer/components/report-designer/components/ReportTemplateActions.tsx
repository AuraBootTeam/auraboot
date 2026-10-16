import { useRef, useState } from 'react';
import { useSmartText } from '~/utils/i18n';
import { useReportDocument } from '../state/ReportDocumentProvider';
import {
  parseReportTemplate,
  REPORT_TEMPLATE_MAX_BYTES,
  serializeReportTemplate,
} from '../services/reportTemplateIO';

/** Design-definition exchange is separate from result exports and keeps target identity. */
export function ReportTemplateActions({ disabled = false }: { disabled?: boolean }) {
  const text = useSmartText();
  const { report, importDocument } = useReportDocument();
  const input = useRef<HTMLInputElement>(null);
  const [error, setError] = useState(false);
  const [imported, setImported] = useState(false);
  const buttonClass =
    'rounded-md border border-gray-200 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50';
  const exportTemplate = () => {
    if (!report || disabled) return;
    setError(false);
    try {
      const blob = new Blob([serializeReportTemplate(report)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = 'report.report-template.json';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } catch {
      setError(true);
    }
  };
  return (
    <section
      aria-label={text({ zh: '设计模板', en: 'Design template' })}
      className="border-b border-gray-200 bg-white px-4 py-2"
      data-testid="report-template-actions"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-2 text-xs font-medium text-gray-500">
          {text({ zh: '设计模板', en: 'Design template' })}
        </span>
        <button
          type="button"
          className={buttonClass}
          disabled={disabled || !report}
          onClick={() => input.current?.click()}
          data-testid="report-template-import"
        >
          {text({ zh: '导入设计模板', en: 'Import design template' })}
        </button>
        <button
          type="button"
          className={buttonClass}
          disabled={disabled || !report}
          onClick={exportTemplate}
          data-testid="report-template-export"
        >
          {text({ zh: '导出设计模板', en: 'Export design template' })}
        </button>
        <span className="text-xs text-gray-500">
          {text({
            zh: '替换当前设计，可撤销；不会复制查询结果或权限。',
            en: 'Replaces the current design and supports undo. Query results and permissions are not copied.',
          })}
        </span>
      </div>
      <input
        ref={input}
        type="file"
        accept=".json,application/json"
        hidden
        data-testid="report-template-import-input"
        disabled={disabled}
        onChange={async (event) => {
          const file = event.currentTarget.files?.[0];
          event.currentTarget.value = '';
          if (!file || disabled) return;
          setError(false);
          setImported(false);
          try {
            if (file.size > REPORT_TEMPLATE_MAX_BYTES)
              throw new Error('Report template is too large');
            const next = parseReportTemplate(await file.text());
            importDocument(next);
            setImported(true);
          } catch {
            setError(true);
          }
        }}
      />
      {error && (
        <p role="alert" className="mt-2 text-sm text-red-600" data-testid="report-template-error">
          {text({
            zh: '设计模板处理失败。请使用有效的 AuraBoot 报表设计模板（不超过 2 MB）；当前设计未被替换。',
            en: 'Template processing failed. Use a valid AuraBoot report design template (up to 2 MB). The current design was not replaced.',
          })}
        </p>
      )}
      {imported && !error && (
        <p
          role="status"
          className="mt-2 text-sm text-blue-700"
          data-testid="report-template-imported"
        >
          {text({
            zh: '设计模板已载入。可撤销；保存后写入当前报表，数据预览仍受当前权限控制。',
            en: 'Design template loaded. You can undo; saving writes to the current report. Data preview uses current permissions.',
          })}
        </p>
      )}
    </section>
  );
}
