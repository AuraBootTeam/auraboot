import { useParams } from 'react-router';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';

export const loader = async () => ({});

export default function TeamDslDetailPage() {
  const { teamPid } = useParams();
  return (
    <DynamicPageRenderer
      tableName="ab_team"
      pageType="detail"
      pageKey="ab_team_detail"
      recordPid={teamPid}
    />
  );
}
