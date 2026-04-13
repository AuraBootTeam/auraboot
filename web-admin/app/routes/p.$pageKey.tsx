/**
 * Dynamic List Page — /p/{model_code}
 *
 * URL segment is the model_code. PageKey derived as {model_code}_list.
 */

import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/shared/services/session';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';

export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pageKey } = params;
  if (!pageKey) {
    throw new Response('Page key is required', { status: 400 });
  }

  try {
    const token = await getTokenFromRequest(request);
    return { tableName: pageKey, token };
  } catch (error) {
    console.error('Failed to load dynamic page:', error);
    if (error instanceof Response) {
      throw error;
    }
    throw new Response('Failed to load page schema', { status: 500 });
  }
};

export default function DynamicTableList() {
  const { tableName, token } = useLoaderData<typeof loader>();
  return <DynamicPageRenderer tableName={tableName} pageType="list" token={token} />;
}
