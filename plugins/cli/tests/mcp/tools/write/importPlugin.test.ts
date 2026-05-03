import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../../../src/client/api-client.js';
import { importPluginTool } from '../../../../src/mcp/tools/write/importPlugin.js';

const minimalManifest = {
  pluginId: 'demo-plugin',
  namespace: 'demo',
  version: '1.0.0',
};

describe('importPluginTool', () => {
  it('declares correct identity + destructiveHint, NOT idempotent', () => {
    const tool = importPluginTool({} as ApiClient);
    expect(tool.name).toBe('import_plugin');
    expect(tool.annotations).toMatchObject({
      destructiveHint: true,
      idempotentHint: false,
    });
  });

  describe('safety: dryRun defaults to true', () => {
    it('parses without dryRun and treats it as true', () => {
      const tool = importPluginTool({} as ApiClient);
      const parsed = tool.inputSchema.parse({ manifest: minimalManifest });
      expect(parsed.dryRun).toBe(true);
    });

    it('still posts when dryRun omitted (with dryRun=true on the wire)', async () => {
      const post = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        data: { dryRun: true, valid: true, errors: [], conflicts: [] },
      });
      const client = { post, get: vi.fn(), delete: vi.fn() } as unknown as ApiClient;
      const tool = importPluginTool(client);

      await tool.handler({
        manifest: minimalManifest,
        dryRun: true,
        conflictStrategy: 'error',
        autoDeployProcesses: true,
        autoPublishModels: true,
        autoPublishFields: true,
        autoPublishCommands: true,
        autoPublishPages: true,
      });

      const [path, body, query] = post.mock.calls[0];
      expect(path).toBe('/api/plugins/import/execute-direct');
      expect(body).toEqual(minimalManifest);
      expect(query).toMatchObject({
        dryRun: 'true',
        conflictStrategy: 'error',
      });
    });
  });

  describe('execute path', () => {
    it('passes dryRun=false on the query string when caller opts in', async () => {
      const post = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        data: { importId: 'imp-1', success: true, pluginPid: 'plg-1' },
      });
      const client = { post, get: vi.fn(), delete: vi.fn() } as unknown as ApiClient;
      const tool = importPluginTool(client);

      await tool.handler({
        manifest: minimalManifest,
        dryRun: false,
        conflictStrategy: 'overwrite',
        autoDeployProcesses: true,
        autoPublishModels: true,
        autoPublishFields: true,
        autoPublishCommands: true,
        autoPublishPages: true,
      });

      const [, , query] = post.mock.calls[0];
      expect(query).toMatchObject({
        dryRun: 'false',
        conflictStrategy: 'overwrite',
      });
    });

    it('forwards autoPublish flags as strings', async () => {
      const post = vi.fn().mockResolvedValue({ ok: true, status: 200, data: {} });
      const client = { post, get: vi.fn(), delete: vi.fn() } as unknown as ApiClient;
      const tool = importPluginTool(client);

      await tool.handler({
        manifest: minimalManifest,
        dryRun: false,
        conflictStrategy: 'skip',
        autoDeployProcesses: false,
        autoPublishModels: false,
        autoPublishFields: false,
        autoPublishCommands: true,
        autoPublishPages: false,
      });

      const [, , query] = post.mock.calls[0];
      expect(query).toMatchObject({
        autoDeployProcesses: 'false',
        autoPublishModels: 'false',
        autoPublishCommands: 'true',
        autoPublishPages: 'false',
      });
    });
  });

  describe('zod input', () => {
    it('rejects unknown conflictStrategy', () => {
      const tool = importPluginTool({} as ApiClient);
      const parsed = tool.inputSchema.safeParse({
        manifest: minimalManifest,
        conflictStrategy: 'merge',
      });
      expect(parsed.success).toBe(false);
    });
  });

  describe('error mapping', () => {
    it('surfaces non-ok response as isError carrying status + dryRun', async () => {
      const post = vi.fn().mockResolvedValue({
        ok: false,
        status: 422,
        data: null,
        message: 'invalid manifest version',
      });
      const client = { post, get: vi.fn(), delete: vi.fn() } as unknown as ApiClient;
      const tool = importPluginTool(client);

      const result = await tool.handler({
        manifest: minimalManifest,
        dryRun: true,
        conflictStrategy: 'error',
        autoDeployProcesses: true,
        autoPublishModels: true,
        autoPublishFields: true,
        autoPublishCommands: true,
        autoPublishPages: true,
      });

      expect(result.isError).toBe(true);
      const body = JSON.parse(result.content[0].text);
      expect(body.status).toBe(422);
      expect(body.dryRun).toBe(true);
      expect(body.error).toMatch(/invalid manifest version/);
    });
  });
});
