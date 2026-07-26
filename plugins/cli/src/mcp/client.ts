import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';

// ── Config types ─────────────────────────────────────────────────────────────

export interface McpServerConfig {
  /** URL for SSE or Streamable HTTP transport */
  url?: string;
  /** Command for stdio transport */
  command?: string;
  /** Args for stdio transport */
  args?: string[];
  /** Environment variables for stdio transport */
  env?: Record<string, string>;
  /** Transport type */
  transport: 'stdio' | 'sse' | 'streamable_http';
  /** Optional HTTP authentication, kept only in the local config. */
  authType?: 'none' | 'bearer' | 'api_key';
  authConfig?: {
    token?: string;
    header?: string;
  };
  /** Human-readable description */
  description?: string;
}

export interface McpConfigFile {
  servers: Record<string, McpServerConfig>;
}

export interface PortableMcpServerConfig extends Omit<McpServerConfig, 'env' | 'authConfig'> {
  envKeys?: string[];
  secretRequired?: boolean;
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
    return normalizeMcpConfig({ servers: parsed.servers || {} });
  } catch {
    return { servers: {} };
  }
}

/** Normalize the historical `http` spelling without rewriting on read. */
export function normalizeMcpConfig(config: McpConfigFile): McpConfigFile {
  const servers: Record<string, McpServerConfig> = {};
  for (const [name, raw] of Object.entries(config.servers || {})) {
    const transport = (raw.transport as string) === 'http'
      ? 'streamable_http'
      : raw.transport;
    servers[name] = { ...raw, transport };
  }
  return { servers };
}

/**
 * Merge a credential-free platform export into local configuration.
 *
 * Local-only servers remain untouched. Existing auth/env secrets are preserved
 * because the platform never exports reusable credentials.
 */
export function mergePulledMcpConfig(
  local: McpConfigFile,
  remote: { servers: Record<string, PortableMcpServerConfig> },
): { config: McpConfigFile; secretsRequired: string[] } {
  const merged = normalizeMcpConfig(local);
  const secretsRequired: string[] = [];
  for (const [name, portable] of Object.entries(remote.servers || {})) {
    const existing = merged.servers[name];
    const next: McpServerConfig = {
      transport: portable.transport,
      ...(portable.url ? { url: portable.url } : {}),
      ...(portable.command ? { command: portable.command } : {}),
      ...(portable.args ? { args: portable.args } : {}),
      ...(portable.description ? { description: portable.description } : {}),
      ...(portable.authType ? { authType: portable.authType } : {}),
    };
    if (existing?.env) next.env = existing.env;
    if (existing?.authConfig) next.authConfig = existing.authConfig;
    if (portable.secretRequired && !next.env && !next.authConfig) {
      secretsRequired.push(name);
    }
    merged.servers[name] = next;
  }
  return { config: merged, secretsRequired };
}

/** Select a deterministic whole-config or single-server payload for push. */
export function selectMcpServersForPush(
  config: McpConfigFile,
  name?: string,
): Record<string, McpServerConfig> {
  const normalized = normalizeMcpConfig(config);
  if (!name) return normalized.servers;
  const selected = normalized.servers[name];
  if (!selected) {
    throw new Error(`MCP server "${name}" is not configured locally`);
  }
  return { [name]: selected };
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
    const requestInit = { headers: authHeaders(config) };
    transport = new SSEClientTransport(new URL(config.url), {
      requestInit,
      eventSourceInit: { fetch: (url, init) => fetch(url, {
        ...init,
        headers: { ...authHeaders(config), ...(init?.headers || {}) },
      }) },
    });
  } else if (config.transport === 'streamable_http') {
    if (!config.url) {
      throw new Error(`Server "${name}" is configured for Streamable HTTP but has no url`);
    }
    transport = new StreamableHTTPClientTransport(new URL(config.url), {
      requestInit: { headers: authHeaders(config) },
    });
  } else {
    throw new Error(`Unsupported transport: ${config.transport}`);
  }

  // Connect with timeout
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([
      client.connect(transport),
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(
          new Error(`Connection to "${name}" timed out after ${timeoutMs}ms`),
        ), timeoutMs);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }

  return {
    client,
    close: async () => {
      await client.close();
    },
  };
}

function authHeaders(config: McpServerConfig): Record<string, string> {
  const token = config.authConfig?.token;
  if (!token || !config.authType || config.authType === 'none') return {};
  if (config.authType === 'bearer') {
    return { Authorization: `Bearer ${token}` };
  }
  if (config.authType === 'api_key') {
    return { [config.authConfig?.header || 'X-API-Key']: token };
  }
  return {};
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
