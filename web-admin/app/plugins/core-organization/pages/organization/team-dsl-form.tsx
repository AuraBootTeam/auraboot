import { useParams } from 'react-router';
import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';

export const loader = async () => ({});

export default function TeamDslFormPage() {
  const { teamPid } = useParams();
  return (
    <DynamicPageRenderer
      tableName="ab_team"
      pageType="form"
      pageKey="ab_team_form"
      recordPid={teamPid}
    />
  );
}
