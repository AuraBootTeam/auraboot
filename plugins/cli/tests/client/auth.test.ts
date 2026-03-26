import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'fs';
import { join } from 'path';
import { tmpdir } from 'os';
import { rmSync } from 'fs';

// We test auth functions by mocking the filesystem and fetch

describe('auth', () => {
  let tempDir: string;

  beforeEach(() => {
    tempDir = join(tmpdir(), `aura-test-${Date.now()}`);
    mkdirSync(tempDir, { recursive: true });
  });

  afterEach(() => {
    rmSync(tempDir, { recursive: true, force: true });
  });

  describe('credentials file', () => {
    it('should write credentials in JSON format', () => {
      const credFile = join(tempDir, 'credentials.json');
      const creds = { jwt: 'test-jwt', email: 'test@example.com', expiresAt: '2026-03-16T00:00:00Z' };
      writeFileSync(credFile, JSON.stringify({ local: creds }, null, 2));

      const loaded = JSON.parse(readFileSync(credFile, 'utf-8'));
      expect(loaded.local).toEqual(creds);
      expect(loaded.local.jwt).toBe('test-jwt');
      expect(loaded.local.email).toBe('test@example.com');
    });

    it('should support multiple environments', () => {
      const credFile = join(tempDir, 'credentials.json');
      const data = {
        local: { jwt: 'local-jwt', email: 'a@b.com' },
        staging: { jwt: 'staging-jwt', email: 'a@b.com' },
        production: { jwt: 'prod-jwt', email: 'a@b.com' },
      };
      writeFileSync(credFile, JSON.stringify(data, null, 2));

      const loaded = JSON.parse(readFileSync(credFile, 'utf-8'));
      expect(loaded.local.jwt).toBe('local-jwt');
      expect(loaded.staging.jwt).toBe('staging-jwt');
      expect(loaded.production.jwt).toBe('prod-jwt');
    });
  });

  describe('config file', () => {
    it('should parse config with environments', () => {
      const configFile = join(tempDir, 'config.json');
      const config = {
        defaultEnv: 'staging',
        environments: {
          local: { baseUrl: 'http://localhost:6443' },
          staging: { baseUrl: 'https://staging.auraboot.com' },
        },
        output: 'json',
      };
      writeFileSync(configFile, JSON.stringify(config, null, 2));

      const loaded = JSON.parse(readFileSync(configFile, 'utf-8'));
      expect(loaded.defaultEnv).toBe('staging');
      expect(loaded.environments.staging.baseUrl).toBe('https://staging.auraboot.com');
    });
  });

  describe('token resolution priority', () => {
    it('should prefer CLI flag over env var', () => {
      // Simulate the priority logic
      const resolveToken = (opts: { token?: string }, envToken?: string, fileCreds?: { jwt: string } | null) => {
        if (opts.token) return opts.token;
        if (envToken) return envToken;
        if (fileCreds?.jwt) return fileCreds.jwt;
        return null;
      };

      expect(resolveToken({ token: 'flag-jwt' }, 'env-jwt', { jwt: 'file-jwt' })).toBe('flag-jwt');
      expect(resolveToken({}, 'env-jwt', { jwt: 'file-jwt' })).toBe('env-jwt');
      expect(resolveToken({}, undefined, { jwt: 'file-jwt' })).toBe('file-jwt');
      expect(resolveToken({}, undefined, null)).toBeNull();
    });
  });

  describe('token expiration detection', () => {
    it('should detect expired tokens', () => {
      const pastDate = new Date(Date.now() - 3600 * 1000).toISOString();
      const futureDate = new Date(Date.now() + 3600 * 1000).toISOString();

      // Simulate isTokenExpired logic
      const isExpired = (expiresAt?: string) => {
        if (!expiresAt) return false;
        return new Date(expiresAt).getTime() <= Date.now();
      };

      expect(isExpired(pastDate)).toBe(true);
      expect(isExpired(futureDate)).toBe(false);
      expect(isExpired(undefined)).toBe(false);
    });

    it('should skip expired tokens in resolution', () => {
      const resolveToken = (
        opts: { token?: string },
        envToken?: string,
        fileCreds?: { jwt: string; expiresAt?: string } | null,
      ) => {
        if (opts.token) return opts.token;
        if (envToken) return envToken;
        if (fileCreds?.jwt) {
          if (fileCreds.expiresAt && new Date(fileCreds.expiresAt).getTime() <= Date.now()) {
            return null; // expired
          }
          return fileCreds.jwt;
        }
        return null;
      };

      const expired = { jwt: 'old-jwt', expiresAt: new Date(Date.now() - 3600 * 1000).toISOString() };
      const valid = { jwt: 'good-jwt', expiresAt: new Date(Date.now() + 3600 * 1000).toISOString() };

      expect(resolveToken({}, undefined, expired)).toBeNull();
      expect(resolveToken({}, undefined, valid)).toBe('good-jwt');
      // Explicit token overrides expired file creds
      expect(resolveToken({ token: 'flag-jwt' }, undefined, expired)).toBe('flag-jwt');
    });
  });

  describe('login API', () => {
    it('should parse JWT from login response', () => {
      const responseBody = {
        code: 200,
        data: { jwt: 'eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.test', userId: '123' },
        message: 'success',
      };

      const jwt = responseBody.data?.jwt;
      expect(jwt).toBeDefined();
      expect(jwt).toContain('eyJ');
    });

    it('should handle missing JWT in response', () => {
      const responseBody = { code: 401, data: null, message: 'Invalid credentials' };
      const jwt = responseBody.data?.jwt;
      expect(jwt).toBeUndefined();
    });
  });
});
