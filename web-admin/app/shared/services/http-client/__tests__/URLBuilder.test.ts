import { describe, it, expect } from 'vitest';
import {
  replacePathVariables,
  buildQueryString,
  resolveBaseUrl,
  buildRequest,
} from '../URLBuilder';
import type { RequestContext, FetchOptions } from '../types';

describe('replacePathVariables', () => {
  it('should replace single path variable', () => {
    const result = replacePathVariables('/api/user/{userId}', { userId: 123 });
    expect(result.processedPath).toBe('/api/user/123');
    expect(result.remainingParams).toEqual({});
  });

  it('should replace multiple path variables', () => {
    const result = replacePathVariables('/api/user/{userId}/post/{postId}', {
      userId: 123,
      postId: 456,
      role: 'admin',
    });
    expect(result.processedPath).toBe('/api/user/123/post/456');
    expect(result.remainingParams).toEqual({ role: 'admin' });
  });

  it('should URL-encode path variable values', () => {
    const result = replacePathVariables('/api/user/{name}', {
      name: 'John Doe',
    });
    expect(result.processedPath).toBe('/api/user/John%20Doe');
  });

  it('should leave unmatched placeholders untouched', () => {
    const result = replacePathVariables('/api/user/{userId}', { role: 'admin' });
    expect(result.processedPath).toBe('/api/user/{userId}');
    expect(result.remainingParams).toEqual({ role: 'admin' });
  });

  it('should handle path with no placeholders', () => {
    const result = replacePathVariables('/api/users', { page: 1 });
    expect(result.processedPath).toBe('/api/users');
    expect(result.remainingParams).toEqual({ page: 1 });
  });

  it('should handle empty params', () => {
    const result = replacePathVariables('/api/user/{userId}', {});
    expect(result.processedPath).toBe('/api/user/{userId}');
    expect(result.remainingParams).toEqual({});
  });
});

describe('buildQueryString', () => {
  it('should build simple query string', () => {
    const result = buildQueryString({ name: 'John', age: 30 });
    expect(result).toBe('name=John&age=30');
  });

  it('should skip undefined values', () => {
    const result = buildQueryString({ name: 'John', age: undefined });
    expect(result).toBe('name=John');
  });

  it('should skip null values', () => {
    const result = buildQueryString({ name: 'John', role: null });
    expect(result).toBe('name=John');
  });

  it('should handle boolean values', () => {
    const result = buildQueryString({ active: true, deleted: false });
    expect(result).toBe('active=true&deleted=false');
  });

  it('should handle number values', () => {
    const result = buildQueryString({ page: 1, size: 20 });
    expect(result).toBe('page=1&size=20');
  });

  it('should JSON.stringify array values', () => {
    const result = buildQueryString({ ids: [1, 2, 3] });
    const decoded = decodeURIComponent(result);
    expect(decoded).toBe('ids=[1,2,3]');
  });

  it('should JSON.stringify object values', () => {
    const result = buildQueryString({
      filters: [{ fieldName: 'status', operator: 'EQ', value: 'active' }],
    });
    const parsed = new URLSearchParams(result);
    const filtersValue = parsed.get('filters');
    expect(JSON.parse(filtersValue!)).toEqual([
      { fieldName: 'status', operator: 'EQ', value: 'active' },
    ]);
  });

  it('should handle empty params', () => {
    const result = buildQueryString({});
    expect(result).toBe('');
  });

  it('should handle mixed primitive and object values', () => {
    const result = buildQueryString({
      page: 1,
      filters: { field: 'name' },
      name: 'test',
    });
    const parsed = new URLSearchParams(result);
    expect(parsed.get('page')).toBe('1');
    expect(parsed.get('name')).toBe('test');
    expect(JSON.parse(parsed.get('filters')!)).toEqual({ field: 'name' });
  });
});

describe('resolveBaseUrl', () => {
  it('should use explicit apiConfig.baseUrl when provided', () => {
    const context: RequestContext = { isServer: true };
    const result = resolveBaseUrl(context, { baseUrl: 'https://custom.api.com' });
    expect(result).toBe('https://custom.api.com');
  });

  it('should use empty string apiConfig.baseUrl', () => {
    const context: RequestContext = { isServer: true };
    const result = resolveBaseUrl(context, { baseUrl: '' });
    expect(result).toBe('');
  });

  it('should return default BFF URL for SSR without env vars', () => {
    const context: RequestContext = { isServer: true };
    const result = resolveBaseUrl(context);
    expect(result).toMatch(/^http:\/\/localhost:\d+$/);
  });

  it('should return origin for CSR in browser', () => {
    const context: RequestContext = { isServer: false };
    const result = resolveBaseUrl(context);
    // jsdom provides window.location.origin (may include port)
    expect(result).toBe(window.location.origin);
  });
});

describe('buildRequest', () => {
  const serverContext: RequestContext = { isServer: true };

  it('should build GET request with query params', () => {
    const options: FetchOptions = {
      method: 'get',
      params: { page: 1, size: 20 },
    };
    const { url, init } = buildRequest('/api/users', options, serverContext);
    expect(url).toContain('/api/users?page=1&size=20');
    expect(init.method).toBe('get');
    expect(init.body).toBeUndefined();
  });

  it('should append GET params with ampersand when path already has a query string', () => {
    const options: FetchOptions = {
      method: 'get',
      params: { datasourceId: 'execution_logs_dataSource' },
    };
    const { url } = buildRequest('/api/scheduled-tasks/task-1/logs?limit=50', options, serverContext);
    expect(url).toContain('/api/scheduled-tasks/task-1/logs?limit=50&datasourceId=execution_logs_dataSource');
    expect(url).not.toContain('limit=50?datasourceId=');
  });

  it('should build POST request with JSON body', () => {
    const options: FetchOptions = {
      method: 'post',
      params: { name: 'John', email: 'john@test.com' },
    };
    const { url, init } = buildRequest('/api/users', options, serverContext);
    expect(url).toContain('/api/users');
    expect(url).not.toContain('?');
    expect(init.method).toBe('post');
    expect(init.body).toBe('{"name":"John","email":"john@test.com"}');
  });

  it('should preserve array bodies for batch PUT requests', () => {
    const options: FetchOptions = {
      method: 'put',
      params: [{ pid: 'pid-1', name: 'Updated' }],
    };
    const { url, init } = buildRequest('/api/dynamic/page_schema/batch', options, serverContext);
    expect(url).toContain('/api/dynamic/page_schema/batch');
    expect(url).not.toContain('?0=');
    expect(init.method).toBe('put');
    expect(init.body).toBe('[{"pid":"pid-1","name":"Updated"}]');
  });

  it('should build DELETE request with query params (not body)', () => {
    const options: FetchOptions = {
      method: 'delete',
      params: { reason: 'inactive' },
    };
    const { url, init } = buildRequest('/api/user/123', options, serverContext);
    expect(url).toContain('?reason=inactive');
    expect(init.method).toBe('delete');
    expect(init.body).toBeUndefined();
  });

  it('should build DELETE request with array JSON body for batch APIs', () => {
    const options: FetchOptions = {
      method: 'delete',
      params: ['pid-1', 'pid-2'],
    };
    const { url, init } = buildRequest('/api/dynamic/page_schema/batch', options, serverContext);
    expect(url).toContain('/api/dynamic/page_schema/batch');
    expect(url).not.toContain('?0=');
    expect(init.method).toBe('delete');
    expect(init.body).toBe('["pid-1","pid-2"]');
  });

  it('should replace path variables and use remaining as query/body', () => {
    const options: FetchOptions = {
      method: 'get',
      params: { userId: 123, role: 'admin' },
    };
    const { url } = buildRequest('/api/user/{userId}', options, serverContext);
    expect(url).toContain('/api/user/123');
    expect(url).toContain('role=admin');
  });

  it('should add authorization header when token provided', () => {
    const options: FetchOptions = { method: 'get' };
    const { init } = buildRequest('/api/users', options, serverContext, 'my-token');
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer my-token');
  });

  it('should not add authorization header without token', () => {
    const options: FetchOptions = { method: 'get' };
    const { init } = buildRequest('/api/users', options, serverContext);
    expect((init.headers as Record<string, string>)['Authorization']).toBeUndefined();
  });

  it('should set Content-Type to application/json', () => {
    const options: FetchOptions = { method: 'get' };
    const { init } = buildRequest('/api/users', options, serverContext);
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
  });

  it('should set credentials to include', () => {
    const options: FetchOptions = { method: 'get' };
    const { init } = buildRequest('/api/users', options, serverContext);
    expect(init.credentials).toBe('include');
  });

  it('should add AbortSignal.timeout when timeout option is set', () => {
    const options: FetchOptions = { method: 'get', timeout: 5000 };
    const { init } = buildRequest('/api/users', options, serverContext);
    // AbortSignal.timeout may not be available in all test environments
    if (typeof AbortSignal !== 'undefined' && 'timeout' in AbortSignal) {
      expect(init.signal).toBeDefined();
    }
  });

  it('should pass caller abort signal to fetch init', () => {
    const controller = new AbortController();
    const options: FetchOptions = { method: 'get', signal: controller.signal };
    const { init } = buildRequest('/api/users', options, serverContext);
    expect(init.signal).toBe(controller.signal);
  });

  it('should handle empty params for GET request', () => {
    const options: FetchOptions = { method: 'get', params: {} };
    const { url } = buildRequest('/api/users', options, serverContext);
    expect(url).not.toContain('?');
  });

  it('should default to GET method', () => {
    const { init } = buildRequest('/api/users', {}, serverContext);
    expect(init.method).toBe('get');
  });
});

describe('keepalive passthrough', () => {
  const serverContext: RequestContext = { isServer: true };

  it('threads keepalive into RequestInit while preserving Authorization and credentials', () => {
    const options: FetchOptions = { method: 'post', keepalive: true };
    const { init } = buildRequest('/api/collect', options, serverContext, 'my-token');
    expect(init.keepalive).toBe(true);
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer my-token');
    expect(init.credentials).toBe('include');
  });

  it('does not set keepalive when option is absent', () => {
    const options: FetchOptions = { method: 'post' };
    const { init } = buildRequest('/api/collect', options, serverContext, 'my-token');
    expect(init.keepalive).toBeUndefined();
  });
});

describe('X-Timezone header injection', () => {
  it('should add X-Timezone header when context has timezone', () => {
    const context: RequestContext = {
      isServer: false,
      token: 'test-token',
      timezone: 'Asia/Shanghai',
    };

    const result = buildRequest('/api/test', { method: 'get' }, context, 'test-token');

    expect((result.init.headers as Record<string, string>)['X-Timezone']).toBe('Asia/Shanghai');
  });

  it('should NOT add X-Timezone header when context has no timezone', () => {
    const context: RequestContext = {
      isServer: false,
      token: 'test-token',
    };

    const result = buildRequest('/api/test', { method: 'get' }, context, 'test-token');

    expect((result.init.headers as Record<string, string>)['X-Timezone']).toBeUndefined();
  });

  it('should NOT add X-Timezone header in SSR context', () => {
    const context: RequestContext = {
      isServer: true,
      token: 'test-token',
    };

    const result = buildRequest('/api/test', { method: 'get' }, context, 'test-token');

    expect((result.init.headers as Record<string, string>)['X-Timezone']).toBeUndefined();
  });
});
