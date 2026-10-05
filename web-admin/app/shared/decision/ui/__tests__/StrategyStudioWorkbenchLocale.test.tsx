import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StrategyStudioWorkbench } from '../StrategyStudioWorkbench';
import { strategyStudioText } from '../strategyStudioText';
import messages from '../strategyStudio.i18n.json';
import type { DecisionApi } from '../../api/decisionApi';

// The locale contract under test: every chrome string must come from the
// strategyStudio catalog via useI18n().locale, while the built-in zh-CN demo
// data (field labels, value labels, payload names) keeps its source values.
const language = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: language.locale,
    t: (key: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? key,
  }),
}));

// The rule binding block is a separate module with its own locale contract;
// the workbench chrome under test here does not depend on its rendering.
vi.mock('~/ui/smart/decision/DecisionRuleBindingBlock', () => ({
  DecisionRuleBindingBlock: () => null,
}));

function createApi(): DecisionApi {
  return {
    getActionCatalog: vi.fn(async () => ({ actions: [] })),
    listVersions: vi.fn(async () => []),
    getDecisionImpact: vi.fn(async () => ({
      incoming: [],
      outgoing: [],
      risk: { level: 'LOW', summary: 'no blockers' },
    })),
    evaluate: vi.fn(async () => ({
      traceId: 'trace-locale',
      status: 'MATCHED',
      matched: true,
      outputs: {},
    })),
    analyzeTable: vi.fn(async () => ({
      valid: true,
      metrics: {
        ruleCount: 0,
        gapCount: 0,
        overlapCount: 0,
        conflictCount: 0,
        unreachableRuleCount: 0,
        finiteCombinationCount: 0,
        finiteDomainComplete: true,
      },
      errors: [],
      warnings: [],
    })),
    exportTableDmn: vi.fn(async () => ({ valid: true, dmnXml: '<definitions />', errors: [], warnings: [] })),
    importTableDmn: vi.fn(async () => ({ valid: true, dmnXml: '<definitions />', errors: [], warnings: [] })),
    roundTripTableDmn: vi.fn(async () => ({ valid: true, dmnXml: '<definitions />', errors: [], warnings: [] })),
    getDefinition: vi.fn(async () => undefined),
    createDefinition: vi.fn(async () => ({ decisionCode: 'complaint_sla_deadline' })),
    createDraftVersion: vi.fn(async () => ({ pid: 'draft-locale', status: 'DRAFT' })),
    validateVersion: vi.fn(async () => ({ valid: true })),
    publishVersion: vi.fn(async () => ({ pid: 'draft-locale', status: 'PUBLISHED' })),
    listConditionFragments: vi.fn(async () => ({ records: [], total: 0, size: 20, current: 1, pages: 0 })),
    createConditionFragment: vi.fn(async () => ({
      fragmentCode: 'wd_manager_approve_sla_condition',
      pid: 'fragment-locale',
      version: 1,
      status: 'DRAFT',
    })),
    createConditionFragmentVersion: vi.fn(async () => ({
      fragmentCode: 'wd_manager_approve_sla_condition',
      pid: 'fragment-locale',
      version: 1,
      status: 'DRAFT',
    })),
    updateConditionFragmentDraft: vi.fn(async () => ({
      fragmentCode: 'wd_manager_approve_sla_condition',
      pid: 'fragment-locale',
      version: 1,
      status: 'DRAFT',
    })),
    listConditionFragmentVersions: vi.fn(async () => []),
    validateConditionFragmentVersion: vi.fn(async () => ({
      fragmentCode: 'wd_manager_approve_sla_condition',
      pid: 'fragment-locale',
      status: 'VALIDATED',
    })),
    publishConditionFragmentVersion: vi.fn(async () => ({
      fragmentCode: 'wd_manager_approve_sla_condition',
      pid: 'fragment-locale',
      version: 1,
      status: 'PUBLISHED',
    })),
  } as unknown as DecisionApi;
}

function renderWorkbench() {
  return render(<StrategyStudioWorkbench api={createApi()} fields={[]} />);
}

describe('StrategyStudioWorkbench locale contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    language.locale = 'zh-CN';
  });

  it('keeps the default zh-CN chrome byte-identical to the previous literals', () => {
    language.locale = 'zh-CN';
    renderWorkbench();

    expect(screen.getByTestId('strategy-studio')).toBeInTheDocument();
    expect(
      screen.getByTestId('strategy-studio').querySelector('.strategy-studio-header h3'),
    ).toHaveTextContent('主管审批 SLA');
    expect(screen.getByTestId('strategy-scenario-SLA')).toHaveTextContent('进入审批节点');
    expect(screen.getByText('影响面')).toBeInTheDocument();
    expect(screen.getByText('测试运行')).toBeInTheDocument();
    expect(screen.getByText('保存草稿')).toBeInTheDocument();
    expect(screen.getByText('发布')).toBeInTheDocument();
    expect(screen.getByTestId('strategy-consumer-summary')).toHaveTextContent('消费方');
    expect(screen.getByTestId('strategy-consumer-summary')).toHaveTextContent('SLA / 超时通知');
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('SLA 节点');
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('申请人');
    expect(screen.getByTestId('strategy-workspace-tab-rule')).toHaveTextContent('规则配置');
    expect(screen.getByTestId('strategy-workspace-tab-rule')).toHaveTextContent('条件 / 映射 / 测试');
    expect(screen.getByTestId('strategy-workspace-tab-facts')).toHaveTextContent('事实目录');
    expect(screen.getByTestId('strategy-workspace-tab-dmn')).toHaveTextContent('决策表');
    expect(screen.getByTestId('strategy-workspace-tab-review')).toHaveTextContent('片段与发布');
    expect(screen.getByTestId('strategy-dmn-panel')).toHaveTextContent('DMN 决策输出');
    expect(screen.getByTestId('strategy-dmn-panel')).toHaveTextContent('请假审批 SLA 截止时间');
    expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent('条件片段库');
    expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent('暂无条件片段');
    expect(screen.getByTestId('strategy-action-plan')).toHaveTextContent('发送通知');
    expect(screen.getByTestId('strategy-action-plan')).toHaveTextContent('写入审计');
    expect(screen.getByTestId('strategy-action-plan')).not.toHaveTextContent('NOTIFY');
    expect(screen.getByTestId('strategy-action-plan')).toHaveTextContent('消息');
    expect(screen.getByTestId('strategy-action-plan')).toHaveTextContent('治理');
    expect(screen.getByTestId('strategy-studio')).toHaveTextContent('发布检查');
    expect(screen.getByTestId('strategy-studio')).toHaveTextContent('就绪');
    expect(screen.getByTestId('strategy-studio')).toHaveTextContent('字段可解析');
    expect(screen.getByTestId('strategy-studio')).toHaveTextContent('片段版本可用');
    expect(screen.getByTestId('strategy-studio')).toHaveTextContent('影响面已确认');
  });

  it.each(['en', 'en-US', 'en-GB'])('localizes the chrome through the catalog for %s', (locale) => {
    language.locale = locale;
    renderWorkbench();

    expect(
      screen.getByTestId('strategy-studio').querySelector('.strategy-studio-header h3'),
    ).toHaveTextContent('Manager approval SLA');
    expect(screen.getByTestId('strategy-scenario-SLA')).toHaveTextContent('Entering an approval node');
    expect(screen.getByText('Impact')).toBeInTheDocument();
    expect(screen.getByText('Run test')).toBeInTheDocument();
    expect(screen.getByText('Save draft')).toBeInTheDocument();
    expect(screen.getByText('Publish')).toBeInTheDocument();
    expect(screen.getByTestId('strategy-consumer-summary')).toHaveTextContent('Consumer');
    expect(screen.getByTestId('strategy-consumer-summary')).toHaveTextContent(
      'SLA / timeout notification',
    );
    expect(screen.getByTestId('strategy-workspace-tab-rule')).toHaveTextContent('Rule configuration');
    expect(screen.getByTestId('strategy-workspace-tab-facts')).toHaveTextContent('Fact catalog');
    expect(screen.getByTestId('strategy-workspace-tab-dmn')).toHaveTextContent('Decision table');
    expect(screen.getByTestId('strategy-workspace-tab-review')).toHaveTextContent('Fragments & publish');
    expect(screen.getByTestId('strategy-dmn-panel')).toHaveTextContent('DMN decision output');
    expect(screen.getByTestId('strategy-dmn-panel')).toHaveTextContent('Leave approval SLA deadline');
    expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent(
      'Condition fragment library',
    );
    expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent(
      'No condition fragments yet',
    );
    expect(screen.getByTestId('strategy-action-plan')).toHaveTextContent('Send notification');
    expect(screen.getByTestId('strategy-action-plan')).toHaveTextContent('Write audit');
    expect(screen.getByTestId('strategy-action-plan')).toHaveTextContent('Messaging');
    expect(screen.getByTestId('strategy-action-plan')).toHaveTextContent('Governance');
    expect(screen.getByTestId('strategy-studio')).toHaveTextContent('Publish check');
    expect(screen.getByTestId('strategy-studio')).toHaveTextContent('Ready');
    expect(screen.getByTestId('strategy-studio')).toHaveTextContent('Fields resolvable');
    expect(screen.getByTestId('strategy-studio')).toHaveTextContent('Impact confirmed');
    expect(screen.queryByText('主管审批 SLA')).not.toBeInTheDocument();
    // Built-in field vocabulary is authored demo data and stays in its source language.
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('SLA 节点');
  });

  it('falls back to zh-CN for an unmapped locale instead of rendering an empty label', () => {
    language.locale = 'fr-FR';
    renderWorkbench();

    expect(
      screen.getByTestId('strategy-studio').querySelector('.strategy-studio-header h3'),
    ).toHaveTextContent('主管审批 SLA');
    expect(screen.getByText('保存草稿')).toBeInTheDocument();
    expect(screen.queryByText('Save draft')).not.toBeInTheDocument();
  });

  it('switches chrome with locale on rerender while scenario state and payloads stay stable', async () => {
    language.locale = 'zh-CN';
    const api = createApi();
    const evaluate = api.evaluate as ReturnType<typeof vi.fn>;
    const view = render(<StrategyStudioWorkbench api={api} fields={[]} />);

    fireEvent.click(screen.getByTestId('strategy-scenario-BPM'));
    expect(screen.getByTestId('strategy-scenario-BPM')).toHaveAttribute('aria-pressed', 'true');
    expect(
      screen.getByTestId('strategy-studio').querySelector('.strategy-studio-header h3'),
    ).toHaveTextContent('请假审批流程');

    fireEvent.click(screen.getByText('测试运行'));
    await waitFor(() => expect(evaluate).toHaveBeenCalledOnce());
    expect(evaluate).toHaveBeenLastCalledWith(
      expect.objectContaining({ callerType: 'BPM', callerRef: 'wd_leave_approval' }),
    );
    expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent('测试通过 · trace-locale');

    language.locale = 'en-US';
    view.rerender(<StrategyStudioWorkbench api={api} fields={[]} />);

    expect(
      screen.getByTestId('strategy-studio').querySelector('.strategy-studio-header h3'),
    ).toHaveTextContent('Leave approval process');
    expect(screen.queryByText('请假审批流程')).not.toBeInTheDocument();
    // The selected scenario survives the locale flip without refetching catalogs.
    expect(screen.getByTestId('strategy-scenario-BPM')).toHaveAttribute('aria-pressed', 'true');
    expect(api.getActionCatalog).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByText('Run test'));
    await waitFor(() => expect(evaluate).toHaveBeenCalledTimes(2));
    expect(evaluate).toHaveBeenLastCalledWith(
      expect.objectContaining({ callerType: 'BPM', callerRef: 'wd_leave_approval' }),
    );
    expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent(
      'Test matched · trace-locale',
    );
  });
});

describe('strategyStudio catalog contract', () => {
  it('pairs every catalog entry with both zh-CN and en text', () => {
    const entries = Object.entries(messages as Record<string, Record<string, string>>);
    expect(entries.length).toBeGreaterThan(100);
    for (const [key, entry] of entries) {
      expect(entry['zh-CN'], `zh-CN missing for ${key}`).toBeTruthy();
      expect(entry['en'], `en missing for ${key}`).toBeTruthy();
    }
  });

  it('resolves regional variants to en and unknown locales to zh-CN', () => {
    expect(strategyStudioText('saveDraftAction', 'zh-CN')).toBe('保存草稿');
    expect(strategyStudioText('saveDraftAction', 'en')).toBe('Save draft');
    expect(strategyStudioText('saveDraftAction', 'en-US')).toBe('Save draft');
    expect(strategyStudioText('saveDraftAction', 'en-GB')).toBe('Save draft');
    expect(strategyStudioText('saveDraftAction', 'fr-FR')).toBe('保存草稿');
  });

  it('interpolates params and keeps unknown tokens verbatim', () => {
    expect(strategyStudioText('draftSaved', 'zh-CN', { title: '请假审批流程' })).toBe(
      '草稿已保存 · 请假审批流程',
    );
    expect(strategyStudioText('draftSaved', 'en-US', { title: 'Leave approval process' })).toBe(
      'Draft saved · Leave approval process',
    );
    expect(strategyStudioText('fieldContextSuffix', 'zh-CN', { context: '当前记录' })).toBe(
      '当前记录字段',
    );
    expect(strategyStudioText('fragmentLoaded', 'en-US')).toBe('Shared fragment loaded · {name}');
  });

  it('interpolates payload status templates without leaking raw tokens', () => {
    expect(strategyStudioText('impactUpdated', 'zh-CN', { summary: '无阻塞引用', count: 2 })).toBe(
      '无阻塞引用 · 2 个引用',
    );
    expect(strategyStudioText('impactUpdated', 'en-GB', { summary: 'no blockers', count: 2 })).toBe(
      'no blockers · 2 reference(s)',
    );
  });

  // Counter-evidence: deleting a catalog entry (or ignoring the locale argument)
  // turns these fixed-key assertions red — '' from a missing entry never equals
  // the expected text, and a locale-blind resolver cannot return both sides.
  it('fails if an entry is removed or the locale is ignored', () => {
    expect(strategyStudioText('publishAction', 'zh-CN')).toBe('发布');
    expect(strategyStudioText('publishAction', 'en-US')).toBe('Publish');
    expect(strategyStudioText('publishAction', 'en-US')).not.toBe(
      strategyStudioText('publishAction', 'zh-CN'),
    );
    expect(strategyStudioText('publishBlockedCount', 'zh-CN', { count: 3 })).toBe(
      '发布被阻断 · 3 项待处理',
    );
    expect(strategyStudioText('publishBlockedCount', 'en-US', { count: 3 })).toBe(
      'Publishing blocked · 3 item(s) pending',
    );
  });
});
