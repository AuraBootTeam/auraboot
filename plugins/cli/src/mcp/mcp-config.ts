import { closeSync, constants, fstatSync, fsyncSync, ftruncateSync, mkdirSync, openSync, readFileSync, writeSync } from 'fs';
import { dirname, join } from 'path';
import type { SkillClient } from '../skills/install.js';

/**
 * Writes the `auraboot` MCP server entry into an agent client's project config.
 *
 * Clients that use the standard `{ mcpServers: { ... } }` JSON shape get their
 * config merged in place (existing servers preserved). Clients with a non-JSON
 * config (Codex uses TOML) are reported as `manual` so `aura init` can print
 * instructions instead of clobbering an unfamiliar format.
 */

export const MCP_CONFIG_FILE: Record<SkillClient, string | null> = {
  cursor: '.cursor/mcp.json',
  claude: '.mcp.json',
  codex: null,
};

export const AURA_MCP_ENTRY = {
  command: 'aura',
  args: ['mcp', 'serve'],
} as const;

export interface McpConfigWrite {
  client: SkillClient;
  path: string | null;
  action: 'created' | 'updated' | 'unchanged' | 'manual';
}

export function writeAuraMcpConfig(root: string, client: SkillClient): McpConfigWrite {
  const rel = MCP_CONFIG_FILE[client];
  if (rel === null) return { client, path: null, action: 'manual' };

  const file = join(root, rel);
  mkdirSync(dirname(file), { recursive: true });
  const flags = constants.O_RDWR | constants.O_NOFOLLOW | constants.O_NONBLOCK;
  let descriptor: number;
  let existed = true;
  try {
    descriptor = openSync(file, flags);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
    try {
      descriptor = openSync(file, flags | constants.O_CREAT | constants.O_EXCL, 0o600);
      existed = false;
    } catch (creationError) {
      // A concurrent creator wins; read its config instead of overwriting it.
      if ((creationError as NodeJS.ErrnoException).code !== 'EEXIST') throw creationError;
      descriptor = openSync(file, flags);
    }
  }
  try {
    if (!fstatSync(descriptor).isFile()) throw new Error('MCP config must be a regular file');
    const json = existed ? JSON.parse(readFileSync(descriptor, 'utf8')) : {};
    if (!json || typeof json !== 'object' || Array.isArray(json)) {
      throw new Error('MCP config must be a JSON object');
    }
    if (json.mcpServers == null) json.mcpServers = {};
    if (typeof json.mcpServers !== 'object' || Array.isArray(json.mcpServers)) {
      throw new Error('MCP servers must be a JSON object');
    }
    const before = JSON.stringify(json.mcpServers.auraboot ?? null);
    json.mcpServers.auraboot = { command: AURA_MCP_ENTRY.command, args: [...AURA_MCP_ENTRY.args] };
    const after = JSON.stringify(json.mcpServers.auraboot);
    if (existed && before === after) return { client, path: file, action: 'unchanged' };

    // Write the same checked inode, preserving existing permissions and refusing symlink swaps.
    const bytes = Buffer.from(`${JSON.stringify(json, null, 2)}\n`);
    let offset = 0;
    while (offset < bytes.length) {
      const written = writeSync(descriptor, bytes, offset, bytes.length - offset, offset);
      if (written === 0) throw new Error('MCP config write made no progress');
      offset += written;
    }
    ftruncateSync(descriptor, bytes.length);
    fsyncSync(descriptor);
    return { client, path: file, action: existed ? 'updated' : 'created' };
  } finally {
    closeSync(descriptor);
  }
}
