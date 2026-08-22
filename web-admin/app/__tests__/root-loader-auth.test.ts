import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  destroySession: vi.fn(),
  fetchBootstrapStatus: vi.fn(),
  getI18nData: vi.fn(),
  getSessionFromRequest: vi.fn(),
  getTokenFromRequest: vi.fn(),
  getUserInfo: vi.fn(),
  getUserMenus: vi.fn(),
  ssrCacheGet: vi.fn(),
  ssrCacheSet: vi.fn(),
}));

vi.mock('~/shared/services/session', () => ({
  getSessionFromRequest: mocks.getSessionFromRequest,
  getTokenFromRequest: mocks.getTokenFromRequest,
  sessionStorage: {
    destroySession: mocks.destroySession,
  },
}));

vi.mock('~/shared/services/userService', () => ({
  getUserInfo: mocks.getUserInfo,
}));

vi.mock('~/shared/services/form', () => ({
  getI18nData: mocks.getI18nData,
}));

vi.mock('~/shared/services/menu', () => ({
  getUserMenus: mocks.getUserMenus,
}));

vi.mock('~/services/bootstrapStatus', () => ({
  fetchBootstrapStatus: mocks.fetchBootstrapStatus,
}));

vi.mock('~/utils/ssr-cache', () => ({
  ssrCacheKey: (pathname: string, locale: string) => `${pathname}:${locale}`,
  ssrLoaderCache: {
    get: mocks.ssrCacheGet,
    set: mocks.ssrCacheSet,
  },
}));

vi.mock('~/middleware/auth_filter', () => ({
  sessionMiddleware: vi.fn(),
}));

vi.mock('~/contexts/I18nContext', () => ({
  I18nProvider: ({ children }: { children: unknown }) => children,
  useI18n: () => ({ isRTL: false }),
}));

vi.mock('~/contexts/ThemeContext', () => ({
  ThemeProvider: ({ children }: { children: unknown }) => children,
}));

vi.mock('~/contexts/ToastContext', () => ({
  ToastProvider: ({ children }: { children: unknown }) => children,
}));

vi.mock('~/contexts/TimezoneContext', () => ({
  TimezoneProvider: ({ children }: { children: unknown }) => children,
}));

vi.mock('~/contexts/ConfirmDialogContext', () => ({
  ConfirmDialogProvider: ({ children }: { children: unknown }) => children,
}));

vi.mock('~/contexts/AuthContext', () => ({
  AuthProvider: ({ children }: { children: unknown }) => children,
}));

vi.mock('~/contexts/EntitlementContext', () => ({
  EntitlementProvider: ({ children }: { children: unknown }) => children,
}));

vi.mock('~/contexts/DslRegistryContext', () => ({
  DslRegistryProvider: ({ children }: { children: unknown }) => children,
}));

vi.mock('~/plugins/core-aurabot/components-shell', () => ({
  AuraBotProvider: ({ children }: { children: unknown }) => children,
}));

vi.mock('~/providers/QueryProvider', () => ({
  QueryProvider: ({ children }: { children: unknown }) => children,
}));

vi.mock('~/components/BootstrapBanner', () => ({
  BootstrapBanner: () => null,
}));

vi.mock('~/components/BootstrapNotReady', () => ({
  BootstrapNotReady: () => null,
}));

describe('root loader authentication guard', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    mocks.fetchBootstrapStatus.mockResolvedValue(null);
    mocks.getI18nData.mockResolvedValue({});
    mocks.getSessionFromRequest.mockResolvedValue({ get: vi.fn() });
    mocks.getUserInfo.mockResolvedValue({ user: null, permissions: null, preferences: null });
    mocks.getUserMenus.mockResolvedValue([]);
    mocks.ssrCacheGet.mockReturnValue(undefined);
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('redirects anonymous private routes even when route middleware is bypassed', async () => {
    mocks.getTokenFromRequest.mockResolvedValue(null);

    const { loader } = await import('~/root');
    const result = await loader({
      request: new Request('http://localhost/documents'),
    } as any);

    expect(result).toMatchObject({
      url: '/login?redirectTo=%2Fdocuments',
      status: 302,
    });
    expect(mocks.getUserInfo).not.toHaveBeenCalled();
    expect(mocks.getUserMenus).not.toHaveBeenCalled();
  });

  it('does not destroy an authenticated session when user resolution is temporarily unavailable', async () => {
    mocks.getTokenFromRequest.mockResolvedValue('still-valid-token');
    mocks.getUserInfo.mockRejectedValue(new Error('Network error: fetch failed'));

    const { loader } = await import('~/root');

    await expect(
      loader({ request: new Request('http://localhost/p/inv_fg_pack_rule/edit/record-1') } as any),
    ).rejects.toThrow('Network error: fetch failed');
    expect(mocks.destroySession).not.toHaveBeenCalled();
  });

  it('destroys an authenticated session only after an authoritative auth rejection', async () => {
    mocks.getTokenFromRequest.mockResolvedValue('invalid-token');
    mocks.getUserInfo.mockResolvedValue({ user: null, permissions: null, preferences: null });
    mocks.destroySession.mockResolvedValue('__session=; Max-Age=0');

    const { loader } = await import('~/root');
    const result = await loader({
      request: new Request('http://localhost/p/inv_fg_pack_rule/edit/record-1'),
    } as any);

    expect(result).toMatchObject({
      url: '/login?redirectTo=%2Fp%2Finv_fg_pack_rule%2Fedit%2Frecord-1',
    });
    expect(mocks.destroySession).toHaveBeenCalledTimes(1);
  });

  it('keeps anonymous public routes available without user or menu fetches', async () => {
    mocks.getTokenFromRequest.mockResolvedValue(null);

    const { loader } = await import('~/root');
    const result = await loader({
      request: new Request('http://localhost/login'),
    } as any);

    expect(result).toMatchObject({
      user: null,
      menus: [],
      i18n: {},
      locale: 'zh-CN',
      runtimeProfile: 'admin',
      icpCompliance: {
        enabled: false,
        siteDisplayName: 'AuraBoot',
      },
      branding: {
        productName: 'AuraBoot',
        poweredByText: 'Powered by AuraBoot',
      },
      buildIdentity: {
        version: 'development',
        revision: 'local',
      },
    });
    expect(mocks.getUserInfo).not.toHaveBeenCalled();
    expect(mocks.getUserMenus).not.toHaveBeenCalled();
  });

  it('injects ICP settings from the BFF runtime environment', async () => {
    vi.stubEnv('ICP_COMPLIANCE_ENABLED', 'true');
    vi.stubEnv('ICP_SITE_TITLE', 'Runtime title');
    vi.stubEnv('ICP_RECORD_NUMBER', 'Runtime ICP record');
    mocks.getTokenFromRequest.mockResolvedValue(null);

    const { loader } = await import('~/root');
    const result = await loader({
      request: new Request('http://localhost/login'),
    } as any);

    expect(result).toMatchObject({
      icpCompliance: {
        enabled: true,
        siteTitle: 'Runtime title',
        recordNumber: 'Runtime ICP record',
        siteDisplayName: 'AuraBoot Runtime title',
      },
    });
  });

  it('loads commercial branding through the BFF-only runtime boundary', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          branding: {
            mode: 'commercial',
            productName: 'Northstar',
            poweredByText: 'Powered by Northstar',
          },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    const { resolveDeploymentBrandingFromBff } = await import('~/root');
    await expect(
      resolveDeploymentBrandingFromBff({
        EDITION: 'standard',
        BFF_INTERNAL_URL: 'http://bff.internal:4000',
      }),
    ).resolves.toMatchObject({
      mode: 'commercial',
      productName: 'Northstar',
    });
    expect(fetchMock).toHaveBeenCalledWith('http://bff.internal:4000/api/runtime/branding');
  });

  it('fails closed when commercial branding cannot be resolved by the BFF', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })));

    const { resolveDeploymentBrandingFromBff } = await import('~/root');
    await expect(
      resolveDeploymentBrandingFromBff({ EDITION: 'standard', BFF_PORT: '4001' }),
    ).rejects.toThrow('Unable to resolve deployment branding from BFF (503).');
  });

  it('always emits a stable product title when ICP mode is disabled', async () => {
    const { COMMUNITY_BRANDING } = await import('~/config/branding');
    const { meta } = await import('~/root');

    expect(
      meta({
        data: {
          branding: COMMUNITY_BRANDING,
          icpCompliance: { enabled: false },
        } as any,
      }),
    ).toEqual([{ title: 'AuraBoot' }]);
  });

  it('keeps commercial branding in the title when ICP records are enabled', async () => {
    const { COMMUNITY_BRANDING } = await import('~/config/branding');
    const { meta } = await import('~/root');

    expect(
      meta({
        data: {
          branding: { ...COMMUNITY_BRANDING, mode: 'commercial', productName: 'Northstar' },
          icpCompliance: { enabled: true, siteDisplayName: 'AuraBoot Filed Site' },
        } as any,
      }),
    ).toEqual([{ title: 'Northstar' }]);
  });

  it('declares browser identity links from the resolved branding contract', async () => {
    const { COMMUNITY_BRANDING } = await import('~/config/branding');
    const { resolveBrandingDocumentLinks } = await import('~/root');

    expect(resolveBrandingDocumentLinks(COMMUNITY_BRANDING)).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ rel: 'manifest', href: '/manifest.json' }),
        expect.objectContaining({ rel: 'icon', href: '/favicon.ico' }),
        expect.objectContaining({ rel: 'apple-touch-icon', href: '/apple-touch-icon.png' }),
      ]),
    );
  });

  it('keeps storefront runtime public even when an admin token exists', async () => {
    mocks.getTokenFromRequest.mockResolvedValue('admin-token');

    const { loader } = await import('~/root');
    const result = await loader({
      request: new Request('http://localhost/s/demo/products/sample-product'),
    } as any);

    expect(result).toMatchObject({
      user: null,
      menus: [],
      i18n: {},
      locale: 'zh-CN',
      runtimeProfile: 'storefront',
    });
    expect(mocks.getUserInfo).not.toHaveBeenCalled();
    expect(mocks.getUserMenus).not.toHaveBeenCalled();
  });

  it('keeps checkout runtime public and isolated from admin menus', async () => {
    mocks.getTokenFromRequest.mockResolvedValue(null);

    const { loader } = await import('~/root');
    const result = await loader({
      request: new Request('http://localhost/checkout/chk_123/payment'),
    } as any);

    expect(result).toMatchObject({
      user: null,
      menus: [],
      runtimeProfile: 'checkout',
    });
    expect(mocks.getUserInfo).not.toHaveBeenCalled();
    expect(mocks.getUserMenus).not.toHaveBeenCalled();
  });
});
