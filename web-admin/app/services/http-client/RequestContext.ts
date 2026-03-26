/**
 * Request Context Layer
 *
 * Provides unified request context abstraction for SSR and CSR environments.
 * Handles token resolution from different sources (session cookies vs browser storage).
 */

import { JWT_TOKEN_KEY } from '~/constants/AuthConstant';
import type { RequestContext } from './types';

/**
 * Create request context for SSR environment
 *
 * Extracts authentication token from React Router session cookie.
 * This function runs on the server side (Node.js) during SSR.
 *
 * @param request React Router Request object from loader/action
 * @returns RequestContext with token from session
 *
 * @example
 * // In a React Router loader
 * export async function loader({ request }: LoaderFunctionArgs) {
 *   const context = await createServerContext(request);
 *   // Use context.token for authenticated requests
 * }
 */
export async function createServerContext(request: Request): Promise<RequestContext> {
  const token = await getTokenFromSession(request);

  return {
    isServer: true,
    token,
    request,
  };
}

/**
 * Create request context for CSR environment
 *
 * Reads authentication token from browser storage (sessionStorage or localStorage).
 * This function runs in the browser during client-side rendering.
 *
 * @returns RequestContext with token from browser storage
 *
 * @example
 * // In a React component
 * const context = createBrowserContext();
 * // Use context.token for authenticated requests
 */
export function createBrowserContext(): RequestContext {
  const token = getTokenFromBrowser();
  const timezone = getEffectiveTimezone();

  return {
    isServer: false,
    token,
    timezone,
  };
}

/**
 * Auto-detect environment and create appropriate context
 *
 * Determines whether code is running in SSR or CSR environment
 * and creates the corresponding context.
 *
 * Decision logic:
 * - If Request object is provided → SSR mode (use session)
 * - If no Request object → CSR mode (use browser storage)
 *
 * @param request Optional React Router Request object
 * @returns RequestContext for current environment
 *
 * @example
 * // Automatic detection
 * const context = await createRequestContext(request); // SSR if request provided
 * const context = await createRequestContext();        // CSR if no request
 */
export async function createRequestContext(request?: Request): Promise<RequestContext> {
  if (request) {
    // SSR mode: Request object provided
    return createServerContext(request);
  } else {
    // CSR mode: No request object, running in browser
    return createBrowserContext();
  }
}

/**
 * Get token from React Router session (SSR)
 *
 * Extracts JWT token from session cookie using React Router's
 * cookie session storage.
 *
 * @param request React Router Request object
 * @returns Token string or undefined if not found
 */
async function getTokenFromSession(request: Request): Promise<string | undefined> {
  try {
    const { sessionStorage } = await import('~/services/session');
    const cookie = request.headers.get('Cookie');
    const session = await sessionStorage.getSession(cookie);
    const token = session.get(JWT_TOKEN_KEY);
    // Normalize null to undefined
    return token || undefined;
  } catch (error) {
    return undefined;
  }
}

/**
 * Get effective timezone from localStorage.
 * Written by TimezoneContext after cascade resolution (user > tenant > browser).
 */
function getEffectiveTimezone(): string | undefined {
  if (typeof window === 'undefined') return undefined;
  try {
    return window.localStorage.getItem('effective-timezone') ?? undefined;
  } catch {
    return undefined;
  }
}

/**
 * Get token from browser storage (CSR)
 *
 * Reads JWT token from browser storage with fallback strategy:
 * 1. Try sessionStorage first (temporary, cleared on tab close)
 * 2. Fall back to localStorage (persistent across sessions)
 *
 * @returns Token string or undefined if not found
 */
function getTokenFromBrowser(): string | undefined {
  // Check if running in browser environment
  if (typeof window === 'undefined') {
    return undefined;
  }

  try {
    // Try sessionStorage first (preferred for security)
    const sessionToken = window.sessionStorage.getItem(JWT_TOKEN_KEY);
    if (sessionToken) {
      return sessionToken;
    }

    // Fall back to localStorage
    const localToken = window.localStorage.getItem(JWT_TOKEN_KEY);
    if (localToken) {
      return localToken;
    }

    return undefined;
  } catch (error) {
    // Storage access might be blocked (private browsing, etc.)
    return undefined;
  }
}
