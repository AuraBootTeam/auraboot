import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';

// ── Config types ─────────────────────────────────────────────────────────────

export interface McpServerConfig {
  /** URL for SSE transport */
  url?: string;
  /** Command for stdio transport */
  command?: string;
  /** Args for stdio transport */
  args?: string[];
  /** Environment variables for stdio transport */
  env?: Record<string, string>;
  /** Transport type */
  transport: 'stdio' | 'sse';
  /** Human-readable description */
  description?: string;
}

export interface McpConfigFile {
  servers: Record<string, McpServerConfig>;
}

// ── Config file management ───────────────────────────────────────────────────

const CONFIG_DIR = join(homedir(), '.aura');
const CONFIG_PATH = join(CONFIG_DIR, 'mcp.json');

/**
 * Load MCP server configuration from ~/.aura/mcp.json.
 * Returns empty config if file does not exist.
 */
export function loadMcpConfig(): McpConfigFile {
  if (!existsSync(CONFIG_PATH)) {
    return { servers: {} };
  }
  try {
    const raw = readFileSync(CONFIG_PATH, 'utf-8');
    const parsed = JSON.parse(raw);
    return { servers: parsed.servers || {} };
  } catch {
    return { servers: {} };
  }
}

/**
 * Save MCP server configuration to ~/.aura/mcp.json.
 */
export function saveMcpConfig(config: McpConfigFile): void {
  mkdirSync(CONFIG_DIR, { recursive: true });
  writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2) + '\n', 'utf-8');
}

/**
 * Add or update an MCP server in the config.
 */
export function addServer(name: string, server: McpServerConfig): void {
  const config = loadMcpConfig();
  config.servers[name] = server;
  saveMcpConfig(config);
}

/**
 * Remove an MCP server from the config.
 */
export function removeServer(name: string): boolean {
  const config = loadMcpConfig();
  if (!config.servers[name]) return false;
  delete config.servers[name];
  saveMcpConfig(config);
  return true;
}

/**
 * Get a single server config by name. Returns undefined if not found.
 */
export function getServer(name: string): McpServerConfig | undefined {
  const config = loadMcpConfig();
  return config.servers[name];
}

// ── MCP Client connection ────────────────────────────────────────────────────

export interface McpConnection {
  client: Client;
  close: () => Promise<void>;
}

/**
 * Connect to an MCP server using its config.
 * Performs the initialize handshake and returns a connected client.
 */
export async function connectToServer(
  name: string,
  config: McpServerConfig,
  timeoutMs = 15_000,
): Promise<McpConnection> {
  const client = new Client(
    { name: 'aura-cli', version: '2.0.0' },
    { capabilities: {} },
  );

  let transport;
  if (config.transport === 'stdio') {
    if (!config.command) {
      throw new Error(`Server "${name}" is configured for stdio but has no command`);
    }
    transport = new StdioClientTransport({
      command: config.command,
      args: config.args || [],
      env: config.env,
    });
  } else if (config.transport === 'sse') {
    if (!config.url) {
      throw new Error(`Server "${name}" is configured for SSE but has no url`);
    }
    transport = new SSEClientTransport(new URL(config.url));
  } else {
    throw new Error(`Unsupported transport: ${config.transport}`);
  }

  // Connect with timeout
  const timer = setTimeout(() => {
    throw new Error(`Connection to "${name}" timed out after ${timeoutMs}ms`);
  }, timeoutMs);

  try {
    await client.connect(transport);
  } finally {
    clearTimeout(timer);
  }

  return {
    client,
    close: async () => {
      await client.close();
    },
  };
}

/**
 * List tools from a connected MCP server.
 */
export async function listServerTools(
  conn: McpConnection,
): Promise<Array<{ name: string; description?: string }>> {
  const result = await conn.client.listTools();
  return (result.tools || []).map((t) => ({
    name: t.name,
    description: t.description,
  }));
}

/**
 * Call a tool on a connected MCP server.
 */
export async function callServerTool(
  conn: McpConnection,
  toolName: string,
  args: Record<string, unknown> = {},
): Promise<{ content: Array<{ type: string; text?: string }>; isError?: boolean }> {
  const result = await conn.client.callTool({ name: toolName, arguments: args });
  return result as { content: Array<{ type: string; text?: string }>; isError?: boolean };
}

export { CONFIG_PATH };
