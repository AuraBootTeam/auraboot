/**
 * Centralised env contract for Playwright tests, helpers, and seed scripts.
 *
 * Single source of truth for:
 *   - Resolved HTTP base URLs (vite, backend, BFF) — `BASE_URL`,
 *     `BACKEND_URL`, `BFF_URL`
 *   - psql command prefix — `PSQL_BASE`
 *
 * Defaults preserve host-mode (BE 6443 / vite 5173 / BFF 3500 / pg 5432)
 * so any caller behaves identically against the host stack with no env
 * vars set. To target a per-worktree isolated docker stack, source
 * `scripts/dev/r2-env-export.sh <slug>` (or set BE_PORT / VITE_PORT /
 * BFF_PORT / PG_PORT, or libpq PGPORT, manually); to be explicit about host mode, source
 * `scripts/dev/host-env-export.sh`.
 *
 * Why this exists: every time a spec hard-codes `'http://localhost:6443'`
 * or `'psql -h localhost -p 5432 ...'` the test silently dials the host
 * stack instead of the isolated stack, producing uniform 401s (JWT key
 * mismatch) or false-positives (cross-DB writes). The drift gate
 * (`pnpm test:env-lint`) blocks new hits; this module gives every spec
 * a frictionless path to the right thing.
 *
 * Companion shell exports: `scripts/dev/{host,r2}-env-export.sh`.
 *
 * Backward compatibility: `playwright-env.ts` and `pg-env.ts` re-export
 * from this file. New code should import from `helpers/environments`.
 */

const BE_PORT = process.env.BE_PORT ?? '6443';
const VITE_PORT = process.env.VITE_PORT ?? '5173';
const BFF_PORT = process.env.BFF_PORT ?? '3500';

function envValue(primary: string, alias: string, fallback: string): string {
  return process.env[primary] ?? process.env[alias] ?? fallback;
}

/** Direct backend HTTP base — for specs that bypass vite/BFF and call the API directly. */
export const BACKEND_URL =
  process.env.BACKEND_URL ?? `http://localhost:${BE_PORT}`;

/** Vite-served base — what `page.goto` and `page.request` use by default. */
export const BASE_URL =
  process.env.PLAYWRIGHT_BASE_URL ?? `http://localhost:${VITE_PORT}`;

/** Remix BFF base — for specs that need to drive `/login` or BFF-specific endpoints directly. */
export const BFF_URL =
  process.env.BFF_URL ?? `http://localhost:${BFF_PORT}`;

const PG_HOST = envValue('PG_HOST', 'PGHOST', 'localhost');
const PG_PORT = envValue('PG_PORT', 'PGPORT', '5432');
const PG_USER = process.env.PG_USER ?? process.env.PGUSER ?? process.env.USER ?? 'ghj';
const PG_DB = envValue('PG_DB', 'PGDATABASE', 'aura_boot');

/**
 * `psql -h <host> -p <port> -U <user> -d <db>` prefix. Append your own
 * flags (-tA, -P pager=off, -c, -f, etc.) at the call site.
 */
export const PSQL_BASE = `psql -h ${PG_HOST} -p ${PG_PORT} -U ${PG_USER} -d ${PG_DB}`;

// ─────────────────────────────────────────────────────────────────────────────
// First-class Environment profiles (Phase 3 — env-scripts-testing v3)
// ─────────────────────────────────────────────────────────────────────────────
//
// `loadEnv(profile)` returns a typed `EnvironmentSpec` describing the ports,
// URLs, and Postgres coordinates for one of the five canonical profiles.
//
//   host        — developer host stack (BE 6443 / vite 5173 / BFF 3500 / pg 5432)
//   r2          — per-worktree isolated docker stack (ports from
//                 `.aura-stack/<slug>.env` via env vars exported by
//                 `scripts/dev/r2-env-export.sh`)
//   ga-e2e      — GitHub Actions E2E docker stack
//                 (`docker-compose.ga-e2e.override.yml`); ports identical to
//                 host, but PG_HOST=postgres / BACKEND_URL etc. resolved from
//                 process.env so the in-cluster service names route correctly
//   ci          — CI runner (defaults to host ports; values come from
//                 process.env so the workflow can override per-job)
//   enterprise  — enterprise overlay stack (BE 6444 / vite 5174 / BFF 3501 /
//                 pg 5433); used by `auraboot-enterprise/web-admin-ext` runs
//
// The returned spec ALWAYS reflects `process.env` first; the profile only
// supplies defaults. This keeps backward-compat with the existing
// `BACKEND_URL` / `BASE_URL` / `BFF_URL` / `PSQL_BASE` exports above and lets
// callers stay drift-free regardless of who launched the shell.

export type EnvProfile = 'host' | 'r2' | 'ga-e2e' | 'ci' | 'enterprise';

export interface EnvironmentSpec {
  /** Profile name as supplied. */
  name: EnvProfile;
  ports: {
    be: string;
    vite: string;
    bff: string;
    pg: string;
    redis: string;
  };
  urls: {
    backend: string;
    base: string;
    bff: string;
  };
  pg: {
    host: string;
    port: string;
    user: string;
    db: string;
  };
}

interface ProfileDefaults {
  bePort: string;
  vitePort: string;
  bffPort: string;
  pgPort: string;
  redisPort: string;
  pgHost: string;
  pgUser: string;
  pgDb: string;
}

const PROFILE_DEFAULTS: Record<EnvProfile, ProfileDefaults> = {
  host: {
    bePort: '6443',
    vitePort: '5173',
    bffPort: '3500',
    pgPort: '5432',
    redisPort: '6379',
    pgHost: 'localhost',
    pgUser: 'auraboot',
    pgDb: 'aura_boot',
  },
  r2: {
    // r2 ports come from .aura-stack/<slug>.env via scripts/dev/r2-env-export.sh.
    // We only fall back to host defaults if the env was not sourced — in that
    // case the resulting spec will silently target host, which is the existing
    // documented behaviour (and the env-drift gate catches regressions).
    bePort: '6443',
    vitePort: '5173',
    bffPort: '3500',
    pgPort: '5432',
    redisPort: '6379',
    pgHost: 'localhost',
    pgUser: 'auraboot',
    pgDb: 'aura_boot',
  },
  'ga-e2e': {
    bePort: '6443',
    vitePort: '5173',
    bffPort: '3500',
    pgPort: '5432',
    redisPort: '6379',
    // Inside the GA docker network, the postgres service is reachable as
    // `postgres`. CI workflows may override with PG_HOST=localhost when using
    // service ports.
    pgHost: 'postgres',
    pgUser: 'auraboot',
    pgDb: 'aura_boot',
  },
  ci: {
    bePort: '6443',
    vitePort: '5173',
    bffPort: '3500',
    pgPort: '5432',
    redisPort: '6379',
    pgHost: 'localhost',
    pgUser: 'auraboot',
    pgDb: 'aura_boot',
  },
  enterprise: {
    bePort: '6444',
    vitePort: '5174',
    bffPort: '3501',
    pgPort: '5433',
    redisPort: '6380',
    pgHost: 'localhost',
    pgUser: 'auraboot',
    pgDb: 'aura_boot',
  },
};

/**
 * Resolve a typed environment spec for the given profile. process.env wins
 * over profile defaults so a sourced `*-env-export.sh` script always takes
 * precedence.
 *
 * Defaults to 'host' when no profile is provided. The returned object is
 * read-only by convention; callers should not mutate it.
 */
export function loadEnv(profile: EnvProfile = 'host'): EnvironmentSpec {
  const defaults = PROFILE_DEFAULTS[profile];
  const bePort = process.env.BE_PORT ?? defaults.bePort;
  const vitePort = process.env.VITE_PORT ?? defaults.vitePort;
  const bffPort = process.env.BFF_PORT ?? defaults.bffPort;
  const pgPort = process.env.PG_PORT ?? process.env.PGPORT ?? defaults.pgPort;
  const redisPort = process.env.REDIS_PORT ?? defaults.redisPort;
  const pgHost = process.env.PG_HOST ?? process.env.PGHOST ?? defaults.pgHost;
  const pgUser = process.env.PG_USER ?? process.env.PGUSER ?? process.env.USER ?? defaults.pgUser;
  const pgDb = process.env.PG_DB ?? process.env.PGDATABASE ?? defaults.pgDb;
  return {
    name: profile,
    ports: { be: bePort, vite: vitePort, bff: bffPort, pg: pgPort, redis: redisPort },
    urls: {
      backend: process.env.BACKEND_URL ?? `http://localhost:${bePort}`,
      base: process.env.PLAYWRIGHT_BASE_URL ?? `http://localhost:${vitePort}`,
      bff: process.env.BFF_URL ?? `http://localhost:${bffPort}`,
    },
    pg: { host: pgHost, port: pgPort, user: pgUser, db: pgDb },
  };
}
