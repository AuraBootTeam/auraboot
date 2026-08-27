import {
  Outlet,
  Links,
  Meta,
  Scripts,
  ScrollRestoration,
  redirect,
  Link,
  useRouteLoaderData,
  useLoaderData,
  type LoaderFunctionArgs,
} from 'react-router';
import React, { useEffect, useState } from 'react';
import { isSystemTenant } from '~/constants/SpaceConstants';

import { I18nProvider, useI18n } from '~/contexts/I18nContext';
import { ThemeProvider } from '~/contexts/ThemeContext';
import { ToastProvider } from '~/contexts/ToastContext';
import { TimezoneProvider } from '~/contexts/TimezoneContext';
import { ConfirmDialogProvider } from '~/contexts/ConfirmDialogContext';
import { getI18nData } from '~/shared/services/form';
import { getUserMenus } from '~/shared/services/menu';
import {
  RuntimeProfileProvider,
  getRuntimeProfileFromPathname,
  isAnonymousRuntimeProfile,
  shouldBootCorePlugins,
} from '@auraboot/runtime-kernel';
import { useFederationStore } from '~/plugins/FederationManager';
import { resolveIcpComplianceConfig } from '~/config/icpCompliance';
import {
  COMMUNITY_BRANDING,
  isCommercialEdition,
  resolveBrandDisplayName,
  resolveBuildIdentity,
  resolveCommunityBranding,
  type BrandingConfig,
} from '~/config/branding';
import { useRootLoaderData, type RootLoaderData } from '~/root-data';

import '~/app.css';
import '~/styles/print.css';
import '~/plugins/core-designer/components/studio/workbench/styles/smart-slots.css';
import '~/plugins/core-designer/components/studio/workbench/styles/drag-preview.css';
import '~/plugins/core-designer/components/studio/workbench/styles/responsive.css';
import '~/plugins/core-designer/components/studio/workbench/styles/command.css';
import '~/plugins/core-designer/components/studio/workbench/styles/drag.css';

import { getUserInfo } from '~/shared/services/userService';
import { isPublicRoute } from '~/middleware/sessionMiddlewareFactory';
import {
  getSessionFromRequest,
  getTokenFromRequest,
  maybeRenewSession,
  sessionStorage,
} from '~/shared/services/session';
import { AuthProvider } from '~/contexts/AuthContext';
import { EntitlementProvider, useEntitlement } from '~/contexts/EntitlementContext';
import { DslRegistryProvider } from '~/contexts/DslRegistryContext';
import { AuraBotProvider } from '~/plugins/core-aurabot/components-shell';
import { QueryProvider } from '~/providers/QueryProvider';
import { fetchBootstrapStatus } from '~/services/bootstrapStatus';
import { fetchAccessPolicy } from '~/services/accessPolicy';
import { BootstrapBanner } from '~/components/BootstrapBanner';
import { BootstrapNotReady } from '~/components/BootstrapNotReady';
import { AuthSessionRevalidator } from '~/components/AuthSessionRevalidator';
import {
  formatClientReportMessage,
  generateErrorId,
  resolveErrorLocale,
  resolveErrorPresentation,
  resolveRootErrorView,
  rootT,
} from '~/error/root-error-view';
import { reportClientError } from '~/shared/observability/clientErrorReporter';
import { fetchTimeoutSignal } from '~/utils/fetchTimeout';

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

export async function resolveDeploymentBrandingFromBff(
  environment: Record<string, string | undefined>,
): Promise<BrandingConfig> {
  if (!isCommercialEdition(environment.EDITION)) {
    return resolveCommunityBranding();
  }

  const bffUrl =
    environment.BFF_INTERNAL_URL || `http://127.0.0.1:${environment.BFF_PORT || '3500'}`;
  const response = await fetch(`${bffUrl}/api/runtime/branding`, {
    signal: fetchTimeoutSignal(),
  });
  if (!response.ok) {
    throw new Error(`Unable to resolve deployment branding from BFF (${response.status}).`);
  }
  const payload = (await response.json()) as { branding?: BrandingConfig };
  if (!payload.branding) {
    throw new Error('BFF deployment branding response is missing the branding contract.');
  }
  return payload.branding;
}

export async function loader({ request }: LoaderFunctionArgs): Promise<RootLoaderData | Response> {
  const locale = getLocaleFromRequest(request);
  const initialTimezone = getTimezoneFromRequest(request);
  const { pathname } = new URL(request.url);
  const runtimeProfile = getRuntimeProfileFromPathname(pathname);
  const icpCompliance = resolveIcpComplianceConfig(process.env);
  const branding = await resolveDeploymentBrandingFromBff(process.env);
  const buildIdentity = resolveBuildIdentity(process.env);

  // Bootstrap status: never redirect; inject into loader data so the banner can render
  const bootstrapStatus = await fetchBootstrapStatus();
  const accessPolicy = await fetchAccessPolicy();
  const token = await getTokenFromRequest(request);

  // Public runtime shells must never load admin user, permissions, or menus.
  // This includes logged-in browser sessions visiting a storefront or checkout
  // URL; buyer/customer identity is a separate commerce concern.
  if (isAnonymousRuntimeProfile(runtimeProfile) || (isPublicRoute(pathname) && !token)) {
    // SSR cache: public routes produce identical loader data for the same
    // pathname + locale combination. Cache for 30s to reduce redundant i18n
    // fetches and backend bootstrap-status checks.
    // Known staleness window: bootstrapStatus is captured at cache time; after
    // setup, the banner may persist on public routes for up to 30s. Acceptable
    // tradeoff — banner has no functional side effect, only visual.
    const cacheKey = ssrCacheKey(pathname, locale);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const cached = ssrLoaderCache.get(cacheKey) as any;
    if (cached) {
      return cached as RootLoaderData;
    }

    const i18nData = await getI18nData(locale, request);
    const edition = process.env.EDITION || 'enterprise';
    const result: RootLoaderData = {
      runtimeProfile,
      user: null,
      permissions: [],
      preferences: null,
      menus: [],
      i18n: i18nData,
      locale,
      initialTimezone: initialTimezone ?? undefined,
      skipTenantPreferences: true,
      edition,
      spaces: [],
      bootstrapStatus,
      icpCompliance,
      branding,
      buildIdentity,
      accessPolicy,
    };
    ssrLoaderCache.set(cacheKey, result);
    return result;
  }

  if (!token && !isPublicRoute(pathname)) {
    return redirect(`/login?redirectTo=${encodeURIComponent(pathname)}`, 302);
  }

  // Authenticated flow (existing logic)

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

  // Authoritative auth rejection: fetchUserInfo returns null only for a 401.
  // Transport, timeout and 5xx failures throw instead, preserving the valid session for retry.
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
  const rootData: RootLoaderData = {
    runtimeProfile,
    user,
    permissions,
    preferences,
    menus,
    i18n: i18nData,
    locale,
    initialTimezone: initialTimezone ?? undefined,
    skipTenantPreferences:
      isAnonymousRuntimeProfile(runtimeProfile) || (isPublicRoute(pathname) && !user),
    edition,
    spaces,
    bootstrapStatus,
    icpCompliance,
    branding,
    buildIdentity,
    accessPolicy,
  };

  // Sliding-session renewal: when the access token is inside its renewal window,
  // swap it for a fresh one and update the httpOnly session cookie. The renewed
  // cookie is attached to this response so every subsequent request carries the
  // new token; failures are non-fatal (the current token stays valid until its
  // real deadline, then the normal 401 → login redirect applies).
  if (user) {
    const renewal = await maybeRenewSession(request);
    if (renewal.renewed && renewal.setCookie) {
      return new Response(JSON.stringify(rootData), {
        headers: {
          'Content-Type': 'application/json',
          'Set-Cookie': renewal.setCookie,
        },
      });
    }
  }

  return rootData;
}

export const meta = ({ data }: { data?: RootLoaderData }) => [
  {
    title: data
      ? resolveBrandDisplayName(data.branding, data.icpCompliance)
      : COMMUNITY_BRANDING.productName,
  },
];

export function resolveBrandingDocumentLinks(branding: BrandingConfig) {
  return [
    { rel: 'icon', href: branding.faviconUrl },
    { rel: 'icon', type: 'image/png', sizes: '32x32', href: branding.favicon32Url },
    {
      rel: 'apple-touch-icon',
      sizes: '180x180',
      href: branding.appleTouchIconUrl,
    },
    { rel: 'manifest', href: branding.manifestUrl },
  ];
}

export function Layout({ children }: { children: React.ReactNode }) {
  const branding = useRootLoaderData()?.branding ?? COMMUNITY_BRANDING;
  return (
    <html lang="zh-CN" className="h-full" suppressHydrationWarning>
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <meta name="color-scheme" content="light dark" />
        <Meta />
        <Links />
        {resolveBrandingDocumentLinks(branding).map((link) => (
          <link key={`${link.rel}:${link.href}`} {...link} />
        ))}
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

/**
 * Activates the kernel only after the first entitlement snapshot resolves.
 * A required commercial feature stays fail-closed when the entitlement
 * module is unavailable, the snapshot is stale, or the feature is missing.
 */
function CorePluginBootstrap({ enabled }: { enabled: boolean }) {
  const { ready, hasFeatureKey } = useEntitlement();

  useEffect(() => {
    if (!enabled || !ready) return;
    void import('~/framework/boot-plugins').then(({ bootCorePlugins }) =>
      bootCorePlugins({ hasFeature: hasFeatureKey }),
    );
  }, [enabled, hasFeatureKey, ready]);

  return null;
}

export default function App() {
  const data = useLoaderData<typeof loader>() as RootLoaderData;
  const setFederationRuntimeProfile = useFederationStore((state) => state.setRuntimeProfile);
  const bootCoreRuntime = shouldBootCorePlugins(data.runtimeProfile);

  useEffect(() => {
    setFederationRuntimeProfile(data.runtimeProfile);
  }, [data.runtimeProfile, setFederationRuntimeProfile]);

  // Capture uncaught front-end errors → /api/client-errors so they surface in the
  // in-app troubleshooting center (/ops/errors) instead of vanishing.
  useEffect(() => {
    void import('~/shared/observability/clientErrorReporter').then(
      ({ installClientErrorReporter }) => installClientErrorReporter(),
    );
  }, []);

  const appFrame = (
    <>
      {data.bootstrapStatus && !data.bootstrapStatus.initialized && (
        <BootstrapBanner status={data.bootstrapStatus} />
      )}
      <div className={data.bootstrapStatus && !data.bootstrapStatus.initialized ? 'pt-10' : ''}>
        <Outlet />
      </div>
    </>
  );

  const sharedProviders = (
    <RuntimeProfileProvider value={data.runtimeProfile}>
      <I18nProvider initialData={data.i18n || {}} initialLocale={data.locale}>
        <AppDirectionSync locale={data.locale} />
        <TimezoneProvider
          initialTimezone={data.initialTimezone}
          skipTenantPreferences={data.skipTenantPreferences}
        >
          <ToastProvider>
            <ConfirmDialogProvider>
              {bootCoreRuntime ? <AuraBotProvider>{appFrame}</AuraBotProvider> : appFrame}
            </ConfirmDialogProvider>
          </ToastProvider>
        </TimezoneProvider>
      </I18nProvider>
    </RuntimeProfileProvider>
  );

  return (
    <QueryProvider>
      <ThemeProvider>
        {bootCoreRuntime ? (
          <AuthProvider>
            <AuthSessionRevalidator enabled={bootCoreRuntime} isAuthenticated={!!data.user} />
            <EntitlementProvider>
              <CorePluginBootstrap enabled={bootCoreRuntime} />
              <DslRegistryProvider>{sharedProviders}</DslRegistryProvider>
            </EntitlementProvider>
          </AuthProvider>
        ) : (
          sharedProviders
        )}
      </ThemeProvider>
    </QueryProvider>
  );
}

type ErrorBoundaryProps = { error: unknown };

export function ErrorBoundary({ error }: ErrorBoundaryProps) {
  const rootData = useRouteLoaderData('root') as RootLoaderData | undefined;
  const locale = resolveErrorLocale(rootData?.locale);
  const view = resolveRootErrorView(error);
  const { title, detail } = resolveErrorPresentation(view, locale);
  const [errorId, setErrorId] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [clientContext, setClientContext] = useState<{ pageUrl: string; occurredAt: string } | null>(
    null,
  );
  const t = (key: Parameters<typeof rootT>[0]) => rootT(key, locale);
  const errorMessage = error instanceof Error ? error.message : String(error ?? '');
  const stack = error instanceof Error ? error.stack : undefined;

  useEffect(() => {
    // Generate client-side only: a random id must not differ between the SSR
    // tree and the first client render (hydration).
    setErrorId(generateErrorId());
    setClientContext({
      pageUrl: window.location.href,
      occurredAt: new Date().toISOString(),
    });
  }, [error]);

  useEffect(() => {
    if (!errorId || !clientContext) return;
    if (typeof document !== 'undefined') {
      document.title = `${title} · AuraBoot`;
    }
    reportClientError({
      errorType: 'boundary',
      kind: view.kind,
      status: view.status,
      errorId,
      message: formatClientReportMessage(errorId, view, errorMessage || 'unknown boundary error'),
      stack,
      pageUrl: clientContext.pageUrl,
    });
    // Report once per boundary render; the id is stable for the same error.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [errorId, clientContext]);

  const handleCopyErrorId = async () => {
    if (!errorId) return;
    try {
      await navigator.clipboard.writeText(errorId);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard unavailable (e.g. non-secure context) — leave the id visible.
    }
  };

  if (rootData?.bootstrapStatus && !rootData.bootstrapStatus.initialized) {
    return <BootstrapNotReady />;
  }

  return (
    <main
      role="alert"
      aria-live="assertive"
      className="flex min-h-screen items-center justify-center bg-gray-50 px-4 dark:bg-gray-900"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-8 text-center shadow-lg dark:bg-gray-800">
        <svg
          className={`mx-auto mb-4 h-12 w-12 ${
            view.kind === 'not-found' || view.kind === 'forbidden'
              ? 'text-amber-400'
              : 'text-red-400'
          }`}
          viewBox="0 0 24 24"
          fill="currentColor"
          aria-hidden="true"
        >
          <path
            fillRule="evenodd"
            d="M12 2a10 10 0 100 20 10 10 0 000-20zm0 18a8 8 0 110-16 8 8 0 010 16zm-1-13h2v6h-2V7zm0 8h2v2h-2v-2z"
            clipRule="evenodd"
          />
        </svg>
        <h1 className="mb-2 text-3xl font-bold text-gray-900 dark:text-white">{title}</h1>
        <p className="mb-6 text-sm text-gray-600 dark:text-gray-300">{detail}</p>

        <div className="mb-6 inline-flex items-center gap-2 rounded-md border border-gray-200 px-3 py-1.5 text-sm dark:border-gray-700">
          <span className="text-gray-500 dark:text-gray-400">{t('errorId')}</span>
          <code className="font-mono font-medium text-gray-900 dark:text-white">
            {errorId ?? 'ERR-……'}
          </code>
          <button
            type="button"
            onClick={handleCopyErrorId}
            disabled={!errorId}
            className="rounded px-1.5 py-0.5 text-xs font-medium text-blue-600 transition-colors hover:bg-blue-50 dark:text-blue-400 dark:hover:bg-gray-700"
          >
            {copied ? t('copied') : t('copy')}
          </button>
        </div>

        <details className="mb-6 text-left">
          <summary className="cursor-pointer text-sm text-gray-500 dark:text-gray-400">
            {t('techDetails')}
          </summary>
          <dl className="mt-2 space-y-1 rounded-md bg-gray-50 p-3 text-xs text-gray-600 dark:bg-gray-800 dark:text-gray-300">
            <div className="flex justify-between gap-3">
              <dt>{t('errorKind')}</dt>
              <dd className="font-mono">{view.kind}</dd>
            </div>
            {view.status != null && (
              <div className="flex justify-between gap-3">
                <dt>{t('error')}</dt>
                <dd className="font-mono">{view.status}</dd>
              </div>
            )}
            <div className="flex justify-between gap-3">
              <dt>{t('pageUrl')}</dt>
              <dd className="max-w-[15rem] truncate font-mono">
                {clientContext?.pageUrl ?? '—'}
              </dd>
            </div>
            <div className="flex justify-between gap-3">
              <dt>{t('occurredAt')}</dt>
              <dd className="font-mono">{clientContext?.occurredAt ?? '—'}</dd>
            </div>
            {import.meta.env.DEV && errorMessage && (
              <div className="flex justify-between gap-3">
                <dt>{t('errorMessage')}</dt>
                <dd className="max-w-[15rem] truncate font-mono">{errorMessage}</dd>
              </div>
            )}
            {import.meta.env.DEV && stack && (
              <pre className="mt-2 max-h-40 overflow-auto rounded bg-gray-100 p-2 text-[10px] leading-relaxed dark:bg-gray-700">
                <code>{stack}</code>
              </pre>
            )}
          </dl>
        </details>

        <div className="text-center">
          <div className="flex flex-col justify-center gap-3 sm:flex-row">
            {view.retryable && (
              <button
                type="button"
                onClick={() => window.location.reload()}
                className="rounded-lg bg-blue-600 px-4 py-2 font-medium text-white transition-colors hover:bg-blue-700"
              >
                {t('retry')}
              </button>
            )}
            <Link
              to="/"
              className="rounded-lg bg-gray-100 px-4 py-2 font-medium text-gray-700 transition-colors hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-200 dark:hover:bg-gray-600"
            >
              {t('backHome')}
            </Link>
            <button
              type="button"
              onClick={() => window.history.back()}
              className="rounded-lg px-4 py-2 font-medium text-gray-500 transition-colors hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-700"
            >
              {t('backPrevious')}
            </button>
          </div>
        </div>
      </div>
    </main>
  );
}
