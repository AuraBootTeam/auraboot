/**
 * Custom Page Route — /p/c/{pageKey}
 *
 * For non-CRUD pages (dashboards, composite, kanban, custom views).
 * Uses the URL segment directly as pageKey — no suffix derivation.
 */

import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/services/session';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';

export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pageKey } = params;
  if (!pageKey) {
    throw new Response('Page key is required', { status: 400 });
  }

  try {
    const token = await getTokenFromRequest(request);
    return { pageKey, token };
  } catch (error) {
    console.error('Failed to load custom page:', error);
    if (error instanceof Response) {
      throw error;
    }
    throw new Response('Failed to load custom page', { status: 500 });
  }
};

export default function CustomPage() {
  const { pageKey, token } = useLoaderData<typeof loader>();
  return (
    <DynamicPageRenderer
      tableName={pageKey}
      pageType="list"
      pageKey={pageKey}
      token={token}
    />
  );
}
