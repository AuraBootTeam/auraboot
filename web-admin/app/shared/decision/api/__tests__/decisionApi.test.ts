import { describe, it, expect, vi } from 'vitest';
import { createDecisionApi, type HttpClient } from '../decisionApi';

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
  } as unknown as HttpClient;
  return { http, calls };
}

describe('decisionApi client', () => {
  it('evaluate posts to /api/decision/evaluate and unwraps data', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    const res = await api.evaluate({ decisionCode: 'big', binding: 'LATEST', context: { record: { data: {} } } });
    expect(calls[0]).toMatchObject({ method: 'post', endpoint: '/api/decision/evaluate' });
    expect((calls[0].body as { decisionCode: string }).decisionCode).toBe('big');
    expect(res).toMatchObject({ status: 'MATCHED', matched: true });
  });

  it('batchEvaluate posts the request array to /batch-evaluate', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.batchEvaluate([{ decisionCode: 'a', context: {} }, { decisionCode: 'b', context: {} }]);
    expect(calls[0].endpoint).toBe('/api/decision/batch-evaluate');
    expect((calls[0].body as unknown[]).length).toBe(2);
  });

  it('createDraftVersion targets the code path; publishVersion targets the pid path', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.createDraftVersion('big', { kind: 'SIMPLE_CONDITION', runtimeAdapter: 'AST_EVALUATOR', contentJson: {} });
    await api.publishVersion('pid-123');
    expect(calls[0].endpoint).toBe('/api/decision/definitions/big/versions');
    expect(calls[1].endpoint).toBe('/api/decision/versions/pid-123/publish');
  });

  it('getLogs passes traceId as a query param', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.getLogs('trace-xyz');
    expect(calls[0]).toMatchObject({ method: 'get', endpoint: '/api/decision/logs' });
    expect(calls[0].params).toMatchObject({ traceId: 'trace-xyz' });
  });

  it('event-policy run-and-execute targets /api/event-policy/run-and-execute', async () => {
    const { http, calls } = fakeHttp();
    const api = createDecisionApi(http);
    await api.runAndExecutePolicy({ eventType: 'FORM_SUBMITTED', targetType: 'FORM', targetKey: 'complaint', context: {} });
    expect(calls[0].endpoint).toBe('/api/event-policy/run-and-execute');
  });
});
