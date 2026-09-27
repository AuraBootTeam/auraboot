/**
 * Custom Page Route — /p/c/{pageKey}
 *
 * For non-CRUD pages (dashboards, composite, kanban, custom views).
 * Uses the URL segment directly as pageKey — no suffix derivation.
 */

import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';

export const loader = async ({ params }: LoaderFunctionArgs) => {
  const { pageKey } = params;
  if (!pageKey) {
    throw new Response('Page key is required', { status: 400 });
  }

  return { pageKey };
};

export default function CustomPage() {
  const { pageKey } = useLoaderData<typeof loader>();
  return (
    <DynamicPageRenderer
      tableName={pageKey}
      pageType="list"
      pageKey={pageKey}
    />
  );
}
