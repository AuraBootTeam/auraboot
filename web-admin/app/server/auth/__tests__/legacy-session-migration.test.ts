import { describe, expect, it, vi } from 'vitest';
import type { NextFunction, Request, Response } from 'express';
import { handleLegacySessionMigration } from '../legacy-session-migration';

function request(origin = 'https://app.example.com'): Request {
  const headers: Record<string, string> = {
    origin,
    host: 'app.example.com',
    authorization: 'Bearer legacy.jwt.value',
    'x-aura-remember': 'true',
  };
  return {
    protocol: 'https',
    get: vi.fn((name: string) => headers[name.toLowerCase()]),
  } as unknown as Request;
}

function response() {
  const end = vi.fn();
  const json = vi.fn();
  const status = vi.fn(() => ({ end, json }));
  const setHeader = vi.fn();
  return {
    value: { status, setHeader } as unknown as Response,
    status,
    setHeader,
    end,
    json,
  };
}

describe('legacy session migration endpoint', () => {
  it('validates the old token before committing a server-owned session', async () => {
    const req = request();
    const res = response();
    const commitSession = vi.fn().mockResolvedValue(undefined);
    const fetchImpl = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    const next = vi.fn() as NextFunction;

    await handleLegacySessionMigration(req, res.value, next, {
      backendUrl: 'http://backend.internal',
      commitSession,
      fetchImpl,
    });

    expect(fetchImpl).toHaveBeenCalledWith('http://backend.internal/api/auth/me', {
      headers: { Authorization: 'Bearer legacy.jwt.value' },
    });
    expect(commitSession).toHaveBeenCalledWith(req, res.value, 'legacy.jwt.value', true);
    expect(res.setHeader).toHaveBeenCalledWith('Cache-Control', 'no-store');
    expect(res.status).toHaveBeenCalledWith(204);
    expect(next).not.toHaveBeenCalled();
  });

  it('rejects cross-origin and invalid-token exchanges without setting a cookie', async () => {
    const commitSession = vi.fn();
    const crossOriginResponse = response();
    await handleLegacySessionMigration(
      request('https://evil.example.com'),
      crossOriginResponse.value,
      vi.fn(),
      {
        backendUrl: 'http://backend.internal',
        commitSession,
        fetchImpl: vi.fn(),
      },
    );
    expect(crossOriginResponse.status).toHaveBeenCalledWith(403);

    const invalidResponse = response();
    await handleLegacySessionMigration(request(), invalidResponse.value, vi.fn(), {
      backendUrl: 'http://backend.internal',
      commitSession,
      fetchImpl: vi.fn().mockResolvedValue(new Response(null, { status: 401 })),
    });
    expect(invalidResponse.status).toHaveBeenCalledWith(401);
    expect(commitSession).not.toHaveBeenCalled();
  });
});
