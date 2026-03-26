/**
 * HTTP Client Type Definitions
 *
 * Core types and interfaces for the refactored HTTP client.
 * These types support SSR/CSR environments, authentication strategies,
 * and the three-tier architecture (Browser → BFF → Spring Boot).
 */

/**
 * Unified request context for SSR and CSR environments
 *
 * This interface abstracts the differences between server-side (Node.js)
 * and client-side (browser) execution contexts.
 */
export interface RequestContext {
  /**
   * Whether running in server-side (Node.js) environment
   * - true: SSR mode (React Router loader)
   * - false: CSR mode (browser component)
   */
  isServer: boolean;

  /**
   * Authentication token (if available)
   * - SSR: extracted from session cookie via React Router Request
   * - CSR: read from browser storage (sessionStorage/localStorage)
   * - undefined: no token available (public route or unauthenticated)
   */
  token?: string;

  /**
   * React Router Request object (SSR only)
   * Available when called from a React Router loader function
   */
  request?: Request;

  /**
   * Effective timezone for query boundary calculations (CSR only).
   * Injected as X-Timezone request header via URLBuilder.
   * - Set from localStorage('effective-timezone') by createBrowserContext()
   * - Undefined in SSR mode (server has no user timezone context)
   */
  timezone?: string;
}

/**
 * Options for HTTP requests
 *
 * Provides fine-grained control over request behavior including
 * authentication, parameters, timeouts, and API configuration.
 */
export interface FetchOptions {
  /**
   * HTTP method
   * @default 'get'
   */
  method?: 'get' | 'post' | 'put' | 'delete' | 'patch' | 'options';

  /**
   * Request parameters
   *
   * Usage depends on HTTP method:
   * - PathVariables: {userId} in path → replaced with actual values
   * - Query params: for GET requests → appended as ?key=value
   * - Body params: for POST/PUT/PATCH → sent as JSON body
   *
   * @example
   * // PathVariable replacement
   * path: '/api/user/{userId}'
   * params: { userId: 123, role: 'admin' }
   * // Result: /api/user/123?role=admin (GET)
   * // Result: /api/user/123 with body {role: 'admin'} (POST)
   */
  params?: Record<string, any>;

  /**
   * Explicit authentication token
   *
   * When provided, overrides automatic token resolution.
   * Useful for:
   * - Testing with specific tokens
   * - Impersonation scenarios
   * - Token refresh flows
   *
   * @example
   * fetchResult('/api/user/current', { token: 'custom-jwt-token' })
   */
  token?: string | null;

  /**
   * Request timeout in milliseconds
   * @default 30000 (30 seconds)
   */
  timeout?: number;

  /**
   * API configuration overrides
   *
   * Allows per-request customization of API settings
   * without changing global configuration.
   */
  apiConfig?: Partial<ApiConfig>;

  /**
   * Skip automatic token addition
   *
   * When true, the system will NOT automatically add authentication token
   * from context. Only the explicitly provided token (if any) will be used.
   *
   * Use cases:
   * - Public API routes (login, signup, health checks)
   * - Anonymous requests
   * - Custom authentication schemes
   *
   * @default false
   */
  skipAutoToken?: boolean;
}

/**
 * Standardized API response wrapper
 *
 * All API responses are wrapped in this format for consistent
 * error handling and success detection across the application.
 *
 * @template T The type of the response data
 */
export interface Result<T> {
  /**
   * Response code
   *
   * - "0": Success
   * - Other codes: Error codes (HTTP status codes or custom error codes)
   *
   * @example
   * "0" - Success
   * "401" - Unauthorized
   * "404" - Not Found
   * "network_error" - Network connectivity issue
   * "timeout_error" - Request timeout
   * "json_parse_error" - Invalid JSON response
   */
  code: string;

  /**
   * Response description/message
   *
   * Human-readable message describing the result.
   * - Success: descriptive message or empty string
   * - Error: error description for debugging/display
   */
  desc: string;

  /**
   * Response message (alias of desc)
   *
   * Some legacy callers use `message` instead of `desc`.
   */
  message?: string;

  /**
   * Success flag (derived from code)
   *
   * Some callers expect `success` on the result object.
   */
  success?: boolean;

  /**
   * Response data (null on error)
   *
   * Contains the actual response payload on success.
   * Set to null when an error occurs.
   */
  data: T | null;
}

/**
 * API configuration
 *
 * Global and per-request configuration for API behavior.
 * Environment-specific settings are loaded from environment variables.
 */
export interface ApiConfig {
  /**
   * Request timeout in milliseconds
   *
   * Maximum time to wait for a response before aborting the request.
   * Can be overridden per-request via FetchOptions.
   *
   * @default 30000 (30 seconds)
   */
  timeout: number;

  /**
   * Base URL for API requests
   *
   * Environment-specific base URL:
   * - SSR: BFF internal URL (e.g., http://localhost:3500)
   * - CSR: empty string (relative URLs for Vite proxy)
   *
   * Typically auto-detected based on runtime context.
   * Can be overridden via environment variables:
   * - BFF_INTERNAL_URL (SSR)
   *
   * @default undefined (auto-detected)
   */
  baseUrl?: string;
}

/**
 * Error codes used throughout the HTTP client
 *
 * Standardized error codes for consistent error handling.
 */
export const ErrorCodes = {
  /** Successful response */
  SUCCESS: '0',

  /** Network connectivity error */
  NETWORK_ERROR: 'network_error',

  /** Request timeout */
  TIMEOUT_ERROR: 'timeout_error',

  /** JSON parsing error */
  JSON_PARSE_ERROR: 'json_parse_error',

  /** HTTP 401 Unauthorized */
  UNAUTHORIZED: '401',

  /** HTTP 403 Forbidden */
  FORBIDDEN: '403',

  /** HTTP 404 Not Found */
  NOT_FOUND: '404',

  /** HTTP 500 Internal Server Error */
  INTERNAL_SERVER_ERROR: '500',
} as const;

/**
 * Type for error code values
 */
export type ErrorCode = (typeof ErrorCodes)[keyof typeof ErrorCodes] | string;
