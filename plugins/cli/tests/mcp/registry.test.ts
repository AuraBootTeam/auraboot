import { beforeEach, describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import { ToolRegistry, type Tool } from '../../src/mcp/registry.js';

function makeTool(name: string, overrides: Partial<Tool> = {}): Tool {
  return {
    name,
    title: `Title ${name}`,
    description: `Description ${name}`,
    inputSchema: z.object({ x: z.string().optional() }),
    handler: async () => ({
      content: [{ type: 'text' as const, text: `result-${name}` }],
    }),
    ...overrides,
  };
}

describe('ToolRegistry', () => {
  let registry: ToolRegistry;

  beforeEach(() => {
    registry = new ToolRegistry();
  });

  describe('register', () => {
    it('stores a tool by name', () => {
      const tool = makeTool('alpha');
      registry.register(tool);
      expect(registry.get('alpha')).toBe(tool);
      expect(registry.size()).toBe(1);
    });

    it('throws on duplicate registration', () => {
      registry.register(makeTool('alpha'));
      expect(() => registry.register(makeTool('alpha'))).toThrow(
        /already registered: alpha/,
      );
    });

    it('keeps multiple distinct tools', () => {
      registry.register(makeTool('alpha'));
      registry.register(makeTool('beta'));
      registry.register(makeTool('gamma'));
      expect(registry.size()).toBe(3);
      expect(registry.list().map((t) => t.name)).toEqual(['alpha', 'beta', 'gamma']);
    });
  });

  describe('get / list', () => {
    it('returns undefined for unknown name', () => {
      expect(registry.get('does-not-exist')).toBeUndefined();
    });

    it('list returns insertion order', () => {
      registry.register(makeTool('zebra'));
      registry.register(makeTool('aardvark'));
      const names = registry.list().map((t) => t.name);
      expect(names).toEqual(['zebra', 'aardvark']);
    });
  });

  describe('attachTo', () => {
    it('registers each tool with the server', () => {
      const server = { registerTool: vi.fn() } as unknown as Parameters<
        ToolRegistry['attachTo']
      >[0];

      registry.register(makeTool('one'));
      registry.register(makeTool('two'));
      registry.attachTo(server);

      expect((server as any).registerTool).toHaveBeenCalledTimes(2);
      expect((server as any).registerTool.mock.calls[0][0]).toBe('one');
      expect((server as any).registerTool.mock.calls[1][0]).toBe('two');
    });

    it('forwards title + description + zod shape', () => {
      const server = { registerTool: vi.fn() } as unknown as Parameters<
        ToolRegistry['attachTo']
      >[0];

      registry.register(makeTool('alpha'));
      registry.attachTo(server);

      const meta = (server as any).registerTool.mock.calls[0][1];
      expect(meta.title).toBe('Title alpha');
      expect(meta.description).toBe('Description alpha');
      expect(meta.inputSchema).toBeDefined(); // zod shape passed through
    });

    it('routes handler through audit wrapper when provided', async () => {
      const server = { registerTool: vi.fn() } as unknown as Parameters<
        ToolRegistry['attachTo']
      >[0];

      const tool = makeTool('alpha');
      registry.register(tool);

      const audit = vi.fn(async (_name: string, fn: () => Promise<any>) => fn());
      registry.attachTo(server, audit);

      const wrappedHandler = (server as any).registerTool.mock.calls[0][2];
      const result = await wrappedHandler({ x: 'value' });

      expect(audit).toHaveBeenCalledTimes(1);
      expect(audit.mock.calls[0][0]).toBe('alpha');
      expect(result.content[0].text).toBe('result-alpha');
    });

    it('skips audit when wrapper omitted', async () => {
      const server = { registerTool: vi.fn() } as unknown as Parameters<
        ToolRegistry['attachTo']
      >[0];

      registry.register(makeTool('alpha'));
      registry.attachTo(server);

      const wrappedHandler = (server as any).registerTool.mock.calls[0][2];
      const result = await wrappedHandler({ x: 'value' });
      expect(result.content[0].text).toBe('result-alpha');
    });

    it('forwards annotations when present', () => {
      const server = { registerTool: vi.fn() } as unknown as Parameters<
        ToolRegistry['attachTo']
      >[0];

      registry.register(
        makeTool('readonly', {
          annotations: { readOnlyHint: true, idempotentHint: true },
        }),
      );
      registry.attachTo(server);

      const meta = (server as any).registerTool.mock.calls[0][1];
      expect(meta.annotations).toEqual({ readOnlyHint: true, idempotentHint: true });
    });
  });
});
