/**
 * Report Viewer — runtime report display route
 */

import { useParams, useLocation } from 'react-router';
import { ReportPageContent } from '~/plugins/core-designer/components/report-designer';

export default function ReportViewPage() {
  const { pageKey } = useParams();
  const { search } = useLocation();

  if (!pageKey) {
    return <div className="p-8 text-red-600">Missing report page key</div>;
  }

  return <ReportPageContent pageKey={pageKey} search={search} />;
}
