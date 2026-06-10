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

function metricSummary(label: string, arm: DecisionRolloutArmMetrics, testId: string) {
  const distribution = distributionEntries(arm);

  return (
    <div className="rollout-metric-card" data-testid={testId}>
      <span>{label}</span>
      <strong>v{arm.version ?? '-'}</strong>
      <small>Eval {arm.evaluations}</small>
      <small>Match {formatRate(arm.matchedRate)}</small>
      <small>Error {formatRate(arm.errorRate)}</small>
      {arm.p95LatencyMs != null && <small>P95 {arm.p95LatencyMs}ms</small>}
      {distribution.length > 0 && (
        <div className="rollout-distribution" data-testid={`${testId}-distribution`}>
          <small className="rollout-distribution-title">Distribution</small>
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
  const [decisionCode, setDecisionCode] = useState(initialDecisionCode);
  const [baselineVersion, setBaselineVersion] = useState(String(initialBaselineVersion));
  const [candidateVersion, setCandidateVersion] = useState(String(initialCandidateVersion));
  const [percentage, setPercentage] = useState('10');
  const [routingKeyExpr, setRoutingKeyExpr] = useState('traceId');
  const [cohortRoutingKeys, setCohortRoutingKeys] = useState('');
  const [cohortTracePrefixes, setCohortTracePrefixes] = useState('');
  const [tenantSegments, setTenantSegments] = useState('');
  const [salt, setSalt] = useState('decision-rollout');
  const [note, setNote] = useState('');
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [selectedPid, setSelectedPid] = useState<string | null>(null);
  const [busyPid, setBusyPid] = useState<string | null>(null);
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
    queryKey: ['decision-rollout-metrics', activePid],
    queryFn: () => api.getRolloutMetrics(activePid ?? ''),
    enabled: Boolean(activePid),
  });

  const refresh = async () => {
    await rolloutsQuery.refetch();
    await metricsQuery.refetch();
  };

  const createRollout = async () => {
    setMessage(null);
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
  };

  const runAction = async (
    pid: string,
    label: 'activate' | 'pause' | 'promote' | 'rollback',
    action: (pid: string, req?: DecisionRolloutActionRequest) => Promise<DecisionRollout>,
  ) => {
    setBusyPid(pid);
    setMessage(null);
    try {
      await action(pid, { note: note.trim() || undefined });
      setSelectedPid(pid);
      setPending(null);
      setNote('');
      setMessage(`已执行 ${label}`);
      await refresh();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : `执行 ${label} 失败`);
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
      <div className="decisionops-toolbar rollout-toolbar">
        <label>
          决策编码
          <input
            className="decisionops-search-input"
            aria-label="rollout-decision-code"
            value={decisionCode}
            onChange={(event) => {
              setDecisionCode(event.target.value);
              setSelectedPid(null);
            }}
          />
        </label>
        <label>
          Baseline
          <input
            aria-label="rollout-baseline-version"
            type="number"
            min="1"
            value={baselineVersion}
            onChange={(event) => setBaselineVersion(event.target.value)}
          />
        </label>
        <label>
          Candidate
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
        <label>
          Routing key
          <input
            aria-label="rollout-routing-key"
            value={routingKeyExpr}
            onChange={(event) => setRoutingKeyExpr(event.target.value)}
          />
        </label>
        <label>
          Cohort keys
          <input
            aria-label="rollout-cohort-routing-keys"
            placeholder="record-1, record-2"
            value={cohortRoutingKeys}
            onChange={(event) => setCohortRoutingKeys(event.target.value)}
          />
        </label>
        <label>
          Trace prefix
          <input
            aria-label="rollout-cohort-trace-prefixes"
            placeholder="vip-, beta-"
            value={cohortTracePrefixes}
            onChange={(event) => setCohortTracePrefixes(event.target.value)}
          />
        </label>
        <label>
          Tenant segments
          <input
            aria-label="rollout-tenant-segments"
            placeholder="early, beta"
            value={tenantSegments}
            onChange={(event) => setTenantSegments(event.target.value)}
          />
        </label>
        <label>
          Salt
          <input
            aria-label="rollout-salt"
            value={salt}
            onChange={(event) => setSalt(event.target.value)}
          />
        </label>
        <button
          className="decisionops-primary-button"
          data-testid="rollout-create"
          type="button"
          title={canManageRollouts ? undefined : '缺少灰度管理权限'}
          disabled={!normalizedCode || !canManageRollouts}
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
        </div>
      )}

      {rolloutsQuery.isLoading && (
        <div className="decisionops-state" data-testid="rollout-loading">
          加载中...
        </div>
      )}
      {rolloutsQuery.isError && (
        <div className="decisionops-state is-error" data-testid="rollout-error">
          灰度策略加载失败
        </div>
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
                  <th>流量</th>
                  <th>Routing</th>
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
                      <strong>{rollout.decisionCode ?? normalizedCode}</strong>
                      <code className="decisionops-code">{rollout.pid}</code>
                    </td>
                    <td>
                      {`v${rollout.baselineVersion ?? '-'} -> v${rollout.candidateVersion ?? '-'}`}
                    </td>
                    <td>
                      <span className={`decisionops-badge ${statusTone(rollout.status)}`}>
                        {rollout.status ?? 'UNKNOWN'}
                      </span>
                    </td>
                    <td>{rollout.percentage ?? 0}%</td>
                    <td>
                      <span>{rollout.routingKeyExpr ?? 'traceId'}</span>
                      {rolloutAudienceSummary(rollout) && (
                        <small className="rollout-routing-meta">
                          {rolloutAudienceSummary(rollout)}
                        </small>
                      )}
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
                <div className="rollout-metrics-grid">
                  {metricSummary(
                    'Baseline',
                    metricsQuery.data.baseline,
                    'rollout-metrics-baseline',
                  )}
                  {metricSummary(
                    'Candidate',
                    metricsQuery.data.candidate,
                    'rollout-metrics-candidate',
                  )}
                </div>
                {windowTrend(metricsQuery.data.windows)}
              </div>
            ) : (
              <div className="decisionops-empty" data-testid="rollout-metrics-empty">
                选择灰度策略后展示指标
              </div>
            )}

            {pending && (
              <div className="rollout-confirm-panel" data-testid="rollout-confirm-panel">
                <h3>确认 {pending.action}</h3>
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
                        <strong>{entry.action ?? 'AUDIT'}</strong>
                        {entry.note && <p>{entry.note}</p>}
                        <dl>
                          {entry.by && (
                            <div>
                              <dt>By</dt>
                              <dd>{entry.by}</dd>
                            </div>
                          )}
                          {entry.at && (
                            <div>
                              <dt>At</dt>
                              <dd>{entry.at}</dd>
                            </div>
                          )}
                        </dl>
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
