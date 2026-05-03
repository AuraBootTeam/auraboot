import { appendFileSync, mkdirSync } from 'fs';
import { homedir } from 'os';
import { join } from 'path';
import type { ApiClient } from '../client/api-client.js';
import type { ToolResult } from './registry.js';
import type { TenantContext } from './tenant-pin.js';

const AURA_DIR = join(homedir(), '.aura');
const AUDIT_FILE = join(AURA_DIR, 'mcp-audit.log');

export interface AuditEntry {
  ts: string;
  tool: string;
  tenantId: number;
  email?: string;
  durationMs: number;
  success: boolean;
  error?: string;
}

/**
 * Append one JSON-line entry to `~/.aura/mcp-audit.log`.
 * Audit failures MUST NOT break tool execution — we swallow IO errors.
 */
function writeLocal(entry: AuditEntry): void {
  try {
    mkdirSync(AURA_DIR, { recursive: true });
    appendFileSync(AUDIT_FILE, JSON.stringify(entry) + '\n');
  } catch {
    // Audit failures are intentionally silenced.
  }
}

/**
 * Fire-and-forget POST to /api/meta/audit/mcp-tool. Errors are logged at
 * stderr and swallowed — backend audit must NEVER block tool execution
 * (the same rule the local-file write above follows).
 *
 * D6 introduced this remote write alongside the local file write. The
 * local file remains the source of truth for offline review; the remote
 * row exists so the existing audit query UI can show MCP activity.
 */
function postRemote(client: ApiClient, payload: McpAuditPayload): void {
  void client
    .post('/api/meta/audit/mcp-tool', payload)
    .catch((e) => {
      // Stderr only — stdout is reserved for the JSON-RPC frame stream.
      // eslint-disable-next-line no-console
      console.error(
        `[aura-mcp] audit relay failed for ${payload.toolName}: ${(e as Error).message}`,
      );
    });
}

interface McpAuditPayload {
  toolName: string;
  input?: Record<string, unknown>;
  output?: Record<string, unknown>;
  success: boolean;
  errorMessage?: string;
  durationMs: number;
}

export interface AuditWrapperOptions {
  /**
   * When supplied, a fire-and-forget POST mirrors the audit entry to
   * the backend endpoint /api/meta/audit/mcp-tool. Tests typically omit
   * this so the wrapper is purely local.
   */
  remoteClient?: ApiClient;
}

/**
 * Build an audit wrapper bound to a pinned tenant context.
 *
 * The returned function matches the AuditWrapper signature consumed by
 * ToolRegistry.attachTo — every tool handler call goes through it,
 * recording duration, success, and (on failure) the error message that
 * was surfaced to the LLM. When `remoteClient` is provided, every entry
 * is also fire-and-forgot to the backend audit endpoint.
 */
export function makeAuditWrapper(
  ctx: TenantContext,
  options: AuditWrapperOptions = {},
): (toolName: string, fn: () => Promise<ToolResult>) => Promise<ToolResult> {
  return async (toolName, fn) => {
    const start = Date.now();
    let success = true;
    let error: string | undefined;
    let result: ToolResult | undefined;

    try {
      result = await fn();
      if (result && result.isError) {
        success = false;
        const text = result.content?.[0]?.text;
        error = typeof text === 'string' ? text : 'tool returned isError';
      }
      return result;
    } catch (e) {
      success = false;
      error = (e as Error).message;
      throw e;
    } finally {
      const durationMs = Date.now() - start;
      writeLocal({
        ts: new Date().toISOString(),
        tool: toolName,
        tenantId: ctx.tenantId,
        email: ctx.email,
        durationMs,
        success,
        error,
      });
      if (options.remoteClient) {
        postRemote(options.remoteClient, {
          toolName,
          success,
          errorMessage: error,
          durationMs,
        });
      }
    }
  };
}

// Exported for test access.
export const _internal = {
  AUDIT_FILE,
  writeLocal,
};
