/**
 * Unit tests for ApiService
 *
 * ApiService wraps get/post/put/del from `~/shared/services/http-client`.
 * Key logic to test:
 *  - request() delegates to correct http-client fn based on method
 *  - handleResponse: success response → { success: true, data }
 *  - handleResponse: failure response → { success: false, message, code }
 *  - abort logic: pre-aborted signal → ABORTED_RESPONSE immediately
 *  - abort logic: signal aborts during race → ABORTED_RESPONSE
 *  - AbortError thrown → ABORTED_RESPONSE
 *  - createAbortableRequest() returns { promise, abort, controller }
 *  - Module-level utilities: getApiService, createApiService, setDefaultApiService
 *  - Config helpers: setDefaultHeaders, setAuthToken, clearAuthToken, getConfig, updateConfig
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getMock, postMock, putMock, delMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  putMock: vi.fn(),
  delMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  get: getMock,
  post: postMock,
  put: putMock,
  del: delMock,
}));

import {
  ApiService,
  createApiService,
  createAbortableRequest,
  getApiService,
  setDefaultApiService,
} from '../ApiService';

const successResult = (data: unknown) => ({ code: '0', desc: 'OK', data });
const failureResult = (desc: string, code = '500') => ({ code, desc, data: null });

describe('ApiService', () => {
  let service: ApiService;

  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
    putMock.mockReset();
    delMock.mockReset();
    service = new ApiService({ baseURL: '/api' });
  });

  // ── constructor defaults ────────────────────────────────────────────────────

  describe('constructor', () => {
    it('applies default timeout and headers', () => {
      const config = service.getConfig();

      expect(config.timeout).toBe(10000);
      expect(config.headers?.['Content-Type']).toBe('application/json');
      expect(config.protocol).toBe('http');
      expect(config.baseURL).toBe('/api');
    });

    it('merges supplied config over defaults', () => {
      const svc = new ApiService({ baseURL: '/custom', timeout: 5000, protocol: 'grpc' });
      const config = svc.getConfig();

      expect(config.timeout).toBe(5000);
      expect(config.protocol).toBe('grpc');
      expect(config.baseURL).toBe('/custom');
    });
  });

  // ── GET request routing ─────────────────────────────────────────────────────

  describe('get()', () => {
    it('calls http-client get with baseURL+endpoint and params', async () => {
      getMock.mockResolvedValue(successResult({ id: 1 }));

      const response = await service.get('/users', { role: 'admin' });

      expect(getMock).toHaveBeenCalledWith('/api/users', { role: 'admin' });
      expect(response.success).toBe(true);
      expect(response.data).toEqual({ id: 1 });
    });

    it('returns success:false when backend indicates failure', async () => {
      getMock.mockResolvedValue(failureResult('Not found', '404'));

      const response = await service.get('/users/99');

      expect(response.success).toBe(false);
      expect(response.message).toBe('Not found');
      expect(response.code).toBe('404');
    });
  });

  // ── POST request routing ────────────────────────────────────────────────────

  describe('post()', () => {
    it('calls http-client post with baseURL+endpoint and body', async () => {
      postMock.mockResolvedValue(successResult({ id: 2 }));

      const response = await service.post('/users', { name: 'Alice' });

      expect(postMock).toHaveBeenCalledWith('/api/users', { name: 'Alice' });
      expect(response.success).toBe(true);
      expect(response.data).toEqual({ id: 2 });
    });
  });

  // ── PUT request routing ─────────────────────────────────────────────────────

  describe('put()', () => {
    it('calls http-client put', async () => {
      putMock.mockResolvedValue(successResult({ id: 1, name: 'Bob' }));

      const response = await service.put('/users/1', { name: 'Bob' });

      expect(putMock).toHaveBeenCalledWith('/api/users/1', { name: 'Bob' });
      expect(response.data).toEqual({ id: 1, name: 'Bob' });
    });
  });

  // ── DELETE request routing ──────────────────────────────────────────────────

  describe('delete()', () => {
    it('calls http-client del', async () => {
      delMock.mockResolvedValue(successResult(null));

      await service.delete('/users/1');

      expect(delMock).toHaveBeenCalledWith('/api/users/1', undefined);
    });
  });

  // ── PATCH falls back to POST ────────────────────────────────────────────────

  describe('patch()', () => {
    it('falls back to http-client post (no patch in http-client layer)', async () => {
      postMock.mockResolvedValue(successResult({ id: 1 }));

      const response = await service.patch('/users/1', { active: false });

      // executeRequest default case uses post
      expect(postMock).toHaveBeenCalledWith('/api/users/1', { active: false });
      expect(response.success).toBe(true);
    });
  });

  // ── handleResponse: null data treated as failure ────────────────────────────

  describe('handleResponse (null data)', () => {
    it('returns success:false when data is null even with code:0', async () => {
      getMock.mockResolvedValue({ code: '0', desc: 'OK', data: null });

      const response = await service.get('/empty');

      expect(response.success).toBe(false);
      expect(response.data).toBeNull();
    });
  });

  // ── handleError paths ───────────────────────────────────────────────────────

  describe('handleError', () => {
    it('handles network Error with message', async () => {
      getMock.mockRejectedValue(new Error('Network timeout'));

      const response = await service.get('/slow');

      expect(response.success).toBe(false);
      expect(response.message).toBe('Network timeout');
      expect(response.code).toBe('network_error');
    });

    it('handles error with response shape (HTTP error)', async () => {
      const httpError = { response: { status: 503, data: { message: 'Service unavailable', code: 'UNAVAILABLE' } } };
      getMock.mockRejectedValue(httpError);

      const response = await service.get('/down');

      expect(response.success).toBe(false);
      expect(response.message).toBe('Service unavailable');
      expect(response.code).toBe('UNAVAILABLE');
    });

    it('returns generic message for unknown error shape', async () => {
      getMock.mockRejectedValue({ weirdProp: true });

      const response = await service.get('/weird');

      expect(response.success).toBe(false);
      expect(response.message).toBe('请求失败');
    });
  });

  // ── abort: pre-aborted signal ───────────────────────────────────────────────

  describe('abort: pre-aborted signal', () => {
    it('returns ABORTED_RESPONSE immediately without calling http-client', async () => {
      const controller = new AbortController();
      controller.abort();

      const response = await service.get('/users', {}, controller.signal);

      expect(getMock).not.toHaveBeenCalled();
      expect(response.success).toBe(false);
      expect(response.code).toBe('abort_error');
      expect(response.message).toBe('Request aborted');
    });
  });

  // ── abort: signal fires during race ────────────────────────────────────────

  describe('abort: signal fires during race', () => {
    it('resolves to ABORTED_RESPONSE when controller.abort() called before request resolves', async () => {
      const controller = new AbortController();

      // getMock returns a promise that won't resolve until we abort
      let resolveGet!: (v: unknown) => void;
      getMock.mockReturnValue(new Promise((resolve) => { resolveGet = resolve; }));

      const responsePromise = service.get('/slow', {}, controller.signal);

      // Abort before the mock resolves
      controller.abort();

      const response = await responsePromise;

      expect(response.success).toBe(false);
      expect(response.code).toBe('abort_error');

      // Clean up the pending promise
      resolveGet(successResult(null));
    });
  });

  // ── abort: AbortError thrown ────────────────────────────────────────────────

  describe('abort: AbortError thrown', () => {
    it('handles DOMException AbortError and returns ABORTED_RESPONSE', async () => {
      const controller = new AbortController();
      const abortError = new DOMException('The operation was aborted', 'AbortError');
      getMock.mockRejectedValue(abortError);

      const response = await service.get('/cancelable', {}, controller.signal);

      expect(response.code).toBe('abort_error');
    });

    it('handles Error with name AbortError', async () => {
      const err = new Error('aborted');
      err.name = 'AbortError';
      getMock.mockRejectedValue(err);

      const response = await service.get('/cancelable');

      expect(response.code).toBe('abort_error');
    });
  });

  // ── createAbortableRequest ──────────────────────────────────────────────────

  describe('createAbortableRequest()', () => {
    it('returns { promise, abort, controller }', () => {
      getMock.mockReturnValue(new Promise(() => {}));

      const req = service.createAbortableRequest('/search', { method: 'get', params: { q: 'test' } });

      expect(req).toHaveProperty('promise');
      expect(req).toHaveProperty('abort');
      expect(req).toHaveProperty('controller');
      expect(req.controller).toBeInstanceOf(AbortController);
    });

    it('abort() causes promise to resolve with abort_error', async () => {
      let resolveGet!: (v: unknown) => void;
      getMock.mockReturnValue(new Promise((r) => { resolveGet = r; }));

      const req = service.createAbortableRequest<string[]>('/search', { method: 'get' });
      req.abort();

      const response = await req.promise;
      expect(response.code).toBe('abort_error');

      resolveGet(successResult([]));
    });
  });

  // ── config helpers ──────────────────────────────────────────────────────────

  describe('setDefaultHeaders / setAuthToken / clearAuthToken', () => {
    it('setDefaultHeaders merges headers', () => {
      service.setDefaultHeaders({ 'X-Tenant': 'acme' });

      expect(service.getConfig().headers?.['X-Tenant']).toBe('acme');
      expect(service.getConfig().headers?.['Content-Type']).toBe('application/json');
    });

    it('setAuthToken sets Authorization Bearer header', () => {
      service.setAuthToken('my-jwt-token');

      expect(service.getConfig().headers?.['Authorization']).toBe('Bearer my-jwt-token');
    });

    it('clearAuthToken removes Authorization header', () => {
      service.setAuthToken('my-jwt-token');
      service.clearAuthToken();

      expect(service.getConfig().headers?.['Authorization']).toBeUndefined();
    });
  });

  describe('updateConfig', () => {
    it('merges partial config update', () => {
      service.updateConfig({ timeout: 30000 });

      expect(service.getConfig().timeout).toBe(30000);
      expect(service.getConfig().baseURL).toBe('/api'); // unchanged
    });
  });

  // ── module-level utilities ──────────────────────────────────────────────────

  describe('getApiService()', () => {
    it('returns a singleton ApiService with /api as baseURL', () => {
      const svc1 = getApiService();
      const svc2 = getApiService();

      expect(svc1).toBeInstanceOf(ApiService);
      expect(svc1).toBe(svc2); // same instance
    });
  });

  describe('createApiService()', () => {
    it('returns a new ApiService instance', () => {
      const svc = createApiService({ baseURL: '/custom-api' });

      expect(svc).toBeInstanceOf(ApiService);
      expect(svc.getConfig().baseURL).toBe('/custom-api');
    });
  });

  describe('setDefaultApiService()', () => {
    it('replaces the singleton returned by getApiService()', () => {
      const custom = new ApiService({ baseURL: '/replaced' });
      setDefaultApiService(custom);

      expect(getApiService()).toBe(custom);

      // Restore for other tests
      setDefaultApiService(new ApiService({ baseURL: '/api' }));
    });
  });

  describe('createAbortableRequest() module helper', () => {
    it('delegates to service.createAbortableRequest()', () => {
      getMock.mockReturnValue(new Promise(() => {}));
      const svc = new ApiService({ baseURL: '/api' });

      const req = createAbortableRequest(svc, '/test', { method: 'get' });

      expect(req).toHaveProperty('promise');
      expect(req).toHaveProperty('abort');
    });
  });
});
