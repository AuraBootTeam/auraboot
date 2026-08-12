import { useRef, useState } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useModalFocusTrap } from '../useModalFocusTrap';

function Harness() {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useModalFocusTrap(open, ref, () => setOpen(false));
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        打开
      </button>
      {open ? (
        <div ref={ref} role="dialog" aria-label="测试对话框">
          <button type="button">首项</button>
          <button type="button" onClick={() => setOpen(false)}>
            尾项
          </button>
        </div>
      ) : null}
    </>
  );
}

function NestedHarness() {
  const [parentOpen, setParentOpen] = useState(false);
  const [childOpen, setChildOpen] = useState(false);
  const parentRef = useRef<HTMLDivElement>(null);
  const childRef = useRef<HTMLDivElement>(null);
  useModalFocusTrap(parentOpen, parentRef, () => setParentOpen(false));
  useModalFocusTrap(childOpen, childRef, () => setChildOpen(false));
  return (
    <>
      <button type="button" onClick={() => setParentOpen(true)}>
        打开父层
      </button>
      {parentOpen ? (
        <div ref={parentRef} role="dialog" aria-label="父层">
          <button type="button" onClick={() => setChildOpen(true)}>
            打开子层
          </button>
        </div>
      ) : null}
      {childOpen ? (
        <div ref={childRef} role="dialog" aria-label="子层">
          <button type="button">子层操作</button>
        </div>
      ) : null}
    </>
  );
}

describe('useModalFocusTrap', () => {
  it('focuses the modal, wraps Tab in both directions, closes on Escape and restores focus', async () => {
    render(<Harness />);
    const trigger = screen.getByRole('button', { name: '打开' });
    trigger.focus();
    fireEvent.click(trigger);

    const first = screen.getByRole('button', { name: '首项' });
    const last = screen.getByRole('button', { name: '尾项' });
    await waitFor(() => expect(first).toHaveFocus());
    expect(trigger).toHaveProperty('inert', true);

    last.focus();
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(first).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(last).toHaveFocus();

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(trigger).toHaveFocus());
    expect(trigger.inert).not.toBe(true);
  });

  it('lets only the topmost nested modal handle Escape and restores focus to its trigger', async () => {
    render(<NestedHarness />);
    fireEvent.click(screen.getByRole('button', { name: '打开父层' }));
    const childTrigger = screen.getByRole('button', { name: '打开子层' });
    await waitFor(() => expect(childTrigger).toHaveFocus());
    fireEvent.click(childTrigger);

    const childAction = screen.getByRole('button', { name: '子层操作' });
    await waitFor(() => expect(childAction).toHaveFocus());
    expect(screen.getByRole('dialog', { name: '父层', hidden: true })).toHaveProperty(
      'inert',
      true,
    );
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('dialog', { name: '子层' })).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: '父层' })).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: '父层' }).inert).not.toBe(true);
    await waitFor(() => expect(childTrigger).toHaveFocus());
  });
});
