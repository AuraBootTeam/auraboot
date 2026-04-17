/**
 * BpmOperationsSection.test.tsx
 *
 * Unit tests for the Task 13 operations section:
 *   - instance === null renders nothing (status section owns the empty state);
 *   - non-running instance renders the "closed" info row and no buttons;
 *   - running instance + current user is an assignee → approve/reject/cc
 *     buttons are enabled; initiator → withdraw is enabled;
 *   - disabled buttons surface the blocked-reason tooltip via `title`;
 *   - approve → clicks runApprove → approveTask() is called with the
 *     assignee-matching task id and onActionComplete fires.
 */

import React from 'react';
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';

// --- Mock the dialogs so the section tests focus on wiring, not dialog internals.
vi.mock('../WithdrawDialog', () => ({
  WithdrawDialog: ({
    open,
    taskId,
    onConfirm,
  }: {
    open: boolean;
    taskId: string;
    onConfirm: (reason?: string) => Promise<void>;
  }) =>
    open ? (
      <div data-testid="withdraw-dialog-stub" data-task-id={taskId}>
        <button
          type="button"
          data-testid="withdraw-dialog-stub-confirm"
          onClick={() => void onConfirm('reason-stub')}
        >
          confirm
        </button>
      </div>
    ) : null,
}));

vi.mock('../CcDialog', () => ({
  CcDialog: ({
    open,
    taskId,
    onConfirm,
  }: {
    open: boolean;
    taskId: string;
    onConfirm: (receivers: string[], comment: string) => Promise<void>;
  }) =>
    open ? (
      <div data-testid="cc-dialog-stub" data-task-id={taskId}>
        <button
          type="button"
          data-testid="cc-dialog-stub-confirm"
          onClick={() => void onConfirm(['u-99'], 'fyi')}
        >
          confirm
        </button>
      </div>
    ) : null,
}));

vi.mock('../TerminateDialog', () => ({
  TerminateDialog: ({
    open,
    processInstanceId,
    onConfirm,
  }: {
    open: boolean;
    processInstanceId: string;
    onConfirm: (reason: string) => Promise<void>;
  }) =>
    open ? (
      <div data-testid="terminate-dialog-stub" data-instance-id={processInstanceId}>
        <button
          type="button"
          data-testid="terminate-dialog-stub-confirm"
          onClick={() => void onConfirm('reason-stub')}
        >
          confirm
        </button>
      </div>
    ) : null,
}));

// --- Mock useAuth with a mutable handle so individual tests can flip the user.
const authStub = {
  user: { pid: 'u-200' } as { pid?: string } | null,
  permissions: [] as string[],
};
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: authStub.user,
    hasPermission: (code: string) => authStub.permissions.includes(code),
  }),
}));

// --- Mock the workbench service endpoints.
const approveTaskSpy = vi.fn().mockResolvedValue(undefined);
const rejectTaskSpy = vi.fn().mockResolvedValue(undefined);
const withdrawTaskSpy = vi.fn().mockResolvedValue(undefined);
const ccTaskSpy = vi.fn().mockResolvedValue(undefined);
const terminateProcessSpy = vi.fn().mockResolvedValue(undefined);
const getTasksByProcessInstanceSpy = vi.fn<() => Promise<any[]>>();

vi.mock('~/plugins/core-bpm/services/bpmWorkbenchService', () => ({
  approveTask: (...args: unknown[]) =>
    approveTaskSpy(...(args as Parameters<typeof approveTaskSpy>)),
  rejectTask: (...args: unknown[]) =>
    rejectTaskSpy(...(args as Parameters<typeof rejectTaskSpy>)),
  withdrawTask: (...args: unknown[]) =>
    withdrawTaskSpy(...(args as Parameters<typeof withdrawTaskSpy>)),
  ccTask: (...args: unknown[]) => ccTaskSpy(...(args as Parameters<typeof ccTaskSpy>)),
  terminateProcess: (...args: unknown[]) =>
    terminateProcessSpy(...(args as Parameters<typeof terminateProcessSpy>)),
  getTasksByProcessInstance: (...args: unknown[]) =>
    getTasksByProcessInstanceSpy(
      ...(args as Parameters<typeof getTasksByProcessInstanceSpy>),
    ),
}));

import { BpmOperationsSection } from '../BpmOperationsSection';
import type { BpmInstanceForRecord } from '~/plugins/core-bpm/services/bpmWorkbenchService';

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
  authStub.user = { pid: 'u-200' };
  authStub.permissions = [];
});

beforeEach(() => {
  getTasksByProcessInstanceSpy.mockResolvedValue([]);
});

const t = (_key: string, _params?: Record<string, unknown>, fallback?: string): string =>
  fallback ?? _key;

function buildInstance(overrides: Partial<BpmInstanceForRecord> = {}): BpmInstanceForRecord {
  return {
    instanceId: 'pi-001',
    processDefinitionId: 'pd-alpha',
    status: 'running',
    currentNodes: [],
    completedNodes: [],
    variables: {},
    ...overrides,
  };
}

function runningInstance(): BpmInstanceForRecord {
  return buildInstance({
    variables: { startUserId: 'u-100' },
    currentNodes: [
      {
        nodeId: 'approver',
        type: 'userTask',
        name: '审批',
        status: 'running',
        assignee: 'u-200',
        completedAt: null,
        completedBy: null,
      },
    ],
  });
}

describe('BpmOperationsSection', () => {
  it('renders nothing when the instance is null', () => {
    const { container } = render(
      <BpmOperationsSection instance={null} t={t} />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders the closed info row and no buttons for a terminal instance', () => {
    const instance = buildInstance({ status: 'approved' });
    render(<BpmOperationsSection instance={instance} t={t} />);

    const closed = screen.getByTestId('bpm-operations-closed');
    expect(closed).toHaveAttribute('data-status', 'approved');
    expect(closed).toHaveTextContent('流程已结束');
    expect(screen.queryByTestId('bpm-operations-approve')).toBeNull();
  });

  it('enables approve/reject/cc for assignee and wires approveTask on confirm', async () => {
    authStub.user = { pid: 'u-200' };
    getTasksByProcessInstanceSpy.mockResolvedValue([
      {
        instanceId: 'pi-001',
        taskId: 'task-7',
        processInstanceId: 'pi-001',
        processDefinitionKey: 'pd-alpha',
        taskDefinitionKey: 'approver',
        taskName: '审批',
        assignee: 'u-200',
        claimUserId: 'u-200',
        createTime: '2026-04-17T00:00:00Z',
        priority: 0,
      },
    ]);

    const onActionComplete = vi.fn();
    await act(async () => {
      render(
        <BpmOperationsSection
          instance={runningInstance()}
          onActionComplete={onActionComplete}
          t={t}
        />,
      );
    });

    const approveBtn = screen.getByTestId('bpm-operations-approve') as HTMLButtonElement;
    const rejectBtn = screen.getByTestId('bpm-operations-reject') as HTMLButtonElement;
    const ccBtn = screen.getByTestId('bpm-operations-cc') as HTMLButtonElement;
    const withdrawBtn = screen.getByTestId('bpm-operations-withdraw') as HTMLButtonElement;

    expect(approveBtn.disabled).toBe(false);
    expect(rejectBtn.disabled).toBe(false);
    expect(ccBtn.disabled).toBe(false);
    // u-200 is an assignee but NOT the initiator (u-100) → withdraw disabled.
    expect(withdrawBtn.disabled).toBe(true);
    expect(withdrawBtn.getAttribute('title')).toContain('仅发起人可撤回');

    // Click approve → open dialog → confirm.
    fireEvent.click(approveBtn);
    expect(screen.getByTestId('bpm-approve-dialog')).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(screen.getByTestId('bpm-approve-confirm'));
    });

    expect(approveTaskSpy).toHaveBeenCalledWith('task-7', undefined);
    expect(onActionComplete).toHaveBeenCalledTimes(1);
  });

  it('enables withdraw only when current user is the initiator', async () => {
    authStub.user = { pid: 'u-100' };
    getTasksByProcessInstanceSpy.mockResolvedValue([
      {
        instanceId: 'pi-001',
        taskId: 'task-7',
        processInstanceId: 'pi-001',
        processDefinitionKey: 'pd-alpha',
        taskDefinitionKey: 'approver',
        taskName: '审批',
        assignee: 'u-200',
        claimUserId: 'u-200',
        createTime: '2026-04-17T00:00:00Z',
        priority: 0,
      },
    ]);

    const onActionComplete = vi.fn();
    await act(async () => {
      render(
        <BpmOperationsSection
          instance={runningInstance()}
          onActionComplete={onActionComplete}
          t={t}
        />,
      );
    });

    const withdrawBtn = screen.getByTestId(
      'bpm-operations-withdraw',
    ) as HTMLButtonElement;
    expect(withdrawBtn.disabled).toBe(false);

    fireEvent.click(withdrawBtn);
    const stub = screen.getByTestId('withdraw-dialog-stub');
    expect(stub).toHaveAttribute('data-task-id', 'task-7');

    await act(async () => {
      fireEvent.click(screen.getByTestId('withdraw-dialog-stub-confirm'));
    });

    expect(withdrawTaskSpy).toHaveBeenCalledWith('task-7', 'reason-stub');
    expect(onActionComplete).toHaveBeenCalled();
  });

  it('unlocks every button when the current user has bpm.admin on a running instance', async () => {
    authStub.user = { pid: 'u-admin' };
    authStub.permissions = ['bpm.admin'];
    getTasksByProcessInstanceSpy.mockResolvedValue([
      {
        instanceId: 'pi-001',
        taskId: 'task-7',
        processInstanceId: 'pi-001',
        processDefinitionKey: 'pd-alpha',
        taskDefinitionKey: 'approver',
        taskName: '审批',
        assignee: 'someone-else',
        claimUserId: 'someone-else',
        createTime: '2026-04-17T00:00:00Z',
        priority: 0,
      },
    ]);

    await act(async () => {
      render(<BpmOperationsSection instance={runningInstance()} t={t} />);
    });

    // Admin is not the assignee, so assignee-matching task id is null.
    // Withdraw falls back to the first pending task, approve/reject/cc are
    // task.none-blocked even though policy allows.
    expect(
      (screen.getByTestId('bpm-operations-withdraw') as HTMLButtonElement).disabled,
    ).toBe(false);
    expect(
      (screen.getByTestId('bpm-operations-approve') as HTMLButtonElement).getAttribute(
        'title',
      ),
    ).toContain('暂无待办任务');
  });

  // ---- Fix B: terminate button + dialog wiring ----

  it('enables terminate for bpm.admin and wires terminateProcess on confirm', async () => {
    authStub.user = { pid: 'u-admin' };
    authStub.permissions = ['bpm.admin'];
    getTasksByProcessInstanceSpy.mockResolvedValue([]);

    const onActionComplete = vi.fn();
    await act(async () => {
      render(
        <BpmOperationsSection
          instance={runningInstance()}
          onActionComplete={onActionComplete}
          t={t}
        />,
      );
    });

    const terminateBtn = screen.getByTestId(
      'bpm-operations-terminate',
    ) as HTMLButtonElement;
    expect(terminateBtn.disabled).toBe(false);

    fireEvent.click(terminateBtn);
    const stub = screen.getByTestId('terminate-dialog-stub');
    expect(stub).toHaveAttribute('data-instance-id', 'pi-001');

    await act(async () => {
      fireEvent.click(screen.getByTestId('terminate-dialog-stub-confirm'));
    });

    expect(terminateProcessSpy).toHaveBeenCalledWith('pi-001', 'reason-stub');
    expect(onActionComplete).toHaveBeenCalled();
  });

  it('disables terminate for non-bpm.admin users and surfaces the reason tooltip', async () => {
    authStub.user = { pid: 'u-200' };
    authStub.permissions = [];
    getTasksByProcessInstanceSpy.mockResolvedValue([]);

    await act(async () => {
      render(<BpmOperationsSection instance={runningInstance()} t={t} />);
    });

    const terminateBtn = screen.getByTestId(
      'bpm-operations-terminate',
    ) as HTMLButtonElement;
    expect(terminateBtn.disabled).toBe(true);
    expect(terminateBtn.getAttribute('title')).toContain('仅 BPM 管理员可终止');

    fireEvent.click(terminateBtn);
    expect(screen.queryByTestId('terminate-dialog-stub')).toBeNull();
    expect(terminateProcessSpy).not.toHaveBeenCalled();
  });
});
