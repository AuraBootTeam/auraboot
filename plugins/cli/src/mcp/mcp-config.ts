import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'fs';
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
  const existed = existsSync(file);

  let json: Record<string, any> = {};
  if (existed) {
    try {
      const parsed = JSON.parse(readFileSync(file, 'utf8'));
      if (parsed && typeof parsed === 'object') json = parsed;
    } catch {
      json = {};
    }
  }

  if (!json.mcpServers || typeof json.mcpServers !== 'object') json.mcpServers = {};
  const before = JSON.stringify(json.mcpServers.auraboot ?? null);
  json.mcpServers.auraboot = { command: AURA_MCP_ENTRY.command, args: [...AURA_MCP_ENTRY.args] };
  const after = JSON.stringify(json.mcpServers.auraboot);

  if (existed && before === after) return { client, path: file, action: 'unchanged' };

  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, `${JSON.stringify(json, null, 2)}\n`);
  return { client, path: file, action: existed ? 'updated' : 'created' };
}
