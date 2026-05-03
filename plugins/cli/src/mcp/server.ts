import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import chalk from 'chalk';
import { ApiClient } from '../client/api-client.js';
import { makeAuditWrapper } from './audit.js';
import { ToolRegistry } from './registry.js';
import { pinTenant } from './tenant-pin.js';
import { askAuraBotTool } from './tools/read/askAuraBot.js';
import { describeCommandPipelineTool } from './tools/read/describeCommandPipeline.js';
import { dispatchAgentTool } from './tools/read/dispatchAgent.js';
import { listAgentsTool } from './tools/read/listAgents.js';
import { listToolsTool } from './tools/read/listTools.js';
import { queryDslCapabilitiesTool } from './tools/read/queryDslCapabilities.js';
import { queryEntityTool } from './tools/read/queryEntity.js';
import { queryExistingModelsTool } from './tools/read/queryExistingModels.js';
import { queryPageSchemasTool } from './tools/read/queryPageSchemas.js';
import { runNamedQueryTool } from './tools/read/runNamedQuery.js';
import { createModelTool } from './tools/write/createModel.js';
import { createPageSchemaTool } from './tools/write/createPageSchema.js';

/**
 * Aura MCP Server — exposes AuraBoot data and (in later increments)
 * write operations as tools for AI agents running inside Cursor /
 * Claude Code / any MCP-aware client.
 *
 * Usage:
 *   aura mcp serve            # the only entry point
 *
 * Cursor `.cursor/mcp.json`:
 *   { "mcpServers": { "auraboot": { "command": "aura", "args": ["mcp", "serve"] } } }
 *
 * Claude Code `~/.claude/mcp_servers.json`:
 *   { "aura": { "command": "aura", "args": ["mcp", "serve"] } }
 *
 * The server REFUSES to start if the current session has no tenant
 * pinned. This is the multi-tenant safety boundary — without a pinned
 * tenant we cannot decide which AuraBoot instance the AI is writing to.
 */
/**
 * Build the canonical AuraBoot ToolRegistry, populated with every tool
 * that should be visible over MCP. Exported so tests (and any future
 * non-stdio entry point — Streamable HTTP, in-process embedding) can
 * exercise the same wiring as the production stdio server.
 */
export function buildToolRegistry(client: ApiClient): ToolRegistry {
  const registry = new ToolRegistry();

  // Read tools (preserved from v1.x — behavior must remain identical).
  registry.register(queryEntityTool(client));
  registry.register(runNamedQueryTool(client));
  registry.register(listAgentsTool(client));
  registry.register(listToolsTool(client));
  registry.register(dispatchAgentTool(client));
  registry.register(askAuraBotTool(client));

  // Discovery tools — pre-create context for write tools coming in W2.
  registry.register(queryDslCapabilitiesTool(client));
  registry.register(queryExistingModelsTool(client));
  registry.register(queryPageSchemasTool(client));

  // Static doc tool — pipeline phase reference for LLM context (D3).
  registry.register(describeCommandPipelineTool());

  // Write tools (W2) — destructive, dryRun=true keeps LLM iteration safe.
  registry.register(createModelTool(client));
  registry.register(createPageSchemaTool(client));

  return registry;
}

export async function startMcpServer(options: { token?: string; env?: string }): Promise<void> {
  const client = new ApiClient(options);
  const ctx = pinTenant(client.getToken());
  const audit = makeAuditWrapper(ctx, { remoteClient: client });

  const server = new McpServer({
    name: 'aura',
    version: '2.0.0',
  });

  const registry = buildToolRegistry(client);
  registry.attachTo(server, audit);

  const transport = new StdioServerTransport();
  await server.connect(transport);

  // Banner goes to stderr — stdout is reserved for JSON-RPC frames.
  console.error(
    chalk.dim(
      `[aura-mcp] Connected as ${ctx.email ?? '<unknown>'} @ tenant=${ctx.tenantName ?? ctx.tenantId}`,
    ),
  );
  console.error(chalk.dim(`[aura-mcp] Server ready — ${registry.size()} tools available`));
}
