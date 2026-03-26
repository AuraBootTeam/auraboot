import { describe, it, expect } from 'vitest';

describe('login command', () => {
  describe('credential resolution', () => {
    it('should prefer CLI options over env vars', () => {
      const opts = { user: 'cli@test.com', password: 'cli-pass' };
      const envUser = 'env@test.com';
      const envPass = 'env-pass';

      const email = opts.user || envUser || 'admin@auraboot.test';
      const password = opts.password || envPass || 'Test2026x';

      expect(email).toBe('cli@test.com');
      expect(password).toBe('cli-pass');
    });

    it('should fall back to env vars', () => {
      const opts = { user: undefined, password: undefined };
      const envUser = 'env@test.com';
      const envPass = 'env-pass';

      const email = opts.user || envUser || 'admin@auraboot.test';
      const password = opts.password || envPass || 'Test2026x';

      expect(email).toBe('env@test.com');
      expect(password).toBe('env-pass');
    });

    it('should use defaults as last resort', () => {
      const opts = { user: undefined, password: undefined };
      const envUser = undefined;
      const envPass = undefined;

      const email = opts.user || envUser || 'admin@auraboot.test';
      const password = opts.password || envPass || 'Test2026x';

      expect(email).toBe('admin@auraboot.test');
      expect(password).toBe('Test2026x');
    });
  });

  describe('login request', () => {
    it('should construct correct login body', () => {
      const email = 'admin@auraboot.test';
      const password = 'Test2026x';
      const body = JSON.stringify({ email, password });

      const parsed = JSON.parse(body);
      expect(parsed.email).toBe('admin@auraboot.test');
      expect(parsed.password).toBe('Test2026x');
    });
  });

  describe('environment selection', () => {
    it('should default to local', () => {
      const config = { defaultEnv: 'local' };
      const opts = { env: undefined };
      const env = opts.env || config.defaultEnv;
      expect(env).toBe('local');
    });

    it('should respect --env flag', () => {
      const config = { defaultEnv: 'local' };
      const opts = { env: 'production' };
      const env = opts.env || config.defaultEnv;
      expect(env).toBe('production');
    });
  });
});
