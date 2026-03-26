/**
 * Report Viewer — runtime report display route
 */

import { useParams } from 'react-router';
import { ReportPageContent } from '~/report-designer';

export default function ReportViewPage() {
  const { pageKey } = useParams();

  if (!pageKey) {
    return <div className="p-8 text-red-600">Missing report page key</div>;
  }

  return <ReportPageContent pageKey={pageKey} />;
}
