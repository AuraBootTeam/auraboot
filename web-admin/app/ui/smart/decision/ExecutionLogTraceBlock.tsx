import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type DecisionApi,
  type DecisionLogFilters,
  type DecisionLogRecord,
  type HttpClient,
} from '~/shared/decision/api/decisionApi';

interface ExecutionLogTraceBlockProps {
  block?: {
    props?: ExecutionLogTraceProps;
    mode?: ExecutionLogTraceProps['mode'];
  };
  runtime?: {
    getContext?: () => {
      record?: Record<string, unknown>;
      row?: Record<string, unknown>;
      data?: Record<string, unknown>;
    };
  };
}

interface ExecutionLogTraceProps {
  mode?: 'list' | 'detail';
  initialDecisionCode?: string;
  initialKeyword?: string;
  pageSize?: number;
}

type MatchedFilter = 'ALL' | 'true' | 'false';

type FilterState = {
  keyword: string;
  decisionCode: string;
  status: string;
  callerType: string;
  matched: MatchedFilter;
  rolloutArm: string;
  minDurationMs: string;
  maxDurationMs: string;
};

const STATUS_OPTIONS = ['ALL', 'MATCHED', 'NOT_MATCHED', 'ERROR', 'SKIPPED', 'UNKNOWN'];
const CALLER_OPTIONS = ['ALL', 'API', 'AUTOMATION', 'EVENT_POLICY', 'SLA', 'BPM', 'TEST'];
const ROLLOUT_OPTIONS = ['ALL', 'BASELINE', 'CANDIDATE'];

function createApi(): DecisionApi {
  const service = getApiService();
  const http: HttpClient = {
    get: <T,>(endpoint: string, params?: Record<string, unknown>) =>
      service.get<T>(endpoint, params),
    post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
    delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
  };
  return createDecisionApi(http);
}

function recordFromRuntime(runtime: ExecutionLogTraceBlockProps['runtime']) {
  const context = runtime?.getContext?.();
  return context?.record ?? context?.row ?? context?.data ?? {};
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function pidFromPath(pathname: string): string | undefined {
  const match = pathname.match(/\/p\/decisionops_execution_logs\/view\/([^/?#]+)/);
  return match?.[1] ? decodeURIComponent(match[1]) : undefined;
}

function filtersFromSearch(
  search: string,
  props: ExecutionLogTraceProps,
  record: Record<string, unknown>,
): FilterState {
  const params = new URLSearchParams(search);
  const traceId = params.get('traceId') ?? undefined;
  const policyCode = params.get('policyCode') ?? undefined;
  const keyword =
    stringValue(record.traceId) ??
    traceId ??
    policyCode ??
    props.initialKeyword ??
    '';
  return {
    keyword,
    decisionCode:
      stringValue(record.decisionCode) ??
      params.get('decisionCode') ??
      props.initialDecisionCode ??
      '',
    status: params.get('status') ?? 'ALL',
    callerType: params.get('callerType') ?? 'ALL',
    matched: (params.get('matched') as MatchedFilter | null) ?? 'ALL',
    rolloutArm: params.get('rolloutArm') ?? 'ALL',
    minDurationMs: params.get('minDurationMs') ?? '',
    maxDurationMs: params.get('maxDurationMs') ?? '',
  };
}

function toNumberOrEmpty(value: string): number | '' {
  if (!value.trim()) return '';
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : '';
}

function apiFilters(filters: FilterState, pageSize: number): DecisionLogFilters {
  return {
    keyword: filters.keyword.trim(),
    decisionCode: filters.decisionCode.trim(),
    status: filters.status === 'ALL' ? '' : filters.status,
    callerType: filters.callerType === 'ALL' ? '' : filters.callerType,
    matched: filters.matched === 'ALL' ? '' : filters.matched === 'true',
    rolloutArm: filters.rolloutArm === 'ALL' ? '' : filters.rolloutArm,
    minDurationMs: toNumberOrEmpty(filters.minDurationMs),
    maxDurationMs: toNumberOrEmpty(filters.maxDurationMs),
    page: 0,
    size: pageSize,
  };
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  return '日志加载失败';
}

function formatDate(value?: string): string {
  if (!value) return '-';
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').replace(/Z$/, '');
}

function display(value: unknown): string {
  if (value === undefined || value === null || value === '') return '-';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  return String(value);
}

function cellText(value: unknown, className?: string) {
  return <div className={`elta-cell-text${className ? ` ${className}` : ''}`}>{display(value)}</div>;
}

function matchedRuleLabels(raw: unknown): string[] {
  if (!raw) return [];
  if (Array.isArray(raw)) {
    return raw
      .map((item) => {
        if (typeof item === 'string') return item;
        if (!item || typeof item !== 'object') return null;
        const row = item as Record<string, unknown>;
        const candidate = row.ruleId ?? row.ruleCode ?? row.id ?? row.name ?? row.reason;
        return typeof candidate === 'string' && candidate.trim() ? candidate : null;
      })
      .filter((item): item is string => Boolean(item));
  }
  if (typeof raw === 'object') {
    const obj = raw as Record<string, unknown>;
    if (Array.isArray(obj.rules)) return matchedRuleLabels(obj.rules);
    if (Array.isArray(obj.matchedRules)) return matchedRuleLabels(obj.matchedRules);
  }
  return typeof raw === 'string' && raw.trim() ? [raw] : [];
}

function sortedTrace(records: DecisionLogRecord[]) {
  return [...records].sort((left, right) =>
    String(left.createdAt ?? '').localeCompare(String(right.createdAt ?? '')),
  );
}

export function ExecutionLogTraceBlock({ block, runtime }: ExecutionLogTraceBlockProps) {
  const api = useMemo(() => createApi(), []);
  const location = useLocation();
  const navigate = useNavigate();
  const params = useParams();
  const props = block?.props ?? {};
  const mode = props.mode ?? block?.mode ?? 'list';
  const pageSize = props.pageSize ?? 50;
  const record = useMemo(() => recordFromRuntime(runtime), [runtime]);
  const routePid =
    stringValue(record.pid) ?? stringValue(params.recordId) ?? pidFromPath(location.pathname);
  const initialFilters = useMemo(
    () => filtersFromSearch(location.search, props, record),
    [location.search, props.initialDecisionCode, props.initialKeyword, record],
  );
  const [filters, setFilters] = useState<FilterState>(initialFilters);
  const [records, setRecords] = useState<DecisionLogRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [selectedLog, setSelectedLog] = useState<DecisionLogRecord | null>(null);
  const [traceRecords, setTraceRecords] = useState<DecisionLogRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [traceLoading, setTraceLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    setFilters(initialFilters);
  }, [initialFilters]);

  const loadTrace = useCallback(
    async (log: DecisionLogRecord) => {
      setSelectedLog(log);
      setTraceLoading(true);
      setError('');
      try {
        const traceId = stringValue(log.traceId);
        const chain = traceId ? await api.getLogs(traceId) : [];
        setTraceRecords(chain.length ? sortedTrace(chain) : [log]);
      } catch (e) {
        setError(errorMessage(e));
        setTraceRecords([log]);
      } finally {
        setTraceLoading(false);
      }
    },
    [api],
  );

  const loadRecent = useCallback(
    async (nextFilters: FilterState) => {
      setLoading(true);
      setError('');
      try {
        const page = await api.getRecentLogs(apiFilters(nextFilters, pageSize));
        setRecords(page.records ?? []);
        setTotal(Number(page.total ?? page.records?.length ?? 0));
      } catch (e) {
        setError(errorMessage(e));
        setRecords([]);
        setTotal(0);
      } finally {
        setLoading(false);
      }
    },
    [api, pageSize],
  );

  useEffect(() => {
    if (mode !== 'list') return;
    void loadRecent(initialFilters);
  }, [initialFilters, loadRecent, mode]);

  useEffect(() => {
    if (mode !== 'detail' || !routePid) return;
    setLoading(true);
    setError('');
    api
      .getLogByPid(routePid)
      .then((log) => {
        setRecords([log]);
        return loadTrace(log);
      })
      .catch((e) => {
        setError(errorMessage(e));
        setRecords([]);
        setSelectedLog(null);
        setTraceRecords([]);
      })
      .finally(() => setLoading(false));
  }, [api, loadTrace, mode, routePid]);

  const updateFilter = (field: keyof FilterState, value: string) => {
    setFilters((current) => ({ ...current, [field]: value }));
  };

  const applyFilters = () => {
    void loadRecent(filters);
  };

  const resetFilters = () => {
    const next = filtersFromSearch('', props, {});
    setFilters(next);
    void loadRecent(next);
  };

  const openDetail = (log: DecisionLogRecord) => {
    if (!log.pid) return;
    navigate(`/p/decisionops_execution_logs/view/${encodeURIComponent(log.pid)}`);
  };

  return (
    <section className="execution-log-trace-block" data-testid="execution-log-trace-block">
      {mode === 'list' && (
        <div className="elta-filters" data-testid="elta-filters">
          <label>
            <span>关键词</span>
            <input
              aria-label="log-keyword"
              value={filters.keyword}
              onChange={(e) => updateFilter('keyword', e.target.value)}
              placeholder="trace / caller / error"
            />
          </label>
          <label>
            <span>决策编码</span>
            <input
              aria-label="log-decision-code"
              value={filters.decisionCode}
              onChange={(e) => updateFilter('decisionCode', e.target.value)}
              placeholder="decisionCode"
            />
          </label>
          <label>
            <span>状态</span>
            <select
              aria-label="log-status"
              value={filters.status}
              onChange={(e) => updateFilter('status', e.target.value)}
            >
              {STATUS_OPTIONS.map((option) => (
                <option key={option} value={option}>{option}</option>
              ))}
            </select>
          </label>
          <label>
            <span>调用方</span>
            <select
              aria-label="log-caller-type"
              value={filters.callerType}
              onChange={(e) => updateFilter('callerType', e.target.value)}
            >
              {CALLER_OPTIONS.map((option) => (
                <option key={option} value={option}>{option}</option>
              ))}
            </select>
          </label>
          <label>
            <span>命中</span>
            <select
              aria-label="log-matched"
              value={filters.matched}
              onChange={(e) => updateFilter('matched', e.target.value)}
            >
              <option value="ALL">ALL</option>
              <option value="true">true</option>
              <option value="false">false</option>
            </select>
          </label>
          <label>
            <span>灰度分支</span>
            <select
              aria-label="log-rollout-arm"
              value={filters.rolloutArm}
              onChange={(e) => updateFilter('rolloutArm', e.target.value)}
            >
              {ROLLOUT_OPTIONS.map((option) => (
                <option key={option} value={option}>{option}</option>
              ))}
            </select>
          </label>
          <label>
            <span>最小耗时</span>
            <input
              aria-label="log-min-duration"
              type="number"
              min="0"
              value={filters.minDurationMs}
              onChange={(e) => updateFilter('minDurationMs', e.target.value)}
            />
          </label>
          <label>
            <span>最大耗时</span>
            <input
              aria-label="log-max-duration"
              type="number"
              min="0"
              value={filters.maxDurationMs}
              onChange={(e) => updateFilter('maxDurationMs', e.target.value)}
            />
          </label>
          <button type="button" data-testid="elta-apply" onClick={applyFilters} disabled={loading}>
            查询
          </button>
          <button type="button" data-testid="elta-reset" onClick={resetFilters} disabled={loading}>
            重置
          </button>
        </div>
      )}

      <div className="elta-summary">
        <strong>{mode === 'detail' ? 'Trace Chain' : 'Execution Logs'}</strong>
        <span data-testid="elta-count">{loading ? '加载中...' : `${records.length}/${total}`}</span>
        {error ? <span className="elta-error" data-testid="elta-error">{error}</span> : null}
      </div>

      {mode === 'list' && (
        <div className="elta-table-wrap">
          <table className="elta-table">
            <colgroup>
              <col className="elta-col-trace" />
              <col className="elta-col-decision" />
              <col className="elta-col-version" />
              <col className="elta-col-status" />
              <col className="elta-col-caller" />
              <col className="elta-col-rollout" />
              <col className="elta-col-duration" />
              <col className="elta-col-time" />
              <col className="elta-col-actions" />
            </colgroup>
            <thead>
              <tr>
                <th>Trace ID</th>
                <th>决策</th>
                <th>版本</th>
                <th>状态</th>
                <th>调用方</th>
                <th>灰度</th>
                <th>耗时</th>
                <th>时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {records.map((log) => (
                <tr key={log.pid ?? log.traceId} data-testid={`elta-row-${log.pid ?? log.traceId}`}>
                  <td className="mono">{cellText(log.traceId, 'mono')}</td>
                  <td>{cellText(log.decisionCode)}</td>
                  <td>{cellText(log.selectedVersion ?? log.decisionVersion)}</td>
                  <td><span className={`elta-status elta-status-${log.status ?? 'UNKNOWN'}`}>{display(log.status)}</span></td>
                  <td>{cellText(`${display(log.callerType)} / ${display(log.callerRef)}`)}</td>
                  <td>{cellText(`${display(log.rolloutArm)}${log.rolloutBucket != null ? ` #${log.rolloutBucket}` : ''}`)}</td>
                  <td>{cellText(log.durationMs != null ? `${log.durationMs}ms` : '-')}</td>
                  <td>{cellText(formatDate(log.createdAt))}</td>
                  <td className="elta-row-actions">
                    <button type="button" data-testid={`elta-open-trace-${log.pid ?? log.traceId}`} onClick={() => void loadTrace(log)}>
                      Trace
                    </button>
                    <button type="button" data-testid={`elta-open-detail-${log.pid ?? log.traceId}`} onClick={() => openDetail(log)} disabled={!log.pid}>
                      详情
                    </button>
                  </td>
                </tr>
              ))}
              {!records.length && !loading ? (
                <tr>
                  <td colSpan={9} data-testid="elta-empty">无匹配日志</td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      )}

      {selectedLog ? (
        <aside className="elta-drawer" role="dialog" aria-label="Trace Chain" data-testid="elta-trace-drawer">
          <div className="elta-drawer-head">
            <div>
              <h3>Trace Chain</h3>
              <span className="mono">{display(selectedLog.traceId)}</span>
            </div>
            <button type="button" data-testid="elta-close-trace" onClick={() => setSelectedLog(null)}>
              关闭
            </button>
          </div>
          <div className="elta-drawer-meta">
            <span>决策 {display(selectedLog.decisionCode)}</span>
            <span>状态 {display(selectedLog.status)}</span>
            <span>调用方 {display(selectedLog.callerType)} / {display(selectedLog.callerRef)}</span>
            <span>耗时 {selectedLog.durationMs != null ? `${selectedLog.durationMs}ms` : '-'}</span>
          </div>
          {traceLoading ? <div data-testid="elta-trace-loading">Trace 加载中...</div> : null}
          <ol className="elta-chain" data-testid="elta-trace-chain">
            {traceRecords.map((log, index) => (
              <li
                key={log.pid ?? `${log.traceId}-${index}`}
                className={log.pid && log.pid === selectedLog.pid ? 'elta-chain-current' : ''}
                data-testid={`elta-chain-node-${log.pid ?? index}`}
              >
                <div className="elta-chain-main">
                  <strong>{display(log.decisionCode)}</strong>
                  <span className={`elta-status elta-status-${log.status ?? 'UNKNOWN'}`}>{display(log.status)}</span>
                </div>
                <div className="elta-chain-sub">
                  <span>v{display(log.selectedVersion ?? log.decisionVersion)}</span>
                  <span>{display(log.runtimeAdapter)}</span>
                  <span>{log.durationMs != null ? `${log.durationMs}ms` : '-'}</span>
                  <span>{formatDate(log.createdAt)}</span>
                </div>
                <div className="elta-chain-rules">
                  命中规则: {matchedRuleLabels(log.matchedRulesJson).join(', ') || '-'}
                </div>
                {log.errorMessage ? <div className="elta-chain-error">{log.errorMessage}</div> : null}
              </li>
            ))}
          </ol>
        </aside>
      ) : null}
    </section>
  );
}

export default ExecutionLogTraceBlock;
