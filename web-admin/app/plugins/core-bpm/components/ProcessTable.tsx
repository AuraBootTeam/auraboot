/**
 * ProcessTable - Process list table for "My Started Processes" tab
 */

import { useState, useCallback } from 'react';
import { Button } from '~/ui/ui/button';
import { MoreHorizontal, RefreshCw, PlayCircle } from 'lucide-react';
import { DateTime } from '~/ui/DateTime';
import type { ProcessInstance } from '../services/bpmWorkbenchService';

function StatusBadge({ status }: { status: string }) {
  const config: Record<string, { label: string; className: string }> = {
    running: { label: '进行中', className: 'bg-status-blue-bg text-status-blue' },
    completed: { label: '已完成', className: 'bg-status-gray-bg text-status-gray' },
    suspended: { label: '已暂停', className: 'bg-status-amber-bg text-status-amber' },
    terminated: { label: '已终止', className: 'bg-status-red-bg text-status-red' },
    aborted: { label: '已终止', className: 'bg-status-red-bg text-status-red' },
  };
  const normalizedStatus = status?.toLowerCase() || 'unknown';
  const { label, className } = config[normalizedStatus] || {
    label: status,
    className: 'bg-status-gray-bg text-status-gray',
  };
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${className}`}
    >
      {label}
    </span>
  );
}

// ==================== Types ====================

export interface ProcessTableProps {
  processes: ProcessInstance[];
  loading: boolean;
  onViewDetail: (process: ProcessInstance) => void;
  onSuspend: (process: ProcessInstance) => void;
  onResume: (process: ProcessInstance) => void;
  onTerminate: (process: ProcessInstance) => void;
}

// ==================== Component ====================

export function ProcessTable({
  processes,
  loading,
  onViewDetail,
  onSuspend,
  onResume,
  onTerminate,
}: ProcessTableProps) {
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);

  if (loading) {
    return (
      <div className="text-text-2 flex items-center justify-center py-8">
        <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
        加载中...
      </div>
    );
  }

  if (processes.length === 0) {
    return (
      <div className="text-text-3 flex flex-col items-center justify-center py-12">
        <PlayCircle className="mb-4 h-12 w-12 opacity-20" />
        <p>暂无流程</p>
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="text-text-2 w-full text-left text-sm">
        <thead className="border-border bg-subtle text-text-3 border-b text-xs uppercase">
          <tr>
            <th className="px-4 py-3">流程名称</th>
            <th className="px-4 py-3">业务标识</th>
            <th className="px-4 py-3">发起时间</th>
            <th className="px-4 py-3">状态</th>
            <th className="w-24 px-4 py-3">操作</th>
          </tr>
        </thead>
        <tbody className="divide-border divide-y">
          {processes.map((process) => (
            <ProcessRow
              key={process.instanceId}
              process={process}
              isMenuOpen={openMenuId === process.instanceId}
              onToggleMenu={setOpenMenuId}
              onViewDetail={onViewDetail}
              onSuspend={onSuspend}
              onResume={onResume}
              onTerminate={onTerminate}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ==================== Row ====================

function ProcessRow({
  process,
  isMenuOpen,
  onToggleMenu,
  onViewDetail,
  onSuspend,
  onResume,
  onTerminate,
}: {
  process: ProcessInstance;
  isMenuOpen: boolean;
  onToggleMenu: (id: string | null) => void;
  onViewDetail: (p: ProcessInstance) => void;
  onSuspend: (p: ProcessInstance) => void;
  onResume: (p: ProcessInstance) => void;
  onTerminate: (p: ProcessInstance) => void;
}) {
  const handleToggle = useCallback(
    () => onToggleMenu(isMenuOpen ? null : process.instanceId),
    [onToggleMenu, isMenuOpen, process.instanceId],
  );

  const menuAction = (fn: () => void) => {
    fn();
    onToggleMenu(null);
  };

  return (
    <tr className="transition-colors hover:bg-hover">
      <td className="px-4 py-3">
        <div className="flex flex-col">
          <span className="font-medium">{process.title || process.processDefinitionKey}</span>
          <span className="text-text-3 text-xs">{process.instanceId}</span>
        </div>
      </td>
      <td className="px-4 py-3">{process.businessKey || '-'}</td>
      <td className="text-text-3 px-4 py-3 text-xs">
        <DateTime value={process.startTime} />
      </td>
      <td className="px-4 py-3">
        <StatusBadge status={process.status} />
      </td>
      <td className="relative px-4 py-3">
        <Button variant="ghost" size="sm" onClick={handleToggle}>
          <MoreHorizontal className="h-4 w-4" />
        </Button>
        {isMenuOpen && (
          <div className="border-border bg-panel shadow-pop absolute right-0 z-10 mt-2 w-48 origin-top-right rounded-md border py-1">
            <button
              className="text-text-2 hover:bg-hover block w-full px-4 py-2 text-left text-sm"
              onClick={() => menuAction(() => onViewDetail(process))}
            >
              查看详情
            </button>
            {process.status === 'running' && (
              <button
                className="text-text-2 hover:bg-hover block w-full px-4 py-2 text-left text-sm"
                onClick={() => menuAction(() => onSuspend(process))}
              >
                暂停流程
              </button>
            )}
            {process.status === 'suspended' && (
              <button
                className="text-text-2 hover:bg-hover block w-full px-4 py-2 text-left text-sm"
                onClick={() => menuAction(() => onResume(process))}
              >
                恢复流程
              </button>
            )}
            {process.status !== 'completed' && process.status !== 'terminated' && process.status !== 'aborted' && (
              <button
                className="text-status-red hover:bg-hover block w-full px-4 py-2 text-left text-sm"
                onClick={() => menuAction(() => onTerminate(process))}
              >
                终止流程
              </button>
            )}
          </div>
        )}
      </td>
    </tr>
  );
}
