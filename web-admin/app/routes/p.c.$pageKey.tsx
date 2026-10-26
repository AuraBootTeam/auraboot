/**
 * Custom Page Route — /p/c/{pageKey}
 *
 * For non-CRUD pages (dashboards, composite, kanban, custom views).
 * Uses the URL segment directly as pageKey — no suffix derivation.
 */

import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';
import { DynamicPageUnavailable } from '~/framework/meta/rendering/pages/DynamicPageUnavailable';
import { getTokenFromRequest } from '~/shared/services/session';
import { resolveDynamicPageAccessError } from '~/shared/services/dynamic-page-access.server';

export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pageKey } = params;
  if (!pageKey) {
    throw new Response('Page key is required', { status: 400 });
  }

  const token = await getTokenFromRequest(request);
  const accessError = await resolveDynamicPageAccessError(
    request,
    token,
    pageKey,
    `/p/c/${pageKey}`,
  );
  return { pageKey, accessError };
};

export default function CustomPage() {
  const { pageKey, accessError } = useLoaderData<typeof loader>();
  if (accessError) return <DynamicPageUnavailable message={accessError} />;
  return <DynamicPageRenderer tableName={pageKey} pageType="list" pageKey={pageKey} />;
}
