import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { executeFetch } from '../FetchExecutor';
import { ErrorCodes } from '../types';

describe('executeFetch', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('should return normalized result on success', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ code: '0', desc: 'OK', data: { id: 1 } }),
    });

    const result = await executeFetch<{ id: number }>('http://localhost:3500/api/test', {
      method: 'get',
    });

    expect(result.code).toBe('0');
    expect(result.success).toBe(true);
    expect(result.data).toEqual({ id: 1 });
    expect(result.desc).toBe('OK');
    expect(result.message).toBe('OK');
  });

  it('should normalize result with missing fields', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ code: '0', data: { id: 1 } }),
    });

    const result = await executeFetch<{ id: number }>('http://localhost:3500/api/test', {
      method: 'get',
    });

    expect(result.success).toBe(true);
    expect(result.desc).toBe('');
    expect(result.message).toBe('');
    expect(result.data).toEqual({ id: 1 });
  });

  it('should normalize result using message as desc fallback', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ code: '0', message: 'Success', data: null }),
    });

    const result = await executeFetch('http://localhost:3500/api/test', {
      method: 'get',
    });

    expect(result.desc).toBe('Success');
    expect(result.message).toBe('Success');
  });

  it('should handle HTTP 404 error', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      statusText: 'Not Found',
    });

    const result = await executeFetch('http://localhost:3500/api/test/999', { method: 'get' });

    expect(result.code).toBe('404');
    expect(result.success).toBe(false);
    expect(result.data).toBeNull();
    expect(result.desc).toContain('404');
  });

  it('should handle HTTP 500 error', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
    });

    const result = await executeFetch('http://localhost:3500/api/test', { method: 'get' });

    expect(result.code).toBe('500');
    expect(result.success).toBe(false);
  });

  it('should preserve backend error context on HTTP errors', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 422,
      statusText: 'Unprocessable Entity',
      json: () =>
        Promise.resolve({
          code: '35000',
          message: 'Bad parameter',
          context: { error: "Field 'wd_req_days' is required" },
        }),
    });

    const result = await executeFetch('http://localhost:3500/api/test', { method: 'post' });

    expect(result.code).toBe('35000');
    expect(result.success).toBe(false);
    expect(result.context).toEqual({ error: "Field 'wd_req_days' is required" });
    expect(result.httpStatus).toBe(422);
  });

  it('uses only human backend context on HTTP rejection and preserves diagnostics', async () => {
    for (const context of ['分页参数无效，请刷新后重试。',
      { exception: 'BusinessException', detail: '分页参数无效，请刷新后重试。', messageKey: '$i18n:private-key', cause: 'private-cause' }]) {
      globalThis.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
        code: '35000', message: 'Bad parameter', context,
      }), { status: 400 }));
      const result = await executeFetch('http://localhost/api/list?size=0', { method: 'get' });
      expect(result.desc).toBe('分页参数无效，请刷新后重试。');
      expect(result.message).toBe('Bad parameter');
      expect(result.context).toEqual(context);
      expect(result.httpStatus).toBe(400);
      expect(result.code).toBe('35000');
    }
  });

  it('keeps generic errors for empty or structured diagnostic context', async () => {
    for (const context of [null, '', '  ', { detail: '' }, { detail: { secret: 'private' } },
      { detail: 123, messageKey: '$i18n:private', cause: 'private' }, { messageKey: 'private' }]) {
      globalThis.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
        code: '35000', desc: 'Original failure', message: 'Bad parameter', context,
      }), { status: 400 }));
      const result = await executeFetch('http://localhost/api/list', { method: 'get' });
      expect(result.desc).toBe('Original failure');
      expect(result.message).toBe('Bad parameter');
      expect(result.context).toEqual(context);
    }
  });

  it('uses human detail for normal business rejection but leaves successful descriptions intact', async () => {
    const cases: Array<[Record<string, unknown>, string]> = [
      [{ code: '35000', message: 'Bad parameter', context: { detail: '可读的业务拒绝' } }, '可读的业务拒绝'],
      [{ code: '0', success: false, desc: 'Rejected', context: '明确拒绝' }, 'Rejected'],
      [{ code: '0', desc: 'Loaded', context: { detail: '非错误详情' } }, 'Loaded'],
      [{ code: '35000', success: true, desc: 'Explicit success', context: '非错误详情' }, 'Explicit success'],
    ];
    for (const [body, expected] of cases) {
      globalThis.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 200 }));
      const result = await executeFetch('http://localhost/api/list', { method: 'get' });
      expect(result.desc).toBe(expected);
      expect(result.message).toBe(body.message ?? body.desc);
    }
  });

  it('does not promote Core system or forbidden diagnostic contexts into descriptions', async () => {
    // Wire shapes from GlobalExceptionHandler.handleGenericException / handleAccessDeniedException.
    // These are transport UT fixtures, not a live backend or UI verification.
    const cases: Array<[number, Record<string, unknown>]> = [
      [500, { code: '1', message: 'Internal system error', context: {
        exception: 'IllegalStateException', detail: 'SELECT private_column FROM private_table; model private_model PID secret',
        cause: 'RuntimeException: private-cause',
      } }],
      [403, { code: '403', message: 'Access forbidden', context: 'Missing permission for plugin route: private_permission' }],
      [401, { code: '40000', message: 'Business error', context: 'private authentication detail' }],
      [500, { code: '35000', message: 'Bad parameter', context: 'private server detail' }],
      [403, { code: '35000', message: 'Bad parameter', context: { detail: 'private authorization detail' } }],
      [400, { code: '35000', message: 'Bad parameter', context: { exception: 'IllegalStateException', detail: 'private stored record' } }],
      [400, { code: '7777', message: 'Unknown failure', context: 'private unknown detail' }],
    ];
    for (const [httpStatus, body] of cases) {
      globalThis.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: httpStatus }));
      const result = await executeFetch('http://localhost/api/meta/command-phases', { method: 'get' });
      expect(result.desc).toBe(body.message);
      expect(result.context).toEqual(body.context);
      expect(result.httpStatus).toBe(httpStatus);
      expect(result.code).toBe(body.code);
    }
  });

  it('keeps the exact Core BusinessError contract readable with a trustworthy transport status', async () => {
    for (const context of ['已知业务拒绝', { exception: 'BusinessException', detail: '已知业务拒绝', cause: 'private cause' }]) {
      globalThis.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
        code: '40000', message: 'Business error', context, httpStatus: 500,
      }), { status: 400 }));
      const result = await executeFetch('http://localhost/api/list', { method: 'get' });
      expect(result.desc).toBe('已知业务拒绝');
      expect(result.httpStatus).toBe(400);
    }
  });

  it('should handle HTTP error with empty statusText', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 502,
      statusText: '',
    });

    const result = await executeFetch('http://localhost:3500/api/test', { method: 'get' });

    expect(result.code).toBe('502');
    expect(result.desc).toContain('Unknown Error');
  });

  it('should handle network error', async () => {
    const transportError = Object.assign(new Error('connect ECONNREFUSED ::1:5362'), {
      code: 'ECONNREFUSED',
      syscall: 'connect',
      address: '::1',
      port: 5362,
    });
    globalThis.fetch = vi
      .fn()
      .mockRejectedValue(new TypeError('Failed to fetch', { cause: transportError }));

    const result = await executeFetch('http://invalid-host/api/test', { method: 'get' });

    expect(result.code).toBe(ErrorCodes.NETWORK_ERROR);
    expect(result.success).toBe(false);
    expect(result.desc).toContain('Failed to fetch');
    expect(result.context).toEqual({
      transportCause: {
        name: 'Error',
        message: 'connect ECONNREFUSED ::1:5362',
        code: 'ECONNREFUSED',
        syscall: 'connect',
        address: '::1',
        port: 5362,
      },
    });
  });

  it('should handle timeout error (AbortError)', async () => {
    const abortError = new Error('The operation was aborted');
    abortError.name = 'AbortError';
    globalThis.fetch = vi.fn().mockRejectedValue(abortError);

    const result = await executeFetch('http://localhost:3500/api/test', { method: 'get' });

    expect(result.code).toBe(ErrorCodes.TIMEOUT_ERROR);
    expect(result.success).toBe(false);
  });

  it('should handle JSON parse error', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.reject(new SyntaxError('Unexpected token')),
    });

    const result = await executeFetch('http://localhost:3500/api/test', { method: 'get' });

    expect(result.code).toBe(ErrorCodes.JSON_PARSE_ERROR);
    expect(result.success).toBe(false);
    expect(result.desc).toContain('Unexpected token');
  });

  it('should normalize null data to null', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ code: '0', desc: 'OK' }),
    });

    const result = await executeFetch('http://localhost:3500/api/test', { method: 'get' });

    expect(result.data).toBeNull();
  });

  it('should derive success from code when success field is missing', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ code: '1001', desc: 'Custom error', data: null }),
    });

    const result = await executeFetch('http://localhost:3500/api/test', { method: 'get' });

    expect(result.success).toBe(false); // code !== '0'
  });

  it('should pass init options to fetch with an uppercase wire method', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ code: '0', desc: 'OK', data: null }),
    });
    globalThis.fetch = mockFetch;

    const init: RequestInit = {
      method: 'post',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer token' },
      body: '{"name":"test"}',
      credentials: 'include',
    };

    await executeFetch('http://localhost:3500/api/test', init);

    expect(mockFetch).toHaveBeenCalledWith('http://localhost:3500/api/test', {
      ...init,
      method: 'POST',
    });
  });

  it('should normalize lowercase PATCH before it reaches the server', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ code: '0', desc: 'OK', data: null }),
    });
    globalThis.fetch = mockFetch;

    await executeFetch('http://localhost:3500/api/authoring/sessions/session-1/patches', {
      method: 'patch',
      body: '{}',
    });

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:3500/api/authoring/sessions/session-1/patches',
      { method: 'PATCH', body: '{}' },
    );
  });
});
