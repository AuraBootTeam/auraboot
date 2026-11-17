import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import DataScopeBar from '../DataScopeBar';
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string, _: unknown, f: string) => f ?? k }),
}));
function fixture(disabled = false) {
  const onStage = vi.fn();
  const onOpenChange = vi.fn();
  render(
    <DataScopeBar
      matrix={{ modules: [] }}
      defaultScope="self"
      applyToCurrent={false}
      onStage={onStage}
      onOpenChange={onOpenChange}
      disabled={disabled}
    />,
  );
  return { onStage, onOpenChange };
}
describe('DataScopeBar draft authoring', () => {
  it('stages a default and optional current-grant update without writing', () => {
    const { onStage } = fixture();
    fireEvent.click(screen.getByTestId('data-scope-modify-btn'));
    fireEvent.click(screen.getByTestId('data-scope-option-dept').querySelector('input')!);
    fireEvent.click(screen.getByTestId('data-scope-apply-current'));
    expect(onStage).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('data-scope-apply'));
    expect(onStage).toHaveBeenCalledWith('dept', true);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
  it('Escape cancels pending changes and restores focus', () => {
    const { onStage } = fixture();
    const button = screen.getByTestId('data-scope-modify-btn');
    button.focus();
    fireEvent.click(button);
    fireEvent.click(screen.getByTestId('data-scope-option-all').querySelector('input')!);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onStage).not.toHaveBeenCalled();
    expect(button).toHaveFocus();
  });
  it('reopening after cancel uses the parent draft', () => {
    fixture();
    fireEvent.click(screen.getByTestId('data-scope-modify-btn'));
    fireEvent.click(screen.getByTestId('data-scope-option-all').querySelector('input')!);
    fireEvent.click(screen.getByTestId('data-scope-drawer-close'));
    fireEvent.click(screen.getByTestId('data-scope-modify-btn'));
    expect(screen.getByTestId('data-scope-option-self').querySelector('input')).toBeChecked();
  });
  it('read-only configuration prevents editing', () => {
    const { onStage } = fixture(true);
    const button = screen.getByTestId('data-scope-modify-btn');
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(onStage).not.toHaveBeenCalled();
  });
});
