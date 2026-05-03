import { describe, expect, it } from 'vitest';
import {
  decodeJwtPayload,
  resolveTenantContext,
} from '../../src/mcp/tenant-pin.js';

function makeJwt(payload: Record<string, unknown>): string {
  const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString(
    'base64url',
  );
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
  return `${header}.${body}.unverified-signature`;
}

describe('decodeJwtPayload', () => {
  it('decodes a well-formed JWT payload', () => {
    const token = makeJwt({ tenantId: 42, email: 'demo@example.com' });
    expect(decodeJwtPayload(token)).toEqual({
      tenantId: 42,
      email: 'demo@example.com',
    });
  });

  it('returns null for tokens without 3 parts', () => {
    expect(decodeJwtPayload('not-a-jwt')).toBeNull();
    expect(decodeJwtPayload('header.body')).toBeNull();
    expect(decodeJwtPayload('a.b.c.d')).toBeNull();
  });

  it('returns null for tokens with non-base64 payload', () => {
    expect(decodeJwtPayload('header.~~not-base64~~.sig')).toBeNull();
  });

  it('returns null for tokens whose payload is not JSON', () => {
    const malformed = `header.${Buffer.from('not json').toString('base64url')}.sig`;
    expect(decodeJwtPayload(malformed)).toBeNull();
  });

  it('returns null when payload decodes to a non-object (e.g. JSON string)', () => {
    const stringPayload = `header.${Buffer.from('"plain string"').toString('base64url')}.sig`;
    expect(decodeJwtPayload(stringPayload)).toBeNull();
  });
});

describe('resolveTenantContext', () => {
  describe('failure modes', () => {
    it('errors when token is null', () => {
      const result = resolveTenantContext(null);
      expect(result.kind).toBe('error');
      if (result.kind === 'error') {
        expect(result.reason).toMatch(/Not authenticated/);
      }
    });

    it('errors when token has invalid format', () => {
      const result = resolveTenantContext('not-a-real-jwt');
      expect(result.kind).toBe('error');
      if (result.kind === 'error') {
        expect(result.reason).toMatch(/Invalid token format/);
      }
    });

    it('errors when payload has no tenantId claim', () => {
      const token = makeJwt({ email: 'demo@example.com' });
      const result = resolveTenantContext(token);
      expect(result.kind).toBe('error');
      if (result.kind === 'error') {
        expect(result.reason).toMatch(/no tenant context/);
      }
    });

    it('errors when tenantId is non-numeric and non-string', () => {
      const token = makeJwt({ tenantId: { weird: 'shape' } });
      const result = resolveTenantContext(token);
      expect(result.kind).toBe('error');
      if (result.kind === 'error') {
        expect(result.reason).toMatch(/no tenant context/);
      }
    });

    it('errors when string tenantId is not numeric', () => {
      const token = makeJwt({ tenantId: 'not-a-number' });
      const result = resolveTenantContext(token);
      expect(result.kind).toBe('error');
      if (result.kind === 'error') {
        expect(result.reason).toMatch(/not numeric/);
      }
    });
  });

  describe('happy paths', () => {
    it('accepts numeric tenantId', () => {
      const token = makeJwt({ tenantId: 42, email: 'demo@example.com' });
      const result = resolveTenantContext(token);
      expect(result.kind).toBe('ok');
      if (result.kind === 'ok') {
        expect(result.ctx.tenantId).toBe(42);
        expect(result.ctx.email).toBe('demo@example.com');
      }
    });

    it('accepts numeric-string tenantId', () => {
      const token = makeJwt({ tenantId: '7' });
      const result = resolveTenantContext(token);
      expect(result.kind).toBe('ok');
      if (result.kind === 'ok') {
        expect(result.ctx.tenantId).toBe(7);
      }
    });

    it('accepts snake_case tenant_id alias', () => {
      const token = makeJwt({ tenant_id: 12 });
      const result = resolveTenantContext(token);
      expect(result.kind).toBe('ok');
      if (result.kind === 'ok') {
        expect(result.ctx.tenantId).toBe(12);
      }
    });

    it('captures optional tenantName and email', () => {
      const token = makeJwt({
        tenantId: 42,
        tenantName: 'acme',
        email: 'alice@acme.io',
      });
      const result = resolveTenantContext(token);
      expect(result.kind).toBe('ok');
      if (result.kind === 'ok') {
        expect(result.ctx.tenantName).toBe('acme');
        expect(result.ctx.email).toBe('alice@acme.io');
      }
    });

    it('falls back to sub claim when email is missing', () => {
      const token = makeJwt({ tenantId: 42, sub: 'fallback@example.com' });
      const result = resolveTenantContext(token);
      expect(result.kind).toBe('ok');
      if (result.kind === 'ok') {
        expect(result.ctx.email).toBe('fallback@example.com');
      }
    });

    it('returns undefined tenantName / email when claims are missing', () => {
      const token = makeJwt({ tenantId: 42 });
      const result = resolveTenantContext(token);
      expect(result.kind).toBe('ok');
      if (result.kind === 'ok') {
        expect(result.ctx.tenantName).toBeUndefined();
        expect(result.ctx.email).toBeUndefined();
      }
    });
  });
});
