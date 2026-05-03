import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../../../src/client/api-client.js';
import { rollbackImportTool } from '../../../../src/mcp/tools/write/rollbackImport.js';

describe('rollbackImportTool', () => {
  it('declares correct identity + destructiveHint + idempotentHint', () => {
    const tool = rollbackImportTool({} as ApiClient);
    expect(tool.name).toBe('rollback_import');
    expect(tool.annotations).toMatchObject({
      destructiveHint: true,
      idempotentHint: true,
    });
  });

  it('rejects empty importId', () => {
    const tool = rollbackImportTool({} as ApiClient);
    expect(tool.inputSchema.safeParse({ importId: '' }).success).toBe(false);
  });

  it('POSTs to /api/plugins/import/{importId}/rollback', async () => {
    const post = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      data: { importId: 'imp-1', success: true },
    });
    const client = { post, get: vi.fn(), delete: vi.fn() } as unknown as ApiClient;
    const tool = rollbackImportTool(client);

    await tool.handler({ importId: 'imp-1' });

    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][0]).toBe('/api/plugins/import/imp-1/rollback');
  });

  it('encodes special characters in importId', async () => {
    const post = vi.fn().mockResolvedValue({ ok: true, status: 200, data: {} });
    const client = { post, get: vi.fn(), delete: vi.fn() } as unknown as ApiClient;
    const tool = rollbackImportTool(client);

    await tool.handler({ importId: 'imp/with/slash' });

    expect(post.mock.calls[0][0]).toBe('/api/plugins/import/imp%2Fwith%2Fslash/rollback');
  });

  it('surfaces backend non-ok as isError with importId in payload', async () => {
    const post = vi.fn().mockResolvedValue({
      ok: false,
      status: 410,
      data: null,
      message: 'Rollback window expired',
    });
    const client = { post, get: vi.fn(), delete: vi.fn() } as unknown as ApiClient;
    const tool = rollbackImportTool(client);

    const result = await tool.handler({ importId: 'imp-old' });

    expect(result.isError).toBe(true);
    const body = JSON.parse(result.content[0].text);
    expect(body.status).toBe(410);
    expect(body.importId).toBe('imp-old');
    expect(body.error).toMatch(/expired/);
  });

  it('captures thrown errors into isError result', async () => {
    const client = {
      post: vi.fn(async () => {
        throw new Error('connection reset');
      }),
      get: vi.fn(),
      delete: vi.fn(),
    } as unknown as ApiClient;
    const tool = rollbackImportTool(client);

    const result = await tool.handler({ importId: 'imp-1' });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/connection reset/);
  });
});
