import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../../../src/client/api-client.js';
import { createCommandTool } from '../../../../src/mcp/tools/write/createCommand.js';

const validBase = {
  code: 'crm_lead.assign',
  modelCode: 'crm_lead',
};

function makeClient(opts: {
  postImpl?: (path: string, body?: unknown) => Promise<any>;
  deleteImpl?: (path: string) => Promise<any>;
}): ApiClient {
  return {
    post: vi.fn(opts.postImpl ?? (async () => ({ ok: true, status: 200, data: {} }))),
    delete: vi.fn(opts.deleteImpl ?? (async () => ({ ok: true, status: 200, data: null }))),
    get: vi.fn(),
  } as unknown as ApiClient;
}

describe('createCommandTool', () => {
  it('declares correct identity + destructiveHint', () => {
    const tool = createCommandTool({} as ApiClient);
    expect(tool.name).toBe('create_command');
    expect(tool.annotations).toMatchObject({ destructiveHint: true, idempotentHint: false });
  });

  describe('zod input', () => {
    it('rejects missing code', () => {
      const tool = createCommandTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({ modelCode: 'crm_lead' });
      expect(parsed.success).toBe(false);
    });

    it('rejects missing modelCode', () => {
      const tool = createCommandTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({ code: 'crm_lead.assign' });
      expect(parsed.success).toBe(false);
    });

    it('rejects unknown cmdRiskLevel', () => {
      const tool = createCommandTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({ ...validBase, cmdRiskLevel: 'L7' });
      expect(parsed.success).toBe(false);
    });

    it('rejects bindingRule missing ruleType', () => {
      const tool = createCommandTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({
        ...validBase,
        bindingRules: [{ expression: '1==1' }],
      });
      expect(parsed.success).toBe(false);
    });
  });

  describe('dryRun', () => {
    it('does not call backend and surfaces wouldCreate', async () => {
      const client = makeClient({});
      const tool = createCommandTool(client);

      const result = await tool.handler({
        ...validBase,
        bindingRules: [{ ruleType: 'EXPRESSION', expression: 'amount > 0', sequence: 0, enabled: true }],
        dryRun: true,
      });

      expect((client.post as any).mock.calls).toHaveLength(0);
      expect((client.delete as any).mock.calls).toHaveLength(0);
      const body = JSON.parse(result.content[0].text);
      expect(body.dryRun).toBe(true);
      expect(body.wouldCreate.command.code).toBe('crm_lead.assign');
      expect(body.wouldCreate.bindingRules).toHaveLength(1);
      // The MCP-only `dryRun` flag must NOT leak into the simulated payload.
      expect(body.wouldCreate.command).not.toHaveProperty('dryRun');
    });
  });

  describe('happy 2-step path', () => {
    it('creates the command then 2 binding rules in order, no rollback', async () => {
      const post = vi.fn(async (path: string) => {
        if (path === '/api/meta/commands') {
          return { ok: true, status: 200, data: { pid: 'cmd-1', code: 'crm_lead.assign' } };
        }
        return { ok: true, status: 200, data: { pid: 'br-' + path.length } };
      });
      const del = vi.fn();
      const client = { post, delete: del, get: vi.fn() } as unknown as ApiClient;
      const tool = createCommandTool(client);

      const result = await tool.handler({
        ...validBase,
        bindingRules: [
          { ruleType: 'EXPRESSION', expression: 'true', sequence: 0, enabled: true },
          { ruleType: 'EVENT', eventType: 'lead.assigned', sequence: 1, enabled: true },
        ],
        dryRun: false,
      });

      expect(post).toHaveBeenCalledTimes(3);
      expect(del).not.toHaveBeenCalled();
      expect(post.mock.calls[0][0]).toBe('/api/meta/commands');
      expect(post.mock.calls[1][0]).toBe('/api/meta/commands/cmd-1/binding-rules');
      expect(post.mock.calls[2][0]).toBe('/api/meta/commands/cmd-1/binding-rules');
      expect(result.isError).toBeUndefined();
      const body = JSON.parse(result.content[0].text);
      expect(body.command.pid).toBe('cmd-1');
      expect(body.bindingRules).toHaveLength(2);
    });

    it('skips binding-rules step when none provided', async () => {
      const post = vi.fn(async () => ({
        ok: true,
        status: 200,
        data: { pid: 'cmd-1', code: 'crm_lead.assign' },
      }));
      const client = { post, delete: vi.fn(), get: vi.fn() } as unknown as ApiClient;
      const tool = createCommandTool(client);

      await tool.handler({ ...validBase, dryRun: false });

      expect(post).toHaveBeenCalledTimes(1);
    });
  });

  describe('rollback on partial failure', () => {
    it('rolls back command when 2nd binding rule fails (rollback succeeds)', async () => {
      let bindingCallCount = 0;
      const post = vi.fn(async (path: string) => {
        if (path === '/api/meta/commands') {
          return { ok: true, status: 200, data: { pid: 'cmd-1' } };
        }
        bindingCallCount++;
        if (bindingCallCount === 2) {
          return { ok: false, status: 422, data: null, message: 'invalid eventType' };
        }
        return { ok: true, status: 200, data: { pid: 'br-1' } };
      });
      const del = vi.fn(async () => ({ ok: true, status: 200, data: null }));
      const client = { post, delete: del, get: vi.fn() } as unknown as ApiClient;
      const tool = createCommandTool(client);

      const result = await tool.handler({
        ...validBase,
        bindingRules: [
          { ruleType: 'EXPRESSION', expression: 'true', sequence: 0, enabled: true },
          { ruleType: 'EVENT', eventType: 'bogus', sequence: 1, enabled: true },
        ],
        dryRun: false,
      });

      expect(result.isError).toBe(true);
      expect(del).toHaveBeenCalledWith('/api/meta/commands/cmd-1');
      const body = JSON.parse(result.content[0].text);
      expect(body.step).toBe('create_binding_rule');
      expect(body.failedAtIndex).toBe(1);
      expect(body.createdSoFar).toBe(1);
      expect(body.rollback.status).toBe('ok');
      expect(body.rollback.deletedCommandPid).toBe('cmd-1');
    });

    it('reports manual cleanup when both binding fails AND rollback fails', async () => {
      const post = vi.fn(async (path: string) => {
        if (path === '/api/meta/commands') {
          return { ok: true, status: 200, data: { pid: 'cmd-1' } };
        }
        return { ok: false, status: 500, data: null, message: 'binding crashed' };
      });
      const del = vi.fn(async () => ({
        ok: false,
        status: 500,
        data: null,
        message: 'delete crashed',
      }));
      const client = { post, delete: del, get: vi.fn() } as unknown as ApiClient;
      const tool = createCommandTool(client);

      const result = await tool.handler({
        ...validBase,
        bindingRules: [{ ruleType: 'HANDLER', handlerClass: 'X', sequence: 0, enabled: true }],
        dryRun: false,
      });

      expect(result.isError).toBe(true);
      const body = JSON.parse(result.content[0].text);
      expect(body.rollback.status).toBe('failed');
      expect(body.rollback.manualCleanupHint).toContain('DELETE /api/meta/commands/cmd-1');
    });
  });

  describe('command-creation failure (no rollback needed)', () => {
    it('classifies "已存在" on command create as conflict', async () => {
      const post = vi.fn(async () => ({
        ok: false,
        status: 200,
        data: null,
        message: 'Command code 已存在',
      }));
      const del = vi.fn();
      const client = { post, delete: del, get: vi.fn() } as unknown as ApiClient;
      const tool = createCommandTool(client);

      const result = await tool.handler({ ...validBase, dryRun: false });

      expect(result.isError).toBe(true);
      expect(del).not.toHaveBeenCalled();
      const body = JSON.parse(result.content[0].text);
      expect(body.step).toBe('create_command');
      expect(body.kind).toBe('conflict');
    });
  });
});
