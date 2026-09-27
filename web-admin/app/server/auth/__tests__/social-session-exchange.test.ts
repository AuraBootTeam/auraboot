import { describe, expect, it, vi } from 'vitest';
import type { NextFunction, Request, Response } from 'express';
import { handleSocialSessionExchange } from '../social-session-exchange';

function response() {
  const json = vi.fn();
  const status = vi.fn(() => ({ json }));
  const setHeader = vi.fn();
  return {
    value: { status, setHeader } as unknown as Response,
    status,
    setHeader,
    json,
  };
}

describe('social login session exchange', () => {
  it('commits the backend JWT without returning it to browser JavaScript', async () => {
    const req = {
      originalUrl: '/api/auth/login/social/oidc/callback?code=code&state=state',
      body: {},
    } as Request;
    const res = response();
    const commitSession = vi.fn().mockResolvedValue(undefined);
    const fetchImpl = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: '0',
      data: { jwt: 'backend.jwt.value', tenantId: '42', mergeRequired: false },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));

    await handleSocialSessionExchange(req, res.value, vi.fn() as NextFunction, {
      backendUrl: 'http://backend.internal',
      commitSession,
      fetchImpl,
    });

    expect(fetchImpl).toHaveBeenCalledWith(
      'http://backend.internal/api/auth/login/social/oidc/callback?code=code&state=state',
      expect.objectContaining({ method: 'POST', body: undefined }),
    );
    expect(commitSession).toHaveBeenCalledWith(req, res.value, 'backend.jwt.value', true);
    expect(res.json).toHaveBeenCalledWith(expect.objectContaining({
      data: expect.objectContaining({ jwt: null, sessionEstablished: true }),
    }));
    expect(JSON.stringify(res.json.mock.calls)).not.toContain('backend.jwt.value');
  });

  it('passes merge challenges through without creating a session', async () => {
    const req = {
      originalUrl: '/api/auth/login/social/confirm-merge',
      body: { mergeToken: 'merge-ticket', password: 'password' },
    } as Request;
    const res = response();
    const commitSession = vi.fn();
    const fetchImpl = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: '0',
      data: { jwt: null, mergeRequired: true, mergeToken: 'merge-ticket' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));

    await handleSocialSessionExchange(req, res.value, vi.fn() as NextFunction, {
      backendUrl: 'http://backend.internal',
      commitSession,
      fetchImpl,
    });

    expect(fetchImpl).toHaveBeenCalledWith(
      'http://backend.internal/api/auth/login/social/confirm-merge',
      expect.objectContaining({ body: JSON.stringify(req.body) }),
    );
    expect(commitSession).not.toHaveBeenCalled();
    expect(res.json).toHaveBeenCalledWith(expect.objectContaining({
      data: expect.objectContaining({ mergeRequired: true }),
    }));
  });

  it('redacts an unexpected JWT from an upstream error without creating a session', async () => {
    const req = {
      originalUrl: '/api/auth/login/social/oidc/callback?code=bad&state=state',
      body: {},
    } as Request;
    const res = response();
    const commitSession = vi.fn();
    const fetchImpl = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'social_exchange_failed',
      data: { jwt: 'must.not.reach.browser' },
    }), { status: 401, headers: { 'Content-Type': 'application/json' } }));

    await handleSocialSessionExchange(req, res.value, vi.fn() as NextFunction, {
      backendUrl: 'http://backend.internal',
      commitSession,
      fetchImpl,
    });

    expect(commitSession).not.toHaveBeenCalled();
    expect(res.status).toHaveBeenCalledWith(401);
    expect(JSON.stringify(res.json.mock.calls)).not.toContain('must.not.reach.browser');
    expect(res.json).toHaveBeenCalledWith(expect.objectContaining({
      data: expect.objectContaining({ jwt: null }),
    }));
  });
});
