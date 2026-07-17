import { afterEach, describe, expect, it, vi } from 'vitest';

const ENV_KEYS = [
  'PG_HOST',
  'PG_PORT',
  'PG_USER',
  'PG_DB',
  'PGHOST',
  'PGPORT',
  'PGUSER',
  'PGDATABASE',
  'USER',
  'PLAYWRIGHT_BASE_URL',
  'BACKEND_URL',
  'BE_PORT',
  'BFF_URL',
  'BFF_PORT',
  'PW_SKIP_WEBSERVER',
] as const;

const originalEnv = new Map<string, string | undefined>(
  ENV_KEYS.map((key) => [key, process.env[key]]),
);

function resetEnv() {
  for (const key of ENV_KEYS) {
    const original = originalEnv.get(key);
    if (original === undefined) {
      delete process.env[key];
    } else {
      process.env[key] = original;
    }
  }
}

async function loadEnvironments() {
  vi.resetModules();
  return import('../../tests/helpers/environments');
}

describe('Playwright test environment helpers', () => {
  afterEach(() => {
    resetEnv();
    vi.resetModules();
  });

  it('uses libpq PG* aliases when Aura PG_* aliases are absent', async () => {
    resetEnv();
    delete process.env.PG_HOST;
    delete process.env.PG_PORT;
    delete process.env.PG_USER;
    delete process.env.PG_DB;
    delete process.env.USER;
    process.env.PGHOST = '127.0.0.1';
    process.env.PGPORT = '5516';
    process.env.PGUSER = 'auraboot';
    process.env.PGDATABASE = 'aura_boot_e2e';

    const env = await loadEnvironments();

    expect(env.PSQL_BASE).toBe(
      'psql -h 127.0.0.1 -p 5516 -U auraboot -d aura_boot_e2e',
    );
    expect(env.loadEnv('r2').pg).toEqual({
      host: '127.0.0.1',
      port: '5516',
      user: 'auraboot',
      db: 'aura_boot_e2e',
    });
  });

  it('lets Aura PG_* aliases override libpq PG* aliases', async () => {
    resetEnv();
    process.env.PG_HOST = 'pg-aura';
    process.env.PG_PORT = '6543';
    process.env.PG_USER = 'aura_user';
    process.env.PG_DB = 'aura_db';
    process.env.PGHOST = 'pg-libpq';
    process.env.PGPORT = '5432';
    process.env.PGUSER = 'libpq_user';
    process.env.PGDATABASE = 'libpq_db';

    const env = await loadEnvironments();

    expect(env.PSQL_BASE).toBe('psql -h pg-aura -p 6543 -U aura_user -d aura_db');
    expect(env.loadEnv('r2').pg).toEqual({
      host: 'pg-aura',
      port: '6543',
      user: 'aura_user',
      db: 'aura_db',
    });
  });

  it('fails fast when a targeted Docker run only sets PLAYWRIGHT_BASE_URL', async () => {
    resetEnv();
    process.env.PLAYWRIGHT_BASE_URL = 'http://localhost:5226';
    process.env.PW_SKIP_WEBSERVER = '1';

    await expect(loadEnvironments()).rejects.toThrow(
      /requires BACKEND_URL, BE_PORT, BFF_PORT, PG_DB/,
    );
  });

  it('fails fast when a targeted runtime omits PW_SKIP_WEBSERVER', async () => {
    resetEnv();
    process.env.PLAYWRIGHT_BASE_URL = 'http://localhost:5226';
    process.env.BACKEND_URL = 'http://localhost:6496';
    process.env.BE_PORT = '6496';
    process.env.BFF_PORT = '3553';
    process.env.PG_DB = 'auraboot_96';

    await expect(loadEnvironments()).rejects.toThrow(/requires PW_SKIP_WEBSERVER=1/);
  });

  it('accepts the targeted Docker env contract used by BPM and OSS slices', async () => {
    resetEnv();
    process.env.PLAYWRIGHT_BASE_URL = 'http://localhost:5226';
    process.env.BACKEND_URL = 'http://localhost:6496';
    process.env.BE_PORT = '6496';
    process.env.BFF_PORT = '3553';
    process.env.PG_DB = 'auraboot_96';
    process.env.PW_SKIP_WEBSERVER = '1';

    const env = await loadEnvironments();

    expect(env.BASE_URL).toBe('http://localhost:5226');
    expect(env.BACKEND_URL).toBe('http://localhost:6496');
    expect(env.BFF_URL).toBe('http://localhost:3553');
    expect(env.PG_CONN.database).toBe('auraboot_96');
    expect(env.loadEnv('r2').ports).toMatchObject({
      be: '6496',
      bff: '3553',
    });
  });

  it('rejects mismatched local backend URL and BE_PORT in targeted Docker mode', async () => {
    resetEnv();
    process.env.PLAYWRIGHT_BASE_URL = 'http://localhost:5226';
    process.env.BACKEND_URL = 'http://localhost:6443';
    process.env.BE_PORT = '6496';
    process.env.BFF_PORT = '3553';
    process.env.PG_DB = 'auraboot_96';
    process.env.PW_SKIP_WEBSERVER = '1';

    await expect(loadEnvironments()).rejects.toThrow(
      /BACKEND_URL port 6443 does not match BE_PORT=6496/,
    );
  });
});
