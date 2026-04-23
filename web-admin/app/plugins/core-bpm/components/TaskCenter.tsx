/**
 * Task Center Component
 * Container component composing TaskTable, ProcessTable, TaskStatsCards,
 * TaskActionDialogs, TaskDetailDrawer, and NotifyPanel.
 */

import { useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '~/ui/ui/button';
import { Input } from '~/ui/ui/input';
import { Search, RefreshCw, CheckCheck, XCircle } from 'lucide-react';
import { useTaskCenter, type TabId } from '../hooks/useTaskCenter';
import type { ProcessInstance, TaskInstance } from '../services/bpmWorkbenchService';
import { TaskStatsCards } from './TaskStatsCards';
import { TaskTable } from './TaskTable';
import { ProcessTable } from './ProcessTable';
import { TaskActionDialogs } from './TaskActionDialogs';
import { NotifyPanel } from './NotifyPanel';
import { useToastContext } from '~/contexts/ToastContext';
import { useAuth } from '~/contexts/AuthContext';
import * as notifyService from '../services/bpmNotifyService';
import * as bpmFormService from '../services/bpmFormService';

// ==================== Stable constants ====================

const EMPTY_SET = new Set<string>();
const NOOP = () => {};

export function deriveTaskDetailModelSegment(formRef?: string | null): string | null {
  const normalized = formRef?.trim();
  if (!normalized) return null;
  if (normalized.endsWith('_detail')) return normalized.slice(0, -'_detail'.length);
  if (normalized.endsWith('_form')) return normalized.slice(0, -'_form'.length);
  return null;
}

export function resolveTaskDetailPath(input: {
  formRef?: string | null;
  businessKey?: string | null;
  processInstanceId?: string | null;
  processDefinitionKey?: string | null;
}): string | null {
  const modelSegment = deriveTaskDetailModelSegment(input.formRef);
  const businessKey = input.businessKey?.trim();
  if (modelSegment && businessKey) {
    return `/p/${encodeURIComponent(modelSegment)}/view/${encodeURIComponent(businessKey)}`;
  }
  const processInstanceId = input.processInstanceId?.trim();
  if (processInstanceId) {
    return `/bpm/process-status?processInstanceId=${encodeURIComponent(processInstanceId)}`;
  }
  if (businessKey) {
    const params = new URLSearchParams({ businessKey });
    const processKey = input.processDefinitionKey?.trim();
    if (processKey) {
      params.set('processKey', processKey);
    }
    return `/bpm/process-status?${params.toString()}`;
  }
  return null;
}

// ==================== Main Component ====================

export function TaskCenter() {
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { user } = useAuth();
  const currentUserId = Number(user?.pid) || 0;

  const tc = useTaskCenter();

  // Navigation handlers for process detail
  const handleViewProcessDetail = useCallback(
    (process: ProcessInstance) => {
      navigate(`/bpm/process-status?processInstanceId=${encodeURIComponent(process.instanceId)}`);
    },
    [navigate],
  );

  const handleOpenTerminateDialog = useCallback(
    (process: ProcessInstance) => {
      tc.openDialog('terminate', undefined, process);
    },
    [tc.openDialog],
  );

  const handleOpenTaskDetail = useCallback(
    async (task: TaskInstance) => {
      let formRef: string | null | undefined;
      let businessKey = task.businessKey;

      try {
        const formData = await bpmFormService.getTaskForm(task.taskId);
        formRef = formData.formBinding?.formRef;
        businessKey = formData.businessKey || businessKey;
      } catch {
        // Fall through to process-status when task form metadata is unavailable.
      }

      const target = resolveTaskDetailPath({
        formRef,
        businessKey,
        processInstanceId: task.processInstanceId,
        processDefinitionKey: task.processDefinitionKey,
      });

      if (!target) {
        showErrorToast('无法打开详情页');
        return;
      }

      navigate(target);
    },
    [navigate, showErrorToast],
  );

  // Urge handler (wraps tc.urgeTask with currentUserId)
  const handleUrge = useCallback(
    (task: Parameters<typeof tc.urgeTask>[0]) => {
      tc.urgeTask(task, currentUserId);
    },
    [tc.urgeTask, currentUserId],
  );

  // Carbon copy handler (from task action menu)
  const handleCarbonCopy = useCallback(
    async (userIds: string[], content: string) => {
      if (!tc.dialog.task) return;
      try {
        await notifyService.sendCarbonCopy({
          taskId: tc.dialog.task.taskId,
          processInstanceId: tc.dialog.task.processInstanceId,
          senderUserId: currentUserId,
          recipientUserIds: userIds.map(Number),
          content,
        });
        showSuccessToast('抄送成功');
        tc.closeDialog();
      } catch {
        showErrorToast('抄送失败');
      }
    },
    [tc.dialog.task, tc.closeDialog, currentUserId, showSuccessToast, showErrorToast],
  );

  const tabs: { id: TabId; label: string; count?: number }[] = useMemo(
    () => [
      { id: 'todo', label: '待办任务', count: tc.workbenchData?.todoCount },
      { id: 'completed', label: '已办任务' },
      { id: 'started', label: '我发起的' },
      { id: 'cc', label: '抄送给我' },
      { id: 'urge', label: '催办提醒' },
    ],
    [tc.workbenchData?.todoCount],
  );

  return (
    <div className="mx-auto w-full space-y-6 px-6 py-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">任务中心</h1>
          <p className="text-gray-500">管理您的审批任务和流程</p>
        </div>
        <Button onClick={tc.fetchData} variant="outline">
          <RefreshCw className="mr-2 h-4 w-4" />
          刷新
        </Button>
      </div>

      {/* Statistics Cards */}
      <TaskStatsCards data={tc.workbenchData} slaWarningCount={tc.slaWarningCount} />

      {/* Task Tabs */}
      <div className="rounded-lg border bg-white">
        <div className="border-b p-4">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold">任务列表</h2>
            <div className="flex items-center space-x-2">
              {tc.activeTab !== 'cc' && tc.activeTab !== 'urge' && (
                <div className="relative">
                  <Search className="absolute top-2.5 left-2.5 h-4 w-4 text-gray-400" />
                  <Input
                    placeholder="搜索任务..."
                    className="w-64 pl-8"
                    value={tc.searchText}
                    onChange={(e) => tc.setSearchText(e.target.value)}
                  />
                </div>
              )}
              {tc.activeTab === 'todo' && tc.selectedTasks.size > 0 && (
                <>
                  <Button size="sm" onClick={tc.batchApprove}>
                    <CheckCheck className="mr-1 h-4 w-4" />
                    批量通过
                  </Button>
                  <Button size="sm" variant="destructive" onClick={tc.batchReject}>
                    <XCircle className="mr-1 h-4 w-4" />
                    批量驳回
                  </Button>
                </>
              )}
            </div>
          </div>
        </div>

        <div className="border-b">
          <div className="flex space-x-4 px-4">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                onClick={() => tc.setActiveTab(tab.id)}
                className={`border-b-2 px-1 py-3 text-sm font-medium transition-colors ${
                  tc.activeTab === tab.id
                    ? 'border-blue-600 text-blue-600'
                    : 'border-transparent text-gray-500 hover:text-gray-700'
                }`}
              >
                {tab.label}
                {tab.count !== undefined && tab.count > 0 && (
                  <span className="ml-2 rounded-full bg-gray-100 px-2 py-0.5 text-xs">
                    {tab.count}
                  </span>
                )}
              </button>
            ))}
          </div>
        </div>

        <div className="p-4">
          {tc.activeTab === 'todo' && (
            <TaskTable
              tasks={tc.filteredTodoTasks}
              loading={tc.loading}
              selectedTasks={tc.selectedTasks}
              onSelectTask={tc.handleSelectTask}
              onSelectAll={tc.handleSelectAll}
              onOpenDialog={tc.openDialog}
              onClaim={tc.claimTask}
              onOpenDetail={handleOpenTaskDetail}
              onUrge={handleUrge}
              slaWarningTaskIds={tc.slaWarningTaskIds}
            />
          )}
          {tc.activeTab === 'completed' && (
            <TaskTable
              tasks={tc.filteredCompletedTasks}
              loading={tc.loading}
              selectedTasks={EMPTY_SET}
              onSelectTask={NOOP}
              onSelectAll={NOOP}
              onOpenDialog={NOOP}
              onClaim={NOOP}
              onOpenDetail={handleOpenTaskDetail}
              showActions={false}
              showCheckbox={false}
            />
          )}
          {tc.activeTab === 'started' && (
            <ProcessTable
              processes={tc.filteredProcesses}
              loading={tc.loading}
              onViewDetail={handleViewProcessDetail}
              onSuspend={tc.suspendProcess}
              onResume={tc.resumeProcess}
              onTerminate={handleOpenTerminateDialog}
            />
          )}
          {tc.activeTab === 'cc' && <NotifyPanel userId={currentUserId} type="CC" />}
          {tc.activeTab === 'urge' && <NotifyPanel userId={currentUserId} type="urge" />}
        </div>
      </div>

      {/* Action Dialogs */}
      <TaskActionDialogs
        dialog={tc.dialog}
        onClose={tc.closeDialog}
        onComplete={tc.completeTask}
        onApprove={tc.approveTask}
        onReject={tc.rejectTask}
        onDelegate={tc.delegateTask}
        onTransfer={tc.transferTask}
        onTerminate={tc.terminateProcess}
        onAddSign={tc.addSign}
        onRemoveSign={tc.removeSign}
        onRollback={tc.rollbackTask}
        onCarbonCopy={handleCarbonCopy}
      />
    </div>
  );
}

export default TaskCenter;
