import { type RouteConfig, layout, route } from '@react-router/dev/routes';
import { coreRoutes } from '../packages/core/route-manifest';

export default [
  // API routes (always)
  route('/api/address-data', './routes/api.address-data.tsx'),

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

  // Main app layout
  layout('./routes/DefaultLayout.tsx', [
    ...coreRoutes(),
  ]),
] satisfies RouteConfig;
