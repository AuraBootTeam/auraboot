/**
 * Centralised resolved URLs for Playwright tests + helpers.
 *
 * Defaults preserve host-mode (BE 6443 / vite 5173 / BFF 3500) so any
 * caller that uses these constants behaves identically when run against
 * the host stack with no env vars set. To target a per-worktree
 * isolated docker stack, set:
 *
 *   BE_PORT          backend host port (start-isolated.sh allocates)
 *   VITE_PORT        vite host port
 *   BFF_PORT         remix BFF host port
 *   PLAYWRIGHT_BASE_URL  optional explicit override (otherwise derived
 *                        from VITE_PORT)
 *   BACKEND_URL          optional explicit override (otherwise derived
 *                        from BE_PORT)
 *   BFF_URL              optional explicit override (otherwise derived
 *                        from BFF_PORT)
 *
 * Why this exists: every time a spec hard-codes `'http://localhost:6443'`
 * the test silently dials the host backend instead of the isolated
 * backend, the JWT signed by one is rejected by the other (different
 * HS384 key), and the test fails with a uniform 401. Today's
 * fix/oss-suite-r2 work collected several of those drift sites. This
 * module makes the right thing the path of least resistance for new
 * specs.
 *
 * Companion: `tests/helpers/pg-env.ts` for psql connection strings.
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
