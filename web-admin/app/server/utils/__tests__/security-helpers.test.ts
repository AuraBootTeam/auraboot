import { describe, expect, it } from 'vitest';
import { parseCookieHeader } from '../cookie-security';
import { resolveCorsOrigin } from '../cors-security';
import fs from 'node:fs';
import path from 'node:path';

describe('BFF security helpers', () => {
  it('does not grant credentials to development CORS origins', () => {
    const result = resolveCorsOrigin({
      origin: 'http://localhost:5173',
      allowedOrigins: [],
      allowedDevPorts: new Set(['5173']),
      credentials: true,
      environment: 'development',
    });

    expect(result).toEqual({
      allowOrigin: 'http://localhost:5173',
      allowCredentials: false,
    });
  });

  it('allows credentials only for explicitly configured origins', () => {
    const result = resolveCorsOrigin({
      origin: 'https://app.example.com',
      allowedOrigins: ['https://app.example.com'],
      allowedDevPorts: new Set(),
      credentials: true,
      environment: 'production',
    });

    expect(result).toEqual({
      allowOrigin: 'https://app.example.com',
      allowCredentials: true,
    });
  });

  it('does not grant credentialed CORS for wildcard origins', () => {
    const result = resolveCorsOrigin({
      origin: 'https://app.example.com',
      allowedOrigins: ['*'],
      allowedDevPorts: new Set(),
      credentials: true,
      environment: 'production',
    });

    expect(result).toEqual({ allowCredentials: false });
  });

  it('parses cookies into a Map and rejects prototype keys', () => {
    const cookies = parseCookieHeader('sid=abc; __proto__=polluted; constructor=x; theme=dark');

    expect(cookies).toBeInstanceOf(Map);
    expect(Object.fromEntries(cookies)).toEqual({ sid: 'abc', theme: 'dark' });
    expect(({} as Record<string, unknown>).polluted).toBeUndefined();
  });
});

describe('cookie-test page', () => {
  it('does not render status messages with innerHTML string interpolation', () => {
    const html = fs.readFileSync(
      path.resolve(process.cwd(), 'public/cookie-test.html'),
      'utf8',
    );

    expect(html).not.toContain('statusDiv.innerHTML = `<div class="status ${type}">${message}</div>`');
  });
});
