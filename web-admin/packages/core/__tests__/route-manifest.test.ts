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

  it('registers custom low-code pages before generic /p pages and catch-all', () => {
    const routes = coreRoutes();
    const customEditIndex = routes.findIndex(
      (entry) => entry.path === '/p/c/:pageKey/edit/:recordId',
    );
    const customPageIndex = routes.findIndex((entry) => entry.path === '/p/c/:pageKey');
    const genericPageIndex = routes.findIndex((entry) => entry.path === '/p/:pageKey');
    const catchAllIndex = routes.findIndex((entry) => entry.path === '/*');

    expect(routes).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: '/p/c/:pageKey',
          file: './routes/p.c.$pageKey.tsx',
        }),
        expect.objectContaining({
          path: '/p/c/:pageKey/edit/:recordId',
          file: './routes/p.c.$pageKey.edit.tsx',
        }),
      ]),
    );
    expect(customEditIndex).toBeGreaterThanOrEqual(0);
    expect(customPageIndex).toBeGreaterThanOrEqual(0);
    expect(customEditIndex).toBeLessThan(customPageIndex);
    expect(customEditIndex).toBeLessThan(genericPageIndex);
    expect(customEditIndex).toBeLessThan(catchAllIndex);
    expect(customPageIndex).toBeLessThan(genericPageIndex);
    expect(customPageIndex).toBeLessThan(catchAllIndex);
  });

  it('exposes empty commerce runtime route hooks for future plugin-owned routes', () => {
    expect(merchantRuntimeRoutes()).toEqual([]);
    expect(storefrontRuntimeRoutes()).toEqual([]);
    expect(checkoutRuntimeRoutes()).toEqual([]);
    expect(themePreviewRuntimeRoutes()).toEqual([]);
  });
});
