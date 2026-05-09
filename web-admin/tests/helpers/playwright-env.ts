/**
 * @deprecated Re-export shim. Import from `helpers/environments` instead.
 *
 * Kept so existing import sites keep building while migration lands.
 * Phase 1.6 of the env-scripts-testing v3 plan introduced
 * `environments.ts` as the single source of truth.
 */
export { BACKEND_URL, BASE_URL, BFF_URL } from './environments';
