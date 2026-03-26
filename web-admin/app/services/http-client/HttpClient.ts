/**
 * Unified Facade Layer
 *
 * Provides simple API that coordinates all layers:
 * - Request Context (SSR/CSR detection)
 * - Authentication Strategy (token resolution)
 * - URL Building (PathVariable, query params, base URL)
 * - HTTP Execution (fetch and error handling)
 */

import { createRequestContext } from './RequestContext';
import { resolveAuthToken } from './AuthStrategy';
import { buildRequest } from './URLBuilder';
import { executeFetch } from './FetchExecutor';
import type { Result, FetchOptions } from './types';

/**
 * Unified HTTP request function
 *
 * Auto-detects SSR/CSR environment and coordinates all layers.
 * This is the main entry point for making HTTP requests.
 *
 * Layer coordination:
 * 1. Create RequestContext (SSR or CSR)
 * 2. Resolve authentication token
 * 3. Build complete request (URL + init)
 * 4. Execute fetch and handle errors
 *
 * @param path API path (e.g., /api/user/{userId})
 * @param options Request options (method, params, token, etc.)
 * @param request Optional React Router Request (for SSR)
 * @returns Promise<Result<T>> - Standardized response
 *
 * @example
 * // SSR mode (in React Router loader)
 * export async function loader({ request }: LoaderFunctionArgs) {
 *   const result = await fetchResult<User>('/api/user/current', {}, request);
 *   return result;
 * }
 *
 * @example
 * // CSR mode (in React component)
 * const result = await fetchResult<User>('/api/user/current');
 *
 * @example
 * // With PathVariables and query params
 * const result = await fetchResult<User>('/api/user/{userId}', {
 *   params: { userId: 123, includeProfile: true }
 * });
 * // Request: GET /api/user/123?includeProfile=true
 *
 * @example
 * // POST with body
 * const result = await fetchResult<User>('/api/user', {
 *   method: 'post',
 *   params: { name: 'John', email: 'john@example.com' }
 * });
 */
export async function fetchResult<T>(
  path: string,
  options: FetchOptions = {},
  request?: Request,
): Promise<Result<T>> {
  // Step 1: Create request context (auto-detect SSR/CSR)
  const context = await createRequestContext(request);

  // Step 2: Resolve authentication token
  const token = await resolveAuthToken(path, context, options);

  // Step 3: Build complete request
  const { url, init } = buildRequest(path, options, context, token);

  // Step 4: Execute fetch and handle errors
  const result = await executeFetch<T>(url, init);

  return result;
}

/**
 * GET request convenience method
 *
 * @param path API path
 * @param params Query parameters and PathVariables
 * @param options Additional options (token, timeout, etc.)
 * @param request Optional React Router Request (for SSR)
 * @returns Promise<Result<T>>
 *
 * @example
 * // Simple GET
 * const result = await get<User[]>('/api/users');
 *
 * @example
 * // GET with query params
 * const result = await get<User[]>('/api/users', { role: 'admin', active: true });
 *
 * @example
 * // GET with PathVariable
 * const result = await get<User>('/api/user/{userId}', { userId: 123 });
 */
export async function get<T>(
  path: string,
  params?: Record<string, any>,
  options?: Omit<FetchOptions, 'method' | 'params'>,
  request?: Request,
): Promise<Result<T>> {
  return fetchResult<T>(
    path,
    {
      ...options,
      method: 'get',
      params,
    },
    request,
  );
}

/**
 * POST request convenience method
 *
 * @param path API path
 * @param params Request body and PathVariables
 * @param options Additional options (token, timeout, etc.)
 * @param request Optional React Router Request (for SSR)
 * @returns Promise<Result<T>>
 *
 * @example
 * // POST with body
 * const result = await post<User>('/api/user', {
 *   name: 'John',
 *   email: 'john@example.com'
 * });
 *
 * @example
 * // POST with PathVariable
 * const result = await post<Comment>('/api/post/{postId}/comment', {
 *   postId: 456,
 *   content: 'Great post!'
 * });
 */
export async function post<T>(
  path: string,
  params?: Record<string, any>,
  options?: Omit<FetchOptions, 'method' | 'params'>,
  request?: Request,
): Promise<Result<T>> {
  return fetchResult<T>(
    path,
    {
      ...options,
      method: 'post',
      params,
    },
    request,
  );
}

/**
 * PUT request convenience method
 *
 * @param path API path
 * @param params Request body and PathVariables
 * @param options Additional options (token, timeout, etc.)
 * @param request Optional React Router Request (for SSR)
 * @returns Promise<Result<T>>
 *
 * @example
 * // PUT to update resource
 * const result = await put<User>('/api/user/{userId}', {
 *   userId: 123,
 *   name: 'John Updated',
 *   email: 'john.updated@example.com'
 * });
 */
export async function put<T>(
  path: string,
  params?: Record<string, any>,
  options?: Omit<FetchOptions, 'method' | 'params'>,
  request?: Request,
): Promise<Result<T>> {
  return fetchResult<T>(
    path,
    {
      ...options,
      method: 'put',
      params,
    },
    request,
  );
}

/**
 * DELETE request convenience method
 *
 * Note: For DELETE requests, remaining params (after PathVariable replacement)
 * are sent as JSON body, NOT as query string parameters. This differs from
 * typical REST conventions where DELETE params are query parameters.
 *
 * @param path API path
 * @param params PathVariables and request body
 * @param options Additional options (token, timeout, etc.)
 * @param request Optional React Router Request (for SSR)
 * @returns Promise<Result<T>>
 *
 * @example
 * // DELETE resource
 * const result = await del<void>('/api/user/{userId}', { userId: 123 });
 *
 * @example
 * // DELETE with body params (NOT query params)
 * const result = await del<void>('/api/user/{userId}', {
 *   userId: 123,
 *   reason: 'inactive'
 * });
 * // Sends: DELETE /api/user/123 with body {"reason":"inactive"}
 */
export async function del<T>(
  path: string,
  params?: Record<string, any>,
  options?: Omit<FetchOptions, 'method' | 'params'>,
  request?: Request,
): Promise<Result<T>> {
  return fetchResult<T>(
    path,
    {
      ...options,
      method: 'delete',
      params,
    },
    request,
  );
}

/**
 * PATCH request convenience method
 *
 * @param path API path
 * @param params Request body and PathVariables
 * @param options Additional options (token, timeout, etc.)
 * @param request Optional React Router Request (for SSR)
 * @returns Promise<Result<T>>
 *
 * @example
 * // PATCH to partially update resource
 * const result = await patch<User>('/api/user/{userId}', {
 *   userId: 123,
 *   name: 'John Updated',
 * });
 */
export async function patch<T>(
  path: string,
  params?: Record<string, any>,
  options?: Omit<FetchOptions, 'method' | 'params'>,
  request?: Request,
): Promise<Result<T>> {
  return fetchResult<T>(
    path,
    {
      ...options,
      method: 'patch',
      params,
    },
    request,
  );
}
