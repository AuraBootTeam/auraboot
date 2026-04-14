import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { resolveToken, resolveBaseUrl } from '../../src/client/auth.js';

/**
 * Integration test for env-var auth resolution.
 *
 * These exercise the actual exported functions (unlike auth.test.ts which
 * re-implements the priority logic inline). They are the regression guard
 * for: non-interactive callers set AURA_TOKEN / AURA_API_URL and expect the
 * CLI to honor them without hitting credentials.json or network login.
 */
describe('auth env-var resolution', () => {
  const ORIGINAL_TOKEN = process.env.AURA_TOKEN;
  const ORIGINAL_API_URL = process.env.AURA_API_URL;

  beforeEach(() => {
    delete process.env.AURA_TOKEN;
    delete process.env.AURA_API_URL;
  });

  afterEach(() => {
    if (ORIGINAL_TOKEN === undefined) delete process.env.AURA_TOKEN;
    else process.env.AURA_TOKEN = ORIGINAL_TOKEN;
    if (ORIGINAL_API_URL === undefined) delete process.env.AURA_API_URL;
    else process.env.AURA_API_URL = ORIGINAL_API_URL;
  });

  describe('resolveToken', () => {
    it('returns AURA_TOKEN env var when set', () => {
      process.env.AURA_TOKEN = 'env-jwt-123';
      expect(resolveToken({})).toBe('env-jwt-123');
    });

    it('prefers --token flag over AURA_TOKEN env var', () => {
      process.env.AURA_TOKEN = 'env-jwt-123';
      expect(resolveToken({ token: 'flag-jwt' })).toBe('flag-jwt');
    });

    it('treats empty AURA_TOKEN as unset', () => {
      process.env.AURA_TOKEN = '';
      // Falls through to credentials file; in a clean env that is null.
      const result = resolveToken({});
      // Test environment may or may not have ~/.aura/credentials.json;
      // what we care about is the empty-string env var is not returned verbatim.
      expect(result).not.toBe('');
    });
  });

  describe('resolveBaseUrl', () => {
    it('returns AURA_API_URL env var when set', () => {
      process.env.AURA_API_URL = 'http://backend.test:6443';
      expect(resolveBaseUrl()).toBe('http://backend.test:6443');
    });

    it('strips trailing slash from AURA_API_URL', () => {
      process.env.AURA_API_URL = 'https://api.example.com/';
      expect(resolveBaseUrl()).toBe('https://api.example.com');
    });

    it('trims whitespace from AURA_API_URL', () => {
      process.env.AURA_API_URL = '  http://localhost:6443  ';
      expect(resolveBaseUrl()).toBe('http://localhost:6443');
    });

    it('falls back to default env when AURA_API_URL empty', () => {
      process.env.AURA_API_URL = '';
      // Default config ships with local=http://localhost:6443; don't assume
      // user's config, just assert we got a URL back (not the empty string).
      const url = resolveBaseUrl();
      expect(url).toMatch(/^https?:\/\//);
    });
  });
});
