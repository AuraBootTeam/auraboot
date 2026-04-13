/**
 * Dynamic Detail/View Page — /p/{model_code}/view/{recordId}
 *
 * URL segment is the model_code. PageKey derived as {model_code}_detail.
 */

import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/services/session';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';

export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pageKey, recordId } = params;
  if (!pageKey || !recordId) {
    throw new Response('Page key and record ID are required', { status: 400 });
  }

  try {
    const token = await getTokenFromRequest(request);
    return { tableName: pageKey, recordId, token };
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
