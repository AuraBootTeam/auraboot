import { useState, useEffect, useCallback } from 'react';
import { get } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';

// ============================================================================
// Types
// ============================================================================

interface TaskStats {
  total_tasks: number;
  todo_count: number;
  in_progress_count: number;
  done_count: number;
  cancelled_count: number;
  overdue_count: number;
}

interface CostSummary {
  contract_amount: number;
  budget_amount: number;
  actual_cost: number;
  profit: number;
  profit_rate: number;
  received_amount: number;
  payment_rate: number;
  budget_warning: boolean;
}

interface CostByCategory {
  category: string;
  amount: number;
  percentage: number;
}

interface PaymentPlan {
  pid: string;
  cc_pp_period: number;
  cc_pp_plan_date: string;
  cc_pp_plan_amount: number;
  cc_pp_actual_amount: number;
  cc_pp_status: string;
}

interface ProjectOverviewProps {
  projectId: string;
}

function formatAmount(n: number): string {
  if (Math.abs(n) >= 10000) return `¥${(n / 10000).toFixed(1)}万`;
  return `¥${n.toLocaleString()}`;
}

// ============================================================================
// Component — Project Health Report
// ============================================================================

export default function ProjectOverview({ projectId }: ProjectOverviewProps) {
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [stats, setStats] = useState<TaskStats | null>(null);
  const [cost, setCost] = useState<CostSummary | null>(null);
  const [categories, setCategories] = useState<CostByCategory[]>([]);
  const [payments, setPayments] = useState<PaymentPlan[]>([]);
  const [loading, setLoading] = useState(true);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [statsRes, costRes, catRes, payRes] = await Promise.all([
        get<{ records: TaskStats[] }>('/api/datasource/list', {
          datasourceId: 'nq:pm_project_task_stats',
          projectId,
          format: 'records',
        }),
        get<{ records: CostSummary[] }>('/api/datasource/list', {
          datasourceId: 'nq:cc_project_cost_summary',
          projectId,
          format: 'records',
        }),
        get<{ records: CostByCategory[] }>('/api/datasource/list', {
          datasourceId: 'nq:cc_cost_by_category',
          projectId,
          format: 'records',
        }),
        get<{ records: PaymentPlan[] }>('/api/dynamic/cc-payment-plan/list', {
          filters: JSON.stringify([
            { fieldName: 'cc_pp_contract_id', operator: 'is_not_null', value: '' },
          ]),
          pageSize: '20',
          sortField: 'cc_pp_period',
          sortOrder: 'asc',
        }),
      ]);

      if (ResultHelper.isSuccess(statsRes) && statsRes.data?.records?.length) {
        setStats(statsRes.data.records[0]);
      }
      if (ResultHelper.isSuccess(costRes) && costRes.data?.records?.length) {
        setCost(costRes.data.records[0]);
      }
      if (ResultHelper.isSuccess(catRes) && catRes.data?.records) {
        setCategories(catRes.data.records);
      }
      if (ResultHelper.isSuccess(payRes) && payRes.data?.records) {
        setPayments(payRes.data.records);
      }
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  if (loading) {
    return (
      <div
        className="flex min-h-[200px] items-center justify-center"
        data-testid="overview-loading"
      >
        <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600 dark:border-blue-400" />
      </div>
    );
  }

  const total = stats?.total_tasks ?? 0;
  const done = stats?.done_count ?? 0;
  const taskPct = total > 0 ? Math.round((done / total) * 100) : 0;
  const budgetExec =
    cost && cost.budget_amount > 0 ? Math.round((cost.actual_cost * 100) / cost.budget_amount) : 0;

  // Risk alerts
  const risks: { level: 'high' | 'medium'; message: string }[] = [];
  if (budgetExec > 100)
    risks.push({
      level: 'high',
      message: l(`预算超支 ${budgetExec}%`, `Budget overrun ${budgetExec}%`),
    });
  else if (budgetExec > 90)
    risks.push({
      level: 'medium',
      message: l(`预算执行率 ${budgetExec}%，接近上限`, `Budget at ${budgetExec}%, near limit`),
    });
  if ((cost?.payment_rate ?? 0) < 30 && (cost?.contract_amount ?? 0) > 0) {
    risks.push({
      level: 'medium',
      message: l(`回款率仅 ${cost!.payment_rate}%`, `Payment rate only ${cost!.payment_rate}%`),
    });
  }
  if ((stats?.overdue_count ?? 0) > 0) {
    risks.push({
      level: 'high',
      message: l(`${stats!.overdue_count} 个任务已逾期`, `${stats!.overdue_count} tasks overdue`),
    });
  }
  const overduePayments = payments.filter(
    (p) =>
      p.cc_pp_status === 'overdue' ||
      (p.cc_pp_status === 'pending' && p.cc_pp_plan_date < new Date().toISOString().slice(0, 10)),
  );
  if (overduePayments.length > 0) {
    risks.push({
      level: 'high',
      message: l(
        `${overduePayments.length} 笔回款逾期`,
        `${overduePayments.length} payments overdue`,
      ),
    });
  }

  return (
    <div className="space-y-6" data-testid="project-overview">
      {/* KPI Gauge Cards */}
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4" data-testid="overview-kpi-cards">
        <GaugeCard
          label={l('合同金额', 'Contract')}
          value={formatAmount(cost?.contract_amount || 0)}
          color="blue"
        />
        <GaugeCard
          label={l('预算执行', 'Budget Exec')}
          value={`${budgetExec}%`}
          gauge={budgetExec}
          color={budgetExec > 100 ? 'red' : budgetExec > 90 ? 'amber' : 'green'}
        />
        <GaugeCard
          label={l('利润率', 'Profit Rate')}
          value={`${cost?.profit_rate || 0}%`}
          gauge={cost?.profit_rate || 0}
          color={
            (cost?.profit_rate || 0) >= 20
              ? 'green'
              : (cost?.profit_rate || 0) >= 10
                ? 'amber'
                : 'red'
          }
        />
        <GaugeCard
          label={l('回款率', 'Payment Rate')}
          value={`${cost?.payment_rate || 0}%`}
          gauge={cost?.payment_rate || 0}
          color={
            (cost?.payment_rate || 0) >= 60
              ? 'green'
              : (cost?.payment_rate || 0) >= 30
                ? 'amber'
                : 'red'
          }
        />
      </div>

      {/* Middle row: Task Progress + Cost Structure */}
      <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
        {/* Task Progress */}
        <div
          className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
          data-testid="overview-task-progress"
        >
          <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
            {l('任务进度', 'Task Progress')}
          </h3>
          <div className="mb-3 flex items-center gap-4">
            <div className="h-4 flex-1 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
              <div
                className="h-full rounded-full bg-green-500 transition-all"
                style={{ width: `${taskPct}%` }}
              />
            </div>
            <span className="text-lg font-bold text-gray-900 dark:text-white">{taskPct}%</span>
          </div>
          <div className="grid grid-cols-4 gap-2 text-center">
            <StatMini label={l('待办', 'todo')} value={stats?.todo_count ?? 0} color="gray" />
            <StatMini
              label={l('进行中', 'wip')}
              value={stats?.in_progress_count ?? 0}
              color="blue"
            />
            <StatMini label={l('已完成', 'Done')} value={done} color="green" />
            <StatMini label={l('逾期', 'Overdue')} value={stats?.overdue_count ?? 0} color="red" />
          </div>
        </div>

        {/* Cost Structure */}
        <div
          className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
          data-testid="overview-cost-structure"
        >
          <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
            {l('成本结构', 'Cost Structure')}
          </h3>
          {categories.length === 0 ? (
            <div className="py-4 text-center text-sm text-gray-400">
              {l('暂无成本数据', 'No cost data')}
            </div>
          ) : (
            <div className="space-y-2">
              {categories.slice(0, 5).map((c) => (
                <div key={c.category} className="flex items-center gap-2">
                  <span className="w-16 truncate text-xs text-gray-500 dark:text-gray-400">
                    {c.category}
                  </span>
                  <div className="h-4 flex-1 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
                    <div
                      className="h-full rounded-full bg-blue-500 dark:bg-blue-400"
                      style={{ width: `${c.percentage}%` }}
                    />
                  </div>
                  <span className="w-10 text-right text-xs font-medium text-gray-600 dark:text-gray-400">
                    {c.percentage}%
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Bottom row: Payment Plan + Risk Alerts */}
      <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
        {/* Payment Plan */}
        <div
          className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
          data-testid="overview-payment-plan"
        >
          <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
            {l('回款计划', 'Payment Plan')}
          </h3>
          {payments.length === 0 ? (
            <div className="py-4 text-center text-sm text-gray-400">
              {l('暂无回款计划', 'No payment plans')}
            </div>
          ) : (
            <div className="space-y-2">
              {payments.slice(0, 5).map((p) => {
                const statusIcon =
                  p.cc_pp_status === 'received'
                    ? '✅'
                    : p.cc_pp_status === 'partial'
                      ? '⏳'
                      : p.cc_pp_status === 'overdue'
                        ? '🔴'
                        : '📅';
                return (
                  <div
                    key={p.pid}
                    className="flex items-center justify-between border-b border-gray-100 py-2 last:border-0 dark:border-gray-700/50"
                  >
                    <div className="flex items-center gap-2">
                      <span>{statusIcon}</span>
                      <span className="text-sm text-gray-600 dark:text-gray-400">
                        {l(`第${p.cc_pp_period}期`, `Period ${p.cc_pp_period}`)}
                      </span>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className="font-mono text-sm text-gray-900 dark:text-white">
                        {formatAmount(p.cc_pp_plan_amount)}
                      </span>
                      <span className="text-xs text-gray-400">{p.cc_pp_plan_date}</span>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Risk Alerts */}
        <div
          className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
          data-testid="overview-risk-alerts"
        >
          <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
            {l('风险预警', 'Risk Alerts')}
          </h3>
          {risks.length === 0 ? (
            <div className="py-4 text-center">
              <div className="mb-1 text-3xl">✅</div>
              <div className="text-sm text-gray-500">{l('项目运行正常', 'Project healthy')}</div>
            </div>
          ) : (
            <div className="space-y-2">
              {risks.map((r, i) => (
                <div
                  key={i}
                  className={`flex items-center gap-3 rounded-lg p-3 ${
                    r.level === 'high'
                      ? 'border border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/10'
                      : 'border border-amber-200 bg-amber-50 dark:border-amber-800 dark:bg-amber-900/10'
                  }`}
                >
                  <span className="text-lg">{r.level === 'high' ? '🔴' : '🟡'}</span>
                  <span
                    className={`text-sm ${r.level === 'high' ? 'text-red-700 dark:text-red-300' : 'text-amber-700 dark:text-amber-300'}`}
                  >
                    {r.message}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ============================================================================
// Sub-components
// ============================================================================

function GaugeCard({
  label,
  value,
  gauge,
  color,
}: {
  label: string;
  value: string;
  gauge?: number;
  color: string;
}) {
  const colorMap: Record<string, { ring: string; text: string }> = {
    blue: { ring: 'stroke-blue-500', text: 'text-blue-600 dark:text-blue-400' },
    green: { ring: 'stroke-green-500', text: 'text-green-600 dark:text-green-400' },
    amber: { ring: 'stroke-amber-500', text: 'text-amber-600 dark:text-amber-400' },
    red: { ring: 'stroke-red-500', text: 'text-red-600 dark:text-red-400' },
  };
  const c = colorMap[color] || colorMap.blue;

  return (
    <div className="flex flex-col items-center rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
      {gauge !== undefined ? (
        <div className="relative mb-2 h-16 w-16">
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
              className={c.ring}
              strokeWidth="3"
              strokeLinecap="round"
              strokeDasharray={`${Math.min(gauge, 100) * 0.97} 100`}
            />
          </svg>
          <div className="absolute inset-0 flex items-center justify-center">
            <span className={`text-sm font-bold ${c.text}`}>{value}</span>
          </div>
        </div>
      ) : (
        <div className={`mb-2 text-2xl font-bold ${c.text}`}>{value}</div>
      )}
      <div className="text-xs text-gray-500 dark:text-gray-400">{label}</div>
    </div>
  );
}

function StatMini({ label, value, color }: { label: string; value: number; color: string }) {
  const colors: Record<string, string> = {
    gray: 'text-gray-600 dark:text-gray-400',
    blue: 'text-blue-600 dark:text-blue-400',
    green: 'text-green-600 dark:text-green-400',
    red: 'text-red-600 dark:text-red-400',
  };
  return (
    <div>
      <div className={`text-lg font-bold ${colors[color] || colors.gray}`}>{value}</div>
      <div className="text-[10px] text-gray-500 dark:text-gray-400">{label}</div>
    </div>
  );
}
