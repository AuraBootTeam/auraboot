import { type RouteConfig, index, layout, route } from '@react-router/dev/routes';
import { coreRoutes } from '../packages/core/route-manifest';

// Enterprise routes are contributed by auraboot-enterprise at build time via
// the overlay (web-admin-ext). In the OSS distribution this import resolves
// to an empty stub; enterprise build replaces it via path alias or overlay rsync.
// See scripts/reverse-sync-webadmin.sh and auraboot-enterprise/plugins/.

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

  // Main app layout — core only in OSS; enterprise/platform routes are injected
  // by the enterprise build via overlay.
  layout('./routes/DefaultLayout.tsx', [
    index('./routes/_index.tsx'),
    ...coreRoutes(),
  ]),

  // Public shared view
  route('/share/:token', './routes/share.$token.tsx'),
] satisfies RouteConfig;
