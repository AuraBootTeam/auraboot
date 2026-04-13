import type { LoaderFunctionArgs } from 'react-router';

import {
  Outlet,
  Links,
  Meta,
  Scripts,
  ScrollRestoration,
  redirect,
  useRouteLoaderData,
  useLoaderData,
} from 'react-router';
import React, { useEffect } from 'react';

import { isRouteErrorResponse } from 'react-router';
import { isSystemTenant } from '~/constants/SpaceConstants';

import { I18nProvider, useI18n } from '~/contexts/I18nContext';
import { ThemeProvider } from '~/contexts/ThemeContext';
import { ToastProvider } from '~/contexts/ToastContext';
import { TimezoneProvider } from '~/contexts/TimezoneContext';
import { ConfirmDialogProvider } from '~/contexts/ConfirmDialogContext';
import { getI18nData } from '~/services/form';
import { getUserMenus } from '~/services/menu';

export interface RootLoaderData {
  user: any;
  permissions: any;
  preferences: any;
  menus: any[];
  i18n: Record<string, string>;
  locale: string;
  initialTimezone?: string;
  edition: string;
  spaces: any[];
}

import '~/app.css';
import '~/styles/print.css';
import '~/studio/workbench/styles/smart-slots.css';
import '~/studio/workbench/styles/drag-preview.css';
import '~/studio/workbench/styles/responsive.css';
import '~/studio/workbench/styles/command.css';
import '~/studio/workbench/styles/drag.css';

import { getUserInfo } from '~/services/userService';
import { isPublicRoute } from '~/middleware/sessionMiddlewareFactory';
import { getSessionFromRequest, getTokenFromRequest, sessionStorage } from '~/services/session';
import { AuthProvider } from '~/contexts/AuthContext';
import { EntitlementProvider } from '~/contexts/EntitlementContext';
import { DslRegistryProvider } from '~/contexts/DslRegistryContext';
import { AuraBotProvider } from '~/aurabot';
import { QueryProvider } from '~/providers/QueryProvider';

import { sessionMiddleware } from '~/middleware/auth_filter';
import { ssrLoaderCache, ssrCacheKey } from '~/utils/ssr-cache';

export const unstable_middleware = [sessionMiddleware];

// Read locale from cookie (set by I18nContext on locale change)
function getLocaleFromRequest(request: Request): string {
  const cookie = request.headers.get('Cookie') || '';
  const match = cookie.match(/(?:^|;\s*)locale=([^;]+)/);
  return match?.[1] || 'zh-CN';
}

// Read timezone from cookie (set by TimezoneContext on timezone resolve)
function getTimezoneFromRequest(request: Request): string {
  const cookie = request.headers.get('Cookie') || '';
  const match = cookie.match(/(?:^|;\s*)effective-timezone=([^;]+)/);
  const raw = match?.[1] ? decodeURIComponent(match[1]) : '';
  if (!raw) return '';
  try {
    Intl.DateTimeFormat(undefined, { timeZone: raw });
    return raw;
  } catch {
    return '';
  }
}

export async function loader({ request }: LoaderFunctionArgs): Promise<RootLoaderData | Response> {
  const locale = getLocaleFromRequest(request);
  const initialTimezone = getTimezoneFromRequest(request);
  const { pathname } = new URL(request.url);

  // Bootstrap check: redirect to /setup if system not initialized
  if (!pathname.startsWith('/setup')) {
    try {
      const bootstrapUrl = process.env.BFF_INTERNAL_URL || 'http://127.0.0.1:6443';
      const bootstrapRes = await fetch(`${bootstrapUrl}/api/bootstrap/status`);
      if (bootstrapRes.ok) {
        const bootstrapResult = await bootstrapRes.json();
        if (
          bootstrapResult.code === '0' &&
          bootstrapResult.data &&
          !bootstrapResult.data.initialized
        ) {
          return redirect('/setup');
        }
      }
    } catch {
      // Backend not available — don't redirect, continue normal flow
    }
  }

  // Public marketing: skip user/menu fetch for anonymous visitors
  if (isPublicRoute(pathname)) {
    const token = await getTokenFromRequest(request);
    if (!token) {
      // SSR cache: public routes without auth produce identical loader data
      // for the same pathname + locale combination. Cache for 30s to reduce
      // redundant i18n fetches and backend bootstrap-status checks.
      const cacheKey = ssrCacheKey(pathname, locale);
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const cached = ssrLoaderCache.get(cacheKey) as any;
      if (cached) {
        return cached as RootLoaderData;
      }

      const i18nData = await getI18nData(locale, request);
      const edition = process.env.EDITION || 'enterprise';
      const result = {
        user: null,
        permissions: [],
        preferences: null,
        menus: [],
        i18n: i18nData,
        locale,
        initialTimezone: initialTimezone ?? undefined,
        edition,
        spaces: [],
      };
      ssrLoaderCache.set(cacheKey, result);
      return result;
    }
  }

  // Authenticated flow (existing logic)
  const token = await getTokenFromRequest(request);

  async function fetchSpaces(): Promise<any[]> {
    if (!token) return [];
    try {
      const apiUrl = process.env.BFF_INTERNAL_URL || 'http://127.0.0.1:6443';
      const resp = await fetch(`${apiUrl}/api/tenant-selection/my-spaces`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!resp.ok) return [];
      const result = await resp.json();
      return result.data || [];
    } catch {
      return [];
    }
  }

  const [{ user, permissions, preferences }, i18nData, spaces] = await Promise.all([
    getUserInfo(request),
    getI18nData(locale, request),
    fetchSpaces(),
  ]);

  // Stale token guard: token exists but user resolution failed (e.g. DB reset)
  // → clear session and redirect to login
  if (!user && !isPublicRoute(pathname)) {
    if (token) {
      const session = await getSessionFromRequest(request);
      return redirect(`/login?redirectTo=${encodeURIComponent(pathname)}`, {
        headers: {
          'Set-Cookie': await sessionStorage.destroySession(session),
        },
      });
    }
  }

  const menus = user ? await getUserMenus(request) : [];

  // Tenant guard: routes outside of auth/tenant-selection require a tenant
  if (!isPublicRoute(pathname) && pathname !== '/tenant-selection' && user && !user.tenantId) {
    return redirect('/tenant-selection');
  }

  // Reverse guard: if user already has a tenant, tenant-selection is a no-op.
  // Exception: system-tenant users can visit /tenant-selection to switch spaces.
  if (pathname === '/tenant-selection' && user?.tenantId && !isSystemTenant(user.tenantId)) {
    return redirect('/');
  }

  const edition = process.env.EDITION || 'enterprise';
  return { user, permissions, preferences, menus, i18n: i18nData, locale, initialTimezone: initialTimezone ?? undefined, edition, spaces };
}

export function useRootLoaderData(): RootLoaderData | undefined {
  return useRouteLoaderData<typeof loader>('root') as RootLoaderData | undefined;
}

export function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" className="h-full">
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <meta name="color-scheme" content="light dark" />
        <Meta />
        <Links />
      </head>
      <body className="h-full bg-gray-50 transition-colors duration-200 dark:bg-gray-900">
        {children}
        <ScrollRestoration />
        <Scripts />
      </body>
    </html>
  );
}

/**
 * Syncs document.dir and document.documentElement.lang based on the current locale.
 * Must be rendered inside I18nProvider to access useI18n().
 */
function AppDirectionSync({ locale }: { locale: string }) {
  const { isRTL } = useI18n();

  useEffect(() => {
    document.documentElement.lang = locale;
    document.documentElement.dir = isRTL ? 'rtl' : 'ltr';
  }, [locale, isRTL]);

  return null;
}

export default function App() {
  const data = useLoaderData<typeof loader>() as RootLoaderData;

  // M3.7 — boot kernel + activate core plugins once on mount. Plugin
  // setup() registers NavigationResources, widgets, etc. into the kernel
  // singleton so menu / breadcrumb / widget resolution can derive from a
  // single source. See app/framework/boot-plugins.ts.
  useEffect(() => {
    void import('~/framework/boot-plugins').then(({ bootCorePlugins }) =>
      bootCorePlugins(),
    );
  }, []);

  return (
    <QueryProvider>
      <ThemeProvider>
        <AuthProvider>
          <EntitlementProvider>
            <DslRegistryProvider>
              <I18nProvider initialData={data.i18n || {}} initialLocale={data.locale}>
                <AppDirectionSync locale={data.locale} />
                <TimezoneProvider initialTimezone={data.initialTimezone}>
                  <ToastProvider>
                    <ConfirmDialogProvider>
                      <AuraBotProvider>
                        <Outlet />
                      </AuraBotProvider>
                    </ConfirmDialogProvider>
                  </ToastProvider>
                </TimezoneProvider>
              </I18nProvider>
            </DslRegistryProvider>
          </EntitlementProvider>
        </AuthProvider>
      </ThemeProvider>
    </QueryProvider>
  );
}

type ErrorBoundaryProps = { error: unknown };

const ROOT_ERROR_TEXTS = {
  oops: {
    'zh-CN': '出错了！',
    'en-US': 'Oops!',
    'ja-JP': 'エラーが発生しました！',
    'ko-KR': '오류가 발생했습니다!',
  },
  error: { 'zh-CN': '错误', 'en-US': 'Error', 'ja-JP': 'エラー', 'ko-KR': '오류' },
  unexpected: {
    'zh-CN': '发生了意外错误。',
    'en-US': 'An unexpected error occurred.',
    'ja-JP': '予期しないエラーが発生しました。',
    'ko-KR': '예기치 않은 오류가 발생했습니다.',
  },
  notFound: {
    'zh-CN': '请求的页面不存在。',
    'en-US': 'The requested page could not be found.',
    'ja-JP': 'リクエストされたページが見つかりません。',
    'ko-KR': '요청한 페이지를 찾을 수 없습니다.',
  },
  techDetails: {
    'zh-CN': '技术详情',
    'en-US': 'Technical Details',
    'ja-JP': '技術的な詳細',
    'ko-KR': '기술 세부 정보',
  },
  backHome: {
    'zh-CN': '返回首页',
    'en-US': 'Back to Home',
    'ja-JP': 'ホームに戻る',
    'ko-KR': '홈으로 돌아가기',
  },
} as const;

type RootErrorTextKey = keyof typeof ROOT_ERROR_TEXTS;
type SupportedErrorLocale = 'zh-CN' | 'en-US' | 'ja-JP' | 'ko-KR';

function rootT(key: RootErrorTextKey): string {
  let lang: SupportedErrorLocale = 'en-US';
  if (typeof navigator !== 'undefined') {
    const navLang = navigator.language;
    if (navLang?.startsWith('zh')) lang = 'zh-CN';
    else if (navLang?.startsWith('ja')) lang = 'ja-JP';
    else if (navLang?.startsWith('ko')) lang = 'ko-KR';
  }
  return ROOT_ERROR_TEXTS[key][lang];
}

export function ErrorBoundary({ error }: ErrorBoundaryProps) {
  let message = rootT('oops');
  let details = rootT('unexpected');
  let stack: string | undefined;

  if (isRouteErrorResponse(error)) {
    message = error.status === 404 ? '404' : rootT('error');
    details = error.status === 404 ? rootT('notFound') : error.statusText || details;
  } else if (import.meta.env.DEV && error && error instanceof Error) {
    details = error.message;
    stack = error.stack;
  }

  return (
    <main className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-md rounded-lg bg-white p-8 shadow-lg dark:bg-gray-800">
        <div className="text-center">
          <h1 className="mb-4 text-6xl font-bold text-gray-900 dark:text-white">{message}</h1>
          <p className="mb-8 text-lg text-gray-600 dark:text-gray-300">{details}</p>
          {stack && (
            <details className="text-left">
              <summary className="mb-2 cursor-pointer text-sm text-gray-500 dark:text-gray-400">
                {rootT('techDetails')}
              </summary>
              <pre className="overflow-auto rounded bg-gray-100 p-4 text-xs dark:bg-gray-700">
                <code>{stack}</code>
              </pre>
            </details>
          )}
          <button
            onClick={() => (window.location.href = '/')}
            className="rounded-lg bg-blue-600 px-4 py-2 font-medium text-white transition-colors hover:bg-blue-700"
          >
            {rootT('backHome')}
          </button>
        </div>
      </div>
    </main>
  );
}
