import express from 'express';
import { describe, expect, it } from 'vitest';
import { trustedProxyPolicy } from '../trusted-proxies';

describe('BFF trusted proxy policy', () => {
  it('trusts loopback gateways by default and rejects remote peers', () => {
    const app = express();
    app.set('trust proxy', trustedProxyPolicy(undefined));
    const trusts = app.get('trust proxy fn');
    expect(trusts('127.0.0.1')).toBe(true);
    expect(trusts('::1')).toBe(true);
    expect(trusts('198.51.100.7')).toBe(false);
    expect(trusts('10.0.0.7')).toBe(false);
  });

  it('accepts only the explicitly configured remote proxy ranges', () => {
    const app = express();
    app.set('trust proxy', trustedProxyPolicy('127.0.0.1, 10.1.2.0/24,2001:db8::/64'));
    const trusts = app.get('trust proxy fn');
    expect(trusts('10.1.2.7')).toBe(true);
    expect(trusts('10.1.3.7')).toBe(false);
    expect(trusts('2001:db8::7')).toBe(true);
    expect(trusts('198.51.100.7')).toBe(false);
  });

  it.each([
    'true',
    '*',
    '0.0.0.0/0',
    '::/0',
    '10.0.0.1/33',
    '::1/129',
    'proxy.example',
    '127.0.0.1,',
    '10.0.0.1/-1',
  ])('rejects unsafe or malformed policy %s', (raw) => {
    expect(() => trustedProxyPolicy(raw)).toThrow('AURA_TRUSTED_PROXIES');
  });
});
