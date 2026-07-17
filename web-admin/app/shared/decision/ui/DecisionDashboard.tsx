/**
 * DecisionOps overview dashboard (mockup 概览 / F1, docs/1.md §22): KPI cards (definitions, policies,
 * today's evaluations, match rate, failures, p95 latency) + an exception queue (recent failed /
 * retrying executions needing attention). Pure presentation from a supplied summary; the caller
 * fetches it. Match rate / failure formatting is derived here so the numbers stay consistent.
 */

import { decisionStatusLabel } from './statusLabels';

export interface DashboardSummary {
  definitions: number;
  policies: number;
  evaluationsToday: number;
  matched: number;
  failed: number;
  retrying: number;
  p95LatencyMs?: number;
}

export interface ExceptionItem {
  traceId: string;
  code: string;
  status: 'FAILED' | 'FAILED_RETRYING' | 'ERROR';
  error?: string;
  time?: string;
}

export interface DecisionDashboardProps {
  summary: DashboardSummary;
  exceptions: ExceptionItem[];
}

function matchRate(s: DashboardSummary): string {
  if (!s.evaluationsToday) return '—';
  return `${Math.round((s.matched / s.evaluationsToday) * 1000) / 10}%`;
}

export function DecisionDashboard({ summary, exceptions }: DecisionDashboardProps) {
  const cards: { key: string; label: string; value: string }[] = [
    { key: 'definitions', label: '决策定义', value: String(summary.definitions) },
    { key: 'policies', label: '事件策略', value: String(summary.policies) },
    { key: 'evaluations', label: '今日评估', value: String(summary.evaluationsToday) },
    { key: 'match-rate', label: '命中率', value: matchRate(summary) },
    { key: 'failed', label: '失败', value: String(summary.failed + summary.retrying) },
    { key: 'p95', label: 'P95 延迟', value: summary.p95LatencyMs != null ? `${summary.p95LatencyMs}ms` : '—' },
  ];

  return (
    <div data-testid="decision-dashboard">
      <div className="dd-cards">
        {cards.map((c) => (
          <div key={c.key} className="dd-card" data-testid={`dd-card-${c.key}`}>
            <div className="dd-card-value">{c.value}</div>
            <div className="dd-card-label">{c.label}</div>
          </div>
        ))}
      </div>

      <div className="dd-exceptions">
        <h4>异常队列</h4>
        {exceptions.length === 0 ? (
          <div data-testid="dd-exceptions-empty">无异常</div>
        ) : (
          <ul data-testid="dd-exceptions-list">
            {exceptions.map((e) => (
              <li key={e.traceId} data-testid={`dd-exc-${e.traceId}`} data-status={e.status}>
                <span className="mono">{e.code}</span>
                <span className={`dd-exc-status dd-${e.status}`} title={e.status}>
                  {decisionStatusLabel(e.status)}
                </span>
                {e.error && <span className="dd-exc-error">{e.error}</span>}
                {e.time && <span className="dd-exc-time">{e.time}</span>}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

export default DecisionDashboard;
