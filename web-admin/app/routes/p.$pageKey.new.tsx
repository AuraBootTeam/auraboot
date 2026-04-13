/**
 * Dynamic Create Page — /p/{model_code}/new
 *
 * URL segment is the model_code. PageKey derived as {model_code}_form.
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
    console.error('Failed to load form schema:', error);
    if (error instanceof Response) {
      throw error;
    }
    throw new Response('Failed to load form schema', { status: 500 });
  }
};

export default function DynamicFormNew() {
  const { tableName, token } = useLoaderData<typeof loader>();
  return <DynamicPageRenderer tableName={tableName} pageType="form" token={token} />;
}
