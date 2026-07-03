import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import { DecisionOpsConsole } from '../DecisionOpsConsole';
import { type FieldOption } from '../ConditionBuilder';
import type { DecisionApi } from '../../api/decisionApi';

const FIELDS: FieldOption[] = [
  {
    scope: 'record',
    path: 'data.priority',
    label: '优先级',
    dataType: 'enum',
    options: ['HIGH', 'LOW'],
  },
];

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
  it('renders the tab bar + the Strategy Studio as the default product entry', () => {
    renderConsole();
    expect(screen.getByTestId('doc-tab-studio')).toBeInTheDocument();
    expect(screen.getByTestId('doc-tab-definitions')).toBeInTheDocument();
    expect(screen.getByTestId('strategy-studio')).toBeInTheDocument();
    expect(screen.getByTestId('strategy-scenario-SLA')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('优先级');
    expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent('高价值');
    expect(screen.getByTestId('strategy-action-plan')).toHaveTextContent('NOTIFY');
    expect(screen.getByTestId('decision-rule-binding-block')).toBeInTheDocument();
  });

  it('hydrates Strategy Studio decisions and fact fields from the Decision Runtime APIs', async () => {
    const listDefinitions = vi.fn(async () => [
      {
        decisionCode: 'approval_routing',
        decisionName: 'API 审批路由',
        enabled: true,
      },
    ]);
    const getModelFields = vi.fn(async () => [
      {
        entityCode: 'wd_leave_request',
        path: 'record.data.amount',
        label: '申请金额',
        dataType: 'decimal',
        refs: 4,
      },
    ]);

    renderStudioWithoutModelFields({
      listDefinitions,
      getModelFields,
    } as unknown as Partial<DecisionApi>);

    await waitFor(() => expect(listDefinitions).toHaveBeenCalledOnce());
    await waitFor(() => expect(getModelFields).toHaveBeenCalledOnce());

    fireEvent.click(screen.getByTestId('strategy-scenario-BPM'));

    await waitFor(() =>
      expect(screen.getByLabelText('decision-code')).toHaveTextContent(
        'API 审批路由 (approval_routing)',
      ),
    );
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('申请金额');
    expect(screen.getByTestId('strategy-fact-catalog')).toHaveTextContent('record.data.amount');
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
    expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent('1 个消费方引用');

    fireEvent.click(screen.getByTestId('strategy-run-test'));
    await waitFor(() => expect(evaluate).toHaveBeenCalledOnce());
    expect(evaluate).toHaveBeenCalledWith(
      expect.objectContaining({
        decisionCode: 'complaint_sla_deadline',
        callerType: 'SLA',
        callerRef: 'SLA_ESCALATE_HIGH_VALUE',
      }),
    );
    expect(screen.getByTestId('strategy-operation-status')).toHaveTextContent('trace-studio');

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
        kind: 'SIMPLE_CONDITION',
        runtimeAdapter: 'AST_EVALUATOR',
        outputSchemaJson: expect.objectContaining({
          actions: expect.arrayContaining([
            expect.objectContaining({ actionType: 'NOTIFY' }),
            expect.objectContaining({ actionType: 'PATCH_RECORD' }),
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
          children: expect.arrayContaining([
            expect.objectContaining({
              left: expect.objectContaining({
                scope: 'process',
                path: 'taskKey',
              }),
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
            expect.objectContaining({ actionType: 'START_PROCESS' }),
            expect.objectContaining({ actionType: 'WRITE_AUDIT' }),
          ]),
        }),
      }),
    );
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
        kind: 'SIMPLE_CONDITION',
      }),
    );
  });

  it('switches Strategy Studio scenarios and keeps the reusable rule binding workflow visible', () => {
    renderConsole();

    fireEvent.click(screen.getByTestId('strategy-scenario-BPM'));

    expect(screen.getByTestId('strategy-scenario-BPM')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('strategy-consumer-summary')).toHaveTextContent('BPM');
    expect(screen.getByTestId('strategy-consumer-summary')).toHaveTextContent('task.enter.approval');
    expect(screen.getByLabelText('decision-code')).toHaveValue('approval_routing');
    expect(screen.getByTestId('decision-binding-editor')).toBeInTheDocument();
  });

  it('reuses a shared condition fragment to switch consumers and shows operation feedback', async () => {
    renderConsole();

    fireEvent.click(screen.getByTestId('strategy-fragment-BPM'));

    expect(screen.getByTestId('strategy-scenario-BPM')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('strategy-consumer-summary')).toHaveTextContent('BPM');
    expect(screen.getByTestId('strategy-fragment-library')).toHaveTextContent('高金额审批升级');
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
      expect(screen.getByTestId('dt-analysis-panel')).toHaveTextContent('DMN_GAP'),
    );
    expect(screen.getByTestId('dt-metric-gap')).toHaveTextContent('Gap 1');
    expect(screen.getByTestId('dt-analysis-summary')).toHaveTextContent('规则 1');
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
        durationMs: 18,
        createdAt: '2026-06-08T14:10:00Z',
      },
    ]);
    renderConsole('logs', { getLogs } as unknown as Partial<DecisionApi>);

    fireEvent.change(screen.getByLabelText('log-trace-id'), { target: { value: 'trace-live' } });
    fireEvent.click(screen.getByTestId('elq-fetch'));

    await waitFor(() => expect(getLogs).toHaveBeenCalledWith('trace-live'));
    expect(screen.getByTestId('elv-row-trace-live')).toHaveTextContent('complaint_sla_deadline');
    expect(screen.getByTestId('elv-row-trace-live')).toHaveTextContent('MATCHED');
    expect(screen.getByTestId('elv-row-trace-live')).toHaveTextContent('R-101');
    expect(screen.getByTestId('elv-row-trace-live')).toHaveTextContent('18ms');
  });

  it('honors initialTab', () => {
    renderConsole('connectors');
    expect(screen.getByTestId('connector-list')).toBeInTheDocument();
  });
});
