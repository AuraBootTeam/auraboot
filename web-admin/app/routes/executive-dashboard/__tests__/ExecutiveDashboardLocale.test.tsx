import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ExecutiveDashboard from '../index';
import { executiveDashboardText } from '../executiveDashboardText';
import messages from '../executiveDashboard.i18n.json';

// The locale contract under test: every chrome string on the executive dashboard
// must come from the executiveDashboard catalog via useI18n().locale, while user
// data (project names, departments, unknown enum codes) keeps its source values.
const language = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: language.locale,
    t: (key: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? key,
  }),
}));

const navigate = vi.hoisted(() => vi.fn());

const nqData = vi.hoisted(() => ({
  'nq:cc_dashboard_kpi': [
    {
      project_count: 12,
      contract_total: 234567890,
      received_total: 12345678,
      cost_total: 150000000,
      profit_total: 84567890,
      risk_count: 3,
    },
  ],
  'nq:cc_profit_ranking': [
    {
      pid: 'p1',
      project_name: 'Phoenix',
      contract_amount: 100,
      cost_amount: 60,
      profit_amount: 40,
      profit_rate: 40,
    },
    {
      pid: 'p2',
      project_name: 'Dragon',
      contract_amount: 80,
      cost_amount: 55,
      profit_amount: 25,
      profit_rate: 31.25,
    },
  ],
  'nq:cc_risk_projects': [
    {
      pid: 'p3',
      project_name: 'Risky',
      project_status: 'in_progress',
      risk_type: 'BUDGET_OVERRUN',
      severity: 'high',
      overdue_tasks: 2,
      overdue_payments: 1,
      budget_exec_rate: 120,
    },
    {
      pid: 'p4',
      project_name: 'Novel',
      project_status: 'in_progress',
      risk_type: 'NEW_KIND',
      severity: 'medium',
      overdue_tasks: 1,
      overdue_payments: 0,
      budget_exec_rate: 95,
    },
  ],
  'nq:cc_project_summary_all': [
    {
      pid: 'p1',
      project_name: 'Phoenix',
      project_status: 'in_progress',
      dept_name: 'Sales',
      contract_amount: 100,
      received_amount: 50,
      cost_amount: 60,
      profit: 40,
      profit_rate: 40,
      payment_rate: 50,
    },
    {
      pid: 'p2',
      project_name: 'Dragon',
      project_status: 'PLANNING',
      dept_name: 'RD',
      contract_amount: 0,
      received_amount: 0,
      cost_amount: 0,
      profit: 0,
      profit_rate: 0,
      payment_rate: 0,
    },
  ],
  'nq:cc_dept_profit': [
    {
      dept_name: 'Sales',
      project_count: 5,
      contract_total: 200,
      cost_total: 120,
      profit: 80,
      profit_rate: 40,
    },
  ],
  'nq:cc_payment_overview': [
    {
      pid: 'p1',
      project_name: 'Phoenix',
      due_amount: 100,
      received_amount: 50,
      payment_rate: 50,
      overdue_amount: 10,
      overdue_count: 1,
    },
  ],
  'nq:cc_cost_warning_list': [
    {
      pid: 'p1',
      project_name: 'Phoenix',
      category: 'Cloud',
      budget_amount: 100,
      actual_amount: 120,
      exec_rate: 120,
      variance: 20,
      warning_level: 'over',
    },
  ],
  'nq:cc_progress_health': [
    {
      pid: 'p1',
      project_name: 'Phoenix',
      project_status: 'in_progress',
      planned_progress: 80,
      actual_progress: 65,
      variance: -15,
      total_tasks: 10,
      done_tasks: 6,
      overdue_tasks: 2,
      health_status: 'delayed',
    },
  ],
  'nq:pm_dashboard_kpi': [
    {
      project_count: 8,
      active_count: 5,
      completed_count: 3,
      total_tasks: 40,
      done_tasks: 25,
      overdue_tasks: 4,
      total_hours: 320,
      billable_hours: 210,
    },
  ],
  'nq:pm_project_health_overview': [
    {
      pid: 'p1',
      pm_project_name: 'Phoenix PM',
      pm_project_status: 'in_progress',
      pm_project_client: 'ACME',
      dept_name: 'Delivery',
      planned_progress: 80,
      actual_progress: 65,
      total_tasks: 10,
      done_tasks: 6,
      in_progress_tasks: 2,
      overdue_tasks: 2,
      health_status: 'AT_RISK',
    },
  ],
  'nq:pm_project_status_distribution': [
    { status: 'PLANNING', count: 3 },
    { status: 'weird_status', count: 1 },
  ],
  'nq:pm_task_status_distribution': [{ status: 'TODO', count: 4 }],
  'nq:pm_overdue_tasks': [
    {
      pid: 't1',
      pm_task_title: 'Fix login',
      pm_task_status: 'TODO',
      pm_task_priority: 'CRITICAL',
      pm_task_type: 'DEV',
      pm_task_due_date: '2026-01-01',
      overdue_days: 9,
      pm_project_name: 'Phoenix PM',
    },
  ],
}));

vi.mock('~/shared/services/http-client', () => ({
  get: vi.fn(async (_url: string, params: { datasourceId?: string }) => ({
    code: '0',
    data: {
      records:
        (nqData as Record<string, unknown[]>)[params.datasourceId ?? ''] ?? ([] as unknown[]),
    },
  })),
}));

vi.mock('react-router', () => ({
  useNavigate: () => navigate,
}));

function renderDashboard() {
  return render(<ExecutiveDashboard />);
}

describe('ExecutiveDashboard locale contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    language.locale = 'zh-CN';
  });

  it('keeps the default zh-CN chrome byte-identical to the previous literals', async () => {
    language.locale = 'zh-CN';
    renderDashboard();

    // Header
    expect(screen.getByText('经营驾驶舱')).toBeInTheDocument();
    expect(screen.getByText('数据范围：全部项目（不含已取消/归档）')).toBeInTheDocument();
    // Tab navigation
    expect(screen.getByText('总览')).toBeInTheDocument();
    expect(screen.getByText('利润分析')).toBeInTheDocument();
    expect(screen.getByText('回款分析')).toBeInTheDocument();
    expect(screen.getByText('成本预警')).toBeInTheDocument();
    expect(screen.getByText('进度健康')).toBeInTheDocument();
    expect(screen.getByText('项目管理')).toBeInTheDocument();
    // KPI cards (fmt units stay 亿/万 in zh-CN)
    expect(await screen.findByText('项目数')).toBeInTheDocument();
    expect(screen.getByText('合同总额')).toBeInTheDocument();
    expect(screen.getByText('¥2.3亿')).toBeInTheDocument();
    expect(screen.getAllByText('已回款')).toHaveLength(2);
    expect(screen.getByText('¥1234.6万')).toBeInTheDocument();
    expect(screen.getByText('总成本')).toBeInTheDocument();
    expect(screen.getByText('总利润')).toBeInTheDocument();
    expect(screen.getAllByText('风险项目')).toHaveLength(2);
    // Panels + table chrome
    expect(screen.getByText('利润排行 TOP 10')).toBeInTheDocument();
    expect(screen.getByText('项目财务明细')).toBeInTheDocument();
    expect(screen.getByText('项目', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('部门')).toBeInTheDocument();
    expect(screen.getByText('状态')).toBeInTheDocument();
    expect(screen.getByText('合同')).toBeInTheDocument();
    expect(screen.getByText('利润率')).toBeInTheDocument();
    // Known risk type maps through the catalog; unknown codes stay raw.
    expect(screen.getByText('预算超支')).toBeInTheDocument();
    expect(screen.getByText('NEW_KIND')).toBeInTheDocument();
    // User data keeps its source values.
    expect(screen.getAllByText('Phoenix').length).toBe(2);
    expect(screen.getByText('Risky')).toBeInTheDocument();

    // KPI-driven table filter keeps working: 合同总额 filters the zero-contract
    // detail-table row out; the profit-ranking entry is unaffected (2 → 1 → 2).
    fireEvent.click(screen.getByText('合同总额'));
    expect(screen.getByText('清除筛选', { exact: false })).toBeInTheDocument();
    expect(screen.getAllByText('Dragon')).toHaveLength(1);
    fireEvent.click(screen.getByText('清除筛选', { exact: false }));
    expect(screen.getAllByText('Dragon')).toHaveLength(2);
  });

  it('keeps the PM tab zh-CN chrome byte-identical and unknown enum codes raw', async () => {
    language.locale = 'zh-CN';
    renderDashboard();

    fireEvent.click(screen.getByTestId('dashboard-tab-pm-overview'));
    expect(await screen.findByText('项目总数')).toBeInTheDocument();
    // KPI card + status badge share the same label in zh-CN.
    expect(screen.getAllByText('进行中').length).toBeGreaterThan(0);
    expect(screen.getAllByText('已完成').length).toBeGreaterThan(0);
    expect(screen.getByText('总任务')).toBeInTheDocument();
    expect(screen.getByText('完成率')).toBeInTheDocument();
    expect(screen.getByText('总工时')).toBeInTheDocument();
    expect(screen.getByText('可计费')).toBeInTheDocument();
    expect(screen.getByText('项目状态分布')).toBeInTheDocument();
    expect(screen.getByText('任务状态分布')).toBeInTheDocument();
    // Known status maps through the catalog; unknown codes stay raw.
    expect(screen.getByText('规划中')).toBeInTheDocument();
    expect(screen.getByText('weird_status')).toBeInTheDocument();
    expect(screen.getByText('待办')).toBeInTheDocument();
    // Overdue tasks panel interpolates the count.
    expect(screen.getByText('逾期任务 (1)')).toBeInTheDocument();
    expect(screen.getByText('优先级')).toBeInTheDocument();
    expect(screen.getByText('截止日期')).toBeInTheDocument();
    expect(screen.getByText('逾期天数')).toBeInTheDocument();
    expect(screen.getByText('紧急')).toBeInTheDocument();
    expect(screen.getByText('项目健康度')).toBeInTheDocument();
    expect(screen.getByText('有风险')).toBeInTheDocument();
  });

  it.each(['en', 'en-US', 'en-GB'])('localizes the chrome through the catalog for %s', async (locale) => {
    language.locale = locale;
    renderDashboard();

    expect(screen.getByText('Executive Dashboard')).toBeInTheDocument();
    expect(screen.getByText('Scope: All projects (excl. cancelled/archived)')).toBeInTheDocument();
    expect(screen.getByText('Profit Analysis')).toBeInTheDocument();
    expect(screen.getByText('Payment Analysis')).toBeInTheDocument();
    expect(screen.getByText('Cost Warning')).toBeInTheDocument();
    expect(screen.getByText('Progress Health')).toBeInTheDocument();
    expect(screen.getByText('Project Management')).toBeInTheDocument();

    expect(await screen.findByText('Contracts')).toBeInTheDocument();
    expect(screen.getByText('¥2.3 × 100000000')).toBeInTheDocument();
    expect(screen.getByText('¥1234.6 × 10000')).toBeInTheDocument();
    expect(screen.getAllByText('Received')).toHaveLength(2);
    expect(screen.getByText('Profit Ranking TOP 10')).toBeInTheDocument();
    expect(screen.getByText('Project Financial Detail')).toBeInTheDocument();
    expect(screen.getByText('Dept')).toBeInTheDocument();
    expect(screen.getByText('Rate')).toBeInTheDocument();
    expect(screen.getByText('Budget overrun')).toBeInTheDocument();
    // Unknown codes fall back to the underscore-spaced code in English locales.
    expect(screen.getByText('NEW KIND')).toBeInTheDocument();
    // User data keeps its source values regardless of locale.
    expect(screen.getAllByText('Phoenix').length).toBe(2);
    // The zh-CN chrome is gone once an English locale is active.
    expect(screen.queryByText('经营驾驶舱')).not.toBeInTheDocument();
    expect(screen.queryByText('项目数')).not.toBeInTheDocument();
  });

  it('renders the PM tab through the catalog in en-US', async () => {
    language.locale = 'en-US';
    renderDashboard();

    fireEvent.click(screen.getByTestId('dashboard-tab-pm-overview'));
    expect(await screen.findByText('Projects')).toBeInTheDocument();
    expect(screen.getByText('Project Status Distribution')).toBeInTheDocument();
    expect(screen.getByText('Task Status Distribution')).toBeInTheDocument();
    expect(screen.getByText('Planning')).toBeInTheDocument();
    // Unknown status codes stay raw in every locale.
    expect(screen.getByText('weird_status')).toBeInTheDocument();
    expect(screen.getByText('Overdue Tasks (1)')).toBeInTheDocument();
    expect(screen.getByText('Priority')).toBeInTheDocument();
    expect(screen.getByText('Due Date')).toBeInTheDocument();
    expect(screen.getByText('Overdue Days')).toBeInTheDocument();
    expect(screen.getByText('Critical')).toBeInTheDocument();
    expect(screen.getByText('Project Health')).toBeInTheDocument();
    expect(screen.getByText('At risk')).toBeInTheDocument();
    expect(screen.queryByText('项目健康度')).not.toBeInTheDocument();
  });

  it('falls back to zh-CN for an unmapped locale instead of rendering an empty label', async () => {
    language.locale = 'fr-FR';
    renderDashboard();

    expect(screen.getByText('经营驾驶舱')).toBeInTheDocument();
    expect(await screen.findByText('项目数')).toBeInTheDocument();
    expect(screen.queryByText('Executive Dashboard')).not.toBeInTheDocument();
  });

  it('switches chrome with locale on rerender while the active tab stays stable', async () => {
    language.locale = 'zh-CN';
    const view = renderDashboard();

    fireEvent.click(screen.getByTestId('dashboard-tab-payment'));
    expect(await screen.findByText('应收总额')).toBeInTheDocument();
    expect(screen.getAllByText('回款率').length).toBeGreaterThan(0);

    language.locale = 'en-US';
    view.rerender(<ExecutiveDashboard />);

    expect(screen.getByText('Total Due')).toBeInTheDocument();
    expect(screen.getByText('Payment Rate')).toBeInTheDocument();
    expect(screen.queryByText('应收总额')).not.toBeInTheDocument();
    expect(screen.queryByText('回款率')).not.toBeInTheDocument();
  });
});

describe('executiveDashboard catalog contract', () => {
  it('pairs every catalog entry with both zh-CN and en text', () => {
    const entries = Object.entries(messages as Record<string, Record<string, string>>);
    expect(entries.length).toBeGreaterThan(100);
    for (const [key, entry] of entries) {
      expect(entry['zh-CN'], `zh-CN missing for ${key}`).toBeTruthy();
      expect(entry['en'], `en missing for ${key}`).toBeTruthy();
    }
  });

  it('resolves regional variants to en and unknown locales to zh-CN', () => {
    expect(executiveDashboardText('tabOverview', 'zh-CN')).toBe('总览');
    expect(executiveDashboardText('tabOverview', 'en')).toBe('Overview');
    expect(executiveDashboardText('tabOverview', 'en-US')).toBe('Overview');
    expect(executiveDashboardText('tabOverview', 'en-GB')).toBe('Overview');
    expect(executiveDashboardText('tabOverview', 'fr-FR')).toBe('总览');
  });

  it('interpolates params and keeps unknown tokens verbatim', () => {
    expect(executiveDashboardText('fmtAmountYi', 'zh-CN', { value: '2.3' })).toBe('¥2.3亿');
    expect(executiveDashboardText('fmtAmountYi', 'en-US', { value: '2.3' })).toBe(
      '¥2.3 × 100000000',
    );
    expect(executiveDashboardText('overdueTasksTitle', 'zh-CN', { count: 3 })).toBe('逾期任务 (3)');
    expect(executiveDashboardText('overdueTasksTitle', 'en-GB', { count: 3 })).toBe(
      'Overdue Tasks (3)',
    );
    expect(executiveDashboardText('tasksProgress', 'zh-CN', { done: 6, total: 10 })).toBe(
      '任务: 6/10',
    );
    // Missing params keep the raw token instead of rendering "undefined".
    expect(executiveDashboardText('overdueTasksTitle', 'en-US')).toBe('Overdue Tasks ({count})');
  });

  // Counter-evidence: deleting a catalog entry (or ignoring the locale argument)
  // turns these fixed-key assertions red — '' from a missing entry never equals
  // the expected text, and a locale-blind resolver cannot return both sides.
  it('fails if an entry is removed or the locale is ignored', () => {
    expect(executiveDashboardText('title', 'zh-CN')).toBe('经营驾驶舱');
    expect(executiveDashboardText('title', 'en-US')).toBe('Executive Dashboard');
    expect(executiveDashboardText('title', 'en-US')).not.toBe(
      executiveDashboardText('title', 'zh-CN'),
    );
    expect(executiveDashboardText('scopeNote', 'zh-CN')).toBe(
      '数据范围：全部项目（不含已取消/归档）',
    );
    expect(executiveDashboardText('scopeNote', 'en-US')).toBe(
      'Scope: All projects (excl. cancelled/archived)',
    );
  });
});
