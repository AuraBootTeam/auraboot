import { ApplicationCapabilitiesProvider, useApplicationCapabilities } from '~/contexts/ApplicationCapabilitiesContext';
import {
  Outlet,
  Links,
  Meta,
  Scripts,
  ScrollRestoration,
  redirect,
  data as routeData,
  Link,
  useRouteLoaderData,
  useLoaderData,
  type LoaderFunctionArgs,
} from 'react-router';
import { context as traceContext, propagation as tracePropagation } from '@opentelemetry/api';
import React, { useEffect, useState } from 'react';
import { isSystemTenant } from '~/constants/SpaceConstants';
import { startActiveSessionRenewal } from '~/shared/services/active-session';

import { I18nProvider, useI18n } from '~/contexts/I18nContext';
import { ThemeProvider } from '~/contexts/ThemeContext';
import { ToastProvider } from '~/contexts/ToastContext';
import { Toaster } from 'sonner';
import { TimezoneProvider } from '~/contexts/TimezoneContext';
import { TenantThemeProvider } from '~/contexts/TenantThemeContext';
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
import { RootLoaderDataProvider, useRootLoaderData, type RootLoaderData } from '~/root-data';

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
  getImpersonationFromRequest,
  getTokenFromRequest,
  maybeRenewSession,
  restoreOperatorSession,
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
import { SessionMaintenance } from '~/components/SessionMaintenance';
import {
  mergeSessionContext,
  type SessionContextSnapshot,
} from '~/shared/services/session-context';
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
import {
  RootDependencyUnavailableError,
  ROOT_DEPENDENCY_UNAVAILABLE,
} from '~/shared/observability/rootReadErrors';

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
  requestId?: string,
  pathname = '/',
): Promise<BrandingConfig> {
  if (!isCommercialEdition(environment.EDITION)) {
    return resolveCommunityBranding();
  }

  // /api/runtime/branding exists only on this app's own BFF (license + branding
  // file access are deliberately BFF-only). BFF_INTERNAL_URL must NOT be used
  // here: deployments routinely point it at the backend/gateway for SSR data
  // fetches, and hitting it yields a bare 401 from Spring. Always call the
  // loopback BFF.
  const bffUrl = `http://127.0.0.1:${environment.BFF_PORT || '3500'}`;
  // Authentication appearance is unrelated to authenticated application refreshes.
  // Keep its database read out of long-lived display/session revalidation.
  const appearanceRoutes = [
    '/login',
    '/admin-login',
    '/signup',
    '/forgot-password',
    '/reset-password',
  ];
  const includeAppearance = appearanceRoutes.includes(pathname.replace(/\/$/, ''));
  const response = await fetch(
    `${bffUrl}/api/runtime/branding${includeAppearance ? '?include=auth-appearance' : ''}`,
    {
      headers: requestId ? { 'X-Request-Id': requestId } : undefined,
      signal: fetchTimeoutSignal(),
    },
  );
  if (!response.ok) {
    if ([502, 503, 504].includes(response.status)) {
      throw new RootDependencyUnavailableError('branding', response.status);
    }
    throw new Error(`Unable to resolve deployment branding from BFF (${response.status}).`);
  }
  const payload = (await response.json()) as { branding?: BrandingConfig };
  if (!payload.branding) {
    throw new Error('BFF deployment branding response is missing the branding contract.');
  }
  return payload.branding;
}

export async function loader(args: LoaderFunctionArgs): Promise<RootLoaderData | Response> {
  const { createLoaderDiagnostics, isRootDependencyUnavailable } =
    await import('~/server/observability/loaderDiagnostics.server');
  const diagnostics = createLoaderDiagnostics(args.request);
  try {
    return await diagnostics.stage('request', () => loadRoot(args, diagnostics));
  } catch (error) {
    if (!args.request.signal.aborted && isRootDependencyUnavailable(error)) {
      throw routeData(
        {
          code: ROOT_DEPENDENCY_UNAVAILABLE,
          requestId: diagnostics.correlation.requestId,
          traceId: diagnostics.correlation.traceId,
          stage: diagnostics.failedStage,
        },
        { status: 503 },
      );
    }
    throw error;
  }
}

async function loadRoot(
  { request, context }: LoaderFunctionArgs,
  diagnostics: Awaited<
    ReturnType<
      (typeof import('~/server/observability/loaderDiagnostics.server'))['createLoaderDiagnostics']
    >
  >,
): Promise<RootLoaderData | Response> {
  const stage = diagnostics.stage;
  const locale = getLocaleFromRequest(request);
  const initialTimezone = getTimezoneFromRequest(request);
  const requestUrl = new URL(request.url);
  const { pathname } = requestUrl;
  const impersonation = await stage('impersonation', () => getImpersonationFromRequest(request));
  if (impersonation && new Date(impersonation.expiresAt).getTime() <= Date.now()) {
    return redirect(pathname + requestUrl.search, {
      headers: { 'Set-Cookie': await restoreOperatorSession(request) },
    });
  }
  const runtimeProfile = getRuntimeProfileFromPathname(pathname);
  const icpCompliance = resolveIcpComplianceConfig(process.env);
  const buildIdentity = resolveBuildIdentity(process.env);
  // These reads are independent. Keep every request fresh without serial network waits.
  const [branding, bootstrapStatus, accessPolicy, token] = await Promise.all([
    stage('branding', () =>
      resolveDeploymentBrandingFromBff(process.env, diagnostics.correlation.requestId, pathname),
    ),
    stage('bootstrap', () => fetchBootstrapStatus()),
    stage('access-policy', () => fetchAccessPolicy()),
    stage('session', () => getTokenFromRequest(request)),
  ]);

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
      // Published appearance must take effect immediately, even while the
      // locale/bootstrap bundle remains in the public-route cache.
      return { ...cached, branding } as RootLoaderData;
    }

    const i18nData = await stage('i18n-public', () => getI18nData(locale, request, false));
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
      impersonation: null,
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
        signal: fetchTimeoutSignal(),
      });
      if (!resp.ok) return [];
      const result = await resp.json();
      return result.data || [];
    } catch {
      return [];
    }
  }

  const [{ user, permissions, preferences }, i18nData, spaces] = await Promise.all([
    stage('user-info', () => getUserInfo(request, context)),
    stage('i18n', () => getI18nData(locale, request)),
    stage('spaces', () => fetchSpaces()),
  ]);

  // Authoritative auth rejection: fetchUserInfo returns null only for a 401.
  // Transport, timeout and 5xx failures throw instead, preserving the valid session for retry.
  if (!user && !isPublicRoute(pathname)) {
    if (token) {
      if (impersonation) {
        return redirect(pathname + requestUrl.search, {
          headers: { 'Set-Cookie': await restoreOperatorSession(request) },
        });
      }
      const session = await getSessionFromRequest(request);
      return redirect(`/login?redirectTo=${encodeURIComponent(pathname)}`, {
        headers: {
          'Set-Cookie': await sessionStorage.destroySession(session),
        },
      });
    }
  }

  const menus = user ? await stage('menus', () => getUserMenus(request)) : [];

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
  const traceCarrier: Record<string, string> = {};
  tracePropagation.inject(traceContext.active(), traceCarrier);
  const rootData: RootLoaderData = {
    traceparent: traceCarrier.traceparent,
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
    impersonation,
  };

  // Sliding-session renewal: when the access token is inside its renewal window,
  // swap it for a fresh one and update the httpOnly session cookie. The renewed
  // cookie is attached to this response so every subsequent request carries the
  // new token; failures are non-fatal (the current token stays valid until its
  // real deadline, then the normal 401 → login redirect applies).
  if (user && !impersonation) {
    const renewal = await stage('session-renewal', () => maybeRenewSession(request));
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
  const rootData = useRootLoaderData();
  const branding = rootData?.branding ?? COMMUNITY_BRANDING;
  return (
    <html lang="zh-CN" className="h-full" suppressHydrationWarning>
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <meta name="color-scheme" content="light dark" />
        {rootData?.traceparent && <meta name="traceparent" content={rootData.traceparent} />}
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
  const { agent, loading } = useApplicationCapabilities();

  useEffect(() => {
    if (!enabled || !ready || loading) return;
    void import('~/framework/boot-plugins').then(({ bootCorePlugins }) =>
      bootCorePlugins({ hasFeature: hasFeatureKey, agentState: agent }),
    );
  }, [enabled, hasFeatureKey, ready, agent, loading]);

  return null;
}

function SessionLoginRedirect() {
  useEffect(() => {
    window.location.replace(
      `/login?redirectTo=${encodeURIComponent(window.location.pathname + window.location.search)}`,
    );
  }, []);
  return null;
}

export default function App() {
  const source = useLoaderData<typeof loader>() as RootLoaderData;
  const [live, setLive] = useState<{
    source: RootLoaderData;
    data: RootLoaderData;
    rejection?: 401 | 403;
    error?: Error;
  }>();
  const current = live?.source === source ? live : undefined;
  const data = current?.data ?? source;
  const onRefresh = (snapshot: SessionContextSnapshot) =>
    setLive((previous) => ({
      source,
      data: mergeSessionContext(previous?.source === source ? previous.data : source, snapshot),
    }));
  const onRejected = (rejection: 401 | 403) => setLive({ source, data, rejection });
  const onError = (error: Error) => setLive({ source, data, error });
  const setFederationRuntimeProfile = useFederationStore((state) => state.setRuntimeProfile);
  const bootCoreRuntime = shouldBootCorePlugins(data.runtimeProfile);
  const authenticated = Boolean(data.user);

  useEffect(() => {
    if (!authenticated) return;
    return startActiveSessionRenewal(window, document, async () => {
      const response = await fetch('/api/auth/session-renew', {
        method: 'POST',
        credentials: 'same-origin',
      });
      if (!response.ok) throw new Error('Active session renewal failed');
    });
  }, [authenticated]);


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

  if (current?.error) throw current.error;
  if (current?.rejection === 401) {
    return <SessionLoginRedirect />;
  }
  if (current?.rejection === 403) {
    const locale = resolveErrorLocale(data.locale);
    return (
      <main
        role="alert"
        data-testid="session-access-denied"
        className="flex min-h-screen items-center justify-center bg-gray-50 px-4 dark:bg-gray-900"
      >
        <div className="max-w-md rounded-lg bg-white p-8 text-center shadow-sm dark:bg-gray-800">
          <h1 className="mb-3 text-xl font-semibold text-gray-900 dark:text-white">
            {rootT('sessionAccessChangedTitle', locale)}
          </h1>
          <p className="mb-6 text-sm text-gray-600 dark:text-gray-300">
            {rootT('sessionAccessChangedDetail', locale)}
          </p>
          <Link
            to="/home"
            className="rounded-lg bg-gray-100 px-4 py-2 text-sm text-gray-900 dark:bg-gray-700 dark:text-white"
          >
            {rootT('backHome', locale)}
          </Link>
        </div>
      </main>
    );
  }

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
          <TenantThemeProvider>
            <ToastProvider closeLabel={data.i18n?.['notification.close']}>
              <ConfirmDialogProvider>
                {bootCoreRuntime ? <AuraBotProvider>{appFrame}</AuraBotProvider> : appFrame}
              </ConfirmDialogProvider>
              {/* sonner-based feedback (designer saves, export failures) previously had no
                  mounted Toaster and was invisible; render it once at the app root. */}
              <Toaster richColors position="top-right" />
            </ToastProvider>
          </TenantThemeProvider>
        </TimezoneProvider>
      </I18nProvider>
    </RuntimeProfileProvider>
  );

  return (
    <RootLoaderDataProvider value={data}>
      <QueryProvider>
        <ThemeProvider>
          {bootCoreRuntime ? (
            <AuthProvider data={data}>
              <SessionMaintenance
                enabled={bootCoreRuntime && !data.impersonation}
                source={source}
                snapshot={data}
                onRefresh={onRefresh}
                onRejected={onRejected}
                onError={onError}
              />
              <ApplicationCapabilitiesProvider><EntitlementProvider>
                <CorePluginBootstrap enabled={bootCoreRuntime} />
                <DslRegistryProvider>{sharedProviders}</DslRegistryProvider>
              </EntitlementProvider></ApplicationCapabilitiesProvider>
            </AuthProvider>
          ) : (
            sharedProviders
          )}
        </ThemeProvider>
      </QueryProvider>
    </RootLoaderDataProvider>
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
  const [clientContext, setClientContext] = useState<{
    pageUrl: string;
    occurredAt: string;
  } | null>(null);
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
              <dd className="max-w-[15rem] truncate font-mono">{clientContext?.pageUrl ?? '—'}</dd>
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
