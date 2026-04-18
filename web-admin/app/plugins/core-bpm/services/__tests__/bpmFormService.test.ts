/**
 * Tests for bpmFormService.getTaskForm shape contract.
 *
 * Pin the response shape to backend TaskFormResponse so a future renaming
 * regression (single formBinding ↔ multi-form forms[]) is caught at the
 * unit level instead of leaking to E2E or production.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('~/shared/services/http-client', () => {
  const ErrorCodes = { SUCCESS: '0' } as const;
  const get = vi.fn();
  const post = vi.fn();
  return { get, post, ErrorCodes };
});

import { get } from '~/shared/services/http-client';
import { getTaskForm } from '~/plugins/core-bpm/services/bpmFormService';

const mockedGet = get as unknown as ReturnType<typeof vi.fn>;

describe('bpmFormService.getTaskForm', () => {
  beforeEach(() => {
    mockedGet.mockReset();
  });

  it('returns the backend formBinding object verbatim when the task has a binding', async () => {
    mockedGet.mockResolvedValue({
      code: '0',
      data: {
        taskId: 't-1',
        taskName: 'task_manager_approve',
        processName: 'wd_leave_approval',
        processInstanceId: 'pi-1',
        nodeId: 'task_manager_approve',
        businessKey: 'wlr-1',
        processVariables: { decision: null },
        formBinding: {
          nodeId: 'task_manager_approve',
          formType: 'PAGE',
          formRef: 'wd_leave_request_detail',
          fieldPermissions: { wd_req_reason: 'readonly' },
          saveStrategy: 'business_only',
          variableBindings: { decision: 'wd_req_decision' },
        },
        taskActions: [
          { key: 'approve', type: 'complete', resultVariable: 'taskResult', resultValue: 'approved' },
        ],
      },
    });

    const result = await getTaskForm('t-1');

    expect(result.taskId).toBe('t-1');
    expect(result.formBinding).not.toBeNull();
    expect(result.formBinding?.formRef).toBe('wd_leave_request_detail');
    expect(result.formBinding?.formType).toBe('PAGE');
    expect(result.formBinding?.fieldPermissions).toEqual({ wd_req_reason: 'readonly' });
    expect(result.formBinding?.saveStrategy).toBe('business_only');
    expect(result.formBinding?.variableBindings).toEqual({ decision: 'wd_req_decision' });
    expect(result.businessKey).toBe('wlr-1');
    expect(result.taskActions?.[0]?.resultVariable).toBe('taskResult');
    // Regression guard: the legacy { hasForm, forms[] } shape must not leak back in.
    expect((result as unknown as Record<string, unknown>).hasForm).toBeUndefined();
    expect((result as unknown as Record<string, unknown>).forms).toBeUndefined();
  });

  it('returns formBinding=null when the node has no form attached', async () => {
    mockedGet.mockResolvedValue({
      code: '0',
      data: {
        taskId: 't-2',
        processInstanceId: 'pi-2',
        nodeId: 'plain_user_task',
        formBinding: null,
        taskActions: null,
      },
    });

    const result = await getTaskForm('t-2');

    expect(result.formBinding).toBeNull();
    expect(result.taskActions).toBeNull();
  });

  it('throws when the API returns a non-success code', async () => {
    mockedGet.mockResolvedValue({ code: '500', desc: 'boom', data: null });
    await expect(getTaskForm('t-3')).rejects.toThrow(/boom/);
  });
});
