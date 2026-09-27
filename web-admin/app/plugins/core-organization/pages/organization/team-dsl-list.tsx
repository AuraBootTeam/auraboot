import { DynamicPageRenderer } from '~/framework/meta/rendering/pages/DynamicPageRenderer';

export const loader = async () => ({});

export default function TeamDslListPage() {
  return (
    <DynamicPageRenderer
      tableName="ab_team"
      pageType="list"
      pageKey="ab_team_list"
    />
  );
}
