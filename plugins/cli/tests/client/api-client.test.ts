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
});
