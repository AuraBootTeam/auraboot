import { describe, it, expect, vi } from 'vitest';
import { createDecisionApi, type HttpClient } from '../decisionApi';
import type { DecisionTable } from '../../table/decisionTable';

function fakeHttp() {
  const calls: { method: string; endpoint: string; body?: unknown; params?: unknown }[] = [];
  // cast via unknown — a generic vi.fn isn't directly assignable to the generic HttpClient methods
  const http = {
    get: vi.fn((endpoint: string, params?: Record<string, unknown>) => {
      calls.push({ method: 'get', endpoint, params });
      return Promise.resolve({ data: { ok: true, endpoint } });
    }),
    post: vi.fn((endpoint: string, body?: unknown) => {
      calls.push({ method: 'post', endpoint, body });
      return Promise.resolve({ data: { status: 'MATCHED', matched: true } });
    }),
    delete: vi.fn((endpoint: string) => {
      calls.push({ method: 'delete', endpoint });
      return Promise.resolve({ data: { ok: true, endpoint } });
    }),
  } as unknown as HttpClient;
  return { http, calls };
}

describe('decisionApi client', () => {
  it('evaluate posts to /api/decision/evaluate and unwraps data', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    const res = await api.evaluate({
      decisionCode: 'big',
      binding: 'LATEST',
      context: { record: { data: {} } },
    });
    expect(calls[0]).toMatchObject({ method: 'post', endpoint: '/decision/evaluate' });
    expect((calls[0].body as { decisionCode: string }).decisionCode).toBe('big');
    expect(res).toMatchObject({ status: 'MATCHED', matched: true });
  });

  it('throws the API error message instead of returning null data', async () => {
    const http = {
      post: vi.fn(async () => ({
        success: false,
        data: null,
        message: 'Cannot publish from status DRAFT. Must be VALIDATED first.',
        code: '35000',
      })),
      get: vi.fn(),
      delete: vi.fn(),
    } as unknown as HttpClient;
    const api = createDecisionApi(http);

    await expect(api.evaluate({ decisionCode: 'big', context: {} })).rejects.toThrow(
      'Cannot publish from status DRAFT. Must be VALIDATED first.',
    );
  });

  it('batchEvaluate posts the request array to /batch-evaluate', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.batchEvaluate([
      { decisionCode: 'a', context: {} },
      { decisionCode: 'b', context: {} },
    ]);
    expect(calls[0].endpoint).toBe('/decision/batch-evaluate');
    expect((calls[0].body as unknown[]).length).toBe(2);
  });

  it('createDraftVersion targets the code path; lifecycle transitions target the pid path', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.createDraftVersion('big', {
      kind: 'SIMPLE_CONDITION',
      runtimeAdapter: 'AST_EVALUATOR',
      contentJson: {},
    });
    await api.publishVersion('pid-123');
    await api.publishVersion('pid-456', { impactAcknowledged: true });
    await api.deprecateVersion('pid-789', { impactAcknowledged: true, note: 'planned retirement' });
    await api.retireVersion('pid-789', { impactAcknowledged: true, note: 'replaced by v2' });
    await api.deleteVersion('pid-draft');
    expect(calls[0].endpoint).toBe('/decision/definitions/big/versions');
    expect(calls[1].endpoint).toBe('/decision/versions/pid-123/publish');
    expect(calls[1].body).toBeUndefined();
    expect(calls[2]).toMatchObject({
      method: 'post',
      endpoint: '/decision/versions/pid-456/publish',
      body: { impactAcknowledged: true },
    });
    expect(calls[3]).toMatchObject({
      method: 'post',
      endpoint: '/decision/versions/pid-789/deprecate',
      body: { impactAcknowledged: true, note: 'planned retirement' },
    });
    expect(calls[4]).toMatchObject({
      method: 'post',
      endpoint: '/decision/versions/pid-789/retire',
      body: { impactAcknowledged: true, note: 'replaced by v2' },
    });
    expect(calls[5]).toMatchObject({
      method: 'delete',
      endpoint: '/decision/versions/pid-draft',
    });
  });

  it('getLogs passes traceId as a query param', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.getLogs('trace-xyz');
    expect(calls[0]).toMatchObject({ method: 'get', endpoint: '/decision/logs' });
    expect(calls[0].params).toMatchObject({ traceId: 'trace-xyz' });
  });

  it('getRecentLogs passes only populated advanced filters', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.getRecentLogs({
      keyword: 'trace-xyz',
      decisionCode: '',
      status: 'MATCHED',
      callerType: 'AUTOMATION',
      matched: true,
      rolloutArm: 'CANDIDATE',
      minDurationMs: 10,
      maxDurationMs: '',
      page: 0,
      size: 50,
    });
    expect(calls[0]).toMatchObject({ method: 'get', endpoint: '/decision/logs/recent' });
    expect(calls[0].params).toMatchObject({
      keyword: 'trace-xyz',
      status: 'MATCHED',
      callerType: 'AUTOMATION',
      matched: true,
      rolloutArm: 'CANDIDATE',
      minDurationMs: 10,
      page: 0,
      size: 50,
    });
    expect(calls[0].params).not.toHaveProperty('decisionCode');
    expect(calls[0].params).not.toHaveProperty('maxDurationMs');
  });

  it('getLogByPid fetches one DSL detail record', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.getLogByPid('log-pid-1');
    expect(calls[0]).toMatchObject({ method: 'get', endpoint: '/decision/logs/log-pid-1' });
  });

  it('getDashboard fetches the DecisionOps dashboard summary', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http) as unknown as { getDashboard: () => Promise<unknown> };
    await api.getDashboard();
    expect(calls[0]).toMatchObject({ method: 'get', endpoint: '/decision/dashboard/summary' });
  });

  it('getModelFields fetches the DecisionOps data model field index', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http) as unknown as { getModelFields: () => Promise<unknown> };
    await api.getModelFields();
    expect(calls[0]).toMatchObject({ method: 'get', endpoint: '/decision/model/fields' });
  });

  it('listConnectors fetches and adapts platform API connectors for the DecisionOps registry', async () => {
    const calls: { method: string; endpoint: string }[] = [];
    const http = {
      get: vi.fn(async (endpoint: string) => {
        calls.push({ method: 'get', endpoint });
        return {
          data: [
            {
              pid: 'conn-crm',
              name: 'CRM API',
              baseUrl: 'https://crm.example.com/api',
              authType: 'api_key',
              enabled: true,
            },
          ],
        };
      }),
      post: vi.fn(),
    } as unknown as HttpClient;

    const api = createDecisionApi(http) as unknown as { listConnectors: () => Promise<unknown[]> };
    const connectors = await api.listConnectors();

    expect(calls[0]).toMatchObject({ method: 'get', endpoint: '/connectors' });
    expect(connectors[0]).toMatchObject({
      code: 'conn-crm',
      name: 'CRM API',
      type: 'REST',
      endpoint: 'https://crm.example.com/api',
      authMode: 'APIKEY',
      health: 'UNKNOWN',
      enabled: true,
    });
  });

  it('getPermissionMatrix fetches the DecisionOps permission governance matrix', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http) as unknown as {
      getPermissionMatrix: () => Promise<unknown>;
    };
    await api.getPermissionMatrix();
    expect(calls[0]).toMatchObject({
      method: 'get',
      endpoint: '/decision/permissions/matrix',
    });
  });

  it('getDecisionImpact fetches the decision impact read model by decision code', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http) as unknown as {
      getDecisionImpact: (code: string) => Promise<unknown>;
    };
    await api.getDecisionImpact('sla_deadline');
    expect(calls[0]).toMatchObject({
      method: 'get',
      endpoint: '/decision/definitions/sla_deadline/impact',
    });
  });

  it('getFieldImpact fetches indexed field impact by field ref', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http) as unknown as {
      getFieldImpact: (fieldRef: string) => Promise<unknown>;
    };
    await api.getFieldImpact('record.data.amount');
    expect(calls[0]).toMatchObject({
      method: 'get',
      endpoint: '/decision/fields/impact',
      params: { fieldRef: 'record.data.amount' },
    });
  });

  it('getIntegrationImpact fetches connector/webhook impact by target refs', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http) as unknown as {
      getIntegrationImpact: (targetType: string, targetCode: string) => Promise<unknown>;
    };
    await api.getIntegrationImpact('CONNECTOR', 'api-1');
    expect(calls[0]).toMatchObject({
      method: 'get',
      endpoint: '/decision/integrations/impact',
      params: { targetType: 'CONNECTOR', targetCode: 'api-1' },
    });
  });

  it('preflightFieldChange posts field/schema guard requests', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.preflightFieldChange({
      fieldRef: 'record.data.amount',
      action: 'DELETE_FIELD',
      impactAcknowledged: true,
    });
    expect(calls[0]).toMatchObject({
      method: 'post',
      endpoint: '/decision/fields/preflight',
      body: {
        fieldRef: 'record.data.amount',
        action: 'DELETE_FIELD',
        impactAcknowledged: true,
      },
    });
  });

  it('analyzeTable posts the editable DMN table model to the stateless analysis endpoint', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    const table: DecisionTable = {
      hitPolicy: 'UNIQUE',
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
      rules: [],
    };

    await api.analyzeTable(table, 'amount_route', 'version-pid');

    expect(calls[0]).toMatchObject({
      method: 'post',
      endpoint: '/decision/tables/analyze',
      body: { decisionCode: 'amount_route', versionPid: 'version-pid', model: table },
    });
  });

  it('exports, imports, and round-trips DMN XML through table endpoints', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    const table: DecisionTable = {
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
      rules: [],
    };

    await api.exportTableDmn(table, 'amount_route', 'amount_route');
    await api.importTableDmn('<definitions />');
    await api.roundTripTableDmn(table, 'amount_route', 'amount_route');

    expect(calls[0]).toMatchObject({
      method: 'post',
      endpoint: '/decision/tables/export-dmn',
      body: { decisionName: 'amount_route', decisionId: 'amount_route', model: table },
    });
    expect(calls[1]).toMatchObject({
      method: 'post',
      endpoint: '/decision/tables/import-dmn',
      body: { dmnXml: '<definitions />' },
    });
    expect(calls[2]).toMatchObject({
      method: 'post',
      endpoint: '/decision/tables/round-trip',
      body: { decisionName: 'amount_route', decisionId: 'amount_route', model: table },
    });
  });

  it('refreshUsageIndexSource refreshes one indexed source without a full rebuild', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.refreshUsageIndexSource('EVENT_POLICY', 'policy version pid');
    expect(calls[0]).toMatchObject({
      method: 'post',
      endpoint: '/decision/usage-index/sources/EVENT_POLICY/policy%20version%20pid/refresh',
    });
  });

  it('routes rollout policy CRUD, lifecycle, and metrics calls to the backend endpoints', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);

    await api.createRollout('risk_score', {
      baselineVersion: 1,
      candidateVersion: 2,
      percentage: 10,
      routingKeyExpr: 'traceId',
      salt: 'risk-score',
    });
    await api.listRollouts('risk_score');
    await api.getActiveRollout('risk_score');
    await api.activateRollout('rollout-pid', { note: 'start 10%' });
    await api.pauseRollout('rollout-pid', { note: 'pause window' });
    await api.promoteRollout('rollout-pid', { note: 'promote candidate' });
    await api.rollbackRollout('rollout-pid', { note: 'rollback candidate' });
    await api.getRolloutMetrics('rollout-pid');
    await api.getRolloutMetrics('rollout-pid', {
      windowHours: 168,
      bucketMinutes: 60,
      refresh: false,
    });

    expect(calls).toEqual([
      {
        method: 'post',
        endpoint: '/decision/definitions/risk_score/rollouts',
        body: {
          baselineVersion: 1,
          candidateVersion: 2,
          percentage: 10,
          routingKeyExpr: 'traceId',
          salt: 'risk-score',
        },
      },
      { method: 'get', endpoint: '/decision/definitions/risk_score/rollouts', params: undefined },
      {
        method: 'get',
        endpoint: '/decision/definitions/risk_score/rollouts/active',
        params: undefined,
      },
      {
        method: 'post',
        endpoint: '/decision/rollouts/rollout-pid/activate',
        body: { note: 'start 10%' },
      },
      {
        method: 'post',
        endpoint: '/decision/rollouts/rollout-pid/pause',
        body: { note: 'pause window' },
      },
      {
        method: 'post',
        endpoint: '/decision/rollouts/rollout-pid/promote',
        body: { note: 'promote candidate' },
      },
      {
        method: 'post',
        endpoint: '/decision/rollouts/rollout-pid/rollback',
        body: { note: 'rollback candidate' },
      },
      { method: 'get', endpoint: '/decision/rollouts/rollout-pid/metrics', params: undefined },
      {
        method: 'get',
        endpoint: '/decision/rollouts/rollout-pid/metrics',
        params: { windowHours: 168, bucketMinutes: 60, refresh: false },
      },
    ]);
  });

  it('event-policy run-and-execute targets /api/event-policy/run-and-execute', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.runAndExecutePolicy({
      eventType: 'FORM_SUBMITTED',
      targetType: 'FORM',
      targetKey: 'complaint',
      context: {},
    });
    expect(calls[0].endpoint).toBe('/event-policy/run-and-execute');
  });

  it('event-policy version lifecycle targets definition code and version pid paths', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.createPolicyDraftVersion('complaint_policy', {
      phase: 'AFTER_COMMIT',
      matchMode: 'COLLECT_ALL',
      executionMode: 'ORDERED',
      failureStrategy: 'FAIL_FAST',
      conflictStrategy: 'REJECT_ON_CONFLICT',
      dedupStrategy: 'BY_IDEMPOTENCY_KEY',
      rulesJson: [],
    });
    await api.validatePolicyVersion('policy-version-pid');
    await api.publishPolicyVersion('policy-version-pid');

    expect(calls[0]).toMatchObject({
      method: 'post',
      endpoint: '/event-policy/definitions/complaint_policy/versions',
    });
    expect(calls[1]).toMatchObject({
      method: 'post',
      endpoint: '/event-policy/versions/policy-version-pid/validate',
    });
    expect(calls[2]).toMatchObject({
      method: 'post',
      endpoint: '/event-policy/versions/policy-version-pid/publish',
    });
  });

  it('lists event-policy versions by definition code', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http) as unknown as {
      listPolicyVersions: (code: string) => Promise<unknown>;
    };
    await api.listPolicyVersions('complaint_policy');
    expect(calls[0]).toMatchObject({
      method: 'get',
      endpoint: '/event-policy/definitions/complaint_policy/versions',
    });
  });

  it('listPolicies passes filters to /event-policy/definitions', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.listPolicies({
      keyword: 'complaint',
      eventType: 'FORM_SUBMITTED',
      status: 'PUBLISHED',
    });
    expect(calls[0]).toMatchObject({
      method: 'get',
      endpoint: '/event-policy/definitions',
      params: { keyword: 'complaint', eventType: 'FORM_SUBMITTED', status: 'PUBLISHED' },
    });
  });

  it('event-policy definition commands target create, enabled, and copy paths', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.createPolicyDefinition({
      policyCode: 'complaint_policy',
      policyName: 'Complaint Policy',
      eventType: 'FORM_SUBMITTED',
      targetType: 'FORM',
      targetKey: 'complaint',
    });
    await api.setPolicyEnabled('complaint_policy', false);
    await api.copyPolicyDefinition('complaint_policy', {
      policyCode: 'complaint_policy_copy',
      policyName: 'Complaint Policy Copy',
    });

    expect(calls[0]).toMatchObject({
      method: 'post',
      endpoint: '/event-policy/definitions',
      body: {
        policyCode: 'complaint_policy',
        policyName: 'Complaint Policy',
        eventType: 'FORM_SUBMITTED',
        targetType: 'FORM',
        targetKey: 'complaint',
      },
    });
    expect(calls[1]).toMatchObject({
      method: 'post',
      endpoint: '/event-policy/definitions/complaint_policy/enabled',
      body: { enabled: false },
    });
    expect(calls[2]).toMatchObject({
      method: 'post',
      endpoint: '/event-policy/definitions/complaint_policy/copy',
      body: {
        policyCode: 'complaint_policy_copy',
        policyName: 'Complaint Policy Copy',
      },
    });
  });
});
