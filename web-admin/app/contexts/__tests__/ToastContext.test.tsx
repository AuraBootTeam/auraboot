import { fireEvent, render, screen } from '@testing-library/react';
import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider, useToastContext } from '../ToastContext';

function ToastProbe() {
  const { showInfoToast, showSuccessToast } = useToastContext();

  return (
    <div>
      <button type="button" onClick={() => showInfoToast('采购价格已提交，后台处理中...')}>
        Show info
      </button>
      <button type="button" onClick={() => showSuccessToast('导入完成')}>
        Show success
      </button>
    </div>
  );
}

describe('ToastProvider', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('stacks multiple toasts in one fixed container instead of overlapping them', () => {
    render(
      <ToastProvider>
        <ToastProbe />
      </ToastProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Show info' }));
    fireEvent.click(screen.getByRole('button', { name: 'Show success' }));

    const stack = screen.getByTestId('toast-stack');
    expect(stack).toHaveClass('fixed', 'flex', 'flex-col', 'gap-2.5');
    const alerts = screen.getAllByRole('alert');
    expect(alerts).toHaveLength(2);

    for (const alert of alerts) {
      expect(stack).toContainElement(alert);
      expect(alert).not.toHaveClass('fixed');
    }
  });

  it('treats toast events as short acknowledgements without a countdown bar', () => {
    vi.useFakeTimers();

    render(
      <ToastProvider>
        <div />
      </ToastProvider>,
    );

    act(() => {
      window.dispatchEvent(
        new CustomEvent('aura:toast', {
          detail: {
            message: '已提交，后台处理中',
            variant: 'info',
            duration: 1000,
          },
        }),
      );
    });

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('已提交，后台处理中');
    expect(alert.querySelector('[style*="transition-duration"]')).toBeNull();

    act(() => {
      vi.advanceTimersByTime(999);
    });
    expect(screen.getByRole('alert')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(301);
    });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
