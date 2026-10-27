import { useEffect, useRef, useState } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useModalFocusTrap } from '../useModalFocusTrap';
function Harness() {
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null),
    input = useRef<HTMLInputElement>(null);
  useModalFocusTrap(open, container, () => setOpen(false), input);
  return (
    <>
      <button onClick={() => setOpen(true)}>Open</button>
      {open && (
        <div ref={container} role="dialog" aria-label="Test">
          <button onClick={() => setOpen(false)}>×</button>
          <input ref={input} aria-label="Reason" />
          <button>Last</button>
        </div>
      )}
    </>
  );
}
describe('shared modal initial focus', () => {
  it('does not override a command restoring its enabled opener before the cleanup frame', async () => {
    function Prompt({ close }: { close: () => void }) {
      const container = useRef<HTMLDivElement>(null);
      useModalFocusTrap(true, container, close);
      return (
        <div ref={container} role="dialog" aria-label="Prompt">
          <button onClick={close}>Cancel prompt</button>
        </div>
      );
    }
    function Nested() {
      const [open, setOpen] = useState(false);
      const outer = useRef<HTMLDivElement>(null),
        opener = useRef<HTMLButtonElement>(null);
      useModalFocusTrap(true, outer, () => {}, undefined, false);
      useEffect(() => {
        if (!open) queueMicrotask(() => opener.current?.focus());
      }, [open]);
      return (
        <>
          <div ref={outer} role="dialog" aria-label="Detail">
            <button>Detail X</button>
            <button
              ref={opener}
              disabled={open}
              onClick={() => {
                opener.current?.blur();
                setOpen(true);
              }}
            >
              Open prompt
            </button>
          </div>
          {open && <Prompt close={() => setOpen(false)} />}
        </>
      );
    }
    render(<Nested />);
    const opener = screen.getByRole('button', { name: 'Open prompt' });
    opener.focus();
    fireEvent.click(opener);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Cancel prompt' })).toHaveFocus(),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Cancel prompt' }));
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    expect(opener).toHaveFocus();
  });

  it('captures the opener before native inert activation blurs it', async () => {
    render(<Harness />);
    const opener = screen.getByRole('button', { name: 'Open' });
    let inert = false;
    Object.defineProperty(opener, 'inert', {
      configurable: true,
      get: () => inert,
      set: (next: boolean) => {
        inert = next;
        if (next) opener.blur();
      },
    });
    opener.focus();
    fireEvent.click(opener);
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Reason' })).toHaveFocus());
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(opener).toHaveFocus());
  });
  it('focuses the requested input ahead of X, then restores the actual opener', async () => {
    render(<Harness />);
    const opener = screen.getByRole('button', { name: 'Open' });
    opener.focus();
    fireEvent.click(opener);
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Reason' })).toHaveFocus());
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(opener).toHaveFocus());
  });
  it('does not steal a user-selected control when the autofocus frame arrives late', async () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    const last = screen.getByRole('button', { name: 'Last' });
    last.focus();
    await new Promise((resolve) => requestAnimationFrame(resolve));
    expect(last).toHaveFocus();
  });
  it('does not let a closing modal restore focus into the header of its replacement', async () => {
    function Dialog({ id, onReplace }: { id: number; onReplace: () => void }) {
      const container = useRef<HTMLDivElement>(null),
        input = useRef<HTMLInputElement>(null);
      useModalFocusTrap(true, container, onReplace, input);
      return (
        <div ref={container} role="dialog" aria-label={`Dialog ${id}`}>
          <button onClick={onReplace}>Next</button>
          <input ref={input} aria-label={`Reason ${id}`} />
        </div>
      );
    }
    function SequentialDialogs() {
      const [id, setId] = useState(1);
      return <Dialog key={id} id={id} onReplace={() => setId((value) => value + 1)} />;
    }
    render(<SequentialDialogs />);
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Reason 1' })).toHaveFocus());
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Reason 2' })).toHaveFocus());
  });
});
