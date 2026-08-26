/**
 * Deadline for SSR loader fetches.
 *
 * The root loader runs on every navigation and on the auth revalidation
 * triggered by window focus / visibility change / the 60s interval. Several of
 * its fetches (branding, i18n, bootstrap status, access policy) use raw fetch
 * with no timeout, so a BFF or backend that accepts connections but stops
 * responding leaves the page frozen indefinitely. These calls are all
 * server-to-server on a private URL, so 10s is a generous bound.
 */
export const SSR_LOADER_FETCH_TIMEOUT_MS = 10_000;

/**
 * Returns an AbortSignal that fires after {@link SSR_LOADER_FETCH_TIMEOUT_MS},
 * or undefined where `AbortSignal.timeout` is unavailable so the fetch falls
 * back to its previous behavior.
 */
export function fetchTimeoutSignal(
  timeoutMs: number = SSR_LOADER_FETCH_TIMEOUT_MS,
): AbortSignal | undefined {
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
    // @ts-ignore - AbortSignal.timeout is not present in all TS DOM lib versions
    return AbortSignal.timeout(timeoutMs);
  }
  return undefined;
}
