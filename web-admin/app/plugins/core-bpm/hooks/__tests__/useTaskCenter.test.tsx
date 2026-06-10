/**
 * Unit tests for useTaskCenter hook.
 *
 * Strategy:
 * - vi.mock all three service modules (workbenchService, slaService, notifyService)
 * - vi.mock ToastContext so the hook can be rendered without a Provider
 * - Use renderHook + act to drive state transitions
 * - Assert state machine logic, loading/error paths, selection, dialog, and action calls
 */

import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// ── Mock service modules BEFORE importing the hook ──────────────────────────

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: vi.fn(() => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
    showWarningToast: vi.fn(),
    showInfoToast: vi.fn(),
    showToast: vi.fn(),
  })),
}));

vi.mock('~/plugins/core-bpm/services/bpmWorkbenchService', () => ({
  getWorkbench: vi.fn(),
  completeTask: vi.fn(),
  approveTask: vi.fn(),
  rejectTask: vi.fn(),
  claimTask: vi.fn(),
  delegateTask: vi.fn(),
  transferTask: vi.fn(),
  addSign: vi.fn(),
  removeSign: vi.fn(),
  rollbackTask: vi.fn(),
  suspendProcess: vi.fn(),
  resumeProcess: vi.fn(),
  terminateProcess: vi.fn(),
  batchProcessTasks: vi.fn(),
}));

vi.mock('~/plugins/core-bpm/services/slaService', () => ({
  getDashboard: vi.fn(),
}));

vi.mock('~/plugins/core-bpm/services/bpmNotifyService', () => ({
  sendUrge: vi.fn(),
}));

// ── Now import under-test and mocked modules ─────────────────────────────────

import { useTaskCenter } from '../useTaskCenter';
import * as workbenchService from '~/plugins/core-bpm/services/bpmWorkbenchService';
import * as slaService from '~/plugins/core-bpm/services/slaService';
import * as notifyService from '~/plugins/core-bpm/services/bpmNotifyService';
import { useToastContext } from '~/contexts/ToastContext';

const mockGetWorkbench = vi.mocked(workbenchService.getWorkbench);
const mockGetDashboard = vi.mocked(slaService.getDashboard);
const mockCompleteTask = vi.mocked(workbenchService.completeTask);
const mockApproveTask = vi.mocked(workbenchService.approveTask);
const mockRejectTask = vi.mocked(workbenchService.rejectTask);
const mockClaimTask = vi.mocked(workbenchService.claimTask);
const mockDelegateTask = vi.mocked(workbenchService.delegateTask);
const mockTransferTask = vi.mocked(workbenchService.transferTask);
const mockAddSign = vi.mocked(workbenchService.addSign);
const mockRemoveSign = vi.mocked(workbenchService.removeSign);
const mockRollbackTask = vi.mocked(workbenchService.rollbackTask);
const mockSuspendProcess = vi.mocked(workbenchService.suspendProcess);
const mockResumeProcess = vi.mocked(workbenchService.resumeProcess);
const mockTerminateProcess = vi.mocked(workbenchService.terminateProcess);
const mockBatchProcessTasks = vi.mocked(workbenchService.batchProcessTasks);
const mockSendUrge = vi.mocked(notifyService.sendUrge);

// ── Fixtures ─────────────────────────────────────────────────────────────────

const makeTask = (overrides: Partial<ReturnType<typeof baseTask>> = {}) => ({
  ...baseTask(),
  ...overrides,
});

function baseTask() {
  return {
    instanceId: 'inst-1',
    taskId: 'task-1',
    processInstanceId: 'proc-1',
    processDefinitionKey: 'order-approval',
    taskDefinitionKey: 'review',
    taskName: 'Review Order',
    assignee: 'user-99',
    claimUserId: 'user-99',
    createTime: '2026-01-01T00:00:00Z',
    priority: 50,
    title: 'Review Order',
    businessKey: 'ORD-001',
  };
}

const makeProcess = () => ({
  instanceId: 'proc-1',
  processDefinitionId: 'order-approval:1',
  processDefinitionKey: 'order-approval',
  businessKey: 'ORD-001',
  startUserId: 'user-1',
  startTime: '2026-01-01T00:00:00Z',
  status: 'running',
  title: 'Order ORD-001',
});

const makeWorkbench = (overrides = {}) => ({
  todoTasks: [makeTask()],
  completedTasks: [],
  startedProcesses: [makeProcess()],
  todoCount: 1,
  completedCount: 0,
  startedCount: 1,
  ...overrides,
});

const makeSla = () => ({
  processDefinitions: { total: 5, draft: 1, deployed: 3, suspended: 1 },
  sla: { active: 10, running: 8, warning: 2, overdue: 1, paused: 0 },
  slaConfigs: { total: 4, enabled: 3 },
});

// Capture the toast mock instance returned per render so we can assert on it
function getToastMock() {
  return vi.mocked(useToastContext)();
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('useTaskCenter', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetWorkbench.mockResolvedValue(makeWorkbench());
    mockGetDashboard.mockResolvedValue(makeSla());
  });

  // ── Initial load ───────────────────────────────────────────────────────────

  describe('initial data load', () => {
    it('starts in loading state and resolves after fetch', async () => {
      const { result } = renderHook(() => useTaskCenter());

      expect(result.current.loading).toBe(true);

      await waitFor(() => expect(result.current.loading).toBe(false));

      expect(result.current.workbenchData).not.toBeNull();
      expect(result.current.workbenchData!.todoTasks).toHaveLength(1);
      expect(mockGetWorkbench).toHaveBeenCalledTimes(1);
      expect(mockGetDashboard).toHaveBeenCalledTimes(1);
    });

    it('shows error toast and clears loading when getWorkbench rejects', async () => {
      const toastMock = {
        showSuccessToast: vi.fn(),
        showErrorToast: vi.fn(),
        showWarningToast: vi.fn(),
        showInfoToast: vi.fn(),
        showToast: vi.fn(),
      };
      vi.mocked(useToastContext).mockReturnValue(toastMock);
      mockGetWorkbench.mockRejectedValue(new Error('network error'));

      const { result } = renderHook(() => useTaskCenter());

      await waitFor(() => expect(result.current.loading).toBe(false));

      expect(toastMock.showErrorToast).toHaveBeenCalledWith('加载任务数据失败');
      expect(result.current.workbenchData).toBeNull();
    });

    it('still loads workbench when SLA dashboard rejects (graceful)', async () => {
      mockGetDashboard.mockRejectedValue(new Error('sla down'));

      const { result } = renderHook(() => useTaskCenter());

      await waitFor(() => expect(result.current.loading).toBe(false));

      expect(result.current.workbenchData).not.toBeNull();
      // slaWarningCount is undefined when SLA dashboard failed (null internally)
      expect(result.current.slaWarningCount).toBeUndefined();
    });
  });

  // ── SLA computed values ────────────────────────────────────────────────────

  describe('slaWarningCount', () => {
    it('sums warning + overdue from SLA dashboard', async () => {
      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      // makeSla() has warning:2 + overdue:1 = 3
      expect(result.current.slaWarningCount).toBe(3);
    });

    it('returns undefined when sla dashboard is null', async () => {
      mockGetDashboard.mockRejectedValue(new Error('sla down'));
      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      expect(result.current.slaWarningCount).toBeUndefined();
    });
  });

  describe('slaWarningTaskIds', () => {
    it('includes tasks with dueDate within 4 hours', async () => {
      const now = Date.now();
      const soonDue = new Date(now + 2 * 60 * 60 * 1000).toISOString(); // 2h from now
      mockGetWorkbench.mockResolvedValue(
        makeWorkbench({
          todoTasks: [makeTask({ taskId: 'urgent-1', dueDate: soonDue })],
        }),
      );

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      expect(result.current.slaWarningTaskIds.has('urgent-1')).toBe(true);
    });

    it('excludes tasks with dueDate beyond 4 hours', async () => {
      const now = Date.now();
      const farDue = new Date(now + 8 * 60 * 60 * 1000).toISOString(); // 8h from now
      mockGetWorkbench.mockResolvedValue(
        makeWorkbench({
          todoTasks: [makeTask({ taskId: 'normal-1', dueDate: farDue })],
        }),
      );

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      expect(result.current.slaWarningTaskIds.has('normal-1')).toBe(false);
    });

    it('excludes tasks without dueDate', async () => {
      mockGetWorkbench.mockResolvedValue(
        makeWorkbench({
          todoTasks: [makeTask({ taskId: 'no-due', dueDate: undefined })],
        }),
      );

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      expect(result.current.slaWarningTaskIds.has('no-due')).toBe(false);
    });
  });

  // ── Tab and search ─────────────────────────────────────────────────────────

  describe('tab and search state', () => {
    it('defaults to todo tab', () => {
      const { result } = renderHook(() => useTaskCenter());
      expect(result.current.activeTab).toBe('todo');
    });

    it('setActiveTab updates the tab', async () => {
      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.setActiveTab('completed'));
      expect(result.current.activeTab).toBe('completed');
    });

    it('setSearchText filters todoTasks by taskName', async () => {
      mockGetWorkbench.mockResolvedValue(
        makeWorkbench({
          todoTasks: [
            makeTask({ taskId: 't1', taskName: 'Approve Invoice' }),
            makeTask({ taskId: 't2', taskName: 'Review Contract' }),
          ],
        }),
      );

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.setSearchText('invoice'));

      expect(result.current.filteredTodoTasks).toHaveLength(1);
      expect(result.current.filteredTodoTasks[0].taskId).toBe('t1');
    });

    it('setSearchText filters completedTasks by businessKey', async () => {
      mockGetWorkbench.mockResolvedValue(
        makeWorkbench({
          completedTasks: [
            makeTask({ taskId: 'c1', businessKey: 'ORD-100' }),
            makeTask({ taskId: 'c2', businessKey: 'ORD-200' }),
          ],
        }),
      );

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.setSearchText('ORD-200'));
      expect(result.current.filteredCompletedTasks).toHaveLength(1);
      expect(result.current.filteredCompletedTasks[0].taskId).toBe('c2');
    });

    it('setSearchText filters processes by title', async () => {
      mockGetWorkbench.mockResolvedValue(
        makeWorkbench({
          startedProcesses: [
            { ...makeProcess(), instanceId: 'p1', title: 'Alpha Process' },
            { ...makeProcess(), instanceId: 'p2', title: 'Beta Process' },
          ],
        }),
      );

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.setSearchText('beta'));
      expect(result.current.filteredProcesses).toHaveLength(1);
      expect(result.current.filteredProcesses[0].instanceId).toBe('p2');
    });

    it('returns all tasks when searchText is empty', async () => {
      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      expect(result.current.filteredTodoTasks).toHaveLength(1);
    });
  });

  // ── Task selection ─────────────────────────────────────────────────────────

  describe('task selection', () => {
    it('handleSelectTask adds and removes a task id', async () => {
      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.handleSelectTask('task-1', true));
      expect(result.current.selectedTasks.has('task-1')).toBe(true);

      act(() => result.current.handleSelectTask('task-1', false));
      expect(result.current.selectedTasks.has('task-1')).toBe(false);
    });

    it('handleSelectAll selects all todo task ids', async () => {
      mockGetWorkbench.mockResolvedValue(
        makeWorkbench({
          todoTasks: [makeTask({ taskId: 'a' }), makeTask({ taskId: 'b' })],
        }),
      );

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.handleSelectAll(true));
      expect(result.current.selectedTasks.has('a')).toBe(true);
      expect(result.current.selectedTasks.has('b')).toBe(true);
    });

    it('handleSelectAll(false) clears all selections', async () => {
      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.handleSelectTask('task-1', true));
      act(() => result.current.handleSelectAll(false));
      expect(result.current.selectedTasks.size).toBe(0);
    });
  });

  // ── Dialog management ──────────────────────────────────────────────────────

  describe('dialog management', () => {
    it('openDialog sets type + task; closeDialog resets to null', async () => {
      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      const task = makeTask();
      act(() => result.current.openDialog('approve', task));
      expect(result.current.dialog.type).toBe('approve');
      expect(result.current.dialog.task?.taskId).toBe('task-1');

      act(() => result.current.closeDialog());
      expect(result.current.dialog.type).toBeNull();
    });

    it('openDialog with process sets process on dialog', async () => {
      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      const proc = makeProcess();
      act(() => result.current.openDialog('terminate', undefined, proc));
      expect(result.current.dialog.type).toBe('terminate');
      expect(result.current.dialog.process?.instanceId).toBe('proc-1');
    });
  });

  // ── Detail drawer ──────────────────────────────────────────────────────────

  describe('detail drawer', () => {
    it('openDetail sets detailTask; closeDetail clears it', async () => {
      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      const task = makeTask();
      act(() => result.current.openDetail(task));
      expect(result.current.detailTask?.taskId).toBe('task-1');

      act(() => result.current.closeDetail());
      expect(result.current.detailTask).toBeNull();
    });
  });

  // ── Task actions ───────────────────────────────────────────────────────────

  describe('completeTask', () => {
    it('calls service and triggers fetchData on success', async () => {
      mockCompleteTask.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      const task = makeTask();
      act(() => result.current.openDialog('complete', task));

      await act(async () => {
        await result.current.completeTask('done');
      });

      expect(mockCompleteTask).toHaveBeenCalledWith({ taskId: 'task-1', comment: 'done' });
      // fetchData triggers a second getWorkbench call
      expect(mockGetWorkbench).toHaveBeenCalledTimes(2);
    });

    it('does nothing when dialog.task is null', async () => {
      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      // No openDialog call — task is null
      await act(async () => {
        await result.current.completeTask('done');
      });

      expect(mockCompleteTask).not.toHaveBeenCalled();
    });
  });

  describe('approveTask', () => {
    it('calls service with taskId and comment', async () => {
      mockApproveTask.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.openDialog('approve', makeTask()));

      await act(async () => {
        await result.current.approveTask('looks good');
      });

      expect(mockApproveTask).toHaveBeenCalledWith('task-1', 'looks good');
    });
  });

  describe('rejectTask', () => {
    it('calls service with taskId and comment', async () => {
      mockRejectTask.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.openDialog('reject', makeTask()));

      await act(async () => {
        await result.current.rejectTask('missing info');
      });

      expect(mockRejectTask).toHaveBeenCalledWith('task-1', 'missing info');
    });
  });

  describe('claimTask', () => {
    it('calls claimTask service with the taskId', async () => {
      mockClaimTask.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      await act(async () => {
        await result.current.claimTask(makeTask());
      });

      expect(mockClaimTask).toHaveBeenCalledWith('task-1');
    });
  });

  describe('delegateTask', () => {
    it('calls delegateTask service', async () => {
      mockDelegateTask.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.openDialog('delegate', makeTask()));

      await act(async () => {
        await result.current.delegateTask('user-42', 'please delegate');
      });

      expect(mockDelegateTask).toHaveBeenCalledWith('task-1', 'user-42', 'please delegate');
    });
  });

  describe('transferTask', () => {
    it('calls transferTask service', async () => {
      mockTransferTask.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.openDialog('transfer', makeTask()));

      await act(async () => {
        await result.current.transferTask('user-55', 'transferred');
      });

      expect(mockTransferTask).toHaveBeenCalledWith('task-1', 'user-55', 'transferred');
    });
  });

  describe('addSign', () => {
    it('calls addSign service', async () => {
      mockAddSign.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.openDialog('addSign', makeTask()));

      await act(async () => {
        await result.current.addSign('user-77', 'add reviewer');
      });

      expect(mockAddSign).toHaveBeenCalledWith('task-1', 'user-77', 'add reviewer');
    });
  });

  describe('removeSign', () => {
    it('calls removeSign service', async () => {
      mockRemoveSign.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.openDialog('removeSign', makeTask()));

      await act(async () => {
        await result.current.removeSign('user-88', 'remove reviewer');
      });

      expect(mockRemoveSign).toHaveBeenCalledWith('task-1', 'user-88', 'remove reviewer');
    });
  });

  describe('rollbackTask', () => {
    it('calls rollbackTask service with targetActivityId and reason', async () => {
      mockRollbackTask.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.openDialog('rollback', makeTask()));

      await act(async () => {
        await result.current.rollbackTask('activity-node-1', 'need changes');
      });

      expect(mockRollbackTask).toHaveBeenCalledWith('task-1', 'activity-node-1', 'need changes');
    });
  });

  // ── urgeTask ───────────────────────────────────────────────────────────────

  describe('urgeTask', () => {
    it('sends urge notification when task has assignee', async () => {
      mockSendUrge.mockResolvedValue(undefined);

      const toastMock = {
        showSuccessToast: vi.fn(),
        showErrorToast: vi.fn(),
        showWarningToast: vi.fn(),
        showInfoToast: vi.fn(),
        showToast: vi.fn(),
      };
      vi.mocked(useToastContext).mockReturnValue(toastMock);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      const task = makeTask({ taskName: 'Urgent Task' });
      await act(async () => {
        await result.current.urgeTask(task, 1001);
      });

      expect(mockSendUrge).toHaveBeenCalledWith(
        expect.objectContaining({
          taskId: 'task-1',
          processInstanceId: 'proc-1',
          senderUserId: 1001,
        }),
      );
      expect(toastMock.showSuccessToast).toHaveBeenCalledWith('催办已发送');
    });

    it('shows warning toast when task has no assignee', async () => {
      const toastMock = {
        showSuccessToast: vi.fn(),
        showErrorToast: vi.fn(),
        showWarningToast: vi.fn(),
        showInfoToast: vi.fn(),
        showToast: vi.fn(),
      };
      vi.mocked(useToastContext).mockReturnValue(toastMock);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      const noAssigneeTask = makeTask({ assignee: '', claimUserId: '' });
      await act(async () => {
        await result.current.urgeTask(noAssigneeTask, 1001);
      });

      expect(mockSendUrge).not.toHaveBeenCalled();
      expect(toastMock.showWarningToast).toHaveBeenCalledWith('该任务暂无处理人，无法催办');
    });
  });

  // ── Process actions ────────────────────────────────────────────────────────

  describe('suspendProcess', () => {
    it('calls suspendProcess service and re-fetches', async () => {
      mockSuspendProcess.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      await act(async () => {
        await result.current.suspendProcess(makeProcess());
      });

      expect(mockSuspendProcess).toHaveBeenCalledWith('proc-1');
      expect(mockGetWorkbench).toHaveBeenCalledTimes(2);
    });
  });

  describe('resumeProcess', () => {
    it('calls resumeProcess service and re-fetches', async () => {
      mockResumeProcess.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      await act(async () => {
        await result.current.resumeProcess(makeProcess());
      });

      expect(mockResumeProcess).toHaveBeenCalledWith('proc-1');
    });
  });

  describe('terminateProcess', () => {
    it('calls terminateProcess service when dialog.process is set', async () => {
      mockTerminateProcess.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.openDialog('terminate', undefined, makeProcess()));

      await act(async () => {
        await result.current.terminateProcess('cancelled by user');
      });

      expect(mockTerminateProcess).toHaveBeenCalledWith('proc-1', 'cancelled by user');
    });

    it('does nothing when dialog.process is null', async () => {
      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      await act(async () => {
        await result.current.terminateProcess('cancelled');
      });

      expect(mockTerminateProcess).not.toHaveBeenCalled();
    });
  });

  // ── Batch operations ───────────────────────────────────────────────────────

  describe('batchApprove', () => {
    it('calls batchProcessTasks with selected ids and action=approve', async () => {
      mockBatchProcessTasks.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.handleSelectTask('task-1', true));

      await act(async () => {
        await result.current.batchApprove();
      });

      expect(mockBatchProcessTasks).toHaveBeenCalledWith({
        taskIds: ['task-1'],
        action: 'approve',
        comment: '批量通过',
      });
      // Selection cleared after batch
      expect(result.current.selectedTasks.size).toBe(0);
    });

    it('shows warning toast when no tasks are selected', async () => {
      const toastMock = {
        showSuccessToast: vi.fn(),
        showErrorToast: vi.fn(),
        showWarningToast: vi.fn(),
        showInfoToast: vi.fn(),
        showToast: vi.fn(),
      };
      vi.mocked(useToastContext).mockReturnValue(toastMock);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      await act(async () => {
        await result.current.batchApprove();
      });

      expect(mockBatchProcessTasks).not.toHaveBeenCalled();
      expect(toastMock.showWarningToast).toHaveBeenCalledWith('请先选择任务');
    });
  });

  describe('batchReject', () => {
    it('calls batchProcessTasks with action=reject', async () => {
      mockBatchProcessTasks.mockResolvedValue(undefined);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.handleSelectTask('task-1', true));

      await act(async () => {
        await result.current.batchReject();
      });

      expect(mockBatchProcessTasks).toHaveBeenCalledWith(
        expect.objectContaining({ action: 'reject', comment: '批量驳回' }),
      );
    });

    it('shows warning toast when no tasks are selected', async () => {
      const toastMock = {
        showSuccessToast: vi.fn(),
        showErrorToast: vi.fn(),
        showWarningToast: vi.fn(),
        showInfoToast: vi.fn(),
        showToast: vi.fn(),
      };
      vi.mocked(useToastContext).mockReturnValue(toastMock);

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      await act(async () => {
        await result.current.batchReject();
      });

      expect(toastMock.showWarningToast).toHaveBeenCalledWith('请先选择任务');
    });
  });

  // ── Error path: service failure toasts ────────────────────────────────────

  describe('error toasts on service failure', () => {
    it('shows error toast when completeTask service rejects', async () => {
      const toastMock = {
        showSuccessToast: vi.fn(),
        showErrorToast: vi.fn(),
        showWarningToast: vi.fn(),
        showInfoToast: vi.fn(),
        showToast: vi.fn(),
      };
      vi.mocked(useToastContext).mockReturnValue(toastMock);

      mockCompleteTask.mockRejectedValue(new Error('server error'));

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.openDialog('complete', makeTask()));

      await act(async () => {
        await result.current.completeTask('done');
      });

      expect(toastMock.showErrorToast).toHaveBeenCalledWith('完成任务失败');
    });

    it('shows error toast when approveTask service rejects', async () => {
      const toastMock = {
        showSuccessToast: vi.fn(),
        showErrorToast: vi.fn(),
        showWarningToast: vi.fn(),
        showInfoToast: vi.fn(),
        showToast: vi.fn(),
      };
      vi.mocked(useToastContext).mockReturnValue(toastMock);

      mockApproveTask.mockRejectedValue(new Error('server error'));

      const { result } = renderHook(() => useTaskCenter());
      await waitFor(() => expect(result.current.loading).toBe(false));

      act(() => result.current.openDialog('approve', makeTask()));

      await act(async () => {
        await result.current.approveTask('ok');
      });

      expect(toastMock.showErrorToast).toHaveBeenCalledWith('通过任务失败');
    });
  });
});
