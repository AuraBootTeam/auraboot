import { describe, expect, it } from 'vitest';
import { authenticateHttpRequest, extractBearerToken } from '../../src/mcp/http-auth.js';

/** Build an unsigned JWT with the given payload (resolveTenantContext only base64-decodes). */
function jwt(payload: Record<string, unknown>): string {
  const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString('base64url');
  return `${b64({ alg: 'none' })}.${b64(payload)}.sig`;
}

describe('extractBearerToken', () => {
  it('extracts the token from a Bearer header (case-insensitive, trimmed)', () => {
    expect(extractBearerToken('Bearer abc.def.ghi')).toBe('abc.def.ghi');
    expect(extractBearerToken('bearer abc')).toBe('abc');
    expect(extractBearerToken('  Bearer   abc  ')).toBe('abc');
  });

  it('returns null for missing / non-bearer / empty headers', () => {
    expect(extractBearerToken(undefined)).toBeNull();
    expect(extractBearerToken('')).toBeNull();
    expect(extractBearerToken('Basic xyz')).toBeNull();
    expect(extractBearerToken('Bearer ')).toBeNull();
  });
});

describe('authenticateHttpRequest', () => {
  it('401 when there is no bearer token', () => {
    const r = authenticateHttpRequest(undefined);
    expect(r).toMatchObject({ ok: false, status: 401 });
  });

  it('401 when the token has no tenant', () => {
    const r = authenticateHttpRequest(`Bearer ${jwt({ sub: 'a@b.com' })}`);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.status).toBe(401);
  });

  it('ok with the resolved tenant context when the token carries a tenantId', () => {
    const r = authenticateHttpRequest(`Bearer ${jwt({ tenantId: 7, tenantName: 'acme', email: 'a@b.com' })}`);
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.ctx.tenantId).toBe(7);
      expect(r.ctx.tenantName).toBe('acme');
      expect(r.token).toContain('.');
    }
  });
});
