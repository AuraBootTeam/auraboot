/**
 * SaveDialog backdrop-click regression test.
 *
 * Confirms that clicks inside the dialog (inputs, buttons) do NOT
 * dismiss it, and that only a direct click on the backdrop triggers
 * onClose. Previously the outer `onClick={onClose}` could fire on any
 * descendant click in environments where stopPropagation was partially
 * observed (nested portals, event-delegation wrappers); the
 * `e.target === e.currentTarget` guard on the backdrop removes any
 * ambiguity.
 */

import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

import { SaveDialog } from '~/plugins/core-designer/components/bpmn-designer/components/SaveDialog';

describe('SaveDialog backdrop click', () => {
  // Project vitest config sets isolate=false; explicit cleanup prevents the
  // previous render's DOM from leaking into the next test.
  afterEach(() => {
    cleanup();
  });

  const baseProps = {
    isOpen: true,
    initialData: { name: 'P', key: 'p' },
    isNew: true,
  };

  it('closes on direct backdrop click', () => {
    const onClose = vi.fn();
    render(<SaveDialog {...baseProps} onClose={onClose} onSave={vi.fn()} />);

    const backdrop = screen.getByTestId('bpmn-save-dialog-backdrop');
    fireEvent.click(backdrop);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('does NOT close when clicking the inner panel', () => {
    const onClose = vi.fn();
    render(<SaveDialog {...baseProps} onClose={onClose} onSave={vi.fn()} />);

    const panel = screen.getByTestId('bpmn-save-dialog-panel');
    fireEvent.click(panel);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('does NOT close when clicking inputs / form fields', () => {
    const onClose = vi.fn();
    render(<SaveDialog {...baseProps} onClose={onClose} onSave={vi.fn()} />);

    const nameInput = screen.getByPlaceholderText('例如: 员工请假审批流程');
    fireEvent.click(nameInput);
    const keyInput = screen.getByPlaceholderText('例如: leave_approval_process');
    fireEvent.click(keyInput);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('does NOT close when clicking the cancel button inside the panel (it closes via its own handler, but that is one call)', () => {
    // The explicit cancel button calls onClose directly; we just assert a
    // single invocation (not a second bubbled one from the backdrop).
    const onClose = vi.fn();
    render(<SaveDialog {...baseProps} onClose={onClose} onSave={vi.fn()} />);

    fireEvent.click(screen.getByText('取消'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
