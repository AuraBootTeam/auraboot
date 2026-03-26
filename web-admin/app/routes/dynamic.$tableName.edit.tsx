/**
 * Dynamic Edit Page — thin route wrapper
 *
 * Loader extracts tableName, recordId, and auth token.
 * Rendering is delegated to DynamicPageRenderer → FormPageContent via the
 * admin profile's pageRenderers registry.
 */

import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/services/session';
import { DynamicPageRenderer } from '~/meta/rendering/pages/DynamicPageRenderer';

export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { tableName, recordId } = params;
  if (!tableName || !recordId) {
    throw new Response('Table name and record ID are required', { status: 400 });
  }

  try {
    const token = await getTokenFromRequest(request);
    return { tableName, recordId, token };
  } catch (error) {
    console.error('Failed to load edit page:', error);
    if (error instanceof Response) {
      throw error;
    }
    throw new Response('Failed to load edit page', { status: 500 });
  }
};

export default function DynamicFormEdit() {
  const { tableName, recordId, token } = useLoaderData<typeof loader>();
  return (
    <DynamicPageRenderer tableName={tableName} pageType="edit" token={token} recordId={recordId} />
  );
}
