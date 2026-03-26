import { useState, useCallback } from 'react';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';

/**
 * Scheduled Task entity
 */
export interface ScheduledTask {
  id: number;
  pid: string;
  name: string;
  description: string | null;
  taskType: 'cron' | 'interval' | 'one_time';
  cronExpression: string | null;
  intervalMs: number | null;
  handlerBean: string;
  handlerMethod: string;
  params: string | null;
  maxRetries: number;
  timeoutMs: number;
  enabled: boolean;
  lastRunAt: string | null;
  nextRunAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Scheduled Task Log entity
 */
export interface ScheduledTaskLog {
  id: number;
  taskPid: string;
  status: 'running' | 'success' | 'failed' | 'timeout';
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  result: string | null;
  errorMessage: string | null;
  retryCount: number;
  triggerType: 'scheduled' | 'manual';
}

/**
 * Create/Update request
 */
export interface ScheduledTaskRequest {
  name: string;
  description?: string;
  taskType: 'cron' | 'interval' | 'one_time';
  cronExpression?: string;
  intervalMs?: number;
  handlerBean: string;
  handlerMethod?: string;
  params?: string;
  maxRetries?: number;
  timeoutMs?: number;
  enabled?: boolean;
}

/**
 * Hook for managing scheduled tasks
 */
export function useScheduledTask() {
  const [tasks, setTasks] = useState<ScheduledTask[]>([]);
  const [logs, setLogs] = useState<ScheduledTaskLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [logsLoading, setLogsLoading] = useState(false);
  const { showSuccessToast, showErrorToast } = useToastContext();

  /**
   * Fetch all tasks
   */
  const fetchTasks = useCallback(async () => {
    setLoading(true);
    try {
      const result = await fetchResult('/api/scheduled-tasks', {
        method: 'get',
      });

      if (ResultHelper.isSuccess(result)) {
        setTasks((result.data as ScheduledTask[]) || []);
      } else {
        showErrorToast(result.message || 'Failed to load tasks');
      }
    } catch (error) {
      console.error('Failed to fetch tasks:', error);
      showErrorToast('Failed to load scheduled tasks');
    } finally {
      setLoading(false);
    }
  }, [showErrorToast]);

  /**
   * Create a new task
   */
  const createTask = useCallback(
    async (request: ScheduledTaskRequest): Promise<boolean> => {
      try {
        const result = await fetchResult('/api/scheduled-tasks', {
          method: 'post',
          params: request,
        });

        if (ResultHelper.isSuccess(result)) {
          showSuccessToast('Task created successfully');
          await fetchTasks();
          return true;
        } else {
          showErrorToast(result.message || 'Failed to create task');
          return false;
        }
      } catch (error) {
        console.error('Failed to create task:', error);
        showErrorToast('Failed to create task');
        return false;
      }
    },
    [showSuccessToast, showErrorToast, fetchTasks],
  );

  /**
   * Update a task
   */
  const updateTask = useCallback(
    async (pid: string, request: ScheduledTaskRequest): Promise<boolean> => {
      try {
        const result = await fetchResult(`/api/scheduled-tasks/${pid}`, {
          method: 'put',
          params: request,
        });

        if (ResultHelper.isSuccess(result)) {
          showSuccessToast('Task updated successfully');
          await fetchTasks();
          return true;
        } else {
          showErrorToast(result.message || 'Failed to update task');
          return false;
        }
      } catch (error) {
        console.error('Failed to update task:', error);
        showErrorToast('Failed to update task');
        return false;
      }
    },
    [showSuccessToast, showErrorToast, fetchTasks],
  );

  /**
   * Delete a task
   */
  const deleteTask = useCallback(
    async (pid: string): Promise<boolean> => {
      try {
        const result = await fetchResult(`/api/scheduled-tasks/${pid}`, {
          method: 'delete',
        });

        if (ResultHelper.isSuccess(result)) {
          showSuccessToast('Task deleted successfully');
          await fetchTasks();
          return true;
        } else {
          showErrorToast(result.message || 'Failed to delete task');
          return false;
        }
      } catch (error) {
        console.error('Failed to delete task:', error);
        showErrorToast('Failed to delete task');
        return false;
      }
    },
    [showSuccessToast, showErrorToast, fetchTasks],
  );

  /**
   * Enable a task
   */
  const enableTask = useCallback(
    async (pid: string): Promise<boolean> => {
      try {
        const result = await fetchResult(`/api/scheduled-tasks/${pid}/enable`, {
          method: 'put',
        });

        if (ResultHelper.isSuccess(result)) {
          showSuccessToast('Task enabled');
          setTasks((prev) => prev.map((t) => (t.pid === pid ? { ...t, enabled: true } : t)));
          return true;
        } else {
          showErrorToast(result.message || 'Failed to enable task');
          return false;
        }
      } catch (error) {
        console.error('Failed to enable task:', error);
        showErrorToast('Failed to enable task');
        return false;
      }
    },
    [showSuccessToast, showErrorToast],
  );

  /**
   * Disable a task
   */
  const disableTask = useCallback(
    async (pid: string): Promise<boolean> => {
      try {
        const result = await fetchResult(`/api/scheduled-tasks/${pid}/disable`, {
          method: 'put',
        });

        if (ResultHelper.isSuccess(result)) {
          showSuccessToast('Task disabled');
          setTasks((prev) => prev.map((t) => (t.pid === pid ? { ...t, enabled: false } : t)));
          return true;
        } else {
          showErrorToast(result.message || 'Failed to disable task');
          return false;
        }
      } catch (error) {
        console.error('Failed to disable task:', error);
        showErrorToast('Failed to disable task');
        return false;
      }
    },
    [showSuccessToast, showErrorToast],
  );

  /**
   * Trigger task manually
   */
  const triggerTask = useCallback(
    async (pid: string): Promise<boolean> => {
      try {
        const result = await fetchResult(`/api/scheduled-tasks/${pid}/trigger`, {
          method: 'post',
        });

        if (ResultHelper.isSuccess(result)) {
          showSuccessToast('Task triggered');
          return true;
        } else {
          showErrorToast(result.message || 'Failed to trigger task');
          return false;
        }
      } catch (error) {
        console.error('Failed to trigger task:', error);
        showErrorToast('Failed to trigger task');
        return false;
      }
    },
    [showSuccessToast, showErrorToast],
  );

  /**
   * Fetch logs for a task
   */
  const fetchLogs = useCallback(
    async (pid: string, limit: number = 20) => {
      setLogsLoading(true);
      try {
        const result = await fetchResult(`/api/scheduled-tasks/${pid}/logs`, {
          method: 'get',
          params: { limit: limit.toString() },
        });

        if (ResultHelper.isSuccess(result)) {
          setLogs((result.data as ScheduledTaskLog[]) || []);
        } else {
          showErrorToast(result.message || 'Failed to load logs');
        }
      } catch (error) {
        console.error('Failed to fetch logs:', error);
        showErrorToast('Failed to load task logs');
      } finally {
        setLogsLoading(false);
      }
    },
    [showErrorToast],
  );

  /**
   * Reload all tasks in scheduler
   */
  const reloadScheduler = useCallback(async (): Promise<boolean> => {
    try {
      const result = await fetchResult('/api/scheduled-tasks/reload', {
        method: 'post',
      });

      if (ResultHelper.isSuccess(result)) {
        showSuccessToast('Scheduler reloaded');
        await fetchTasks();
        return true;
      } else {
        showErrorToast(result.message || 'Failed to reload scheduler');
        return false;
      }
    } catch (error) {
      console.error('Failed to reload scheduler:', error);
      showErrorToast('Failed to reload scheduler');
      return false;
    }
  }, [showSuccessToast, showErrorToast, fetchTasks]);

  return {
    tasks,
    logs,
    loading,
    logsLoading,
    fetchTasks,
    createTask,
    updateTask,
    deleteTask,
    enableTask,
    disableTask,
    triggerTask,
    fetchLogs,
    reloadScheduler,
  };
}
