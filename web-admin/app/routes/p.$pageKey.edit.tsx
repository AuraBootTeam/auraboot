/**
 * Dynamic Edit Page — /p/{model_code}/edit/{recordId}
 *
 * URL segment is the model_code. PageKey derived as {model_code}_form.
 */

import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/shared/services/session';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';
import { DynamicPageUnavailable } from '~/framework/meta/rendering/pages/DynamicPageUnavailable';
import { resolveDynamicPageAccessError } from '~/shared/services/dynamic-page-access.server';

export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pageKey } = params;
  const recordPid = params.recordPid ?? params.recordId;
  if (!pageKey || !recordPid) {
    throw new Response('Page key and record ID are required', { status: 400 });
  }

  try {
    const token = await getTokenFromRequest(request);
    const accessError = await resolveDynamicPageAccessError(request, token, pageKey);
    return { tableName: pageKey, recordPid, token, accessError };
  } catch (error) {
    console.error('Failed to load edit page:', error);
    if (error instanceof Response) {
      throw error;
    }
    throw new Response('Failed to load edit page', { status: 500 });
  }
};

export default function DynamicFormEdit() {
  const { tableName, recordPid, token, accessError } = useLoaderData<typeof loader>();
  if (accessError) {
    return <DynamicPageUnavailable message={accessError} />;
  }
  return (
    <DynamicPageRenderer tableName={tableName} pageType="form" token={token} recordPid={recordPid} />
  );
}
