import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import RoleFormDialog from '../RoleFormDialog';
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (key: string, _vars: unknown, fallback: string) => fallback ?? key }),
}));
describe('business-facing role creation', () => {
  it('creates an ordinary role from a name without requiring a technical identifier', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<RoleFormDialog open onOpenChange={vi.fn()} role={null} onSave={onSave} />);
    expect(screen.getByTestId('role-form-code')).not.toBeVisible();
    fireEvent.change(screen.getByTestId('role-form-name'), { target: { value: 'BOM engineer' } });
    fireEvent.click(screen.getByTestId('role-form-submit'));
    await waitFor(() => expect(onSave).toHaveBeenCalledOnce());
    expect(onSave.mock.calls[0][0]).toMatchObject({ name: 'BOM engineer', type: 'custom' });
    expect(onSave.mock.calls[0][0].code).toMatch(/^custom_[a-f0-9]{32}$/);
  });
  it('leaves a cancelled role uncreated', () => {
    const onSave = vi.fn(),
      close = vi.fn();
    render(<RoleFormDialog open onOpenChange={close} role={null} onSave={onSave} />);
    fireEvent.change(screen.getByTestId('role-form-name'), { target: { value: 'Draft role' } });
    fireEvent.click(screen.getByTestId('role-form-cancel'));
    expect(close).toHaveBeenCalledWith(false);
    expect(onSave).not.toHaveBeenCalled();
  });
});
