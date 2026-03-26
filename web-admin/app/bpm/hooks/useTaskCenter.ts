/**
 * useTaskCenter Hook
 * Centralized state management for BPM Task Center
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import type { TaskInstance, ProcessInstance, WorkbenchData } from '../services/bpmWorkbenchService';
import * as workbenchService from '../services/bpmWorkbenchService';
import * as slaService from '../services/slaService';
import * as notifyService from '../services/bpmNotifyService';
import type { DashboardData } from '../services/slaService';

export type TabId = 'todo' | 'completed' | 'started' | 'cc' | 'urge';

export interface DialogState {
  type:
    | 'complete'
    | 'approve'
    | 'reject'
    | 'delegate'
    | 'transfer'
    | 'terminate'
    | 'addSign'
    | 'removeSign'
    | 'rollback'
    | 'carbonCopy'
    | null;
  task?: TaskInstance;
  process?: ProcessInstance;
}

export function useTaskCenter() {
  const { showSuccessToast, showErrorToast, showWarningToast } = useToastContext();
  const [activeTab, setActiveTab] = useState<TabId>('todo');
  const [loading, setLoading] = useState(true);
  const [searchText, setSearchText] = useState('');
  const [workbenchData, setWorkbenchData] = useState<WorkbenchData | null>(null);
  const [selectedTasks, setSelectedTasks] = useState<Set<string>>(new Set());
  const [dialog, setDialog] = useState<DialogState>({ type: null });
  const [detailTask, setDetailTask] = useState<TaskInstance | null>(null);
  const [slaDashboard, setSlaDashboard] = useState<DashboardData | null>(null);

  // Stabilize toast refs to prevent fetchData from being recreated on context re-renders
  // (unstable toast references cause double-fetch + re-render that steals click events)
  const toastRef = useRef({ showSuccessToast, showErrorToast, showWarningToast });
  toastRef.current = { showSuccessToast, showErrorToast, showWarningToast };

  // Fetch workbench data + SLA dashboard (stable reference — runs only once on mount)
  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const [data, sla] = await Promise.all([
        workbenchService.getWorkbench(),
        slaService.getDashboard().catch(() => null),
      ]);
      setWorkbenchData(data);
      setSlaDashboard(sla);
    } catch (error) {
      console.error('Failed to fetch workbench data:', error);
      toastRef.current.showErrorToast('加载任务数据失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // SLA warning count from dashboard
  const slaWarningCount = useMemo(
    () => (slaDashboard ? slaDashboard.sla.warning + slaDashboard.sla.overdue : undefined),
    [slaDashboard],
  );

  // SLA warning task IDs: tasks with overdue or near-deadline due dates (within 4 hours)
  const slaWarningTaskIds = useMemo(() => {
    const tasks = workbenchData?.todoTasks || [];
    const now = Date.now();
    const fourHoursMs = 4 * 60 * 60 * 1000;
    const ids = new Set<string>();
    for (const t of tasks) {
      if (t.dueDate) {
        const due = new Date(t.dueDate).getTime();
        if (due - now <= fourHoursMs) {
          ids.add(t.taskId);
        }
      }
    }
    return ids;
  }, [workbenchData?.todoTasks]);

  // Task selection
  const handleSelectTask = useCallback((taskId: string, selected: boolean) => {
    setSelectedTasks((prev) => {
      const next = new Set(prev);
      if (selected) next.add(taskId);
      else next.delete(taskId);
      return next;
    });
  }, []);

  const handleSelectAll = useCallback(
    (selected: boolean) => {
      if (selected && workbenchData) {
        setSelectedTasks(new Set(workbenchData.todoTasks.map((t) => t.taskId)));
      } else {
        setSelectedTasks(new Set());
      }
    },
    [workbenchData],
  );

  // Dialog management
  const openDialog = useCallback(
    (type: DialogState['type'], task?: TaskInstance, process?: ProcessInstance) => {
      setDialog({ type, task, process });
    },
    [],
  );

  const closeDialog = useCallback(() => {
    setDialog({ type: null });
  }, []);

  // Open detail drawer
  const openDetail = useCallback((task: TaskInstance) => {
    setDetailTask(task);
  }, []);

  const closeDetail = useCallback(() => {
    setDetailTask(null);
  }, []);

  // Task actions — all use toastRef.current to avoid dependency on toast identity
  const completeTask = useCallback(
    async (comment: string) => {
      if (!dialog.task) return;
      try {
        await workbenchService.completeTask({ taskId: dialog.task.taskId, comment });
        toastRef.current.showSuccessToast('任务已完成');
        closeDialog();
        fetchData();
      } catch {
        toastRef.current.showErrorToast('完成任务失败');
      }
    },
    [dialog.task, closeDialog, fetchData],
  );

  const approveTask = useCallback(
    async (comment: string) => {
      if (!dialog.task) return;
      try {
        await workbenchService.approveTask(dialog.task.taskId, comment);
        toastRef.current.showSuccessToast('任务已通过');
        closeDialog();
        fetchData();
      } catch {
        toastRef.current.showErrorToast('通过任务失败');
      }
    },
    [dialog.task, closeDialog, fetchData],
  );

  const rejectTask = useCallback(
    async (comment: string) => {
      if (!dialog.task) return;
      try {
        await workbenchService.rejectTask(dialog.task.taskId, comment);
        toastRef.current.showSuccessToast('任务已驳回');
        closeDialog();
        fetchData();
      } catch {
        toastRef.current.showErrorToast('驳回任务失败');
      }
    },
    [dialog.task, closeDialog, fetchData],
  );

  const claimTask = useCallback(
    async (task: TaskInstance) => {
      try {
        await workbenchService.claimTask(task.taskId);
        toastRef.current.showSuccessToast('任务已认领');
        fetchData();
      } catch {
        toastRef.current.showErrorToast('认领任务失败');
      }
    },
    [fetchData],
  );

  const delegateTask = useCallback(
    async (userId: string, comment: string) => {
      if (!dialog.task) return;
      try {
        await workbenchService.delegateTask(dialog.task.taskId, userId, comment);
        toastRef.current.showSuccessToast('任务已委托');
        closeDialog();
        fetchData();
      } catch {
        toastRef.current.showErrorToast('委托任务失败');
      }
    },
    [dialog.task, closeDialog, fetchData],
  );

  const transferTask = useCallback(
    async (userId: string, comment: string) => {
      if (!dialog.task) return;
      try {
        await workbenchService.transferTask(dialog.task.taskId, userId, comment);
        toastRef.current.showSuccessToast('任务已转办');
        closeDialog();
        fetchData();
      } catch {
        toastRef.current.showErrorToast('转办任务失败');
      }
    },
    [dialog.task, closeDialog, fetchData],
  );

  const addSign = useCallback(
    async (userId: string, reason: string) => {
      if (!dialog.task) return;
      try {
        await workbenchService.addSign(dialog.task.taskId, userId, reason);
        toastRef.current.showSuccessToast('加签成功');
        closeDialog();
        fetchData();
      } catch {
        toastRef.current.showErrorToast('加签失败');
      }
    },
    [dialog.task, closeDialog, fetchData],
  );

  const removeSign = useCallback(
    async (userId: string, reason: string) => {
      if (!dialog.task) return;
      try {
        await workbenchService.removeSign(dialog.task.taskId, userId, reason);
        toastRef.current.showSuccessToast('减签成功');
        closeDialog();
        fetchData();
      } catch {
        toastRef.current.showErrorToast('减签失败');
      }
    },
    [dialog.task, closeDialog, fetchData],
  );

  const rollbackTask = useCallback(
    async (targetActivityId: string, reason: string) => {
      if (!dialog.task) return;
      try {
        await workbenchService.rollbackTask(dialog.task.taskId, targetActivityId, reason);
        toastRef.current.showSuccessToast('回退成功');
        closeDialog();
        fetchData();
      } catch {
        toastRef.current.showErrorToast('回退失败');
      }
    },
    [dialog.task, closeDialog, fetchData],
  );

  // Urge task (send reminder to assignee)
  const urgeTask = useCallback(async (task: TaskInstance, senderUserId: number) => {
    if (!task.claimUserId && !task.assignee) {
      toastRef.current.showWarningToast('该任务暂无处理人，无法催办');
      return;
    }
    try {
      const assigneeId = Number(task.claimUserId || task.assignee) || 0;
      await notifyService.sendUrge({
        taskId: task.taskId,
        processInstanceId: task.processInstanceId,
        senderUserId,
        assigneeUserId: assigneeId,
        content: `请尽快处理任务「${task.taskName || task.title || ''}」`,
      });
      toastRef.current.showSuccessToast('催办已发送');
    } catch {
      toastRef.current.showErrorToast('催办发送失败');
    }
  }, []);

  // Process actions
  const suspendProcess = useCallback(
    async (process: ProcessInstance) => {
      try {
        await workbenchService.suspendProcess(process.instanceId);
        toastRef.current.showSuccessToast('流程已暂停');
        fetchData();
      } catch {
        toastRef.current.showErrorToast('暂停流程失败');
      }
    },
    [fetchData],
  );

  const resumeProcess = useCallback(
    async (process: ProcessInstance) => {
      try {
        await workbenchService.resumeProcess(process.instanceId);
        toastRef.current.showSuccessToast('流程已恢复');
        fetchData();
      } catch {
        toastRef.current.showErrorToast('恢复流程失败');
      }
    },
    [fetchData],
  );

  const terminateProcess = useCallback(
    async (comment: string) => {
      if (!dialog.process) return;
      try {
        await workbenchService.terminateProcess(dialog.process.instanceId, comment);
        toastRef.current.showSuccessToast('流程已终止');
        closeDialog();
        fetchData();
      } catch {
        toastRef.current.showErrorToast('终止流程失败');
      }
    },
    [dialog.process, closeDialog, fetchData],
  );

  // Batch operations
  const batchApprove = useCallback(async () => {
    if (selectedTasks.size === 0) {
      toastRef.current.showWarningToast('请先选择任务');
      return;
    }
    try {
      await workbenchService.batchProcessTasks({
        taskIds: Array.from(selectedTasks),
        action: 'approve',
        comment: '批量通过',
      });
      toastRef.current.showSuccessToast(`已批量通过 ${selectedTasks.size} 个任务`);
      setSelectedTasks(new Set());
      fetchData();
    } catch {
      toastRef.current.showErrorToast('批量通过失败');
    }
  }, [selectedTasks, fetchData]);

  const batchReject = useCallback(async () => {
    if (selectedTasks.size === 0) {
      toastRef.current.showWarningToast('请先选择任务');
      return;
    }
    try {
      await workbenchService.batchProcessTasks({
        taskIds: Array.from(selectedTasks),
        action: 'reject',
        comment: '批量驳回',
      });
      toastRef.current.showSuccessToast(`已批量驳回 ${selectedTasks.size} 个任务`);
      setSelectedTasks(new Set());
      fetchData();
    } catch {
      toastRef.current.showErrorToast('批量驳回失败');
    }
  }, [selectedTasks, fetchData]);

  // Filtered data
  const filteredTodoTasks = useMemo(() => {
    const tasks = workbenchData?.todoTasks || [];
    if (!searchText) return tasks;
    const lower = searchText.toLowerCase();
    return tasks.filter(
      (t) =>
        t.taskName?.toLowerCase().includes(lower) ||
        t.processDefinitionKey?.toLowerCase().includes(lower) ||
        t.businessKey?.toLowerCase().includes(lower),
    );
  }, [workbenchData?.todoTasks, searchText]);

  const filteredCompletedTasks = useMemo(() => {
    const tasks = workbenchData?.completedTasks || [];
    if (!searchText) return tasks;
    const lower = searchText.toLowerCase();
    return tasks.filter(
      (t) =>
        t.taskName?.toLowerCase().includes(lower) ||
        t.processDefinitionKey?.toLowerCase().includes(lower) ||
        t.businessKey?.toLowerCase().includes(lower),
    );
  }, [workbenchData?.completedTasks, searchText]);

  const filteredProcesses = useMemo(() => {
    const processes = workbenchData?.startedProcesses || [];
    if (!searchText) return processes;
    const lower = searchText.toLowerCase();
    return processes.filter(
      (p) =>
        p.processDefinitionKey?.toLowerCase().includes(lower) ||
        p.businessKey?.toLowerCase().includes(lower) ||
        p.title?.toLowerCase().includes(lower),
    );
  }, [workbenchData?.startedProcesses, searchText]);

  return {
    // State
    activeTab,
    loading,
    searchText,
    workbenchData,
    selectedTasks,
    dialog,
    detailTask,

    // Filtered data
    filteredTodoTasks,
    filteredCompletedTasks,
    filteredProcesses,

    // SLA
    slaWarningCount,
    slaWarningTaskIds,

    // Setters
    setActiveTab,
    setSearchText,

    // Data
    fetchData,

    // Selection
    handleSelectTask,
    handleSelectAll,

    // Dialog
    openDialog,
    closeDialog,

    // Detail drawer
    openDetail,
    closeDetail,

    // Task actions
    completeTask,
    approveTask,
    rejectTask,
    claimTask,
    delegateTask,
    transferTask,
    addSign,
    removeSign,
    rollbackTask,
    urgeTask,

    // Process actions
    suspendProcess,
    resumeProcess,
    terminateProcess,

    // Batch
    batchApprove,
    batchReject,
  };
}
