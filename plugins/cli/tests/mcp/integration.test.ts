import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../src/client/api-client.js';
import { makeAuditWrapper } from '../../src/mcp/audit.js';
import { buildToolRegistry } from '../../src/mcp/server.js';

/**
 * End-to-end MCP integration test.
 *
 * Spins up the real `buildToolRegistry()` against an in-memory client/server
 * transport pair (no stdio child process, no network). Confirms a real MCP
 * client sees every tool we register and can call them — this is what
 * Cursor / Claude Code do at runtime.
 *
 * Pulls forward part of D11 so D5 ships with stronger evidence than a
 * hand-recorded screencast.
 */
describe('MCP server integration (in-memory transport)', () => {
  let server: McpServer;
  let client: Client;
  let apiGet: ReturnType<typeof vi.fn>;
  let apiPost: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    apiGet = vi.fn();
    apiPost = vi.fn();
    const fakeApi = {
      get: apiGet,
      post: apiPost,
    } as unknown as ApiClient;

    server = new McpServer({ name: 'aura-test', version: '0.0.0-test' });
    const registry = buildToolRegistry(fakeApi);
    registry.attachTo(server); // no audit wrapper in tests — keeps fixtures clean

    client = new Client(
      { name: 'aura-test-client', version: '0.0.0-test' },
      { capabilities: {} },
    );

    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
    await Promise.all([
      server.connect(serverTransport),
      client.connect(clientTransport),
    ]);
  });

  afterEach(async () => {
    await client.close();
    await server.close();
  });

  it('lists all 15 registered tools over MCP', async () => {
    const { tools } = await client.listTools();
    const names = tools.map((t) => t.name).sort();

    expect(names).toEqual(
      [
        // Read tools (preserved from v1.x)
        'ask_aurabot',
        'dispatch_agent',
        'list_agents',
        'list_tools',
        'query_entity',
        'run_named_query',
        // Discovery tools (D2)
        'query_dsl_capabilities',
        'query_existing_models',
        'query_page_schemas',
        // Static doc (D3)
        'describe_command_pipeline',
        // Write tools (W2)
        'create_model',
        'create_page_schema',
        'create_command',
        'import_plugin',
        'rollback_import',
      ].sort(),
    );
  });

  it('flags create_model as destructive in tool metadata', async () => {
    const { tools } = await client.listTools();
    const createModel = tools.find((t) => t.name === 'create_model')!;
    expect(createModel).toBeDefined();
    // SDK exposes annotations on the listed tool entry.
    expect((createModel as any).annotations?.destructiveHint).toBe(true);
  });

  it('forwards create_model to POST /api/meta/models stripping the dryRun flag', async () => {
    apiPost.mockResolvedValueOnce({
      ok: true,
      status: 200,
      data: { pid: 'mp-1', code: 'crm_lead' },
    });

    const result = await client.callTool({
      name: 'create_model',
      arguments: {
        code: 'crm_lead',
        displayName: 'Lead',
        modelType: 'entity',
        dryRun: false,
      },
    });

    expect(result.isError).toBeFalsy();
    expect(apiPost).toHaveBeenCalledTimes(1);
    const [path, body] = apiPost.mock.calls[0];
    expect(path).toBe('/api/meta/models');
    expect(body).not.toHaveProperty('dryRun');
    expect(body).toMatchObject({ code: 'crm_lead', displayName: 'Lead' });
  });

  it('import_plugin defaults dryRun=true on the wire when caller omits it', async () => {
    apiPost.mockResolvedValueOnce({
      ok: true,
      status: 200,
      data: { dryRun: true, valid: true, errors: [], conflicts: [] },
    });

    await client.callTool({
      name: 'import_plugin',
      arguments: {
        manifest: { pluginId: 'demo', namespace: 'demo', version: '1.0.0' },
      },
    });

    const [path, body, query] = apiPost.mock.calls[0];
    expect(path).toBe('/api/plugins/import/execute-direct');
    expect(body).toMatchObject({ pluginId: 'demo' });
    expect(query.dryRun).toBe('true');
  });

  it('rollback_import POSTs to /import/{id}/rollback', async () => {
    apiPost.mockResolvedValueOnce({
      ok: true,
      status: 200,
      data: { importId: 'imp-1', success: true },
    });

    const result = await client.callTool({
      name: 'rollback_import',
      arguments: { importId: 'imp-1' },
    });

    expect(result.isError).toBeFalsy();
    const [path] = apiPost.mock.calls[0];
    expect(path).toBe('/api/plugins/import/imp-1/rollback');
  });

  it('orchestrates create_command + 2 binding-rules over MCP without rollback', async () => {
    apiPost.mockImplementation(async (path: string) => {
      if (path === '/api/meta/commands') {
        return { ok: true, status: 200, data: { pid: 'cmd-x', code: 'crm_lead.assign' } };
      }
      return { ok: true, status: 200, data: { pid: 'br-' + Date.now() } };
    });

    const result = await client.callTool({
      name: 'create_command',
      arguments: {
        code: 'crm_lead.assign',
        modelCode: 'crm_lead',
        bindingRules: [
          { ruleType: 'EXPRESSION', expression: 'amount > 0' },
          { ruleType: 'EVENT', eventType: 'lead.assigned' },
        ],
        dryRun: false,
      },
    });

    expect(result.isError).toBeFalsy();
    expect(apiPost).toHaveBeenCalledTimes(3);
    expect(apiPost.mock.calls[0][0]).toBe('/api/meta/commands');
    expect(apiPost.mock.calls[1][0]).toBe('/api/meta/commands/cmd-x/binding-rules');
    expect(apiPost.mock.calls[2][0]).toBe('/api/meta/commands/cmd-x/binding-rules');
  });

  it('forwards create_page_schema to POST /api/pages with V2 flat body', async () => {
    apiPost.mockResolvedValueOnce({
      ok: true,
      status: 200,
      data: { pid: 'pg-1', pageKey: 'crm_lead_form' },
    });

    const result = await client.callTool({
      name: 'create_page_schema',
      arguments: {
        pageKey: 'crm_lead_form',
        name: 'crm_lead_form',
        title: 'Lead Form',
        kind: 'form',
        blocks: [{ blockType: 'form-section', blockId: 'main' }],
        dryRun: false,
      },
    });

    expect(result.isError).toBeFalsy();
    expect(apiPost).toHaveBeenCalledTimes(1);
    const [path, body] = apiPost.mock.calls[0];
    expect(path).toBe('/api/pages');
    expect(body).not.toHaveProperty('dryRun');
    expect(body.kind).toBe('form');
    expect(body.blocks).toHaveLength(1);
  });

  it('short-circuits create_model when dryRun=true (no HTTP)', async () => {
    const result = await client.callTool({
      name: 'create_model',
      arguments: {
        code: 'crm_lead',
        displayName: 'Lead',
        dryRun: true,
      },
    });

    expect(result.isError).toBeFalsy();
    expect(apiPost).not.toHaveBeenCalled();
    const text = (result.content as Array<{ text: string }>)[0]?.text;
    const parsed = JSON.parse(text);
    expect(parsed.dryRun).toBe(true);
    expect(parsed.wouldCreate.code).toBe('crm_lead');
  });

  it('calls describe_command_pipeline without any HTTP', async () => {
    const result = await client.callTool({
      name: 'describe_command_pipeline',
      arguments: {},
    });

    expect(result.isError).toBeFalsy();
    expect(apiGet).not.toHaveBeenCalled();
    expect(apiPost).not.toHaveBeenCalled();

    const text = (result.content as Array<{ text: string }>)[0]?.text;
    const parsed = JSON.parse(text);
    expect(parsed.totalTransactionalStages).toBe(20);
    expect(parsed.afterCommit).toHaveLength(4);
  });

  it('routes query_dsl_capabilities to GET /api/dsl/registry', async () => {
    apiGet.mockResolvedValueOnce({
      ok: true,
      status: 200,
      data: { version: '2.0', enums: { BlockType: [] } },
    });

    const result = await client.callTool({
      name: 'query_dsl_capabilities',
      arguments: {},
    });

    expect(result.isError).toBeFalsy();
    expect(apiGet).toHaveBeenCalledWith('/api/dsl/registry');
    const text = (result.content as Array<{ text: string }>)[0]?.text;
    expect(JSON.parse(text)).toEqual({ version: '2.0', enums: { BlockType: [] } });
  });

  it('forwards filters into query_existing_models', async () => {
    apiGet.mockResolvedValueOnce({
      ok: true,
      status: 200,
      data: { items: [{ code: 'crm_lead', displayName: 'Lead' }], total: 1 },
    });

    const result = await client.callTool({
      name: 'query_existing_models',
      arguments: { keyword: 'crm', limit: 25 },
    });

    expect(result.isError).toBeFalsy();
    expect(apiGet).toHaveBeenCalledWith('/api/meta/models', {
      page: '1',
      size: '25',
      currentOnly: 'true',
      keyword: 'crm',
    });
  });

  it('surfaces backend errors as isError without throwing on the wire', async () => {
    apiGet.mockResolvedValueOnce({
      ok: false,
      status: 403,
      data: null,
      message: 'Permission denied',
    });

    const result = await client.callTool({
      name: 'query_dsl_capabilities',
      arguments: {},
    });

    expect(result.isError).toBe(true);
    const text = (result.content as Array<{ text: string }>)[0]?.text;
    expect(text).toMatch(/Permission denied/);
  });

  it('relays one audit POST per tool invocation when audit is wired with a remote client', async () => {
    // Rebuild server with the audit wrapper actually attached so we can
    // verify the fire-and-forget audit relay reaches the backend.
    await client.close();
    await server.close();

    const auditPost = vi.fn().mockResolvedValue({ ok: true, status: 200, data: null });
    const auditedApi = {
      get: vi.fn(),
      post: vi.fn().mockImplementation(async (path: string, body?: unknown) => {
        if (path === '/api/meta/audit/mcp-tool') {
          return auditPost(path, body);
        }
        return { ok: true, status: 200, data: { pid: 'mp-1' } };
      }),
    } as unknown as ApiClient;

    const newServer = new McpServer({ name: 'aura-test', version: '0.0.0-test' });
    const registry = buildToolRegistry(auditedApi);
    const audit = makeAuditWrapper(
      { tenantId: 42, tenantName: 'acme', email: 'demo@acme.io' },
      { remoteClient: auditedApi },
    );
    registry.attachTo(newServer, audit);

    const newClient = new Client(
      { name: 'aura-test-client', version: '0.0.0-test' },
      { capabilities: {} },
    );
    const [ct, st] = InMemoryTransport.createLinkedPair();
    await Promise.all([newServer.connect(st), newClient.connect(ct)]);

    await newClient.callTool({
      name: 'create_model',
      arguments: { code: 'crm_lead', displayName: 'Lead', dryRun: false },
    });

    // Audit POST is fire-and-forget — wait one microtask cycle.
    await new Promise((resolve) => setImmediate(resolve));

    expect(auditPost).toHaveBeenCalledTimes(1);
    const [path, payload] = auditPost.mock.calls[0];
    expect(path).toBe('/api/meta/audit/mcp-tool');
    expect(payload).toMatchObject({
      toolName: 'create_model',
      success: true,
    });
    expect(typeof payload.durationMs).toBe('number');

    await newClient.close();
    await newServer.close();
  });

  it('rejects kind=dashboard at the schema layer for query_page_schemas', async () => {
    // zod rejection happens during the MCP protocol's own arg validation.
    // Either the SDK throws or returns isError; both are acceptable as long
    // as the bad input never reaches the handler (apiGet stays untouched).
    let threw = false;
    try {
      const result = await client.callTool({
        name: 'query_page_schemas',
        arguments: { kind: 'dashboard', limit: 10 },
      });
      // If the SDK surfaces a structured error rather than throwing:
      expect(result.isError).toBe(true);
    } catch {
      threw = true;
    }
    expect(threw || apiGet.mock.calls.length === 0).toBe(true);
    expect(apiGet).not.toHaveBeenCalled();
  });
});
