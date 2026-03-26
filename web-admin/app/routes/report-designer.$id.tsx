/**
 * Report Designer — edit existing report route
 */

import { useParams } from 'react-router';
import { ReportDesigner } from '~/report-designer';

export default function ReportDesignerEditPage() {
  const { id } = useParams();

  return <ReportDesigner reportId={id} />;
}
