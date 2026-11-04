import { useI18n } from '~/contexts/I18nContext';
import {
  ChatBiResultCard,
  type ChatBiResult,
} from '~/plugins/core-aurabot/components-internal/ChatBiResultCard';
import { toolQueryPresentation } from '~/plugins/core-aurabot/components-internal/toolQueryPresentation';

export interface EngineResult {
  status: string;
  sourceToolCode?: string;
  data?: Record<string, unknown>;
  outputType?: string;
  renderHint?: string;
  textSummary?: string;
  errorMessage?: string;
  durationMs?: number;
  toolCallCount?: number;
}

/** Display business query evidence; protocol bytes remain an explicit diagnostic disclosure. */
export function SkillTrialResult({ result }: { result: EngineResult }) {
  const { t, locale } = useI18n();
  const label = (key: string, cn: string, en: string) =>
    t(`ai.skills.result.${key}`, undefined, locale.startsWith('zh') ? cn : en);
  const pending =
    result.data?.approvalRequired === true || result.data?.confirmationRequired === true;
  if (pending)
    return (
      <p role="status">
        {label(
          'pending',
          '已暂停，等待确认或审批。本页不会自动重试；请前往审批入口核查。',
          'Paused for confirmation or approval. This page will not retry. Review it in approvals.',
        )}
      </p>
    );
  const successful = result.status === 'SUCCESS';
  const source = result.sourceToolCode;
  const isQuery = successful && /^(list|get):[a-z][a-z0-9_]*$/.test(source ?? '');
  const data = result.data ?? {};
  const records = isQuery
    ? source?.startsWith('list:')
      ? Array.isArray(data.records)
        ? (data.records as Record<string, unknown>[])
        : []
      : [
          data.record && typeof data.record === 'object'
            ? (data.record as Record<string, unknown>)
            : data,
        ]
    : [];
  const query: ChatBiResult = toolQueryPresentation(
    { records, total: typeof data.total === 'number' ? data.total : records.length },
    source?.replace(/^get:/, 'list:'),
  );
  // JSON configuration and nested values are available in diagnostics, not flattened into table cells.
  query.columns = query.columns?.filter(
    (column) =>
      records.some((record) => record[column] != null && record[column] !== '') &&
      records.every((record) => {
        const value = record[column];
        return (
          value == null ||
          typeof value === 'number' ||
          typeof value === 'boolean' ||
          (typeof value === 'string' && !/^\s*[\[{]/.test(value))
        );
      }),
  );
  const summary =
    result.textSummary && !/^\s*[\[{]/.test(result.textSummary) ? result.textSummary : undefined;
  return (
    <div className="space-y-3">
      <p role="status" className={successful ? 'text-status-green' : 'text-status-red'}>
        {successful
          ? label('executed', '工具已执行，请核对结果。', 'Tool executed. Review the result.')
          : result.status === 'PARTIAL_SUCCESS'
            ? label(
                'partial',
                '部分步骤已完成，请检查详情，勿直接重跑全部步骤。',
                'Some steps completed. Review details before rerunning any steps.',
              )
            : label(
                'failed',
                '执行未完成，请检查输入、权限及工具配置。',
                'Execution did not complete. Check inputs, permissions and tool configuration.',
              )}
      </p>
      {isQuery ? (
        <ChatBiResultCard result={query} />
      ) : (
        summary && <p className="text-sm whitespace-pre-wrap">{summary}</p>
      )}
      <details className="rounded-control border-border border p-3 text-sm">
        <summary className="text-text-2 cursor-pointer">
          {label('diagnostics', '查看执行诊断', 'Execution diagnostics')}
        </summary>
        <pre className="mt-3 max-h-64 overflow-auto text-xs break-words whitespace-pre-wrap">
          {JSON.stringify(result, null, 2)}
        </pre>
      </details>
    </div>
  );
}
