import chalk from 'chalk';
import { EXIT } from '../client/api-client.js';

export interface TenantContext {
  tenantId: number;
  tenantName?: string;
  email?: string;
}

/**
 * Decode a JWT payload without verifying its signature.
 * The MCP server only uses this for display + tenant pinning;
 * the backend re-validates every API call so a forged token here
 * still cannot bypass authorization.
 */
export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const payload = Buffer.from(parts[1], 'base64url').toString('utf-8');
    const parsed = JSON.parse(payload);
    return typeof parsed === 'object' && parsed !== null ? parsed : null;
  } catch {
    return null;
  }
}

/**
 * Internal seam used by the public `pinTenant` and by tests.
 * Returns either a TenantContext or an error object describing why
 * the pin failed; `pinTenant` translates the latter into a process exit.
 */
export function resolveTenantContext(
  token: string | null,
): { kind: 'ok'; ctx: TenantContext } | { kind: 'error'; reason: string } {
  if (!token) {
    return { kind: 'error', reason: 'Not authenticated. Run: aura login' };
  }

  const payload = decodeJwtPayload(token);
  if (!payload) {
    return { kind: 'error', reason: 'Invalid token format. Run: aura login' };
  }

  const rawTenantId = payload.tenantId ?? payload.tenant_id;
  if (typeof rawTenantId !== 'number' && typeof rawTenantId !== 'string') {
    return {
      kind: 'error',
      reason: 'Token has no tenant context. Run: aura login --tenant <name>',
    };
  }

  const tenantId = Number(rawTenantId);
  if (!Number.isFinite(tenantId)) {
    return {
      kind: 'error',
      reason: 'Token tenantId is not numeric. Run: aura login',
    };
  }

  return {
    kind: 'ok',
    ctx: {
      tenantId,
      tenantName: typeof payload.tenantName === 'string' ? payload.tenantName : undefined,
      email:
        typeof payload.email === 'string'
          ? payload.email
          : typeof payload.sub === 'string'
            ? payload.sub
            : undefined,
    },
  };
}

/**
 * Verify the current session has a tenantId pinned and return a
 * TenantContext for the MCP server to use in banners + audit entries.
 *
 * Refuses to start (process.exit) if the JWT lacks a tenantId —
 * this is the multi-tenant safety boundary that prevents an MCP-driven
 * AI from inadvertently writing to the wrong tenant.
 */
export function pinTenant(token: string | null): TenantContext {
  const result = resolveTenantContext(token);
  if (result.kind === 'error') {
    console.error(chalk.red(result.reason));
    process.exit(EXIT.AUTH_REQUIRED);
  }
  return result.ctx;
}
