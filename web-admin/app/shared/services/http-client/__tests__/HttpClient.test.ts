import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fetchResult, get, post, put, del, patch } from '../HttpClient';

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
    globalThis.fetch = originalFetch;
    window.sessionStorage.clear();
    window.localStorage.clear();
  });

  describe('fetchResult', () => {
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
  });

  describe('del', () => {
    it('should make DELETE request with body (not query params)', async () => {
      mockFetchSuccess();

      await del('/api/user/{userId}', { userId: 123, reason: 'inactive' });

      const [url, init] = (globalThis.fetch as any).mock.calls[0];
      expect(url).toContain('/api/user/123');
      expect(url).not.toContain('reason=');
      expect(init.method).toBe('delete');
      expect(JSON.parse(init.body)).toEqual({ reason: 'inactive' });
    });

    it('should make DELETE request without body when no extra params', async () => {
      mockFetchSuccess();

      await del('/api/user/{userId}', { userId: 123 });

      const [, init] = (globalThis.fetch as any).mock.calls[0];
      expect(init.method).toBe('delete');
      expect(init.body).toBeUndefined();
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
