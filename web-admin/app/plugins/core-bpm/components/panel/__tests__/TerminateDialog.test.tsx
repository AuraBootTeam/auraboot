/**
 * TerminateDialog.test.tsx
 *
 * Unit tests for the Fix B destructive terminate dialog:
 *   - submit stays disabled until BOTH reason non-blank AND confirm checkbox
 *     checked (hazardous-action red line: double confirmation).
 *   - clicking submit forwards the trimmed reason to {@code onConfirm}.
 *   - clicking cancel forwards to {@code onCancel} and never calls
 *     {@code onConfirm}.
 */

import React from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';

import { TerminateDialog } from '../TerminateDialog';

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

const t = (_key: string, _params?: Record<string, unknown>, fallback?: string): string =>
  fallback ?? _key;

describe('TerminateDialog', () => {
  it('keeps submit disabled when reason is blank or confirm checkbox unchecked', () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    const onCancel = vi.fn();

    render(
      <TerminateDialog
        open
        processInstanceId="pi-999"
        onConfirm={onConfirm}
        onCancel={onCancel}
        t={t}
      />,
    );

    const submit = screen.getByTestId('bpm-terminate-submit') as HTMLButtonElement;
    const reason = screen.getByTestId('bpm-terminate-reason') as HTMLTextAreaElement;
    const checkbox = screen.getByTestId(
      'bpm-terminate-confirm-checkbox',
    ) as HTMLInputElement;

    // Initial: both empty/unchecked → disabled.
    expect(submit.disabled).toBe(true);

    // Reason only → still disabled (need checkbox).
    fireEvent.change(reason, { target: { value: '违规操作' } });
    expect(submit.disabled).toBe(true);

    // Clear reason, tick checkbox only → still disabled (need reason).
    fireEvent.change(reason, { target: { value: '' } });
    fireEvent.click(checkbox);
    expect(submit.disabled).toBe(true);

    // Whitespace-only reason (trimmed blank) + checkbox → still disabled.
    fireEvent.change(reason, { target: { value: '   ' } });
    expect(submit.disabled).toBe(true);

    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('enables submit and forwards trimmed reason when both conditions met', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    const onCancel = vi.fn();

    render(
      <TerminateDialog
        open
        processInstanceId="pi-999"
        onConfirm={onConfirm}
        onCancel={onCancel}
        t={t}
      />,
    );

    const reason = screen.getByTestId('bpm-terminate-reason');
    const checkbox = screen.getByTestId('bpm-terminate-confirm-checkbox');

    fireEvent.change(reason, { target: { value: '  违规操作  ' } });
    fireEvent.click(checkbox);

    const submit = screen.getByTestId('bpm-terminate-submit') as HTMLButtonElement;
    expect(submit.disabled).toBe(false);

    await act(async () => {
      fireEvent.click(submit);
    });

    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith('违规操作');
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('invokes onCancel on cancel click without calling onConfirm', () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    const onCancel = vi.fn();

    render(
      <TerminateDialog
        open
        processInstanceId="pi-999"
        onConfirm={onConfirm}
        onCancel={onCancel}
        t={t}
      />,
    );

    fireEvent.click(screen.getByTestId('bpm-terminate-cancel'));

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
