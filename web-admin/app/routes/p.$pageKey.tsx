/**
 * Dynamic List Page — /p/{model_code}
 *
 * URL segment is the model_code. PageKey derived as {model_code}_list.
 */

import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/shared/services/session';
import { fetchResult } from '~/shared/services/http-client';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';

type LoaderData = {
  tableName: string;
  token: string | null;
  accessError: string | null;
};

async function resolveMenuAccessError(request: Request, token: string | null): Promise<string | null> {
  if (!token) return null;

  const pathname = new URL(request.url).pathname;
  const menuResult = await fetchResult<unknown>(
    '/api/menu/by-path',
    {
      method: 'get',
      params: { path: pathname },
      token,
      timeout: 5_000,
    },
    request,
  );

  if (String(menuResult.code) === '403') {
    return menuResult.message || menuResult.desc || 'Access denied';
  }

  // Preserve compatibility for dynamic pages that are intentionally not mounted
  // as menus: success(null), 404, or transient lookup errors should not block
  // the existing /p/:pageKey fallback renderer.
  return null;
}

export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pageKey } = params;
  if (!pageKey) {
    throw new Response('Page key is required', { status: 400 });
  }

  try {
    const token = await getTokenFromRequest(request);
    const accessError = await resolveMenuAccessError(request, token);
    return { tableName: pageKey, token, accessError } satisfies LoaderData;
  } catch (error) {
    console.error('Failed to load dynamic page:', error);
    if (error instanceof Response) {
      throw error;
    }
    throw new Response('Failed to load page schema', { status: 500 });
  }
};

export default function DynamicTableList() {
  const { tableName, token, accessError } = useLoaderData<typeof loader>();
  if (accessError) {
    return <PageUnavailable message={accessError} />;
  }
  return <DynamicPageRenderer tableName={tableName} pageType="list" token={token} />;
}

function PageUnavailable({ message }: { message: string }) {
  return (
    <div className="flex min-h-screen items-center justify-center">
      <div className="max-w-md rounded-lg bg-white p-6 shadow-md">
        <div className="mb-4 flex items-center gap-3">
          <svg
            className="h-6 w-6 text-amber-500"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
            />
          </svg>
          <h2 className="text-lg font-semibold text-gray-900">Page Unavailable</h2>
        </div>
        <p className="text-gray-600">{message || 'Access denied'}</p>
      </div>
    </div>
  );
}
