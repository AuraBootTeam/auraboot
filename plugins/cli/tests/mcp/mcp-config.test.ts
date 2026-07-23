import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { writeAuraMcpConfig } from '../../src/mcp/mcp-config.js';

let root: string;
beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'aura-mcpcfg-'));
});
afterEach(() => {
  rmSync(root, { recursive: true, force: true });
});

describe('writeAuraMcpConfig', () => {
  it('creates a cursor mcp.json with the auraboot server entry', () => {
    const res = writeAuraMcpConfig(root, 'cursor');
    expect(res.action).toBe('created');
    const cfg = JSON.parse(readFileSync(join(root, '.cursor/mcp.json'), 'utf8'));
    expect(cfg.mcpServers.auraboot).toEqual({ command: 'aura', args: ['mcp', 'serve'] });
  });

  it('merges into an existing config without clobbering other servers', () => {
    mkdirSync(join(root, '.cursor'), { recursive: true });
    writeFileSync(
      join(root, '.cursor/mcp.json'),
      JSON.stringify({ mcpServers: { slack: { command: 'slack-mcp' } } }),
    );
    const res = writeAuraMcpConfig(root, 'cursor');
    expect(res.action).toBe('updated');
    const cfg = JSON.parse(readFileSync(join(root, '.cursor/mcp.json'), 'utf8'));
    expect(cfg.mcpServers.slack).toEqual({ command: 'slack-mcp' });
    expect(cfg.mcpServers.auraboot).toEqual({ command: 'aura', args: ['mcp', 'serve'] });
  });

  it('is idempotent — a second write reports unchanged', () => {
    writeAuraMcpConfig(root, 'cursor');
    expect(writeAuraMcpConfig(root, 'cursor').action).toBe('unchanged');
  });

  it('uses .mcp.json for claude', () => {
    writeAuraMcpConfig(root, 'claude');
    expect(existsSync(join(root, '.mcp.json'))).toBe(true);
  });

  it('returns manual (no file) for codex which uses a non-JSON config', () => {
    const res = writeAuraMcpConfig(root, 'codex');
    expect(res.action).toBe('manual');
    expect(res.path).toBeNull();
    expect(existsSync(join(root, '.agents'))).toBe(false);
  });
});
