/**
 * Dashboard Designer 编辑页面路由
 */

import { useParams } from 'react-router';
import { DashboardDesigner } from '~/dashboard-designer';

export default function DashboardDesignerEditPage() {
  const { id } = useParams();

  return <DashboardDesigner dashboardId={id} />;
}
