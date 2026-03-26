/**
 * API Service
 *
 * Unified API service class with HTTP request wrapping, error handling,
 * and AbortController support for preventing race conditions.
 *
 * Uses the http-client layer underneath.
 */

import { get, post, put, del } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';

export interface ApiConfig {
  baseURL: string;
  timeout?: number;
  headers?: Record<string, string>;
  protocol?: 'http' | 'grpc';
}

export interface RequestOptions {
  method?: 'get' | 'post' | 'put' | 'delete' | 'patch';
  headers?: Record<string, string>;
  data?: unknown;
  params?: Record<string, unknown>;
  timeout?: number;
  /**
   * AbortSignal for cancelling in-flight requests.
   * When the signal is aborted, the request promise resolves with
   * a cancelled ApiResponse (success=false, code='abort_error')
   * instead of throwing.
   */
  signal?: AbortSignal;
}

export interface ApiResponse<T = unknown> {
  success: boolean;
  data: T;
  message?: string;
  code?: string;
  timestamp?: number;
}

/**
 * Result of createAbortableRequest().
 * Provides the request promise along with an abort() handle.
 */
export interface AbortableRequest<T = unknown> {
  /** The API response promise */
  promise: Promise<ApiResponse<T>>;
  /** Abort the in-flight request */
  abort: () => void;
  /** The underlying AbortController (for advanced use) */
  controller: AbortController;
}

/** Sentinel response returned when a request is aborted */
const ABORTED_RESPONSE: ApiResponse<never> = Object.freeze({
  success: false,
  data: null as never,
  message: 'Request aborted',
  code: 'abort_error',
  timestamp: 0,
});

/**
 * Check if an error is an AbortError (user-initiated cancellation).
 */
function isAbortError(error: unknown): boolean {
  if (error instanceof DOMException && error.name === 'AbortError') {
    return true;
  }
  if (error instanceof Error && error.name === 'AbortError') {
    return true;
  }
  return false;
}

export class ApiService {
  private config: ApiConfig;

  constructor(config: ApiConfig) {
    this.config = {
      timeout: 10000,
      headers: {
        'Content-Type': 'application/json',
      },
      protocol: 'http',
      ...config,
    };
  }

  /**
   * Send GET request
   * @param signal - Optional AbortSignal for cancellation
   */
  async get<T = unknown>(
    endpoint: string,
    params?: Record<string, unknown>,
    signal?: AbortSignal,
  ): Promise<ApiResponse<T>> {
    return this.request<T>(endpoint, {
      method: 'get',
      params,
      signal,
    });
  }

  /**
   * Send POST request
   * @param signal - Optional AbortSignal for cancellation
   */
  async post<T = unknown>(
    endpoint: string,
    data?: unknown,
    signal?: AbortSignal,
  ): Promise<ApiResponse<T>> {
    return this.request<T>(endpoint, {
      method: 'post',
      data,
      signal,
    });
  }

  /**
   * Send PUT request
   * @param signal - Optional AbortSignal for cancellation
   */
  async put<T = unknown>(
    endpoint: string,
    data?: unknown,
    signal?: AbortSignal,
  ): Promise<ApiResponse<T>> {
    return this.request<T>(endpoint, {
      method: 'put',
      data,
      signal,
    });
  }

  /**
   * Send DELETE request
   * @param signal - Optional AbortSignal for cancellation
   */
  async delete<T = unknown>(endpoint: string, signal?: AbortSignal): Promise<ApiResponse<T>> {
    return this.request<T>(endpoint, {
      method: 'delete',
      signal,
    });
  }

  /**
   * Send PATCH request
   * @param signal - Optional AbortSignal for cancellation
   */
  async patch<T = unknown>(
    endpoint: string,
    data?: unknown,
    signal?: AbortSignal,
  ): Promise<ApiResponse<T>> {
    return this.request<T>(endpoint, {
      method: 'patch',
      data,
      signal,
    });
  }

  /**
   * Create an abortable request with a managed AbortController.
   *
   * Returns { promise, abort, controller } so the caller can cancel
   * in-flight requests to prevent race conditions.
   *
   * @example
   * // In a React component or hook:
   * const { promise, abort } = apiService.createAbortableRequest<User[]>(
   *   '/users',
   *   { method: 'get', params: { role: 'admin' } }
   * );
   * // Later, if component unmounts or a new request is needed:
   * abort();
   *
   * @example
   * // Preventing race conditions in search:
   * let currentRequest: AbortableRequest | null = null;
   * async function onSearchChange(query: string) {
   *   currentRequest?.abort();
   *   currentRequest = apiService.createAbortableRequest('/search', {
   *     method: 'get',
   *     params: { q: query },
   *   });
   *   const result = await currentRequest.promise;
   *   if (result.code !== 'abort_error') {
   *     setResults(result.data);
   *   }
   * }
   */
  createAbortableRequest<T = unknown>(
    endpoint: string,
    options: Omit<RequestOptions, 'signal'> = {},
  ): AbortableRequest<T> {
    const controller = new AbortController();
    const promise = this.request<T>(endpoint, {
      ...options,
      signal: controller.signal,
    });

    return {
      promise,
      abort: () => controller.abort(),
      controller,
    };
  }

  /**
   * Core request method
   *
   * When options.signal is provided and already aborted (or becomes aborted
   * during the request), returns a cancelled ApiResponse instead of throwing.
   */
  async request<T = unknown>(
    endpoint: string,
    options: RequestOptions = {},
  ): Promise<ApiResponse<T>> {
    const { signal } = options;

    // Early exit: if signal is already aborted before we even start
    if (signal?.aborted) {
      return { ...ABORTED_RESPONSE, timestamp: Date.now() } as ApiResponse<T>;
    }

    try {
      // Wrap the underlying http-client call with abort signal awareness.
      // The http-client layer does not natively accept an AbortSignal,
      // so we race the request against the signal to discard stale responses.
      const resultPromise = this.executeRequest<T>(endpoint, options);

      if (!signal) {
        // No signal — execute normally
        const result = await resultPromise;
        return this.handleResponse<T>(result);
      }

      // Race the request against the abort signal
      const result = await raceWithAbort(resultPromise, signal);
      if (result === null) {
        // Signal was aborted — return cancelled response
        return { ...ABORTED_RESPONSE, timestamp: Date.now() } as ApiResponse<T>;
      }

      return this.handleResponse<T>(result);
    } catch (error) {
      if (isAbortError(error)) {
        return { ...ABORTED_RESPONSE, timestamp: Date.now() } as ApiResponse<T>;
      }
      return this.handleError(error);
    }
  }

  /**
   * Execute the actual HTTP request via the http-client layer.
   * Separated from request() to keep abort logic clean.
   */
  private async executeRequest<T>(
    endpoint: string,
    options: RequestOptions,
  ): Promise<{ code: string; desc: string; data: T | null }> {
    const fullUrl = this.config.baseURL + endpoint;

    // Cast to Record<string, unknown> for http-client compatibility
    const queryParams = (options.params || options.data) as Record<string, unknown> | undefined;
    const bodyParams = (options.data || options.params) as Record<string, unknown> | undefined;

    switch (options.method) {
      case 'get':
        return get<T>(fullUrl, queryParams);
      case 'post':
        return post<T>(fullUrl, bodyParams);
      case 'put':
        return put<T>(fullUrl, bodyParams);
      case 'delete':
        return del<T>(fullUrl, bodyParams);
      default:
        return post<T>(fullUrl, bodyParams);
    }
  }

  /**
   * Handle successful response from http-client
   */
  private handleResponse<T>(result: {
    code: string;
    desc: string;
    data: T | null;
  }): ApiResponse<T> {
    if (ResultHelper.isSuccess(result) && result.data !== null) {
      return {
        success: true,
        data: result.data,
        timestamp: Date.now(),
      };
    }

    return {
      success: false,
      data: null as never,
      message: result.desc,
      code: result.code,
      timestamp: Date.now(),
    };
  }

  /**
   * Handle errors.
   * AbortError is handled upstream in request() — this method handles
   * only non-abort errors (network, HTTP, etc.).
   */
  private handleError(error: unknown): ApiResponse<never> {
    // Double-check for AbortError that somehow reaches here
    if (isAbortError(error)) {
      return { ...ABORTED_RESPONSE, timestamp: Date.now() };
    }

    console.error('API请求错误:', error);

    let message = '请求失败';
    let code = 'unknown_error';

    const err = error as Record<string, unknown> | null | undefined;
    if (err?.response) {
      // HTTP error response
      const response = err.response as Record<string, unknown>;
      const status = response.status;
      const data = response.data as Record<string, unknown> | null | undefined;
      message = (data?.message as string) || `HTTP ${status} 错误`;
      code = (data?.code as string) || `HTTP_${String(status)}`;
    } else if (error instanceof Error) {
      // Network or other errors
      message = error.message;
      code = (err?.code as string) || 'network_error';
    }

    return {
      success: false,
      data: null as never,
      message,
      code,
      timestamp: Date.now(),
    };
  }

  /**
   * Set default request headers
   */
  setDefaultHeaders(headers: Record<string, string>): void {
    this.config.headers = {
      ...this.config.headers,
      ...headers,
    };
  }

  /**
   * Set authentication token
   */
  setAuthToken(token: string): void {
    this.setDefaultHeaders({
      Authorization: `Bearer ${token}`,
    });
  }

  /**
   * Clear authentication token
   */
  clearAuthToken(): void {
    const headers = { ...this.config.headers };
    delete headers.Authorization;
    this.config.headers = headers;
  }

  /**
   * Get config copy
   */
  getConfig(): ApiConfig {
    return { ...this.config };
  }

  /**
   * Update config
   */
  updateConfig(config: Partial<ApiConfig>): void {
    this.config = { ...this.config, ...config };
  }
}

// ============================================================================
// Module-level utilities
// ============================================================================

/**
 * Race a promise against an AbortSignal.
 *
 * Returns null if the signal fires before the promise resolves.
 * If the promise resolves first, returns the result normally.
 */
function raceWithAbort<T>(promise: Promise<T>, signal: AbortSignal): Promise<T | null> {
  // If already aborted, return immediately
  if (signal.aborted) {
    return Promise.resolve(null);
  }

  return new Promise<T | null>((resolve, reject) => {
    let settled = false;

    const onAbort = () => {
      if (!settled) {
        settled = true;
        resolve(null);
      }
    };

    signal.addEventListener('abort', onAbort, { once: true });

    promise
      .then((value) => {
        if (!settled) {
          settled = true;
          signal.removeEventListener('abort', onAbort);
          resolve(value);
        }
      })
      .catch((err) => {
        if (!settled) {
          settled = true;
          signal.removeEventListener('abort', onAbort);
          reject(err);
        }
      });
  });
}

/**
 * Create a standalone abortable request (without ApiService instance).
 *
 * Convenience wrapper for one-off requests that need cancellation support.
 *
 * @example
 * const { promise, abort } = createAbortableRequest(
 *   apiService,
 *   '/api/search',
 *   { method: 'get', params: { q: 'test' } }
 * );
 *
 * // Cancel when no longer needed
 * abort();
 *
 * const result = await promise;
 * if (result.code !== 'abort_error') {
 *   // Use result
 * }
 */
export function createAbortableRequest<T = unknown>(
  service: ApiService,
  endpoint: string,
  options: Omit<RequestOptions, 'signal'> = {},
): AbortableRequest<T> {
  return service.createAbortableRequest<T>(endpoint, options);
}

// ============================================================================
// Default instance management
// ============================================================================

let defaultApiService: ApiService | null = null;

/**
 * Get default API service instance
 */
export function getApiService(): ApiService {
  if (!defaultApiService) {
    defaultApiService = new ApiService({
      baseURL: '/api',
    });
  }
  return defaultApiService;
}

/**
 * Create a new API service instance
 */
export function createApiService(config: ApiConfig): ApiService {
  return new ApiService(config);
}

/**
 * Set default API service instance
 */
export function setDefaultApiService(apiService: ApiService): void {
  defaultApiService = apiService;
}

export default ApiService;
