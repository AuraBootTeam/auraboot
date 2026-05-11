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
});
