/**
 * WithdrawDialog.test.tsx
 *
 * Unit tests for Task 13's withdraw dialog:
 *   - policy copy varies by withdrawPolicy prop;
 *   - none-policy hides the confirm button;
 *   - strict / loose confirm round-trip calls onConfirm with the trimmed
 *     reason string;
 *   - empty reason coalesces to undefined (optional for withdraw);
 *   - errors thrown by onConfirm surface inline without closing the dialog.
 */

import React from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';

import { WithdrawDialog } from '../WithdrawDialog';

afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

const t = (_key: string, _params?: Record<string, unknown>, fallback?: string): string =>
  fallback ?? _key;

describe('WithdrawDialog', () => {
  it('renders the strict policy description when withdrawPolicy=strict', () => {
    render(
      <WithdrawDialog
        open
        taskId="task-1"
        withdrawPolicy="strict"
        onConfirm={vi.fn().mockResolvedValue(undefined)}
        onCancel={vi.fn()}
        t={t}
      />,
    );

    const policy = screen.getByTestId('bpm-withdraw-policy');
    expect(policy).toHaveAttribute('data-policy', 'strict');
    expect(policy).toHaveTextContent('严格模式');
    expect(screen.getByTestId('bpm-withdraw-confirm')).toBeInTheDocument();
  });

  it('hides the confirm button when withdrawPolicy=none', () => {
    render(
      <WithdrawDialog
        open
        taskId="task-1"
        withdrawPolicy="none"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
        t={t}
      />,
    );

    expect(screen.queryByTestId('bpm-withdraw-confirm')).toBeNull();
    expect(screen.getByTestId('bpm-withdraw-policy')).toHaveTextContent('不允许撤回');
  });

  it('forwards the trimmed reason to onConfirm when present', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    render(
      <WithdrawDialog
        open
        taskId="task-1"
        withdrawPolicy="loose"
        onConfirm={onConfirm}
        onCancel={vi.fn()}
        t={t}
      />,
    );

    fireEvent.change(screen.getByTestId('bpm-withdraw-reason'), {
      target: { value: '  发错了  ' },
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId('bpm-withdraw-confirm'));
    });

    expect(onConfirm).toHaveBeenCalledWith('发错了');
  });

  it('passes undefined when reason is blank (reason is optional)', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    render(
      <WithdrawDialog
        open
        taskId="task-1"
        onConfirm={onConfirm}
        onCancel={vi.fn()}
        t={t}
      />,
    );

    await act(async () => {
      fireEvent.click(screen.getByTestId('bpm-withdraw-confirm'));
    });

    expect(onConfirm).toHaveBeenCalledWith(undefined);
  });

  it('surfaces errors inline without closing the dialog', async () => {
    const onConfirm = vi.fn().mockRejectedValue(new Error('backend said no'));
    const onCancel = vi.fn();
    render(
      <WithdrawDialog
        open
        taskId="task-1"
        withdrawPolicy="loose"
        onConfirm={onConfirm}
        onCancel={onCancel}
        t={t}
      />,
    );

    await act(async () => {
      fireEvent.click(screen.getByTestId('bpm-withdraw-confirm'));
    });

    expect(screen.getByTestId('bpm-withdraw-error')).toHaveTextContent('backend said no');
    expect(onCancel).not.toHaveBeenCalled();
  });
});
