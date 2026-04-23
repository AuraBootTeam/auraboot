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
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    const result = await executeFetch('http://invalid-host/api/test', { method: 'get' });

    expect(result.code).toBe(ErrorCodes.NETWORK_ERROR);
    expect(result.success).toBe(false);
    expect(result.desc).toContain('Failed to fetch');
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

  it('should pass init options to fetch', async () => {
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

    expect(mockFetch).toHaveBeenCalledWith('http://localhost:3500/api/test', init);
  });
});
