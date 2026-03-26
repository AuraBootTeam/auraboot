/**
 * HTTP Client - Unified Export
 *
 * Clean, layered HTTP client for AuraBoot web-admin.
 * Supports SSR/CSR environments and BFF architecture.
 *
 * @example
 * // Simple usage
 * import { fetchResult } from '~/services/http-client';
 * const result = await fetchResult<User>('/api/user/current');
 *
 * @example
 * // Convenience methods
 * import { get, post } from '~/services/http-client';
 * const users = await get<User[]>('/api/users');
 * const newUser = await post<User>('/api/user', { name: 'John' });
 *
 * @example
 * // SSR mode (React Router loader)
 * import { fetchResult } from '~/services/http-client';
 * export async function loader({ request }: LoaderFunctionArgs) {
 *   return await fetchResult<User>('/api/user/current', {}, request);
 * }
 */

// ============================================================================
// Main API - Unified Facade
// ============================================================================

export { fetchResult, get, post, put, del, patch } from './HttpClient';

// ============================================================================
// Types
// ============================================================================

export type { RequestContext, FetchOptions, Result, ApiConfig, ErrorCode } from './types';

export { ErrorCodes } from './types';

// ============================================================================
// Advanced API - Direct Layer Access (for advanced use cases)
// ============================================================================

// Request Context Layer
export { createServerContext, createBrowserContext, createRequestContext } from './RequestContext';

// Authentication Strategy Layer
export { resolveAuthToken, isPublicApiRoute } from './AuthStrategy';

// URL Building Layer
export { buildRequest, resolveBaseUrl, replacePathVariables, buildQueryString } from './URLBuilder';

// HTTP Execution Layer
export { executeFetch } from './FetchExecutor';
