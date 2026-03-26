/**
 * URL Building Layer
 *
 * Handles URL construction with PathVariable replacement, query parameters,
 * and environment-specific base URL resolution.
 */

import type { RequestContext, FetchOptions, ApiConfig } from './types';

/**
 * Build complete request configuration
 *
 * Responsibilities:
 * - Replace PathVariables in path ({userId} → 123)
 * - Separate path params from query params
 * - Construct base URL (SSR vs CSR)
 * - Build query string for GET requests
 * - Prepare body for POST/PUT/PATCH/DELETE requests
 * - Add authentication headers
 *
 * Note on DELETE: Remaining params are sent as query string parameters (same as GET),
 * since many HTTP clients/proxies strip body from DELETE requests.
 *
 * @param path API path with optional PathVariables (e.g., /api/user/{userId})
 * @param options Fetch options with params, method, etc.
 * @param context Request context (SSR or CSR)
 * @param token Resolved authentication token
 * @returns Complete request configuration { url, init }
 *
 * @example
 * // PathVariable replacement + query params
 * buildRequest('/api/user/{userId}', {
 *   method: 'get',
 *   params: { userId: 123, role: 'admin' }
 * }, context, token);
 * // Result: { url: 'http://localhost:3500/api/user/123?role=admin', init: {...} }
 *
 * @example
 * // POST with body
 * buildRequest('/api/user', {
 *   method: 'post',
 *   params: { name: 'John', email: 'john@example.com' }
 * }, context, token);
 * // Result: { url: 'http://localhost:3500/api/user', init: { body: '{"name":"John",...}' } }
 */
export function buildRequest(
  path: string,
  options: FetchOptions,
  context: RequestContext,
  token?: string,
): {
  url: string;
  init: RequestInit;
} {
  const { method = 'get', params = {}, timeout, apiConfig } = options;

  // Step 1: Replace PathVariables and separate params
  const { processedPath, remainingParams } = replacePathVariables(path, params);

  // Step 2: Resolve base URL based on environment
  const baseUrl = resolveBaseUrl(context, apiConfig);

  // Step 3: Build query string for GET requests
  let fullUrl = baseUrl + processedPath;
  if (method === 'get' && Object.keys(remainingParams).length > 0) {
    const queryString = buildQueryString(remainingParams);
    if (queryString) {
      fullUrl += '?' + queryString;
    }
  }

  // Step 4: Build request init
  const init: RequestInit = {
    method,
    headers: {
      'Content-Type': 'application/json',
    },
    credentials: 'include', // Send cookies to BFF server
  };

  // Step 5: Add authentication header
  if (token) {
    init.headers = {
      ...init.headers,
      Authorization: `Bearer ${token}`,
    };
  }

  // Step 5.1: Add timezone header (CSR only, when timezone is resolved)
  if (context.timezone) {
    init.headers = {
      ...init.headers,
      'X-Timezone': context.timezone,
    };
  }

  // Step 6: Add request body for non-GET/DELETE requests; DELETE uses query params
  if (method === 'delete' && Object.keys(remainingParams).length > 0) {
    const deleteSearchParams = new URLSearchParams();
    for (const [key, val] of Object.entries(remainingParams)) {
      if (val !== undefined && val !== null) {
        deleteSearchParams.set(key, String(val));
      }
    }
    const separator = fullUrl.includes('?') ? '&' : '?';
    fullUrl = `${fullUrl}${separator}${deleteSearchParams.toString()}`;
  } else if (method !== 'get') {
    // Preserve array params as-is (spreading destroys array structure)
    if (Array.isArray(params)) {
      init.body = JSON.stringify(params);
    } else if (Object.keys(remainingParams).length > 0) {
      init.body = JSON.stringify(remainingParams);
    }
  }

  // Step 7: Add timeout signal (if supported)
  if (timeout && typeof AbortSignal !== 'undefined' && 'timeout' in AbortSignal) {
    // @ts-ignore - AbortSignal.timeout is not in all TypeScript versions
    init.signal = AbortSignal.timeout(timeout);
  }

  return { url: fullUrl, init };
}

/**
 * Resolve base URL based on runtime context
 *
 * Environment-specific URL resolution:
 * - SSR: Use BFF internal URL (http://localhost:3500)
 * - CSR: Use current origin (http://localhost:3000 in dev)
 *
 * Configuration priority:
 * 1. Explicit apiConfig.baseUrl
 * 2. Environment variable BFF_INTERNAL_URL (SSR only)
 * 3. Default: http://localhost:3500 (SSR) or window.location.origin (CSR)
 *
 * @param context Request context
 * @param apiConfig Optional API configuration override
 * @returns Base URL string
 *
 * @example
 * // SSR mode
 * resolveBaseUrl({ isServer: true, ... }, undefined)
 * // Result: 'http://localhost:3500'
 *
 * @example
 * // CSR mode
 * resolveBaseUrl({ isServer: false, ... }, undefined)
 * // Result: 'http://localhost:3000' (current origin)
 */
export function resolveBaseUrl(context: RequestContext, apiConfig?: Partial<ApiConfig>): string {
  // Priority 1: Explicit baseUrl in apiConfig
  if (apiConfig?.baseUrl !== undefined) {
    return apiConfig.baseUrl;
  }

  // SSR mode: Use BFF internal URL
  if (context.isServer) {
    // Priority 2: Environment variable
    const bffInternalUrl = getBffInternalUrl();
    if (bffInternalUrl) {
      return bffInternalUrl;
    }

    // Priority 3: Default BFF URL
    const bffPort = getBffPort();
    return `http://localhost:${bffPort}`;
  }

  // CSR mode: Use current origin for absolute URLs
  // This fixes "Failed to parse URL" errors in browser fetch()
  if (typeof window !== 'undefined') {
    return window.location.origin;
  }

  // Fallback (should never reach here in normal operation)
  return '';
}

/**
 * Replace PathVariable placeholders in path
 *
 * Replaces {variable} placeholders with actual values from params.
 * Separates path params from remaining params (for query string or body).
 *
 * PathVariable matching:
 * - Exact match: {userId} matches params.userId
 * - Case-sensitive
 * - URL-encodes values for safety
 *
 * @param path API path with placeholders
 * @param params Request parameters
 * @returns Processed path and remaining params
 *
 * @example
 * replacePathVariables('/api/user/{userId}/post/{postId}', {
 *   userId: 123,
 *   postId: 456,
 *   role: 'admin'
 * });
 * // Result: {
 * //   processedPath: '/api/user/123/post/456',
 * //   remainingParams: { role: 'admin' }
 * // }
 */
export function replacePathVariables(
  path: string,
  params: Record<string, any>,
): {
  processedPath: string;
  remainingParams: Record<string, any>;
} {
  let processedPath = path;
  const remainingParams: Record<string, any> = { ...params };

  // Find all PathVariable placeholders: {variableName}
  const pathVariableRegex = /\{([^}]+)\}/g;
  const matches = path.matchAll(pathVariableRegex);

  for (const match of matches) {
    const placeholder = match[0]; // {userId}
    const variableName = match[1]; // userId

    if (variableName in params) {
      const value = params[variableName];
      // URL-encode the value for safety
      const encodedValue = encodeURIComponent(String(value));
      processedPath = processedPath.replace(placeholder, encodedValue);

      // Remove from remaining params (it's been used in path)
      delete remainingParams[variableName];
    }
  }

  return { processedPath, remainingParams };
}

/**
 * Build query string from parameters
 *
 * Converts parameter object to URL query string.
 * Filters out undefined and null values.
 * Objects and arrays are JSON-serialized automatically.
 *
 * @param params Query parameters
 * @returns Query string (without leading ?)
 *
 * @example
 * buildQueryString({ name: 'John', age: 30, role: undefined })
 * // Result: 'name=John&age=30'
 *
 * @example
 * buildQueryString({ filters: [{ field: 'name', op: 'eq' }] })
 * // Result: 'filters=%5B%7B%22field%22%3A%22name%22%2C%22op%22%3A%22eq%22%7D%5D'
 */
export function buildQueryString(params: Record<string, any>): string {
  const searchParams = new URLSearchParams();

  for (const [key, value] of Object.entries(params)) {
    // Skip undefined and null values
    if (value !== undefined && value !== null) {
      if (typeof value === 'object') {
        searchParams.append(key, JSON.stringify(value));
      } else {
        searchParams.append(key, String(value));
      }
    }
  }

  return searchParams.toString();
}

/**
 * Get BFF internal URL from environment variables
 *
 * Reads BFF_INTERNAL_URL environment variable (SSR only).
 * This is used for server-to-server communication.
 *
 * @returns BFF internal URL or undefined
 */
function getBffInternalUrl(): string | undefined {
  // Only available in server environment
  if (typeof process === 'undefined' || !process.env) {
    return undefined;
  }

  return process.env.BFF_INTERNAL_URL;
}

/**
 * Get BFF port from environment variables
 *
 * Reads BFF_PORT environment variable with fallback to default.
 *
 * @returns BFF port number as string
 */
function getBffPort(): string {
  // Try environment variable first
  if (typeof process !== 'undefined' && process.env?.BFF_PORT) {
    return process.env.BFF_PORT;
  }

  // Try import.meta.env (Vite)
  if (typeof import.meta !== 'undefined' && import.meta.env?.BFF_PORT) {
    return import.meta.env.BFF_PORT;
  }

  // Default port
  return '3500';
}
