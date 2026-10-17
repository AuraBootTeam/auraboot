// @vitest-environment node
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import express from 'express';
import type { ErrorRequestHandler } from 'express';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { once } from 'node:events';
const mocks = vi.hoisted(() => ({
  token: vi.fn(),
  impersonation: vi.fn(),
  renew: vi.fn(),
  destroy: vi.fn(),
  debug: vi.fn(),
  error: vi.fn(),
}));
vi.mock('~/shared/services/session', () => ({
  getTokenFromRequest: mocks.token,
  getImpersonationFromRequest: mocks.impersonation,
  maybeRenewSession: mocks.renew,
  sessionStorage: { getSession: vi.fn(async () => ({})), destroySession: mocks.destroy },
}));
vi.mock('~/server/utils/logger', () => ({ default: { debug: mocks.debug, error: mocks.error } }));
import { createSessionContextHandler } from '../sessionContext';
describe('session context HTTP boundary', () => {
  let servers: Server[], url: string, requests: string[], mode: string;
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
    mode = 'success';
    mocks.token.mockResolvedValue('server-only-jwt');
    mocks.impersonation.mockResolvedValue(null);
    mocks.renew.mockResolvedValue({ renewed: true, setCookie: '__session=fixture; HttpOnly' });
    mocks.destroy.mockResolvedValue('__session=; Max-Age=0');
    const backend = await listen(
      createServer((req, res) => {
        requests.push(req.url || '');
        expect(req.headers.authorization).toBe('Bearer server-only-jwt');
        if (mode === 'blocked') return;
        res.setHeader('Content-Type', 'application/json');
        if (mode === '401' || mode === '403' || mode === '503') {
          res.statusCode = Number(mode);
          res.end('{}');
          return;
        }
        if (mode === 'invalid') {
          res.end(JSON.stringify({ code: '0', data: {} }));
          return;
        }
        res.end(
          JSON.stringify({
            code: '0',
            data:
              req.url === '/api/auth/me'
                ? {
                    user: { id: 'user-one', tenantId: 'school-one', jwt: 'must-not-reach-browser' },
                    permissions: { permissionCodes: ['read'], roles: [] },
                    preferences: null,
                  }
                : [
                    {
                      id: 1,
                      pid: 'menu-one',
                      path: '/home',
                      name: 'Home',
                      visible: true,
                      status: 'active',
                      orderNo: 1,
                    },
                  ],
          }),
        );
      }),
    );
    const app = express();
    app.get('/api/runtime/session-context', createSessionContextHandler(backend));
    const errors: ErrorRequestHandler = (_error, _req, res, _next) => {
      res.status(500).json({ code: 'invalid_context' });
    };
    app.use(errors);
    url = `${await listen(createServer(app))}/api/runtime/session-context`;
  });
  afterEach(async () => {
    await Promise.all(
      servers.map(
        (s) =>
          new Promise<void>((resolve) => {
            s.closeAllConnections();
            s.close(() => resolve());
          }),
      ),
    );
  });
  it('returns only fresh session fields and renews the HTTP-only cookie without branding or business reads', async () => {
    const response = await fetch(url);
    const body = await response.json();
    expect(response.status).toBe(200);
    expect(response.headers.get('set-cookie')).toContain('HttpOnly');
    expect(response.headers.get('cache-control')).toBe('no-store');
    expect(Object.keys(body.data).sort()).toEqual(['menus', 'permissions', 'preferences', 'user']);
    expect(body.data.user.jwt).toBeUndefined();
    expect(requests).toEqual(['/api/auth/me', '/api/menu/user']);
    expect(mocks.renew).toHaveBeenCalledTimes(1);
  });
  it('clears the cookie after an authoritative rejection', async () => {
    mode = '401';
    const response = await fetch(url);
    expect(response.status).toBe(401);
    expect(response.headers.get('set-cookie')).toContain('Max-Age=0');
    expect(mocks.renew).not.toHaveBeenCalled();
  });
  it('does not renew or disclose data when the user is forbidden', async () => {
    mode = '403';
    const response = await fetch(url);
    expect(response.status).toBe(403);
    expect(mocks.renew).not.toHaveBeenCalled();
    expect(requests).toEqual(['/api/auth/me']);
  });
  it('requires an existing session and refuses impersonated maintenance', async () => {
    mocks.token.mockResolvedValueOnce(null);
    expect((await fetch(url)).status).toBe(401);
    mocks.impersonation.mockResolvedValue({ sessionPid: 'operator' });
    expect((await fetch(url)).status).toBe(409);
    expect(requests).toEqual([]);
  });
  it('returns a typed temporary response for upstream service unavailability', async () => {
    mode = '503';
    const response = await fetch(url);
    expect(response.status).toBe(503);
    expect(await response.json()).toMatchObject({
      code: 'session_context_unavailable',
      stage: 'identity',
    });
    expect(mocks.renew).not.toHaveBeenCalled();
  });
  it('keeps invalid successful payloads visible as configuration failures', async () => {
    mode = 'invalid';
    expect((await fetch(url)).status).toBe(500);
    expect(mocks.renew).not.toHaveBeenCalled();
  });
  it('bounds a hung backend read and retains sanitized stage diagnostics', async () => {
    mode = 'blocked';
    const start = performance.now();
    const response = await fetch(url);
    expect(response.status).toBe(503);
    expect(performance.now() - start).toBeLessThan(10000);
    expect(await response.json()).toMatchObject({
      code: 'session_context_unavailable',
      stage: 'identity',
    });
    expect(mocks.error).toHaveBeenCalledWith(
      expect.objectContaining({
        stage: 'identity',
        exception: expect.objectContaining({ name: 'TimeoutError' }),
      }),
      expect.anything(),
    );
  });
});
