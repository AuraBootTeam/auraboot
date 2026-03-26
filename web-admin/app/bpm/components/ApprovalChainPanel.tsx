/**
 * Approval chain timeline panel
 */

import { useState, useEffect } from 'react';
import { RefreshCw } from 'lucide-react';
import { useToastContext } from '~/contexts/ToastContext';
import { getApprovalChain, type ApprovalChainReport } from '../services/bpmReportService';

// ==================== Helper Functions ====================

function formatDate(dateString?: string): string {
  if (!dateString) return '-';
  try {
    return new Date(dateString).toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return dateString;
  }
}

function getEventColor(eventType: string): string {
  switch (eventType) {
    case 'process_start':
      return 'border-green-500 bg-green-50';
    case 'process_end':
      return 'border-gray-500 bg-gray-50';
    case 'task_completed':
      return 'border-blue-500 bg-blue-50';
    case 'jump_to_node':
      return 'border-orange-500 bg-orange-50';
    default:
      return 'border-blue-500 bg-white';
  }
}

// ==================== Component ====================

interface ApprovalChainPanelProps {
  processInstanceId: string;
}

export function ApprovalChainPanel({ processInstanceId }: ApprovalChainPanelProps) {
  const { showErrorToast } = useToastContext();
  const [report, setReport] = useState<ApprovalChainReport | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadReport();
  }, [processInstanceId]);

  const loadReport = async () => {
    setLoading(true);
    try {
      const data = await getApprovalChain(processInstanceId);
      setReport(data);
    } catch (error) {
      console.error('Failed to load approval chain:', error);
      showErrorToast('Failed to load approval chain');
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-8 text-gray-500">
        <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
        Loading...
      </div>
    );
  }

  if (!report) {
    return <div className="p-4 text-center text-gray-500">No data</div>;
  }

  return (
    <div className="p-4">
      <h3 className="mb-4 text-lg font-semibold">Approval Chain ({report.totalSteps} steps)</h3>

      <div className="relative">
        <div className="absolute top-0 bottom-0 left-4 w-0.5 bg-gray-200" />

        <div className="space-y-4">
          {report.chain.map((entry, index) => (
            <div key={entry.id || index} className="relative flex items-start gap-4 pl-10">
              <div
                className={`absolute left-2 flex h-5 w-5 items-center justify-center rounded-full border-2 text-xs ${getEventColor(entry.eventType)}`}
              />
              <div className="flex-1 rounded-lg border border-gray-200 bg-white p-3">
                <div className="flex items-start justify-between">
                  <span className="text-sm font-medium text-gray-800">{entry.eventType}</span>
                  <span className="text-xs text-gray-500">{formatDate(entry.timestamp)}</span>
                </div>
                {entry.description && (
                  <p className="mt-1 text-sm text-gray-600">{entry.description}</p>
                )}
                {entry.operatorId && (
                  <p className="mt-1 text-xs text-gray-500">Operator: {entry.operatorId}</p>
                )}
                {entry.activityId && (
                  <p className="text-xs text-gray-500">Node: {entry.activityId}</p>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
