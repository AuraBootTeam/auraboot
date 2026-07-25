import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  validateSemanticYaml,
  publishSemanticYaml,
  runSemanticQuery,
} from '../semanticApi';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

afterEach(() => vi.unstubAllGlobals());

describe('semantic authoring API — envelope handling', () => {
  it('validateSemanticYaml unwraps ApiResponse.data on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse({
          code: '0',
          message: 'OK',
          data: {
            ok: true,
            modelCode: 'demo',
            version: '0.1',
            metricCount: 1,
            dimensionCount: 2,
            entityCount: 1,
            accessPolicyCount: 0,
          },
        }),
      ),
    );
    const r = await validateSemanticYaml('version: "0.1"');
    expect(r.modelCode).toBe('demo');
    expect(r.metricCount).toBe(1);
  });

  it('validateSemanticYaml throws the backend message on a non-zero code', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse(
          {
            code: '35000',
            message: 'measure[x].expr contains forbidden SQL token (matches denylist)',
            context: { errorCode: 'SQL_INJECTION_DETECTED' },
          },
          400,
        ),
      ),
    );
    await expect(validateSemanticYaml('bad')).rejects.toThrow(/forbidden SQL token/);
  });

  it('publishSemanticYaml returns the created pid and passes yaml + pluginCode as JSON', async () => {
    const spy = vi.fn(
      async (_url: string, _init?: RequestInit): Promise<Response> =>
        jsonResponse({ code: '0', data: { ok: true, pid: '01ABC' } }),
    );
    vi.stubGlobal('fetch', spy);
    const r = await publishSemanticYaml('version: "0.1"', 'my-ns');
    expect(r.pid).toBe('01ABC');
    // Sent as a JSON body (BFF drops text/plain bodies), not a query param.
    const init = spy.mock.calls[0][1] as RequestInit;
    const sent = JSON.parse(String(init.body));
    expect(sent.pluginCode).toBe('my-ns');
    expect(sent.yaml).toContain('0.1');
  });

  it('runSemanticQuery unwraps rows', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse({
          code: '0',
          data: {
            queryId: 'q1',
            rows: [{ 'demo.status': 'active', 'demo.total': 2 }],
            rowcount: 1,
            durationMs: 3,
            referencedColumns: ['status'],
            warnings: [],
          },
        }),
      ),
    );
    const r = await runSemanticQuery({ metrics: ['demo.total'] });
    expect(r.rowcount).toBe(1);
    expect(r.rows[0]['demo.total']).toBe(2);
  });

  it('surfaces a 403 permission denial as an error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse(
          { code: '403', message: 'Access forbidden', context: 'Missing required permission(s)' },
          403,
        ),
      ),
    );
    await expect(runSemanticQuery({ metrics: ['demo.total'] })).rejects.toThrow(
      /forbidden|permission/i,
    );
  });
});
