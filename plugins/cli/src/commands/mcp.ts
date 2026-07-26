import chalk from 'chalk';
import {
  loadMcpConfig,
  addServer,
  removeServer,
  getServer,
  connectToServer,
  listServerTools,
  saveMcpConfig,
  mergePulledMcpConfig,
  selectMcpServersForPush,
  CONFIG_PATH,
  type McpServerConfig,
} from '../mcp/client.js';
import { ApiClient } from '../client/api-client.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../output/formatter.js';

interface McpCommandOptions {
  format?: string;
  agentMode?: boolean;
  token?: string;
  env?: string;
}

// ── aura mcp list ────────────────────────────────────────────────────────────

const SERVER_COLUMNS: ColumnDef[] = [
  { key: 'name', header: 'name' },
  { key: 'transport', header: 'transport' },
  { key: 'endpoint', header: 'endpoint' },
  { key: 'description', header: 'description' },
];

export async function mcpListCommand(options: McpCommandOptions): Promise<void> {
  const opts = resolveOutputOptions(options);
  const config = loadMcpConfig();
  const entries = Object.entries(config.servers);

  if (entries.length === 0) {
    if (!opts.agentMode) {
      console.log(chalk.dim('No MCP servers configured.'));
      console.log(chalk.dim(`Config file: ${CONFIG_PATH}`));
      console.log();
      console.log(chalk.dim('Add one with:'));
      console.log(chalk.cyan('  aura mcp add slack --url http://localhost:3001 --transport sse'));
      console.log(chalk.cyan('  aura mcp add github --command npx --args "-y,@modelcontextprotocol/server-github" --transport stdio'));
    } else {
      console.log(JSON.stringify([]));
    }
    return;
  }

  const rows = entries.map(([name, cfg]) => ({
    name,
    transport: cfg.transport,
    endpoint: cfg.transport === 'stdio'
      ? `${cfg.command} ${(cfg.args || []).join(' ')}`.trim()
      : cfg.url || '',
    description: cfg.description || '',
  }));

  printOutput(rows, SERVER_COLUMNS, opts);
}

// ── aura mcp add ─────────────────────────────────────────────────────────────

export interface McpAddOptions extends Omit<McpCommandOptions, 'env'> {
  url?: string;
  command?: string;
  args?: string;
  transport: string;
  description?: string;
  env?: string[];
  authType?: string;
  token?: string;
  header?: string;
}

export async function mcpAddCommand(name: string, options: McpAddOptions): Promise<void> {
  const transport = options.transport === 'http'
    ? 'streamable_http'
    : options.transport as 'stdio' | 'sse' | 'streamable_http';

  if (!['stdio', 'sse', 'streamable_http'].includes(transport)) {
    console.error(chalk.red(
      `Invalid transport "${transport}". Must be stdio, sse, or streamable_http.`,
    ));
    process.exit(1);
  }

  if (transport !== 'stdio' && !options.url) {
    console.error(chalk.red(`${transport} transport requires --url`));
    process.exit(1);
  }

  if (transport === 'stdio' && !options.command) {
    console.error(chalk.red('stdio transport requires --command'));
    process.exit(1);
  }

  const serverConfig: McpServerConfig = { transport };

  if (transport !== 'stdio') {
    serverConfig.url = options.url;
  } else {
    serverConfig.command = options.command;
    if (options.args) {
      serverConfig.args = options.args.split(',');
    }
  }

  if (options.description) {
    serverConfig.description = options.description;
  }

  if (options.authType) {
    const authType = options.authType.toLowerCase().replace('-', '_');
    if (!['none', 'bearer', 'api_key'].includes(authType)) {
      console.error(chalk.red('Invalid auth type. Must be none, bearer, or api_key.'));
      process.exit(1);
    }
    serverConfig.authType = authType as McpServerConfig['authType'];
    if (authType !== 'none') {
      if (!options.token) {
        console.error(chalk.red(`${authType} authentication requires --token`));
        process.exit(1);
      }
      serverConfig.authConfig = {
        token: options.token,
        ...(options.header ? { header: options.header } : {}),
      };
    }
  }

  // Parse --env KEY=VALUE pairs
  if (options.env && options.env.length > 0) {
    serverConfig.env = {};
    for (const pair of options.env) {
      const eqIdx = pair.indexOf('=');
      if (eqIdx > 0) {
        serverConfig.env[pair.substring(0, eqIdx)] = pair.substring(eqIdx + 1);
      }
    }
  }

  addServer(name, serverConfig);
  console.log(chalk.green(`✓ Added MCP server "${name}" (${transport})`));
  console.log(chalk.dim(`  Config saved to ${CONFIG_PATH}`));
}

export interface McpSyncOptions extends McpCommandOptions {
  dryRun?: boolean;
}

export async function mcpPushCommand(
  name: string | undefined,
  options: McpSyncOptions,
  client: ApiClient = new ApiClient(options),
): Promise<void> {
  const local = loadMcpConfig();
  const servers = selectMcpServersForPush(local, name);
  await client.requireAuth();
  const response = await client.post<{
    dryRun: boolean;
    changes: Array<{ name: string; action: string; transport: string }>;
    total: number;
  }>('/api/agent/mcp-servers/sync', {
    servers,
    dryRun: Boolean(options.dryRun),
  });
  if (!response.ok) {
    throw new Error(response.message || 'MCP configuration push failed');
  }
  printOutput(response.data.changes, [
    { key: 'name', header: 'name' },
    { key: 'action', header: 'action' },
    { key: 'transport', header: 'transport' },
  ], resolveOutputOptions(options));
}

export async function mcpPullCommand(
  name: string | undefined,
  options: McpCommandOptions,
  client: ApiClient = new ApiClient(options),
): Promise<void> {
  await client.requireAuth();
  const response = await client.get<{
    servers: Record<string, import('../mcp/client.js').PortableMcpServerConfig>;
  }>('/api/agent/mcp-servers/export');
  if (!response.ok) {
    throw new Error(response.message || 'MCP configuration pull failed');
  }
  const remoteServers = name
    ? { [name]: response.data.servers[name] }
    : response.data.servers;
  if (name && !response.data.servers[name]) {
    throw new Error(`Server "${name}" does not exist on the platform`);
  }
  const merged = mergePulledMcpConfig(
    loadMcpConfig(),
    { servers: remoteServers },
  );
  saveMcpConfig(merged.config);
  console.log(chalk.green(
    `✓ Pulled ${Object.keys(remoteServers).length} MCP server configuration(s)`,
  ));
  if (merged.secretsRequired.length > 0) {
    console.warn(chalk.yellow(
      `Secrets must be entered locally for: ${merged.secretsRequired.join(', ')}`,
    ));
  }
}

// ── aura mcp remove ──────────────────────────────────────────────────────────

export async function mcpRemoveCommand(name: string): Promise<void> {
  const removed = removeServer(name);
  if (removed) {
    console.log(chalk.green(`✓ Removed MCP server "${name}"`));
  } else {
    console.error(chalk.red(`Server "${name}" not found in config.`));
    process.exit(1);
  }
}

// ── aura mcp test ────────────────────────────────────────────────────────────

export async function mcpTestCommand(name: string, options: McpCommandOptions): Promise<void> {
  const config = getServer(name);
  if (!config) {
    console.error(chalk.red(`Server "${name}" not found. Run: aura mcp list`));
    process.exit(1);
  }

  console.log(chalk.dim(`Connecting to "${name}" (${config.transport})...`));

  try {
    const conn = await connectToServer(name, config);
    const tools = await listServerTools(conn);
    await conn.close();

    const serverInfo = conn.client.getServerVersion();
    const serverName = serverInfo?.name || 'unknown';
    const serverVersion = serverInfo?.version || 'unknown';

    console.log(chalk.green(`✓ Connected to ${serverName} v${serverVersion}`));
    console.log(chalk.dim(`  Tools available: ${tools.length}`));
    if (tools.length > 0) {
      const preview = tools.slice(0, 5).map((t) => t.name).join(', ');
      const suffix = tools.length > 5 ? ` (+${tools.length - 5} more)` : '';
      console.log(chalk.dim(`  Preview: ${preview}${suffix}`));
    }
  } catch (err) {
    console.error(chalk.red(`✗ Connection failed: ${(err as Error).message}`));
    process.exit(1);
  }
}

// ── aura mcp tools ───────────────────────────────────────────────────────────

const TOOL_COLUMNS: ColumnDef[] = [
  { key: 'name', header: 'tool' },
  { key: 'description', header: 'description', width: 60 },
];

export async function mcpToolsCommand(name: string, options: McpCommandOptions): Promise<void> {
  const opts = resolveOutputOptions(options);
  const config = getServer(name);
  if (!config) {
    console.error(chalk.red(`Server "${name}" not found. Run: aura mcp list`));
    process.exit(1);
  }

  if (!opts.agentMode) {
    console.log(chalk.dim(`Connecting to "${name}"...`));
  }

  try {
    const conn = await connectToServer(name, config);
    const tools = await listServerTools(conn);
    await conn.close();

    if (!opts.agentMode) {
      console.log(chalk.green(`✓ ${tools.length} tools from "${name}"\n`));
    }

    printOutput(tools, TOOL_COLUMNS, opts);
  } catch (err) {
    console.error(chalk.red(`Failed to list tools: ${(err as Error).message}`));
    process.exit(1);
  }
}
