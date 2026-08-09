import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fetchResult, get, post, put, del, patch } from '../HttpClient';
import {
  activateAuthoringPreviewGuard,
  AUTHORING_WRITE_BLOCKED_EVENT,
  resetAuthoringPreviewGuardForTests,
} from '../AuthoringPreviewGuard';

// Mock session module to prevent SSR session resolution errors
vi.mock('~/shared/services/session', () => ({
  sessionStorage: {
    getSession: vi.fn(async () => ({
      get: vi.fn(),
      set: vi.fn(),
      unset: vi.fn(),
    })),
    commitSession: vi.fn(),
    destroySession: vi.fn(),
  },
}));

describe('HttpClient integration', () => {
  const originalFetch = globalThis.fetch;

  function mockFetchSuccess(data: any = null) {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ code: '0', desc: 'OK', data }),
    });
  }

  beforeEach(() => {
    vi.restoreAllMocks();
    // Set up sessionStorage for CSR token (key must match AuthConstant.JWT_TOKEN_KEY)
    window.sessionStorage.setItem('jwtToken', 'test-token');
  });

  afterEach(() => {
    resetAuthoringPreviewGuardForTests();
    globalThis.fetch = originalFetch;
    window.sessionStorage.clear();
    window.localStorage.clear();
  });

  describe('fetchResult', () => {
    it('blocks business writes during authoring preview but allows reads and authoring APIs', async () => {
      mockFetchSuccess({ ok: true });
      const blockedEvents = vi.fn();
      window.addEventListener(AUTHORING_WRITE_BLOCKED_EVENT, blockedEvents);
      const deactivate = activateAuthoringPreviewGuard('session-1');

      const command = await fetchResult('/api/meta/commands/execute/order:update', {
        method: 'post',
        params: { payload: { pid: 'record-1' } },
      });
      const mutation = await fetchResult('/api/dynamic/order/record-1', {
        method: 'put',
        params: { status: 'APPROVED' },
      });

      expect(command.code).toBe('authoring_preview_write_blocked');
      expect(mutation.code).toBe('authoring_preview_write_blocked');
      expect(globalThis.fetch).not.toHaveBeenCalled();
      expect(blockedEvents).toHaveBeenCalledTimes(2);

      await fetchResult('/api/dynamic/order/list');
      await fetchResult('/api/authoring/sessions/session-1/handoffs', {
        method: 'post',
        params: { expectedRevision: 1 },
      });
      expect(globalThis.fetch).toHaveBeenCalledTimes(2);

      deactivate();
      window.removeEventListener(AUTHORING_WRITE_BLOCKED_EVENT, blockedEvents);
    });

    it('should make a GET request by default', async () => {
      mockFetchSuccess({ users: [] });

      const result = await fetchResult('/api/users');

      expect(result.code).toBe('0');
      expect(result.success).toBe(true);
      expect(result.data).toEqual({ users: [] });

      const [url, init] = (globalThis.fetch as any).mock.calls[0];
      expect(url).toContain('/api/users');
      expect(init.method).toBe('get');
    });

    it('should make a POST request with body', async () => {
      mockFetchSuccess({ id: 1 });

      const result = await fetchResult('/api/users', {
        method: 'post',
        params: { name: 'John' },
      });

      expect(result.success).toBe(true);
      const [, init] = (globalThis.fetch as any).mock.calls[0];
      expect(init.method).toBe('post');
      expect(init.body).toBe('{"name":"John"}');
    });

    it('should generate a client request identity for command posts', async () => {
      mockFetchSuccess({ ok: true });

      await fetchResult('/api/meta/commands/execute/crm:release_qdp', {
        method: 'post',
        params: { payload: { requestPid: 'request-1' } },
      });

      const [, init] = (globalThis.fetch as any).mock.calls[0];
      const body = JSON.parse(init.body);
      expect(body.clientRequestId).toMatch(/^ui-/);
      expect(body.payload).toEqual({ requestPid: 'request-1' });
    });

    it('should preserve an explicit command client request identity', async () => {
      mockFetchSuccess({ ok: true });

      await fetchResult('/api/meta/commands/execute/crm:release_qdp', {
        method: 'post',
        params: { clientRequestId: 'external-retry-1', payload: {} },
      });

      const [, init] = (globalThis.fetch as any).mock.calls[0];
      expect(JSON.parse(init.body).clientRequestId).toBe('external-retry-1');
    });

    it('should include auth header for protected routes', async () => {
      mockFetchSuccess();

      await fetchResult('/api/user/current');

      const [, init] = (globalThis.fetch as any).mock.calls[0];
      expect((init.headers as Record<string, string>)['Authorization']).toMatch(/^Bearer /);
    });

    it('should not include auth header for public routes', async () => {
      mockFetchSuccess();

      await fetchResult('/api/auth/login', {
        method: 'post',
        params: { email: 'test@test.com', password: '123' },
      });

      const [, init] = (globalThis.fetch as any).mock.calls[0];
      expect((init.headers as Record<string, string>)['Authorization']).toBeUndefined();
    });
  });

  describe('get', () => {
    it('should make GET request with params as query string', async () => {
      mockFetchSuccess([]);

      const result = await get('/api/users', { page: 1, size: 20 });

      expect(result.success).toBe(true);
      const [url] = (globalThis.fetch as any).mock.calls[0];
      expect(url).toContain('page=1');
      expect(url).toContain('size=20');
    });
  });

  describe('post', () => {
    it('should make POST request with params as body', async () => {
      mockFetchSuccess({ id: 1 });

      const result = await post('/api/users', { name: 'John' });

      expect(result.success).toBe(true);
      const [, init] = (globalThis.fetch as any).mock.calls[0];
      expect(init.method).toBe('post');
      expect(JSON.parse(init.body)).toEqual({ name: 'John' });
    });
  });

  describe('put', () => {
    it('should make PUT request', async () => {
      mockFetchSuccess({ id: 1 });

      const result = await put('/api/user/{userId}', {
        userId: 1,
        name: 'Updated',
      });

      expect(result.success).toBe(true);
      const [url, init] = (globalThis.fetch as any).mock.calls[0];
      expect(url).toContain('/api/user/1');
      expect(init.method).toBe('put');
      expect(JSON.parse(init.body)).toEqual({ name: 'Updated' });
    });

    it('should make PUT request with array body for batch endpoints', async () => {
      mockFetchSuccess([{ pid: 'pid-1' }]);

      await put('/api/dynamic/page_schema/batch', [{ pid: 'pid-1', name: 'Updated' }]);

      const [url, init] = (globalThis.fetch as any).mock.calls[0];
      expect(url).toContain('/api/dynamic/page_schema/batch');
      expect(url).not.toContain('?0=');
      expect(init.method).toBe('put');
      expect(JSON.parse(init.body)).toEqual([{ pid: 'pid-1', name: 'Updated' }]);
    });
  });

  describe('del', () => {
    it('should make DELETE request with query params (not body)', async () => {
      mockFetchSuccess();

      await del('/api/user/{userId}', { userId: 123, reason: 'inactive' });

      const [url, init] = (globalThis.fetch as any).mock.calls[0];
      expect(url).toContain('/api/user/123');
      expect(url).toContain('reason=inactive');
      expect(init.method).toBe('delete');
      expect(init.body).toBeUndefined();
    });

    it('should make DELETE request without body when no extra params', async () => {
      mockFetchSuccess();

      await del('/api/user/{userId}', { userId: 123 });

      const [, init] = (globalThis.fetch as any).mock.calls[0];
      expect(init.method).toBe('delete');
      expect(init.body).toBeUndefined();
    });

    it('should make DELETE request with array body for batch endpoints', async () => {
      mockFetchSuccess();

      await del('/api/dynamic/page_schema/batch', ['pid-1', 'pid-2']);

      const [url, init] = (globalThis.fetch as any).mock.calls[0];
      expect(url).toContain('/api/dynamic/page_schema/batch');
      expect(url).not.toContain('?0=');
      expect(init.method).toBe('delete');
      expect(JSON.parse(init.body)).toEqual(['pid-1', 'pid-2']);
    });
  });

  describe('patch', () => {
    it('should make PATCH request with body', async () => {
      mockFetchSuccess({ id: 1 });

      const result = await patch('/api/user/{userId}', {
        userId: 1,
        name: 'Patched',
      });

      expect(result.success).toBe(true);
      const [url, init] = (globalThis.fetch as any).mock.calls[0];
      expect(url).toContain('/api/user/1');
      expect(init.method).toBe('patch');
      expect(JSON.parse(init.body)).toEqual({ name: 'Patched' });
    });
  });
});
