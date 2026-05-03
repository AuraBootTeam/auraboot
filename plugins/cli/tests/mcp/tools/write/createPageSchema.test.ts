import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../../../src/client/api-client.js';
import { createPageSchemaTool } from '../../../../src/mcp/tools/write/createPageSchema.js';

const validList = {
  pageKey: 'crm_lead_list',
  name: 'crm_lead_list',
  title: 'Leads',
  kind: 'list' as const,
  blocks: [{ blockType: 'table' as const, blockId: 'main' }],
};

function fakeClient(post: (...args: any[]) => Promise<any>): ApiClient {
  return { post: vi.fn(post), get: vi.fn() } as unknown as ApiClient;
}

describe('createPageSchemaTool', () => {
  it('declares correct identity + destructiveHint', () => {
    const tool = createPageSchemaTool({} as ApiClient);
    expect(tool.name).toBe('create_page_schema');
    expect(tool.annotations).toMatchObject({ destructiveHint: true, idempotentHint: false });
  });

  describe('zod V2 flat enforcement', () => {
    it('rejects kind=dashboard at the schema layer', () => {
      const tool = createPageSchemaTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({ ...validList, kind: 'dashboard' });
      expect(parsed.success).toBe(false);
    });

    it('rejects kind=composite (legacy concept removed in V2)', () => {
      const tool = createPageSchemaTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({ ...validList, kind: 'composite' });
      expect(parsed.success).toBe(false);
    });

    it('rejects empty blocks array', () => {
      const tool = createPageSchemaTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({ ...validList, blocks: [] });
      expect(parsed.success).toBe(false);
    });

    it('rejects unknown blockType', () => {
      const tool = createPageSchemaTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({
        ...validList,
        blocks: [{ blockType: 'data-table' /* legacy alias removed */ }],
      });
      expect(parsed.success).toBe(false);
    });

    it('rejects pageKey starting with digit', () => {
      const tool = createPageSchemaTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({ ...validList, pageKey: '1bad' });
      expect(parsed.success).toBe(false);
    });

    it('rejects layout without discriminator', () => {
      const tool = createPageSchemaTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({ ...validList, layout: { cols: 12 } });
      expect(parsed.success).toBe(false);
    });

    it('accepts grid layout with cols', () => {
      const tool = createPageSchemaTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({
        ...validList,
        layout: { type: 'grid', cols: 12 },
      });
      expect(parsed.success).toBe(true);
    });

    it('accepts all 3 valid kinds', () => {
      const tool = createPageSchemaTool({} as ApiClient);
      for (const kind of ['list', 'form', 'detail'] as const) {
        const parsed = tool.inputSchema.safeParse({ ...validList, kind });
        expect(parsed.success).toBe(true);
      }
    });
  });

  describe('dryRun behavior', () => {
    it('does not call backend when dryRun=true', async () => {
      const post = vi.fn();
      const client = { post, get: vi.fn() } as unknown as ApiClient;
      const tool = createPageSchemaTool(client);

      const result = await tool.handler({
        ...validList,
        isTemplate: false,
        sortWeight: 0,
        dryRun: true,
      });

      expect(post).not.toHaveBeenCalled();
      expect(result.isError).toBeUndefined();
      const body = JSON.parse(result.content[0].text);
      expect(body.dryRun).toBe(true);
      expect(body.valid).toBe(true);
      expect(body.wouldCreate.pageKey).toBe('crm_lead_list');
      // The MCP-only `dryRun` flag must NOT leak into the simulated payload.
      expect(body.wouldCreate).not.toHaveProperty('dryRun');
    });
  });

  describe('happy path POST', () => {
    it('strips dryRun + posts to /api/pages', async () => {
      const post = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        data: { pid: 'pg-1', pageKey: 'crm_lead_list' },
      });
      const client = { post, get: vi.fn() } as unknown as ApiClient;
      const tool = createPageSchemaTool(client);

      const result = await tool.handler({
        ...validList,
        isTemplate: false,
        sortWeight: 0,
        dryRun: false,
      });

      expect(post).toHaveBeenCalledTimes(1);
      const [path, body] = post.mock.calls[0];
      expect(path).toBe('/api/pages');
      expect(body).not.toHaveProperty('dryRun');
      expect(body.kind).toBe('list');
      expect(body.blocks).toHaveLength(1);
      expect(result.isError).toBeUndefined();
    });
  });

  describe('error mapping', () => {
    it('classifies 已存在 as conflict', async () => {
      const tool = createPageSchemaTool(
        fakeClient(async () => ({
          ok: false,
          status: 200,
          data: null,
          message: 'pageKey 已存在: crm_lead_list',
        })),
      );

      const result = await tool.handler({
        ...validList,
        isTemplate: false,
        sortWeight: 0,
        dryRun: false,
      });

      expect(result.isError).toBe(true);
      const body = JSON.parse(result.content[0].text);
      expect(body.kind).toBe('conflict');
      expect(body.suggestion).toMatch(/pageKey/i);
    });

    it('classifies "already exists" English message as conflict', async () => {
      const tool = createPageSchemaTool(
        fakeClient(async () => ({
          ok: false,
          status: 200,
          data: null,
          message: 'pageKey already exists',
        })),
      );

      const result = await tool.handler({
        ...validList,
        isTemplate: false,
        sortWeight: 0,
        dryRun: false,
      });

      expect(result.isError).toBe(true);
      const body = JSON.parse(result.content[0].text);
      expect(body.kind).toBe('conflict');
    });

    it('non-conflict failure reported as backend_error without suggestion', async () => {
      const tool = createPageSchemaTool(
        fakeClient(async () => ({
          ok: false,
          status: 422,
          data: null,
          message: 'blockType custom requires renderComponent',
        })),
      );

      const result = await tool.handler({
        ...validList,
        isTemplate: false,
        sortWeight: 0,
        dryRun: false,
      });

      expect(result.isError).toBe(true);
      const body = JSON.parse(result.content[0].text);
      expect(body.kind).toBe('backend_error');
      expect(body.suggestion).toBeUndefined();
    });
  });
});
