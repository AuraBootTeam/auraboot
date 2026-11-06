import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import NavigationRoleAssignment from '../NavigationRoleAssignment';
const state = vi.hoisted(() => ({ entryRoles: vi.fn(), saveEntryRoles: vi.fn() }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (key: string, _args: unknown, fallback: string) => fallback ?? key }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn() }),
}));
vi.mock('~/routes/enterprise/permission/capability/roleNavigationService', () => ({
  roleNavigationService: state,
}));
describe('visible role search', () => {
  it('preserves selected roles hidden by search and saves the complete selection', async () => {
    const snapshot = {
      revision: 'revision',
      roles: [
        {
          pid: 'engineering',
          name: 'Engineering',
          selected: true,
          enabled: true,
          mode: 'explicit',
        },
        { pid: 'sales', name: 'Sales', selected: false, enabled: true, mode: 'explicit' },
      ],
    };
    state.entryRoles.mockResolvedValue(snapshot);
    state.saveEntryRoles.mockResolvedValue(snapshot);
    render(<NavigationRoleAssignment menuPid="menu" disabled={false} />);
    await waitFor(() => expect(screen.getByTestId('navigation-roles-configure')).toBeEnabled());
    fireEvent.click(screen.getByTestId('navigation-roles-configure'));
    fireEvent.change(screen.getByTestId('navigation-role-search'), { target: { value: 'Sales' } });
    expect(screen.queryByTestId('navigation-role-engineering')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('navigation-role-sales'));
    fireEvent.change(screen.getByTestId('navigation-role-search'), {
      target: { value: 'No such role' },
    });
    expect(screen.getByText('No matching roles')).toBeVisible();
    fireEvent.change(screen.getByTestId('navigation-role-search'), { target: { value: '' } });
    expect(screen.getByTestId('navigation-role-engineering')).toBeChecked();
    expect(screen.getByTestId('navigation-role-sales')).toBeChecked();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(state.saveEntryRoles).toHaveBeenCalledExactlyOnceWith('menu', 'revision', [
        'engineering',
        'sales',
      ]),
    );
  });
});
