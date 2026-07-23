import { resolveTenantContext, type TenantContext } from './tenant-pin.js';

/**
 * Auth boundary for the Streamable HTTP MCP transport.
 *
 * Model (owner decision, 2026-07-23): static token + tenant header — the remote
 * agent sends `Authorization: Bearer <aura-token>`, and the tenant is resolved
 * per-request from that JWT (same logic as the stdio server's startup tenant
 * pin). No OAuth flow; reuses the existing CLI token.
 */

export function extractBearerToken(authorization: string | undefined | null): string | null {
  if (!authorization) return null;
  const m = /^Bearer\s+(.+)$/i.exec(authorization.trim());
  const token = m?.[1]?.trim();
  return token ? token : null;
}

export type HttpAuthResult =
  | { ok: true; token: string; ctx: TenantContext }
  | { ok: false; status: number; reason: string };

export function authenticateHttpRequest(authorization: string | undefined | null): HttpAuthResult {
  const token = extractBearerToken(authorization);
  if (!token) {
    return { ok: false, status: 401, reason: 'Missing Bearer token (Authorization: Bearer <aura-token>)' };
  }
  const tenant = resolveTenantContext(token);
  if (tenant.kind !== 'ok') {
    return { ok: false, status: 401, reason: tenant.reason };
  }
  return { ok: true, token, ctx: tenant.ctx };
}
