import { useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { MemoryRouter } from 'react-router';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import FormDialog, { FormDialogProvider } from '../FormDialog';
import { promptInputForm } from '../ActionRegistry';
import { ActionCancelledError } from '../ActionCancelledError';
import { registerFormDialogScope } from '../formDialogLifecycle';
import { useModalFocusTrap } from '~/hooks/useModalFocusTrap';
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'en',
    t: (key: string, _args?: unknown, fallback?: string) => fallback ?? key,
  }),
}));
vi.mock('~/contexts/TimezoneContext', () => ({ useTimezone: () => ({ timezone: 'UTC' }) }));
function Root({ children }: { children?: React.ReactNode }) {
  return (
    <MemoryRouter>
      <FormDialogProvider>
        <FormDialog />
        <FormDialog />
        {children}
      </FormDialogProvider>
    </MemoryRouter>
  );
}
describe('shared FormDialog presentation', () => {
  it('has one host, freezes object/title, deduplicates dictionaries, and submits no summary values', async () => {
    render(<Root />);
    const record = { title: 'Case A', status: 'new' };
    const fetcher = vi
      .fn()
      .mockResolvedValue({ code: '0', data: [{ value: 'new', label: 'New' }] });
    let pending!: Promise<Record<string, unknown>>;
    act(() => {
      pending = promptInputForm(
        [
          { field: 'note', type: 'textarea', label: 'Reason', required: true },
          { field: 'selection', type: 'select', dictCode: 'case_status', defaultValue: 'new' },
        ],
        { en: 'Close · ${record.title}' },
        fetcher,
        'Submit',
        { record },
        { locale: 'en' },
        {
          width: 600,
          showClose: true,
          summary: {
            columns: 2,
            fields: [
              { field: 'title', label: 'Event' },
              { field: 'status', dictCode: 'case_status' },
            ],
          },
        },
      );
    });
    record.title = 'Case B';
    record.status = 'closed';
    await screen.findByRole('dialog', { name: 'Close · Case A' });
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    expect(screen.getByTestId('form-dialog-summary')).toHaveTextContent('Case A');
    expect(screen.getByTestId('form-dialog-summary')).toHaveTextContent('New');
    expect(screen.getByTestId('form-dialog-summary')).toHaveClass('grid-cols-2');
    expect(screen.getByTestId('form-dialog-summary')).not.toHaveClass(
      'sm:grid-cols-2',
      'grid-cols-1',
    );
    expect(fetcher).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(screen.getByRole('textbox', { name: 'Reason' })).toHaveFocus();
    fireEvent.change(screen.getByRole('textbox', { name: 'Reason' }), {
      target: { value: 'Verified' },
    });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await expect(pending).resolves.toEqual({ note: 'Verified', selection: 'new' });
  });
  it('initially focuses a segmented business choice ahead of the header X', async () => {
    render(<Root />);
    act(() =>
      window.dispatchEvent(
        new CustomEvent('dialog:form', {
          detail: {
            title: 'Choose',
            fields: [{ field: 'resolution', type: 'segmented', label: 'Conclusion' }],
            fieldOptions: { resolution: [{ value: 'confirmed', label: 'Confirmed' }] },
            presentation: { width: 600, showClose: true },
          },
        }),
      ),
    );
    await waitFor(() =>
      expect(screen.getByRole('radio', { name: 'Confirmed', exact: true })).toHaveFocus(),
    );
  });
  it('X cancels exactly once and sends no submitted fields', async () => {
    render(<Root />);
    const cancel = vi.fn(),
      submit = vi.fn();
    act(() =>
      window.dispatchEvent(
        new CustomEvent('dialog:form', {
          detail: {
            title: 'Cancel',
            fields: [],
            presentation: { width: 600, showClose: true },
            onCancel: cancel,
            onSubmit: submit,
          },
        }),
      ),
    );
    fireEvent.click(screen.getByTestId('form-dialog-close'));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(cancel).toHaveBeenCalledOnce();
    expect(submit).not.toHaveBeenCalled();
  });
  it('does not open a stale prompt after its modal was disposed during async choices loading', async () => {
    render(<Root />);
    const controller = new AbortController();
    const remove = registerFormDialogScope(controller.signal, 1);
    let release!: (value: unknown) => void;
    const fetcher = vi.fn(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );
    const pending = promptInputForm([{ field: 'choice', dictCode: 'choices' }], 'Stale', fetcher);
    const rejected = expect(pending).rejects.toBeInstanceOf(ActionCancelledError);
    controller.abort();
    remove();
    await act(async () => {
      release({ code: '0', data: [] });
      await rejected;
    });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
  it('cancels an already-open direct registry prompt when the modal scope aborts', async () => {
    render(<Root />);
    const controller = new AbortController(),
      remove = registerFormDialogScope(controller.signal, 1);
    const pending = promptInputForm([{ field: 'note' }], 'Open prompt');
    const rejected = expect(pending).rejects.toBeInstanceOf(ActionCancelledError);
    await screen.findByRole('dialog', { name: 'Open prompt' });
    await act(async () => {
      controller.abort();
      remove();
      await rejected;
    });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
function Nested() {
  const [open, setOpen] = useState(false),
    ref = useRef<HTMLDivElement>(null);
  useModalFocusTrap(open, ref, () => setOpen(false));
  return (
    <>
      <button onClick={() => setOpen(true)}>Open detail</button>
      {open &&
        createPortal(
          <div ref={ref} role="dialog" aria-label="Detail">
            <FormDialog />
            <button
              onClick={() => {
                void promptInputForm(
                  [{ field: 'reason', label: 'Reason' }],
                  'Close case',
                  undefined,
                  undefined,
                  {},
                  { locale: 'en' },
                  { width: 600, showClose: true },
                ).catch(() => {});
              }}
            >
              Close case
            </button>
          </div>,
          document.body,
        )}
    </>
  );
}
it('nested Escape dismisses the form first and restores footer then source focus', async () => {
  render(
    <Root>
      <Nested />
    </Root>,
  );
  const opener = screen.getByRole('button', { name: 'Open detail' });
  opener.focus();
  fireEvent.click(opener);
  const trigger = screen.getByRole('button', { name: 'Close case' });
  await waitFor(() => expect(trigger).toHaveFocus());
  fireEvent.click(trigger);
  await waitFor(() => expect(screen.getByRole('textbox', { name: 'Reason' })).toHaveFocus());
  fireEvent.keyDown(document, { key: 'Escape' });
  await waitFor(() => expect(trigger).toHaveFocus());
  expect(screen.getByRole('dialog', { name: 'Detail' })).toBeInTheDocument();
  fireEvent.keyDown(document, { key: 'Escape' });
  await waitFor(() => expect(opener).toHaveFocus());
});

it('retains an opt-in prompt on backdrop click, clamps its panel, and keeps explicit cancellation', async () => {
  render(<Root />);
  let pending!: Promise<Record<string, unknown>>;
  act(() => {
    pending = promptInputForm(
      [{ field: 'note', label: 'Reason' }],
      'Retained prompt',
      undefined,
      undefined,
      {},
      { locale: 'en' },
      { width: 600, showClose: true, closeOnBackdrop: false },
    );
  });
  const rejected = expect(pending).rejects.toBeInstanceOf(ActionCancelledError);
  await screen.findByRole('dialog', { name: 'Retained prompt' });
  expect(screen.getByTestId('form-dialog')).toHaveClass('aura-dialog-presentation');
  expect(screen.getByTestId('form-dialog-panel')).toHaveClass('aura-dialog-presentation__panel');
  expect(screen.getByTestId('form-dialog-panel').style.getPropertyValue('--dialog-width')).toBe(
    '600px',
  );
  expect(screen.getByTestId('form-dialog-backdrop')).toHaveClass(
    'aura-dialog-presentation__backdrop',
  );
  expect(screen.getByTestId('form-dialog-body')).toHaveClass('aura-dialog-presentation__body');
  fireEvent.click(screen.getByTestId('form-dialog-backdrop'));
  expect(screen.getByRole('dialog', { name: 'Retained prompt' })).toBeInTheDocument();
  fireEvent.click(screen.getByTestId('form-dialog-close'));
  await rejected;
});
it('preserves backdrop cancellation for prompts that omit the new option', async () => {
  render(<Root />);
  const cancel = vi.fn();
  act(() =>
    window.dispatchEvent(
      new CustomEvent('dialog:form', {
        detail: { title: 'Legacy prompt', fields: [], onCancel: cancel },
      }),
    ),
  );
  expect(screen.getByTestId('form-dialog')).not.toHaveClass('aura-dialog-presentation');
  expect(screen.getByTestId('form-dialog-panel')).not.toHaveClass(
    'aura-dialog-presentation__panel',
  );
  expect(screen.getByTestId('form-dialog-panel')).toHaveClass('mx-4', 'max-h-[calc(100vh-2rem)]');
  expect(screen.getByTestId('form-dialog-panel')).not.toHaveAttribute('style');
  expect(screen.getByTestId('form-dialog-backdrop')).toHaveClass('bg-black/50');
  fireEvent.click(screen.getByTestId('form-dialog-backdrop'));
  expect(cancel).toHaveBeenCalledOnce();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
