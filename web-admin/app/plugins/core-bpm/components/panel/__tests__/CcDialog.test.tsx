/**
 * CcDialog.test.tsx
 *
 * Unit tests for Task 13's carbon-copy dialog:
 *   - receivers input is required (empty list disables confirm);
 *   - comment input is required;
 *   - onConfirm receives (receiverUserIds, trimmed comment) verbatim;
 *   - backend errors surface inline without closing the dialog.
 *
 * We stub {@link MemberPicker} with a minimal controlled shim so the test
 * can simulate adding / clearing receivers without depending on the live
 * /api/users/search endpoint. The stub exposes
 * {@code data-testid="member-picker-stub"} and invokes
 * {@code props.onChange(['u1'])} when clicked.
 */

import React from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';

// Stub MemberPicker BEFORE importing CcDialog so the picker is intercepted.
vi.mock('~/ui/smart/picker/MemberPicker', () => {
  return {
    MemberPicker: ({
      value,
      onChange,
      placeholder,
    }: {
      value?: string | string[];
      onChange?: (val: string | string[] | undefined) => void;
      placeholder?: string;
    }) => {
      const current = Array.isArray(value) ? value : value ? [value] : [];
      return (
        <div>
          <button
            type="button"
            data-testid="member-picker-add"
            onClick={() => onChange?.([...current, `u-${current.length + 1}`])}
          >
            add user
          </button>
          <button
            type="button"
            data-testid="member-picker-clear"
            onClick={() => onChange?.([])}
          >
            clear
          </button>
          <div data-testid="member-picker-selected">{current.join(',')}</div>
          <div data-testid="member-picker-placeholder">{placeholder}</div>
        </div>
      );
    },
  };
});

import { CcDialog } from '../CcDialog';

afterEach(() => {
  vi.restoreAllMocks();
});

const t = (_key: string, _params?: Record<string, unknown>, fallback?: string): string =>
  fallback ?? _key;

describe('CcDialog', () => {
  it('disables confirm until at least one receiver AND a comment are provided', () => {
    render(
      <CcDialog
        open
        taskId="task-1"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
        t={t}
      />,
    );

    const confirm = screen.getByTestId('bpm-cc-confirm') as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);

    // Only receivers - still disabled (comment missing).
    fireEvent.click(screen.getByTestId('member-picker-add'));
    expect(confirm.disabled).toBe(true);

    // Now add comment too - enabled.
    fireEvent.change(screen.getByTestId('bpm-cc-comment'), {
      target: { value: 'fyi' },
    });
    expect(confirm.disabled).toBe(false);

    // Clear comment → disabled again.
    fireEvent.change(screen.getByTestId('bpm-cc-comment'), {
      target: { value: '   ' },
    });
    expect(confirm.disabled).toBe(true);
  });

  it('invokes onConfirm with selected receivers and trimmed comment', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    render(
      <CcDialog
        open
        taskId="task-1"
        onConfirm={onConfirm}
        onCancel={vi.fn()}
        t={t}
      />,
    );

    fireEvent.click(screen.getByTestId('member-picker-add'));
    fireEvent.click(screen.getByTestId('member-picker-add'));
    fireEvent.change(screen.getByTestId('bpm-cc-comment'), {
      target: { value: '  请关注  ' },
    });

    await act(async () => {
      fireEvent.click(screen.getByTestId('bpm-cc-confirm'));
    });

    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith(['u-1', 'u-2'], '请关注');
  });

  it('surfaces backend errors inline without closing the dialog', async () => {
    const onConfirm = vi.fn().mockRejectedValue(new Error('CcPolicy violated'));
    const onCancel = vi.fn();
    render(
      <CcDialog
        open
        taskId="task-1"
        onConfirm={onConfirm}
        onCancel={onCancel}
        t={t}
      />,
    );

    fireEvent.click(screen.getByTestId('member-picker-add'));
    fireEvent.change(screen.getByTestId('bpm-cc-comment'), {
      target: { value: 'fyi' },
    });

    await act(async () => {
      fireEvent.click(screen.getByTestId('bpm-cc-confirm'));
    });

    expect(screen.getByTestId('bpm-cc-error')).toHaveTextContent('CcPolicy violated');
    expect(onCancel).not.toHaveBeenCalled();
  });
});
