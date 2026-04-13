/**
 * ApprovalTimeline — displays the approval history of a business record
 * as a vertical timeline with status icons, approver names, and timestamps.
 *
 * Data source: BPM approval chain report API.
 */

import { useState, useEffect } from 'react';
import {
  CheckCircle2,
  XCircle,
  Clock,
  PlayCircle,
  RefreshCw,
  User,
  Send,
  ArrowRight,
} from 'lucide-react';
import { cn } from '~/utils/cn';
import { useToastContext } from '~/contexts/ToastContext';
import { DateTime } from '~/ui/DateTime';
import { getApprovalChain, type ApprovalChainEntry } from '../services/bpmReportService';

// ==================== Types ====================

interface ApprovalTimelineProps {
  /** Process instance ID to load the approval chain for */
  processInstanceId: string;
  /** Compact mode hides descriptions and details */
  compact?: boolean;
  className?: string;
}

// ==================== Helpers ====================

const EVENT_CONFIG: Record<
  string,
  {
    icon: typeof CheckCircle2;
    color: string;
    bgColor: string;
    lineColor: string;
    label: string;
  }
> = {
  PROCESS_START: {
    icon: Send,
    color: 'text-green-600',
    bgColor: 'bg-green-50 border-green-200',
    lineColor: 'bg-green-300',
    label: 'Submitted',
  },
  TASK_COMPLETED: {
    icon: CheckCircle2,
    color: 'text-blue-600',
    bgColor: 'bg-blue-50 border-blue-200',
    lineColor: 'bg-blue-300',
    label: 'Approved',
  },
  TASK_REJECTED: {
    icon: XCircle,
    color: 'text-red-600',
    bgColor: 'bg-red-50 border-red-200',
    lineColor: 'bg-red-300',
    label: 'Rejected',
  },
  PROCESS_END: {
    icon: CheckCircle2,
    color: 'text-gray-600',
    bgColor: 'bg-gray-50 border-gray-200',
    lineColor: 'bg-gray-300',
    label: 'Completed',
  },
  JUMP_TO_NODE: {
    icon: ArrowRight,
    color: 'text-orange-600',
    bgColor: 'bg-orange-50 border-orange-200',
    lineColor: 'bg-orange-300',
    label: 'Jumped',
  },
};

const DEFAULT_CONFIG = {
  icon: PlayCircle,
  color: 'text-gray-500',
  bgColor: 'bg-gray-50 border-gray-200',
  lineColor: 'bg-gray-200',
  label: 'In Progress',
};

function getConfig(eventType: string) {
  return EVENT_CONFIG[eventType] || DEFAULT_CONFIG;
}

// Date formatting handled by <DateTime> component

// ==================== Component ====================

export function ApprovalTimeline({
  processInstanceId,
  compact = false,
  className,
}: ApprovalTimelineProps) {
  const { showErrorToast } = useToastContext();
  const [entries, setEntries] = useState<ApprovalChainEntry[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!processInstanceId) return;
    let cancelled = false;

    const load = async () => {
      setLoading(true);
      try {
        const report = await getApprovalChain(processInstanceId);
        if (!cancelled) {
          setEntries(report.chain || []);
        }
      } catch (error) {
        if (!cancelled) {
          console.error('Failed to load approval timeline:', error);
          showErrorToast('Failed to load approval timeline');
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    load();
    return () => {
      cancelled = true;
    };
  }, [processInstanceId]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-6 text-sm text-gray-400">
        <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
        Loading approval timeline...
      </div>
    );
  }

  if (entries.length === 0) {
    return <div className="py-4 text-center text-sm text-gray-400">No approval history</div>;
  }

  return (
    <div className={cn('relative', className)}>
      {/* Vertical connector line */}
      <div className="absolute top-5 bottom-5 left-[19px] w-0.5 bg-gray-200" />

      <div className="space-y-1">
        {entries.map((entry, index) => {
          const config = getConfig(entry.eventType);
          const Icon = config.icon;
          const isLast = index === entries.length - 1;

          return (
            <div key={entry.id || index} className="relative flex items-start gap-3 py-2">
              {/* Icon circle */}
              <div
                className={cn(
                  'relative z-10 flex h-10 w-10 shrink-0 items-center justify-center rounded-full border',
                  config.bgColor,
                )}
              >
                <Icon className={cn('h-5 w-5', config.color)} />
              </div>

              {/* Content */}
              <div className="min-w-0 flex-1 pt-1">
                <div className="flex items-center justify-between gap-2">
                  <div className="flex min-w-0 items-center gap-2">
                    <span className="truncate text-sm font-medium text-gray-900">
                      {entry.description || config.label}
                    </span>
                    {entry.operatorId && (
                      <span className="inline-flex items-center gap-1 text-xs text-gray-500">
                        <User className="h-3 w-3" />
                        {entry.operatorId}
                      </span>
                    )}
                  </div>
                  <DateTime
                    value={entry.timestamp}
                    className="text-xs whitespace-nowrap text-gray-400"
                  />
                </div>

                {!compact && entry.activityId && (
                  <p className="mt-0.5 text-xs text-gray-400">Node: {entry.activityId}</p>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
