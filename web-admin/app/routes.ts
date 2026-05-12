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

  // Explicit admin namespace. During the compatibility window it redirects
  // /admin/* to the existing admin paths while the shell remains admin-scoped.
  layout('./routes/AdminLayout.tsx', [
    route('/admin', './routes/admin._index.tsx'),
    route('/admin/*', './routes/admin.$.tsx'),
  ]),

  // Merchant runtime shell. Commerce plugins can later contribute concrete
  // merchant routes without reusing the platform admin sidebar.
  layout('./routes/MerchantLayout.tsx', [
    route('/merchant', './commerce/merchant/MerchantHome.tsx'),
    route('/merchant/*', './commerce/merchant/MerchantHome.tsx', { id: 'merchant-splat' }),
  ]),

  // Public storefront runtime shell.
  layout('./routes/StorefrontLayout.tsx', [
    route('/s/:storeHandle', './commerce/storefront/StorefrontPage.tsx', { id: 'storefront-home' }),
    route('/s/:storeHandle/*', './commerce/storefront/StorefrontPage.tsx', {
      id: 'storefront-splat',
    }),
  ]),

  // Public checkout runtime shell.
  layout('./routes/CheckoutLayout.tsx', [
    route('/checkout/:checkoutId', './commerce/checkout/CheckoutFlow.tsx', { id: 'checkout-home' }),
    route('/checkout/:checkoutId/*', './commerce/checkout/CheckoutFlow.tsx', {
      id: 'checkout-splat',
    }),
  ]),

  // Authenticated theme preview shell for Theme Designer integration.
  layout('./routes/ThemePreviewLayout.tsx', [
    route('/theme-preview/:themeId', './commerce/theme-preview/ThemePreviewPage.tsx', {
      id: 'theme-preview-home',
    }),
    route('/theme-preview/:themeId/*', './commerce/theme-preview/ThemePreviewPage.tsx', {
      id: 'theme-preview-splat',
    }),
  ]),

  // Legacy main app layout — core routes only in OSS; enterprise overlay injects more.
  layout('./routes/DefaultLayout.tsx', [index('./routes/_index.tsx'), ...coreRoutes()]),

  // Public shared view
  route('/share/:token', './routes/share.$token.tsx'),
] satisfies RouteConfig;
