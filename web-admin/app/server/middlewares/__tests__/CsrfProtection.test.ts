import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { NextFunction, Request, Response } from 'express';
import { enforceCsrfProtection, hasSameOrigin } from '../CsrfProtection';
import { sessionStorage } from '~/shared/services/session';

vi.mock('~/shared/services/session', () => ({
  sessionStorage: { getSession: vi.fn() },
}));

function request(overrides: Partial<Request> = {}): Request {
  const headers: Record<string, string | undefined> = {
    host: 'app.example.com',
    origin: 'https://app.example.com',
  };
  return {
    method: 'POST',
    protocol: 'https',
    headers,
    get: vi.fn((name: string) => headers[name.toLowerCase()]),
    ...overrides,
  } as unknown as Request;
}

function response() {
  const json = vi.fn();
  const status = vi.fn(() => ({ json }));
  return { value: { status } as unknown as Response, status, json };
}

describe('BFF CSRF protection', () => {
  beforeEach(() => {
    vi.mocked(sessionStorage.getSession).mockReset();
  });

  it('accepts only the exact BFF origin for browser cookie writes', () => {
    expect(hasSameOrigin(request())).toBe(true);
    expect(
      hasSameOrigin(
        request({
          protocol: 'http',
          headers: {
            host: '127.0.0.1:6243',
            origin: 'https://app.example.com',
            'x-forwarded-host': 'app.example.com',
            'x-forwarded-proto': 'https',
          },
          get: vi.fn((name: string) => ({
            host: '127.0.0.1:6243',
            origin: 'https://app.example.com',
            'x-forwarded-host': 'app.example.com',
            'x-forwarded-proto': 'https',
          })[name.toLowerCase()]) as unknown as Request['get'],
        }),
      ),
    ).toBe(true);
    expect(
      hasSameOrigin(
        request({
          get: vi.fn((name: string) =>
            name.toLowerCase() === 'origin' ? 'https://evil.example.com' : 'app.example.com',
          ) as unknown as Request['get'],
        }),
      ),
    ).toBe(false);
  });

  it('rejects a cross-origin mutation authenticated by the session cookie', async () => {
    vi.mocked(sessionStorage.getSession).mockResolvedValue({
      get: vi.fn(() => 'server-owned-jwt'),
    } as never);
    const req = request({
      headers: {
        host: 'app.example.com',
        origin: 'https://evil.example.com',
        cookie: '__session=signed',
      },
      get: vi.fn((name: string) =>
        name.toLowerCase() === 'origin' ? 'https://evil.example.com' : 'app.example.com',
      ) as unknown as Request['get'],
    });
    const res = response();
    const next = vi.fn() as NextFunction;

    await enforceCsrfProtection(req, res.value, next);

    expect(next).not.toHaveBeenCalled();
    expect(res.status).toHaveBeenCalledWith(403);
    expect(res.json).toHaveBeenCalledWith(expect.objectContaining({ code: 'csrf_origin_rejected' }));
  });

  it('allows same-origin cookie writes and explicit bearer clients', async () => {
    vi.mocked(sessionStorage.getSession).mockResolvedValue({
      get: vi.fn(() => 'server-owned-jwt'),
    } as never);
    const next = vi.fn() as NextFunction;
    await enforceCsrfProtection(
      request({ headers: { host: 'app.example.com', origin: 'https://app.example.com', cookie: '__session=signed' } }),
      response().value,
      next,
    );
    expect(next).toHaveBeenCalledTimes(1);

    const bearerNext = vi.fn() as NextFunction;
    await enforceCsrfProtection(
      request({ headers: { authorization: 'Bearer sdk-token' } }),
      response().value,
      bearerNext,
    );
    expect(bearerNext).toHaveBeenCalledTimes(1);
  });
});
