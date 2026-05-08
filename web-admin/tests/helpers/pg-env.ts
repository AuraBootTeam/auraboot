/**
 * Shared psql connection scaffolding for test specs / seed scripts that
 * shell out to `psql`. Same env-override pattern as
 * scripts/oss-reset-and-init.sh:
 *
 *   PG_HOST   default localhost
 *   PG_PORT   default 5432
 *   PG_USER   default $USER (or 'ghj' as ultimate fallback)
 *   PG_DB     default aura_boot
 *   PGPASSWORD optional — set when isolated stack uses md5 auth
 *
 * Defaults preserve host-mode behaviour. To target an isolated docker
 * stack:
 *
 *   PG_HOST=localhost PG_PORT=5467 PG_USER=auraboot \
 *   PGPASSWORD=auraboot_dev npx playwright test ...
 */

const PG_HOST = process.env.PG_HOST ?? 'localhost';
const PG_PORT = process.env.PG_PORT ?? '5432';
const PG_USER = process.env.PG_USER ?? process.env.USER ?? 'ghj';
const PG_DB = process.env.PG_DB ?? 'aura_boot';

/**
 * Returns the psql command prefix `psql -h <host> -p <port> -U <user> -d <db>`.
 * Append your own flags (-tA, -P pager=off, -c, -f, etc.) at the call site.
 */
export const PSQL_BASE = `psql -h ${PG_HOST} -p ${PG_PORT} -U ${PG_USER} -d ${PG_DB}`;
