/**
 * Unit tests for HealthCheckService
 * Tests cache, overall-status calculation, and endpoint-check logic
 * by injecting a mock Axios instance.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';

vi.mock('~/server/utils/logger', () => ({
  default: {
    info: vi.fn(),
    error: vi.fn(),
    warn: vi.fn(),
    health: vi.fn(),
  },
}));

vi.mock('~/server/utils/config', () => ({
  config: {
    springBoot: { url: 'http://localhost:8080' },
    proxy: {
      baseUrl: 'http://localhost:8080',
      timeout: 30000,
      retry: {
        retries: 3,
        retryDelay: 1000,
        maxDelay: 10000,
        backoffMultiplier: 2,
        jitterEnabled: false,
        exponentialBackoff: true,
        retryableStatusCodes: [],
        retryableErrors: [],
      },
      healthCheck: {
        cacheTtl: 30000,
        timeout: 5000,
        retries: 2,
        endpoints: [],
      },
    },
  },
}));

import { HealthCheckService } from '../HealthCheckService';

function makeMockHttpClient(statusOverride?: number, rejectWith?: Error) {
  const requestMock = vi.fn();
  if (rejectWith) {
    requestMock.mockRejectedValue(rejectWith);
  } else {
    requestMock.mockResolvedValue({
      status: statusOverride ?? 200,
      headers: {},
      data: { status: 'UP' },
    });
  }
  return { request: requestMock } as any;
}

describe('HealthCheckService', () => {
  // ── cache ─────────────────────────────────────────────────────────────────

  describe('cache management', () => {
    it('getCacheStats returns size 0 initially', () => {
      const svc = new HealthCheckService(makeMockHttpClient(), { endpoints: [], cacheTtl: 5000, timeout: 1000, retries: 0 });
      expect(svc.getCacheStats()).toEqual({ size: 0, keys: [] });
    });

    it('clearCache removes all entries', async () => {
      const http = makeMockHttpClient();
      const svc = new HealthCheckService(http, { endpoints: [], cacheTtl: 60000, timeout: 1000, retries: 0 });

      // Populate cache with one full check
      await svc.performHealthCheck(true);
      expect(svc.getCacheStats().size).toBeGreaterThan(0);

      svc.clearCache();
      expect(svc.getCacheStats()).toEqual({ size: 0, keys: [] });
    });

    it('returns cached result on second call (request not repeated)', async () => {
      const http = makeMockHttpClient();
      const svc = new HealthCheckService(http, {
        cacheTtl: 60000,
        timeout: 1000,
        retries: 0,
        endpoints: [{ name: 'ep1', url: 'http://x/health', expectedStatus: [200] }],
      });

      await svc.performHealthCheck(true);
      const callCount1 = http.request.mock.calls.length;

      await svc.performHealthCheck(true); // should hit cache
      expect(http.request.mock.calls.length).toBe(callCount1); // no additional calls
    });

    it('bypasses cache when useCache is false', async () => {
      const http = makeMockHttpClient();
      const svc = new HealthCheckService(http, {
        cacheTtl: 60000,
        timeout: 1000,
        retries: 0,
        endpoints: [{ name: 'ep1', url: 'http://x/health', expectedStatus: [200] }],
      });

      await svc.performHealthCheck(false);
      const callCount1 = http.request.mock.calls.length;

      await svc.performHealthCheck(false);
      expect(http.request.mock.calls.length).toBeGreaterThan(callCount1);
    });
  });

  // ── overall status calculation ─────────────────────────────────────────────

  describe('overall status', () => {
    it('returns healthy when all endpoint checks are "up"', async () => {
      const http = makeMockHttpClient(200); // all return 200 → up
      const svc = new HealthCheckService(http, {
        cacheTtl: 0, // disable cache so each call is fresh
        timeout: 1000,
        retries: 0,
        endpoints: [{ name: 'actuator', url: 'http://x/health', expectedStatus: [200] }],
      });

      const result = await svc.performHealthCheck(false);
      expect(result.status).toBe('healthy');
      expect(result.checks['actuator'].status).toBe('up');
    });

    it('returns unhealthy when an endpoint throws (status down)', async () => {
      const http = makeMockHttpClient(undefined, new Error('ECONNREFUSED'));
      const svc = new HealthCheckService(http, {
        cacheTtl: 0,
        timeout: 1000,
        retries: 0,
        endpoints: [{ name: 'actuator', url: 'http://x/health', expectedStatus: [200] }],
      });

      const result = await svc.performHealthCheck(false);
      expect(result.status).toBe('unhealthy');
      expect(result.checks['actuator'].status).toBe('down');
    });

    it('returns degraded when an endpoint returns unexpected status (warning)', async () => {
      const http = makeMockHttpClient(503); // 503 not in [200] → warning
      const svc = new HealthCheckService(http, {
        cacheTtl: 0,
        timeout: 1000,
        retries: 0,
        endpoints: [{ name: 'actuator', url: 'http://x/health', expectedStatus: [200] }],
      });

      const result = await svc.performHealthCheck(false);
      expect(result.status).toBe('degraded');
      expect(result.checks['actuator'].status).toBe('warning');
      expect(result.checks['actuator'].message).toMatch(/503/);
    });

    it('result includes bff-self and system-resources checks', async () => {
      const http = makeMockHttpClient(200);
      const svc = new HealthCheckService(http, {
        cacheTtl: 0,
        timeout: 1000,
        retries: 0,
        endpoints: [],
      });

      const result = await svc.performHealthCheck(false);
      expect(result.checks).toHaveProperty('bff-self');
      expect(result.checks).toHaveProperty('system-resources');
      expect(result.checks['bff-self'].status).toBe('up');
    });

    it('result has a timestamp and non-negative duration', async () => {
      const http = makeMockHttpClient(200);
      const svc = new HealthCheckService(http, {
        cacheTtl: 0,
        timeout: 1000,
        retries: 0,
        endpoints: [],
      });

      const result = await svc.performHealthCheck(false);
      expect(typeof result.timestamp).toBe('string');
      expect(result.duration).toBeGreaterThanOrEqual(0);
    });
  });
});
