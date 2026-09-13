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
        <div role="alert" className="mb-3 text-sm text-red-700">
          <p>
            {query.failure === 'access'
              ? text({
                  zh: '查询未成功：当前账号无权读取报表数据，或登录已失效。请确认登录和访问权限。',
                  en: 'Query failed: access was denied or your session expired. Check your sign-in and permissions.',
                })
              : query.failure === 'parameters'
                ? text({
                    zh: '查询未成功：请检查必填参数及输入格式。',
                    en: 'Query failed: check required parameters and input formats.',
                  })
                : text({
                    zh: '查询未成功，请重试；若持续失败，请联系管理员。',
                    en: 'Query failed. Retry, or contact your administrator if the problem persists.',
                  })}
          </p>
          <p className="mt-1">
            {query.hasResult
              ? text({
                  zh: '当前显示上次成功查询的结果，下载仍使用该次已应用的参数。',
                  en: 'Showing the last successful results. Downloads still use their applied parameters.',
                })
              : text({
                  zh: '当前没有可用结果，下载已禁用。',
                  en: 'No results are available. Downloads are disabled.',
                })}
          </p>
          <button
            type="button"
            onClick={query.apply}
            disabled={query.loading}
            className="mt-2 rounded border border-current px-3 py-1 disabled:opacity-50"
          >
            {text({ zh: '重新查询', en: 'Retry query' })}
          </button>
        </div>
      )}
    </div>
  );
}
