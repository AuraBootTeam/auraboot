import { isRouteErrorResponse, type ClientLoaderFunctionArgs } from 'react-router';
import type { RootLoaderData } from '~/root-data';
import { reportClientError } from './clientErrorReporter';

import { ROOT_DEPENDENCY_UNAVAILABLE } from './rootReadErrors';
export { ROOT_DEPENDENCY_UNAVAILABLE } from './rootReadErrors';
type Scope = { url: string; snapshot: RootLoaderData; isCurrent: () => boolean };
let active: Scope | undefined;
let unavailable = false;
const listeners = new Set<() => void>();
const pageKey = (url: string) => {
  const value = new URL(url);
  return value.origin + value.pathname + value.search;
};

export function setBackgroundRefreshUnavailable(value: boolean): void {
  if (unavailable === value) return;
  unavailable = value;
  for (const listener of listeners) listener();
}
export const backgroundRefreshUnavailable = () => unavailable;
export const subscribeBackgroundRefresh = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

/** Scope retains only the data currently displayed, never an identity from an earlier page. */
export async function runBackgroundRootRefresh(
  scope: Scope,
  operation: () => Promise<void>,
): Promise<void> {
  if (active) return;
  active = scope;
  try {
    await operation();
  } finally {
    if (active === scope) active = undefined;
  }
}

export async function loadRootOnClient({
  request,
  serverLoader,
}: ClientLoaderFunctionArgs): Promise<RootLoaderData> {
  const scope = active;
  try {
    const result = await serverLoader<RootLoaderData>();
    if (!request.signal.aborted) setBackgroundRefreshUnavailable(false);
    return result as RootLoaderData;
  } catch (error) {
    const dependencyFailure =
      isRouteErrorResponse(error) &&
      error.status === 503 &&
      error.data?.code === ROOT_DEPENDENCY_UNAVAILABLE;
    const transportFailure =
      error instanceof TypeError &&
      /failed to fetch|fetch failed|networkerror|load failed|network request failed/i.test(
        error.message,
      );
    const sameDisplayedPage =
      scope &&
      typeof window !== 'undefined' &&
      pageKey(scope.url) === pageKey(request.url) &&
      pageKey(scope.url) === pageKey(window.location.href);
    if (
      !scope ||
      !sameDisplayedPage ||
      !scope.isCurrent() ||
      request.signal.aborted ||
      (!dependencyFailure && !transportFailure)
    )
      throw error;
    const firstFailure = !backgroundRefreshUnavailable();
    setBackgroundRefreshUnavailable(true);
    if (firstFailure)
      reportClientError({
        errorType: 'background-refresh',
        kind: 'network',
        traceId: dependencyFailure ? error.data.traceId : undefined,
        message: dependencyFailure
          ? `[requestId=${String(error.data.requestId)} stage=${String(error.data.stage)}] Background root dependency unavailable`
          : 'Background root request transport failed',
        pageUrl: window.location.href,
      });
    return scope.snapshot;
  }
}
