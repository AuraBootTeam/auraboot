/**
 * Unit tests for FlowDesignerApiClient
 * Mocks ProxyApiClient.proxyRequest so no real HTTP is made.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';

// ── Hoist ─────────────────────────────────────────────────────────────────
const { proxyRequestMock } = vi.hoisted(() => ({
  proxyRequestMock: vi.fn(),
}));

vi.mock('~/server/clients/ProxyApiClient', () => {
  // ProxyApiClient has a complex constructor (axios, HealthCheckService, interceptors).
  // Return a class whose constructor is a no-op and injects our mock proxyRequest.
  class MockProxyApiClient {
    proxyRequest = proxyRequestMock;
    checkHealth = vi.fn();
    getClientInfo = vi.fn();
    updateRetryConfig = vi.fn();
  }
  return { ProxyApiClient: MockProxyApiClient };
});

vi.mock('~/server/utils/logger', () => ({
  default: { info: vi.fn(), error: vi.fn(), warn: vi.fn() },
}));

vi.mock('~/server/utils/config', () => ({
  config: {
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
        retryableStatusCodes: [502, 503],
        retryableErrors: ['ECONNRESET'],
      },
      healthCheck: {
        cacheTtl: 30000,
        timeout: 5000,
        retries: 2,
        endpoints: [],
      },
    },
    springBoot: { url: 'http://localhost:8080' },
  },
}));

import { FlowDesignerApiClient } from '../FlowDesignerApiClient';
import type { FlowData } from '../FlowDesignerApiClient';

const BASE = '/api/flow-designer';

function makeAxiosResponse(status: number, data: any) {
  return { status, data } as any;
}

function makeFlow(overrides: Partial<FlowData> = {}): FlowData {
  return {
    name: 'Test Flow',
    nodes: [],
    edges: [],
    status: 'draft',
    layoutMode: 'free',
    gridConfig: { columns: 3, rowGap: 20, columnGap: 20 },
    ...overrides,
  };
}

describe('FlowDesignerApiClient', () => {
  let client: FlowDesignerApiClient;

  beforeEach(() => {
    client = new FlowDesignerApiClient();
    vi.clearAllMocks();
  });

  // ── saveFlow ──────────────────────────────────────────────────────────────

  describe('saveFlow', () => {
    it('POSTs to /api/flow-designer/flows and returns success on 201', async () => {
      const flowData = makeFlow();
      const savedFlow = { ...flowData, id: 'new-id' };
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(201, savedFlow));

      const result = await client.saveFlow(flowData);

      expect(proxyRequestMock).toHaveBeenCalledWith(
        expect.objectContaining({
          method: 'post',
          path: `${BASE}/flows`,
          data: flowData,
        }),
      );
      expect(result.success).toBe(true);
      expect(result.data).toEqual(savedFlow);
    });

    it('returns success:false for non-2xx response', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(409, { message: 'Conflict' }));

      const result = await client.saveFlow(makeFlow());

      expect(result.success).toBe(false);
      expect(result.message).toBe('Conflict');
    });

    it('returns success:false with fallback message when response data has no message', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(500, {}));

      const result = await client.saveFlow(makeFlow());

      expect(result.success).toBe(false);
      expect(result.message).toBe('Failed to save flow');
    });

    it('returns success:false on network error', async () => {
      proxyRequestMock.mockRejectedValue(new Error('ECONNREFUSED'));

      const result = await client.saveFlow(makeFlow());

      expect(result.success).toBe(false);
      expect(result.message).toBe('ECONNREFUSED');
    });
  });

  // ── updateFlow ────────────────────────────────────────────────────────────

  describe('updateFlow', () => {
    it('PUTs to /api/flow-designer/flows/:id and returns success on 200', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(200, { id: 'abc', name: 'Updated' }));

      const result = await client.updateFlow('abc', { name: 'Updated' });

      expect(proxyRequestMock).toHaveBeenCalledWith(
        expect.objectContaining({
          method: 'put',
          path: `${BASE}/flows/abc`,
        }),
      );
      expect(result.success).toBe(true);
    });

    it('returns success:false for non-2xx response', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(404, { message: 'Not found' }));

      const result = await client.updateFlow('abc', {});

      expect(result.success).toBe(false);
    });

    it('returns success:false on exception', async () => {
      proxyRequestMock.mockRejectedValue(new Error('timeout'));

      const result = await client.updateFlow('abc', {});

      expect(result.success).toBe(false);
      expect(result.message).toBe('timeout');
    });
  });

  // ── getFlow ───────────────────────────────────────────────────────────────

  describe('getFlow', () => {
    it('GETs /api/flow-designer/flows/:id and returns data on 200', async () => {
      const flow = makeFlow({ id: 'xyz' });
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(200, flow));

      const result = await client.getFlow('xyz');

      expect(proxyRequestMock).toHaveBeenCalledWith(
        expect.objectContaining({ method: 'get', path: `${BASE}/flows/xyz` }),
      );
      expect(result.success).toBe(true);
      expect(result.data).toEqual(flow);
    });

    it('returns "Flow not found" message on 404', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(404, {}));

      const result = await client.getFlow('xyz');

      expect(result.success).toBe(false);
      expect(result.message).toBe('Flow not found');
    });

    it('returns success:false for other non-200 status', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(403, { message: 'Forbidden' }));

      const result = await client.getFlow('xyz');

      expect(result.success).toBe(false);
      expect(result.message).toBe('Forbidden');
    });
  });

  // ── getFlowList ───────────────────────────────────────────────────────────

  describe('getFlowList', () => {
    it('GETs base path when query is empty', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(200, { records: [], total: 0 }));

      await client.getFlowList({});

      const call = proxyRequestMock.mock.calls[0][0];
      expect(call.method).toBe('get');
      expect(call.path).toBe(`${BASE}/flows`);
    });

    it('appends query params when provided', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(200, { records: [], total: 0 }));

      await client.getFlowList({ page: 1, size: 10, name: 'test', status: 'draft', createdBy: 'u1' });

      const call = proxyRequestMock.mock.calls[0][0];
      expect(call.path).toContain('page=1');
      expect(call.path).toContain('size=10');
      expect(call.path).toContain('name=test');
      expect(call.path).toContain('status=draft');
      expect(call.path).toContain('createdBy=u1');
    });

    it('returns success:false on non-200', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(500, { message: 'Server error' }));

      const result = await client.getFlowList({});

      expect(result.success).toBe(false);
    });
  });

  // ── deleteFlow ────────────────────────────────────────────────────────────

  describe('deleteFlow', () => {
    it('DELETEs /api/flow-designer/flows/:id and returns success on 200', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(200, {}));

      const result = await client.deleteFlow('abc');

      expect(proxyRequestMock).toHaveBeenCalledWith(
        expect.objectContaining({ method: 'delete', path: `${BASE}/flows/abc` }),
      );
      expect(result.success).toBe(true);
    });

    it('returns success:false for non-2xx', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(404, { message: 'Not found' }));

      const result = await client.deleteFlow('abc');

      expect(result.success).toBe(false);
    });
  });

  // ── publishFlow ───────────────────────────────────────────────────────────

  describe('publishFlow', () => {
    it('delegates to updateFlow with status=published', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(200, { id: 'abc', status: 'published' }));

      const result = await client.publishFlow('abc');

      const call = proxyRequestMock.mock.calls[0][0];
      expect(call.method).toBe('put');
      expect(call.path).toBe(`${BASE}/flows/abc`);
      expect(call.data).toMatchObject({ status: 'published' });
      expect(result.success).toBe(true);
    });
  });

  // ── duplicateFlow ─────────────────────────────────────────────────────────

  describe('duplicateFlow', () => {
    it('POSTs to /flows/:id/duplicate with name', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(201, { id: 'dup-id', name: 'Copy' }));

      const result = await client.duplicateFlow('abc', 'Copy');

      expect(proxyRequestMock).toHaveBeenCalledWith(
        expect.objectContaining({
          method: 'post',
          path: `${BASE}/flows/abc/duplicate`,
          data: { name: 'Copy' },
        }),
      );
      expect(result.success).toBe(true);
    });

    it('returns success:false on non-2xx', async () => {
      proxyRequestMock.mockResolvedValue(makeAxiosResponse(400, { message: 'Bad request' }));

      const result = await client.duplicateFlow('abc', 'Copy');

      expect(result.success).toBe(false);
    });
  });
});
