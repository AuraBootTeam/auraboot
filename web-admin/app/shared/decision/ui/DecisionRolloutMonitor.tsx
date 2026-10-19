import { useTimezone } from '~/contexts/TimezoneContext';
import { formatInTimezone } from '~/shared/services/dateTimeFormatService';
import { useI18n } from '~/contexts/I18nContext';
import { diagnosticText } from './diagnosticsCopy';
import { DecisionLoadError } from './DecisionLoadError';
import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { usePermissions } from '~/contexts/AuthContext';
import type {
  DecisionApi,
  DecisionRollout,
  DecisionRolloutActionRequest,
  DecisionRolloutArmMetrics,
  DecisionRolloutWindowMetrics,
} from '../api/decisionApi';

export interface DecisionRolloutMonitorProps {
  api: DecisionApi;
  initialDecisionCode?: string;
  initialBaselineVersion?: number | string;
  initialCandidateVersion?: number | string;
  hasPermission?: (permissionCode: string) => boolean;
}

type ConfirmAction = 'promote' | 'rollback';

interface PendingAction {
  pid: string;
  action: ConfirmAction;
}

interface RolloutAuditEntry {
  action?: string;
  note?: string;
  by?: string;
  at?: string;
}

const DEFAULT_CODE = 'complaint_sla_deadline';
const ROLLOUT_MANAGE_PERMISSION = 'decision.rollout.manage';
const ROLLOUT_PROMOTE_PERMISSION = 'decision.rollout.promote';
const ROLLOUT_ROLLBACK_PERMISSION = 'decision.rollout.rollback';

function asNumber(value: string): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function splitCsv(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

function buildCohort(routingKeysValue: string, tracePrefixesValue: string) {
  const routingKeys = splitCsv(routingKeysValue);
  const traceIdPrefix = splitCsv(tracePrefixesValue);
  const cohort: { routingKeys?: string[]; traceIdPrefix?: string[] } = {};

  if (routingKeys.length > 0) {
    cohort.routingKeys = routingKeys;
  }
  if (traceIdPrefix.length > 0) {
    cohort.traceIdPrefix = traceIdPrefix;
  }

  return cohort.routingKeys || cohort.traceIdPrefix ? cohort : undefined;
}

function buildSegment(tenantSegmentsValue: string) {
  const tenantSegments = splitCsv(tenantSegmentsValue);
  return tenantSegments.length > 0 ? { tenantSegments } : undefined;
}

function formatRate(value?: number): string {
  return `${((value ?? 0) * 100).toFixed(1)}%`;
}

function distributionEntries(arm: DecisionRolloutArmMetrics): [string, number][] {
  return Object.entries(arm.resultDistribution ?? {})
    .sort((left, right) => right[1] - left[1])
    .slice(0, 4);
}

function windowLabel(value?: string): string {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

function maxWindowEvaluations(windows: DecisionRolloutWindowMetrics[]): number {
  return Math.max(
    1,
    ...windows.flatMap((window) => [
      window.baseline?.evaluations ?? 0,
      window.candidate?.evaluations ?? 0,
    ]),
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function stringField(source: Record<string, unknown>, key: string): string | undefined {
  const value = source[key];
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function arrayField(source: unknown, key: string): string[] {
  if (!isRecord(source)) return [];
  const value = source[key];
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
    : [];
}

function auditEntries(audit: unknown): RolloutAuditEntry[] {
  const values = Array.isArray(audit) ? audit : [audit];

  return values
    .filter(isRecord)
    .map((entry) => ({
      action: stringField(entry, 'action'),
      note: stringField(entry, 'note'),
      by: stringField(entry, 'by'),
      at: stringField(entry, 'at'),
    }))
    .filter((entry) => entry.action || entry.note || entry.by || entry.at);
}

function rolloutAudienceSummary(rollout: DecisionRollout): string {
  const parts: string[] = [];
  const routingKeys = arrayField(rollout.cohort, 'routingKeys');
  const tracePrefixes = arrayField(rollout.cohort, 'traceIdPrefix');
  const tenantSegments = arrayField(rollout.segment, 'tenantSegments');
  const singleTenantSegment = isRecord(rollout.segment)
    ? stringField(rollout.segment, 'tenantSegment')
    : undefined;

  if (routingKeys.length > 0) {
    parts.push(`Cohort keys ${routingKeys.length}`);
  }
  if (tracePrefixes.length > 0) {
    parts.push(`Prefixes ${tracePrefixes.length}`);
  }
  if (tenantSegments.length > 0) {
    parts.push(`Segments ${tenantSegments.length}`);
  } else if (singleTenantSegment) {
    parts.push(`Segment ${singleTenantSegment}`);
  }

  return parts.join(' · ');
}

function metricSummary(label: string, arm: DecisionRolloutArmMetrics, testId: string, locale: string) {
  const distribution = distributionEntries(arm);

  return (
    <div className="rollout-metric-card" data-testid={testId}>
      <span>{label}</span>
      <strong>v{arm.version ?? '-'}</strong>
      <small>{diagnosticText('eval', locale)} {arm.evaluations}</small>
      <small>{diagnosticText('match', locale)} {formatRate(arm.matchedRate)}</small>
      <small>{diagnosticText('error', locale)} {formatRate(arm.errorRate)}</small>
      {arm.p95LatencyMs != null && <small>P95 {arm.p95LatencyMs}ms</small>}
      {distribution.length > 0 && (
        <div className="rollout-distribution" data-testid={`${testId}-distribution`}>
          <small className="rollout-distribution-title">{diagnosticText('distribution', locale)}</small>
          <dl>
            {distribution.map(([key, count]) => (
              <div key={key}>
                <dt title={key}>{key}</dt>
                <dd>{count}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}
    </div>
  );
}

function windowTrend(windows: DecisionRolloutWindowMetrics[] = []) {
  const visible = windows.slice(-8);
  if (visible.length === 0) {
    return null;
  }
  const maxEvaluations = maxWindowEvaluations(visible);

  return (
    <div className="rollout-window-trend" data-testid="rollout-window-trend">
      <h4>窗口趋势</h4>
      <div className="rollout-window-list">
        {visible.map((window) => {
          const baselineEval = window.baseline?.evaluations ?? 0;
          const candidateEval = window.candidate?.evaluations ?? 0;
          const baselineWidth = `${Math.max(3, (baselineEval / maxEvaluations) * 100)}%`;
          const candidateWidth = `${Math.max(3, (candidateEval / maxEvaluations) * 100)}%`;

          return (
            <div className="rollout-window-row" key={window.windowStart ?? `${baselineEval}-${candidateEval}`}>
              <time>{windowLabel(window.windowStart)}</time>
              <div className="rollout-window-bars">
                <span
                  className="rollout-window-bar is-baseline"
                  style={{ width: baselineWidth }}
                  title={`Baseline ${baselineEval}`}
                />
                <span
                  className="rollout-window-bar is-candidate"
                  style={{ width: candidateWidth }}
                  title={`Candidate ${candidateEval}`}
                />
              </div>
              <strong>{baselineEval}/{candidateEval}</strong>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function metricsMeta(metrics: {
  windowHours?: number;
  bucketSeconds?: number;
  retentionDays?: number;
  source?: string;
  latencyAggregation?: string;
  refreshedAt?: string;
}) {
  const bucketMinutes =
    metrics.bucketSeconds && Number.isFinite(metrics.bucketSeconds)
      ? Math.round(metrics.bucketSeconds / 60)
      : undefined;
  const items = [
    metrics.source ? `Source ${metrics.source}` : null,
    metrics.windowHours ? `Window ${metrics.windowHours}h` : null,
    bucketMinutes ? `Bucket ${bucketMinutes}m` : null,
    metrics.retentionDays ? `Retention ${metrics.retentionDays}d` : null,
    metrics.latencyAggregation ? `Latency ${metrics.latencyAggregation}` : null,
    metrics.refreshedAt ? `Refreshed ${metrics.refreshedAt}` : null,
  ].filter((item): item is string => Boolean(item));

  return (
    <div className="rollout-metrics-meta" data-testid="rollout-metrics-meta">
      {items.map((item) => (
        <span key={item}>{item}</span>
      ))}
    </div>
  );
}

function statusTone(status?: string): string {
  if (status === 'ACTIVE' || status === 'PROMOTED') return 'is-success';
  if (status === 'PAUSED' || status === 'DRAFT') return 'is-warning';
  if (status === 'ROLLED_BACK') return 'is-danger';
  return 'is-neutral';
}

export function DecisionRolloutMonitor({
  api,
  initialDecisionCode = DEFAULT_CODE,
  initialBaselineVersion = '1',
  initialCandidateVersion = '2',
  hasPermission,
}: DecisionRolloutMonitorProps) {
  const { hasPermission: hasContextPermission } = usePermissions();
  const { locale } = useI18n();
  const { timezone, formats } = useTimezone();
  const text = (key: string) => diagnosticText(key, locale);
  const [decisionCode, setDecisionCode] = useState(initialDecisionCode);
  const [baselineVersion, setBaselineVersion] = useState(String(initialBaselineVersion));
  const [candidateVersion, setCandidateVersion] = useState(String(initialCandidateVersion));
  const [percentage, setPercentage] = useState('10');
  const [routingKeyExpr, setRoutingKeyExpr] = useState('traceId');
  const [cohortRoutingKeys, setCohortRoutingKeys] = useState('');
  const [cohortTracePrefixes, setCohortTracePrefixes] = useState('');
  const [tenantSegments, setTenantSegments] = useState('');
  const [salt, setSalt] = useState('decision-rollout');
  const [metricsWindowHours, setMetricsWindowHours] = useState('168');
  const [metricsBucketMinutes, setMetricsBucketMinutes] = useState('60');
  const [note, setNote] = useState('');
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [selectedPid, setSelectedPid] = useState<string | null>(null);
  const [busyPid, setBusyPid] = useState<string | null>(null);
  const [operationError, setOperationError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const canManageRollouts =
    hasPermission?.(ROLLOUT_MANAGE_PERMISSION) ?? hasContextPermission(ROLLOUT_MANAGE_PERMISSION);
  const canPromoteRollouts =
    hasPermission?.(ROLLOUT_PROMOTE_PERMISSION) ?? hasContextPermission(ROLLOUT_PROMOTE_PERMISSION);
  const canRollbackRollouts =
    hasPermission?.(ROLLOUT_ROLLBACK_PERMISSION) ??
    hasContextPermission(ROLLOUT_ROLLBACK_PERMISSION);
  const permissionWarnings = [
    canManageRollouts ? null : '缺少灰度管理权限',
    canPromoteRollouts ? null : '缺少全量发布权限',
    canRollbackRollouts ? null : '缺少灰度回滚权限',
  ].filter(Boolean);

  const normalizedCode = decisionCode.trim();
  const definitionsQuery = useQuery({
    queryKey: ['decision-rollout-definition-choices'],
    queryFn: () => api.listDefinitions(),
    enabled: typeof api.listDefinitions === 'function',
  });
  const definitionData = definitionsQuery.data;
  const definitionRows = Array.isArray(definitionData) ? definitionData : (definitionData as {records?:unknown[]})?.records ?? [];
  const choices = definitionRows.filter((row): row is {decisionCode:string;decisionName?:string} => Boolean(row && typeof row === 'object' && typeof (row as {decisionCode?:unknown}).decisionCode === 'string'));
  const ruleName = (code: string) => choices.find(row => row.decisionCode === code)?.decisionName || text('selectedRule');

  const rolloutsQuery = useQuery({
    queryKey: ['decision-rollouts', normalizedCode],
    queryFn: () => api.listRollouts(normalizedCode),
    enabled: Boolean(normalizedCode),
  });
  const rollouts = rolloutsQuery.data ?? [];
  const selectedRollout = useMemo(
    () => rollouts.find((rollout) => rollout.pid === selectedPid) ?? rollouts[0],
    [rollouts, selectedPid],
  );
  const selectedAuditEntries = useMemo(
    () => auditEntries(selectedRollout?.audit),
    [selectedRollout?.audit],
  );
  const activePid = selectedRollout?.pid;
  const metricsQuery = useQuery({
    queryKey: ['decision-rollout-metrics', activePid, metricsWindowHours, metricsBucketMinutes],
    queryFn: () =>
      api.getRolloutMetrics(activePid ?? '', {
        windowHours: Math.max(1, asNumber(metricsWindowHours)),
        bucketMinutes: Math.max(1, asNumber(metricsBucketMinutes)),
      }),
    enabled: Boolean(activePid),
  });

  const refresh = async () => {
    await rolloutsQuery.refetch();
    await metricsQuery.refetch();
  };

  const createRollout = async () => {
    setMessage(null); setOperationError(null);setCreating(true);
    try {
    const request = {
      baselineVersion: asNumber(baselineVersion),
      candidateVersion: asNumber(candidateVersion),
      percentage: Math.min(100, Math.max(0, asNumber(percentage))),
      routingKeyExpr: routingKeyExpr.trim() || undefined,
      salt: salt.trim() || undefined,
    };
    const cohort = buildCohort(cohortRoutingKeys, cohortTracePrefixes);
    const segment = buildSegment(tenantSegments);
    const created = await api.createRollout(normalizedCode, {
      ...request,
      ...(cohort ? { cohort } : {}),
      ...(segment ? { segment } : {}),
    });
    setSelectedPid(created.pid);
    setMessage('灰度策略已创建');
    await rolloutsQuery.refetch();
    } catch (error) {setMessage(text('operationFailed'));setOperationError(error instanceof Error ? error.message : String(error));}
    finally {setCreating(false);}
  };

  const runAction = async (
    pid: string,
    label: 'activate' | 'pause' | 'promote' | 'rollback',
    action: (pid: string, req?: DecisionRolloutActionRequest) => Promise<DecisionRollout>,
  ) => {
    setBusyPid(pid);
    setMessage(null); setOperationError(null);
    try {
      await action(pid, { note: note.trim() || undefined });
      setSelectedPid(pid);
      setPending(null);
      setNote('');
      setMessage(`${text('executed')} ${text(label)}`);
      await refresh();
    } catch (error) {
      setMessage(text('operationFailed'));setOperationError(error instanceof Error ? error.message : String(error));
    } finally {
      setBusyPid(null);
    }
  };

  const confirmPending = async () => {
    if (!pending) return;
    const action = pending.action === 'promote' ? api.promoteRollout : api.rollbackRollout;
    await runAction(pending.pid, pending.action, action);
  };

  return (
    <section
      className="decisionops-list-page rollout-monitor"
      data-testid="decision-rollout-monitor"
    >
      {definitionsQuery.isError && <DecisionLoadError testId="rollout-definitions-error" error={definitionsQuery.error} onRetry={definitionsQuery.refetch} busy={definitionsQuery.isFetching} />}
      <p className="text-sm text-muted-foreground mb-3">{text('rolloutHelp')}</p>
      <div className="decisionops-toolbar rollout-toolbar">
        <label>
          {text('ruleSelector')}
          <select aria-label="rollout-decision-code" disabled={definitionsQuery.isFetching || definitionsQuery.isError} value={decisionCode} onChange={event => {setDecisionCode(event.target.value);setSelectedPid(null);}}>
            {!choices.some(row => row.decisionCode === decisionCode) && <option value={decisionCode}>{text('selectedRule')}</option>}
            {choices.map(row => <option key={row.decisionCode} value={row.decisionCode}>{row.decisionName || text('rule')}</option>)}
          </select>
        </label>
        <label>
          {text('baseline')}
          <input
            aria-label="rollout-baseline-version"
            type="number"
            min="1"
            value={baselineVersion}
            onChange={(event) => setBaselineVersion(event.target.value)}
          />
        </label>
        <label>
          {text('candidate')}
          <input
            aria-label="rollout-candidate-version"
            type="number"
            min="1"
            value={candidateVersion}
            onChange={(event) => setCandidateVersion(event.target.value)}
          />
        </label>
        <label>
          百分比
          <input
            aria-label="rollout-percentage"
            type="number"
            min="0"
            max="100"
            value={percentage}
            onChange={(event) => setPercentage(event.target.value)}
          />
        </label>
        <details className="rollout-advanced" data-testid="rollout-advanced">
          <summary>{text('advanced')}</summary>
          <div className="rollout-advanced-fields">
        <label>
          {text('routing')}
          <input
            aria-label="rollout-routing-key"
            value={routingKeyExpr}
            onChange={(event) => setRoutingKeyExpr(event.target.value)}
          />
        </label>
        <label>
          {text('cohort')}
          <input
            aria-label="rollout-cohort-routing-keys"
            placeholder="record-1, record-2"
            value={cohortRoutingKeys}
            onChange={(event) => setCohortRoutingKeys(event.target.value)}
          />
        </label>
        <label>
          {text('prefix')}
          <input
            aria-label="rollout-cohort-trace-prefixes"
            placeholder="vip-, beta-"
            value={cohortTracePrefixes}
            onChange={(event) => setCohortTracePrefixes(event.target.value)}
          />
        </label>
        <label>
          {text('segments')}
          <input
            aria-label="rollout-tenant-segments"
            placeholder="early, beta"
            value={tenantSegments}
            onChange={(event) => setTenantSegments(event.target.value)}
          />
        </label>
        <label>
          {text('salt')}
          <input
            aria-label="rollout-salt"
            value={salt}
            onChange={(event) => setSalt(event.target.value)}
          />
        </label>
        <label>
          {text('window')}
          <input
            aria-label="rollout-metrics-window-hours"
            type="number"
            min="1"
            value={metricsWindowHours}
            onChange={(event) => setMetricsWindowHours(event.target.value)}
          />
        </label>
        <label>
          {text('bucket')}
          <input
            aria-label="rollout-metrics-bucket-minutes"
            type="number"
            min="5"
            value={metricsBucketMinutes}
            onChange={(event) => setMetricsBucketMinutes(event.target.value)}
          />
        </label>
          </div>
        </details>
        <button
          className="decisionops-primary-button"
          data-testid="rollout-create"
          type="button"
          title={canManageRollouts ? undefined : '缺少灰度管理权限'}
          disabled={!normalizedCode || !canManageRollouts || creating || definitionsQuery.isError || (typeof api.listDefinitions === 'function' && !choices.some(row => row.decisionCode === normalizedCode))}
          onClick={createRollout}
        >
          新建灰度
        </button>
      </div>

      {permissionWarnings.length > 0 && (
        <div className="rollout-permission-hint" data-testid="rollout-permission-hint">
          {permissionWarnings.join(' / ')}
        </div>
      )}

      {message && (
        <div className="decisionops-state rollout-message" data-testid="rollout-status-message">
          {message}
          {operationError && <details className="mt-2 text-xs"><summary>{text('technical')}</summary>{operationError}</details>}
        </div>
      )}

      {rolloutsQuery.isLoading && (
        <div className="decisionops-state" data-testid="rollout-loading">
          加载中...
        </div>
      )}
      {rolloutsQuery.isError && (
        <DecisionLoadError testId="rollout-error" error={rolloutsQuery.error} onRetry={rolloutsQuery.refetch} busy={rolloutsQuery.isFetching} />
      )}
      {!rolloutsQuery.isLoading && !rolloutsQuery.isError && rollouts.length === 0 && (
        <div className="decisionops-empty" data-testid="rollout-empty">
          暂无灰度策略
        </div>
      )}

      {rollouts.length > 0 && (
        <div className="rollout-grid">
          <div className="decisionops-table-frame">
            <table className="decisionops-table rollout-table">
              <thead>
                <tr>
                  <th>策略</th>
                  <th>版本</th>
                  <th>状态</th>
                  <th>{text('configuredShare')}</th>
                  <th>{text('routing')}</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {rollouts.map((rollout) => (
                  <tr
                    key={rollout.pid}
                    data-testid={`rollout-row-${rollout.pid}`}
                    data-selected={rollout.pid === selectedRollout?.pid}
                    onClick={() => setSelectedPid(rollout.pid)}
                  >
                    <td>
                      <strong>{ruleName(rollout.decisionCode ?? normalizedCode)}</strong>
                      <details className="text-xs"><summary>{text('technical')}</summary>{rollout.decisionCode} · {rollout.pid}</details>
                    </td>
                    <td>
                      {`v${rollout.baselineVersion ?? '-'} -> v${rollout.candidateVersion ?? '-'}`}
                    </td>
                    <td>
                      <span className={`decisionops-badge ${statusTone(rollout.status)}`}>
                        {text(rollout.status ?? 'UNKNOWN')}
                      </span>
                    </td>
                    <td>{rollout.percentage ?? 0}%</td>
                    <td>
                      <span>{['ACTIVE','PROMOTED'].includes(rollout.status ?? '') ? `${rollout.percentage ?? 0}% → v${rollout.candidateVersion ?? '-'}` : text('inactiveRouting')}</span>
                      <details className="text-xs"><summary>{text('technical')}</summary>{rollout.routingKeyExpr ?? 'traceId'} · {rolloutAudienceSummary(rollout)}</details>
                    </td>
                    <td>
                      <div className="decisionops-row-actions">
                        <button
                          type="button"
                          data-testid={`rollout-activate-${rollout.pid}`}
                          title={canManageRollouts ? undefined : '缺少灰度管理权限'}
                          disabled={busyPid === rollout.pid || !canManageRollouts}
                          onClick={(event) => {
                            event.stopPropagation();
                            void runAction(rollout.pid, 'activate', api.activateRollout);
                          }}
                        >
                          启用
                        </button>
                        <button
                          type="button"
                          data-testid={`rollout-pause-${rollout.pid}`}
                          title={canManageRollouts ? undefined : '缺少灰度管理权限'}
                          disabled={busyPid === rollout.pid || !canManageRollouts}
                          onClick={(event) => {
                            event.stopPropagation();
                            void runAction(rollout.pid, 'pause', api.pauseRollout);
                          }}
                        >
                          暂停
                        </button>
                        <button
                          type="button"
                          data-testid={`rollout-promote-${rollout.pid}`}
                          title={canPromoteRollouts ? undefined : '缺少全量发布权限'}
                          disabled={busyPid === rollout.pid || !canPromoteRollouts}
                          onClick={(event) => {
                            event.stopPropagation();
                            setPending({ pid: rollout.pid, action: 'promote' });
                          }}
                        >
                          全量
                        </button>
                        <button
                          type="button"
                          data-testid={`rollout-rollback-${rollout.pid}`}
                          title={canRollbackRollouts ? undefined : '缺少灰度回滚权限'}
                          disabled={busyPid === rollout.pid || !canRollbackRollouts}
                          onClick={(event) => {
                            event.stopPropagation();
                            setPending({ pid: rollout.pid, action: 'rollback' });
                          }}
                        >
                          回滚
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <aside className="rollout-side-panel">
            {metricsQuery.data ? (
              <div className="rollout-metrics" data-testid="rollout-metrics-panel">
                <h3>版本指标对比</h3>
                <details className="text-xs"><summary>{text('metricsDetails')}</summary>{metricsMeta(metricsQuery.data)}</details>
                <div className="rollout-metrics-grid">
                  {metricSummary(
                    text('baseline'),
                    metricsQuery.data.baseline,
                    'rollout-metrics-baseline', locale,
                  )}
                  {metricSummary(
                    text('candidate'),
                    metricsQuery.data.candidate,
                    'rollout-metrics-candidate', locale,
                  )}
                </div>
                {windowTrend(metricsQuery.data.windows)}
              </div>
            ) : metricsQuery.isError ? <DecisionLoadError testId="rollout-metrics-error" error={metricsQuery.error} onRetry={metricsQuery.refetch} busy={metricsQuery.isFetching} /> : (
              <div className="decisionops-empty" data-testid="rollout-metrics-empty">
                选择灰度策略后展示指标
              </div>
            )}

            {pending && (
              <div className="rollout-confirm-panel" data-testid="rollout-confirm-panel">
                <h3>{text(pending.action)}</h3>
                <p>该动作会改变生产版本流量,需要写入审计说明。</p>
                <label>
                  审计说明
                  <textarea
                    aria-label="rollout-action-note"
                    value={note}
                    onChange={(event) => setNote(event.target.value)}
                  />
                </label>
                <div className="decisionops-row-actions">
                  <button type="button" onClick={() => setPending(null)}>
                    取消
                  </button>
                  <button
                    className="decisionops-primary-button"
                    type="button"
                    data-testid="rollout-confirm-action"
                    onClick={confirmPending}
                  >
                    确认执行
                  </button>
                </div>
              </div>
            )}

            {selectedAuditEntries.length > 0 && (
              <div className="rollout-audit-panel" data-testid="rollout-audit-panel">
                <h3>审计时间线</h3>
                <ol className="rollout-audit-list">
                  {selectedAuditEntries.map((entry, index) => (
                    <li key={`${entry.action ?? 'audit'}-${entry.at ?? index}`}>
                      <div className="rollout-audit-entry">
                        <strong>{text(entry.action?.toLowerCase() ?? 'audit')}</strong>
                        {entry.note && <p>{entry.note}</p>}
                        <details><summary>{text('technical')}</summary><code>{entry.action}</code><dl>
                          {entry.by && (
                            <div>
                              <dt>{text('technical')}</dt>
                              <dd>{entry.by}</dd>
                            </div>
                          )}
                          {entry.at && (
                            <div>
                              <dt>{text('time')}</dt>
                              <dd>{entry.at}</dd>
                            </div>
                          )}
                        </dl></details>
                        {entry.at && <time>{formatInTimezone(entry.at, formats.datetime, timezone)}</time>}
                      </div>
                    </li>
                  ))}
                </ol>
              </div>
            )}
          </aside>
        </div>
      )}
    </section>
  );
}

export default DecisionRolloutMonitor;
