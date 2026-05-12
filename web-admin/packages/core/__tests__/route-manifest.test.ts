import { describe, expect, it } from 'vitest';
import {
  adminRuntimeRoutes,
  checkoutRuntimeRoutes,
  coreRoutes,
  merchantRuntimeRoutes,
  storefrontRuntimeRoutes,
  themePreviewRuntimeRoutes,
} from '../route-manifest';

describe('coreRoutes', () => {
  it('registers the legacy inbox route as a static route', () => {
    const routes = coreRoutes();

    expect(routes).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: '/inbox',
          file: './routes/inbox/index.tsx',
        }),
      ]),
    );
  });

  it('keeps coreRoutes as the backward-compatible admin runtime alias', () => {
    expect(coreRoutes()).toEqual(adminRuntimeRoutes());
  });

  it('exposes empty commerce runtime route hooks for future plugin-owned routes', () => {
    expect(merchantRuntimeRoutes()).toEqual([]);
    expect(storefrontRuntimeRoutes()).toEqual([]);
    expect(checkoutRuntimeRoutes()).toEqual([]);
    expect(themePreviewRuntimeRoutes()).toEqual([]);
  });
});
