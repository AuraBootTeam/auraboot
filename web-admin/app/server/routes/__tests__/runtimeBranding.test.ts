// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import express from 'express';
import type { ErrorRequestHandler } from 'express';
import { once } from 'node:events';

const mocks = vi.hoisted(() => ({ config: vi.fn(), debug: vi.fn(), error: vi.fn() }));
vi.mock('~/config/branding.server', () => ({ resolveDeploymentBranding: mocks.config }));
vi.mock('~/server/utils/logger', () => ({ default: { debug: mocks.debug, error: mocks.error } }));
import { AUTH_APPEARANCE_TIMEOUT_MS, createRuntimeBrandingHandler } from '../runtimeBranding';

describe('runtime branding HTTP boundary', () => {
  let servers: Server[];
  let url: string;
  let backendMode: 'published' | 'invalid' | 'unavailable' | 'blocked';
  let requests: string[];
  let reached: Promise<void>;
  let markReached: () => void;
  let closed: Promise<void>;
  let markClosed: () => void;
  const appearance = { version: 1, template: 'split', defaultLocale: 'zh-CN' };
  const listen = async (server: Server) => {
    servers.push(server);
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  };
  beforeEach(async () => {
    vi.clearAllMocks();
    servers = [];
    requests = [];
    backendMode = 'published';
    reached = new Promise((resolve) => {
      markReached = resolve;
    });
    closed = new Promise((resolve) => {
      markClosed = resolve;
    });
    mocks.config.mockImplementation(async () => ({
      mode: 'commercial',
      productName: 'Verified brand',
    }));
    const backend = await listen(
      createServer((req, res) => {
        requests.push(String(req.headers['x-request-id']));
        markReached();
        res.on('close', markClosed);
        if (backendMode === 'blocked') return;
        res.setHeader('Content-Type', 'application/json');
        res.statusCode = backendMode === 'unavailable' ? 503 : 200;
        res.end(
          JSON.stringify(
            backendMode === 'invalid' ? { code: 'error' } : { code: '0', data: { appearance } },
          ),
        );
      }),
    );
    const app = express();
    app.get('/api/runtime/branding', createRuntimeBrandingHandler(backend, {}));
    const errors: ErrorRequestHandler = (error, _req, res, _next) =>
      res.status(error.status || 500).json({ code: error.code || 'error' });
    app.use(errors);
    url = await listen(createServer(app));
  });
  afterEach(async () => {
    await Promise.all(
      servers.map(
        (server) =>
          new Promise<void>((resolve) => {
            server.closeAllConnections();
            server.close(() => resolve());
          }),
      ),
    );
  });
  it('serves base branding without calling a blocked appearance backend', async () => {
    backendMode = 'blocked';
    const response = await fetch(`${url}/api/runtime/branding`);
    expect(response.status).toBe(200);
    expect((await response.json()).branding).toEqual({
      mode: 'commercial',
      productName: 'Verified brand',
    });
    expect(requests).toEqual([]);
  });
  it('loads current published appearance only when explicitly requested and forwards request identity', async () => {
    const response = await fetch(`${url}/api/runtime/branding?include=auth-appearance`, {
      headers: { 'X-Request-Id': 'branding-proof' },
    });
    expect(response.status).toBe(200);
    expect(response.headers.get('cache-control')).toBe('no-store');
    expect((await response.json()).branding.authAppearance).toEqual(appearance);
    expect(requests).toEqual(['branding-proof']);
    expect(mocks.debug.mock.calls.map(([fields]) => fields.stage)).toEqual([
      'deployment-config',
      'appearance-fetch',
      'appearance-validation',
      'serialize',
      'request',
    ]);
  });
  it('does not contact the backend for community branding even with appearance requested', async () => {
    mocks.config.mockResolvedValue({ mode: 'community' });
    expect((await fetch(`${url}/api/runtime/branding?include=auth-appearance`)).status).toBe(200);
    expect(requests).toEqual([]);
  });
  it('keeps invalid appearance contracts visible as failures rather than falling back', async () => {
    backendMode = 'invalid';
    expect((await fetch(`${url}/api/runtime/branding?include=auth-appearance`)).status).toBe(500);
    expect(mocks.error).toHaveBeenCalledWith(
      expect.objectContaining({ stage: 'appearance-validation', outcome: 'failure' }),
      expect.anything(),
    );
  });
  it('classifies an upstream outage as a typed temporary failure', async () => {
    backendMode = 'unavailable';
    const response = await fetch(`${url}/api/runtime/branding?include=auth-appearance`);
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ code: 'root_dependency_unavailable' });
  });
  it('terminates a blocked inner read before the outer ten-second deadline and records the exact stage', async () => {
    backendMode = 'blocked';
    const started = performance.now();
    const response = await fetch(`${url}/api/runtime/branding?include=auth-appearance`);
    const elapsed = performance.now() - started;
    expect(response.status).toBe(503);
    expect(elapsed).toBeGreaterThanOrEqual(AUTH_APPEARANCE_TIMEOUT_MS - 100);
    expect(elapsed).toBeLessThan(10_000);
    await closed;
    expect(mocks.error).toHaveBeenCalledWith(
      expect.objectContaining({
        stage: 'appearance-fetch',
        durationMs: expect.any(Number),
        exception: expect.objectContaining({ name: 'AxiosError' }),
      }),
      expect.anything(),
    );
  });
  it('cancels the pending backend read when the caller disconnects', async () => {
    backendMode = 'blocked';
    const controller = new AbortController();
    const pending = fetch(`${url}/api/runtime/branding?include=auth-appearance`, {
      signal: controller.signal,
    });
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    await reached;
    controller.abort();
    await rejected;
    await closed;
    expect(mocks.error).toHaveBeenCalledWith(
      expect.objectContaining({
        stage: 'appearance-fetch',
        exception: expect.objectContaining({ name: 'CanceledError' }),
      }),
      expect.anything(),
    );
  });
});
