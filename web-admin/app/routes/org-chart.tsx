/**
 * Organization Chart Route
 */

import { OrgTreeChart } from '~/smart/components/org/OrgTreeChart';

export default function OrgChartPage() {
  return (
    <div className="h-[calc(100vh-64px)]">
      <OrgTreeChart />
    </div>
  );
}
