import { type RouteConfig, index, layout, route } from '@react-router/dev/routes';
import { coreRoutes } from '../packages/core/route-manifest';

// OSS build: core routes only. Enterprise overlay re-injects this file to
// add enterpriseRoutes() + platformRoutes() + PlatformLayout wrapper.
// See auraboot-enterprise/web-admin-ext/plugins/ent-platform-guard/overlay/app/routes.ts
// and scripts/build-web-admin.sh.

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

  // Main app layout — core routes only in OSS; enterprise overlay injects more.
  layout('./routes/DefaultLayout.tsx', [
    index('./routes/_index.tsx'),
    ...coreRoutes(),
  ]),

  // Public shared view
  route('/share/:token', './routes/share.$token.tsx'),
] satisfies RouteConfig;
