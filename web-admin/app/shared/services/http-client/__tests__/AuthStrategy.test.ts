import { describe, it, expect } from 'vitest';
import { resolveAuthToken, isPublicApiRoute } from '../AuthStrategy';
import type { RequestContext, FetchOptions } from '../types';

describe('isPublicApiRoute', () => {
  it('should match exact public routes', () => {
    expect(isPublicApiRoute('/api/auth/login')).toBe(true);
    expect(isPublicApiRoute('/api/auth/register')).toBe(true);
    expect(isPublicApiRoute('/api/auth/signup')).toBe(true);
    expect(isPublicApiRoute('/api/auth/forgot-password')).toBe(true);
    expect(isPublicApiRoute('/api/auth/reset-password')).toBe(true);
    expect(isPublicApiRoute('/api/public')).toBe(true);
    expect(isPublicApiRoute('/api/i18n')).toBe(true);
    expect(isPublicApiRoute('/api/health')).toBe(true);
    expect(isPublicApiRoute('/health')).toBe(true);
  });

  it('should match prefix routes', () => {
    expect(isPublicApiRoute('/api/auth/login/callback')).toBe(true);
    expect(isPublicApiRoute('/api/i18n/zh-CN')).toBe(true);
    expect(isPublicApiRoute('/api/public/config')).toBe(true);
  });

  it('should not match protected routes', () => {
    expect(isPublicApiRoute('/api/user/current')).toBe(false);
    expect(isPublicApiRoute('/api/meta/models')).toBe(false);
    expect(isPublicApiRoute('/api/auth')).toBe(false); // not in list
    expect(isPublicApiRoute('/api/authentication')).toBe(false);
  });
});

describe('resolveAuthToken', () => {
  const contextWithToken: RequestContext = {
    isServer: false,
    token: 'context-token',
  };

  const contextWithoutToken: RequestContext = {
    isServer: false,
  };

  it('should return explicit token when provided (priority 2)', async () => {
    const options: FetchOptions = { token: 'explicit-token' };
    const result = await resolveAuthToken('/api/user', contextWithToken, options);
    expect(result).toBe('explicit-token');
  });

  it('should return undefined for public routes (priority 3)', async () => {
    const options: FetchOptions = {};
    const result = await resolveAuthToken('/api/auth/login', contextWithToken, options);
    expect(result).toBeUndefined();
  });

  it('should return context token for protected routes (priority 4)', async () => {
    const options: FetchOptions = {};
    const result = await resolveAuthToken('/api/user', contextWithToken, options);
    expect(result).toBe('context-token');
  });

  it('should return undefined when skipAutoToken is true and no explicit token', async () => {
    const options: FetchOptions = { skipAutoToken: true };
    const result = await resolveAuthToken('/api/user', contextWithToken, options);
    expect(result).toBeUndefined();
  });

  it('should use explicit token even with skipAutoToken', async () => {
    const options: FetchOptions = { skipAutoToken: true, token: 'manual-token' };
    const result = await resolveAuthToken('/api/user', contextWithToken, options);
    expect(result).toBe('manual-token');
  });

  it('should return undefined when no token available', async () => {
    const options: FetchOptions = {};
    const result = await resolveAuthToken('/api/user', contextWithoutToken, options);
    expect(result).toBeUndefined();
  });

  it('should normalize null token to undefined', async () => {
    const options: FetchOptions = { token: null };
    // null token means "not provided", should fall through to public route / context
    const result = await resolveAuthToken('/api/auth/login', contextWithToken, options);
    expect(result).toBeUndefined(); // public route
  });

  it('should prefer explicit token over context token', async () => {
    const options: FetchOptions = { token: 'explicit' };
    const result = await resolveAuthToken('/api/user', contextWithToken, options);
    expect(result).toBe('explicit');
  });

  it('should prefer explicit token even for public routes', async () => {
    const options: FetchOptions = { token: 'explicit' };
    const result = await resolveAuthToken('/api/auth/login', contextWithToken, options);
    // Explicit token has higher priority than public route check
    expect(result).toBe('explicit');
  });
});
