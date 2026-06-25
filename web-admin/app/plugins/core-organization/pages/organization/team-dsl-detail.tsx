import { useLoaderData, useParams } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';
import { getTokenFromRequest } from '~/shared/services/session';

export const loader = async ({ request }: LoaderFunctionArgs) => {
  const token = await getTokenFromRequest(request);
  return { token };
};

export default function TeamDslDetailPage() {
  const { token } = useLoaderData<typeof loader>();
  const { teamPid } = useParams();
  return (
    <DynamicPageRenderer
      tableName="ab_team"
      pageType="detail"
      pageKey="ab_team_detail"
      recordPid={teamPid}
      token={token}
    />
  );
}
