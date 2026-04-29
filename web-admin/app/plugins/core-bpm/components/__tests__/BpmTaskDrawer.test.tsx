import React from 'react';
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

const getSpy = vi.fn();
const postSpy = vi.fn();

vi.mock('~/shared/services/http-client', () => ({
  get: (...args: unknown[]) => getSpy(...args),
  post: (...args: unknown[]) => postSpy(...args),
}));

vi.mock('~/framework/meta/hooks/useDslForm', () => ({
  useDslForm: () => ({
    submitting: false,
    submit: vi.fn(),
  }),
}));

vi.mock('~/framework/meta/rendering/DslFormRenderer', () => ({
  DslFormRenderer: () => <div data-testid="dsl-form-renderer-stub" />,
}));

import { BpmTaskDrawer } from '../BpmTaskDrawer';

describe('BpmTaskDrawer', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    vi.clearAllMocks();
  });

  beforeEach(() => {
    getSpy.mockResolvedValue({
      success: true,
      data: {
        taskId: 'task-1',
        taskName: 'Approve purchase order',
        processName: 'PO Approval',
        businessKey: 'po-1',
        processVariables: {},
        formBinding: null,
      },
    });
    postSpy.mockResolvedValue({ success: true });
  });

  it('requires a comment before rejecting an approval task', async () => {
    const onClose = vi.fn();
    const onComplete = vi.fn();

    render(
      <BpmTaskDrawer taskId="task-1" open onClose={onClose} onComplete={onComplete} />,
    );

    await screen.findByText('Approve purchase order');
    fireEvent.click(screen.getByTestId('bpm-drawer-reject-btn'));

    expect(await screen.findByTestId('bpm-drawer-action-error')).toHaveTextContent(
      'Rejection comment is required.',
    );
    expect(postSpy).not.toHaveBeenCalled();
    expect(onComplete).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('submits a trimmed rejection comment', async () => {
    const onClose = vi.fn();
    const onComplete = vi.fn();

    render(
      <BpmTaskDrawer taskId="task-1" open onClose={onClose} onComplete={onComplete} />,
    );

    await screen.findByText('Approve purchase order');
    fireEvent.change(screen.getByTestId('approval-comment-textarea'), {
      target: { value: '  Missing invoice attachment  ' },
    });
    fireEvent.click(screen.getByTestId('bpm-drawer-reject-btn'));

    await waitFor(() => expect(postSpy).toHaveBeenCalledTimes(1));
    expect(postSpy).toHaveBeenCalledWith('/api/bpm/forms/task/task-1/submit', {
      saveStrategy: 'variable_only',
      variables: {
        decision: 'reject',
        comment: 'Missing invoice attachment',
      },
    });
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
