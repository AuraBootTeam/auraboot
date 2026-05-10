import { beforeEach, describe, expect, it, vi } from 'vitest';

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
    });
    expect(mocks.getUserInfo).not.toHaveBeenCalled();
    expect(mocks.getUserMenus).not.toHaveBeenCalled();
  });
});
