import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('~/shared/services/http-client', () => {
  const ErrorCodes = { SUCCESS: '0' } as const;
  const get = vi.fn();
  const post = vi.fn();
  return { get, post, ErrorCodes };
});

import { get } from '~/shared/services/http-client';
import {
  getCompletedTasks,
  getInstanceForRecord,
  getTodoTasks,
  getWorkbench,
} from '~/plugins/core-bpm/services/bpmWorkbenchService';

const mockedGet = vi.mocked(get);

describe('bpmWorkbenchService query param wiring', () => {
  beforeEach(() => {
    mockedGet.mockReset();
  });

  it('passes workbench filters as flat query params', async () => {
    mockedGet
      .mockResolvedValueOnce({ code: '0', data: { todoTasks: [], completedTasks: [] } } as any)
      .mockResolvedValueOnce({ code: '0', data: [] } as any)
      .mockResolvedValueOnce({ code: '0', data: [] } as any);

    await expect(getWorkbench('u-1')).resolves.toMatchObject({ todoTasks: [], completedTasks: [] });
    await expect(getTodoTasks('u-2')).resolves.toEqual([]);
    await expect(getCompletedTasks('u-3')).resolves.toEqual([]);

    expect(mockedGet).toHaveBeenNthCalledWith(1, '/api/bpm/workbench', { userId: 'u-1' });
    expect(mockedGet).toHaveBeenNthCalledWith(2, '/api/bpm/tasks/todo', { userId: 'u-2' });
    expect(mockedGet).toHaveBeenNthCalledWith(3, '/api/bpm/tasks/completed', { userId: 'u-3' });
  });

  it('queries process-instance status with flat businessKey params', async () => {
    mockedGet.mockResolvedValue({
      code: '0',
      data: {
        instanceId: 'pi-1',
        processDefinitionId: 'pd-1',
        status: 'running',
        currentNodes: [],
        completedNodes: [],
        variables: {},
      },
    } as any);

    const instance = await getInstanceForRecord('BIZ-1', 'wd_leave_approval');

    expect(mockedGet).toHaveBeenCalledWith('/api/bpm/process-instances/by-business-key/status', {
      businessKey: 'BIZ-1',
      processKey: 'wd_leave_approval',
    });
    expect(instance?.instanceId).toBe('pi-1');
  });

  it('returns null when the backend reports missing process instance', async () => {
    mockedGet.mockResolvedValue({
      code: '35000',
      message: 'Process instance not found for businessKey: BIZ-2',
      context: 'Process instance not found for businessKey: BIZ-2',
      data: null,
    } as any);

    await expect(getInstanceForRecord('BIZ-2')).resolves.toBeNull();
  });
});
