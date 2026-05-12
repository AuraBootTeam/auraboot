import { describe, expect, it } from 'vitest';
import {
  getRuntimeProfileFromPathname,
  isPublicRuntimePathname,
  normalizeRuntimePathname,
  shouldBootCorePlugins,
} from '../runtimeProfile';

describe('runtimeProfile', () => {
  it('normalizes empty, relative, and trailing-slash paths', () => {
    expect(normalizeRuntimePathname('')).toBe('/');
    expect(normalizeRuntimePathname('merchant/')).toBe('/merchant');
    expect(normalizeRuntimePathname('/s/demo/?x=1')).toBe('/s/demo');
  });

  it('resolves explicit runtime route groups', () => {
    expect(getRuntimeProfileFromPathname('/admin')).toBe('admin');
    expect(getRuntimeProfileFromPathname('/merchant/orders')).toBe('merchant');
    expect(getRuntimeProfileFromPathname('/s/demo/products/sku-1')).toBe('storefront');
    expect(getRuntimeProfileFromPathname('/checkout/chk_123/payment')).toBe('checkout');
    expect(getRuntimeProfileFromPathname('/theme-preview/main')).toBe('theme-preview');
  });

  it('keeps legacy app paths on the admin profile', () => {
    expect(getRuntimeProfileFromPathname('/')).toBe('admin');
    expect(getRuntimeProfileFromPathname('/home')).toBe('admin');
    expect(getRuntimeProfileFromPathname('/p/prod_product')).toBe('admin');
  });

  it('treats storefront and checkout as anonymous public runtime paths', () => {
    expect(isPublicRuntimePathname('/s/demo')).toBe(true);
    expect(isPublicRuntimePathname('/checkout/chk_123')).toBe(true);
    expect(isPublicRuntimePathname('/theme-preview/main')).toBe(false);
  });

  it('only boots core platform plugins for authenticated runtime shells', () => {
    expect(shouldBootCorePlugins('admin')).toBe(true);
    expect(shouldBootCorePlugins('merchant')).toBe(true);
    expect(shouldBootCorePlugins('theme-preview')).toBe(true);
    expect(shouldBootCorePlugins('storefront')).toBe(false);
    expect(shouldBootCorePlugins('checkout')).toBe(false);
  });
});
