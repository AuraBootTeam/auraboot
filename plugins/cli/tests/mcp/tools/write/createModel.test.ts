import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../../../src/client/api-client.js';
import { createModelTool } from '../../../../src/mcp/tools/write/createModel.js';

function fakeClient(post: (...args: any[]) => Promise<any>): ApiClient {
  return { post: vi.fn(post), get: vi.fn() } as unknown as ApiClient;
}

describe('createModelTool', () => {
  it('declares correct identity + destructiveHint', () => {
    const tool = createModelTool({} as ApiClient);
    expect(tool.name).toBe('create_model');
    expect(tool.annotations).toMatchObject({ destructiveHint: true, idempotentHint: false });
  });

  describe('input validation', () => {
    it('rejects code starting with a digit', () => {
      const tool = createModelTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({ code: '1bad', displayName: 'X' });
      expect(parsed.success).toBe(false);
    });

    it('rejects empty displayName', () => {
      const tool = createModelTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({ code: 'crm_lead', displayName: '' });
      expect(parsed.success).toBe(false);
    });

    it('accepts minimal valid payload', () => {
      const tool = createModelTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({ code: 'crm_lead', displayName: 'Lead' });
      expect(parsed.success).toBe(true);
    });

    it('rejects unknown sourceType (closed enum)', () => {
      const tool = createModelTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({
        code: 'crm_lead',
        displayName: 'Lead',
        sourceType: 'graphql', // not in [physical, namedQuery, endpoint, sqlView]
      });
      expect(parsed.success).toBe(false);
    });
  });

  describe('dryRun behavior', () => {
    it('does not call backend when dryRun=true', async () => {
      const post = vi.fn();
      const client = { post, get: vi.fn() } as unknown as ApiClient;
      const tool = createModelTool(client);

      const result = await tool.handler({
        code: 'crm_lead',
        displayName: 'Lead',
        modelType: 'entity',
        autoPublish: false,
        dryRun: true,
      });

      expect(post).not.toHaveBeenCalled();
      expect(result.isError).toBeUndefined();
      const body = JSON.parse(result.content[0].text);
      expect(body.dryRun).toBe(true);
      expect(body.valid).toBe(true);
      expect(body.wouldCreate).toMatchObject({ code: 'crm_lead', displayName: 'Lead' });
      // The MCP-only `dryRun` flag must NOT leak into the simulated payload.
      expect(body.wouldCreate).not.toHaveProperty('dryRun');
    });
  });

  describe('happy path POST', () => {
    it('strips dryRun + posts the rest of the body to /api/meta/models', async () => {
      const post = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        data: { pid: 'mp-1', code: 'crm_lead' },
      });
      const client = { post, get: vi.fn() } as unknown as ApiClient;
      const tool = createModelTool(client);

      const result = await tool.handler({
        code: 'crm_lead',
        displayName: 'Lead',
        modelType: 'entity',
        autoPublish: false,
        dryRun: false,
      });

      expect(post).toHaveBeenCalledTimes(1);
      const [path, body] = post.mock.calls[0];
      expect(path).toBe('/api/meta/models');
      expect(body).not.toHaveProperty('dryRun');
      expect(body.code).toBe('crm_lead');
      expect(result.isError).toBeUndefined();
      expect(JSON.parse(result.content[0].text)).toEqual({ pid: 'mp-1', code: 'crm_lead' });
    });
  });

  describe('error mapping', () => {
    it('classifies "已存在" as conflict + suggests rename', async () => {
      const tool = createModelTool(
        fakeClient(async () => ({
          ok: false,
          status: 200,
          data: null,
          message: '模型编码已存在: crm_lead',
        })),
      );

      const result = await tool.handler({
        code: 'crm_lead',
        displayName: 'Lead',
        modelType: 'entity',
        autoPublish: false,
        dryRun: false,
      });

      expect(result.isError).toBe(true);
      const body = JSON.parse(result.content[0].text);
      expect(body.kind).toBe('conflict');
      expect(body.suggestion).toMatch(/different code/i);
    });

    it('classifies non-conflict failure as backend_error without suggestion', async () => {
      const tool = createModelTool(
        fakeClient(async () => ({
          ok: false,
          status: 422,
          data: null,
          message: 'Validation failed: capabilities.sortableFields[0] not in fields',
        })),
      );

      const result = await tool.handler({
        code: 'crm_lead',
        displayName: 'Lead',
        modelType: 'entity',
        autoPublish: false,
        dryRun: false,
      });

      expect(result.isError).toBe(true);
      const body = JSON.parse(result.content[0].text);
      expect(body.kind).toBe('backend_error');
      expect(body.suggestion).toBeUndefined();
    });

    it('captures thrown errors into isError result', async () => {
      const tool = createModelTool(
        fakeClient(async () => {
          throw new Error('socket hang up');
        }),
      );
      const result = await tool.handler({
        code: 'crm_lead',
        displayName: 'Lead',
        modelType: 'entity',
        autoPublish: false,
        dryRun: false,
      });
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toMatch(/socket hang up/);
    });
  });
});
