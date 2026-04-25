/**
 * TaskTable - Task list table with priority and due date columns
 * Used for both todo and completed task tabs.
 */

import { useState, useCallback } from 'react';
import dayjs from 'dayjs';
import { Button } from '~/ui/ui/button';
import { Checkbox } from '~/ui/ui/checkbox';
import { MoreHorizontal, RefreshCw, CheckCircle2, User, AlertCircle, Clock } from 'lucide-react';
import { DateTime } from '~/ui/DateTime';
import type { TaskInstance } from '../services/bpmWorkbenchService';
import type { DialogState } from '../hooks/useTaskCenter';

// ==================== Helper Functions ====================

function getDueDateDisplay(
  dueDate?: string,
): { text: string; className: string; isOverdue: boolean } | null {
  if (!dueDate) return null;
  const due = dayjs(dueDate);
  const now = dayjs();
  const diffMs = due.diff(now);
  const diffHours = due.diff(now, 'hour', true);

  if (diffMs < 0) {
    const overHours = Math.abs(Math.floor(diffHours));
    const text =
      overHours >= 24 ? `逾期 ${Math.floor(overHours / 24)} 天` : `逾期 ${overHours} 小时`;
    return { text, className: 'text-red-600 font-medium', isOverdue: true };
  }

  if (diffHours <= 4) {
    return {
      text: `${Math.ceil(diffHours)} 小时后`,
      className: 'text-orange-500',
      isOverdue: false,
    };
  }

  if (diffHours <= 24) {
    return {
      text: `${Math.ceil(diffHours)} 小时后`,
      className: 'text-yellow-600',
      isOverdue: false,
    };
  }

  const diffDays = Math.ceil(diffHours / 24);
  return { text: `${diffDays} 天后`, className: 'text-gray-500', isOverdue: false };
}

function PriorityBadge({ priority }: { priority?: number }) {
  if (priority === undefined || priority === null) return null;
  if (priority >= 80) {
    return (
      <span className="inline-flex items-center rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-700">
        高
      </span>
    );
  }
  if (priority >= 50) {
    return (
      <span className="inline-flex items-center rounded bg-yellow-100 px-1.5 py-0.5 text-xs font-medium text-yellow-700">
        中
      </span>
    );
  }
  return (
    <span className="inline-flex items-center rounded bg-green-100 px-1.5 py-0.5 text-xs font-medium text-green-700">
      低
    </span>
  );
}

// ==================== Types ====================

export interface TaskTableProps {
  tasks: TaskInstance[];
  loading: boolean;
  selectedTasks: Set<string>;
  onSelectTask: (taskId: string, selected: boolean) => void;
  onSelectAll: (selected: boolean) => void;
  onOpenDialog: (type: DialogState['type'], task: TaskInstance) => void;
  onClaim: (task: TaskInstance) => void;
  onOpenDetail: (task: TaskInstance) => void;
  onUrge?: (task: TaskInstance) => void;
  showActions?: boolean;
  showCheckbox?: boolean;
  /** Show SLA warning icon for overdue/warning tasks */
  slaWarningTaskIds?: Set<string>;
}

// ==================== Component ====================

export function TaskTable({
  tasks,
  loading,
  selectedTasks,
  onSelectTask,
  onSelectAll,
  onOpenDialog,
  onClaim,
  onOpenDetail,
  onUrge,
  showActions = true,
  showCheckbox = true,
  slaWarningTaskIds,
}: TaskTableProps) {
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);

  const handleSelectAllChange = useCallback(
    (checked: boolean | 'indeterminate') => onSelectAll(!!checked),
    [onSelectAll],
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center py-8 text-gray-500">
        <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
        加载中...
      </div>
    );
  }

  if (tasks.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-gray-500">
        <CheckCircle2 className="mb-4 h-12 w-12 opacity-20" />
        <p>暂无任务</p>
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="border-b bg-gray-50 text-xs uppercase">
          <tr>
            {showCheckbox && (
              <th className="w-12 px-4 py-3">
                <Checkbox
                  checked={selectedTasks.size === tasks.length && tasks.length > 0}
                  onCheckedChange={handleSelectAllChange}
                />
              </th>
            )}
            <th className="px-4 py-3">任务名称</th>
            <th className="px-4 py-3">流程</th>
            <th className="px-4 py-3">业务单号</th>
            <th className="w-16 px-4 py-3">优先级</th>
            <th className="px-4 py-3">创建时间</th>
            <th className="px-4 py-3">截止日期</th>
            <th className="px-4 py-3">处理人</th>
            {showActions && <th className="w-24 px-4 py-3">操作</th>}
          </tr>
        </thead>
        <tbody>
          {tasks.map((task) => (
            <TaskRow
              key={task.taskId || task.instanceId}
              task={task}
              showCheckbox={showCheckbox}
              showActions={showActions}
              isSelected={selectedTasks.has(task.taskId)}
              isMenuOpen={openMenuId === task.taskId}
              hasSlaWarning={slaWarningTaskIds?.has(task.taskId) ?? false}
              onSelectTask={onSelectTask}
              onToggleMenu={setOpenMenuId}
              onOpenDialog={onOpenDialog}
              onClaim={onClaim}
              onOpenDetail={onOpenDetail}
              onUrge={onUrge}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ==================== Row Component ====================

interface TaskRowProps {
  task: TaskInstance;
  showCheckbox: boolean;
  showActions: boolean;
  isSelected: boolean;
  isMenuOpen: boolean;
  hasSlaWarning: boolean;
  onSelectTask: (taskId: string, selected: boolean) => void;
  onToggleMenu: (id: string | null) => void;
  onOpenDialog: (type: DialogState['type'], task: TaskInstance) => void;
  onClaim: (task: TaskInstance) => void;
  onOpenDetail: (task: TaskInstance) => void;
  onUrge?: (task: TaskInstance) => void;
}

function TaskRow({
  task,
  showCheckbox,
  showActions,
  isSelected,
  isMenuOpen,
  hasSlaWarning,
  onSelectTask,
  onToggleMenu,
  onOpenDialog,
  onClaim,
  onOpenDetail,
  onUrge,
}: TaskRowProps) {
  const dueInfo = getDueDateDisplay(task.dueDate);

  const handleCheckChange = useCallback(
    (checked: boolean | 'indeterminate') => onSelectTask(task.taskId, !!checked),
    [onSelectTask, task.taskId],
  );

  const handleToggle = useCallback(
    () => onToggleMenu(isMenuOpen ? null : task.taskId),
    [onToggleMenu, isMenuOpen, task.taskId],
  );

  return (
    <tr className="border-b hover:bg-gray-50">
      {showCheckbox && (
        <td className="px-4 py-3">
          <Checkbox checked={isSelected} onCheckedChange={handleCheckChange} />
        </td>
      )}
      <td className="px-4 py-3">
        <div className="flex items-center gap-1.5">
          {hasSlaWarning && <AlertCircle className="h-3.5 w-3.5 flex-shrink-0 text-orange-500" />}
          <div className="flex min-w-0 flex-col">
            <button
              className="truncate text-left font-medium text-blue-600 hover:text-blue-800 hover:underline"
              onClick={() => onOpenDetail(task)}
              data-testid="task-name-button"
            >
              {task.taskName || task.title || '未命名任务'}
            </button>
            {/*
              Secondary line shows processDefinitionKey (internal identifier)
              so the user knows which process definition the task belongs to.
              The primary business-meaningful reference (businessKey) moved
              into its own column so it can be sorted/scanned.
            */}
            {task.processDefinitionKey && (
              <span className="truncate text-xs text-gray-500" data-testid="task-process-key">
                {task.processDefinitionKey}
              </span>
            )}
          </div>
        </div>
      </td>
      <td className="px-4 py-3 text-xs text-gray-600" data-testid="task-process-key-cell">
        {task.processDefinitionKey || <span className="text-gray-400">-</span>}
      </td>
      <td className="px-4 py-3 font-mono text-xs text-gray-700" data-testid="task-business-key">
        {task.businessKey || <span className="text-gray-400">-</span>}
      </td>
      <td className="px-4 py-3">
        <PriorityBadge priority={task.priority} />
      </td>
      <td className="px-4 py-3 text-xs text-gray-500">
        <DateTime value={task.createTime} />
      </td>
      <td className="px-4 py-3">
        {dueInfo ? (
          <div className="flex items-center gap-1">
            {dueInfo.isOverdue && <Clock className="h-3 w-3 text-red-500" />}
            <span className={`text-xs ${dueInfo.className}`}>{dueInfo.text}</span>
          </div>
        ) : (
          <span className="text-xs text-gray-400">-</span>
        )}
      </td>
      <td className="px-4 py-3">
        <div className="flex items-center text-gray-600">
          <User className="mr-1 h-3 w-3" />
          <span className="text-xs">{task.claimUserId || task.assignee || '-'}</span>
        </div>
      </td>
      {showActions && (
        <td className="relative px-4 py-3">
          <Button variant="ghost" size="sm" onClick={handleToggle} data-testid="task-row-actions">
            <MoreHorizontal className="h-4 w-4" />
          </Button>
          {isMenuOpen && (
            <TaskActionMenu
              task={task}
              onOpenDialog={onOpenDialog}
              onClaim={onClaim}
              onOpenDetail={onOpenDetail}
              onUrge={onUrge}
              onClose={() => onToggleMenu(null)}
            />
          )}
        </td>
      )}
    </tr>
  );
}

// ==================== Action Menu ====================

function TaskActionMenu({
  task,
  onOpenDialog,
  onClaim,
  onOpenDetail,
  onUrge,
  onClose,
}: {
  task: TaskInstance;
  onOpenDialog: (type: DialogState['type'], task: TaskInstance) => void;
  onClaim: (task: TaskInstance) => void;
  onOpenDetail: (task: TaskInstance) => void;
  onUrge?: (task: TaskInstance) => void;
  onClose: () => void;
}) {
  const menuAction = (action: () => void) => {
    action();
    onClose();
  };

  return (
    <div className="ring-opacity-5 absolute right-0 z-10 mt-2 w-48 origin-top-right rounded-md bg-white py-1 shadow-lg ring-1 ring-black">
      <button
        data-testid="task-action-detail"
        className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
        onClick={() => menuAction(() => onOpenDetail(task))}
      >
        查看详情
      </button>
      <div className="my-1 border-t" />
      <button
        data-testid="task-action-approve"
        className="block w-full px-4 py-2 text-left text-sm text-green-700 hover:bg-gray-100"
        onClick={() => menuAction(() => onOpenDialog('approve', task))}
      >
        通过
      </button>
      <button
        data-testid="task-action-reject"
        className="block w-full px-4 py-2 text-left text-sm text-red-600 hover:bg-gray-100"
        onClick={() => menuAction(() => onOpenDialog('reject', task))}
      >
        驳回
      </button>
      {!task.claimUserId && (
        <button
          className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
          onClick={() => menuAction(() => onClaim(task))}
        >
          认领任务
        </button>
      )}
      <button
        className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
        onClick={() => menuAction(() => onOpenDialog('complete', task))}
      >
        完成任务
      </button>
      <div className="my-1 border-t" />
      <button
        className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
        onClick={() => menuAction(() => onOpenDialog('delegate', task))}
      >
        委托
      </button>
      <button
        className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
        onClick={() => menuAction(() => onOpenDialog('transfer', task))}
      >
        转办
      </button>
      <button
        className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
        onClick={() => menuAction(() => onOpenDialog('addSign', task))}
      >
        加签
      </button>
      <button
        className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
        onClick={() => menuAction(() => onOpenDialog('removeSign', task))}
      >
        减签
      </button>
      <button
        className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
        onClick={() => menuAction(() => onOpenDialog('rollback', task))}
      >
        回退
      </button>
      <div className="my-1 border-t" />
      <button
        className="block w-full px-4 py-2 text-left text-sm hover:bg-gray-100"
        onClick={() => menuAction(() => onOpenDialog('carbonCopy', task))}
      >
        抄送
      </button>
      {onUrge && (
        <button
          className="block w-full px-4 py-2 text-left text-sm text-orange-600 hover:bg-gray-100"
          onClick={() => menuAction(() => onUrge(task))}
        >
          催办
        </button>
      )}
    </div>
  );
}
