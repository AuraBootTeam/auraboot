import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

const showSuccessToast = vi.fn();
const showErrorToast = vi.fn();

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast, showErrorToast }),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

vi.mock('~/utils/type', () => ({
  ResultHelper: {
    isSuccess: (r: { code: string }) => r.code === '0',
  },
}));

import { useScheduledTask } from '../useScheduledTask';
import { fetchResult } from '~/shared/services/http-client';

const mockFetch = fetchResult as ReturnType<typeof vi.fn>;

const successResult = (data: unknown) => ({ code: '0', data });
const errorResult = (message = 'error') => ({ code: '1', message, data: null });

describe('useScheduledTask', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('initial state', () => {
    const { result } = renderHook(() => useScheduledTask());
    expect(result.current.tasks).toEqual([]);
    expect(result.current.logs).toEqual([]);
    expect(result.current.loading).toBe(false);
    expect(result.current.logsLoading).toBe(false);
  });

  it('fetchTasks populates tasks on success', async () => {
    const tasks = [{ id: 1, name: 'task1', pid: 'p1', enabled: true }];
    mockFetch.mockResolvedValue(successResult(tasks));

    const { result } = renderHook(() => useScheduledTask());
    await act(async () => {
      await result.current.fetchTasks();
    });

    expect(result.current.tasks).toEqual(tasks);
    expect(result.current.loading).toBe(false);
    expect(mockFetch).toHaveBeenCalledWith('/api/scheduled-tasks', { method: 'get' });
  });

  it('fetchTasks shows error toast on failure', async () => {
    mockFetch.mockResolvedValue(errorResult('not found'));

    const { result } = renderHook(() => useScheduledTask());
    await act(async () => {
      await result.current.fetchTasks();
    });

    expect(showErrorToast).toHaveBeenCalledWith('not found');
  });

  it('fetchTasks shows error toast on network exception', async () => {
    mockFetch.mockRejectedValue(new Error('timeout'));

    const { result } = renderHook(() => useScheduledTask());
    await act(async () => {
      await result.current.fetchTasks();
    });

    expect(showErrorToast).toHaveBeenCalledWith('Failed to load scheduled tasks');
  });

  it('createTask returns true and refreshes tasks on success', async () => {
    // First call for create, second for fetchTasks
    mockFetch.mockResolvedValue(successResult({}));

    const { result } = renderHook(() => useScheduledTask());
    let success: boolean = false;
    await act(async () => {
      success = await result.current.createTask({
        name: 'new-task',
        taskType: 'cron',
        handlerBean: 'myBean',
      });
    });

    expect(success).toBe(true);
    expect(showSuccessToast).toHaveBeenCalledWith('Task created successfully');
  });

  it('createTask returns false on API error', async () => {
    mockFetch.mockResolvedValue(errorResult('Bad request'));

    const { result } = renderHook(() => useScheduledTask());
    let success: boolean = true;
    await act(async () => {
      success = await result.current.createTask({
        name: 'bad',
        taskType: 'cron',
        handlerBean: 'bean',
      });
    });

    expect(success).toBe(false);
    expect(showErrorToast).toHaveBeenCalledWith('Bad request');
  });

  it('enableTask optimistically sets enabled=true in tasks', async () => {
    // Prime tasks first
    const tasks = [{ id: 1, pid: 'p1', name: 'task', enabled: false }];
    mockFetch.mockResolvedValueOnce(successResult(tasks));
    const { result } = renderHook(() => useScheduledTask());
    await act(async () => {
      await result.current.fetchTasks();
    });

    // Now enable
    mockFetch.mockResolvedValue(successResult(null));
    await act(async () => {
      await result.current.enableTask('p1');
    });

    expect(result.current.tasks[0].enabled).toBe(true);
    expect(showSuccessToast).toHaveBeenCalledWith('Task enabled');
  });

  it('disableTask optimistically sets enabled=false in tasks', async () => {
    const tasks = [{ id: 1, pid: 'p1', name: 'task', enabled: true }];
    mockFetch.mockResolvedValueOnce(successResult(tasks));
    const { result } = renderHook(() => useScheduledTask());
    await act(async () => {
      await result.current.fetchTasks();
    });

    mockFetch.mockResolvedValue(successResult(null));
    await act(async () => {
      await result.current.disableTask('p1');
    });

    expect(result.current.tasks[0].enabled).toBe(false);
    expect(showSuccessToast).toHaveBeenCalledWith('Task disabled');
  });

  it('triggerTask returns true and shows toast on success', async () => {
    mockFetch.mockResolvedValue(successResult(null));
    const { result } = renderHook(() => useScheduledTask());
    let ok: boolean = false;
    await act(async () => {
      ok = await result.current.triggerTask('p1');
    });
    expect(ok).toBe(true);
    expect(showSuccessToast).toHaveBeenCalledWith('Task triggered');
  });

  it('deleteTask returns true and refreshes on success', async () => {
    mockFetch.mockResolvedValue(successResult(null));
    const { result } = renderHook(() => useScheduledTask());
    let ok: boolean = false;
    await act(async () => {
      ok = await result.current.deleteTask('p1');
    });
    expect(ok).toBe(true);
    expect(showSuccessToast).toHaveBeenCalledWith('Task deleted successfully');
  });

  it('updateTask returns true and refreshes on success', async () => {
    mockFetch.mockResolvedValue(successResult(null));
    const { result } = renderHook(() => useScheduledTask());
    let ok: boolean = false;
    await act(async () => {
      ok = await result.current.updateTask('p1', {
        name: 'updated',
        taskType: 'interval',
        handlerBean: 'bean',
      });
    });
    expect(ok).toBe(true);
    expect(showSuccessToast).toHaveBeenCalledWith('Task updated successfully');
    expect(mockFetch).toHaveBeenCalledWith('/api/scheduled-tasks/p1', expect.objectContaining({ method: 'put' }));
  });

  it('fetchLogs populates logs on success', async () => {
    const logs = [{ id: 10, taskPid: 'p1', status: 'success' }];
    mockFetch.mockResolvedValue(successResult(logs));
    const { result } = renderHook(() => useScheduledTask());
    await act(async () => {
      await result.current.fetchLogs('p1', 10);
    });
    expect(result.current.logs).toEqual(logs);
    expect(result.current.logsLoading).toBe(false);
  });

  it('reloadScheduler returns true and refreshes on success', async () => {
    mockFetch.mockResolvedValue(successResult(null));
    const { result } = renderHook(() => useScheduledTask());
    let ok: boolean = false;
    await act(async () => {
      ok = await result.current.reloadScheduler();
    });
    expect(ok).toBe(true);
    expect(showSuccessToast).toHaveBeenCalledWith('Scheduler reloaded');
  });
});
