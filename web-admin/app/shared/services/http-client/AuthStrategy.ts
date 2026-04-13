/**
 * Authentication Strategy Layer
 *
 * Handles authentication token resolution with priority-based strategy.
 * Separates authentication logic from HTTP execution for better testability.
 */

import type { RequestContext, FetchOptions } from './types';

/**
 * Public API routes that don't require authentication
 *
 * These routes can be accessed without a JWT token.
 * Matches routes by exact path or prefix.
 */
const PUBLIC_API_ROUTES = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/signup',
  '/api/auth/forgot-password',
  '/api/auth/reset-password',
  '/api/public',
  '/api/i18n',
  '/api/health',
  '/health',
] as const;

/**
 * Resolve authentication token based on strategy
 *
 * Priority order:
 * 1. skipAutoToken → use explicit token only (or undefined)
 * 2. explicit token → use provided token
 * 3. public route → no token needed (return undefined)
 * 4. context token → use token from request context
 *
 * Token type normalization:
 * - Converts `null` to `undefined` for consistent handling
 * - Ensures return type is always `string | undefined`
 *
 * @param path API path to check
 * @param context Request context (SSR or CSR)
 * @param options Fetch options with token and skipAutoToken
 * @returns Resolved token or undefined
 *
 * @example
 * // Skip auto token - only use explicit token
 * const token = await resolveAuthToken('/api/user', context, {
 *   skipAutoToken: true,
 *   token: 'custom-token'
 * });
 * // Result: 'custom-token'
 *
 * @example
 * // Explicit token takes priority
 * const token = await resolveAuthToken('/api/user', context, {
 *   token: 'explicit-token'
 * });
 * // Result: 'explicit-token' (even if context has a token)
 *
 * @example
 * // Public route - no token needed
 * const token = await resolveAuthToken('/api/auth/login', context, {});
 * // Result: undefined
 *
 * @example
 * // Protected route - use context token
 * const token = await resolveAuthToken('/api/user/current', context, {});
 * // Result: context.token (from session or browser storage)
 */
export async function resolveAuthToken(
  path: string,
  context: RequestContext,
  options: FetchOptions,
): Promise<string | undefined> {
  const { token: explicitToken, skipAutoToken = false } = options;

  // Priority 1: skipAutoToken flag
  // When true, only use explicit token (no automatic resolution)
  if (skipAutoToken) {
    return normalizeToken(explicitToken);
  }

  // Priority 2: Explicit token provided
  // Use the explicitly provided token, overriding context token
  if (explicitToken !== undefined && explicitToken !== null) {
    return normalizeToken(explicitToken);
  }

  // Priority 3: Public route check
  // Public routes don't need authentication
  if (isPublicApiRoute(path)) {
    return undefined;
  }

  // Priority 4: Context token
  // For protected routes, use token from request context
  // (SSR: from session, CSR: from browser storage)
  return normalizeToken(context.token);
}

/**
 * Check if API path is a public route (no authentication required)
 *
 * Matches routes by:
 * - Exact path match: `/api/auth/login`
 * - Prefix match: `/api/auth/login/callback`
 *
 * @param path API path to check
 * @returns True if public route, false if protected
 *
 * @example
 * isPublicApiRoute('/api/auth/login')           // true
 * isPublicApiRoute('/api/auth/login/callback')  // true
 * isPublicApiRoute('/api/user/current')         // false
 * isPublicApiRoute('/api/i18n/zh-CN')           // true
 */
export function isPublicApiRoute(path: string): boolean {
  return PUBLIC_API_ROUTES.some((route) => path === route || path.startsWith(`${route}/`));
}

/**
 * Normalize token type from `string | null | undefined` to `string | undefined`
 *
 * This ensures consistent token handling throughout the HTTP client.
 * Converts null to undefined to match the expected type signature.
 *
 * @param token Token value (may be string, null, or undefined)
 * @returns Normalized token (string or undefined)
 *
 * @example
 * normalizeToken('valid-token')  // 'valid-token'
 * normalizeToken(null)           // undefined
 * normalizeToken(undefined)      // undefined
 * normalizeToken('')             // undefined (empty string treated as no token)
 */
function normalizeToken(token: string | null | undefined): string | undefined {
  // Treat null, undefined, and empty string as no token
  if (!token) {
    return undefined;
  }

  return token;
}
