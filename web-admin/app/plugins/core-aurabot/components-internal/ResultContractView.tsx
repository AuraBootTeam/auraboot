/**
 * ResultContractView
 *
 * Renders a structured ACP ResultContract (Skill execution output) in the
 * AuraBot chat stream. Dispatches on `renderHint`:
 *
 *   - table / chart_table → <table> with headers from first row
 *   - summary             → textSummary paragraph + optional key/value data
 *   - card                → single key/value card
 *   - timeline            → ordered list of data.events[]
 *   - (fallback)          → JSON dump of data
 *
 * All variants show a header with skillCode + status badge + durationMs.
 *
 * @since 1.0.0
 */

import { ChatBiResultCard, type ChatBiResult } from './ChatBiResultCard';
import { useSmartText } from '~/utils/i18n';
import { formatParamName } from './ConfirmCard';
import { CheckCircle, AlertCircle, XCircle, Clock } from 'lucide-react';
import type { ResultContract, ResultContractStatus } from '../types/ResultContract';

// ============================================================================
// Helpers
// ============================================================================

const STATUS_STYLE: Record<
  ResultContractStatus,
  { color: string; Icon: React.ComponentType<{ className?: string }> }
> = {
  success: { color: 'text-green-600', Icon: CheckCircle },
  partial_success: { color: 'text-yellow-600', Icon: AlertCircle },
  failed: { color: 'text-red-600', Icon: XCircle },
  unknown: { color: 'text-gray-500', Icon: AlertCircle },
};

function truncate(val: unknown, max = 60): string {
  if (val == null) return '—';
  const s = typeof val === 'object' ? JSON.stringify(val) : String(val);
  return s.length > max ? s.slice(0, max) + '…' : s;
}

function resolveTableRows(contract: ResultContract): Array<Record<string, unknown>> {
  if (Array.isArray(contract.table) && contract.table.length > 0) {
    return contract.table;
  }
  const candidate = contract.data?.records ?? contract.data?.table;
  return Array.isArray(candidate) ? (candidate as Array<Record<string, unknown>>) : [];
}

function contractMetric(contract: ResultContract): string | null {
  const rows = resolveTableRows(contract);
  if (rows.length > 0) {
    return `rows: ${rows.length}`;
  }
  if (contract.suggestedActions && contract.suggestedActions.length > 0) {
    return `actions: ${contract.suggestedActions.length}`;
  }
  return null;
}

// ============================================================================
// Variant renderers
// ============================================================================

function TableView({ rows, label, structuredLabel }: { rows: Array<Record<string, unknown>>; label: (key: string) => string; structuredLabel: string }) {
  if (rows.length === 0) {
    return <div className="text-sm text-gray-500">(no rows)</div>;
  }
  const cols = Object.keys(rows[0]);
  return (
    <div className="max-w-full overflow-x-auto [scrollbar-width:thin] [&::-webkit-scrollbar]:h-1.5 [&::-webkit-scrollbar-thumb]:rounded [&::-webkit-scrollbar-thumb]:bg-gray-300" data-testid="rc-table" role="region" tabIndex={0} aria-label={structuredLabel}>
      <table className="w-full table-fixed border border-gray-200 text-sm" style={{ minWidth: `${cols.length * 8}rem` }}>
        <thead className="bg-gray-50">
          <tr>
            {cols.map((c) => (
              <th key={c} className="border-b border-gray-200 px-2 py-1 text-left font-medium">
                {label(c)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.slice(0, 20).map((row, i) => (
            <tr key={i} className={i % 2 ? 'bg-gray-50' : ''}>
              {cols.map((c) => (
                <td key={c} className="break-words border-b border-gray-100 px-2 py-1 align-top [overflow-wrap:anywhere]">
                  {row[c] != null && typeof row[c] === 'object' ? <details>
                    <summary className="cursor-pointer">{structuredLabel}</summary>
                    <pre className="max-w-full whitespace-pre-wrap break-words">{JSON.stringify(row[c], null, 2)}</pre>
                  </details> : row[c] == null ? '—' : String(row[c])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length > 20 && (
        <div className="mt-1 text-xs text-gray-500">…{rows.length - 20} more rows</div>
      )}
    </div>
  );
}

function SummaryView({ contract }: { contract: ResultContract }) {
  return (
    <div data-testid="rc-summary">
      {contract.textSummary && (
        <p className="text-sm leading-relaxed whitespace-pre-wrap">{contract.textSummary}</p>
      )}
      {contract.data && Object.keys(contract.data).length > 0 && (
        <dl className="mt-2 grid grid-cols-[minmax(5rem,0.4fr)_minmax(0,1fr)] gap-x-3 gap-y-1 text-sm">
          {Object.entries(contract.data).map(([k, v]) => (
            <div key={k} className="contents">
              <dt className="font-medium text-gray-700">{k}</dt>
              <dd className="min-w-0 break-words text-gray-900">{truncate(v, 200)}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}

function CardView({ contract }: { contract: ResultContract }) {
  const entries = contract.data ? Object.entries(contract.data) : [];
  return (
    <div className="rounded border border-gray-200 bg-white p-3" data-testid="rc-card">
      {contract.textSummary && (
        <div className="mb-2 text-sm font-medium">{contract.textSummary}</div>
      )}
      {entries.length > 0 && (
        <dl className="grid grid-cols-[minmax(5rem,0.4fr)_minmax(0,1fr)] gap-x-3 gap-y-1 text-sm">
          {entries.map(([k, v]) => (
            <div key={k} className="contents">
              <dt className="text-gray-600">{k}</dt>
              <dd className="min-w-0 break-words text-gray-900">{truncate(v, 120)}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}

interface TimelineEvent {
  at?: string;
  label?: string;
  [key: string]: unknown;
}

function TimelineView({ contract }: { contract: ResultContract }) {
  const events = (contract.data?.events as TimelineEvent[] | undefined) ?? [];
  return (
    <ol className="relative space-y-2 border-l border-gray-200 pl-4" data-testid="rc-timeline">
      {events.map((e, i) => (
        <li key={i} className="text-sm">
          <div className="text-gray-900">{e.label ?? `Event ${i + 1}`}</div>
          {e.at && <div className="text-xs text-gray-500">{e.at}</div>}
        </li>
      ))}
    </ol>
  );
}

function JsonFallback({ contract }: { contract: ResultContract }) {
  return (
    <pre
      className="overflow-x-auto rounded border border-gray-200 bg-gray-50 p-2 text-xs"
      data-testid="rc-json"
    >
      {JSON.stringify(contract.data ?? {}, null, 2)}
    </pre>
  );
}

// ============================================================================
// Main component
// ============================================================================

export interface ResultContractViewProps {
  contract: ResultContract;
}

export function ResultContractView({ contract }: ResultContractViewProps) {
  const text = useSmartText();
  // Older persisted previews were incorrectly emitted as failed execution results.
  // Keep their diagnostic bytes inspectable without inventing a current execution status.
  if (contract.status === 'failed'
      && contract.textSummary === 'AuraBot skill requires confirmation before execution.') {
    return <details data-testid="rc-approval-preview" className="text-xs text-gray-500">
      <summary className="cursor-pointer py-1">{text({ 'zh-CN': '审批预览详情', 'en-US': 'Approval preview details' })}</summary>
      <pre className="max-w-full overflow-x-auto whitespace-pre-wrap break-words">{JSON.stringify(contract, null, 2)}</pre>
    </details>;
  }
  if (contract.status === 'failed' && contract.errorCode === 'analytics_history_unavailable') {
    return (
      <div
        role="alert"
        className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700"
      >
        {text({
          'zh-CN': '历史分析暂不可用，请检查当前数据权限和查询配置。',
          'en-US':
            'Historical analysis is unavailable. Check current data access and query configuration.',
        })}
      </div>
    );
  }
  // AuraBot skills return an execution envelope; render the analytics data, not protocol fields.
  if (contract.skillCode === 'aurabot:chat-bi' && contract.status === 'success') {
    const envelope = contract.data;
    const candidate = envelope?.data ?? envelope;
    if (
      candidate &&
      typeof candidate === 'object' &&
      'records' in candidate &&
      Array.isArray(candidate.records)
    ) {
      return <ChatBiResultCard result={candidate as ChatBiResult} />;
    }
  }

  // Reference resolution exposes the business label; storage identity remains diagnostic.
  if (
    ['platform.resolve_reference', 'platform_resolve_reference'].includes(contract.skillCode || '') &&
    contract.status === 'success'
  ) {
    const label = contract.data?.displayValue;
    if (typeof label !== 'string' || !label.trim()) {
      return (
        <div role="alert">
          {text({
            'zh-CN': '关联对象结果不完整，请重新查询。',
            'en-US': 'The reference result is incomplete. Run the query again.',
          })}
        </div>
      );
    }
    return (
      <div className="rounded-lg border border-gray-200 bg-white p-3 text-sm" data-testid="reference-resolution-result">
        <div className="flex items-center gap-2">
          <CheckCircle className="h-4 w-4 text-green-600" />
          <span>{text({ 'zh-CN': '已确认关联对象', 'en-US': 'Reference confirmed' })}</span>
          <strong className="break-words">{label}</strong>
        </div>
        <details className="mt-2 text-xs text-gray-500" data-testid="reference-resolution-details">
          <summary className="cursor-pointer">
            {text({ 'zh-CN': '匹配详情', 'en-US': 'Match details' })}
          </summary>
          <div className="mt-2"><CardView contract={contract} /></div>
          <code>{contract.skillCode}</code>
        </details>
      </div>
    );
  }

  const skillPayload = contract.data?.data ?? contract.data;
  const createdModel = contract.status === 'success' && contract.skillCode === 'aurabot:model:create'
    && skillPayload && typeof skillPayload === 'object'
    && 'displayName' in skillPayload && typeof skillPayload.displayName === 'string'
    && 'modelCode' in skillPayload && typeof skillPayload.modelCode === 'string' ? skillPayload : null;

  const statusStyle = STATUS_STYLE[contract.status] ?? STATUS_STYLE.unknown;
  const { color, Icon } = statusStyle;
  const metric = contractMetric(contract);

  return (
    <div className="min-w-0 max-w-full space-y-2" data-testid="result-contract">
      <div className={`flex items-center gap-2 text-sm ${color}`}>
        <Icon className="h-4 w-4 shrink-0" />
        <span>{text({
          'zh-CN': ({ success: '已完成', partial_success: '部分完成', failed: '未完成', unknown: '状态未知' })[contract.status] ?? '状态未知',
          'en-US': ({ success: 'Completed', partial_success: 'Partially completed', failed: 'Not completed', unknown: 'Status unknown' })[contract.status] ?? 'Status unknown',
        })}</span>
      </div>
      <details className="min-w-0 text-xs text-gray-500" data-testid="rc-diagnostics">
        <summary className="cursor-pointer py-1">{text({ 'zh-CN': '执行详情', 'en-US': 'Execution details' })}</summary>
      <div className="flex items-center gap-2 text-xs text-gray-600">
        <Icon className={`h-4 w-4 ${color}`} />
        <span className={`font-medium ${color}`}>{contract.status}</span>
        {contract.skillCode && (
          <>
            <span className="text-gray-400">·</span>
            <span className="font-mono">{contract.skillCode}</span>
          </>
        )}
        {typeof contract.durationMs === 'number' && (
          <>
            <span className="text-gray-400">·</span>
            <Clock className="h-3 w-3" />
            <span>{contract.durationMs}ms</span>
          </>
        )}
      </div>
      <div
        className="flex flex-wrap gap-1 text-[11px] text-gray-600"
        data-testid="rc-protocol-meta"
      >
        <span
          className="rounded border border-gray-200 bg-gray-50 px-1.5 py-0.5"
          data-testid="rc-meta-output-type"
        >
          output: {contract.outputType}
        </span>
        <span
          className="rounded border border-gray-200 bg-gray-50 px-1.5 py-0.5"
          data-testid="rc-meta-actionability"
        >
          action: {contract.actionability}
        </span>
        {contract.renderHint && (
          <span
            className="rounded border border-gray-200 bg-gray-50 px-1.5 py-0.5"
            data-testid="rc-meta-render-hint"
          >
            render: {contract.renderHint}
          </span>
        )}
        {metric && (
          <span
            className="rounded border border-gray-200 bg-gray-50 px-1.5 py-0.5"
            data-testid="rc-meta-metric"
          >
            {metric}
          </span>
        )}
        {contract.canContinueFrom && (
          <span className="rounded border border-blue-200 bg-blue-50 px-1.5 py-0.5 text-blue-700">
            continuable
          </span>
        )}
      </div>

      {createdModel && <div className="mt-2"><CardView contract={contract} /></div>}
      </details>

      {createdModel ? (
        <dl className="grid grid-cols-[minmax(4rem,0.4fr)_minmax(0,1fr)] gap-x-3 gap-y-2 rounded-lg border border-green-200 bg-green-50 p-3 text-sm" data-testid="rc-created-model">
          <dt className="text-gray-600">{text({ 'zh-CN': '模型名称', 'en-US': 'Model name' })}</dt>
          <dd className="min-w-0 break-words font-medium">{String(createdModel.displayName)}</dd>
          <dt className="text-gray-600">{text({ 'zh-CN': '模型编码', 'en-US': 'Model code' })}</dt>
          <dd className="min-w-0 break-words [overflow-wrap:anywhere]">{String(createdModel.modelCode)}</dd>
        </dl>
      ) : contract.renderHint === 'table' || contract.renderHint === 'chart_table' ? (
        <TableView rows={resolveTableRows(contract)} label={(key) => text({ 'zh-CN': formatParamName(key, true), 'en-US': formatParamName(key, false) })} structuredLabel={text({ 'zh-CN': '结构化结果', 'en-US': 'Structured result' })} />
      ) : contract.renderHint === 'summary' ? (
        <SummaryView contract={contract} />
      ) : contract.renderHint === 'card' ? (
        <CardView contract={contract} />
      ) : contract.renderHint === 'timeline' ? (
        <TimelineView contract={contract} />
      ) : contract.textSummary ? (
        <SummaryView contract={contract} />
      ) : (
        <JsonFallback contract={contract} />
      )}

      {contract.suggestedActions && contract.suggestedActions.length > 0 && (
        <div className="flex flex-wrap gap-1 pt-1" data-testid="rc-suggested-actions">
          {contract.suggestedActions.map((a, i) => (
            <span
              key={i}
              className="inline-block rounded-full border border-blue-200 bg-blue-50 px-2 py-0.5 text-xs text-blue-700"
            >
              {a.label}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
