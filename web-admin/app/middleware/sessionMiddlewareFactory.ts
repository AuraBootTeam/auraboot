import { redirect, type unstable_MiddlewareFunction } from 'react-router';
import { sessionStorage } from '~/shared/services/session.js';
import { JWT_TOKEN_KEY } from '~/constants/AuthConstant';
import { PLUGIN_PUBLIC_ROUTES } from '~/plugins/_public-routes';

// Routes that never require authentication
const PUBLIC_ROUTES = [
  '/login',
  '/signup',
  '/register',
  '/forgot-password',
  '/reset-password',
  '/setup',
  '/auth/login',
  '/auth/signup',
  '/auth/register',
  '/auth/forgot-password',
  '/api/i18n',
  ...PLUGIN_PUBLIC_ROUTES,
];

// 定义不需要token的API路径
export const PUBLIC_API_ROUTES = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/forgot-password',
  '/api/auth/reset-password',
  '/api/public',
  '/api/i18n', // 国际化资源不需要认证
];

/**
 * 检查路径是否为公开路由（不需要认证）
 * @param pathname 路径
 * @returns 是否为公开路由
 */
// Static asset extensions served from /public must bypass auth so that
// unauthenticated pages (login / landing) can render brand assets, PWA icons, etc.
const STATIC_ASSET_EXT = /\.(png|jpe?g|gif|svg|webp|avif|ico|webmanifest|json|txt|xml|map|js|mjs|css|woff2?|ttf|otf|mp4|webm)$/i;

export function isPublicRoute(pathname: string): boolean {
  if (pathname === '/') return true;
  if (STATIC_ASSET_EXT.test(pathname)) return true;
  return PUBLIC_ROUTES.some((route) => pathname === route || pathname.startsWith(`${route}/`));
}

/**
 * 检查API路径是否为公开API（不需要token）
 * @param apiPath API路径
 * @returns 是否为公开API
 */
export function isPublicApiRoute(apiPath: string): boolean {
  return PUBLIC_API_ROUTES.some(
    (route) => apiPath === route || apiPath.startsWith(`${route}/`) || apiPath.startsWith(route),
  );
}

export function createSessionMiddleware(): unstable_MiddlewareFunction<Response> {
  return async ({ request }, next) => {
    let session = await sessionStorage.getSession(request.headers.get('Cookie'));

    // 获取当前请求的URL路径
    const url = new URL(request.url);
    const pathname = url.pathname;

    // 检查当前路径是否为公开路由
    const isPublic = isPublicRoute(pathname);

    const token = session.get(JWT_TOKEN_KEY);

    // 如果不是公开路由且没有token，则重定向到登录页面
    if (!isPublic && !token) {
      // 将当前路径编码并作为redirectTo参数传递
      const loginUrl = `/login?redirectTo=${encodeURIComponent(pathname)}`;
      throw redirect(loginUrl, 302);
    }

    let response = await next();
    return response;
  };
}
