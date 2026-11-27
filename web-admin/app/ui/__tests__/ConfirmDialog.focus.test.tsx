import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ConfirmDialog from '../ConfirmDialog';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}));

afterEach(() => document.querySelector('[data-focus-trigger]')?.remove());

describe('ConfirmDialog focus lifecycle', () => {
  it('returns focus to the connected trigger when the dialog closes', () => {
    const trigger = document.createElement('button');
    trigger.dataset.focusTrigger = 'true';
    document.body.append(trigger);
    trigger.focus();
    const props = {
      title: 'Confirm purchase',
      content: 'Reserve the reviewed quantities?',
      onConfirm: vi.fn(),
      onCancel: vi.fn(),
    };
    const { rerender } = render(<ConfirmDialog {...props} open />);
    expect(document.activeElement).toBe(screen.getByTestId('confirm-ok'));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(props.onCancel).toHaveBeenCalledOnce();
    rerender(<ConfirmDialog {...props} open={false} />);
    expect(document.activeElement).toBe(trigger);
  });

  it('preserves the focused dialog action when callbacks change while open', () => {
    const props = {
      title: 'Confirm purchase',
      content: 'Reserve the reviewed quantities?',
      onConfirm: vi.fn(),
      onCancel: vi.fn(),
    };
    const { rerender } = render(<ConfirmDialog {...props} open />);
    const cancel = screen.getByTestId('confirm-cancel');
    cancel.focus();
    const nextCancel = vi.fn();
    rerender(<ConfirmDialog {...props} open onCancel={nextCancel} />);
    expect(document.activeElement).toBe(cancel);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(nextCancel).toHaveBeenCalledOnce();
    expect(props.onCancel).not.toHaveBeenCalled();
  });
  it('owns pointer input while another modal has disabled outside interactions', () => {
    const onConfirm = vi.fn();
    const outsidePointerDown = vi.fn();
    document.addEventListener('pointerdown', outsidePointerDown);
    try {
      render(
        <ConfirmDialog
          open
          title="Confirm purchase"
          content="Reserve the reviewed quantities?"
          onConfirm={onConfirm}
          onCancel={vi.fn()}
        />,
      );
      expect(screen.getByTestId('confirm-dialog')).toHaveClass('pointer-events-auto');
      const confirm = screen.getByTestId('confirm-ok');
      fireEvent.pointerDown(confirm);
      fireEvent.click(confirm);
      expect(outsidePointerDown).not.toHaveBeenCalled();
      expect(onConfirm).toHaveBeenCalledOnce();
    } finally {
      document.removeEventListener('pointerdown', outsidePointerDown);
    }
  });
  it('blocks duplicate confirmation and cancellation while an atomic save is pending', () => {
    const onConfirm = vi.fn(),
      onCancel = vi.fn();
    render(
      <ConfirmDialog
        open
        title="Saving"
        content="Saving reviewed navigation"
        confirmDisabled
        cancelDisabled
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    );
    expect(screen.getByTestId('confirm-ok')).toBeDisabled();
    expect(screen.getByTestId('confirm-cancel')).toBeDisabled();
    fireEvent.click(screen.getByTestId('confirm-ok'));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onConfirm).not.toHaveBeenCalled();
    expect(onCancel).not.toHaveBeenCalled();
  });
});
