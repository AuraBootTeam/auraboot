import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DetailActionButtons } from '../DetailActionButtons';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
describe('native detail actions shared by header and modal footer', () => {
  it('keeps permission/visibility/busy gates and dispatches the original record exactly once', () => {
    const buttons = [
      {
        code: 'close',
        label: 'Close',
        primary: true,
        permissionCodes: ['read', 'close'],
        action: { type: 'command', command: 'case:close' },
      },
      { code: 'denied', label: 'Denied', permissionCodes: ['read', 'admin'] },
      { code: 'hidden', label: 'Hidden', visibleWhen: 'hide' },
      { code: 'blocked', label: 'Blocked', disableWhen: 'blocked' },
    ] as ButtonConfig[];
    const record = { pid: 'case-A', row_version: 7 },
      handleAction = vi.fn().mockResolvedValue(undefined);
    const props = {
      buttons,
      modelCode: 'case',
      recordData: record,
      hasPermission: (permission: string) => permission !== 'admin',
      evaluateVisibleWhen: (expression: string | undefined) => expression !== 'hide',
      actionLoading: false,
      resolveButtonLabel: (button: ButtonConfig) => String(button.label),
      handleAction,
      locale: 'en',
      t: (key: string) => key,
    };
    const view = render(<DetailActionButtons {...props} />);
    expect(screen.queryByRole('button', { name: 'Denied' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hidden' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Blocked' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(handleAction).toHaveBeenCalledExactlyOnceWith(buttons[0], record);
    view.rerender(<DetailActionButtons {...props} actionLoading />);
    expect(screen.getByRole('button', { name: 'Close' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(handleAction).toHaveBeenCalledOnce();
  });
});

it('opens footer overflow upward while keeping the header default and original command record', () => {
  const record = { pid: 'case-A' },
    handleAction = vi.fn().mockResolvedValue(undefined);
  const buttons: Array<ButtonConfig & { overflow?: boolean }> = [
    { code: 'reopen', label: 'Reopen', overflow: true },
  ];
  const props = {
    buttons,
    modelCode: 'case',
    recordData: record,
    hasPermission: () => true,
    evaluateVisibleWhen: () => true,
    actionLoading: false,
    resolveButtonLabel: () => 'Reopen',
    handleAction,
    locale: 'en',
    t: (key: string) => key,
  };
  const view = render(<DetailActionButtons {...props} placement="footer" />);
  expect(screen.getByTestId('detail-toolbar-more-menu')).toHaveClass('bottom-full', 'mb-2');
  expect(screen.getByTestId('detail-toolbar-more-menu')).not.toHaveClass('top-10');
  fireEvent.click(screen.getByTestId('detail-toolbar-more').querySelector('summary')!);
  fireEvent.click(screen.getByRole('button', { name: 'Reopen' }));
  expect(handleAction).toHaveBeenCalledExactlyOnceWith(buttons[0], record);
  view.rerender(<DetailActionButtons {...props} />);
  expect(screen.getByTestId('detail-toolbar-more-menu')).toHaveClass('top-10');
});
