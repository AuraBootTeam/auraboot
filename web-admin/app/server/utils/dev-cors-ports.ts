/**
 * Dev-mode CORS port allowlist for the BFF.
 *
 * Defaults cover the canonical local stack:
 *   3000 / 3500 — alternate BFF / proxy ports
 *   5173 / 5174 — Vite dev server (default + auto-bumped)
 *   6443       — Spring Boot dev port
 *
 * Parallel git worktrees commonly need additional non-default ports
 * (e.g. 5175 / 6445) when the canonical ports are already in use by
 * another dev session. Setting `BFF_ALLOWED_PORTS=5175,6445` adds those
 * to the allowlist *on top of* the defaults so the canonical stack
 * keeps working without further config.
 */

export const DEFAULT_DEV_CORS_PORTS = ['3000', '3500', '5173', '5174', '6443'] as const;

/**
 * Parse the BFF_ALLOWED_PORTS env var into a Set, appended to the canonical
 * defaults. Whitespace is trimmed, non-numeric entries are dropped silently.
 *
 * @param envValue - Raw value of BFF_ALLOWED_PORTS (or undefined)
 * @returns A Set containing default ports plus any extra ports parsed from the env.
 */
export function parseDevAllowedPorts(envValue: string | undefined): Set<string> {
  const merged = new Set<string>(DEFAULT_DEV_CORS_PORTS);
  if (!envValue) {
    return merged;
  }
  envValue
    .split(',')
    .map((p) => p.trim())
    .filter((p) => /^\d+$/.test(p))
    .forEach((p) => merged.add(p));
  return merged;
}
