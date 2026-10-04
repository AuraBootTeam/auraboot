import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, statSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import type { UserSpace } from '../../src/client/auth.js';

const business: UserSpace = {
  tenantId: 42, tenantName: 'acme', tenantDisplayName: 'Acme Team',
  spaceType: 'business', roleCodes: ['reader'], isDefault: true,
};
const platform: UserSpace = { ...business, tenantId: 1, tenantName: 'platform', tenantDisplayName: 'Platform', spaceType: 'platform' };
const response = (data: unknown) => ({ ok: true, json: async () => ({ data }) });

describe('auth production implementation', () => {
  let home: string;
  let auth: typeof import('../../src/client/auth.js');
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(async () => {
    home = mkdtempSync(join(tmpdir(), 'aura-auth-contract-'));
    vi.resetModules();
    vi.doMock('os', async () => ({ ...await vi.importActual<typeof import('os')>('os'), homedir: () => home }));
    for (const name of ['AURA_TOKEN', 'AURA_API_URL', 'AURA_DEBUG', 'AURA_USER', 'AURA_PASSWORD']) vi.stubEnv(name, '');
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-02T12:00:00Z'));
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    auth = await import('../../src/client/auth.js');
  });
  afterEach(() => {
    vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.doUnmock('os');
    rmSync(home, { recursive: true, force: true });
  });

  it('round-trips config and private credentials across independent environments', () => {
    expect(auth.loadConfig().defaultEnv).toBe('local');
    expect(auth.loadCredentials()).toBeNull();
    const config = { defaultEnv: 'staging', output: 'json' as const, environments: {
      staging: { baseUrl: 'https://staging.example.test/' }, local: { baseUrl: 'http://localhost:6443' },
    } };
    auth.saveConfig(config);
    expect(auth.loadConfig()).toEqual(config);
    expect(auth.resolveBaseUrl()).toBe('https://staging.example.test');
    expect(auth.resolveBaseUrl('local')).toBe('http://localhost:6443');
    auth.saveCredentials({ jwt: 'staging-token', email: 'fixture@example.test' });
    auth.saveCredentials({ jwt: 'local-token', email: 'fixture@example.test' }, 'local');
    expect(auth.loadCredentials()?.jwt).toBe('staging-token');
    expect(auth.loadCredentials('local')?.jwt).toBe('local-token');
    expect(auth.loadCredentials('absent')).toBeNull();
    expect(statSync(join(home, '.aura/credentials.json')).mode & 0o777).toBe(0o600);
  });

  it('handles malformed local state without reading a real user home', () => {
    mkdirSync(join(home, '.aura'), { recursive: true });
    writeFileSync(join(home, '.aura/config.json'), 'not-json');
    writeFileSync(join(home, '.aura/credentials.json'), 'not-json');
    expect(auth.loadConfig().defaultEnv).toBe('local');
    expect(auth.loadCredentials()).toBeNull();
    auth.saveCredentials({ jwt: 'new', email: 'fixture@example.test' });
    expect(auth.loadCredentials()?.jwt).toBe('new');
  });

  it('resolves flag, environment, and stored tokens in priority order', () => {
    auth.saveCredentials({ jwt: 'file-token', email: 'fixture@example.test' });
    vi.stubEnv('AURA_TOKEN', 'env-token');
    expect(auth.resolveToken({ token: 'flag-token' })).toBe('flag-token');
    expect(auth.resolveToken({})).toBe('env-token');
    vi.stubEnv('AURA_TOKEN', '  ');
    expect(auth.resolveToken({})).toBe('file-token');
  });

  it('logs env auth once without exposing the token', () => {
    vi.stubEnv('AURA_TOKEN', 'fixture-secret-token'); vi.stubEnv('AURA_DEBUG', '1');
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(auth.resolveToken({})).toBe('fixture-secret-token');
    expect(auth.resolveToken({})).toBe('fixture-secret-token');
    expect(log).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(log.mock.calls)).not.toContain('fixture-secret-token');
  });

  it.each([
    ['2026-10-02T11:59:59Z', true], ['2026-10-02T12:00:00Z', true],
    ['2026-10-02T12:00:01Z', false], [undefined, false],
  ] as const)('checks expiry %s (expired=%s)', (expiresAt, expired) => {
    auth.saveCredentials({ jwt: 'file-token', email: 'fixture@example.test', expiresAt });
    expect(auth.isTokenExpired()).toBe(expired);
    expect(auth.resolveToken({})).toBe(expired ? null : 'file-token');
    expect(auth.resolveToken({ token: 'explicit' })).toBe('explicit');
  });

  it('does not resolve an absent stored token', () => {
    expect(auth.isTokenExpired()).toBe(false); expect(auth.resolveToken({})).toBeNull();
  });

  it('gives explicit API URL precedence over named environments', () => {
    vi.stubEnv('AURA_API_URL', ' https://override.example.test/api/ ');
    expect(auth.resolveBaseUrl('missing')).toBe('https://override.example.test/api');
  });
  it.each(['invalid', 'file:///tmp/file', 'https://name:password@example.test'])('rejects unsafe URL %s', (url) => {
    expect(() => auth.normalizeBaseUrl(url)).toThrow();
  });
  it('rejects an unknown environment', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(process, 'exit').mockImplementation(() => { throw new Error('exit-1'); });
    expect(() => auth.resolveBaseUrl('missing')).toThrow('exit-1');
    expect(process.exit).toHaveBeenCalledWith(1);
  });

  it('saves the server JWT for an already tenant-bound login', async () => {
    fetchMock.mockResolvedValueOnce(response({ jwt: 'server-jwt', tenantId: 42 })).mockResolvedValueOnce(response([business]));
    expect(await auth.login('https://api.example.test', 'fixture@example.test', 'fixture-password'))
      .toEqual({ jwt: 'server-jwt', tenantId: 42, spaces: [business], selectedSpace: undefined });
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ email: 'fixture@example.test', password: 'fixture-password' });
    expect(auth.loadCredentials()).toEqual({ jwt: 'server-jwt', email: 'fixture@example.test', expiresAt: '2026-10-03T12:00:00.000Z' });
  });
  it.each(['acme', 'ACME TEAM'])('selects requested tenant %s and caches its JWT', async (tenant) => {
    fetchMock.mockResolvedValueOnce(response({ jwt: 'login-jwt', tenantId: null }))
      .mockResolvedValueOnce(response([platform, business])).mockResolvedValueOnce(response({ jwt: 'tenant-jwt' }));
    const result = await auth.login('https://api.example.test', 'fixture@example.test', 'password', 'staging', tenant);
    expect(result).toEqual({ jwt: 'tenant-jwt', tenantId: 42, spaces: [platform, business], selectedSpace: business });
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual({ action: 'select', tenantId: 42 });
    expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe('Bearer login-jwt');
    expect(auth.loadCredentials('staging')?.jwt).toBe('tenant-jwt');
  });
  it.each([{ spaces: [platform, business] }, { spaces: [platform] }])('auto-selects business or available platform space', async ({ spaces }) => {
    fetchMock.mockResolvedValueOnce(response({ jwt: 'login-jwt', tenantId: null }))
      .mockResolvedValueOnce(response(spaces)).mockResolvedValueOnce(response({ jwt: 'selected-jwt' }));
    const result = await auth.login('https://api.example.test', 'fixture@example.test', 'password');
    expect(result.selectedSpace).toEqual(spaces.find(s => s.spaceType === 'business') ?? spaces[0]);
    expect(result.jwt).toBe('selected-jwt');
  });
  it('rejects unavailable tenant without caching a login', async () => {
    fetchMock.mockResolvedValueOnce(response({ jwt: 'login-jwt' })).mockResolvedValueOnce(response([business]));
    await expect(auth.login('https://api.example.test', 'fixture@example.test', 'password', undefined, 'missing'))
      .rejects.toThrow('Tenant "missing" not found');
    expect(auth.loadCredentials()).toBeNull(); expect(fetchMock).toHaveBeenCalledTimes(2);
  });
  it.each(['http', 'missing-jwt', 'network'])('cannot claim tenant selection success after %s failure', async (failure) => {
    auth.saveCredentials({ jwt: 'previous-token', email: 'fixture@example.test' });
    fetchMock.mockResolvedValueOnce(response({ jwt: 'login-jwt', tenantId: null })).mockResolvedValueOnce(response([business]));
    if (failure === 'http') fetchMock.mockResolvedValueOnce({ ok: false, status: 403, text: async () => 'denied' });
    else if (failure === 'missing-jwt') fetchMock.mockResolvedValueOnce(response({}));
    else fetchMock.mockRejectedValueOnce(new Error('connection lost'));
    await expect(auth.login('https://api.example.test', 'fixture@example.test', 'password', undefined, 'acme')).rejects.toThrow();
    expect(auth.loadCredentials()?.jwt).toBe('previous-token');
  });
  it('rejects failed login and a missing JWT', async () => {
    fetchMock.mockResolvedValueOnce({ ok: false, status: 401, text: async () => 'invalid credentials' });
    await expect(auth.login('https://api.example.test', 'fixture@example.test', 'wrong')).rejects.toThrow('Login failed (401)');
    fetchMock.mockResolvedValueOnce(response({ tenantId: 42 }));
    await expect(auth.login('https://api.example.test', 'fixture@example.test', 'password')).rejects.toThrow('Login response missing JWT');
    expect(auth.loadCredentials()).toBeNull();
  });
  it.each(['network', 'http'])('retains a bound login after optional spaces lookup failure (%s)', async (failure) => {
    fetchMock.mockResolvedValueOnce(response({ jwt: 'bound-jwt', tenantId: 42 }));
    if (failure === 'network') fetchMock.mockRejectedValueOnce(new Error('lookup unavailable'));
    else fetchMock.mockResolvedValueOnce({ ok: false });
    expect(await auth.login('https://api.example.test', 'fixture@example.test', 'password'))
      .toMatchObject({ jwt: 'bound-jwt', tenantId: 42, spaces: [] });
    expect(auth.loadCredentials()?.jwt).toBe('bound-jwt');
  });
  it('auto-login requires credentials and calls the real login implementation', async () => {
    await expect(auth.autoLogin('https://api.example.test')).rejects.toThrow('No credentials available');
    vi.stubEnv('AURA_USER', 'fixture@example.test'); vi.stubEnv('AURA_PASSWORD', 'password');
    fetchMock.mockResolvedValueOnce(response({ jwt: 'auto-jwt', tenantId: 42 })).mockResolvedValueOnce(response([]));
    expect(await auth.autoLogin('https://api.example.test')).toBe('auto-jwt');
    expect(JSON.parse(readFileSync(join(home, '.aura/credentials.json'), 'utf8')).local.jwt).toBe('auto-jwt');
  });
});
