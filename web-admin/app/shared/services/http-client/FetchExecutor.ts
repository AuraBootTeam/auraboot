/**
 * HTTP Execution Layer
 *
 * Handles HTTP request execution and response processing.
 * Converts all errors to standardized Result format.
 */

import { ErrorCodes, type Result } from './types';

function normalizeResult<T>(result: Result<T>): Result<T> {
  // Ensure code is always a string — backend may return numeric 0 instead of "0"
  const code = String(result.code ?? '');
  const message = result.message ?? result.desc ?? '';
  const desc = result.desc ?? result.message ?? '';
  const success =
    typeof result.success === 'boolean' ? result.success : code === ErrorCodes.SUCCESS;

  return {
    ...result,
    code,
    desc,
    message,
    success,
    data: result.data ?? null,
    context: result.context ?? null,
  };
}

/**
 * Execute HTTP request and convert to Result<T>
 *
 * Responsibilities:
 * - Execute fetch request
 * - Handle HTTP errors (4xx, 5xx)
 * - Parse JSON response
 * - Convert errors to Result format
 *
 * Note: Timeout is handled by AbortSignal.timeout() set in URLBuilder.buildRequest(),
 * not by this function. The signal is passed via init.signal.
 *
 * Error handling:
 * - HTTP errors → Result with status code
 * - Network errors → Result with NETWORK_ERROR
 * - Timeout errors → Result with TIMEOUT_ERROR
 * - JSON parse errors → Result with JSON_PARSE_ERROR
 *
 * @param url Complete request URL
 * @param init Request initialization options (may include AbortSignal for timeout)
 * @returns Promise<Result<T>> - Always returns Result, never throws
 *
 * @example
 * // Successful request
 * const result = await executeFetch<User>('http://localhost:3500/api/user/current', {
 *   method: 'get',
 *   headers: { 'Authorization': 'Bearer token' }
 * });
 * // Result: { code: '0', desc: 'Success', data: { id: 1, name: 'John' } }
 *
 * @example
 * // HTTP error
 * const result = await executeFetch<User>('http://localhost:3500/api/user/999', {
 *   method: 'get'
 * });
 * // Result: { code: '404', desc: 'HTTP Error: 404 Not Found', data: null }
 *
 * @example
 * // Network error
 * const result = await executeFetch<User>('http://invalid-host/api/user', {
 *   method: 'get'
 * });
 * // Result: { code: 'network_error', desc: 'Network error: ...', data: null }
 */
export async function executeFetch<T>(url: string, init: RequestInit): Promise<Result<T>> {
  let response: Response | null = null;

  try {
    // Execute fetch request
    response = await fetch(url, init);

    // Check HTTP status code
    if (!response.ok) {
      return await handleHttpError(response);
    }

    // Parse JSON response
    try {
      const result: Result<T> = await response.json();
      return normalizeResult(result);
    } catch (jsonError) {
      return handleJsonError(jsonError as Error);
    }
  } catch (error) {
    // Handle different error types
    if (error instanceof Error) {
      // Check for timeout error
      if (error.name === 'AbortError' || error.name === 'TimeoutError') {
        return handleTimeoutError(error);
      }

      // Check for network error
      if (error.message.includes('fetch') || error.message.includes('network')) {
        return handleNetworkError(error);
      }
    }

    // Generic error fallback
    return handleNetworkError(error as Error);
  }
}

/**
 * Convert HTTP error to Result
 *
 * Attempts to parse the response body for detailed error information
 * from the backend (error code, message, validation errors).
 * Falls back to status code/text if body parsing fails.
 *
 * @param response HTTP response with error status
 * @returns Result with error code and description
 */
async function handleHttpError(response: Response): Promise<Result<any>> {
  const statusCode = response.status.toString();
  const statusText = response.statusText || 'Unknown Error';

  // Try to parse response body for detailed error info from backend
  try {
    const body = await response.json();
    return {
      code: body.code || statusCode,
      desc: body.desc || body.message || `HTTP Error: ${statusCode} ${statusText}`,
      message: body.message || `HTTP Error: ${statusCode} ${statusText}`,
      success: false,
      data: body.data || null,
      context: body.context ?? null,
    };
  } catch {
    // Response body is not JSON or empty — fall back to status info
    return {
      code: statusCode,
      desc: `HTTP Error: ${statusCode} ${statusText}`,
      message: `HTTP Error: ${statusCode} ${statusText}`,
      success: false,
      data: null,
      context: null,
    };
  }
}

/**
 * Convert network error to Result
 *
 * Handles network connectivity issues, DNS failures,
 * connection refused, etc.
 *
 * @param error Network error
 * @returns Result with NETWORK_ERROR code
 *
 * @example
 * handleNetworkError(new Error('Failed to fetch'))
 * // Result: { code: 'network_error', desc: 'Network error: Failed to fetch', data: null }
 */
function handleNetworkError(error: Error): Result<any> {
  return {
    code: ErrorCodes.NETWORK_ERROR,
    desc: `Network error: ${error.message}`,
    message: `Network error: ${error.message}`,
    success: false,
    data: null,
    context: null,
  };
}

/**
 * Convert JSON parse error to Result
 *
 * Handles cases where response body is not valid JSON.
 * This can happen when:
 * - Server returns HTML error page
 * - Response is corrupted
 * - Content-Type mismatch
 *
 * @param error JSON parse error
 * @returns Result with JSON_PARSE_ERROR code
 *
 * @example
 * handleJsonError(new SyntaxError('Unexpected token'))
 * // Result: { code: 'json_parse_error', desc: 'Failed to parse JSON response: ...', data: null }
 */
function handleJsonError(error: Error): Result<any> {
  return {
    code: ErrorCodes.JSON_PARSE_ERROR,
    desc: `Failed to parse JSON response: ${error.message}`,
    message: `Failed to parse JSON response: ${error.message}`,
    success: false,
    data: null,
    context: null,
  };
}

/**
 * Convert timeout error to Result
 *
 * Handles request timeout when response takes too long.
 *
 * @param error Timeout error
 * @returns Result with TIMEOUT_ERROR code
 *
 * @example
 * handleTimeoutError(new Error('The operation was aborted'))
 * // Result: { code: 'timeout_error', desc: 'Request timeout: ...', data: null }
 */
function handleTimeoutError(error: Error): Result<any> {
  return {
    code: ErrorCodes.TIMEOUT_ERROR,
    desc: `Request timeout: ${error.message}`,
    message: `Request timeout: ${error.message}`,
    success: false,
    data: null,
    context: null,
  };
}
