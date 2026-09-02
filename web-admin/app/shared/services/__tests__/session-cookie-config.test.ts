import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const createCookieSessionStorageMock = vi.fn();

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return {
    ...actual,
    createCookieSessionStorage: createCookieSessionStorageMock,
  };
});

describe('resolveSessionCookieSecure', () => {
  it('defaults to Secure in production when no override is set', async () => {
    const { resolveSessionCookieSecure } = await import('~/shared/services/session');
    expect(resolveSessionCookieSecure({ NODE_ENV: 'production' })).toBe(true);
  });

  it('defaults to no Secure outside production when no override is set', async () => {
    const { resolveSessionCookieSecure } = await import('~/shared/services/session');
    expect(resolveSessionCookieSecure({ NODE_ENV: 'development' })).toBe(false);
    expect(resolveSessionCookieSecure({})).toBe(false);
    expect(resolveSessionCookieSecure(undefined)).toBe(false);
  });

  it('honors SESSION_COOKIE_SECURE=false over the production default', async () => {
    const { resolveSessionCookieSecure } = await import('~/shared/services/session');
    expect(
      resolveSessionCookieSecure({ NODE_ENV: 'production', SESSION_COOKIE_SECURE: 'false' }),
    ).toBe(false);
  });

  it('honors SESSION_COOKIE_SECURE=true over the non-production default', async () => {
    const { resolveSessionCookieSecure } = await import('~/shared/services/session');
    expect(
      resolveSessionCookieSecure({ NODE_ENV: 'development', SESSION_COOKIE_SECURE: 'true' }),
    ).toBe(true);
  });

  it('treats an empty override as unset', async () => {
    const { resolveSessionCookieSecure } = await import('~/shared/services/session');
    expect(resolveSessionCookieSecure({ NODE_ENV: 'production', SESSION_COOKIE_SECURE: '' })).toBe(
      true,
    );
  });

  it('fails fast on an invalid override value', async () => {
    const { resolveSessionCookieSecure } = await import('~/shared/services/session');
    expect(() =>
      resolveSessionCookieSecure({ NODE_ENV: 'production', SESSION_COOKIE_SECURE: 'yes' }),
    ).toThrowError(/SESSION_COOKIE_SECURE must be "true" or "false"/);
  });
});

describe('assertSessionCookieDeployment', () => {
  it('is a no-op without PUBLIC_URL', async () => {
    const { assertSessionCookieDeployment } = await import('~/shared/services/session');
    expect(() => assertSessionCookieDeployment({ NODE_ENV: 'production' })).not.toThrow();
    expect(() => assertSessionCookieDeployment(undefined)).not.toThrow();
  });

  it('accepts HTTPS public URL with the production Secure default', async () => {
    const { assertSessionCookieDeployment } = await import('~/shared/services/session');
    expect(() =>
      assertSessionCookieDeployment({ NODE_ENV: 'production', PUBLIC_URL: 'https://wms.example.com' }),
    ).not.toThrow();
  });

  it('rejects HTTPS public URL with SESSION_COOKIE_SECURE=false', async () => {
    const { assertSessionCookieDeployment } = await import('~/shared/services/session');
    expect(() =>
      assertSessionCookieDeployment({
        NODE_ENV: 'production',
        PUBLIC_URL: 'https://wms.example.com',
        SESSION_COOKIE_SECURE: 'false',
      }),
    ).toThrowError(/HTTPS.*not Secure/);
  });

  it('rejects plain-HTTP public origin when the cookie stays Secure', async () => {
    const { assertSessionCookieDeployment } = await import('~/shared/services/session');
    expect(() =>
      assertSessionCookieDeployment({
        NODE_ENV: 'production',
        PUBLIC_URL: 'http://192.168.31.47:8080',
      }),
    ).toThrowError(/browsers will reject the Secure __session cookie/);
  });

  it('accepts plain-HTTP public origin with SESSION_COOKIE_SECURE=false', async () => {
    const { assertSessionCookieDeployment } = await import('~/shared/services/session');
    expect(() =>
      assertSessionCookieDeployment({
        NODE_ENV: 'production',
        PUBLIC_URL: 'http://192.168.31.47:8080',
        SESSION_COOKIE_SECURE: 'false',
      }),
    ).not.toThrow();
  });

  it('accepts loopback HTTP origins because browsers store Secure cookies there', async () => {
    const { assertSessionCookieDeployment } = await import('~/shared/services/session');
    expect(() =>
      assertSessionCookieDeployment({
        NODE_ENV: 'production',
        PUBLIC_URL: 'http://127.0.0.1:18090',
      }),
    ).not.toThrow();
    expect(() =>
      assertSessionCookieDeployment({
        NODE_ENV: 'production',
        PUBLIC_URL: 'http://localhost:3000',
      }),
    ).not.toThrow();
  });

  it('accepts plain-HTTP public origin in non-production (no Secure by default)', async () => {
    const { assertSessionCookieDeployment } = await import('~/shared/services/session');
    expect(() =>
      assertSessionCookieDeployment({
        NODE_ENV: 'development',
        PUBLIC_URL: 'http://192.168.31.47:8080',
      }),
    ).not.toThrow();
  });

  it('fails fast on an unparsable PUBLIC_URL', async () => {
    const { assertSessionCookieDeployment } = await import('~/shared/services/session');
    expect(() =>
      assertSessionCookieDeployment({ NODE_ENV: 'production', PUBLIC_URL: 'not-a-url' }),
    ).toThrowError(/PUBLIC_URL is not a valid URL/);
  });
});

describe('session storage cookie options', () => {
  beforeEach(() => {
    vi.resetModules();
    createCookieSessionStorageMock.mockClear();
    vi.stubEnv('SESSION_SECRET', 'test-secret');
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('marks the __session cookie Secure in production by default', async () => {
    vi.stubEnv('NODE_ENV', 'production');
    vi.stubEnv('SESSION_COOKIE_SECURE', '');
    await import('~/shared/services/session');

    expect(createCookieSessionStorageMock).toHaveBeenCalledTimes(1);
    const options = createCookieSessionStorageMock.mock.calls[0][0];
    expect(options.cookie.secure).toBe(true);
  });

  it('drops Secure on HTTP CI when SESSION_COOKIE_SECURE=false in production', async () => {
    vi.stubEnv('NODE_ENV', 'production');
    vi.stubEnv('SESSION_COOKIE_SECURE', 'false');
    vi.stubEnv('PUBLIC_URL', 'http://192.168.31.47:8080');
    await import('~/shared/services/session');

    const options = createCookieSessionStorageMock.mock.calls[0][0];
    expect(options.cookie.secure).toBe(false);
  });

  it('forces Secure when SESSION_COOKIE_SECURE=true outside production', async () => {
    vi.stubEnv('NODE_ENV', 'development');
    vi.stubEnv('SESSION_COOKIE_SECURE', 'true');
    await import('~/shared/services/session');

    const options = createCookieSessionStorageMock.mock.calls[0][0];
    expect(options.cookie.secure).toBe(true);
  });

  it('refuses to boot in production with Secure cookies on a plain-HTTP public URL', async () => {
    vi.stubEnv('NODE_ENV', 'production');
    vi.stubEnv('SESSION_COOKIE_SECURE', '');
    vi.stubEnv('PUBLIC_URL', 'http://192.168.31.47:8080');

    await expect(import('~/shared/services/session')).rejects.toThrowError(
      /browsers will reject the Secure __session cookie/,
    );
    expect(createCookieSessionStorageMock).not.toHaveBeenCalled();
  });
});
