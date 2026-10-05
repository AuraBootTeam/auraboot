import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MissionControl from '../index';
import { missionControlText } from '../missionControlText';
import messages from '../missionControl.i18n.json';

// The locale contract under test: every chrome string on the mission control
// home page must come from the missionControl catalog via useI18n().locale,
// while backend data (agent names, task titles, enum codes like METRIC/error)
// keeps its source values in every locale.
const language = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: language.locale,
    t: (key: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? key,
  }),
}));

const navigate = vi.hoisted(() => vi.fn());

const db = vi.hoisted(() => {
  const nowIso = new Date().toISOString();
  const recentRuns = [
    {
      pid: 'run-1',
      run_status: 'success',
      model: 'gpt-x',
      started_at: nowIso,
      completed_at: nowIso,
      duration_ms: 1500,
      input_tokens: 100,
      output_tokens: 200,
      total_cost: 0.5,
      error_message: '',
      task_title: 'Fetch pricing pages',
      agent_name: 'Scout',
    },
    {
      pid: 'run-2',
      run_status: 'failed',
      model: 'gpt-x',
      started_at: nowIso,
      completed_at: nowIso,
      duration_ms: 0,
      input_tokens: 0,
      output_tokens: 0,
      total_cost: 0,
      error_message: 'boom',
      task_title: '',
      agent_name: 'Analyst',
    },
  ];
  const observations = [
    {
      pid: 'obs-1',
      observation_type: 'METRIC',
      severity: 'error',
      source: 'scheduler',
      agent_id: 'agent-1',
      title: 'Cost spike',
      detail: '{"k":"v"}',
      created_at: nowIso,
    },
  ];
  return {
    'nq:acp_dashboard_kpi': [
      {
        active_tasks: 4,
        running_now: 2,
        pending_approvals: 3,
        active_agents: 5,
        active_missions: 2,
        month_cost: 123.45,
      },
    ],
    'nq:acp_recent_runs': recentRuns,
    'nq:acp_agent_stats': [
      {
        pid: 'agent-1',
        agent_code: 'scout',
        agent_name: 'Scout',
        agent_type: 'collector',
        model: 'gpt-x',
        agent_status: 'active',
        total_runs: 10,
        success_runs: 9,
        success_rate: 90,
        total_cost: 5,
        avg_cost: 0.5,
        last_run_at: nowIso,
      },
    ],
    'nq:acp_daily_activity': [
      {
        activity_date: '2026-09-28',
        activity_count: 6,
        error_count: 1,
        cost_events: 2,
        alert_count: 2,
        total_observations: 9,
      },
    ],
    'nq:acp_cost_by_agent': [
      {
        agent_name: 'Scout',
        agent_id: 'agent-1',
        run_date: '2026-09-28',
        run_count: 3,
        daily_cost: 1.5,
        daily_input_tokens: 10,
        daily_output_tokens: 20,
      },
    ],
    'nq:acp_error_summary': [
      {
        pid: 'err-1',
        agent_id: 'agent-1',
        agent_name: 'Scout',
        run_model: 'gpt-x',
        started_at: '2026-09-28T10:00:00Z',
        duration_ms: 800,
        total_cost: 0.2,
        error_message: 'boom',
        task_title: 'Fetch pricing pages',
      },
    ],
    schedules: [
      {
        pid: 'sched-1',
        schedule_name: 'Daily scan',
        cron_expression: '0 9 * * *',
        schedule_status: 'active',
        agent_id: 'agent-1',
        last_run_at: nowIso,
      },
    ],
    observations,
    defaults: { recentRuns, observations },
  };
});

vi.mock('~/shared/services/http-client', () => ({
  get: vi.fn(async (url: string, params: Record<string, string> = {}) => {
    let records: unknown[] = [];
    if (url === '/api/dynamic/agent-schedule/list') {
      records = db.schedules;
    } else if (url === '/api/dynamic/agent-observation/list') {
      records = db.observations;
    } else {
      records = (db as unknown as Record<string, unknown[]>)[params.datasourceId ?? ''] ?? [];
    }
    return { code: '0', data: { records } };
  }),
  post: vi.fn(async () => ({ code: '0', data: null })),
}));

vi.mock('react-router', () => ({
  useNavigate: () => navigate,
}));

// jsdom has no EventSource; the page subscribes on mount.
class MockEventSource {
  onerror: unknown = null;
  addEventListener() {}
  close() {}
}
vi.stubGlobal('EventSource', MockEventSource);

function renderPage() {
  return render(<MissionControl />);
}

describe('MissionControl locale contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Tab anchors write #analytics/#observations into the URL; reset so every
    // render boots on the dashboard tab regardless of test order.
    window.location.hash = '';
    db['nq:acp_recent_runs'] = db.defaults.recentRuns;
    db.observations = db.defaults.observations;
  });

  afterEach(() => {
    language.locale = 'zh-CN';
  });

  it('keeps the default zh-CN dashboard chrome byte-identical to the previous literals', async () => {
    language.locale = 'zh-CN';
    renderPage();

    // Header + tab navigation
    expect(screen.getByText('AuraBot 工作台')).toBeInTheDocument();
    expect(screen.getByText('待处理')).toBeInTheDocument();
    expect(screen.getByText('仪表盘')).toBeInTheDocument();
    expect(screen.getByText('分析')).toBeInTheDocument();
    expect(screen.getByText('事件日志')).toBeInTheDocument();

    // Scenario workbench
    expect(await screen.findByText('竞对调研 Agent 工作台')).toBeInTheDocument();
    expect(screen.getAllByText('创建调研使命')).toHaveLength(2);
    expect(screen.getByText('企业场景模板')).toBeInTheDocument();
    expect(screen.getByText('默认: 竞对调研')).toBeInTheDocument();
    expect(
      screen.getByText(
        '围绕公开资料抓取、价格页变更、产品更新、招聘信号和销售话术生成，把使命、任务、运行、审批、追踪和产出物串成一条可审计链路。',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('查看任务链路')).toBeInTheDocument();
    expect(screen.getByText('查看产出物')).toBeInTheDocument();
    expect(screen.getByText('进入任务')).toBeInTheDocument();
    expect(screen.getByText('待人工判断')).toBeInTheDocument();
    expect(screen.getByText('处理审批')).toBeInTheDocument();
    expect(screen.getByText('可用 Agent / 调度')).toBeInTheDocument();
    expect(screen.getByText('团队状态')).toBeInTheDocument();

    // Cost alert interpolates the amount
    expect(screen.getByText('月度成本已超预算: $123.45')).toBeInTheDocument();

    // KPI cards
    expect(screen.getByText('活跃使命')).toBeInTheDocument();
    expect(screen.getAllByText('活跃任务')).toHaveLength(2);
    expect(screen.getByText('运行中')).toBeInTheDocument();
    expect(screen.getByText('待审批')).toBeInTheDocument();
    expect(screen.getByText('活跃 Agent')).toBeInTheDocument();
    expect(screen.getByText('本月成本')).toBeInTheDocument();

    // Workflow map steps + interpolated metas
    expect(screen.getByText('竞对调研执行链路')).toBeInTheDocument();
    expect(screen.getByText('每个入口只承担一个环节，避免资源菜单重复堆叠。')).toBeInTheDocument();
    expect(screen.getAllByText('使命')).toHaveLength(2);
    expect(screen.getByText('任务拆解')).toBeInTheDocument();
    expect(screen.getByText('运行与追踪')).toBeInTheDocument();
    expect(screen.getByText('人工介入')).toBeInTheDocument();
    expect(screen.getAllByText('产出物')).toHaveLength(2);
    expect(screen.getByText('2 个活跃使命')).toBeInTheDocument();
    expect(screen.getByText('4 个活跃任务')).toBeInTheDocument();
    expect(screen.getByText('2 个运行中')).toBeInTheDocument();
    expect(screen.getByText('3 个待审批')).toBeInTheDocument();
    expect(screen.getByText('报告交付面')).toBeInTheDocument();

    // Command hub groups + link titles
    expect(screen.getByText('执行链路')).toBeInTheDocument();
    expect(screen.getByText('治理与审计')).toBeInTheDocument();
    expect(screen.getByText('团队与记忆')).toBeInTheDocument();
    expect(screen.getByText('能力演进')).toBeInTheDocument();
    expect(screen.getByText('任务')).toBeInTheDocument();
    expect(screen.getByText('运行记录')).toBeInTheDocument();
    expect(screen.getByText('审批')).toBeInTheDocument();
    expect(screen.getByText('中断审计')).toBeInTheDocument();
    expect(screen.getByText('AI 追踪')).toBeInTheDocument();
    expect(screen.getByText('审批策略')).toBeInTheDocument();
    expect(screen.getByText('Agent 定义')).toBeInTheDocument();
    expect(screen.getByText('调度')).toBeInTheDocument();
    expect(screen.getByText('记忆库')).toBeInTheDocument();
    expect(screen.getByText('我的画像')).toBeInTheDocument();
    expect(screen.getByText('技能草稿')).toBeInTheDocument();
    expect(screen.getByText('记忆晋升')).toBeInTheDocument();
    expect(screen.getByText('Soul Profiles 管理')).toBeInTheDocument();

    // Agent overview with backend data kept raw
    expect(screen.getByText('Agent 概览')).toBeInTheDocument();
    expect(screen.getByText('查看全部 Agent', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('90% 成功')).toBeInTheDocument();
    expect(screen.getByText('10 次运行')).toBeInTheDocument();
    expect(screen.getByText('$0.5000 平均')).toBeInTheDocument();

    // Activity feed: user data raw, fallback label from catalog
    expect(screen.getByText('最近活动')).toBeInTheDocument();
    expect(screen.getByText('Fetch pricing pages')).toBeInTheDocument();
    expect(screen.getByText('未关联任务')).toBeInTheDocument();
    expect(screen.getAllByText('刚刚')).toHaveLength(2);

    // Active schedules
    expect(screen.getByText('活跃调度')).toBeInTheDocument();
    expect(screen.getByText('Daily scan')).toBeInTheDocument();
    expect(screen.getByText('上次运行: 刚刚')).toBeInTheDocument();
    expect(screen.getByText('立即运行')).toBeInTheDocument();
  });

  it('keeps zh-CN analytics chrome byte-identical and backend codes raw', async () => {
    language.locale = 'zh-CN';
    renderPage();

    expect(await screen.findByText('竞对调研 Agent 工作台')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('mc-tab-analytics'));
    expect(await screen.findByText('30天成本分布')).toBeInTheDocument();
    expect(screen.getByText('总计: $1.50')).toBeInTheDocument();
    expect(screen.getByText('3 次')).toBeInTheDocument();
    expect(screen.getByText('每日活动')).toBeInTheDocument();
    expect(screen.getByText('日期')).toBeInTheDocument();
    expect(screen.getByText('活动')).toBeInTheDocument();
    expect(screen.getByText('错误')).toBeInTheDocument();
    expect(screen.getByText('告警')).toBeInTheDocument();
    expect(screen.getByText('总计', { exact: true })).toBeInTheDocument();
    expect(screen.getByText('最近错误')).toBeInTheDocument();
    // Backend payload stays raw in every locale.
    expect(screen.getByText('boom')).toBeInTheDocument();
    expect(screen.getByText('Scout')).toBeInTheDocument();
  });

  it('keeps zh-CN observations chrome byte-identical and enum codes untranslated', async () => {
    language.locale = 'zh-CN';
    renderPage();

    expect(await screen.findByText('竞对调研 Agent 工作台')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('mc-tab-observations'));
    expect(await screen.findByText('时间')).toBeInTheDocument();
    expect(screen.getByText('类型')).toBeInTheDocument();
    expect(screen.getByText('严重性')).toBeInTheDocument();
    expect(screen.getByText('来源')).toBeInTheDocument();
    expect(screen.getByText('标题')).toBeInTheDocument();
    // Raw enum codes and user data are never translated.
    expect(screen.getByText('METRIC')).toBeInTheDocument();
    expect(screen.getByText('error')).toBeInTheDocument();
    expect(screen.getByText('Cost spike')).toBeInTheDocument();
    // Expanding a row reveals the localized detail label.
    fireEvent.click(screen.getByText('Cost spike'));
    expect(screen.getByText('详情')).toBeInTheDocument();
  });

  it.each(['en', 'en-US', 'en-GB'])(
    'localizes the dashboard chrome through the catalog for %s',
    async (locale) => {
      language.locale = locale;
      renderPage();

      expect(screen.getByText('AuraBot Workbench')).toBeInTheDocument();
      expect(screen.getByText('Create Research Mission')).toBeInTheDocument();
      expect(screen.getByText('Needs Review')).toBeInTheDocument();
      expect(screen.getByText('Dashboard')).toBeInTheDocument();
      expect(screen.getByText('Analytics')).toBeInTheDocument();
      expect(screen.getByText('Events')).toBeInTheDocument();

      expect(await screen.findByText('Competitive Intelligence Agent Workbench')).toBeInTheDocument();
      expect(screen.getByText('Enterprise scenario')).toBeInTheDocument();
      expect(screen.getByText('Default: competitive intelligence')).toBeInTheDocument();
      expect(screen.getByText('Monthly cost exceeded budget: $123.45')).toBeInTheDocument();

      expect(screen.getByText('Active Missions')).toBeInTheDocument();
      expect(screen.getByText('Active Tasks')).toBeInTheDocument();
      expect(screen.getByText('Running Now')).toBeInTheDocument();
      expect(screen.getByText('Pending Approvals')).toBeInTheDocument();
      expect(screen.getByText('Active Agents')).toBeInTheDocument();
      expect(screen.getByText('Month Cost')).toBeInTheDocument();

      expect(screen.getByText('2 active missions')).toBeInTheDocument();
      expect(screen.getByText('4 active tasks')).toBeInTheDocument();
      expect(screen.getByText('2 running')).toBeInTheDocument();
      expect(screen.getByText('3 pending approvals')).toBeInTheDocument();
      expect(screen.getByText('Delivery surface')).toBeInTheDocument();

      expect(screen.getByText('Agent Overview')).toBeInTheDocument();
      expect(screen.getByText('90% success')).toBeInTheDocument();
      expect(screen.getByText('10 runs')).toBeInTheDocument();
      expect(screen.getByText('$0.5000 avg')).toBeInTheDocument();

      expect(screen.getByText('Recent Activity')).toBeInTheDocument();
      expect(screen.getByText('No task')).toBeInTheDocument();
      expect(screen.getAllByText('just now')).toHaveLength(2);
      expect(screen.getByText('Active Schedules')).toBeInTheDocument();
      expect(screen.getByText('Run Now')).toBeInTheDocument();

      // The zh-CN chrome is gone once an English locale is active.
      expect(screen.queryByText('AuraBot 工作台')).not.toBeInTheDocument();
      expect(screen.queryByText('仪表盘')).not.toBeInTheDocument();
    },
  );

  it('localizes analytics and observations chrome through the catalog in en-US', async () => {
    language.locale = 'en-US';
    renderPage();

    expect(await screen.findByText('Competitive Intelligence Agent Workbench')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('mc-tab-analytics'));
    expect(await screen.findByText('30-Day Cost Breakdown')).toBeInTheDocument();
    expect(screen.getByText('Total: $1.50')).toBeInTheDocument();
    expect(screen.getByText('3 runs')).toBeInTheDocument();
    expect(screen.getByText('Daily Activity')).toBeInTheDocument();
    expect(screen.getByText('Date')).toBeInTheDocument();
    expect(screen.getByText('Activity')).toBeInTheDocument();
    expect(screen.getByText('Errors')).toBeInTheDocument();
    expect(screen.getByText('Alerts')).toBeInTheDocument();
    expect(screen.getByText('Recent Errors')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('mc-tab-observations'));
    expect(await screen.findByText('Time')).toBeInTheDocument();
    expect(screen.getByText('Type')).toBeInTheDocument();
    expect(screen.getByText('Severity')).toBeInTheDocument();
    expect(screen.getByText('Source')).toBeInTheDocument();
    expect(screen.getByText('Title')).toBeInTheDocument();
    // Enum codes stay raw in every locale.
    expect(screen.getByText('METRIC')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Cost spike'));
    expect(screen.getByText('Detail')).toBeInTheDocument();
    expect(screen.queryByText('严重性')).not.toBeInTheDocument();
  });

  it('falls back to zh-CN for an unmapped locale instead of rendering an empty label', async () => {
    language.locale = 'fr-FR';
    renderPage();

    expect(screen.getByText('AuraBot 工作台')).toBeInTheDocument();
    expect(await screen.findByText('竞对调研 Agent 工作台')).toBeInTheDocument();
    expect(screen.queryByText('AuraBot Workbench')).not.toBeInTheDocument();
  });

  it('switches chrome with locale on rerender while data and active tab stay stable', async () => {
    language.locale = 'zh-CN';
    const view = renderPage();

    expect(await screen.findByText('活跃使命')).toBeInTheDocument();
    expect(screen.getByTestId('mc-tab-dashboard').getAttribute('data-active')).toBe('true');
    expect(screen.getByText('Fetch pricing pages')).toBeInTheDocument();

    language.locale = 'en-US';
    view.rerender(<MissionControl />);

    expect(screen.getByText('Active Missions')).toBeInTheDocument();
    expect(screen.getByText('Month Cost')).toBeInTheDocument();
    expect(screen.queryByText('活跃使命')).not.toBeInTheDocument();
    expect(screen.queryByText('本月成本')).not.toBeInTheDocument();
    // Business state survives the locale switch: same tab, same backend rows.
    expect(screen.getByTestId('mc-tab-dashboard').getAttribute('data-active')).toBe('true');
    expect(screen.getByText('Fetch pricing pages')).toBeInTheDocument();
  });

  it('renders the empty states through the catalog', async () => {
    language.locale = 'zh-CN';
    db['nq:acp_recent_runs'] = [];
    db.observations = [];
    renderPage();

    expect(await screen.findByText('暂无运行记录。创建 Agent 和任务后，活动将在此显示。')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('mc-tab-observations'));
    expect(await screen.findByText('暂无事件日志。Agent 活动将在此记录。')).toBeInTheDocument();  });
});

describe('missionControl catalog contract', () => {
  it('pairs every catalog entry with both zh-CN and en text', () => {
    const entries = Object.entries(messages as Record<string, Record<string, string>>);
    expect(entries.length).toBeGreaterThan(100);
    for (const [key, entry] of entries) {
      expect(entry['zh-CN'], `zh-CN missing for ${key}`).toBeTruthy();
      expect(entry['en'], `en missing for ${key}`).toBeTruthy();
    }
  });

  it('resolves regional variants to en and unknown locales to zh-CN', () => {
    expect(missionControlText('workbenchTitle', 'zh-CN')).toBe('AuraBot 工作台');
    expect(missionControlText('workbenchTitle', 'en')).toBe('AuraBot Workbench');
    expect(missionControlText('workbenchTitle', 'en-US')).toBe('AuraBot Workbench');
    expect(missionControlText('workbenchTitle', 'en-GB')).toBe('AuraBot Workbench');
    expect(missionControlText('workbenchTitle', 'fr-FR')).toBe('AuraBot 工作台');
  });

  it('interpolates params and keeps unknown tokens verbatim', () => {
    expect(missionControlText('workflowMissionMeta', 'zh-CN', { count: 3 })).toBe('3 个活跃使命');
    expect(missionControlText('workflowMissionMeta', 'en-GB', { count: 3 })).toBe(
      '3 active missions',
    );
    expect(missionControlText('costAlertOver', 'zh-CN', { amount: '123.45' })).toBe(
      '月度成本已超预算: $123.45',
    );
    expect(missionControlText('costAlertOver', 'en-US', { amount: '123.45' })).toBe(
      'Monthly cost exceeded budget: $123.45',
    );
    // Missing params keep the raw token instead of rendering "undefined".
    expect(missionControlText('workflowMissionMeta', 'en-US')).toBe('{count} active missions');
  });

  // Counter-evidence: deleting a catalog entry (or ignoring the locale argument)
  // turns these fixed-key assertions red — '' from a missing entry never equals
  // the expected text, and a locale-blind resolver cannot return both sides.
  it('fails if an entry is removed or the locale is ignored', () => {
    expect(missionControlText('workbenchTitle', 'zh-CN')).toBe('AuraBot 工作台');
    expect(missionControlText('workbenchTitle', 'en-US')).toBe('AuraBot Workbench');
    expect(missionControlText('workbenchTitle', 'en-US')).not.toBe(
      missionControlText('workbenchTitle', 'zh-CN'),
    );
    // Same zh-CN literal, deliberately distinct en variants per surface.
    expect(missionControlText('createMissionHeader', 'zh-CN')).toBe(
      missionControlText('scenarioCreateMission', 'zh-CN'),
    );
    expect(missionControlText('createMissionHeader', 'en-US')).toBe('Create Research Mission');
    expect(missionControlText('scenarioCreateMission', 'en-US')).toBe('Create research mission');
    expect(missionControlText('createMissionHeader', 'en-US')).not.toBe(
      missionControlText('scenarioCreateMission', 'en-US'),
    );
  });
});
