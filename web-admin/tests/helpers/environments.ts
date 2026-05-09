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
 * BFF_PORT / PG_PORT manually); to be explicit about host mode, source
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

/** Direct backend HTTP base — for specs that bypass vite/BFF and call the API directly. */
export const BACKEND_URL =
  process.env.BACKEND_URL ?? `http://localhost:${BE_PORT}`;

/** Vite-served base — what `page.goto` and `page.request` use by default. */
export const BASE_URL =
  process.env.PLAYWRIGHT_BASE_URL ?? `http://localhost:${VITE_PORT}`;

/** Remix BFF base — for specs that need to drive `/login` or BFF-specific endpoints directly. */
export const BFF_URL =
  process.env.BFF_URL ?? `http://localhost:${BFF_PORT}`;

const PG_HOST = process.env.PG_HOST ?? 'localhost';
const PG_PORT = process.env.PG_PORT ?? '5432';
const PG_USER = process.env.PG_USER ?? process.env.USER ?? 'ghj';
const PG_DB = process.env.PG_DB ?? 'aura_boot';

/**
 * `psql -h <host> -p <port> -U <user> -d <db>` prefix. Append your own
 * flags (-tA, -P pager=off, -c, -f, etc.) at the call site.
 */
export const PSQL_BASE = `psql -h ${PG_HOST} -p ${PG_PORT} -U ${PG_USER} -d ${PG_DB}`;
