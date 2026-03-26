/**
 * TaskStatsCards - Statistics cards for Task Center
 */

import { Clock, CheckCircle2, PlayCircle, AlertTriangle } from 'lucide-react';
import type { WorkbenchData } from '../services/bpmWorkbenchService';

interface TaskStatsCardsProps {
  data: WorkbenchData | null;
  slaWarningCount?: number;
}

export function TaskStatsCards({ data, slaWarningCount }: TaskStatsCardsProps) {
  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-3 lg:grid-cols-4">
      <StatCard
        label="待办任务"
        value={data?.todoCount || 0}
        icon={<Clock className="h-4 w-4 text-gray-400" />}
      />
      <StatCard
        label="已办任务"
        value={data?.completedCount || 0}
        icon={<CheckCircle2 className="h-4 w-4 text-gray-400" />}
      />
      <StatCard
        label="我发起的"
        value={data?.startedCount || 0}
        icon={<PlayCircle className="h-4 w-4 text-gray-400" />}
      />
      {slaWarningCount !== undefined && (
        <StatCard
          label="SLA 预警"
          value={slaWarningCount}
          icon={<AlertTriangle className="h-4 w-4 text-orange-400" />}
          highlight={slaWarningCount > 0}
        />
      )}
    </div>
  );
}

function StatCard({
  label,
  value,
  icon,
  highlight,
}: {
  label: string;
  value: number;
  icon: React.ReactNode;
  highlight?: boolean;
}) {
  return (
    <div
      className={`rounded-lg border bg-white p-6 ${highlight ? 'border-orange-300 bg-orange-50' : ''}`}
    >
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium text-gray-500">{label}</h3>
        {icon}
      </div>
      <p className={`mt-2 text-2xl font-bold ${highlight ? 'text-orange-600' : ''}`}>{value}</p>
    </div>
  );
}
