/**
 * TaskDetailDrawer FormTab regression test.
 *
 * Pins the rendering decision purely on `formBinding != null` (the corrected
 * backend contract). Before the fix the FormTab keyed off `hasForm` and
 * `forms[]` which never appeared on the wire, so this guards the path that
 * caused G3 to fall back to "该任务未绑定表单" indefinitely.
 *
 * useDslForm + DslFormRenderer are mocked because their full pipeline pulls
 * in the page renderer registry which is exercised by E2E. We only assert
 * which branch FormTab takes and that the right pageKey is forwarded.
 */

import React from 'react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

// ---- Mocks ----
const useDslFormMock = vi.fn();
vi.mock('~/framework/meta/hooks/useDslForm', () => ({
  useDslForm: (opts: unknown) => useDslFormMock(opts),
}));

vi.mock('~/framework/meta/rendering/DslFormRenderer', () => ({
  DslFormRenderer: (props: { className?: string }) => (
    <div data-testid="mock-dsl-form-renderer" className={props.className} />
  ),
}));

const getTaskFormMock = vi.fn();
const submitTaskFormMock = vi.fn();
vi.mock('~/plugins/core-bpm/services/bpmFormService', () => ({
  getTaskForm: (id: string) => getTaskFormMock(id),
  submitTaskForm: (id: string, data: unknown) => submitTaskFormMock(id, data),
}));

vi.mock('~/plugins/core-bpm/services/slaService', () => ({
  getSlaByInstance: vi.fn().mockResolvedValue([]),
}));

vi.mock('react-router', () => ({
  useNavigate: () => vi.fn(),
}));

vi.mock('~/plugins/core-bpm/components/ApprovalTimeline', () => ({
  ApprovalTimeline: () => <div data-testid="mock-approval-timeline" />,
}));
vi.mock('~/plugins/core-bpm/components/AttachmentPanel', () => ({
  AttachmentPanel: () => <div data-testid="mock-attachment-panel" />,
}));

import { TaskDetailDrawer } from '~/plugins/core-bpm/components/TaskDetailDrawer';
import type { TaskInstance } from '~/plugins/core-bpm/services/bpmWorkbenchService';

function task(overrides: Partial<TaskInstance> = {}): TaskInstance {
  return {
    instanceId: 'i-1',
    taskId: 't-1',
    processInstanceId: 'pi-1',
    processDefinitionKey: 'wd_leave_approval',
    taskDefinitionKey: 'task_manager_approve',
    taskName: 'Manager approval',
    assignee: '',
    claimUserId: '',
    createTime: '2026-04-17T10:00:00Z',
    priority: 50,
    businessKey: 'WDLR-1',
    ...overrides,
  };
}

describe('TaskDetailDrawer FormTab', () => {
  beforeEach(() => {
    useDslFormMock.mockReset();
    useDslFormMock.mockReturnValue({
      loading: false,
      error: null,
      schema: { kind: 'form' },
      enabled: true,
      submitting: false,
      rendererProps: {},
      submit: vi.fn(),
      setFieldValue: vi.fn(),
    });
    getTaskFormMock.mockReset();
    submitTaskFormMock.mockReset();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('renders the DSL form when backend returns a non-null formBinding', async () => {
    getTaskFormMock.mockResolvedValue({
      taskId: 't-1',
      formBinding: {
        formRef: 'wd_leave_request_detail',
        formType: 'PAGE',
        saveStrategy: 'business_only',
        fieldPermissions: { wd_req_reason: 'readonly' },
      },
      processVariables: { decision: null },
      businessKey: 'WDLR-1',
      taskActions: null,
    });

    render(
      <TaskDetailDrawer
        task={task()}
        onClose={vi.fn()}
        onOpenDialog={vi.fn()}
        onClaim={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /表单/ }));

    await waitFor(() => expect(getTaskFormMock).toHaveBeenCalledWith('t-1'));
    await screen.findByTestId('form-tab-content');
    expect(screen.getByTestId('mock-dsl-form-renderer')).toBeInTheDocument();
    // The hook must receive the formRef from the binding (regression pin).
    const lastCall = useDslFormMock.mock.calls.at(-1)?.[0] as Record<string, unknown>;
    expect(lastCall?.pageKey).toBe('wd_leave_request_detail');
    expect(lastCall?.enabled).toBe(true);
    expect(lastCall?.recordId).toBe('WDLR-1');
    expect(lastCall?.fieldPermissions).toEqual({ wd_req_reason: 'readonly' });
  });

  it('shows the empty state when backend returns formBinding=null', async () => {
    getTaskFormMock.mockResolvedValue({
      taskId: 't-1',
      formBinding: null,
      taskActions: null,
    });

    render(
      <TaskDetailDrawer
        task={task()}
        onClose={vi.fn()}
        onOpenDialog={vi.fn()}
        onClaim={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /表单/ }));

    await waitFor(() => expect(getTaskFormMock).toHaveBeenCalledWith('t-1'));
    await screen.findByTestId('form-tab-empty');
    expect(screen.getByText('该任务未绑定表单')).toBeInTheDocument();
    expect(screen.queryByTestId('mock-dsl-form-renderer')).toBeNull();
  });
});
