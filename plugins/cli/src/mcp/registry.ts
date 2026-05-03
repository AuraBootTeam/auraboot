import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { z } from 'zod';

/**
 * MCP tool annotations as defined by the 2025-11-25 spec.
 * Clients use these hints to surface confirmation prompts or rate-limit calls.
 */
export interface ToolAnnotations {
  readOnlyHint?: boolean;
  destructiveHint?: boolean;
  idempotentHint?: boolean;
  openWorldHint?: boolean;
}

export interface ToolResult {
  content: Array<{ type: 'text'; text: string }>;
  isError?: boolean;
}

// Tools generic over their input type — but storage must be Tool<any>
// because handler parameter types are contravariant (a handler expecting
// `{ entityCode: string }` is NOT assignable to a slot expecting `unknown`).
// Tool authors keep precise types via z.infer<typeof inputSchema>;
// the registry deliberately erases that for uniform storage.
export interface Tool<TInput = any> {
  name: string;
  title: string;
  description: string;
  inputSchema: z.ZodObject<z.ZodRawShape>;
  annotations?: ToolAnnotations;
  handler: (params: TInput) => Promise<ToolResult>;
}

/**
 * Wraps a tool handler with a measurement / audit hook.
 * Receives the tool name and the underlying invocation as a thunk.
 */
export type AuditWrapper = (
  toolName: string,
  fn: () => Promise<ToolResult>,
) => Promise<ToolResult>;

/**
 * Central registry for MCP tools.
 *
 * Lets us:
 *   - Hold all tool definitions in one place (source of truth for tools/list)
 *   - Apply a uniform audit wrapper to every handler
 *   - Detect duplicate registrations at boot time rather than at call time
 *
 * Usage:
 *   const registry = new ToolRegistry();
 *   registry.register(queryEntityTool(client));
 *   registry.attachTo(server, auditWrapper);
 */
export class ToolRegistry {
  private tools = new Map<string, Tool<any>>();

  register(tool: Tool<any>): void {
    if (this.tools.has(tool.name)) {
      throw new Error(`Tool already registered: ${tool.name}`);
    }
    this.tools.set(tool.name, tool);
  }

  get(name: string): Tool<any> | undefined {
    return this.tools.get(name);
  }

  list(): Tool<any>[] {
    return [...this.tools.values()];
  }

  size(): number {
    return this.tools.size;
  }

  /**
   * Register every collected tool with the given McpServer.
   * If `audit` is provided, every handler invocation is routed through it.
   */
  attachTo(server: McpServer, audit?: AuditWrapper): void {
    for (const tool of this.list()) {
      const handler = audit
        ? (params: unknown) => audit(tool.name, () => tool.handler(params))
        : (params: unknown) => tool.handler(params);

      server.registerTool(
        tool.name,
        {
          title: tool.title,
          description: tool.description,
          inputSchema: tool.inputSchema.shape,
          ...(tool.annotations ? { annotations: tool.annotations } : {}),
        },
        handler as Parameters<typeof server.registerTool>[2],
      );
    }
  }
}
