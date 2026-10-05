import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import {
  useExecutiveDashboardText,
  type ExecutiveDashboardTextFn,
  type ExecutiveDashboardTextKey,
} from './executiveDashboardText';

// ============================================================================
// Types
// ============================================================================

type DashboardTab = 'overview' | 'profit' | 'payment' | 'cost-warning' | 'progress' | 'pm-overview';

interface KpiData {
  project_count: number;
  contract_total: number;
  received_total: number;
  cost_total: number;
  profit_total: number;
  risk_count: number;
}

interface ProfitRanking {
  pid: string;
  project_name: string;
  contract_amount: number;
  cost_amount: number;
  profit_amount: number;
  profit_rate: number;
}

interface RiskProject {
  pid: string;
  project_name: string;
  project_status: string;
  risk_type: string;
  severity: string;
  overdue_tasks: number;
  overdue_payments: number;
  budget_exec_rate: number;
}

interface ProjectSummary {
  pid: string;
  project_name: string;
  project_status: string;
  dept_name: string;
  contract_amount: number;
  received_amount: number;
  cost_amount: number;
  profit: number;
  profit_rate: number;
  payment_rate: number;
}

interface DeptProfit {
  dept_name: string;
  project_count: number;
  contract_total: number;
  cost_total: number;
  profit: number;
  profit_rate: number;
}

interface PaymentOverview {
  pid: string;
  project_name: string;
  due_amount: number;
  received_amount: number;
  payment_rate: number;
  overdue_amount: number;
  overdue_count: number;
}

interface CostWarning {
  pid: string;
  project_name: string;
  category: string;
  budget_amount: number;
  actual_amount: number;
  exec_rate: number;
  variance: number;
  warning_level: string;
}

interface ProgressHealth {
  pid: string;
  project_name: string;
  project_status: string;
  planned_progress: number;
  actual_progress: number;
  variance: number;
  total_tasks: number;
  done_tasks: number;
  overdue_tasks: number;
  health_status: string;
}

// PM Dashboard Types
interface PmKpiData {
  project_count: number;
  active_count: number;
  completed_count: number;
  total_tasks: number;
  done_tasks: number;
  overdue_tasks: number;
  total_hours: number;
  billable_hours: number;
}

interface PmProjectHealth {
  pid: string;
  pm_project_name: string;
  pm_project_status: string;
  pm_project_client: string;
  dept_name: string;
  planned_progress: number;
  actual_progress: number;
  total_tasks: number;
  done_tasks: number;
  in_progress_tasks: number;
  overdue_tasks: number;
  health_status: string;
}

interface PmStatusDist {
  status: string;
  count: number;
}

interface PmOverdueTask {
  pid: string;
  pm_task_title: string;
  pm_task_status: string;
  pm_task_priority: string;
  pm_task_type: string;
  pm_task_due_date: string;
  overdue_days: number;
  pm_project_name: string;
}

// ============================================================================
// Helpers
// ============================================================================

const RISK_TYPE_TEXT_KEYS: Record<string, ExecutiveDashboardTextKey> = {
  BUDGET_OVERRUN: 'riskTypeBudgetOverrun',
  TASK_OVERDUE: 'riskTypeTaskOverdue',
  PAYMENT_OVERDUE: 'riskTypePaymentOverdue',
  TASK_AND_PAYMENT: 'riskTypeTaskAndPayment',
  BUDGET_WARNING: 'riskTypeBudgetWarning',
};

const HEALTH_STATUS_TEXT_KEYS: Record<string, ExecutiveDashboardTextKey> = {
  NORMAL: 'healthStatusNormal',
  AT_RISK: 'healthStatusAtRisk',
  DELAYED: 'healthStatusDelayed',
};

function fmt(n: number, t: ExecutiveDashboardTextFn): string {
  if (Math.abs(n) >= 100000000) return t('fmtAmountYi', { value: (n / 100000000).toFixed(1) });
  if (Math.abs(n) >= 10000) return t('fmtAmountWan', { value: (n / 10000).toFixed(1) });
  return `¥${n.toLocaleString()}`;
}

// Unknown enum codes coming from business data keep their source values: the
// zh-CN locale renders the raw code, English locales the underscore-spaced code.
function rawEnumLabel(code: string, locale: string): string {
  return locale.startsWith('en') ? code.replace(/_/g, ' ') : code;
}

function fetchNQ<T>(code: string, extra?: Record<string, string>): Promise<T[]> {
  return get<{ records: T[] }>('/api/datasource/list', {
    datasourceId: `nq:${code}`,
    format: 'records',
    ...extra,
  }).then((res) => (ResultHelper.isSuccess(res) && res.data?.records ? res.data.records : []));
}

// ============================================================================
// Main Component
// ============================================================================

export default function ExecutiveDashboard() {
  const { locale, text: t } = useExecutiveDashboardText();
  const navigate = useNavigate();

  const [activeTab, setActiveTab] = useState<DashboardTab>('overview');

  const tabs: { key: DashboardTab; labelKey: ExecutiveDashboardTextKey }[] = [
    { key: 'overview', labelKey: 'tabOverview' },
    { key: 'profit', labelKey: 'tabProfit' },
    { key: 'payment', labelKey: 'tabPayment' },
    { key: 'cost-warning', labelKey: 'tabCostWarning' },
    { key: 'progress', labelKey: 'tabProgress' },
    { key: 'pm-overview', labelKey: 'tabPmOverview' },
  ];

  return (
    <div className="flex h-full flex-col" data-testid="executive-dashboard">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-4 dark:border-gray-700 dark:bg-gray-800">
        <h1 className="text-xl font-bold text-gray-900 dark:text-white">{t('title')}</h1>
        <div
          className="flex items-center gap-2 text-sm text-gray-500 dark:text-gray-400"
          data-testid="dashboard-period-info"
        >
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
            />
          </svg>
          <span>{t('scopeNote')}</span>
        </div>
      </div>

      {/* Tab Navigation */}
      <div
        className="flex border-b border-gray-200 bg-white px-6 dark:border-gray-700 dark:bg-gray-800"
        data-testid="dashboard-tabs"
      >
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`border-b-2 px-4 py-3 text-sm font-medium transition-colors ${
              activeTab === tab.key
                ? 'border-blue-600 text-blue-600 dark:border-blue-400 dark:text-blue-400'
                : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
            }`}
            data-testid={`dashboard-tab-${tab.key}`}
          >
            {t(tab.labelKey)}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      <div className="flex-1 overflow-auto bg-gray-50 p-6 dark:bg-gray-900">
        {activeTab === 'overview' && <OverviewView t={t} locale={locale} navigate={navigate} />}
        {activeTab === 'profit' && <ProfitView t={t} navigate={navigate} />}
        {activeTab === 'payment' && <PaymentView t={t} navigate={navigate} />}
        {activeTab === 'cost-warning' && <CostWarningView t={t} navigate={navigate} />}
        {activeTab === 'progress' && <ProgressView t={t} navigate={navigate} />}
        {activeTab === 'pm-overview' && <PmOverviewView t={t} locale={locale} navigate={navigate} />}
      </div>
    </div>
  );
}

// ============================================================================
// Overview View
// ============================================================================

type TableFilter = 'all' | 'risk' | 'has-contract' | 'has-cost';

function OverviewView({
  t,
  locale,
  navigate,
}: {
  t: ExecutiveDashboardTextFn;
  locale: string;
  navigate: ReturnType<typeof useNavigate>;
}) {
  const [kpi, setKpi] = useState<KpiData | null>(null);
  const [ranking, setRanking] = useState<ProfitRanking[]>([]);
  const [risks, setRisks] = useState<RiskProject[]>([]);
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [tableFilter, setTableFilter] = useState<TableFilter>('all');

  useEffect(() => {
    setLoading(true);
    Promise.all([
      fetchNQ<KpiData>('cc_dashboard_kpi'),
      fetchNQ<ProfitRanking>('cc_profit_ranking'),
      fetchNQ<RiskProject>('cc_risk_projects'),
      fetchNQ<ProjectSummary>('cc_project_summary_all'),
    ])
      .then(([kpiData, rankData, riskData, projData]) => {
        if (kpiData.length) setKpi(kpiData[0]);
        setRanking(rankData);
        setRisks(riskData);
        setProjects(projData);
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spinner />;

  return (
    <div className="space-y-6" data-testid="dashboard-overview">
      {/* KPI Cards — clickable to filter detail table */}
      <div
        className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-6"
        data-testid="dashboard-kpi-cards"
      >
        <KpiCard
          label={t('kpiProjects')}
          value={String(kpi?.project_count ?? 0)}
          color="blue"
          active={tableFilter === 'all'}
          onClick={() => setTableFilter('all')}
        />
        <KpiCard
          label={t('kpiContracts')}
          value={fmt(kpi?.contract_total ?? 0, t)}
          color="indigo"
          active={tableFilter === 'has-contract'}
          onClick={() => setTableFilter((f) => (f === 'has-contract' ? 'all' : 'has-contract'))}
        />
        <KpiCard label={t('kpiReceived')} value={fmt(kpi?.received_total ?? 0, t)} color="green" />
        <KpiCard
          label={t('kpiCost')}
          value={fmt(kpi?.cost_total ?? 0, t)}
          color="amber"
          active={tableFilter === 'has-cost'}
          onClick={() => setTableFilter((f) => (f === 'has-cost' ? 'all' : 'has-cost'))}
        />
        <KpiCard label={t('kpiProfit')} value={fmt(kpi?.profit_total ?? 0, t)} color="emerald" />
        <KpiCard
          label={t('kpiAtRisk')}
          value={String(kpi?.risk_count ?? 0)}
          color={kpi?.risk_count ? 'red' : 'green'}
          active={tableFilter === 'risk'}
          onClick={() => setTableFilter((f) => (f === 'risk' ? 'all' : 'risk'))}
        />
      </div>

      {/* Middle: Profit Ranking + Risk Projects */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Profit TOP 10 */}
        <div
          className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
          data-testid="dashboard-profit-ranking"
        >
          <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
            {t('profitRankingTop10')}
          </h3>
          {ranking.length === 0 ? (
            <EmptyState text={t('noData')} />
          ) : (
            <div className="space-y-3">
              {ranking.map((r, i) => {
                const maxProfit = ranking[0]?.profit_amount || 1;
                const pct = Math.max(0, Math.min(100, (r.profit_amount / maxProfit) * 100));
                return (
                  <div
                    key={r.pid}
                    className="-mx-2 flex cursor-pointer items-center gap-3 rounded-lg p-2 transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/30"
                    onClick={() => navigate(`/project-management/projects/${r.pid}`)}
                  >
                    <span
                      className={`flex h-5 w-5 items-center justify-center rounded-full text-xs font-bold ${i < 3 ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300' : 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400'}`}
                    >
                      {i + 1}
                    </span>
                    <span className="w-24 truncate text-sm text-gray-700 dark:text-gray-300">
                      {r.project_name}
                    </span>
                    <div className="h-5 flex-1 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
                      <div
                        className="h-full rounded-full bg-blue-500 transition-all dark:bg-blue-400"
                        style={{ width: `${pct}%` }}
                      />
                    </div>
                    <span className="w-20 text-right font-mono text-sm text-gray-900 dark:text-white">
                      {fmt(r.profit_amount, t)}
                    </span>
                    <span className="w-12 text-right text-xs text-gray-500">{r.profit_rate}%</span>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Risk Projects */}
        <div
          className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
          data-testid="dashboard-risk-projects"
        >
          <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
            {t('riskProjects')}
          </h3>
          {risks.length === 0 ? (
            <div className="py-8 text-center">
              <div className="mb-2 text-3xl">✅</div>
              <div className="text-sm text-gray-500">{t('noRiskProjects')}</div>
            </div>
          ) : (
            <div className="space-y-2">
              {risks.slice(0, 8).map((r) => (
                <div
                  key={r.pid}
                  onClick={() => navigate(`/project-management/projects/${r.pid}`)}
                  className={`flex cursor-pointer items-center justify-between rounded-lg p-3 transition-colors ${
                    r.severity === 'high'
                      ? 'border border-red-200 bg-red-50 hover:bg-red-100 dark:border-red-800 dark:bg-red-900/10 dark:hover:bg-red-900/20'
                      : 'border border-amber-200 bg-amber-50 hover:bg-amber-100 dark:border-amber-800 dark:bg-amber-900/10 dark:hover:bg-amber-900/20'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span className="text-lg">{r.severity === 'high' ? '🔴' : '🟡'}</span>
                    <span className="text-sm font-medium text-gray-900 dark:text-white">
                      {r.project_name}
                    </span>
                  </div>
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs ${
                      r.risk_type === 'budget_overrun'
                        ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
                        : r.risk_type === 'task_overdue'
                          ? 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300'
                          : r.risk_type === 'payment_overdue'
                            ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
                            : 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-300'
                    }`}
                  >
                    {RISK_TYPE_TEXT_KEYS[r.risk_type]
                      ? t(RISK_TYPE_TEXT_KEYS[r.risk_type])
                      : rawEnumLabel(r.risk_type, locale)}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Detail Table */}
      <div
        className="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800"
        data-testid="dashboard-project-table"
      >
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <h3 className="text-sm font-medium text-gray-700 dark:text-gray-300">
            {t('projectFinancialDetail')}
          </h3>
          {tableFilter !== 'all' && (
            <button
              className="text-xs text-blue-600 hover:underline dark:text-blue-400"
              onClick={() => setTableFilter('all')}
              data-testid="dashboard-clear-filter"
            >
              {t('clearFilter')} ✕
            </button>
          )}
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800">
              <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                {t('colProject')}
              </th>
              <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                {t('colDept')}
              </th>
              <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                {t('colStatus')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                {t('colContract')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                {t('colReceived')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                {t('colCost')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                {t('colProfit')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                {t('colProfitRate')}
              </th>
            </tr>
          </thead>
          <tbody>
            {(() => {
              const riskPids = new Set(risks.map((r) => r.pid));
              const filtered =
                tableFilter === 'all'
                  ? projects
                  : tableFilter === 'risk'
                    ? projects.filter((p) => riskPids.has(p.pid))
                    : tableFilter === 'has-contract'
                      ? projects.filter((p) => p.contract_amount > 0)
                      : tableFilter === 'has-cost'
                        ? projects.filter((p) => p.cost_amount > 0)
                        : projects;
              return filtered.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-4 py-12 text-center text-gray-400">
                    {t('noData')}
                  </td>
                </tr>
              ) : (
                filtered.map((p) => (
                  <tr
                    key={p.pid}
                    className="cursor-pointer border-b border-gray-100 transition-colors hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
                    onClick={() => navigate(`/project-management/projects/${p.pid}`)}
                  >
                    <td className="px-4 py-3 font-medium text-gray-900 dark:text-white">
                      {p.project_name}
                    </td>
                    <td className="px-4 py-3 text-gray-600 dark:text-gray-400">{p.dept_name}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={p.project_status} t={t} />
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-gray-900 dark:text-white">
                      {fmt(p.contract_amount, t)}
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-green-600 dark:text-green-400">
                      {fmt(p.received_amount, t)}
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-gray-600 dark:text-gray-400">
                      {fmt(p.cost_amount, t)}
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-gray-900 dark:text-white">
                      {fmt(p.profit, t)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <span
                        className={`font-medium ${p.profit_rate >= 20 ? 'text-green-600' : p.profit_rate >= 10 ? 'text-amber-600' : 'text-red-600'}`}
                      >
                        {p.profit_rate}%
                      </span>
                    </td>
                  </tr>
                ))
              );
            })()}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ============================================================================
// Profit Analysis View
// ============================================================================

function ProfitView({
  t,
  navigate,
}: {
  t: ExecutiveDashboardTextFn;
  navigate: ReturnType<typeof useNavigate>;
}) {
  const [deptData, setDeptData] = useState<DeptProfit[]>([]);
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      fetchNQ<DeptProfit>('cc_dept_profit'),
      fetchNQ<ProjectSummary>('cc_project_summary_all'),
    ])
      .then(([dept, proj]) => {
        setDeptData(dept);
        setProjects(proj);
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spinner />;

  const maxContract = Math.max(...deptData.map((d) => d.contract_total), 1);

  return (
    <div className="space-y-6" data-testid="dashboard-profit">
      {/* Department Profit Chart */}
      <div
        className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
        data-testid="dashboard-dept-profit"
      >
        <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
          {t('deptProfitAnalysis')}
        </h3>
        {deptData.length === 0 ? (
          <EmptyState text={t('noData')} />
        ) : (
          <div className="space-y-4">
            {deptData.map((d) => (
              <div key={d.dept_name} className="space-y-1">
                <div className="flex items-center justify-between text-sm">
                  <span className="font-medium text-gray-700 dark:text-gray-300">
                    {d.dept_name}
                  </span>
                  <span className="text-gray-500 dark:text-gray-400">
                    {t('deptProjectsCount', { count: d.project_count })} · {t('colProfitRate')}{' '}
                    {d.profit_rate}%
                  </span>
                </div>
                <div className="flex h-6 gap-1">
                  <div
                    className="h-full rounded-l bg-blue-500 dark:bg-blue-400"
                    style={{ width: `${(d.contract_total / maxContract) * 100}%` }}
                    title={t('titleContractAmount', { amount: fmt(d.contract_total, t) })}
                  />
                </div>
                <div className="flex h-6 gap-1">
                  <div
                    className="h-full rounded-l bg-orange-400 dark:bg-orange-500"
                    style={{ width: `${(d.cost_total / maxContract) * 100}%` }}
                    title={t('titleCostAmount', { amount: fmt(d.cost_total, t) })}
                  />
                </div>
                <div className="flex h-6 gap-1">
                  <div
                    className="h-full rounded-l bg-green-500 dark:bg-green-400"
                    style={{ width: `${(d.profit / maxContract) * 100}%` }}
                    title={t('titleProfitAmount', { amount: fmt(d.profit, t) })}
                  />
                </div>
                <div className="flex gap-4 pl-2 text-xs text-gray-500 dark:text-gray-400">
                  <span className="flex items-center gap-1">
                    <span className="h-2 w-2 rounded-full bg-blue-500" />
                    {t('legendContractAmount', { amount: fmt(d.contract_total, t) })}
                  </span>
                  <span className="flex items-center gap-1">
                    <span className="h-2 w-2 rounded-full bg-orange-400" />
                    {t('legendCostAmount', { amount: fmt(d.cost_total, t) })}
                  </span>
                  <span className="flex items-center gap-1">
                    <span className="h-2 w-2 rounded-full bg-green-500" />
                    {t('legendProfitAmount', { amount: fmt(d.profit, t) })}
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Project Detail Table */}
      <div
        className="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800"
        data-testid="dashboard-profit-table"
      >
        <div className="border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <h3 className="text-sm font-medium text-gray-700 dark:text-gray-300">
            {t('projectProfitDetail')}
          </h3>
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800">
              <th className="px-4 py-3 text-left font-medium text-gray-500">{t('colProject')}</th>
              <th className="px-4 py-3 text-left font-medium text-gray-500">{t('colDept')}</th>
              <th className="px-4 py-3 text-right font-medium text-gray-500">
                {t('colContract')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500">{t('colCost')}</th>
              <th className="px-4 py-3 text-right font-medium text-gray-500">{t('colProfit')}</th>
              <th className="px-4 py-3 text-right font-medium text-gray-500">
                {t('colProfitRate')}
              </th>
            </tr>
          </thead>
          <tbody>
            {projects.map((p) => (
              <tr
                key={p.pid}
                className="cursor-pointer border-b border-gray-100 hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
                onClick={() => navigate(`/project-management/projects/${p.pid}`)}
              >
                <td className="px-4 py-3 font-medium text-gray-900 dark:text-white">
                  {p.project_name}
                </td>
                <td className="px-4 py-3 text-gray-600 dark:text-gray-400">{p.dept_name}</td>
                <td className="px-4 py-3 text-right font-mono">{fmt(p.contract_amount, t)}</td>
                <td className="px-4 py-3 text-right font-mono text-gray-600 dark:text-gray-400">
                  {fmt(p.cost_amount, t)}
                </td>
                <td className="px-4 py-3 text-right font-mono">{fmt(p.profit, t)}</td>
                <td className="px-4 py-3 text-right">
                  <span
                    className={`font-medium ${p.profit_rate >= 20 ? 'text-green-600' : p.profit_rate >= 10 ? 'text-amber-600' : 'text-red-600'}`}
                  >
                    {p.profit_rate}%
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ============================================================================
// Payment Analysis View
// ============================================================================

function PaymentView({
  t,
  navigate,
}: {
  t: ExecutiveDashboardTextFn;
  navigate: ReturnType<typeof useNavigate>;
}) {
  const [data, setData] = useState<PaymentOverview[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetchNQ<PaymentOverview>('cc_payment_overview')
      .then(setData)
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spinner />;

  const totalDue = data.reduce((s, d) => s + d.due_amount, 0);
  const totalReceived = data.reduce((s, d) => s + d.received_amount, 0);
  const totalOverdue = data.reduce((s, d) => s + d.overdue_amount, 0);
  const overallRate = totalDue > 0 ? Math.round((totalReceived * 100) / totalDue) : 0;

  return (
    <div className="space-y-6" data-testid="dashboard-payment">
      {/* KPI Row */}
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <KpiCard label={t('kpiTotalDue')} value={fmt(totalDue, t)} color="blue" />
        <KpiCard label={t('kpiTotalReceived')} value={fmt(totalReceived, t)} color="green" />
        <KpiCard
          label={t('kpiPaymentRate')}
          value={`${overallRate}%`}
          color={overallRate >= 60 ? 'green' : overallRate >= 30 ? 'amber' : 'red'}
        />
        <KpiCard
          label={t('kpiOverdueAmount')}
          value={fmt(totalOverdue, t)}
          color={totalOverdue > 0 ? 'red' : 'green'}
        />
      </div>

      {/* Gauge + Table */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* Gauge */}
        <div
          className="flex flex-col items-center justify-center rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
          data-testid="dashboard-payment-gauge"
        >
          <div className="relative mb-4 h-32 w-32">
            <svg viewBox="0 0 36 36" className="h-full w-full -rotate-90">
              <circle
                cx="18"
                cy="18"
                r="15.5"
                fill="none"
                className="stroke-gray-200 dark:stroke-gray-700"
                strokeWidth="3"
              />
              <circle
                cx="18"
                cy="18"
                r="15.5"
                fill="none"
                className={
                  overallRate >= 60
                    ? 'stroke-green-500'
                    : overallRate >= 30
                      ? 'stroke-amber-500'
                      : 'stroke-red-500'
                }
                strokeWidth="3"
                strokeLinecap="round"
                strokeDasharray={`${Math.min(overallRate, 100) * 0.97} 100`}
              />
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
              <span className="text-2xl font-bold text-gray-900 dark:text-white">
                {overallRate}%
              </span>
              <span className="text-xs text-gray-500">{t('paymentRateLabel')}</span>
            </div>
          </div>
          <div
            className={`text-sm font-medium ${overallRate >= 60 ? 'text-green-600' : overallRate >= 30 ? 'text-amber-600' : 'text-red-600'}`}
          >
            {overallRate >= 60 ? t('healthHealthy') : overallRate >= 30 ? t('healthFair') : t('healthAtRisk')}
          </div>
        </div>

        {/* Payment Detail Table */}
        <div
          className="overflow-hidden rounded-lg bg-white shadow-sm lg:col-span-2 dark:bg-gray-800"
          data-testid="dashboard-payment-table"
        >
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800">
                <th className="px-4 py-3 text-left font-medium text-gray-500">{t('colProject')}</th>
                <th className="px-4 py-3 text-right font-medium text-gray-500">{t('colDue')}</th>
                <th className="px-4 py-3 text-right font-medium text-gray-500">
                  {t('colReceivedShort')}
                </th>
                <th className="px-4 py-3 text-right font-medium text-gray-500">
                  {t('colPaymentRate')}
                </th>
                <th className="px-4 py-3 text-right font-medium text-gray-500">
                  {t('colOverdue')}
                </th>
              </tr>
            </thead>
            <tbody>
              {data.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-4 py-12 text-center text-gray-400">
                    {t('noPaymentData')}
                  </td>
                </tr>
              ) : (
                data.map((d) => (
                  <tr
                    key={d.pid}
                    className="cursor-pointer border-b border-gray-100 hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
                    onClick={() => navigate(`/project-management/projects/${d.pid}`)}
                  >
                    <td className="px-4 py-3 font-medium text-gray-900 dark:text-white">
                      {d.project_name}
                    </td>
                    <td className="px-4 py-3 text-right font-mono">{fmt(d.due_amount, t)}</td>
                    <td className="px-4 py-3 text-right font-mono text-green-600">
                      {fmt(d.received_amount, t)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <span
                        className={`font-medium ${d.payment_rate >= 80 ? 'text-green-600' : d.payment_rate >= 50 ? 'text-amber-600' : 'text-red-600'}`}
                      >
                        {d.payment_rate}%
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-red-600">
                      {d.overdue_amount > 0 ? fmt(d.overdue_amount, t) : '-'}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

// ============================================================================
// Cost Warning View
// ============================================================================

function CostWarningView({
  t,
  navigate,
}: {
  t: ExecutiveDashboardTextFn;
  navigate: ReturnType<typeof useNavigate>;
}) {
  const [data, setData] = useState<CostWarning[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetchNQ<CostWarning>('cc_cost_warning_list')
      .then(setData)
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spinner />;

  const overCount = data.filter((d) => d.warning_level === 'over').length;
  const warnCount = data.filter((d) => d.warning_level === 'warning').length;

  return (
    <div className="space-y-6" data-testid="dashboard-cost-warning">
      {/* Summary */}
      <div className="grid grid-cols-3 gap-4">
        <KpiCard
          label={t('kpiTotalWarnings')}
          value={String(data.length)}
          color={data.length > 0 ? 'amber' : 'green'}
        />
        <KpiCard
          label={t('kpiOverBudget')}
          value={String(overCount)}
          color={overCount > 0 ? 'red' : 'green'}
        />
        <KpiCard
          label={t('kpiNearLimit')}
          value={String(warnCount)}
          color={warnCount > 0 ? 'amber' : 'green'}
        />
      </div>

      {/* Warning Table */}
      <div
        className="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800"
        data-testid="dashboard-warning-table"
      >
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800">
              <th className="px-4 py-3 text-center font-medium text-gray-500">{t('colLevel')}</th>
              <th className="px-4 py-3 text-left font-medium text-gray-500">{t('colProject')}</th>
              <th className="px-4 py-3 text-left font-medium text-gray-500">{t('colCategory')}</th>
              <th className="px-4 py-3 text-right font-medium text-gray-500">{t('colBudget')}</th>
              <th className="px-4 py-3 text-right font-medium text-gray-500">{t('colActual')}</th>
              <th className="px-4 py-3 text-right font-medium text-gray-500">
                {t('colExecRate')}
              </th>
              <th className="px-4 py-3 text-right font-medium text-gray-500">{t('colVariance')}</th>
            </tr>
          </thead>
          <tbody>
            {data.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center">
                  <div className="mb-2 text-3xl">✅</div>
                  <div className="text-sm text-gray-500">{t('noCostWarnings')}</div>
                </td>
              </tr>
            ) : (
              data.map((d, i) => (
                <tr
                  key={`${d.pid}-${d.category}-${i}`}
                  className="cursor-pointer border-b border-gray-100 hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
                  onClick={() => navigate(`/project-management/projects/${d.pid}`)}
                >
                  <td className="px-4 py-3 text-center">
                    <span className="text-lg">{d.warning_level === 'over' ? '🔴' : '🟡'}</span>
                  </td>
                  <td className="px-4 py-3 font-medium text-gray-900 dark:text-white">
                    {d.project_name}
                  </td>
                  <td className="px-4 py-3 text-gray-600 dark:text-gray-400">{d.category}</td>
                  <td className="px-4 py-3 text-right font-mono">{fmt(d.budget_amount, t)}</td>
                  <td className="px-4 py-3 text-right font-mono">{fmt(d.actual_amount, t)}</td>
                  <td className="px-4 py-3 text-right">
                    <span
                      className={`font-medium ${d.exec_rate > 100 ? 'text-red-600' : 'text-amber-600'}`}
                    >
                      {d.exec_rate}%
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right font-mono text-red-600">
                    {fmt(d.variance, t)}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ============================================================================
// Progress Health View
// ============================================================================

function ProgressView({
  t,
  navigate,
}: {
  t: ExecutiveDashboardTextFn;
  navigate: ReturnType<typeof useNavigate>;
}) {
  const [data, setData] = useState<ProgressHealth[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetchNQ<ProgressHealth>('cc_progress_health')
      .then(setData)
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spinner />;

  const normalCount = data.filter((d) => d.health_status === 'normal').length;
  const atRiskCount = data.filter((d) => d.health_status === 'at_risk').length;
  const delayedCount = data.filter((d) => d.health_status === 'delayed').length;
  const total = data.length || 1;

  return (
    <div className="space-y-6" data-testid="dashboard-progress">
      {/* Status Distribution */}
      <div className="grid grid-cols-3 gap-4">
        <KpiCard
          label={t('kpiProgressNormal')}
          value={`${normalCount} (${Math.round((normalCount * 100) / total)}%)`}
          color="green"
        />
        <KpiCard
          label={t('kpiProgressAtRisk')}
          value={`${atRiskCount} (${Math.round((atRiskCount * 100) / total)}%)`}
          color="amber"
        />
        <KpiCard
          label={t('kpiProgressDelayed')}
          value={`${delayedCount} (${Math.round((delayedCount * 100) / total)}%)`}
          color="red"
        />
      </div>

      {/* Progress Bars */}
      <div
        className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
        data-testid="dashboard-progress-bars"
      >
        <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
          {t('plannedVsActual')}
        </h3>
        {data.length === 0 ? (
          <EmptyState text={t('noData')} />
        ) : (
          <div className="space-y-4">
            {data.map((d) => (
              <div
                key={d.pid}
                className="cursor-pointer rounded-lg p-3 transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/30"
                onClick={() => navigate(`/project-management/projects/${d.pid}`)}
              >
                <div className="mb-2 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="text-lg">
                      {d.health_status === 'delayed'
                        ? '🔴'
                        : d.health_status === 'at_risk'
                          ? '🟡'
                          : '🟢'}
                    </span>
                    <span className="text-sm font-medium text-gray-900 dark:text-white">
                      {d.project_name}
                    </span>
                  </div>
                  <div className="flex items-center gap-3 text-xs text-gray-500">
                    <span>{t('tasksProgress', { done: d.done_tasks, total: d.total_tasks })}</span>
                    {d.overdue_tasks > 0 && (
                      <span className="text-red-600">
                        {t('overdueTasksCount', { count: d.overdue_tasks })}
                      </span>
                    )}
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <div className="mb-1 flex items-center justify-between text-xs text-gray-500">
                      <span>{t('labelPlanned')}</span>
                      <span>{d.planned_progress}%</span>
                    </div>
                    <div className="h-3 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
                      <div
                        className="h-full rounded-full bg-blue-400 dark:bg-blue-500"
                        style={{ width: `${d.planned_progress}%` }}
                      />
                    </div>
                  </div>
                  <div>
                    <div className="mb-1 flex items-center justify-between text-xs text-gray-500">
                      <span>{t('labelActual')}</span>
                      <span>{d.actual_progress}%</span>
                    </div>
                    <div className="h-3 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
                      <div
                        className={`h-full rounded-full ${d.variance >= 0 ? 'bg-green-500' : d.variance >= -10 ? 'bg-amber-500' : 'bg-red-500'}`}
                        style={{ width: `${d.actual_progress}%` }}
                      />
                    </div>
                  </div>
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
// PM Overview View
// ============================================================================

function PmOverviewView({
  t,
  locale,
  navigate,
}: {
  t: ExecutiveDashboardTextFn;
  locale: string;
  navigate: ReturnType<typeof useNavigate>;
}) {
  const [kpi, setKpi] = useState<PmKpiData | null>(null);
  const [projects, setProjects] = useState<PmProjectHealth[]>([]);
  const [statusDist, setStatusDist] = useState<PmStatusDist[]>([]);
  const [taskDist, setTaskDist] = useState<PmStatusDist[]>([]);
  const [overdueTasks, setOverdueTasks] = useState<PmOverdueTask[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      fetchNQ<PmKpiData>('pm_dashboard_kpi'),
      fetchNQ<PmProjectHealth>('pm_project_health_overview'),
      fetchNQ<PmStatusDist>('pm_project_status_distribution'),
      fetchNQ<PmStatusDist>('pm_task_status_distribution'),
      fetchNQ<PmOverdueTask>('pm_overdue_tasks'),
    ])
      .then(([kpiData, projData, sDist, tDist, oTasks]) => {
        if (kpiData.length) setKpi(kpiData[0]);
        setProjects(projData);
        setStatusDist(sDist);
        setTaskDist(tDist);
        setOverdueTasks(oTasks);
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spinner />;

  const taskCompletionRate =
    kpi && kpi.total_tasks > 0 ? Math.round((kpi.done_tasks * 100) / kpi.total_tasks) : 0;

  const STATUS_TEXT_KEYS: Record<string, { textKey: ExecutiveDashboardTextKey; color: string }> = {
    PLANNING: { textKey: 'statusPlanning', color: 'bg-blue-500' },
    in_progress: { textKey: 'statusInProgress', color: 'bg-amber-500' },
    completed: { textKey: 'statusCompleted', color: 'bg-green-500' },
    archived: { textKey: 'statusArchived', color: 'bg-gray-400' },
  };

  const TASK_STATUS_TEXT_KEYS: Record<
    string,
    { textKey: ExecutiveDashboardTextKey; color: string }
  > = {
    TODO: { textKey: 'taskStatusTodo', color: 'bg-gray-400' },
    in_progress: { textKey: 'statusInProgress', color: 'bg-blue-500' },
    DONE: { textKey: 'taskStatusDone', color: 'bg-green-500' },
    cancelled: { textKey: 'taskStatusCancelled', color: 'bg-red-400' },
  };

  const PRIORITY_TEXT_KEYS: Record<
    string,
    { textKey: ExecutiveDashboardTextKey; color: string }
  > = {
    CRITICAL: {
      textKey: 'priorityCritical',
      color: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300',
    },
    HIGH: {
      textKey: 'priorityHigh',
      color: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300',
    },
    MEDIUM: {
      textKey: 'priorityMedium',
      color: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
    },
    LOW: {
      textKey: 'priorityLow',
      color: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
    },
  };

  const totalProjects = statusDist.reduce((s, d) => s + Number(d.count), 0);
  const totalTaskCount = taskDist.reduce((s, d) => s + Number(d.count), 0);

  return (
    <div className="space-y-6" data-testid="dashboard-pm-overview">
      {/* KPI Cards */}
      <div
        className="grid grid-cols-2 gap-4 md:grid-cols-4 lg:grid-cols-8"
        data-testid="pm-kpi-cards"
      >
        <KpiCard label={t('kpiTotalProjects')} value={String(kpi?.project_count ?? 0)} color="blue" />
        <KpiCard label={t('kpiActive')} value={String(kpi?.active_count ?? 0)} color="amber" />
        <KpiCard label={t('kpiCompleted')} value={String(kpi?.completed_count ?? 0)} color="green" />
        <KpiCard label={t('kpiTotalTasks')} value={String(kpi?.total_tasks ?? 0)} color="indigo" />
        <KpiCard label={t('kpiDoneRate')} value={`${taskCompletionRate}%`} color="emerald" />
        <KpiCard
          label={t('kpiOverdueTasks')}
          value={String(kpi?.overdue_tasks ?? 0)}
          color={kpi?.overdue_tasks ? 'red' : 'green'}
        />
        <KpiCard label={t('kpiTotalHours')} value={String(kpi?.total_hours ?? 0)} color="blue" />
        <KpiCard label={t('kpiBillable')} value={String(kpi?.billable_hours ?? 0)} color="emerald" />
      </div>

      {/* Middle: Status Distribution + Task Distribution */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Project Status Distribution */}
        <div
          className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
          data-testid="pm-project-status-dist"
        >
          <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
            {t('projectStatusDistribution')}
          </h3>
          {statusDist.length === 0 ? (
            <EmptyState text={t('noData')} />
          ) : (
            <div className="space-y-3">
              {statusDist.map((d) => {
                const pct =
                  totalProjects > 0 ? Math.round((Number(d.count) * 100) / totalProjects) : 0;
                const info = STATUS_TEXT_KEYS[d.status] || {
                  textKey: null,
                  color: 'bg-gray-400',
                };
                return (
                  <div key={d.status} className="flex items-center gap-3">
                    <span className="w-20 text-sm text-gray-600 dark:text-gray-400">
                      {info.textKey ? t(info.textKey) : d.status}
                    </span>
                    <div className="h-6 flex-1 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
                      <div
                        className={`h-full ${info.color} rounded-full transition-all`}
                        style={{ width: `${pct}%` }}
                      />
                    </div>
                    <span className="w-12 text-right font-mono text-sm text-gray-900 dark:text-white">
                      {d.count}
                    </span>
                    <span className="w-10 text-right text-xs text-gray-500">{pct}%</span>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Task Status Distribution */}
        <div
          className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
          data-testid="pm-task-status-dist"
        >
          <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
            {t('taskStatusDistribution')}
          </h3>
          {taskDist.length === 0 ? (
            <EmptyState text={t('noData')} />
          ) : (
            <div className="space-y-3">
              {taskDist.map((d) => {
                const pct =
                  totalTaskCount > 0 ? Math.round((Number(d.count) * 100) / totalTaskCount) : 0;
                const info = TASK_STATUS_TEXT_KEYS[d.status] || {
                  textKey: null,
                  color: 'bg-gray-400',
                };
                return (
                  <div key={d.status} className="flex items-center gap-3">
                    <span className="w-20 text-sm text-gray-600 dark:text-gray-400">
                      {info.textKey ? t(info.textKey) : d.status}
                    </span>
                    <div className="h-6 flex-1 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
                      <div
                        className={`h-full ${info.color} rounded-full transition-all`}
                        style={{ width: `${pct}%` }}
                      />
                    </div>
                    <span className="w-12 text-right font-mono text-sm text-gray-900 dark:text-white">
                      {d.count}
                    </span>
                    <span className="w-10 text-right text-xs text-gray-500">{pct}%</span>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      {/* Overdue Tasks */}
      {overdueTasks.length > 0 && (
        <div
          className="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800"
          data-testid="pm-overdue-tasks"
        >
          <div className="flex items-center gap-2 border-b border-gray-200 px-6 py-4 dark:border-gray-700">
            <span className="text-lg">⚠️</span>
            <h3 className="text-sm font-medium text-gray-700 dark:text-gray-300">
              {t('overdueTasksTitle', { count: overdueTasks.length })}
            </h3>
          </div>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800">
                <th className="px-4 py-2 text-left font-medium text-gray-500">{t('colTask')}</th>
                <th className="px-4 py-2 text-left font-medium text-gray-500">{t('colProject')}</th>
                <th className="px-4 py-2 text-left font-medium text-gray-500">
                  {t('colPriority')}
                </th>
                <th className="px-4 py-2 text-left font-medium text-gray-500">{t('colDueDate')}</th>
                <th className="px-4 py-2 text-right font-medium text-gray-500">
                  {t('colOverdueDays')}
                </th>
              </tr>
            </thead>
            <tbody>
              {overdueTasks.slice(0, 15).map((task) => {
                const pri = PRIORITY_TEXT_KEYS[task.pm_task_priority] || {
                  textKey: null,
                  color: 'bg-gray-100 text-gray-600',
                };
                return (
                  <tr
                    key={task.pid}
                    className="border-b border-gray-100 hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
                  >
                    <td className="px-4 py-2 text-gray-900 dark:text-white">
                      {task.pm_task_title}
                    </td>
                    <td className="px-4 py-2 text-gray-600 dark:text-gray-400">
                      {task.pm_project_name}
                    </td>
                    <td className="px-4 py-2">
                      <span
                        className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${pri.color}`}
                      >
                        {pri.textKey ? t(pri.textKey) : task.pm_task_priority}
                      </span>
                    </td>
                    <td className="px-4 py-2 text-gray-600 dark:text-gray-400">
                      {task.pm_task_due_date}
                    </td>
                    <td className="px-4 py-2 text-right">
                      <span
                        className={`font-mono font-medium ${Number(task.overdue_days) > 7 ? 'text-red-600 dark:text-red-400' : 'text-amber-600 dark:text-amber-400'}`}
                      >
                        {task.overdue_days}d
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Project Health Table */}
      <div
        className="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800"
        data-testid="pm-project-health-table"
      >
        <div className="border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <h3 className="text-sm font-medium text-gray-700 dark:text-gray-300">
            {t('projectHealth')}
          </h3>
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800">
              <th className="px-4 py-2 text-left font-medium text-gray-500">{t('colProject')}</th>
              <th className="px-4 py-2 text-left font-medium text-gray-500">{t('colStatus')}</th>
              <th className="px-4 py-2 text-left font-medium text-gray-500">{t('colDept')}</th>
              <th className="px-4 py-2 text-center font-medium text-gray-500">{t('colTasks')}</th>
              <th className="px-4 py-2 text-center font-medium text-gray-500">
                {t('colProgress')}
              </th>
              <th className="px-4 py-2 text-center font-medium text-gray-500">
                {t('colOverdue')}
              </th>
              <th className="px-4 py-2 text-center font-medium text-gray-500">{t('colHealth')}</th>
            </tr>
          </thead>
          <tbody>
            {projects.length === 0 ? (
              <tr>
                <td colSpan={7}>
                  <EmptyState text={t('noData')} />
                </td>
              </tr>
            ) : (
              projects.map((p) => (
                <tr
                  key={p.pid}
                  className="cursor-pointer border-b border-gray-100 hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
                  onClick={() => navigate(`/project-management/projects/${p.pid}`)}
                >
                  <td className="px-4 py-2 font-medium text-gray-900 dark:text-white">
                    {p.pm_project_name}
                  </td>
                  <td className="px-4 py-2">
                    <StatusBadge status={p.pm_project_status} t={t} />
                  </td>
                  <td className="px-4 py-2 text-gray-600 dark:text-gray-400">{p.dept_name}</td>
                  <td className="px-4 py-2 text-center text-gray-600 dark:text-gray-400">
                    <span className="text-green-600 dark:text-green-400">{p.done_tasks}</span>
                    <span className="text-gray-400 dark:text-gray-500">/{p.total_tasks}</span>
                  </td>
                  <td className="px-4 py-2">
                    <div className="flex items-center gap-2">
                      <div className="h-2 flex-1 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
                        <div
                          className={`h-full rounded-full ${
                            p.actual_progress >= p.planned_progress
                              ? 'bg-green-500'
                              : p.planned_progress - p.actual_progress <= 10
                                ? 'bg-amber-500'
                                : 'bg-red-500'
                          }`}
                          style={{ width: `${Math.min(100, p.actual_progress)}%` }}
                        />
                      </div>
                      <span className="w-8 text-right text-xs text-gray-500">
                        {p.actual_progress}%
                      </span>
                    </div>
                  </td>
                  <td className="px-4 py-2 text-center">
                    {p.overdue_tasks > 0 ? (
                      <span className="font-medium text-red-600 dark:text-red-400">
                        {p.overdue_tasks}
                      </span>
                    ) : (
                      <span className="text-gray-400">0</span>
                    )}
                  </td>
                  <td className="px-4 py-2 text-center">
                    <span
                      className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${
                        p.health_status === 'normal'
                          ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300'
                          : p.health_status === 'at_risk'
                            ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
                            : 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
                      }`}
                    >
                      {HEALTH_STATUS_TEXT_KEYS[p.health_status]
                        ? t(HEALTH_STATUS_TEXT_KEYS[p.health_status])
                        : rawEnumLabel(p.health_status, locale)}
                    </span>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
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
  active,
}: {
  label: string;
  value: string;
  color: string;
  onClick?: () => void;
  active?: boolean;
}) {
  return (
    <div
      className={`rounded-lg bg-white p-4 shadow-sm transition-all dark:bg-gray-800 ${
        onClick ? 'cursor-pointer hover:scale-[1.02] hover:shadow-md' : ''
      } ${active ? 'ring-2 ring-blue-500 dark:ring-blue-400' : ''}`}
      onClick={onClick}
    >
      <div className="mb-1 text-xs text-gray-500 dark:text-gray-400">{label}</div>
      <div className={`text-2xl font-bold ${COLOR_MAP[color] || COLOR_MAP.blue}`}>{value}</div>
    </div>
  );
}

function StatusBadge({ status, t }: { status: string; t: ExecutiveDashboardTextFn }) {
  const map: Record<string, { bg: string; textKey: ExecutiveDashboardTextKey }> = {
    PLANNING: {
      bg: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
      textKey: 'statusPlanning',
    },
    in_progress: {
      bg: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
      textKey: 'statusInProgress',
    },
    completed: {
      bg: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
      textKey: 'statusCompleted',
    },
    archived: {
      bg: 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400',
      textKey: 'statusArchived',
    },
  };
  const s: { bg: string; textKey: ExecutiveDashboardTextKey | null } =
    map[status] || { bg: 'bg-gray-100 text-gray-600', textKey: null };
  return (
    <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${s.bg}`}>
      {s.textKey ? t(s.textKey) : status}
    </span>
  );
}

function Spinner() {
  return (
    <div className="flex min-h-[300px] items-center justify-center">
      <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600 dark:border-blue-400" />
    </div>
  );
}

function EmptyState({ text }: { text: string }) {
  return <div className="py-8 text-center text-sm text-gray-400">{text}</div>;
}
