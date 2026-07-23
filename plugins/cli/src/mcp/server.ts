import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import chalk from 'chalk';
import { ApiClient } from '../client/api-client.js';
import { makeAuditWrapper } from './audit.js';
import {
  DEFAULT_MCP_PROFILE,
  filterToolsByProfile,
  resolveMcpProfile,
  type McpProfileName,
} from './profiles.js';
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
import { createCommandTool } from './tools/write/createCommand.js';
import { createPageSchemaTool } from './tools/write/createPageSchema.js';
import { importPluginTool } from './tools/write/importPlugin.js';
import { rollbackImportTool } from './tools/write/rollbackImport.js';

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
export function buildToolRegistry(
  client: ApiClient,
  opts: { profile?: McpProfileName } = {},
): ToolRegistry {
  // Default to the full set so existing callers/tests keep every tool; the
  // user-facing `read` default is applied by startMcpServer via resolveMcpProfile.
  const profile = opts.profile ?? 'full';

  const tools = [
    // Read tools (preserved from v1.x — behavior must remain identical).
    queryEntityTool(client),
    runNamedQueryTool(client),
    listAgentsTool(client),
    listToolsTool(client),
    dispatchAgentTool(client),
    askAuraBotTool(client),
    // Discovery tools — pre-create context for write tools.
    queryDslCapabilitiesTool(client),
    queryExistingModelsTool(client),
    queryPageSchemasTool(client),
    // Static doc tool — pipeline phase reference for LLM context.
    describeCommandPipelineTool(),
    // Write tools — destructive, dryRun=true keeps LLM iteration safe.
    createModelTool(client),
    createPageSchemaTool(client),
    createCommandTool(client),
    importPluginTool(client),
    rollbackImportTool(client),
  ];

  const registry = new ToolRegistry();
  for (const tool of filterToolsByProfile(tools, profile)) {
    registry.register(tool);
  }
  return registry;
}

export async function startMcpServer(options: {
  token?: string;
  env?: string;
  profile?: string;
}): Promise<void> {
  // Profile precedence: --profile flag > AURA_MCP_PROFILE env > minimal default.
  const profile = resolveMcpProfile(options.profile ?? process.env.AURA_MCP_PROFILE);

  // `interactive: false` so that 403 / 404 from a single tool call returns
  // an ApiResponse instead of killing the long-lived MCP server process.
  const client = new ApiClient({ ...options, interactive: false });
  const ctx = pinTenant(client.getToken());
  const audit = makeAuditWrapper(ctx, { remoteClient: client });

  const server = new McpServer({
    name: 'aura',
    version: '2.0.0',
  });

  const registry = buildToolRegistry(client, { profile });
  registry.attachTo(server, audit);

  const transport = new StdioServerTransport();
  await server.connect(transport);

  // Banner goes to stderr — stdout is reserved for JSON-RPC frames.
  console.error(
    chalk.dim(
      `[aura-mcp] Connected as ${ctx.email ?? '<unknown>'} @ tenant=${ctx.tenantName ?? ctx.tenantId}`,
    ),
  );
  const profileHint =
    profile === DEFAULT_MCP_PROFILE
      ? ` (default; use --profile dsl-authoring|full to widen)`
      : '';
  console.error(
    chalk.dim(
      `[aura-mcp] Server ready — profile=${profile}, ${registry.size()} tools available${profileHint}`,
    ),
  );
}
