import { fetchResult } from '~/shared/services/http-client';

export async function resolveDynamicPageAccessError(
  request: Request,
  token: string | null,
  pageKey: string,
): Promise<string | null> {
  if (!token) return null;

  const menuResult = await fetchResult<unknown>(
    '/api/menu/by-path',
    {
      method: 'get',
      params: { path: `/p/${pageKey}` },
      token,
      timeout: 5_000,
    },
    request,
  );

  if (String(menuResult.code) === '403') {
    return menuResult.message || menuResult.desc || 'Access denied';
  }

  // Dynamic pages do not have to be mounted as menus. Only an explicit 403
  // blocks the route; success(null), 404, and transient lookup errors retain
  // compatibility with the model-driven fallback renderer.
  return null;
}
