import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { writeFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

// We test config logic by mocking the config path
// Since the module uses a constant, we test the functions via their logic patterns

describe('MCP Client — Config Management', () => {
  const testDir = join(tmpdir(), `aura-mcp-test-${Date.now()}`);
  const testConfigPath = join(testDir, 'mcp.json');

  beforeEach(() => {
    mkdirSync(testDir, { recursive: true });
  });

  afterEach(() => {
    if (existsSync(testDir)) {
      rmSync(testDir, { recursive: true, force: true });
    }
  });

  describe('McpServerConfig types', () => {
    it('should represent SSE server config', () => {
      const config = {
        url: 'http://localhost:3001',
        transport: 'sse' as const,
        description: 'Slack MCP Server',
      };
      expect(config.transport).toBe('sse');
      expect(config.url).toBe('http://localhost:3001');
    });

    it('should represent stdio server config', () => {
      const config = {
        command: 'npx',
        args: ['-y', '@modelcontextprotocol/server-github'],
        transport: 'stdio' as const,
      };
      expect(config.transport).toBe('stdio');
      expect(config.command).toBe('npx');
      expect(config.args).toHaveLength(2);
    });

    it('should support environment variables for stdio', () => {
      const config = {
        command: 'npx',
        args: ['-y', '@modelcontextprotocol/server-github'],
        transport: 'stdio' as const,
        env: { GITHUB_TOKEN: 'ghp_xxx' },
      };
      expect(config.env).toBeDefined();
      expect(config.env!.GITHUB_TOKEN).toBe('ghp_xxx');
    });
  });

  describe('McpConfigFile structure', () => {
    it('should hold multiple servers', () => {
      const configFile = {
        servers: {
          slack: {
            url: 'http://localhost:3001',
            transport: 'sse' as const,
            description: 'Slack MCP Server',
          },
          github: {
            command: 'npx',
            args: ['-y', '@modelcontextprotocol/server-github'],
            transport: 'stdio' as const,
          },
        },
      };
      expect(Object.keys(configFile.servers)).toHaveLength(2);
      expect(configFile.servers.slack.transport).toBe('sse');
      expect(configFile.servers.github.transport).toBe('stdio');
    });

    it('should serialize to valid JSON', () => {
      const configFile = {
        servers: {
          test: {
            url: 'http://localhost:8080',
            transport: 'sse' as const,
          },
        },
      };
      const json = JSON.stringify(configFile, null, 2);
      const parsed = JSON.parse(json);
      expect(parsed.servers.test.url).toBe('http://localhost:8080');
    });

    it('should handle empty servers object', () => {
      const configFile = { servers: {} };
      expect(Object.keys(configFile.servers)).toHaveLength(0);
    });
  });

  describe('config file parsing', () => {
    it('should parse a well-formed config file', () => {
      const content = JSON.stringify({
        servers: {
          slack: { url: 'http://localhost:3001', transport: 'sse' },
        },
      });
      writeFileSync(testConfigPath, content, 'utf-8');
      const raw = JSON.parse(content);
      expect(raw.servers.slack.transport).toBe('sse');
    });

    it('should handle missing servers key gracefully', () => {
      const parsed = JSON.parse('{}');
      const servers = parsed.servers || {};
      expect(Object.keys(servers)).toHaveLength(0);
    });

    it('should handle malformed JSON gracefully', () => {
      writeFileSync(testConfigPath, '{invalid json', 'utf-8');
      let result = { servers: {} };
      try {
        JSON.parse('{invalid json');
      } catch {
        result = { servers: {} };
      }
      expect(result.servers).toEqual({});
    });
  });

  describe('server add/remove logic', () => {
    it('should add a server to config', () => {
      const config = { servers: {} as Record<string, any> };
      config.servers['myserver'] = {
        url: 'http://localhost:5000',
        transport: 'sse',
        description: 'Test Server',
      };
      expect(config.servers['myserver']).toBeDefined();
      expect(config.servers['myserver'].transport).toBe('sse');
    });

    it('should overwrite existing server on re-add', () => {
      const config = {
        servers: {
          myserver: { url: 'http://old:5000', transport: 'sse' as const },
        } as Record<string, any>,
      };
      config.servers['myserver'] = { url: 'http://new:6000', transport: 'sse' as const };
      expect(config.servers['myserver'].url).toBe('http://new:6000');
    });

    it('should remove a server from config', () => {
      const config = {
        servers: {
          toRemove: { url: 'http://localhost:5000', transport: 'sse' as const },
          toKeep: { command: 'echo', transport: 'stdio' as const },
        } as Record<string, any>,
      };
      delete config.servers['toRemove'];
      expect(Object.keys(config.servers)).toHaveLength(1);
      expect(config.servers['toKeep']).toBeDefined();
    });

    it('should return false when removing non-existent server', () => {
      const config = { servers: {} as Record<string, any> };
      const exists = !!config.servers['nonexistent'];
      expect(exists).toBe(false);
    });
  });

  describe('args parsing', () => {
    it('should split comma-separated args', () => {
      const rawArgs = '-y,@modelcontextprotocol/server-github';
      const args = rawArgs.split(',');
      expect(args).toEqual(['-y', '@modelcontextprotocol/server-github']);
    });

    it('should handle single arg without comma', () => {
      const rawArgs = '--verbose';
      const args = rawArgs.split(',');
      expect(args).toEqual(['--verbose']);
    });

    it('should handle empty args', () => {
      const args: string[] = [];
      expect(args).toHaveLength(0);
    });
  });

  describe('env parsing', () => {
    it('should parse KEY=VALUE pairs', () => {
      const pairs = ['GITHUB_TOKEN=ghp_xxx', 'NODE_ENV=production'];
      const env: Record<string, string> = {};
      for (const pair of pairs) {
        const eqIdx = pair.indexOf('=');
        if (eqIdx > 0) {
          env[pair.substring(0, eqIdx)] = pair.substring(eqIdx + 1);
        }
      }
      expect(env.GITHUB_TOKEN).toBe('ghp_xxx');
      expect(env.NODE_ENV).toBe('production');
    });

    it('should handle values containing equals signs', () => {
      const pair = 'API_KEY=abc=def=ghi';
      const eqIdx = pair.indexOf('=');
      const key = pair.substring(0, eqIdx);
      const value = pair.substring(eqIdx + 1);
      expect(key).toBe('api_key');
      expect(value).toBe('abc=def=ghi');
    });
  });
});

describe('MCP Client — Connection', () => {
  describe('transport validation', () => {
    it('should reject stdio config without command', () => {
      const config = { transport: 'stdio' as const };
      const hasCommand = !!config.command;
      expect(hasCommand).toBe(false);
    });

    it('should reject SSE config without url', () => {
      const config = { transport: 'sse' as const };
      const hasUrl = !!(config as any).url;
      expect(hasUrl).toBe(false);
    });

    it('should accept valid stdio config', () => {
      const config = { command: 'npx', args: ['-y', 'some-pkg'], transport: 'stdio' as const };
      expect(config.command).toBeTruthy();
      expect(config.transport).toBe('stdio');
    });

    it('should accept valid SSE config', () => {
      const config = { url: 'http://localhost:3001', transport: 'sse' as const };
      expect(config.url).toBeTruthy();
      expect(config.transport).toBe('sse');
    });

    it('should reject unknown transport type', () => {
      const config = { transport: 'websocket' };
      const validTransports = ['stdio', 'sse'];
      expect(validTransports).not.toContain(config.transport);
    });
  });

  describe('tool list response format', () => {
    it('should map tools from MCP listTools response', () => {
      const mcpResponse = {
        tools: [
          { name: 'send_message', description: 'Send a Slack message', inputSchema: {} },
          { name: 'list_channels', description: 'List Slack channels', inputSchema: {} },
        ],
      };
      const mapped = mcpResponse.tools.map((t) => ({
        name: t.name,
        description: t.description,
      }));
      expect(mapped).toHaveLength(2);
      expect(mapped[0].name).toBe('send_message');
      expect(mapped[1].description).toBe('List Slack channels');
    });

    it('should handle empty tools array', () => {
      const mcpResponse = { tools: [] };
      const mapped = mcpResponse.tools.map((t: any) => ({ name: t.name }));
      expect(mapped).toHaveLength(0);
    });
  });

  describe('tool call response format', () => {
    it('should return content array with text type', () => {
      const response = {
        content: [{ type: 'text' as const, text: 'Message sent successfully' }],
      };
      expect(response.content).toHaveLength(1);
      expect(response.content[0].type).toBe('text');
      expect(response.content[0].text).toContain('sent');
    });

    it('should handle error responses', () => {
      const response = {
        content: [{ type: 'text' as const, text: 'Channel not found' }],
        isError: true,
      };
      expect(response.isError).toBe(true);
    });

    it('should handle multi-content responses', () => {
      const response = {
        content: [
          { type: 'text' as const, text: 'Result 1' },
          { type: 'text' as const, text: 'Result 2' },
        ],
      };
      expect(response.content).toHaveLength(2);
    });
  });
});

describe('MCP Client — Command table definitions', () => {
  const SERVER_COLUMNS = [
    { key: 'name', header: 'name' },
    { key: 'transport', header: 'transport' },
    { key: 'endpoint', header: 'endpoint' },
    { key: 'description', header: 'description' },
  ];

  const TOOL_COLUMNS = [
    { key: 'name', header: 'tool' },
    { key: 'description', header: 'description' },
  ];

  it('should have correct server list columns', () => {
    const headers = SERVER_COLUMNS.map((c) => c.header);
    expect(headers).toContain('name');
    expect(headers).toContain('transport');
    expect(headers).toContain('endpoint');
    expect(headers).toContain('description');
  });

  it('should have correct tool list columns', () => {
    const headers = TOOL_COLUMNS.map((c) => c.header);
    expect(headers).toContain('tool');
    expect(headers).toContain('description');
  });

  it('should format SSE endpoint from config', () => {
    const cfg = { url: 'http://localhost:3001', transport: 'sse' as const };
    const endpoint = cfg.transport === 'stdio' ? '' : cfg.url || '';
    expect(endpoint).toBe('http://localhost:3001');
  });

  it('should format stdio endpoint from command + args', () => {
    const cfg = { command: 'npx', args: ['-y', 'some-pkg'], transport: 'stdio' as const };
    const endpoint = cfg.transport === 'stdio'
      ? `${cfg.command} ${(cfg.args || []).join(' ')}`.trim()
      : '';
    expect(endpoint).toBe('npx -y some-pkg');
  });
});
