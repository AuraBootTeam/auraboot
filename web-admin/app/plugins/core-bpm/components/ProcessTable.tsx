/**
 * ProcessTable - Process list table for "My Started Processes" tab
 */

import { useState, useCallback } from 'react';
import { Button } from '~/components/ui/button';
import { MoreHorizontal, RefreshCw, PlayCircle } from 'lucide-react';
import { DateTime } from '~/components/DateTime';
import type { ProcessInstance } from '../services/bpmWorkbenchService';

function StatusBadge({ status }: { status: string }) {
  const config: Record<string, { label: string; className: string }> = {
    running: { label: '进行中', className: 'bg-blue-100 text-blue-800' },
    completed: { label: '已完成', className: 'bg-gray-100 text-gray-800' },
    suspended: { label: '已暂停', className: 'bg-yellow-100 text-yellow-800' },
    terminated: { label: '已终止', className: 'bg-red-100 text-red-800' },
    aborted: { label: '已终止', className: 'bg-red-100 text-red-800' },
  };
  const normalizedStatus = status?.toLowerCase() || 'unknown';
  const { label, className } = config[normalizedStatus] || {
    label: status,
    className: 'bg-gray-100 text-gray-800',
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
      <div className="flex items-center justify-center py-8 text-gray-500">
        <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
        加载中...
      </div>
    );
  }

  if (processes.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-gray-500">
        <PlayCircle className="mb-4 h-12 w-12 opacity-20" />
        <p>暂无流程</p>
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="border-b bg-gray-50 text-xs uppercase">
          <tr>
            <th className="px-4 py-3">流程名称</th>
            <th className="px-4 py-3">业务标识</th>
            <th className="px-4 py-3">发起时间</th>
            <th className="px-4 py-3">状态</th>
            <th className="w-24 px-4 py-3">操作</th>
          </tr>
        </thead>
        <tbody>
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
    <tr className="border-b hover:bg-gray-50">
      <td className="px-4 py-3">
        <div className="flex flex-col">
          <span className="font-medium">{process.title || process.processDefinitionKey}</span>
          <span className="text-xs text-gray-500">{process.instanceId}</span>
        </div>
      </td>
      <td className="px-4 py-3">{process.businessKey || '-'}</td>
      <td className="px-4 py-3 text-xs text-gray-500">
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
          <div className="ring-opacity-5 absolute right-0 z-10 mt-2 w-48 origin-top-right rounded-md bg-white py-1 shadow-lg ring-1 ring-black">
            <button
              className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
              onClick={() => menuAction(() => onViewDetail(process))}
            >
              查看详情
            </button>
            {process.status === 'running' && (
              <button
                className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
                onClick={() => menuAction(() => onSuspend(process))}
              >
                暂停流程
              </button>
            )}
            {process.status === 'suspended' && (
              <button
                className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
                onClick={() => menuAction(() => onResume(process))}
              >
                恢复流程
              </button>
            )}
            {process.status !== 'completed' && process.status !== 'terminated' && process.status !== 'aborted' && (
              <button
                className="block w-full px-4 py-2 text-left text-sm text-red-600 hover:bg-gray-100"
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
