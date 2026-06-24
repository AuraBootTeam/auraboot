/**
 * Custom Form Edit Page — /p/c/{pageKey}/edit/{recordPid}
 *
 * Uses the URL pageKey to load a custom form schema, then uses that schema's
 * modelCode to load the edited record.
 */

import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';
import { getTokenFromRequest } from '~/shared/services/session';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

interface PageInfo {
  modelCode?: string;
  kind?: string;
}

export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pageKey, recordPid } = params;
  if (!pageKey || !recordPid) {
    throw new Response('Page key and record ID are required', { status: 400 });
  }

  try {
    const token = await getTokenFromRequest(request);
    const pageResult = await fetchResult<PageInfo>(
      `/api/pages/key/${pageKey}`,
      { method: 'get', token },
      request,
    );

    if (!ResultHelper.isSuccess(pageResult) || !pageResult.data) {
      throw new Response(`Custom page "${pageKey}" not found`, { status: 404 });
    }
    if (pageResult.data.kind && pageResult.data.kind !== 'form') {
      throw new Response(`Custom page "${pageKey}" is not a form page`, { status: 422 });
    }

    const tableName = pageResult.data.modelCode || pageKey;
    return { pageKey, recordPid, tableName, token };
  } catch (error) {
    if (error instanceof Response) {
      throw error;
    }
    console.error('Failed to load custom form edit page:', error);
    throw new Response('Failed to load custom form edit page', { status: 500 });
  }
};

export default function CustomFormEditPage() {
  const { pageKey, recordPid, tableName, token } = useLoaderData<typeof loader>();
  return (
    <DynamicPageRenderer
      tableName={tableName}
      pageType="form"
      pageKey={pageKey}
      token={token}
      recordPid={recordPid}
    />
  );
}
