/**
 * SLA Record List Panel
 * Drill-down panel showing filtered SLA records from the monitor dashboard.
 * Shown when a StatCard is clicked.
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { useSmartText } from '~/utils/i18n';
import { useToastContext } from '~/contexts/ToastContext';
import {
  X,
  ChevronRight,
  AlertTriangle,
  Clock,
  XCircle,
  Activity,
  PauseCircle,
  CheckCircle,
} from 'lucide-react';
import { Button } from '~/components/ui/button';
import type { SlaRecord } from '../services/slaService';
import * as slaService from '../services/slaService';

interface SlaRecordListPanelProps {
  /** Status filter from clicked StatCard, or undefined to show all */
  statusFilter?: string;
  /** Callback to close the panel */
  onClose: () => void;
}

const statusConfig: Record<string, { icon: React.ElementType; color: string; labelKey: string }> = {
  running: {
    icon: Activity,
    color: 'bg-green-100 text-green-800',
    labelKey: 'bpm.sla.status.running',
  },
  WARNING: {
    icon: AlertTriangle,
    color: 'bg-yellow-100 text-yellow-800',
    labelKey: 'bpm.sla.status.warning',
  },
  OVERDUE: { icon: XCircle, color: 'bg-red-100 text-red-800', labelKey: 'bpm.sla.status.overdue' },
  paused: {
    icon: PauseCircle,
    color: 'bg-purple-100 text-purple-800',
    labelKey: 'bpm.sla.status.paused',
  },
  completed: {
    icon: CheckCircle,
    color: 'bg-gray-100 text-gray-600',
    labelKey: 'bpm.sla.status.completed',
  },
  cancelled: {
    icon: XCircle,
    color: 'bg-gray-100 text-gray-400',
    labelKey: 'bpm.sla.status.cancelled',
  },
};

function formatTimeRemaining(deadlineTime: string, totalPausedMs?: number): string {
  const now = Date.now();
  const deadline = new Date(deadlineTime).getTime();
  const paused = totalPausedMs ?? 0;
  const remaining = deadline - now + paused;

  if (remaining <= 0) return 'Overdue';

  const hours = Math.floor(remaining / (1000 * 60 * 60));
  const minutes = Math.floor((remaining % (1000 * 60 * 60)) / (1000 * 60));

  if (hours > 24) {
    const days = Math.floor(hours / 24);
    return `${days}d ${hours % 24}h`;
  }
  return `${hours}h ${minutes}m`;
}

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString();
}

export function SlaRecordListPanel({ statusFilter, onClose }: SlaRecordListPanelProps) {
  const st = useSmartText();
  const navigate = useNavigate();
  const { showErrorToast } = useToastContext();
  const [records, setRecords] = useState<SlaRecord[]>([]);
  const [loading, setLoading] = useState(true);

  const loadRecords = useCallback(async () => {
    setLoading(true);
    try {
      const data = await slaService.listSlaRecords(
        statusFilter ? { status: statusFilter } : undefined,
      );
      setRecords(data);
    } catch {
      showErrorToast(st('$i18n:bpm.sla.drilldown.loadError') || 'Failed to load SLA records');
    } finally {
      setLoading(false);
    }
  }, [statusFilter, showErrorToast, st]);

  useEffect(() => {
    loadRecords();
  }, [loadRecords]);

  const handleRowClick = (record: SlaRecord) => {
    if (record.processInstanceId) {
      navigate(`/bpm/process-status?instanceId=${record.processInstanceId}`);
    }
  };

  const filterLabel = statusFilter
    ? st(`$i18n:bpm.sla.status.${statusFilter.toLowerCase()}`) || statusFilter
    : st('$i18n:bpm.sla.drilldown.allRecords') || 'All Records';

  return (
    <div className="rounded-lg border bg-white shadow-sm" data-testid="sla-drill-panel">
      {/* Header */}
      <div className="flex items-center justify-between rounded-t-lg border-b bg-gray-50 px-4 py-3">
        <h3 className="text-sm font-semibold text-gray-900">
          {st('$i18n:bpm.sla.drilldown.title') || 'SLA Records'} — {filterLabel}
          <span className="ml-2 text-xs font-normal text-gray-500">
            ({records.length} {st('$i18n:bpm.sla.drilldown.records') || 'records'})
          </span>
        </h3>
        <Button
          variant="ghost"
          size="sm"
          onClick={onClose}
          className="h-7 w-7 p-0"
          data-testid="sla-drill-close"
        >
          <X className="h-4 w-4" />
        </Button>
      </div>

      {/* Table */}
      {loading ? (
        <div className="text-muted-foreground py-8 text-center text-sm">
          {st('$i18n:common.loading') || 'Loading...'}
        </div>
      ) : records.length === 0 ? (
        <div
          className="text-muted-foreground py-8 text-center text-sm"
          data-testid="sla-drill-empty"
        >
          {st('$i18n:bpm.sla.drilldown.empty') || 'No SLA records found'}
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-xs font-medium text-gray-500 uppercase">
                <th className="px-4 py-2">
                  {st('$i18n:bpm.sla.drilldown.col.status') || 'Status'}
                </th>
                <th className="px-4 py-2">
                  {st('$i18n:bpm.sla.drilldown.col.processInstance') || 'Process Instance'}
                </th>
                <th className="px-4 py-2">{st('$i18n:bpm.sla.drilldown.col.node') || 'Node'}</th>
                <th className="px-4 py-2">
                  {st('$i18n:bpm.sla.drilldown.col.startTime') || 'Start Time'}
                </th>
                <th className="px-4 py-2">
                  {st('$i18n:bpm.sla.drilldown.col.deadline') || 'Deadline'}
                </th>
                <th className="px-4 py-2">
                  {st('$i18n:bpm.sla.drilldown.col.remaining') || 'Remaining'}
                </th>
                <th className="px-4 py-2">
                  {st('$i18n:bpm.sla.drilldown.col.warningLevel') || 'Warning'}
                </th>
                <th className="w-8 px-4 py-2"></th>
              </tr>
            </thead>
            <tbody>
              {records.map((record) => {
                const cfg = statusConfig[record.status] || statusConfig.RUNNING;
                const StatusIcon = cfg.icon;
                return (
                  <tr
                    key={record.pid}
                    onClick={() => handleRowClick(record)}
                    className="cursor-pointer border-b transition-colors hover:bg-gray-50"
                    data-testid="sla-record-row"
                  >
                    <td className="px-4 py-2.5">
                      <span
                        className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${cfg.color}`}
                      >
                        <StatusIcon className="h-3 w-3" />
                        {st(`$i18n:${cfg.labelKey}`) || record.status}
                      </span>
                    </td>
                    <td className="px-4 py-2.5 font-mono text-xs text-gray-700">
                      {record.processInstanceId?.slice(0, 12)}...
                    </td>
                    <td className="px-4 py-2.5 text-gray-600">
                      {record.nodeId || record.taskId || '—'}
                    </td>
                    <td className="px-4 py-2.5 text-gray-600">
                      {formatDateTime(record.startTime)}
                    </td>
                    <td className="px-4 py-2.5 text-gray-600">
                      {formatDateTime(record.deadlineTime)}
                    </td>
                    <td className="px-4 py-2.5">
                      <span
                        className={
                          record.status === 'overdue' ? 'font-medium text-red-600' : 'text-gray-700'
                        }
                      >
                        {record.status === 'completed' || record.status === 'cancelled'
                          ? '—'
                          : formatTimeRemaining(record.deadlineTime, record.totalPausedMs)}
                      </span>
                    </td>
                    <td className="px-4 py-2.5 text-center">
                      {record.currentWarningLevel != null && record.currentWarningLevel > 0 ? (
                        <span className="font-medium text-yellow-600">
                          L{record.currentWarningLevel}
                        </span>
                      ) : (
                        <span className="text-gray-400">—</span>
                      )}
                    </td>
                    <td className="px-4 py-2.5">
                      <ChevronRight className="h-4 w-4 text-gray-400" />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
