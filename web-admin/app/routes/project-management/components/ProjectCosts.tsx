import { useState, useEffect, useCallback } from 'react';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface CostSummary {
  project_name: string;
  contract_amount: number;
  budget_amount: number;
  actual_cost: number;
  profit: number;
  profit_rate: number;
  received_amount: number;
  payment_rate: number;
  budget_warning: boolean;
}

interface BudgetVariance {
  category: string;
  budget_amount: number;
  actual_amount: number;
  variance: number;
  exec_rate: number;
  status: string;
}

interface CostByCategory {
  category: string;
  amount: number;
  percentage: number;
}

interface MonthlyCost {
  month: string;
  category: string;
  amount: number;
  cumulative: number;
}

type CostView = 'detail' | 'budget' | 'trend' | 'warnings';

interface ProjectCostsProps {
  projectId: string;
}

function formatAmount(n: number): string {
  if (Math.abs(n) >= 10000) return `¥${(n / 10000).toFixed(1)}万`;
  return `¥${n.toLocaleString()}`;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function ProjectCosts({ projectId }: ProjectCostsProps) {
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [costView, setCostView] = useState<CostView>('detail');
  const [summary, setSummary] = useState<CostSummary | null>(null);
  const [variance, setVariance] = useState<BudgetVariance[]>([]);
  const [categories, setCategories] = useState<CostByCategory[]>([]);
  const [monthly, setMonthly] = useState<MonthlyCost[]>([]);
  const [loading, setLoading] = useState(true);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [summaryRes, varianceRes, categoryRes, monthlyRes] = await Promise.all([
        get<{ records: CostSummary[] }>('/api/datasource/list', {
          datasourceId: 'nq:cc_project_cost_summary',
          projectId,
          format: 'records',
        }),
        get<{ records: BudgetVariance[] }>('/api/datasource/list', {
          datasourceId: 'nq:cc_budget_variance',
          projectId,
          format: 'records',
        }),
        get<{ records: CostByCategory[] }>('/api/datasource/list', {
          datasourceId: 'nq:cc_cost_by_category',
          projectId,
          format: 'records',
        }),
        get<{ records: MonthlyCost[] }>('/api/datasource/list', {
          datasourceId: 'nq:cc_cost_monthly_detail',
          projectId,
          format: 'records',
        }),
      ]);

      if (ResultHelper.isSuccess(summaryRes) && summaryRes.data?.records?.length) {
        setSummary(summaryRes.data.records[0]);
      }
      if (ResultHelper.isSuccess(varianceRes) && varianceRes.data?.records) {
        setVariance(varianceRes.data.records);
      }
      if (ResultHelper.isSuccess(categoryRes) && categoryRes.data?.records) {
        setCategories(categoryRes.data.records);
      }
      if (ResultHelper.isSuccess(monthlyRes) && monthlyRes.data?.records) {
        setMonthly(monthlyRes.data.records);
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
      <div className="flex min-h-[200px] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
      </div>
    );
  }

  const budgetExecRate =
    summary && summary.budget_amount > 0
      ? Math.round((summary.actual_cost * 100) / summary.budget_amount)
      : 0;

  const viewButtons: { key: CostView; label: string; icon: string }[] = [
    { key: 'detail', label: l('成本明细', 'Detail'), icon: '☰' },
    { key: 'budget', label: l('预算对比', 'Budget vs Actual'), icon: '◫' },
    { key: 'trend', label: l('成本趋势', 'Trend'), icon: '📈' },
    { key: 'warnings', label: l('预警', 'Warnings'), icon: '⚠' },
  ];

  return (
    <div className="space-y-6" data-testid="project-costs">
      {/* KPI Cards */}
      <div className="grid grid-cols-2 gap-4 md:grid-cols-5" data-testid="costs-kpi">
        <KpiCard
          label={l('预算总额', 'Budget')}
          value={formatAmount(summary?.budget_amount || 0)}
          color="blue"
        />
        <KpiCard
          label={l('实际成本', 'Actual Cost')}
          value={formatAmount(summary?.actual_cost || 0)}
          color="amber"
        />
        <KpiCard
          label={l('执行率', 'Exec Rate')}
          value={`${budgetExecRate}%`}
          color={budgetExecRate > 100 ? 'red' : budgetExecRate > 90 ? 'amber' : 'green'}
        />
        <KpiCard
          label={l('利润', 'Profit')}
          value={formatAmount(summary?.profit || 0)}
          color={(summary?.profit || 0) >= 0 ? 'green' : 'red'}
        />
        <KpiCard
          label={l('利润率', 'Profit Rate')}
          value={`${summary?.profit_rate || 0}%`}
          color={
            (summary?.profit_rate || 0) >= 20
              ? 'green'
              : (summary?.profit_rate || 0) >= 10
                ? 'amber'
                : 'red'
          }
        />
      </div>

      {/* View Toggle */}
      <div
        className="flex w-fit items-center gap-1 rounded-lg bg-gray-100 p-1 dark:bg-gray-700"
        data-testid="cost-view-toggle"
      >
        {viewButtons.map((v) => (
          <button
            key={v.key}
            onClick={() => setCostView(v.key)}
            className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
              costView === v.key
                ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-600 dark:text-white'
                : 'text-gray-500 hover:text-gray-700 dark:text-gray-400'
            }`}
            data-testid={`cost-view-${v.key}`}
          >
            <span className="mr-1">{v.icon}</span>
            {v.label}
          </button>
        ))}
      </div>

      {/* View Content */}
      {costView === 'detail' && <DetailView categories={categories} l={l} />}
      {costView === 'budget' && <BudgetView variance={variance} l={l} />}
      {costView === 'trend' && <TrendView monthly={monthly} l={l} />}
      {costView === 'warnings' && <WarningsView variance={variance} l={l} />}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function KpiCard({ label, value, color }: { label: string; value: string; color: string }) {
  const colorMap: Record<string, string> = {
    blue: 'text-blue-600 dark:text-blue-400',
    green: 'text-green-600 dark:text-green-400',
    amber: 'text-amber-600 dark:text-amber-400',
    red: 'text-red-600 dark:text-red-400',
  };
  return (
    <div className="rounded-lg bg-white p-4 shadow-sm dark:bg-gray-800">
      <div className="mb-1 text-xs text-gray-500 dark:text-gray-400">{label}</div>
      <div className={`text-xl font-bold ${colorMap[color] || colorMap.blue}`}>{value}</div>
    </div>
  );
}

function DetailView({
  categories,
  l,
}: {
  categories: CostByCategory[];
  l: (zh: string, en: string) => string;
}) {
  const maxAmount = Math.max(...categories.map((c) => c.amount), 1);
  return (
    <div
      className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
      data-testid="cost-detail-view"
    >
      <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
        {l('成本结构', 'Cost Structure')}
      </h3>
      {categories.length === 0 ? (
        <div className="py-8 text-center text-gray-400">{l('暂无数据', 'No data')}</div>
      ) : (
        <div className="space-y-3">
          {categories.map((c) => (
            <div key={c.category} className="flex items-center gap-3">
              <span className="w-20 truncate text-sm text-gray-600 dark:text-gray-400">
                {c.category}
              </span>
              <div className="h-6 flex-1 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-700">
                <div
                  className="h-full rounded-full bg-blue-500 transition-all dark:bg-blue-400"
                  style={{ width: `${(c.amount / maxAmount) * 100}%` }}
                />
              </div>
              <span className="w-20 text-right font-mono text-sm text-gray-900 dark:text-white">
                {formatAmount(c.amount)}
              </span>
              <span className="w-12 text-right text-xs text-gray-500">{c.percentage}%</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function BudgetView({
  variance,
  l,
}: {
  variance: BudgetVariance[];
  l: (zh: string, en: string) => string;
}) {
  const statusColors: Record<string, string> = {
    OVER: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300',
    WARNING: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
    NORMAL: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
  };
  return (
    <div
      className="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800"
      data-testid="cost-budget-view"
    >
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800">
            <th className="px-4 py-3 text-left font-medium text-gray-500">
              {l('类别', 'Category')}
            </th>
            <th className="px-4 py-3 text-right font-medium text-gray-500">
              {l('预算', 'Budget')}
            </th>
            <th className="px-4 py-3 text-right font-medium text-gray-500">
              {l('实际', 'Actual')}
            </th>
            <th className="px-4 py-3 text-right font-medium text-gray-500">
              {l('偏差', 'Variance')}
            </th>
            <th className="px-4 py-3 text-right font-medium text-gray-500">
              {l('执行率', 'Exec %')}
            </th>
            <th className="px-4 py-3 text-center font-medium text-gray-500">
              {l('状态', 'Status')}
            </th>
          </tr>
        </thead>
        <tbody>
          {variance.length === 0 ? (
            <tr>
              <td colSpan={6} className="px-4 py-12 text-center text-gray-400">
                {l('暂无数据', 'No data')}
              </td>
            </tr>
          ) : (
            variance.map((v) => (
              <tr key={v.category} className="border-b border-gray-100 dark:border-gray-700/50">
                <td className="px-4 py-3 font-medium text-gray-900 dark:text-white">
                  {v.category}
                </td>
                <td className="px-4 py-3 text-right font-mono">{formatAmount(v.budget_amount)}</td>
                <td className="px-4 py-3 text-right font-mono">{formatAmount(v.actual_amount)}</td>
                <td
                  className={`px-4 py-3 text-right font-mono ${v.variance > 0 ? 'text-red-600' : 'text-green-600'}`}
                >
                  {v.variance > 0 ? '+' : ''}
                  {formatAmount(v.variance)}
                </td>
                <td className="px-4 py-3 text-right font-medium">{v.exec_rate}%</td>
                <td className="px-4 py-3 text-center">
                  <span
                    className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${statusColors[v.status] || statusColors.NORMAL}`}
                  >
                    {v.status}
                  </span>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}

function TrendView({
  monthly,
  l,
}: {
  monthly: MonthlyCost[];
  l: (zh: string, en: string) => string;
}) {
  // Group by month for a simple bar chart
  const monthlyTotals = monthly.reduce<Record<string, number>>((acc, m) => {
    acc[m.month] = (acc[m.month] || 0) + m.amount;
    return acc;
  }, {});
  const months = Object.keys(monthlyTotals).sort();
  const maxMonthly = Math.max(...Object.values(monthlyTotals), 1);

  return (
    <div
      className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
      data-testid="cost-trend-view"
    >
      <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
        {l('月度成本趋势', 'Monthly Cost Trend')}
      </h3>
      {months.length === 0 ? (
        <div className="py-8 text-center text-gray-400">{l('暂无数据', 'No data')}</div>
      ) : (
        <div className="flex h-48 items-end gap-2">
          {months.map((m) => {
            const height = (monthlyTotals[m] / maxMonthly) * 100;
            return (
              <div key={m} className="flex flex-1 flex-col items-center gap-1">
                <span className="font-mono text-[10px] text-gray-500">
                  {formatAmount(monthlyTotals[m])}
                </span>
                <div className="flex w-full justify-center">
                  <div
                    className="w-8 rounded-t bg-blue-500 transition-all dark:bg-blue-400"
                    style={{ height: `${Math.max(height, 2)}%` }}
                  />
                </div>
                <span className="text-[10px] text-gray-500">{m.slice(5)}</span>
              </div>
            );
          })}
        </div>
      )}

      {/* Category breakdown table */}
      {monthly.length > 0 && (
        <div className="mt-6">
          <h4 className="mb-2 text-xs font-medium text-gray-500 dark:text-gray-400">
            {l('分类明细', 'Category Breakdown')}
          </h4>
          <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
            {Array.from(new Set(monthly.map((m) => m.category))).map((cat) => {
              const total = monthly
                .filter((m) => m.category === cat)
                .reduce((s, m) => s + m.amount, 0);
              return (
                <div
                  key={cat}
                  className="flex items-center justify-between rounded bg-gray-50 px-3 py-2 dark:bg-gray-700/30"
                >
                  <span className="text-xs text-gray-600 dark:text-gray-400">{cat}</span>
                  <span className="font-mono text-xs font-medium text-gray-900 dark:text-white">
                    {formatAmount(total)}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

function WarningsView({
  variance,
  l,
}: {
  variance: BudgetVariance[];
  l: (zh: string, en: string) => string;
}) {
  const warnings = variance.filter((v) => v.status === 'over' || v.status === 'warning');

  return (
    <div
      className="rounded-lg bg-white p-6 shadow-sm dark:bg-gray-800"
      data-testid="cost-warnings-view"
    >
      <h3 className="mb-4 text-sm font-medium text-gray-700 dark:text-gray-300">
        {l('成本预警', 'Cost Warnings')}
      </h3>
      {warnings.length === 0 ? (
        <div className="py-12 text-center">
          <div className="mb-2 text-4xl">✅</div>
          <div className="text-sm text-gray-500 dark:text-gray-400">
            {l('所有预算执行正常', 'All budgets within limits')}
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          {warnings.map((w) => (
            <div
              key={w.category}
              className={`flex items-center justify-between rounded-lg border p-4 ${
                w.status === 'over'
                  ? 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/10'
                  : 'border-amber-200 bg-amber-50 dark:border-amber-800 dark:bg-amber-900/10'
              }`}
            >
              <div className="flex items-center gap-3">
                <span className="text-xl">{w.status === 'over' ? '🔴' : '🟡'}</span>
                <div>
                  <div className="font-medium text-gray-900 dark:text-white">{w.category}</div>
                  <div className="text-xs text-gray-500">
                    {l('预算', 'Budget')}: {formatAmount(w.budget_amount)} → {l('实际', 'Actual')}:{' '}
                    {formatAmount(w.actual_amount)}
                  </div>
                </div>
              </div>
              <div className="text-right">
                <div
                  className={`text-lg font-bold ${w.status === 'over' ? 'text-red-600' : 'text-amber-600'}`}
                >
                  {w.exec_rate}%
                </div>
                <div className={`text-xs ${w.variance > 0 ? 'text-red-500' : 'text-green-500'}`}>
                  {w.variance > 0 ? '+' : ''}
                  {formatAmount(w.variance)}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
