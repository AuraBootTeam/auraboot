/**
 * Dynamic Detail/View Page — thin route wrapper
 *
 * Loader extracts tableName, recordId, and auth token.
 * Rendering is delegated to DynamicPageRenderer → DetailPageContent via the
 * admin profile's pageRenderers registry.
 */

import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/services/session';
import { DynamicPageRenderer } from '~/meta/rendering/pages/DynamicPageRenderer';

// Loader function
export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { tableName, recordId } = params;
  if (!tableName || !recordId) {
    throw new Response('Table name and record ID are required', { status: 400 });
  }

  try {
    const token = await getTokenFromRequest(request);
    return { tableName, recordId, token };
  } catch (error) {
    console.error('Failed to load record:', error);
    if (error instanceof Response) {
      throw error;
    }
    throw new Response('Failed to load record data', { status: 500 });
  }
};

export default function DynamicDetailView() {
  const { tableName, recordId, token } = useLoaderData<typeof loader>();
  return (
    <DynamicPageRenderer
      tableName={tableName}
      pageType="detail"
      token={token}
      recordId={recordId}
    />
  );
}
