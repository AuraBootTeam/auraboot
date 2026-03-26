/**
 * Query Builder Route
 */

import { QueryBuilder } from '~/query-builder/QueryBuilder';

export default function QueryBuilderPage() {
  return (
    <div className="h-[calc(100vh-64px)]">
      <QueryBuilder />
    </div>
  );
}
