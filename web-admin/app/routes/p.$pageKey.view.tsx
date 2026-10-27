/**
 * Dynamic Detail/View Page — /p/{model_code}/view/{recordId}
 *
 * URL segment is the model_code. PageKey derived as {model_code}_detail.
 */

import { data, useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/shared/services/session';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';
import { DynamicPageUnavailable } from '~/framework/meta/rendering/pages/DynamicPageUnavailable';
import { resolveDynamicPageAccessError } from '~/shared/services/dynamic-page-access.server';

export function resolveRouteRecordPid(params: LoaderFunctionArgs['params']): string | undefined {
  return params.recordPid ?? params.recordId;
}

export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const modal = new URL(request.url).searchParams.get('presentation') === 'modal';
  const { pageKey } = params;
  const recordPid = resolveRouteRecordPid(params);
  if (!pageKey || !recordPid) {
    throw new Response('Page key and record ID are required', { status: 400 });
  }

  try {
    const token = await getTokenFromRequest(request);
    const accessError = await resolveDynamicPageAccessError(request, token, pageKey);
    const result = {
      tableName: pageKey,
      recordPid,
      accessError,
      httpStatus: accessError ? 403 : 200,
    };
    return modal && accessError ? data(result, { status: 403 }) : result;
  } catch (error) {
    if (modal) {
      const status = error instanceof Response ? error.status : 500;
      return data(
        {
          tableName: pageKey,
          recordPid,
          accessError: status === 403 ? 'Access denied' : null,
          loadError: 'Detail loader failed',
          httpStatus: status,
        },
        { status },
      );
    }
    console.error('Failed to load record:', error);
    if (error instanceof Response) {
      throw error;
    }
    throw new Response('Failed to load record data', { status: 500 });
  }
};

export default function DynamicDetailView() {
  const { tableName, recordPid, accessError } = useLoaderData<typeof loader>();
  if (accessError) {
    return <DynamicPageUnavailable message={accessError} />;
  }
  return (
    <DynamicPageRenderer
      key={`${tableName}:${recordPid}`}
      tableName={tableName}
      pageType="detail"
      recordPid={recordPid}
    />
  );
}
