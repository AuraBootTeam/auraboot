import {
  type RouteConfig,
  type RouteConfigEntry,
  index,
  layout,
  route,
} from '@react-router/dev/routes';
import { coreRoutes } from '../packages/core/route-manifest';

const webRouteManifest = process.env.AURA_WEB_ROUTE_MANIFEST
  ? await import(/* @vite-ignore */ process.env.AURA_WEB_ROUTE_MANIFEST)
  : null;
const ENTERPRISE_ROUTES: RouteConfigEntry[] =
  webRouteManifest?.ENTERPRISE_ROUTES ?? [];
const PLATFORM_ROUTES: RouteConfigEntry[] =
  webRouteManifest?.PLATFORM_ROUTES ?? [];
const PLATFORM_LAYOUT =
  webRouteManifest?.PLATFORM_LAYOUT ?? './routes/PlatformLayout.tsx';

// OSS build: core routes only. A private build-time route manifest contributes
// enterpriseRoutes() + platformRoutes() + the PlatformLayout wrapper.

export default [
  // API routes (always)
  route('/api/address-data', './routes/api.address-data.tsx'),
  route('/_action/switch-space', './routes/api.switch-space.tsx'),

  // Setup wizard (standalone, no layout wrapper, no auth required)
  route('/setup', './routes/setup/SetupWizard.tsx'),

  // Auth layout (always)
  layout('./auth/AuthLayout.tsx', [
    route('/logout', './auth/Logout.tsx'),
    route('/login', './auth/Login.tsx'),
    route('/signup', './auth/SignUp.tsx'),
    route('/forgot-password', './routes/auth/ForgotPassword.tsx'),
    route('/reset-password', './routes/auth/ResetPassword.tsx'),
    route('/login/social/:provider/callback', './routes/auth/social-callback.tsx'),
  ]),

  // Tenant selection (always)
  layout('./tenant/TenantSelectionLayout.tsx', [
    route('/tenant-selection', './tenant/TenantSelection.tsx'),
  ]),

  // Explicit admin namespace. During the compatibility window it redirects
  // /admin/* to the existing admin paths while the shell remains admin-scoped.
  layout('./routes/AdminLayout.tsx', [
    route('/admin', './routes/admin._index.tsx'),
    route('/admin/*', './routes/admin.$.tsx'),
  ]),

  // Commerce runtime shells (Merchant / Storefront / Checkout / Theme Preview)
  // were temporarily scaffolded here as part of an early commerce-on-platform
  // experiment. They have been moved out to the standalone commerce/ product
  // repo (AuraBootTeam/commerce). The multi-runtime kernel itself
  // (~/framework/runtime, RuntimeProfile type, FederationManager profile
  // gating, AdminLayout) stays in OSS because it serves any future product
  // built on AuraBoot, not commerce specifically.

  // Main app layout — core routes in OSS plus optional typed private routes.
  layout('./routes/DefaultLayout.tsx', [
    index('./routes/_index.tsx'),
    ...ENTERPRISE_ROUTES,
    ...(PLATFORM_ROUTES.length > 0
      ? [layout(PLATFORM_LAYOUT, PLATFORM_ROUTES)]
      : []),
    ...coreRoutes(),
  ]),

  // Public shared view
  route('/share/:token', './routes/share.$token.tsx'),
] satisfies RouteConfig;
