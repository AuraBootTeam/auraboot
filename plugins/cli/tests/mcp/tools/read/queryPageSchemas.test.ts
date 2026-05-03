import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../../../src/client/api-client.js';
import { queryPageSchemasTool } from '../../../../src/mcp/tools/read/queryPageSchemas.js';

describe('queryPageSchemasTool', () => {
  it('hits GET /api/pages with page/size only when no filters set', async () => {
    const get = vi.fn().mockResolvedValue({ ok: true, status: 200, data: {} });
    const client = { get, post: vi.fn() } as unknown as ApiClient;

    const tool = queryPageSchemasTool(client);
    await tool.handler({ limit: 50 });

    expect(get).toHaveBeenCalledWith('/api/pages', { page: '1', size: '50' });
  });

  it('forwards kind / isTemplate / keyword when set', async () => {
    const get = vi.fn().mockResolvedValue({ ok: true, status: 200, data: {} });
    const client = { get, post: vi.fn() } as unknown as ApiClient;

    const tool = queryPageSchemasTool(client);
    await tool.handler({ kind: 'list', isTemplate: false, keyword: 'leave', limit: 25 });

    expect(get).toHaveBeenCalledWith('/api/pages', {
      page: '1',
      size: '25',
      kind: 'list',
      isTemplate: 'false',
      keyword: 'leave',
    });
  });

  it('serializes isTemplate=true correctly', async () => {
    const get = vi.fn().mockResolvedValue({ ok: true, status: 200, data: {} });
    const client = { get, post: vi.fn() } as unknown as ApiClient;

    const tool = queryPageSchemasTool(client);
    await tool.handler({ isTemplate: true, limit: 10 });

    const query = get.mock.calls[0][1] as Record<string, string>;
    expect(query.isTemplate).toBe('true');
  });

  it('rejects kind=dashboard via zod (zod safeParse fails before handler)', () => {
    const tool = queryPageSchemasTool({} as ApiClient);
    const parsed = tool.inputSchema.safeParse({ kind: 'dashboard', limit: 10 });
    expect(parsed.success).toBe(false);
  });

  it('returns isError on non-ok response', async () => {
    const get = vi.fn().mockResolvedValue({ ok: false, status: 500, data: null, message: 'Bad' });
    const client = { get, post: vi.fn() } as unknown as ApiClient;

    const tool = queryPageSchemasTool(client);
    const result = await tool.handler({ limit: 50 });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/Bad/);
  });
});
