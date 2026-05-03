import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../../../src/client/api-client.js';
import { queryDslCapabilitiesTool } from '../../../../src/mcp/tools/read/queryDslCapabilities.js';

function fakeClient(getImpl: (...args: any[]) => Promise<any>): ApiClient {
  return { get: vi.fn(getImpl), post: vi.fn() } as unknown as ApiClient;
}

describe('queryDslCapabilitiesTool', () => {
  it('declares correct identity + read-only annotations', () => {
    const tool = queryDslCapabilitiesTool({} as ApiClient);
    expect(tool.name).toBe('query_dsl_capabilities');
    expect(tool.annotations).toMatchObject({ readOnlyHint: true, idempotentHint: true });
  });

  it('hits GET /api/dsl/registry without query params', async () => {
    const get = vi.fn().mockResolvedValue({ ok: true, status: 200, data: { version: '2.0' } });
    const client = { get, post: vi.fn() } as unknown as ApiClient;

    const tool = queryDslCapabilitiesTool(client);
    const result = await tool.handler({});

    expect(get).toHaveBeenCalledWith('/api/dsl/registry');
    expect(result.isError).toBeUndefined();
    expect(JSON.parse(result.content[0].text)).toEqual({ version: '2.0' });
  });

  it('returns isError on non-ok response', async () => {
    const tool = queryDslCapabilitiesTool(
      fakeClient(async () => ({ ok: false, status: 500, data: null, message: 'Boom' })),
    );
    const result = await tool.handler({});
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/Boom/);
  });

  it('captures thrown errors into isError result', async () => {
    const tool = queryDslCapabilitiesTool(
      fakeClient(async () => {
        throw new Error('network down');
      }),
    );
    const result = await tool.handler({});
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/network down/);
  });
});
