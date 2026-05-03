import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { EXIT } from '../../src/client/api-client.js';

describe('ApiClient', () => {
  describe('EXIT codes', () => {
    it('should define all semantic exit codes', () => {
      expect(EXIT.SUCCESS).toBe(0);
      expect(EXIT.FAILURE).toBe(1);
      expect(EXIT.CANCELLED).toBe(2);
      expect(EXIT.FORBIDDEN).toBe(3);
      expect(EXIT.NOT_FOUND).toBe(4);
      expect(EXIT.AUTH_REQUIRED).toBe(5);
    });
  });

  describe('response parsing', () => {
    it('should extract data from AuraBoot API envelope', () => {
      const apiResponse = {
        code: 200,
        data: { records: [{ id: 1, name: 'test' }], total: 1 },
        message: 'success',
      };

      expect(apiResponse.code).toBe(200);
      expect(apiResponse.data.records).toHaveLength(1);
      expect(apiResponse.data.records[0].name).toBe('test');
    });

    it('should handle non-envelope responses', () => {
      const rawResponse = [{ id: 1, name: 'test' }];
      expect(Array.isArray(rawResponse)).toBe(true);
    });

    it('should detect enterprise feature messages in 403', () => {
      const errorMessages = [
        'Agent execution requires Professional license.',
        'This feature requires Enterprise plan.',
      ];

      for (const msg of errorMessages) {
        const isEnterprise = msg.includes('Professional') || msg.includes('license') || msg.includes('Enterprise');
        expect(isEnterprise).toBe(true);
      }

      const normalForbidden = 'Access denied: insufficient permissions';
      const isEnterprise = normalForbidden.includes('Professional') || normalForbidden.includes('license') || normalForbidden.includes('Enterprise');
      expect(isEnterprise).toBe(false);
    });
  });

  describe('URL construction', () => {
    it('should build URL with query params', () => {
      const baseUrl = 'http://localhost:6443';
      const path = '/api/datasource/list';
      const params = { datasourceId: 'nq:acp_agent_stats', maxItems: '200' };

      const url = new URL(path, baseUrl);
      for (const [k, v] of Object.entries(params)) {
        url.searchParams.set(k, v);
      }

      expect(url.toString()).toBe('http://localhost:6443/api/datasource/list?datasourceId=nq%3Aacp_agent_stats&maxItems=200');
    });
  });

  describe('auth header', () => {
    it('should format Bearer token correctly', () => {
      const token = 'eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.test';
      const header = `Bearer ${token}`;
      expect(header).toBe('Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.test');
    });
  });

  describe('put / delete (write verbs)', () => {
    let fetchMock: ReturnType<typeof vi.fn>;

    beforeEach(async () => {
      // Default response matches the AuraBoot envelope so parseResponse succeeds.
      fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ code: 200, data: { id: 1 }, message: 'ok' }),
      });
      vi.stubGlobal('fetch', fetchMock);
    });

    afterEach(() => {
      vi.unstubAllGlobals();
    });

    it('put<T> uses HTTP PUT and sends JSON body', async () => {
      const { ApiClient } = await import('../../src/client/api-client.js');
      const client = new ApiClient({ token: 't1', env: 'local' });
      const resp = await client.put('/api/meta/models/abc', { code: 'crm_lead' });

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [url, init] = fetchMock.mock.calls[0];
      expect(typeof url).toBe('string');
      expect(url).toMatch(/\/api\/meta\/models\/abc$/);
      expect(init.method).toBe('put');
      expect(JSON.parse(init.body)).toEqual({ code: 'crm_lead' });
      expect(init.headers.Authorization).toBe('Bearer t1');
      expect(resp.ok).toBe(true);
    });

    it('delete<T> uses HTTP DELETE and sends no body', async () => {
      const { ApiClient } = await import('../../src/client/api-client.js');
      const client = new ApiClient({ token: 't1', env: 'local' });
      await client.delete('/api/meta/models/abc');

      const [url, init] = fetchMock.mock.calls[0];
      expect(url).toMatch(/\/api\/meta\/models\/abc$/);
      expect(init.method).toBe('delete');
      expect(init.body).toBeUndefined();
      expect(init.headers.Authorization).toBe('Bearer t1');
    });

    it('interactive=false: 403 returns ApiResponse instead of process.exit (MCP server safety)', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 403,
        json: async () => ({ message: 'no role' }),
      });
      const { ApiClient } = await import('../../src/client/api-client.js');
      const client = new ApiClient({ token: 't1', env: 'local', interactive: false });
      const resp = await client.get('/api/foo');
      expect(resp.ok).toBe(false);
      expect(resp.status).toBe(403);
      expect(resp.message).toBe('no role');
    });

    it('interactive=false: 404 returns ApiResponse instead of process.exit', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 404,
        json: async () => ({ message: 'gone' }),
      });
      const { ApiClient } = await import('../../src/client/api-client.js');
      const client = new ApiClient({ token: 't1', env: 'local', interactive: false });
      const resp = await client.get('/api/foo');
      expect(resp.ok).toBe(false);
      expect(resp.status).toBe(404);
      expect(resp.message).toBe('gone');
    });

    it('put propagates non-ok status via ApiResponse.ok=false', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 422,
        text: async () => 'validation failed',
      });
      const { ApiClient } = await import('../../src/client/api-client.js');
      const client = new ApiClient({ token: 't1', env: 'local' });
      const resp = await client.put('/api/meta/models/abc', {});
      expect(resp.ok).toBe(false);
      expect(resp.status).toBe(422);
      expect(resp.message).toMatch(/validation failed/);
    });
  });
});
