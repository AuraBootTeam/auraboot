import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('~/shared/services/http-client', () => {
  const ErrorCodes = { SUCCESS: '0' } as const;
  const get = vi.fn();
  const post = vi.fn();
  return { get, post, ErrorCodes };
});

import { get, post } from '~/shared/services/http-client';
import {
  claimTask,
  completeTask,
  delegateTask,
  getCompletedTasks,
  getInstanceForRecord,
  getStartedProcesses,
  getTaskDetail,
  getTodoTasks,
  getWorkbench,
  startProcess,
  transferTask,
} from '~/plugins/core-bpm/services/bpmWorkbenchService';

const mockedGet = vi.mocked(get);
const mockedPost = vi.mocked(post);

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

  it('reads started processes from the BPM workbench contract', async () => {
    mockedGet.mockResolvedValue({
      code: '0',
      data: {
        todoTasks: [],
        completedTasks: [],
        startedProcesses: [
          {
            instanceId: 'pi-started-1',
            processDefinitionId: 'dwr_process',
            bizUniqueId: 'DWR-BPM-1',
            startUserId: '10001',
            startTime: '2026-06-12T08:00:00Z',
            status: 'running',
          },
        ],
      },
    } as any);

    await expect(getStartedProcesses()).resolves.toEqual([
      expect.objectContaining({
        instanceId: 'pi-started-1',
        processDefinitionKey: 'dwr_process',
        businessKey: 'DWR-BPM-1',
        status: 'running',
      }),
    ]);

    expect(mockedGet).toHaveBeenCalledWith('/api/bpm/workbench', {});
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

describe('bpmWorkbenchService task detail + mutations', () => {
  beforeEach(() => {
    mockedGet.mockReset();
    mockedPost.mockReset();
  });

  it('getTaskDetail GETs the task by id', async () => {
    mockedGet.mockResolvedValue({ code: '0', data: { taskId: 't-1', taskName: 'Approve' } } as any);
    await expect(getTaskDetail('t-1')).resolves.toBeDefined();
    expect(mockedGet).toHaveBeenCalledWith('/api/bpm/tasks/t-1');
  });

  it('getTaskDetail throws the backend desc on failure', async () => {
    mockedGet.mockResolvedValue({ code: '50000', desc: 'boom', data: null } as any);
    await expect(getTaskDetail('t-x')).rejects.toThrow('boom');
  });

  it('completeTask POSTs variables + comment to the complete endpoint', async () => {
    mockedPost.mockResolvedValue({ code: '0' } as any);
    await completeTask({ taskId: 't-2', variables: { approved: true }, comment: 'ok' });
    expect(mockedPost).toHaveBeenCalledWith('/api/bpm/tasks/t-2/complete', {
      variables: { approved: true },
      comment: 'ok',
    });
  });

  it('claimTask POSTs to the claim endpoint', async () => {
    mockedPost.mockResolvedValue({ code: '0' } as any);
    await claimTask('t-3');
    expect(mockedPost).toHaveBeenCalledWith('/api/bpm/tasks/t-3/claim');
  });

  it('delegateTask POSTs userId + comment to the delegate endpoint', async () => {
    mockedPost.mockResolvedValue({ code: '0' } as any);
    await delegateTask('t-4', 'u-9', 'please handle');
    expect(mockedPost).toHaveBeenCalledWith('/api/bpm/tasks/t-4/delegate', {
      userId: 'u-9',
      comment: 'please handle',
    });
  });

  it('transferTask POSTs userId + comment to the transfer endpoint', async () => {
    mockedPost.mockResolvedValue({ code: '0' } as any);
    await transferTask('t-5', 'u-10', 'reassign');
    expect(mockedPost).toHaveBeenCalledWith('/api/bpm/tasks/t-5/transfer', {
      userId: 'u-10',
      comment: 'reassign',
    });
  });

  it('startProcess POSTs the request and returns the instance id', async () => {
    mockedPost.mockResolvedValue({ code: '0', data: 'pi-new-1' } as any);
    const request = { processKey: 'wd_leave', businessKey: 'BIZ-9', variables: {} } as any;
    await expect(startProcess(request)).resolves.toBe('pi-new-1');
    expect(mockedPost).toHaveBeenCalledWith('/api/bpm/workbench/start-process', request);
  });

  it('a failed mutation throws the backend desc', async () => {
    mockedPost.mockResolvedValue({ code: '40000', desc: 'denied' } as any);
    await expect(claimTask('t-6')).rejects.toThrow('denied');
  });
});
