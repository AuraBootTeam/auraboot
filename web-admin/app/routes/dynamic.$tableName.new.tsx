/**
 * Dynamic Create Page — thin route wrapper
 *
 * Loader extracts tableName and auth token.
 * Rendering is delegated to DynamicPageRenderer → FormPageContent via the
 * admin profile's pageRenderers registry.
 *
 */

import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/services/session';
import { DynamicPageRenderer } from '~/meta/rendering/pages/DynamicPageRenderer';

// Loader function
export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { tableName } = params;
  if (!tableName) {
    throw new Response('Table name is required', { status: 400 });
  }

  try {
    const token = await getTokenFromRequest(request);
    return { tableName, token };
  } catch (error) {
    console.error('Failed to load form schema:', error);
    if (error instanceof Response) {
      throw error;
    }
    throw new Response('Failed to load form schema', { status: 500 });
  }
};

export default function DynamicFormNew() {
  const { tableName, token } = useLoaderData<typeof loader>();

  return <DynamicPageRenderer tableName={tableName} pageType="new" token={token} />;
}
