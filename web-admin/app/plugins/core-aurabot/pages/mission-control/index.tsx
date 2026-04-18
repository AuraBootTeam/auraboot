import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router';
import { get, post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';

// ============================================================================
// Types
// ============================================================================

type MCTab = 'dashboard' | 'analytics' | 'observations';

interface KpiData {
  active_tasks: number;
  running_now: number;
  pending_approvals: number;
  active_agents: number;
  active_missions: number;
  month_cost: number;
}

interface AgentStat {
  pid: string;
  agent_code: string;
  agent_name: string;
  agent_type: string;
  model: string;
  agent_status: string;
  total_runs: number;
  success_runs: number;
  success_rate: number;
  total_cost: number;
  avg_cost: number;
  last_run_at: string;
}

interface ObservationItem {
  pid: string;
  observation_type: string;
  severity: string;
  source: string;
  agent_id: string;
  title: string;
  detail: string;
  created_at: string;
}

interface ScheduleItem {
  pid: string;
  schedule_name: string;
  cron_expression: string;
  schedule_status: string;
  agent_id: string;
  last_run_at: string;
}

interface RunRecord {
  pid: string;
  run_status: string;
  model: string;
  started_at: string;
  completed_at: string;
  duration_ms: number;
  input_tokens: number;
  output_tokens: number;
  total_cost: number;
  error_message: string;
  task_title: string;
  agent_name: string;
}

// ============================================================================
// Helpers
// ============================================================================

function fetchNQ<T>(code: string, extra?: Record<string, string>): Promise<T[]> {
  return get<{ records: T[] }>('/api/datasource/list', {
    datasourceId: `nq:${code}`,
    format: 'records',
    ...extra,
  }).then((res) => (ResultHelper.isSuccess(res) && res.data?.records ? res.data.records : []));
}

/** Hook: subscribe to agent SSE events, returns a refresh counter that increments on each event */
function useAgentSse(): number {
  const [refreshKey, setRefreshKey] = useState(0);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    const es = new EventSource('/api/agent/events/stream');
    esRef.current = es;

    es.addEventListener('agent-event', () => {
      setRefreshKey((k) => k + 1);
    });

    es.onerror = () => {
      // Auto-reconnect handled by browser EventSource
    };

    return () => {
      es.close();
      esRef.current = null;
    };
  }, []);

  return refreshKey;
}

/** Hook: auto-refresh on interval + SSE events */
function useAutoRefresh(sseKey: number, intervalMs = 15000): number {
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const id = setInterval(() => setTick((t) => t + 1), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);

  // Combine SSE-driven refresh with interval
  return tick + sseKey;
}

function fmtCost(n: number): string {
  if (n >= 100) return `$${n.toFixed(0)}`;
  if (n >= 1) return `$${n.toFixed(2)}`;
  return `$${n.toFixed(4)}`;
}

function fmtDuration(ms: number): string {
  if (!ms) return '-';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`;
}

function timeAgo(dateStr: string, l: (zh: string, en: string) => string): string {
  if (!dateStr) return '-';
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return l('刚刚', 'just now');
  if (mins < 60) return `${mins}${l('分钟前', 'm ago')}`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}${l('小时前', 'h ago')}`;
  const days = Math.floor(hrs / 24);
  return `${days}${l('天前', 'd ago')}`;
}

// ============================================================================
// Main Component
// ============================================================================

export default function MissionControl() {
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);
  const navigate = useNavigate();

  const [activeTab, setActiveTab] = useState<MCTab>('dashboard');
  const sseKey = useAgentSse();
  const refreshKey = useAutoRefresh(sseKey);

  const tabs: { key: MCTab; label: { zh: string; en: string }; icon: string }[] = [
    { key: 'dashboard', label: { zh: '仪表盘', en: 'Dashboard' }, icon: '📊' },
    { key: 'analytics', label: { zh: '分析', en: 'Analytics' }, icon: '📈' },
    { key: 'observations', label: { zh: '事件日志', en: 'Events' }, icon: '📡' },
  ];

  return (
    <div className="flex h-full flex-col" data-testid="mission-control">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-4 dark:border-gray-700 dark:bg-gray-800">
        <div className="flex items-center gap-3">
          <span className="text-2xl">🎯</span>
          <h1 className="text-xl font-bold text-gray-900 dark:text-white">
            {l('AuraBot Dashboard', 'AuraBot Dashboard')}
          </h1>
          <LiveIndicator />
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/p/mission')}
            className="rounded-md bg-blue-50 px-3 py-1.5 text-sm text-blue-600 transition-colors hover:bg-blue-100 dark:bg-blue-900/30 dark:text-blue-400 dark:hover:bg-blue-900/50"
            data-testid="mc-view-missions"
          >
            {l('查看使命', 'View Missions')}
          </button>
          <button
            onClick={() => navigate('/p/agent-task')}
            className="rounded-md bg-gray-100 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
            data-testid="mc-view-all-tasks"
          >
            {l('全部任务', 'All Tasks')}
          </button>
        </div>
      </div>

      {/* Tab Navigation */}
      <div
        className="flex border-b border-gray-200 bg-white px-6 dark:border-gray-700 dark:bg-gray-800"
        data-testid="mc-tabs"
      >
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`flex items-center gap-1.5 border-b-2 px-4 py-3 text-sm font-medium transition-colors ${
              activeTab === tab.key
                ? 'border-blue-600 text-blue-600 dark:border-blue-400 dark:text-blue-400'
                : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
            }`}
            data-testid={`mc-tab-${tab.key}`}
          >
            <span className="text-sm">{tab.icon}</span>
            {l(tab.label.zh, tab.label.en)}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      <div className="flex-1 overflow-auto bg-gray-50 p-6 dark:bg-gray-900">
        {activeTab === 'dashboard' && (
          <DashboardView l={l} navigate={navigate} refreshKey={refreshKey} />
        )}
        {activeTab === 'analytics' && <AnalyticsView l={l} refreshKey={refreshKey} />}
        {activeTab === 'observations' && <ObservationDrillDownView l={l} refreshKey={refreshKey} />}
      </div>
    </div>
  );
}

// ============================================================================
// Dashboard View
// ============================================================================

function DashboardView({
  l,
  navigate,
  refreshKey,
}: {
  l: (zh: string, en: string) => string;
  navigate: ReturnType<typeof useNavigate>;
  refreshKey: number;
}) {
  const [kpi, setKpi] = useState<KpiData | null>(null);
  const [recentRuns, setRecentRuns] = useState<RunRecord[]>([]);
  const [schedules, setSchedules] = useState<ScheduleItem[]>([]);
  const [agents, setAgents] = useState<AgentStat[]>([]);
  const [triggeringSchedule, setTriggeringSchedule] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const loadSchedules = useCallback(() => {
    get<{ records: ScheduleItem[] }>('/api/dynamic/agent-schedule/list', {
      pageSize: '10',
      filters: JSON.stringify([{ fieldName: 'schedule_status', operator: 'EQ', value: 'active' }]),
    }).then((res) => {
      if (ResultHelper.isSuccess(res) && res.data?.records) setSchedules(res.data.records);
    });
  }, []);

  const handleTriggerSchedule = useCallback(
    async (schedulePid: string) => {
      setTriggeringSchedule(schedulePid);
      try {
        await post<unknown>(`/api/agent/schedule/${schedulePid}/trigger`, {});
        loadSchedules();
      } finally {
        setTriggeringSchedule(null);
      }
    },
    [loadSchedules],
  );

  useEffect(() => {
    const isInitial = refreshKey === 0;
    if (isInitial) setLoading(true);
    Promise.all([
      fetchNQ<KpiData>('acp_dashboard_kpi'),
      fetchNQ<RunRecord>('acp_recent_runs', { maxItems: '10' }),
      fetchNQ<AgentStat>('acp_agent_stats'),
    ])
      .then(([kpiData, runs, agentData]) => {
        if (kpiData.length) setKpi(kpiData[0]);
        setRecentRuns(runs);
        setAgents(agentData);
      })
      .finally(() => setLoading(false));
    loadSchedules();
  }, [refreshKey, loadSchedules]);

  if (loading) return <Spinner />;

  return (
    <div className="space-y-6" data-testid="mc-dashboard">
      {/* Cost Budget Alerts */}
      {kpi && kpi.month_cost > 100 && (
        <div
          className="flex items-center gap-2 rounded-lg border border-red-300 bg-red-100 px-4 py-3 text-sm font-medium text-red-700 dark:border-red-700 dark:bg-red-900/30 dark:text-red-300"
          data-testid="mc-cost-alert-over"
        >
          <span>!!</span>
          {l(
            `月度成本已超预算: $${kpi.month_cost.toFixed(2)}`,
            `Monthly cost exceeded budget: $${kpi.month_cost.toFixed(2)}`,
          )}
        </div>
      )}
      {kpi && kpi.month_cost > 80 && kpi.month_cost <= 100 && (
        <div
          className="flex items-center gap-2 rounded-lg border border-amber-300 bg-amber-100 px-4 py-3 text-sm font-medium text-amber-700 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-300"
          data-testid="mc-cost-alert-warning"
        >
          <span>!</span>
          {l(
            `月度成本接近上限: $${kpi.month_cost.toFixed(2)}`,
            `Monthly cost approaching limit: $${kpi.month_cost.toFixed(2)}`,
          )}
        </div>
      )}

      {/* KPI Cards */}
      <div
        className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-6"
        data-testid="mc-kpi-cards"
      >
        <KpiCard
          label={l('活跃使命', 'Active Missions')}
          value={String(kpi?.active_missions ?? 0)}
          color="blue"
          onClick={() => navigate('/p/mission')}
        />
        <KpiCard
          label={l('活跃任务', 'Active Tasks')}
          value={String(kpi?.active_tasks ?? 0)}
          color="indigo"
          onClick={() => navigate('/p/agent-task')}
        />
        <KpiCard
          label={l('运行中', 'Running Now')}
          value={String(kpi?.running_now ?? 0)}
          color={kpi?.running_now ? 'green' : 'blue'}
        />
        <KpiCard
          label={l('待审批', 'Pending Approvals')}
          value={String(kpi?.pending_approvals ?? 0)}
          color={kpi?.pending_approvals ? 'amber' : 'green'}
          onClick={() => navigate('/p/agent-approval')}
        />
        <KpiCard
          label={l('活跃 Agent', 'Active Agents')}
          value={String(kpi?.active_agents ?? 0)}
          color="emerald"
          onClick={() => navigate('/p/agent-definition')}
        />
        <KpiCard
          label={l('本月成本', 'Month Cost')}
          value={fmtCost(kpi?.month_cost ?? 0)}
          color={kpi?.month_cost && kpi.month_cost > 100 ? 'amber' : 'green'}
        />
      </div>

      {/* Agent KPI Summary */}
      {agents.length > 0 && (
        <div
          className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
          data-testid="mc-agent-kpi"
        >
          <div className="mb-4 flex items-center justify-between">
            <h3 className="text-sm font-medium text-gray-700 dark:text-gray-300">
              {l('Agent 概览', 'Agent Overview')}
            </h3>
            <button
              className="text-xs text-blue-600 hover:underline dark:text-blue-400"
              onClick={() => navigate('/p/agent-definition')}
              data-testid="mc-view-all-agents"
            >
              {l('查看全部 Agent', 'View All Agents')} &rarr;
            </button>
          </div>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
            {agents.map((agent) => (
              <div
                key={agent.pid}
                className="flex cursor-pointer items-center gap-3 rounded-lg border border-gray-200 p-3 transition-colors hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-gray-700/30"
                onClick={() => navigate(`/p/agent-definition/view/${agent.pid}`)}
                data-testid={`mc-agent-kpi-${agent.agent_code}`}
              >
                <span className="text-xl">🤖</span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="truncate text-sm font-medium text-gray-900 dark:text-white">
                      {agent.agent_name}
                    </span>
                    <span
                      className={`rounded-full px-1.5 py-0.5 text-xs ${
                        agent.agent_status === 'active'
                          ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300'
                          : 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400'
                      }`}
                    >
                      {agent.agent_status}
                    </span>
                  </div>
                  <div className="mt-1 flex items-center gap-3 text-xs text-gray-500 dark:text-gray-400">
                    <span
                      className={`font-medium ${agent.success_rate >= 90 ? 'text-green-600' : agent.success_rate >= 70 ? 'text-amber-600' : 'text-red-600'}`}
                    >
                      {agent.success_rate}% {l('成功', 'success')}
                    </span>
                    <span>
                      {agent.total_runs} {l('次运行', 'runs')}
                    </span>
                    <span>
                      {fmtCost(agent.avg_cost)} {l('平均', 'avg')}
                    </span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Activity Feed */}
      <div
        className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
        data-testid="mc-activity-feed"
      >
        <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
          {l('最近活动', 'Recent Activity')}
        </h3>
        {recentRuns.length === 0 ? (
          <EmptyState
            text={l(
              '暂无运行记录。创建 Agent 和任务后，活动将在此显示。',
              'No runs yet. Activity will appear here once agents start executing tasks.',
            )}
          />
        ) : (
          <div className="space-y-3">
            {recentRuns.map((run) => (
              <div
                key={run.pid}
                className="flex items-center gap-3 rounded-lg p-3 transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/30"
              >
                <RunStatusIcon status={run.run_status} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="truncate text-sm font-medium text-gray-900 dark:text-white">
                      {run.agent_name || run.pid.slice(0, 8)}
                    </span>
                    <span className="text-xs text-gray-500">&rarr;</span>
                    <span className="truncate text-sm text-gray-600 dark:text-gray-400">
                      {run.task_title || l('未关联任务', 'No task')}
                    </span>
                  </div>
                  <div className="mt-0.5 flex items-center gap-3 text-xs text-gray-500 dark:text-gray-400">
                    {run.model && <span>{run.model}</span>}
                    {run.duration_ms > 0 && <span>{fmtDuration(run.duration_ms)}</span>}
                    {run.total_cost > 0 && <span>{fmtCost(run.total_cost)}</span>}
                    {(run.input_tokens > 0 || run.output_tokens > 0) && (
                      <span>{(run.input_tokens + run.output_tokens).toLocaleString()} tokens</span>
                    )}
                  </div>
                </div>
                <span className="text-xs whitespace-nowrap text-gray-400">
                  {timeAgo(run.started_at, l)}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Quick Navigation */}
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4" data-testid="mc-quick-nav">
        <QuickLink
          icon="📋"
          title={l('任务', 'Tasks')}
          desc={l('查看和管理 Agent 任务', 'View and manage agent tasks')}
          onClick={() => navigate('/p/agent-task')}
        />
        <QuickLink
          icon="▶️"
          title={l('运行记录', 'Runs')}
          desc={l('查看 Agent 运行日志', 'View agent run logs')}
          onClick={() => navigate('/aurabot/runs')}
        />
        <QuickLink
          icon="🔍"
          title={l('AI 追踪', 'AI Traces')}
          desc={l('LLM 调用追踪和成本分析', 'LLM call traces and cost analysis')}
          onClick={() => navigate('/aurabot/traces')}
        />
        <QuickLink
          icon="🛡️"
          title={l('审批', 'Approvals')}
          desc={l('处理 Agent 审批请求', 'Handle agent approval requests')}
          onClick={() => navigate('/p/agent-approval')}
        />
        <QuickLink
          icon="🧠"
          title={l('记忆库', 'Memory')}
          desc={l('浏览 Agent 记忆数据', 'Browse agent memory data')}
          onClick={() => navigate('/p/agent-memory')}
        />
        <QuickLink
          icon="💡"
          title={l('技能草稿', 'Skill Drafts')}
          desc={l('审核 Learning Loop 产生的草稿', 'Review drafts from the learning loop')}
          onClick={() => navigate('/aurabot/learning-drafts')}
        />
        <QuickLink
          icon="⚠️"
          title={l('中断审计', 'Interrupts')}
          desc={l('查看分类器产出的中断记录', 'Inspect interrupt classifier output')}
          onClick={() => navigate('/aurabot/interrupts')}
        />
        <QuickLink
          icon="⬆️"
          title={l('记忆晋升', 'Memory Promotions')}
          desc={l('审核 user→tenant 记忆晋升提案', 'Review user→tenant memory promotion proposals')}
          onClick={() => navigate('/aurabot/memory-promotions')}
        />
      </div>

      {/* Quick Links to Config Pages */}
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4" data-testid="mc-quick-links">
        <QuickLink
          icon="🤖"
          title={l('Agent 定义', 'Agent Definitions')}
          desc={l('管理 Agent 配置', 'Manage agent configs')}
          onClick={() => navigate('/p/agent-definition')}
        />
        <QuickLink
          icon="📅"
          title={l('调度', 'Schedules')}
          desc={l('定时任务配置', 'Scheduled tasks')}
          onClick={() => navigate('/p/agent-schedule')}
        />
        <QuickLink
          icon="📦"
          title={l('产出物', 'Artifacts')}
          desc={l('Agent 生成内容', 'Agent outputs')}
          onClick={() => navigate('/p/agent-artifact')}
        />
        <QuickLink
          icon="🛡️"
          title={l('审批策略', 'Policies')}
          desc={l('审批规则配置', 'Approval rules')}
          onClick={() => navigate('/p/approval-policy')}
        />
      </div>

      {/* Active Schedules */}
      {schedules.length > 0 && (
        <div
          className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
          data-testid="mc-schedules"
        >
          <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
            {l('活跃调度', 'Active Schedules')}
          </h3>
          <div className="space-y-2">
            {schedules.map((sch) => (
              <div
                key={sch.pid}
                className="flex items-center justify-between rounded-lg bg-gray-50 p-3 dark:bg-gray-700/30"
              >
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium text-gray-900 dark:text-white">
                    {sch.schedule_name || sch.pid.slice(0, 8)}
                  </div>
                  <div className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                    {sch.cron_expression && (
                      <span className="mr-3 font-mono">{sch.cron_expression}</span>
                    )}
                    {sch.last_run_at && (
                      <span>
                        {l('上次运行', 'Last run')}: {timeAgo(sch.last_run_at, l)}
                      </span>
                    )}
                  </div>
                </div>
                <button
                  className="flex items-center gap-1 rounded-md bg-indigo-50 px-3 py-1.5 text-xs font-medium text-indigo-600 transition-colors hover:bg-indigo-100 disabled:opacity-50 dark:bg-indigo-900/30 dark:text-indigo-400 dark:hover:bg-indigo-900/50"
                  onClick={() => handleTriggerSchedule(sch.pid)}
                  disabled={triggeringSchedule === sch.pid}
                  data-testid={`trigger-schedule-${sch.pid}`}
                >
                  {triggeringSchedule === sch.pid
                    ? l('触发中...', 'Triggering...')
                    : l('立即运行', 'Run Now')}
                </button>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// ============================================================================
// Observation Drill-Down View
// ============================================================================

const OBS_TYPE_COLORS: Record<string, string> = {
  ACTIVITY: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
  METRIC: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
  COST: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300',
  ERROR: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300',
  ALERT: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300',
};

function severityClass(severity: string): string {
  switch (severity) {
    case 'error':
      return 'text-red-600 dark:text-red-400';
    case 'critical':
      return 'text-red-700 dark:text-red-300 font-bold';
    case 'warn':
      return 'text-amber-600 dark:text-amber-400';
    default:
      return 'text-gray-500 dark:text-gray-400';
  }
}

function ObservationDrillDownView({
  l,
  refreshKey,
}: {
  l: (zh: string, en: string) => string;
  refreshKey: number;
}) {
  const [summary, setSummary] = useState<DailyActivity[]>([]);
  const [observations, setObservations] = useState<ObservationItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedObs, setExpandedObs] = useState<string | null>(null);

  useEffect(() => {
    const isInitial = refreshKey === 0;
    if (isInitial) setLoading(true);
    Promise.all([
      fetchNQ<DailyActivity>('acp_daily_activity', { maxItems: '7' }),
      get<{ records: ObservationItem[] }>('/api/dynamic/agent-observation/list', {
        pageSize: '50',
        sortField: 'created_at',
        sortOrder: 'desc',
      }).then((res) => (ResultHelper.isSuccess(res) && res.data?.records ? res.data.records : [])),
    ])
      .then(([sum, obs]) => {
        setSummary(sum);
        setObservations(obs);
      })
      .finally(() => setLoading(false));
  }, [refreshKey]);

  if (loading) return <Spinner />;

  return (
    <div className="space-y-6" data-testid="mc-observations">
      {/* Daily Summary */}
      {summary.length > 0 && (
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-7">
          {summary.map((day) => (
            <div
              key={day.activity_date}
              className="rounded-lg border border-gray-200 bg-white p-3 text-center shadow-sm dark:border-gray-700 dark:bg-gray-800"
            >
              <div className="mb-1 text-xs text-gray-500 dark:text-gray-400">
                {day.activity_date
                  ? new Date(day.activity_date).toLocaleDateString(undefined, {
                      month: 'short',
                      day: 'numeric',
                    })
                  : '-'}
              </div>
              <div className="text-lg font-bold text-gray-900 dark:text-white">
                {day.total_observations}
              </div>
              <div className="mt-1 flex justify-center gap-2 text-xs">
                {Number(day.error_count) > 0 && (
                  <span className="text-red-500">{day.error_count} err</span>
                )}
                {Number(day.alert_count) > 0 && (
                  <span className="text-amber-500">{day.alert_count} alert</span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Observation Table */}
      {observations.length === 0 ? (
        <EmptyState
          text={l(
            '暂无事件日志。Agent 活动将在此记录。',
            'No events yet. Agent observations will appear here.',
          )}
        />
      ) : (
        <div className="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800">
                <th className="w-8 px-2"></th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">
                  {l('时间', 'Time')}
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">
                  {l('类型', 'Type')}
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">
                  {l('严重性', 'Severity')}
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">
                  {l('来源', 'Source')}
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">Agent</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">
                  {l('标题', 'Title')}
                </th>
              </tr>
            </thead>
            <tbody>
              {observations.map((obs) => {
                const isExpanded = expandedObs === obs.pid;
                return (
                  <ObservationRow
                    key={obs.pid}
                    obs={obs}
                    l={l}
                    expanded={isExpanded}
                    onToggle={() => setExpandedObs(isExpanded ? null : obs.pid)}
                  />
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function ObservationRow({
  obs,
  l,
  expanded,
  onToggle,
}: {
  obs: ObservationItem;
  l: (zh: string, en: string) => string;
  expanded: boolean;
  onToggle: () => void;
}) {
  let parsedDetail: string = obs.detail || '';
  try {
    if (parsedDetail.startsWith('{') || parsedDetail.startsWith('[')) {
      parsedDetail = JSON.stringify(JSON.parse(parsedDetail), null, 2);
    }
  } catch {
    /* use raw */
  }

  return (
    <>
      <tr
        className="cursor-pointer border-b border-gray-100 transition-colors hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
        onClick={onToggle}
      >
        <td className="px-2 text-center text-gray-400">
          <span className={`inline-block transition-transform ${expanded ? 'rotate-90' : ''}`}>
            &#9654;
          </span>
        </td>
        <td className="px-4 py-3 text-xs whitespace-nowrap text-gray-500">
          {obs.created_at ? timeAgo(obs.created_at, l) : '-'}
        </td>
        <td className="px-4 py-3">
          <span
            className={`rounded-full px-2 py-0.5 text-xs ${OBS_TYPE_COLORS[obs.observation_type] || 'bg-gray-100 text-gray-700'}`}
          >
            {obs.observation_type}
          </span>
        </td>
        <td className={`px-4 py-3 text-xs ${severityClass(obs.severity)}`}>{obs.severity}</td>
        <td className="px-4 py-3 text-xs text-gray-600 dark:text-gray-400">{obs.source || '-'}</td>
        <td className="px-4 py-3 text-xs text-gray-600 dark:text-gray-400">
          {obs.agent_id || '-'}
        </td>
        <td className="max-w-[300px] truncate px-4 py-3 text-sm text-gray-900 dark:text-white">
          {obs.title || '-'}
        </td>
      </tr>
      {expanded && parsedDetail && (
        <tr className="bg-gray-50/50 dark:bg-gray-800/50">
          <td colSpan={7} className="px-6 py-4">
            <div className="mb-1 text-xs font-medium text-gray-500">{l('详情', 'Detail')}</div>
            <pre className="max-h-60 overflow-x-auto rounded bg-gray-100 p-3 font-mono text-xs break-words whitespace-pre-wrap text-gray-700 dark:bg-gray-900 dark:text-gray-300">
              {parsedDetail}
            </pre>
          </td>
        </tr>
      )}
    </>
  );
}

// ============================================================================
// Analytics View
// ============================================================================

interface CostByAgent {
  agent_name: string;
  agent_id: string;
  run_date: string;
  run_count: number;
  daily_cost: number;
  daily_input_tokens: number;
  daily_output_tokens: number;
}

interface DailyActivity {
  activity_date: string;
  activity_count: number;
  error_count: number;
  cost_events: number;
  alert_count: number;
  total_observations: number;
}

interface ErrorSummary {
  pid: string;
  agent_id: string;
  agent_name: string;
  run_model: string;
  started_at: string;
  duration_ms: number;
  total_cost: number;
  error_message: string;
  task_title: string;
}

function AnalyticsView({
  l,
  refreshKey,
}: {
  l: (zh: string, en: string) => string;
  refreshKey: number;
}) {
  const [costData, setCostData] = useState<CostByAgent[]>([]);
  const [activityData, setActivityData] = useState<DailyActivity[]>([]);
  const [errorData, setErrorData] = useState<ErrorSummary[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const isInitial = refreshKey === 0;
    if (isInitial) setLoading(true);
    Promise.all([
      fetchNQ<CostByAgent>('acp_cost_by_agent'),
      fetchNQ<DailyActivity>('acp_daily_activity'),
      fetchNQ<ErrorSummary>('acp_error_summary', { maxItems: '20' }),
    ])
      .then(([cost, activity, errors]) => {
        setCostData(cost);
        setActivityData(activity);
        setErrorData(errors);
      })
      .finally(() => setLoading(false));
  }, [refreshKey]);

  if (loading) return <Spinner />;

  // Aggregate cost by agent for summary
  const agentCostMap = new Map<
    string,
    { name: string; cost: number; runs: number; tokens: number }
  >();
  for (const row of costData) {
    const key = row.agent_id || 'unknown';
    const existing = agentCostMap.get(key) || {
      name: row.agent_name || key,
      cost: 0,
      runs: 0,
      tokens: 0,
    };
    existing.cost += Number(row.daily_cost) || 0;
    existing.runs += Number(row.run_count) || 0;
    existing.tokens +=
      (Number(row.daily_input_tokens) || 0) + (Number(row.daily_output_tokens) || 0);
    agentCostMap.set(key, existing);
  }
  const agentCosts = [...agentCostMap.values()].sort((a, b) => b.cost - a.cost);
  const totalCost30d = agentCosts.reduce((sum, a) => sum + a.cost, 0);

  // Aggregate daily activity for bar-style display
  const recentActivity = activityData.slice(0, 14); // last 14 days

  return (
    <div className="space-y-6" data-testid="mc-analytics">
      {/* Cost by Agent */}
      <div className="rounded-lg border border-gray-200 bg-white p-5 shadow-sm dark:border-gray-700 dark:bg-gray-800">
        <h3 className="mb-4 text-lg font-semibold text-gray-900 dark:text-white">
          {l('30天成本分布', '30-Day Cost Breakdown')}
          <span className="ml-3 text-sm font-normal text-gray-500">
            {l('总计', 'Total')}: {fmtCost(totalCost30d)}
          </span>
        </h3>
        {agentCosts.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {l('暂无成本数据', 'No cost data yet')}
          </p>
        ) : (
          <div className="space-y-3">
            {agentCosts.map((agent) => {
              const pct = totalCost30d > 0 ? (agent.cost / totalCost30d) * 100 : 0;
              return (
                <div key={agent.name} className="flex items-center gap-3">
                  <span
                    className="w-32 truncate text-sm text-gray-700 dark:text-gray-300"
                    title={agent.name}
                  >
                    {agent.name}
                  </span>
                  <div className="relative h-5 flex-1 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
                    <div
                      className="h-full rounded-full bg-blue-500 transition-all duration-300 dark:bg-blue-400"
                      style={{ width: `${Math.max(pct, 2)}%` }}
                    />
                  </div>
                  <span className="w-20 text-right text-sm font-medium text-gray-900 dark:text-white">
                    {fmtCost(agent.cost)}
                  </span>
                  <span className="w-16 text-right text-xs text-gray-500 dark:text-gray-400">
                    {agent.runs} {l('次', 'runs')}
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Daily Activity */}
      <div className="rounded-lg border border-gray-200 bg-white p-5 shadow-sm dark:border-gray-700 dark:bg-gray-800">
        <h3 className="mb-4 text-lg font-semibold text-gray-900 dark:text-white">
          {l('每日活动', 'Daily Activity')}
        </h3>
        {recentActivity.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {l('暂无活动数据', 'No activity data yet')}
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm" data-testid="analytics-activity-table">
              <thead>
                <tr className="border-b border-gray-200 text-left text-gray-500 dark:border-gray-700 dark:text-gray-400">
                  <th className="py-2 pr-4">{l('日期', 'Date')}</th>
                  <th className="py-2 pr-4 text-right">{l('活动', 'Activity')}</th>
                  <th className="py-2 pr-4 text-right">{l('错误', 'Errors')}</th>
                  <th className="py-2 pr-4 text-right">{l('告警', 'Alerts')}</th>
                  <th className="py-2 text-right">{l('总计', 'Total')}</th>
                </tr>
              </thead>
              <tbody>
                {recentActivity.map((day) => (
                  <tr
                    key={day.activity_date}
                    className="border-b border-gray-100 dark:border-gray-700/50"
                  >
                    <td className="py-2 pr-4 text-gray-700 dark:text-gray-300">
                      {day.activity_date ? new Date(day.activity_date).toLocaleDateString() : '-'}
                    </td>
                    <td className="py-2 pr-4 text-right text-green-600 dark:text-green-400">
                      {day.activity_count}
                    </td>
                    <td className="py-2 pr-4 text-right">
                      <span
                        className={
                          Number(day.error_count) > 0
                            ? 'font-medium text-red-600 dark:text-red-400'
                            : 'text-gray-400'
                        }
                      >
                        {day.error_count}
                      </span>
                    </td>
                    <td className="py-2 pr-4 text-right">
                      <span
                        className={
                          Number(day.alert_count) > 0
                            ? 'text-amber-600 dark:text-amber-400'
                            : 'text-gray-400'
                        }
                      >
                        {day.alert_count}
                      </span>
                    </td>
                    <td className="py-2 text-right font-medium text-gray-900 dark:text-white">
                      {day.total_observations}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Error Summary */}
      <div className="rounded-lg border border-gray-200 bg-white p-5 shadow-sm dark:border-gray-700 dark:bg-gray-800">
        <h3 className="mb-4 text-lg font-semibold text-gray-900 dark:text-white">
          {l('最近错误', 'Recent Errors')}
          {errorData.length > 0 && (
            <span className="ml-2 text-sm font-normal text-red-500">{errorData.length}</span>
          )}
        </h3>
        {errorData.length === 0 ? (
          <p className="text-sm text-green-600 dark:text-green-400">
            {l('无错误记录', 'No errors — all clear!')}
          </p>
        ) : (
          <div className="space-y-3" data-testid="analytics-error-list">
            {errorData.slice(0, 10).map((err) => (
              <div
                key={err.pid}
                className="rounded-lg border border-red-200 bg-red-50 p-3 dark:border-red-800/30 dark:bg-red-900/10"
              >
                <div className="mb-1 flex items-center justify-between">
                  <span className="text-sm font-medium text-gray-900 dark:text-white">
                    {err.agent_name || err.agent_id} — {err.task_title || 'Unknown task'}
                  </span>
                  <span className="text-xs text-gray-500 dark:text-gray-400">
                    {err.started_at ? new Date(err.started_at).toLocaleString() : '-'}
                  </span>
                </div>
                <p className="text-sm break-all text-red-700 dark:text-red-300">
                  {err.error_message || 'No error message'}
                </p>
                <div className="mt-1 flex gap-4 text-xs text-gray-500 dark:text-gray-400">
                  <span>{err.run_model}</span>
                  <span>{fmtDuration(err.duration_ms)}</span>
                  <span>{fmtCost(Number(err.total_cost) || 0)}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// ============================================================================
// Shared Components
// ============================================================================

const COLOR_MAP: Record<string, string> = {
  blue: 'text-blue-600 dark:text-blue-400',
  indigo: 'text-indigo-600 dark:text-indigo-400',
  green: 'text-green-600 dark:text-green-400',
  emerald: 'text-emerald-600 dark:text-emerald-400',
  amber: 'text-amber-600 dark:text-amber-400',
  red: 'text-red-600 dark:text-red-400',
};

function KpiCard({
  label,
  value,
  color,
  onClick,
}: {
  label: string;
  value: string;
  color: string;
  onClick?: () => void;
}) {
  return (
    <div
      className={`rounded-lg bg-white p-4 shadow-sm transition-all dark:bg-gray-800 ${
        onClick ? 'cursor-pointer hover:scale-[1.02] hover:shadow-md' : ''
      }`}
      onClick={onClick}
    >
      <div className="mb-1 text-xs text-gray-500 dark:text-gray-400">{label}</div>
      <div className={`text-2xl font-bold ${COLOR_MAP[color] || COLOR_MAP.blue}`}>{value}</div>
    </div>
  );
}

function QuickLink({
  icon,
  title,
  desc,
  onClick,
}: {
  icon: string;
  title: string;
  desc: string;
  onClick: () => void;
}) {
  return (
    <div
      className="cursor-pointer rounded-lg bg-white p-4 shadow-sm transition-all hover:scale-[1.01] hover:shadow-md dark:bg-gray-800"
      onClick={onClick}
    >
      <span className="text-2xl">{icon}</span>
      <div className="mt-2 text-sm font-medium text-gray-900 dark:text-white">{title}</div>
      <div className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{desc}</div>
    </div>
  );
}

function RunStatusIcon({ status }: { status: string }) {
  const icons: Record<string, string> = {
    running: '🔄',
    success: '✅',
    failed: '❌',
    cancelled: '⏹️',
    TIMEOUT: '⏰',
    pending: '⏳',
  };
  return <span className="text-lg">{icons[status] || '❓'}</span>;
}

function Spinner() {
  return (
    <div className="flex min-h-[300px] items-center justify-center">
      <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600 dark:border-blue-400" />
    </div>
  );
}

// ============================================================================
// Utility Components
// ============================================================================

function LiveIndicator() {
  return (
    <span className="flex items-center gap-1.5 rounded-full bg-green-50 px-2 py-1 text-xs text-green-600 dark:bg-green-900/20 dark:text-green-400">
      <span className="relative flex h-2 w-2">
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-green-400 opacity-75" />
        <span className="relative inline-flex h-2 w-2 rounded-full bg-green-500" />
      </span>
      LIVE
    </span>
  );
}

function EmptyState({ text }: { text: string }) {
  return (
    <div className="py-16 text-center">
      <div className="mb-3 text-4xl">🎯</div>
      <div className="mx-auto max-w-md text-sm text-gray-500 dark:text-gray-400">{text}</div>
    </div>
  );
}
