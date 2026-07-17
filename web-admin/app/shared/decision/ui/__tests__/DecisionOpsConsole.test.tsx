import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import { DecisionOpsConsole } from '../DecisionOpsConsole';
import { type FieldOption } from '../ConditionBuilder';
import type { DecisionApi } from '../../api/decisionApi';
import { I18nProvider } from '~/contexts/I18nContext';

const FIELDS: FieldOption[] = [
  {
    scope: 'record',
    path: 'data.priority',
    label: '优先级',
    dataType: 'enum',
    options: ['HIGH', 'LOW'],
  },
  {
    scope: 'process',
    path: 'nodeId',
    label: 'nodeId',
    dataType: 'string',
  },
];

const DECISIONOPS_ZH = {
  'decisionops.header.eyebrow': '策略工作台',
  'decisionops.header.definitions': '规则定义',
  'decisionops.header.policies': '事件策略',
  'decisionops.header.today': '今日评估',
};

function api(overrides: Partial<DecisionApi> = {}): DecisionApi {
  return {
    listDefinitions: vi.fn(async () => [
      { decisionCode: 'big', decisionName: 'Big', enabled: true },
    ]),
    listPolicies: vi.fn(async () => [
      {
        policyCode: 'p1',
        policyName: 'Policy One',
        eventType: 'FORM_SUBMITTED',
        status: 'PUBLISHED',
        enabled: true,
      },
    ]),
    listPolicyVersions: vi.fn(async () => []),
    validate: vi.fn(async () => ({ valid: true })),
    getLogs: vi.fn(async () => []),
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
    exportTableDmn: vi.fn(async () => ({
      valid: true,
      dmnXml: '<definitions><decisionTable /></definitions>',
      model: undefined,
      errors: [],
      warnings: [],
    })),
    importTableDmn: vi.fn(async () => ({
      valid: true,
      dmnXml: '<definitions><decisionTable /></definitions>',
      model: {
        hitPolicy: 'FIRST',
        inputs: [
          {
            id: 'amount',
            label: 'Amount',
            scope: 'record',
            path: 'data.amount',
            dataType: 'decimal',
          },
        ],
        outputs: [{ id: 'route', label: 'Route', dataType: 'string' }],
        rules: [
          {
            ruleId: 'high',
            priority: 10,
            when: { amount: { operator: 'EQ', value: '', feel: '> 10000' } },
            then: { route: 'director' },
          },
        ],
      },
      errors: [],
      warnings: [],
    })),
    roundTripTableDmn: vi.fn(async () => ({
      valid: true,
      dmnXml: '<definitions><decisionTable /></definitions>',
      model: {
        hitPolicy: 'FIRST',
        inputs: [
          {
            id: 'amount',
            label: 'Amount',
            scope: 'record',
            path: 'data.amount',
            dataType: 'decimal',
          },
        ],
        outputs: [{ id: 'route', label: 'Route', dataType: 'string' }],
        rules: [
          {
            ruleId: 'high',
            priority: 10,
            when: { amount: { operator: 'EQ', value: '', feel: '> 10000' } },
            then: { route: 'director' },
          },
        ],
      },
      errors: [],
      warnings: [],
    })),
    listRollouts: vi.fn(async () => []),
    getRolloutMetrics: vi.fn(async () => ({
      policyPid: 'rollout-1',
      baseline: {
        version: 1,
        evaluations: 0,
        matched: 0,
        errors: 0,
        matchedRate: 0,
        errorRate: 0,
        resultDistribution: {},
      },
      candidate: {
        version: 2,
        evaluations: 0,
        matched: 0,
        errors: 0,
        matchedRate: 0,
        errorRate: 0,
        resultDistribution: {},
      },
    })),
    createRollout: vi.fn(async () => ({ pid: 'rollout-new', status: 'DRAFT' })),
    activateRollout: vi.fn(async () => ({ pid: 'rollout-new', status: 'ACTIVE' })),
    pauseRollout: vi.fn(async () => ({ pid: 'rollout-new', status: 'PAUSED' })),
    promoteRollout: vi.fn(async () => ({ pid: 'rollout-new', status: 'PROMOTED' })),
    rollbackRollout: vi.fn(async () => ({ pid: 'rollout-new', status: 'ROLLED_BACK' })),
    getDecisionImpact: vi.fn(async () => ({
      incoming: [],
      outgoing: [],
      risk: { level: 'LOW', summary: '无阻塞引用' },
    })),
    evaluate: vi.fn(async () => ({
      traceId: 'trace-default',
      status: 'MATCHED',
      matched: true,
      outputs: {},
    })),
    createDefinition: vi.fn(async () => ({ decisionCode: 'approval_routing' })),
    getDefinition: vi.fn(async () => ({ decisionCode: 'approval_routing' })),
    createDraftVersion: vi.fn(async () => ({ pid: 'draft-default', status: 'DRAFT' })),
    validateVersion: vi.fn(async () => ({ valid: true })),
    publishVersion: vi.fn(async () => ({ pid: 'draft-default', status: 'PUBLISHED' })),
    getActionCatalog: vi.fn(async () => ({
      actions: [
        { actionType: 'NOTIFY', label: 'Send notification', handlerAvailable: true, category: 'messaging' },
        { actionType: 'START_PROCESS', label: 'Start BPM process', handlerAvailable: true, category: 'workflow' },
        { actionType: 'ADD_COMMENT', label: 'Add comment', handlerAvailable: true, category: 'collaboration' },
        { actionType: 'PATCH_RECORD', label: 'Patch record', handlerAvailable: true, category: 'data' },
        { actionType: 'WEBHOOK', label: 'Webhook', handlerAvailable: true, category: 'integration' },
        { actionType: 'WRITE_AUDIT', label: 'Write audit', handlerAvailable: true, category: 'governance' },
      ],
    })),
    listConditionFragments: vi.fn(async () => ({
      records: [
        {
          fragmentCode: 'leave_sla_node_match',
          fragmentName: '请假 SLA 节点匹配',
          scopeType: 'SLA',
          scopeRef: 'wd_leave_approval',
          version: 1,
          status: 'PUBLISHED',
          fieldRefs: ['record.data.targetKey'],
          decisionRefs: ['complaint_sla_deadline'],
          conditionSpec: {
            root: {
              type: 'predicate',
              left: { type: 'field', scope: 'record', path: 'data.targetKey' },
              operator: 'EQ',
              right: { type: 'literal', value: 'task_manager_approve' },
            },
          },
        },
      ],
      total: 1,
      current: 1,
      size: 20,
    })),
    ...overrides,
  } as unknown as DecisionApi;
}

function renderConsole(
  initialTab?: Parameters<typeof DecisionOpsConsole>[0]['initialTab'],
  apiOverrides: Partial<DecisionApi> = {},
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const apiInstance = api(apiOverrides);
  return render(
    <QueryClientProvider client={client}>
      <DecisionOpsConsole
        api={apiInstance}
        fields={FIELDS}
        initialTab={initialTab}
        modelFields={[
          { entityCode: 'complaint', path: 'priority', label: '优先级', dataType: 'enum', refs: 3 },
        ]}
        logs={[{ traceId: 't1', policyCode: 'p1', status: 'SUCCESS' }]}
        connectors={[
          { code: 'c1', name: 'Hook', type: 'WEBHOOK', health: 'HEALTHY', enabled: true },
        ]}
        permissionGrants={[{ role: '管理员', caps: { view: true, publish: true } }]}
        dashboard={{
          summary: {
            definitions: 5,
            policies: 2,
            evaluationsToday: 10,
            matched: 8,
            failed: 0,
            retrying: 0,
          },
          exceptions: [],
        }}
      />
    </QueryClientProvider>,
  );
}

function renderConsoleWithoutDashboard(apiOverrides: Partial<DecisionApi> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const apiInstance = api(apiOverrides);
  return render(
    <QueryClientProvider client={client}>
      <DecisionOpsConsole
        api={apiInstance}
        fields={FIELDS}
        modelFields={[
          { entityCode: 'complaint', path: 'priority', label: '优先级', dataType: 'enum', refs: 3 },
        ]}
        logs={[{ traceId: 't1', policyCode: 'p1', status: 'SUCCESS' }]}
        connectors={[
          { code: 'c1', name: 'Hook', type: 'WEBHOOK', health: 'HEALTHY', enabled: true },
        ]}
        permissionGrants={[{ role: '管理员', caps: { view: true, publish: true } }]}
      />
    </QueryClientProvider>,
  );
}

function renderConsoleWithoutModelFields(apiOverrides: Partial<DecisionApi> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const apiInstance = api(apiOverrides);
  return render(
    <QueryClientProvider client={client}>
      <DecisionOpsConsole
        api={apiInstance}
        fields={FIELDS}
        initialTab="model"
        logs={[{ traceId: 't1', policyCode: 'p1', status: 'SUCCESS' }]}
        connectors={[
          { code: 'c1', name: 'Hook', type: 'WEBHOOK', health: 'HEALTHY', enabled: true },
        ]}
        permissionGrants={[{ role: '管理员', caps: { view: true, publish: true } }]}
        dashboard={{
          summary: {
            definitions: 5,
            policies: 2,
            evaluationsToday: 10,
            matched: 8,
            failed: 0,
            retrying: 0,
          },
          exceptions: [],
        }}
      />
    </QueryClientProvider>,
  );
}

function renderStudioWithoutModelFields(apiOverrides: Partial<DecisionApi> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const apiInstance = api(apiOverrides);
  return render(
    <QueryClientProvider client={client}>
      <DecisionOpsConsole
        api={apiInstance}
        fields={FIELDS}
        initialTab="studio"
        logs={[{ traceId: 't1', policyCode: 'p1', status: 'SUCCESS' }]}
        connectors={[
          { code: 'c1', name: 'Hook', type: 'WEBHOOK', health: 'HEALTHY', enabled: true },
        ]}
        permissionGrants={[{ role: '管理员', caps: { view: true, publish: true } }]}
        dashboard={{
          summary: {
            definitions: 5,
            policies: 2,
            evaluationsToday: 10,
            matched: 8,
            failed: 0,
            retrying: 0,
          },
          exceptions: [],
        }}
      />
    </QueryClientProvider>,
  );
}

function renderConsoleWithoutConnectors(apiOverrides: Partial<DecisionApi> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const apiInstance = api(apiOverrides);
  return render(
    <QueryClientProvider client={client}>
      <DecisionOpsConsole
        api={apiInstance}
        fields={FIELDS}
        initialTab="connectors"
        modelFields={[
          { entityCode: 'complaint', path: 'priority', label: '优先级', dataType: 'enum', refs: 3 },
        ]}
        logs={[{ traceId: 't1', policyCode: 'p1', status: 'SUCCESS' }]}
        permissionGrants={[{ role: '管理员', caps: { view: true, publish: true } }]}
        dashboard={{
          summary: {
            definitions: 5,
            policies: 2,
            evaluationsToday: 10,
            matched: 8,
            failed: 0,
            retrying: 0,
          },
          exceptions: [],
        }}
      />
    </QueryClientProvider>,
  );
}

function renderConsoleWithoutPermissionGrants(apiOverrides: Partial<DecisionApi> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const apiInstance = api(apiOverrides);
  return render(
    <QueryClientProvider client={client}>
      <DecisionOpsConsole
        api={apiInstance}
        fields={FIELDS}
        initialTab="permissions"
        modelFields={[
          { entityCode: 'complaint', path: 'priority', label: '优先级', dataType: 'enum', refs: 3 },
        ]}
        logs={[{ traceId: 't1', policyCode: 'p1', status: 'SUCCESS' }]}
        connectors={[
          { code: 'c1', name: 'Hook', type: 'WEBHOOK', health: 'HEALTHY', enabled: true },
        ]}
        dashboard={{
          summary: {
            definitions: 5,
            policies: 2,
            evaluationsToday: 10,
            matched: 8,
            failed: 0,
            retrying: 0,
          },
          exceptions: [],
        }}
      />
    </QueryClientProvider>,
  );
}

describe('DecisionOpsConsole', () => {
  it('localizes the product header summary in zh-CN instead of leaking English counters', () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <I18nProvider initialData={DECISIONOPS_ZH} initialLocale="zh-CN">
        <QueryClientProvider client={client}>
          <DecisionOpsConsole
            api={api()}
            fields={FIELDS}
            dashboard={{
              summary: {
                definitions: 5,
                policies: 2,
                evaluationsToday: 10,
                matched: 8,
                failed: 0,
                retrying: 0,
              },
              exceptions: [],
            }}
          />
        </QueryClientProvider>
      </I18nProvider>,
    );

    expect(screen.getAllByText('策略工作台').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('规则定义 5')).toBeInTheDocument();
    expect(screen.getByText('事件策略 2')).toBeInTheDocument();
    expect(screen.getByText('今日评估 10')).toBeInTheDocument();
    expect(screen.queryByText(/^Strategy Studio$/i)).toBeNull();
    expect(screen.queryByText(/Definitions/i)).toBeNull();
    expect(screen.queryByText(/Policies/i)).toBeNull();
    expect(screen.queryByText(/^Today/i)).toBeNull();
  });

  it('renders the tab bar + the Strategy Studio as the default product entry', async () => {
    renderConsole();
    expect(screen.getByTestId('doc-tab-studio')).toBeInTheDocument();
    expect(screen.getByTestId('doc-tab-definitions')).toBeInTheDocument();
    expect(screen.getByTestId('strategy-studio')).toBeInTheDocument();
    expect(screen.getByTestId('strategy-studio').querySelector('.strategy-studio-header h3')).toHaveTextContent('主管审批 SLA');
    expect(screen.getByTestId('strategy-scenario-SLA')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('strategy-scenario-SLA')).toHaveTextContent('进入审批节点');
    expect(screen.getByTestId('strategy-scenario-SLA')).not.toHaveTextContent('task.enter.approval');
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('SLA 节点');
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('流程节点');
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('当前记录');
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('文本');
    expect(screen.getByTestId('strategy-fact-catalog')).not.toHaveTextContent('record.data.targetKey');
    expect(screen.getByTestId('strategy-fact-catalog')).not.toHaveTextContent('sla.deadlineMinutes');
    expect(screen.getByTestId('strategy-fact-catalog')).not.toHaveTextContent('优先级');
    expect(screen.getByTestId('strategy-dmn-panel')).toHaveTextContent('请假审批 SLA 截止时间');
    expect(screen.getByTestId('strategy-dmn-panel')).not.toHaveTextContent('complaint_sla_deadline');
    await waitFor(() =>
      expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent('请假 SLA 节点匹配'),
    );
    expect(screen.getByTestId('strategy-action-plan')).toHaveTextContent('发送通知');
    expect(screen.getByTestId('strategy-action-plan')).not.toHaveTextContent('NOTIFY');
    expect(screen.getByTestId('decision-rule-binding-block')).toBeInTheDocument();
  });

  it('uses business field labels instead of fragment reference paths in Strategy Studio conditions', async () => {
    renderConsole();

    await waitFor(() =>
      expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent('请假 SLA 节点匹配'),
    );

    const fieldSelect = screen.getByLabelText('field-0') as HTMLSelectElement;
    expect(fieldSelect.selectedOptions[0]).toHaveTextContent('SLA 节点');
    expect(fieldSelect.selectedOptions[0]).not.toHaveTextContent('record.data.targetKey');
    expect(screen.getByTestId('cb-preview')).toHaveTextContent('SLA 节点');
    expect(screen.getByTestId('cb-preview')).toHaveTextContent('主管审批节点');
    expect(screen.getByTestId('cb-preview')).not.toHaveTextContent('record.data.targetKey');
    expect(screen.getByTestId('cb-preview')).not.toHaveTextContent('task_manager_approve');
  });

  it('exposes a direct Strategy Studio workbench jump from the console header', () => {
    renderConsole();
    expect(screen.getByRole('link', { name: '进入工作区' })).toHaveAttribute(
      'href',
      '#strategy-workbench',
    );
    expect(screen.getByTestId('strategy-studio')).toHaveAttribute('id', 'strategy-workbench');
  });

  it('keeps compact Strategy Studio work focused on one workspace panel at a time', () => {
    renderConsole();

    expect(screen.getByTestId('strategy-workspace-tab-rule')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('strategy-workspace-panel-rule')).toHaveAttribute('data-active', 'true');
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveAttribute('data-active', 'false');
    expect(screen.getByTestId('strategy-dmn-panel')).toHaveAttribute('data-active', 'false');
    expect(screen.getByTestId('strategy-workspace-panel-review')).toHaveAttribute('data-active', 'false');

    fireEvent.click(screen.getByTestId('strategy-workspace-tab-dmn'));

    expect(screen.getByTestId('strategy-workspace-tab-dmn')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('strategy-workspace-panel-rule')).toHaveAttribute('data-active', 'false');
    expect(screen.getByTestId('strategy-dmn-panel')).toHaveAttribute('data-active', 'true');

    fireEvent.click(screen.getByTestId('strategy-scenario-BPM'));

    expect(screen.getByTestId('strategy-workspace-tab-rule')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('strategy-workspace-panel-rule')).toHaveAttribute('data-active', 'true');
    expect(screen.getByTestId('strategy-dmn-panel')).toHaveAttribute('data-active', 'false');
  });

  it('keeps Strategy Studio action output labels in the content column', async () => {
    renderConsole();

    await waitFor(() =>
      expect(screen.getByTestId('strategy-action-NOTIFY')).toHaveTextContent('发送通知'),
    );

    const action = screen.getByTestId('strategy-action-NOTIFY');
    const copy = action.querySelector('.strategy-action-copy');

    expect(copy).not.toBeNull();
    expect(copy?.querySelector('strong')).toHaveTextContent('发送通知');
    expect(copy?.querySelector('span')).toHaveTextContent('消息');
  });

  it('hydrates Strategy Studio decisions and fact fields from the Decision Runtime APIs', async () => {
    const listDefinitions = vi.fn(async () => [
      {
        decisionCode: 'approval_routing',
        decisionName: 'API 审批路由',
        enabled: true,
      },
    ]);
    const getFactCatalog = vi.fn(async () => ({
      entities: [
        {
          entityCode: 'wd_leave_request',
          modelCode: 'wd_leave_request',
          label: '请假申请',
          facts: [
            {
              factKey: 'wd_leave_request.amount',
              scope: 'record',
              path: 'record.data.amount',
              label: '申请金额',
              dataType: 'decimal',
              operators: ['EQ', 'GT'],
            },
          ],
        },
        {
          entityCode: 'agent_memory',
          modelCode: 'agent_memory',
          label: 'Agent 记忆',
          facts: [
            {
              scope: 'record',
              path: 'record.data.access_count',
              label: '访问次数',
              dataType: 'integer',
            },
          ],
        },
      ],
    }));
    const getModelFields = vi.fn(async () => {
      throw new Error('Strategy Studio must use fact catalog before legacy model fields');
    });

    renderStudioWithoutModelFields({
      listDefinitions,
      getFactCatalog,
      getModelFields,
    } as unknown as Partial<DecisionApi>);

    await waitFor(() => expect(listDefinitions).toHaveBeenCalledOnce());
    await waitFor(() => expect(getFactCatalog).toHaveBeenCalledOnce());
    expect(getModelFields).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('strategy-scenario-BPM'));

    await waitFor(() =>
      expect(screen.getByLabelText('decision-code')).toHaveTextContent(
        'API 审批路由',
      ),
    );
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('申请金额');
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('请假申请');
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('小数');
    expect(screen.getByTestId('strategy-fact-catalog')).not.toHaveTextContent('record.data.amount');
    expect(screen.getByTestId('strategy-fact-catalog')).not.toHaveTextContent('访问次数');
  });

  it('hydrates the Strategy Studio condition-fragment library from the runtime API', async () => {
    const listConditionFragments = vi.fn(async () => ({
      records: [
        {
          fragmentCode: 'approval_high_amount',
          fragmentName: '高金额审批条件',
          scopeType: 'BPM',
          scopeRef: 'wd_leave_approval',
          version: 3,
          status: 'PUBLISHED',
          fieldRefs: ['record.data.wd_req_days', 'process.nodeId'],
          decisionRefs: ['approval_routing'],
          conditionSpec: {
            root: {
              type: 'predicate',
              left: { type: 'field', scope: 'record', path: 'data.wd_req_days' },
              operator: 'GTE',
              right: { type: 'literal', value: 3 },
            },
          },
        },
      ],
      total: 1,
      current: 1,
      size: 20,
    }));

    renderConsole(undefined, { listConditionFragments } as unknown as Partial<DecisionApi>);

    await waitFor(() =>
      expect(listConditionFragments).toHaveBeenCalledWith({ page: 1, size: 50 }),
    );
    await waitFor(() =>
      expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent('高金额审批条件'),
    );
    expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent('BPM');
    expect(screen.queryByText('高价值紧急客诉 v3')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('strategy-fragment-approval_high_amount'));

    expect(screen.getByTestId('strategy-scenario-BPM')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent(
      '已加载共享片段 · 高金额审批条件',
    );
    expect(screen.getByLabelText('decision-code')).toHaveValue('approval_routing');
  });

  it('shows only the latest Strategy Studio condition-fragment version per fragment code', async () => {
    const listConditionFragments = vi.fn(async () => ({
      records: [
        {
          fragmentCode: 'leave_sla_node_match',
          fragmentName: '请假 SLA 节点匹配',
          scopeType: 'SLA',
          scopeRef: 'wd_leave_approval',
          version: 1,
          status: 'PUBLISHED',
          fieldRefs: ['record.data.targetKey'],
          decisionRefs: ['complaint_sla_deadline'],
        },
        {
          fragmentCode: 'leave_sla_node_match',
          fragmentName: '请假 SLA 节点匹配',
          scopeType: 'SLA',
          scopeRef: 'wd_leave_approval',
          version: 2,
          status: 'VALIDATED',
          fieldRefs: ['record.data.targetKey', 'sla.deadlineMinutes'],
          decisionRefs: ['complaint_sla_deadline'],
        },
      ],
      total: 2,
      current: 1,
      size: 20,
    }));

    renderConsole(undefined, { listConditionFragments } as unknown as Partial<DecisionApi>);

    await waitFor(() =>
      expect(screen.getAllByTestId('strategy-fragment-leave_sla_node_match')).toHaveLength(1),
    );
    expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent('v2');
    expect(screen.getByTestId('strategy-fragment-library')).not.toHaveTextContent('v1');
  });

  it('runs Strategy Studio header actions through the Decision Runtime APIs', async () => {
    const getDecisionImpact = vi.fn(async () => ({
      decisionCode: 'complaint_sla_deadline',
      incoming: [{ sourceType: 'SLA_RULE', sourceCode: 'manager_sla' }],
      outgoing: [],
      risk: { blocking: false, summary: '1 个消费方引用' },
    }));
    const evaluate = vi.fn(async () => ({
      traceId: 'trace-studio',
      decisionCode: 'complaint_sla_deadline',
      status: 'MATCHED',
      matched: true,
      outputs: { deadlineMinutes: 30 },
    }));
    const getDefinition = vi.fn(async () => {
      throw new Error('not found');
    });
    const createDefinition = vi.fn(async () => ({ decisionCode: 'complaint_sla_deadline' }));
    const createDraftVersion = vi.fn(async () => ({ pid: 'draft-studio-1', status: 'DRAFT' }));
    const validateVersion = vi.fn(async () => ({ valid: true }));
    const publishVersion = vi.fn(async () => ({ pid: 'draft-studio-1', status: 'PUBLISHED' }));

    renderConsole(undefined, {
      getDecisionImpact,
      evaluate,
      getDefinition,
      createDefinition,
      createDraftVersion,
      validateVersion,
      publishVersion,
    } as unknown as Partial<DecisionApi>);

    fireEvent.click(screen.getByTestId('strategy-impact-preview'));
    await waitFor(() => expect(getDecisionImpact).toHaveBeenCalledWith('complaint_sla_deadline'));
    await waitFor(() =>
      expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent('1 个消费方引用'),
    );

    fireEvent.click(screen.getByTestId('strategy-run-test'));
    await waitFor(() => expect(evaluate).toHaveBeenCalledOnce());
    expect(evaluate).toHaveBeenCalledWith(
      expect.objectContaining({
        decisionCode: 'complaint_sla_deadline',
        callerType: 'SLA',
        callerRef: 'wd_manager_approve_sla',
      }),
    );
    await waitFor(() =>
      expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent('trace-studio'),
    );

    fireEvent.click(screen.getByTestId('strategy-save-draft'));
    await waitFor(() => expect(createDefinition).toHaveBeenCalledWith(
      expect.objectContaining({
        decisionCode: 'complaint_sla_deadline',
        ownerModule: 'SLA',
      }),
    ));
    expect(createDraftVersion).toHaveBeenCalledWith(
      'complaint_sla_deadline',
      expect.objectContaining({
        kind: 'DECISION_TABLE',
        runtimeAdapter: 'PLATFORM_DECISION_TABLE',
        contentJson: expect.objectContaining({
          hitPolicy: 'FIRST',
          inputs: expect.arrayContaining([
            expect.objectContaining({ id: 'sla_deadlineMinutes', scope: 'sla', path: 'deadlineMinutes' }),
          ]),
        }),
        outputSchemaJson: expect.objectContaining({
          actions: expect.arrayContaining([
            expect.objectContaining({ actionType: 'NOTIFY' }),
            expect.objectContaining({ actionType: 'WRITE_AUDIT' }),
          ]),
        }),
      }),
    );
    expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent('草稿已保存');

    fireEvent.click(screen.getByTestId('strategy-scenario-BPM'));
    fireEvent.click(screen.getByTestId('strategy-publish'));

    await waitFor(() => expect(publishVersion).toHaveBeenCalled());
    expect(validateVersion).toHaveBeenCalledWith('draft-studio-1');
    expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent('发布成功');
    expect(createDraftVersion).toHaveBeenLastCalledWith(
      'approval_routing',
      expect.objectContaining({
        contentJson: expect.objectContaining({
          inputs: expect.arrayContaining([
            expect.objectContaining({
              id: 'process_nodeId',
              scope: 'process',
              path: 'nodeId',
            }),
          ]),
        }),
        contextSchemaJson: expect.objectContaining({
          sample: expect.objectContaining({
            process: expect.objectContaining({ taskKey: 'approval' }),
          }),
        }),
        outputSchemaJson: expect.objectContaining({
          actions: expect.arrayContaining([
            expect.objectContaining({ actionType: 'ADD_COMMENT' }),
            expect.objectContaining({ actionType: 'WRITE_AUDIT' }),
          ]),
        }),
      }),
    );
  });

  it('edits the Strategy Studio DMN table and saves it as the scenario decision table', async () => {
    const createDraftVersion = vi.fn(async (_code: string, _req: unknown) => ({
      pid: 'draft-dmn-1',
      status: 'DRAFT',
    }));

    renderConsole(undefined, {
      createDraftVersion,
    } as unknown as Partial<DecisionApi>);

    expect(screen.getByTestId('strategy-dmn-panel')).toBeInTheDocument();
    expect(screen.getByTestId('decision-table-editor')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('dt-add-rule'));
    fireEvent.change(screen.getByLabelText('feel-0-sla_deadlineMinutes'), {
      target: { value: '> 30' },
    });
    fireEvent.change(screen.getByLabelText('out-0-route'), {
      target: { value: 'escalate' },
    });
    fireEvent.click(screen.getByTestId('strategy-save-draft'));

    await waitFor(() => expect(createDraftVersion).toHaveBeenCalled());
    const [, draftRequest] = createDraftVersion.mock.calls[0];
    expect(draftRequest).toMatchObject({
      kind: 'DECISION_TABLE',
      runtimeAdapter: 'PLATFORM_DECISION_TABLE',
      contentJson: {
        hitPolicy: 'FIRST',
        rules: [
          {
            when: {
              sla_deadlineMinutes: { feel: '> 30' },
            },
            then: { route: 'escalate' },
          },
        ],
      },
    });
  });

  it('exposes the scenario fact catalog inside the Strategy Studio DMN input picker', () => {
    renderConsole();

    fireEvent.click(screen.getByTestId('dt-input-field-picker-0'));

    expect(screen.getByTestId('dt-input-field-picker-panel-0')).toHaveTextContent('SLA 节点');
    expect(screen.getByTestId('dt-input-field-picker-panel-0')).toHaveTextContent('SLA 上下文');

    fireEvent.change(screen.getByLabelText('input-field-search-0'), {
      target: { value: '截止' },
    });

    expect(screen.getByTestId('dt-input-field-picker-panel-0')).toHaveTextContent('截止分钟');
    expect(screen.getByTestId('dt-input-field-picker-panel-0')).not.toHaveTextContent('SLA 节点');
  });

  it('runs Strategy Studio DMN analysis and XML round-trip from the scenario table editor', async () => {
    const analyzeTable = vi.fn(async () => ({
      valid: true,
      metrics: {
        ruleCount: 1,
        gapCount: 0,
        overlapCount: 0,
        conflictCount: 0,
        unreachableRuleCount: 0,
        finiteCombinationCount: 1,
        finiteDomainComplete: true,
      },
      errors: [],
      warnings: [],
    }));
    const exportTableDmn = vi.fn(async () => ({
      valid: true,
      dmnXml: '<definitions id="strategy"><decisionTable /></definitions>',
      errors: [],
      warnings: [],
    }));
    const roundTripTableDmn = vi.fn(async () => ({
      valid: true,
      dmnXml: '<definitions id="strategy-roundtrip"><decisionTable /></definitions>',
      model: {
        hitPolicy: 'FIRST',
        inputs: [
          {
            id: 'sla_deadlineMinutes',
            label: '截止分钟',
            scope: 'sla',
            path: 'deadlineMinutes',
            dataType: 'integer',
          },
        ],
        outputs: [{ id: 'route', label: 'Route', dataType: 'string' }],
        rules: [
          {
            ruleId: 'warn',
            priority: 10,
            when: { sla_deadlineMinutes: { operator: 'EQ', value: '', feel: '> 30' } },
            then: { route: 'escalate' },
          },
        ],
      },
      errors: [],
      warnings: [],
    }));

    renderConsole(undefined, {
      analyzeTable,
      exportTableDmn,
      roundTripTableDmn,
    } as unknown as Partial<DecisionApi>);

    fireEvent.click(screen.getByTestId('dt-add-rule'));
    fireEvent.change(screen.getByLabelText('feel-0-sla_deadlineMinutes'), {
      target: { value: '> 30' },
    });
    fireEvent.click(screen.getByTestId('dt-analyze'));

    await waitFor(() => expect(analyzeTable).toHaveBeenCalledOnce());
    expect(analyzeTable).toHaveBeenCalledWith(
      expect.objectContaining({
        inputs: expect.arrayContaining([
          expect.objectContaining({ id: 'sla_deadlineMinutes', path: 'deadlineMinutes' }),
        ]),
      }),
      'complaint_sla_deadline',
      undefined,
    );
    await waitFor(() => expect(screen.getByTestId('dt-analysis-summary')).toHaveTextContent('规则 1'));

    fireEvent.click(screen.getByTestId('dt-export-dmn'));
    await waitFor(() => expect(exportTableDmn).toHaveBeenCalledOnce());
    expect(screen.getByLabelText('dmn-xml')).toHaveValue(
      '<definitions id="strategy"><decisionTable /></definitions>',
    );

    fireEvent.click(screen.getByTestId('dt-roundtrip-dmn'));
    await waitFor(() => expect(roundTripTableDmn).toHaveBeenCalledOnce());
    await waitFor(() =>
      expect(screen.getByLabelText('feel-0-sla_deadlineMinutes')).toHaveValue('> 30'),
    );
    expect(screen.getByTestId('dt-dmn-status')).toHaveTextContent('Round-trip 通过');
  });

  it('does not report Strategy Studio publish success when the runtime rejects the transition', async () => {
    const validateVersion = vi.fn(async () => ({ valid: true }));
    const publishVersion = vi.fn(async () => null);

    renderConsole(undefined, {
      validateVersion,
      publishVersion,
    } as unknown as Partial<DecisionApi>);

    fireEvent.click(screen.getByTestId('strategy-scenario-BPM'));
    fireEvent.click(screen.getByTestId('strategy-publish'));

    await waitFor(() => expect(validateVersion).toHaveBeenCalledWith('draft-default'));
    await waitFor(() =>
      expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent(
        '发布失败 · 发布接口未返回版本结果',
      ),
    );
  });

  it('shows a stable Strategy Studio test-run failure when the runtime returns no result body', async () => {
    const evaluate = vi.fn(async () => null);

    renderConsole(undefined, {
      evaluate,
    } as unknown as Partial<DecisionApi>);

    fireEvent.click(screen.getByTestId('strategy-run-test'));

    await waitFor(() =>
      expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent(
        '测试失败 · 决策执行无返回结果',
      ),
    );
  });

  it('does not recreate an existing Strategy Studio decision definition before saving a draft', async () => {
    const getDefinition = vi.fn(async () => ({
      decisionCode: 'complaint_sla_deadline',
      decisionName: '投诉 SLA 截止时间',
    }));
    const createDefinition = vi.fn(async () => ({ decisionCode: 'complaint_sla_deadline' }));
    const createDraftVersion = vi.fn(async () => ({ pid: 'draft-existing-1', status: 'DRAFT' }));

    renderConsole(undefined, {
      getDefinition,
      createDefinition,
      createDraftVersion,
    } as unknown as Partial<DecisionApi>);

    fireEvent.click(screen.getByTestId('strategy-save-draft'));

    await waitFor(() => expect(getDefinition).toHaveBeenCalledWith('complaint_sla_deadline'));
    expect(createDefinition).not.toHaveBeenCalled();
    expect(createDraftVersion).toHaveBeenCalledWith(
      'complaint_sla_deadline',
      expect.objectContaining({
        kind: 'DECISION_TABLE',
        runtimeAdapter: 'PLATFORM_DECISION_TABLE',
      }),
    );
  });

  it('switches Strategy Studio scenarios and keeps the reusable rule binding workflow visible', () => {
    renderConsole();

    fireEvent.click(screen.getByTestId('strategy-scenario-BPM'));

    expect(screen.getByTestId('strategy-scenario-BPM')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('strategy-consumer-summary')).toHaveTextContent('BPM');
    expect(screen.getByTestId('strategy-consumer-summary')).toHaveTextContent('进入审批节点');
    expect(screen.getByTestId('strategy-consumer-summary')).not.toHaveTextContent('task.enter.approval');
    expect(screen.getByLabelText('decision-code')).toHaveValue('approval_routing');
    expect(screen.getByTestId('decision-binding-editor')).toBeInTheDocument();
  });

  it('reuses a shared condition fragment to switch consumers and shows operation feedback', async () => {
    const listConditionFragments = vi.fn(async () => ({
      records: [
        {
          fragmentCode: 'approval_high_amount',
          fragmentName: '高金额审批条件',
          scopeType: 'BPM',
          scopeRef: 'wd_leave_approval',
          version: 3,
          status: 'PUBLISHED',
          fieldRefs: ['record.data.wd_req_days', 'process.nodeId'],
          decisionRefs: ['approval_routing'],
          conditionSpec: {
            root: {
              type: 'predicate',
              left: { type: 'field', scope: 'record', path: 'data.wd_req_days' },
              operator: 'GTE',
              right: { type: 'literal', value: 3 },
            },
          },
        },
      ],
      total: 1,
      current: 1,
      size: 20,
    }));
    renderConsole(undefined, { listConditionFragments } as unknown as Partial<DecisionApi>);

    await waitFor(() => expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent('高金额审批条件'));
    fireEvent.click(screen.getByTestId('strategy-fragment-approval_high_amount'));

    expect(screen.getByTestId('strategy-scenario-BPM')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('strategy-consumer-summary')).toHaveTextContent('BPM');
    expect(screen.getByLabelText('decision-code')).toHaveValue('approval_routing');

    fireEvent.click(screen.getByTestId('strategy-run-test'));
    await waitFor(() =>
      expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent('测试通过'),
    );

    fireEvent.click(screen.getByTestId('strategy-publish'));
    await waitFor(() =>
      expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent('发布成功'),
    );
  });

  it('switches to the dashboard tab and renders runtime metrics', () => {
    renderConsole();
    fireEvent.click(screen.getByTestId('doc-tab-dashboard'));
    expect(screen.getByTestId('decision-dashboard')).toBeInTheDocument();
    expect(screen.getByTestId('dd-card-definitions')).toHaveTextContent('5');
  });

  it('loads the default dashboard from the API when no host dashboard is provided', async () => {
    const getDashboard = vi.fn(async () => ({
      summary: {
        definitions: 7,
        policies: 4,
        evaluationsToday: 20,
        matched: 15,
        failed: 1,
        retrying: 1,
        p95LatencyMs: 42,
      },
      exceptions: [
        {
          traceId: 'trace-dash',
          code: 'complaint_sla_deadline',
          status: 'ERROR',
          error: 'adapter timeout',
          time: '2026-06-08T14:00:00Z',
        },
      ],
    }));
    renderConsoleWithoutDashboard({ getDashboard } as unknown as Partial<DecisionApi>);

    await waitFor(() => expect(getDashboard).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByTestId('doc-tab-dashboard'));
    await waitFor(() => expect(screen.getByTestId('dd-card-definitions')).toHaveTextContent('7'));
    expect(screen.getByTestId('dd-card-policies')).toHaveTextContent('4');
    expect(screen.getByTestId('dd-card-p95')).toHaveTextContent('42ms');
    expect(screen.getByTestId('dd-exc-trace-dash')).toHaveTextContent('complaint_sla_deadline');
    expect(screen.getByTestId('dd-exc-trace-dash')).toHaveTextContent('adapter timeout');
  });

  it('loads data model fields from the API when no host field catalogue is provided', async () => {
    const getModelFields = vi.fn(async () => [
      { entityCode: 'record', path: 'data.amount', label: 'amount', dataType: 'decimal', refs: 2 },
      { entityCode: 'record', path: 'data.priority', label: 'priority', dataType: 'enum', refs: 1 },
    ]);
    renderConsoleWithoutModelFields({ getModelFields } as unknown as Partial<DecisionApi>);

    await waitFor(() => expect(getModelFields).toHaveBeenCalledOnce());
    await waitFor(() =>
      expect(screen.getByTestId('dmv-row-record.data.amount')).toBeInTheDocument(),
    );
    expect(screen.getByTestId('dmv-refs-data.amount')).toHaveTextContent('2');
  });

  it('loads connectors from the API when no host connector registry is provided', async () => {
    const listConnectors = vi.fn(async () => [
      {
        code: 'conn-crm',
        name: 'CRM API',
        type: 'REST',
        endpoint: 'https://crm.example.com/api',
        authMode: 'APIKEY',
        health: 'UNKNOWN',
        enabled: true,
      },
    ]);
    renderConsoleWithoutConnectors({ listConnectors } as unknown as Partial<DecisionApi>);

    await waitFor(() => expect(listConnectors).toHaveBeenCalledOnce());
    await waitFor(() => expect(screen.getByTestId('cl-row-conn-crm')).toBeInTheDocument());
    expect(screen.getByTestId('cl-row-conn-crm')).toHaveTextContent('CRM API');
    expect(screen.getByTestId('cl-row-conn-crm')).toHaveAttribute('data-health', 'UNKNOWN');
  });

  it('loads permission governance grants from the API when no host matrix is provided', async () => {
    const getPermissionMatrix = vi.fn(async () => ({
      roles: [
        {
          role: '运营管理员',
          caps: { view: true, test: true, publish: false, approve: true, field: false },
        },
      ],
    }));
    renderConsoleWithoutPermissionGrants({
      getPermissionMatrix,
    } as unknown as Partial<DecisionApi>);

    await waitFor(() => expect(getPermissionMatrix).toHaveBeenCalledOnce());
    await waitFor(() => expect(screen.getByTestId('pm-row-运营管理员')).toBeInTheDocument());
    expect(screen.getByLabelText('运营管理员-view')).toHaveAttribute('data-granted', 'true');
    expect(screen.getByLabelText('运营管理员-publish')).toHaveAttribute('data-granted', 'false');
    expect(screen.getByLabelText('运营管理员-approve')).toHaveAttribute('data-granted', 'true');
  });

  it('switches to Definitions (F5, self-fetching) tab', async () => {
    renderConsole();
    fireEvent.click(screen.getByTestId('doc-tab-definitions'));
    await waitFor(() => expect(screen.getByTestId('ddl-row-big')).toBeInTheDocument());
  });

  it('switches to Event Policy (F2, self-fetching) tab', async () => {
    renderConsole();
    fireEvent.click(screen.getByTestId('doc-tab-policies'));
    await waitFor(() => expect(screen.getByTestId('epl-row-p1')).toBeInTheDocument());
  });

  it('opens the designer with the selected Event Policy context', async () => {
    renderConsole();
    fireEvent.click(screen.getByTestId('doc-tab-policies'));
    await waitFor(() => expect(screen.getByTestId('epl-row-p1')).toBeInTheDocument());

    fireEvent.click(screen.getByTestId('epl-open-designer-p1'));

    expect(screen.getByTestId('doc-panel-designer')).toBeInTheDocument();
    expect(screen.getByTestId('epd-workflow')).toBeInTheDocument();
    expect(screen.getByTestId('epd-trigger-context')).toHaveTextContent('Policy One');
    expect(screen.getByTestId('epd-trigger-context')).toHaveTextContent('p1');
    expect(screen.getByTestId('epd-trigger-context')).toHaveTextContent('FORM_SUBMITTED');
  });

  it('switches to Designer (F3) tab and shows the workflow stepper', () => {
    renderConsole();
    fireEvent.click(screen.getByTestId('doc-tab-designer'));
    expect(screen.getByTestId('epd-workflow')).toBeInTheDocument();
    expect(screen.getByTestId('epd-step-trigger')).toBeInTheDocument();
    expect(screen.getByTestId('epd-step-rules')).toBeInTheDocument();
    expect(screen.getByTestId('epd-step-actions')).toBeInTheDocument();
    expect(screen.getByTestId('epd-step-publish')).toBeInTheDocument();
  });

  it('switches to Decision Tables tab and edits the DMN table draft', () => {
    renderConsole();
    fireEvent.click(screen.getByTestId('doc-tab-tables'));
    expect(screen.getByTestId('decision-table-editor')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('hit-policy'), { target: { value: 'COLLECT' } });
    fireEvent.change(screen.getByLabelText('collect-aggregation'), { target: { value: 'SUM' } });
    fireEvent.click(screen.getByTestId('dt-add-rule'));
    fireEvent.change(screen.getByLabelText('feel-0-amount'), { target: { value: '> 10000' } });
    fireEvent.change(screen.getByLabelText('out-0-route'), { target: { value: 'director' } });
    expect(screen.getByLabelText('feel-0-amount')).toHaveValue('> 10000');
    expect(screen.getByLabelText('out-0-route')).toHaveValue('director');
  });

  it('loads unified fact catalog fields into the independent Decision Tables tab', async () => {
    const getFactCatalog = vi.fn(async () => ({
      entities: [
        {
          entityCode: 'wd_leave_request',
          modelCode: 'wd_leave_request',
          label: '请假申请',
          facts: [
            {
              factKey: 'wd_leave_request.wd_leave_type',
              scope: 'record',
              path: 'record.data.wd_leave_type',
              label: '请假类型',
              dataType: 'dict',
              dictCode: 'wd_leave_type',
              allowedValues: [
                { value: 'annual', label: '年假' },
                { value: 'sick', label: '病假' },
              ],
            },
          ],
        },
      ],
    }));
    const getModelFields = vi.fn(async () => [
      {
        entityCode: 'record',
        path: 'data.legacyOnly',
        label: '旧字段目录',
        dataType: 'string' as const,
      },
    ]);
    const analyzeTable = vi.fn(async () => ({
      valid: true,
      metrics: {
        ruleCount: 0,
        gapCount: 0,
        overlapCount: 0,
        conflictCount: 0,
        unreachableRuleCount: 0,
        finiteCombinationCount: 2,
        finiteDomainComplete: true,
      },
      errors: [],
      warnings: [],
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <DecisionOpsConsole
          api={api({ getFactCatalog, getModelFields, analyzeTable } as unknown as Partial<DecisionApi>)}
          fields={[]}
          initialTab="tables"
        />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(getFactCatalog).toHaveBeenCalledOnce());
    expect(getModelFields).not.toHaveBeenCalled();

    fireEvent.click(await screen.findByTestId('dt-input-field-picker-0'));
    await waitFor(() =>
      expect(screen.getByTestId('dt-input-field-picker-panel-0')).toHaveTextContent('请假类型'),
    );
    expect(screen.getByTestId('dt-input-field-picker-panel-0')).not.toHaveTextContent('旧字段目录');

    fireEvent.click(screen.getByTestId('dt-input-field-option-0-record-data_wd_leave_type'));
    fireEvent.click(screen.getByTestId('dt-analyze'));

    await waitFor(() => expect(analyzeTable).toHaveBeenCalledOnce());
    expect(analyzeTable).toHaveBeenCalledWith(
      expect.objectContaining({
        inputs: expect.arrayContaining([
          expect.objectContaining({
            id: 'record_data_wd_leave_type',
            label: '请假类型',
            scope: 'record',
            path: 'data.wd_leave_type',
            dataType: 'dict',
            allowedValues: ['annual', 'sick'],
          }),
        ]),
      }),
    );
  });

  it('runs DMN table analysis from the Decision Tables tab and renders issue metrics', async () => {
    const analyzeTable = vi.fn(async () => ({
      valid: false,
      metrics: {
        ruleCount: 1,
        gapCount: 1,
        overlapCount: 1,
        conflictCount: 0,
        unreachableRuleCount: 0,
        finiteCombinationCount: 4,
        finiteDomainComplete: false,
      },
      errors: [],
      warnings: [
        {
          code: 'DMN_GAP',
          severity: 'WARNING',
          ruleIds: [],
          inputCombination: { amount: 0 },
          message: 'No rule covers this input combination',
        },
      ],
    }));
    renderConsole('tables', { analyzeTable } as unknown as Partial<DecisionApi>);

    fireEvent.click(screen.getByTestId('dt-add-rule'));
    fireEvent.change(screen.getByLabelText('feel-0-amount'), { target: { value: '> 10000' } });
    fireEvent.click(screen.getByTestId('dt-analyze'));

    await waitFor(() => expect(analyzeTable).toHaveBeenCalledOnce());
    expect(analyzeTable).toHaveBeenCalledWith(
      expect.objectContaining({
        hitPolicy: 'FIRST',
        inputs: expect.arrayContaining([
          expect.objectContaining({ id: 'amount', path: 'data.amount' }),
        ]),
      }),
    );
    await waitFor(() =>
      expect(screen.getByTestId('dt-analysis-panel')).toHaveTextContent('规则缺口'),
    );
    expect(screen.getByTestId('dt-analysis-panel')).not.toHaveTextContent('DMN_GAP');
    expect(screen.getByTestId('dt-metric-gap')).toHaveTextContent('缺口 1');
    await waitFor(() => expect(screen.getByTestId('dt-analysis-summary')).toHaveTextContent('规则 1'));
  });

  it('switches to Release Governance tab and renders the rollout monitor', async () => {
    const listRollouts = vi.fn(async () => [
      {
        pid: 'rollout-console',
        decisionCode: 'complaint_sla_deadline',
        baselineVersion: 1,
        candidateVersion: 2,
        status: 'ACTIVE',
        percentage: 10,
      },
    ]);
    renderConsole('rollouts', { listRollouts } as unknown as Partial<DecisionApi>);

    expect(screen.getByTestId('decision-rollout-monitor')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId('rollout-row-rollout-console')).toBeInTheDocument(),
    );
    expect(screen.getByTestId('rollout-row-rollout-console')).toHaveTextContent('10%');
  });

  it('exports and round-trips DMN XML from the Decision Tables tab', async () => {
    const exportTableDmn = vi.fn(async () => ({
      valid: true,
      dmnXml: '<definitions id="exported"><decisionTable /></definitions>',
      errors: [],
      warnings: [],
    }));
    const roundTripTableDmn = vi.fn(async () => ({
      valid: true,
      dmnXml: '<definitions id="roundtrip"><decisionTable /></definitions>',
      model: {
        hitPolicy: 'FIRST',
        inputs: [
          {
            id: 'amount',
            label: 'Amount',
            scope: 'record',
            path: 'data.amount',
            dataType: 'decimal',
          },
        ],
        outputs: [{ id: 'route', label: 'Route', dataType: 'string' }],
        rules: [
          {
            ruleId: 'high',
            priority: 10,
            when: { amount: { operator: 'EQ', value: '', feel: '> 10000' } },
            then: { route: 'director' },
          },
        ],
      },
      errors: [],
      warnings: [],
    }));
    renderConsole('tables', {
      exportTableDmn,
      roundTripTableDmn,
    } as unknown as Partial<DecisionApi>);

    fireEvent.click(screen.getByTestId('dt-export-dmn'));
    await waitFor(() => expect(exportTableDmn).toHaveBeenCalledOnce());
    expect(screen.getByLabelText('dmn-xml')).toHaveValue(
      '<definitions id="exported"><decisionTable /></definitions>',
    );
    expect(screen.getByTestId('dt-dmn-status')).toHaveTextContent('DMN XML 已导出');

    fireEvent.click(screen.getByTestId('dt-roundtrip-dmn'));
    await waitFor(() => expect(roundTripTableDmn).toHaveBeenCalledOnce());
    await waitFor(() => expect(screen.getByLabelText('feel-0-amount')).toHaveValue('> 10000'));
    expect(screen.getByLabelText('dmn-xml')).toHaveValue(
      '<definitions id="roundtrip"><decisionTable /></definitions>',
    );
    expect(screen.getByTestId('dt-dmn-status')).toHaveTextContent('Round-trip 通过');
  });

  it('switches to Logs / Model / Permissions / Connectors tabs', () => {
    renderConsole();
    fireEvent.click(screen.getByTestId('doc-tab-logs'));
    expect(screen.getByTestId('exec-log-viewer')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('doc-tab-model'));
    expect(screen.getByTestId('data-model-viewer')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('doc-tab-permissions'));
    expect(screen.getByTestId('permission-matrix')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('doc-tab-connectors'));
    expect(screen.getByTestId('connector-list')).toBeInTheDocument();
  });

  it('queries execution logs by traceId from the logs tab', async () => {
    const getLogs = vi.fn(async () => [
      {
        traceId: 'trace-live',
        decisionCode: 'complaint_sla_deadline',
        status: 'MATCHED',
        matchedRulesJson: [{ ruleId: 'R-101' }],
        traceSnapshot: {
          virtualSources: [
            {
              sourceRef: 'virtual.leave_request_summary.v1',
              modelCode: 'leave_request_summary_v',
              recordId: 'REQ-001',
              status: 'RESOLVED',
              fields: { slaRiskScore: 91 },
            },
          ],
        },
        durationMs: 18,
        createdAt: '2026-06-08T14:10:00Z',
      },
    ]);
    renderConsole('logs', { getLogs } as unknown as Partial<DecisionApi>);

    fireEvent.change(screen.getByLabelText('log-trace-id'), { target: { value: 'trace-live' } });
    fireEvent.click(screen.getByTestId('elq-fetch'));

    await waitFor(() => expect(getLogs).toHaveBeenCalledWith('trace-live'));
    expect(screen.getByTestId('elv-row-trace-live')).toHaveTextContent('complaint_sla_deadline');
    expect(screen.getByTestId('elv-row-trace-live')).toHaveTextContent('命中');
    expect(screen.getByTestId('elv-row-trace-live')).not.toHaveTextContent('MATCHED');
    expect(screen.getByTestId('elv-row-trace-live')).toHaveTextContent('R-101');
    expect(screen.getByTestId('elv-row-trace-live')).toHaveTextContent('18ms');
    fireEvent.click(screen.getByTestId('elv-open-trace-live'));
    expect(screen.getByTestId('elv-virtual-sources')).toHaveTextContent('virtual.leave_request_summary.v1');
    expect(screen.getByTestId('elv-virtual-sources')).toHaveTextContent('slaRiskScore');
    expect(screen.getByTestId('elv-virtual-sources')).toHaveTextContent('91');
  });

  it('honors initialTab', () => {
    renderConsole('connectors');
    expect(screen.getByTestId('connector-list')).toBeInTheDocument();
  });
});
