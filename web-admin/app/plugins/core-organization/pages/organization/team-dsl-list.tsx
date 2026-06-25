import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';
import { getTokenFromRequest } from '~/shared/services/session';

export const loader = async ({ request }: LoaderFunctionArgs) => {
  const token = await getTokenFromRequest(request);
  return { token };
};

export default function TeamDslListPage() {
  const { token } = useLoaderData<typeof loader>();
  return (
    <DynamicPageRenderer
      tableName="ab_team"
      pageType="list"
      pageKey="ab_team_list"
      token={token}
    />
  );
}
