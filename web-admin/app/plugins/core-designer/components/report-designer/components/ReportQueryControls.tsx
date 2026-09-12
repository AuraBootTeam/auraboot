import { useSmartText } from '~/utils/i18n';
import type { ReportDsl } from '../types';
import type { ReportQuery } from '../services/useReportQuery';
import { ParametersBar } from './ParametersBar';

export function ReportQueryControls({ report, query }: { report: ReportDsl; query: ReportQuery }) {
  const text = useSmartText();
  return (
    <div className="print:hidden">
      <ParametersBar
        parameters={report.parameters ?? []}
        values={query.draft}
        onChange={query.setDraft}
        onApply={query.apply}
        disabled={query.loading}
      />
      {!!report.parameters?.length && (
        <p className="mb-3 text-sm text-gray-600">
          {text({
            zh: '修改参数后请点击应用。下载使用当前结果已应用的参数。',
            en: 'Apply parameter changes to refresh results. Downloads use the parameters applied to current results.',
          })}
        </p>
      )}
      {query.loading && (
        <p role="status" className="mb-3 text-sm text-gray-600">
          {text({ zh: '正在查询报表数据…', en: 'Loading report data…' })}
        </p>
      )}
      {query.failed && (
        <p role="alert" className="mb-3 text-sm text-red-700">
          {text({
            zh: '查询未成功，请检查必填参数、格式和访问权限后重新应用。已有结果及下载参数保持不变。',
            en: 'Query failed. Check required parameters, formats and access, then apply again. Existing results and download parameters are unchanged.',
          })}
        </p>
      )}
    </div>
  );
}
