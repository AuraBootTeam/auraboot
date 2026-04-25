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

import { CheckCircle, AlertCircle, XCircle, Clock } from 'lucide-react';
import type { ResultContract, ResultContractStatus } from '../types/ResultContract';

// ============================================================================
// Helpers
// ============================================================================

const STATUS_STYLE: Record<ResultContractStatus, { color: string; Icon: React.ComponentType<{ className?: string }> }> = {
  success:         { color: 'text-green-600',  Icon: CheckCircle },
  partial_success: { color: 'text-yellow-600', Icon: AlertCircle },
  failed:          { color: 'text-red-600',    Icon: XCircle },
  unknown:         { color: 'text-gray-500',   Icon: AlertCircle },
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

// ============================================================================
// Variant renderers
// ============================================================================

function TableView({ rows }: { rows: Array<Record<string, unknown>> }) {
  if (rows.length === 0) {
    return <div className="text-sm text-gray-500">(no rows)</div>;
  }
  const cols = Object.keys(rows[0]);
  return (
    <div className="overflow-x-auto" data-testid="rc-table">
      <table className="min-w-full text-sm border border-gray-200">
        <thead className="bg-gray-50">
          <tr>
            {cols.map((c) => (
              <th key={c} className="text-left px-2 py-1 border-b border-gray-200 font-medium">
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.slice(0, 20).map((row, i) => (
            <tr key={i} className={i % 2 ? 'bg-gray-50' : ''}>
              {cols.map((c) => (
                <td key={c} className="px-2 py-1 border-b border-gray-100">
                  {truncate(row[c])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length > 20 && (
        <div className="text-xs text-gray-500 mt-1">…{rows.length - 20} more rows</div>
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
        <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          {Object.entries(contract.data).map(([k, v]) => (
            <div key={k} className="contents">
              <dt className="font-medium text-gray-700">{k}</dt>
              <dd className="text-gray-900">{truncate(v, 200)}</dd>
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
    <div className="rounded border border-gray-200 p-3 bg-white" data-testid="rc-card">
      {contract.textSummary && (
        <div className="font-medium text-sm mb-2">{contract.textSummary}</div>
      )}
      {entries.length > 0 && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          {entries.map(([k, v]) => (
            <div key={k} className="contents">
              <dt className="text-gray-600">{k}</dt>
              <dd className="text-gray-900">{truncate(v, 120)}</dd>
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
    <ol className="relative border-l border-gray-200 pl-4 space-y-2" data-testid="rc-timeline">
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
    <pre className="text-xs bg-gray-50 border border-gray-200 rounded p-2 overflow-x-auto" data-testid="rc-json">
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
  const statusStyle = STATUS_STYLE[contract.status] ?? STATUS_STYLE.unknown;
  const { color, Icon } = statusStyle;

  return (
    <div className="space-y-2" data-testid="result-contract">
      <div className="flex items-center gap-2 text-xs text-gray-600">
        <Icon className={`w-4 h-4 ${color}`} />
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
            <Clock className="w-3 h-3" />
            <span>{contract.durationMs}ms</span>
          </>
        )}
      </div>

      {contract.renderHint === 'table' || contract.renderHint === 'chart_table'
        ? <TableView rows={resolveTableRows(contract)} />
        : contract.renderHint === 'summary'
          ? <SummaryView contract={contract} />
          : contract.renderHint === 'card'
            ? <CardView contract={contract} />
            : contract.renderHint === 'timeline'
              ? <TimelineView contract={contract} />
              : contract.textSummary
                ? <SummaryView contract={contract} />
                : <JsonFallback contract={contract} />
      }

      {contract.suggestedActions && contract.suggestedActions.length > 0 && (
        <div className="flex flex-wrap gap-1 pt-1" data-testid="rc-suggested-actions">
          {contract.suggestedActions.map((a, i) => (
            <span
              key={i}
              className="inline-block text-xs px-2 py-0.5 rounded-full bg-blue-50 text-blue-700 border border-blue-200"
            >
              {a.label}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
