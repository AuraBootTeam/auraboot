import { describe, expect, it } from 'vitest';
import {
  getRuntimeProfileFromPathname,
  isPublicRuntimePathname,
  isRuntimeProfileAllowed,
  isRuntimeProfileEnabled,
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

  describe('plugin federation gating', () => {
    it('isRuntimeProfileEnabled defaults an undeclared plugin to admin-only', () => {
      // No declared profiles → plugin default (admin); never leaks to public.
      expect(isRuntimeProfileEnabled(undefined, 'admin')).toBe(true);
      expect(isRuntimeProfileEnabled(undefined, 'storefront')).toBe(false);
      // Declared set is honoured exactly.
      expect(isRuntimeProfileEnabled(['storefront', 'theme-preview'], 'storefront')).toBe(true);
      expect(isRuntimeProfileEnabled(['storefront', 'theme-preview'], 'checkout')).toBe(false);
      // Empty declared set disables everywhere (explicit opt-out, not default).
      expect(isRuntimeProfileEnabled([], 'admin')).toBe(false);
    });

    it('isRuntimeProfileAllowed defaults an undeclared contribution to all runtimes', () => {
      expect(isRuntimeProfileAllowed(undefined, 'admin')).toBe(true);
      expect(isRuntimeProfileAllowed([], 'storefront')).toBe(true);
      expect(isRuntimeProfileAllowed(['merchant'], 'merchant')).toBe(true);
      expect(isRuntimeProfileAllowed(['merchant'], 'admin')).toBe(false);
    });
  });
});
