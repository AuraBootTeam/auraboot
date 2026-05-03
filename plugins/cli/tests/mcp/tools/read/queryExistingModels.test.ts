import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../../../src/client/api-client.js';
import { queryExistingModelsTool } from '../../../../src/mcp/tools/read/queryExistingModels.js';

describe('queryExistingModelsTool', () => {
  it('uses page=1 + size=limit + currentOnly=true defaults', async () => {
    const get = vi.fn().mockResolvedValue({ ok: true, status: 200, data: { items: [] } });
    const client = { get, post: vi.fn() } as unknown as ApiClient;

    const tool = queryExistingModelsTool(client);
    await tool.handler({ limit: 50 });

    expect(get).toHaveBeenCalledWith('/api/meta/models', {
      page: '1',
      size: '50',
      currentOnly: 'true',
    });
  });

  it('passes keyword and modelType when provided', async () => {
    const get = vi.fn().mockResolvedValue({ ok: true, status: 200, data: {} });
    const client = { get, post: vi.fn() } as unknown as ApiClient;

    const tool = queryExistingModelsTool(client);
    await tool.handler({ keyword: 'crm', modelType: 'business', limit: 25 });

    expect(get).toHaveBeenCalledWith('/api/meta/models', {
      page: '1',
      size: '25',
      currentOnly: 'true',
      keyword: 'crm',
      modelType: 'business',
    });
  });

  it('omits keyword/modelType when undefined (no empty query keys)', async () => {
    const get = vi.fn().mockResolvedValue({ ok: true, status: 200, data: {} });
    const client = { get, post: vi.fn() } as unknown as ApiClient;

    const tool = queryExistingModelsTool(client);
    await tool.handler({ limit: 10 });

    const calledQuery = get.mock.calls[0][1] as Record<string, string>;
    expect(Object.keys(calledQuery).sort()).toEqual(['currentOnly', 'page', 'size']);
  });

  it('returns isError on non-ok response', async () => {
    const get = vi.fn().mockResolvedValue({ ok: false, status: 403, data: null, message: 'Denied' });
    const client = { get, post: vi.fn() } as unknown as ApiClient;

    const tool = queryExistingModelsTool(client);
    const result = await tool.handler({ limit: 50 });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/Denied/);
  });
});
