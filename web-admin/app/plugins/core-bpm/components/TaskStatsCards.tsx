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
        icon={<Clock className="text-text-3 h-4 w-4" />}
      />
      <StatCard
        label="已办任务"
        value={data?.completedCount || 0}
        icon={<CheckCircle2 className="text-text-3 h-4 w-4" />}
      />
      <StatCard
        label="我发起的"
        value={data?.startedCount || 0}
        icon={<PlayCircle className="text-text-3 h-4 w-4" />}
      />
      {slaWarningCount !== undefined && (
        <StatCard
          label="SLA 预警"
          value={slaWarningCount}
          icon={<AlertTriangle className="text-status-amber h-4 w-4" />}
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
      className={`rounded-card border-border bg-panel shadow-card border p-5 ${
        highlight ? 'border-status-amber bg-status-amber-bg' : ''
      }`}
    >
      <div className="flex items-center justify-between">
        <h3 className="text-text-2 text-sm font-medium">{label}</h3>
        {icon}
      </div>
      <p className={`text-text mt-2 text-2xl font-bold ${highlight ? 'text-status-amber' : ''}`}>
        {value}
      </p>
    </div>
  );
}
