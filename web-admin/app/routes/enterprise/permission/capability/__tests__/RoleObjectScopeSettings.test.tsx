import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import RoleObjectScopeSettings from '../RoleObjectScopeSettings';
import type { PermissionMatrixDTO } from '../../types';
const models = vi.hoisted(() => ({ findByCode: vi.fn() }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (_key: string, _vars: unknown, fallback: string) => fallback }),
}));
vi.mock('~/shared/services/modelService', () => ({ modelService: models }));
const matrix: PermissionMatrixDTO = {
  modules: [
    {
      moduleCode: 'quote',
      moduleName: 'Quote',
      resources: [
        {
          resourceCode: 'quote',
          resourceName: 'Quote',
          actions: [
            {
              permissionId: 1,
              permissionPid: 'r',
              code: 'model.quote.read',
              action: 'read',
              label: 'View',
              granted: true,
              supported: true,
              scopeType: 'self',
            },
            {
              permissionId: 2,
              permissionPid: 'u',
              code: 'model.quote.update',
              action: 'update',
              label: 'Edit',
              granted: true,
              supported: true,
              scopeType: 'none',
            },
            {
              permissionId: 3,
              permissionPid: 'd',
              code: 'model.quote.delete',
              action: 'delete',
              label: 'Delete',
              granted: false,
              supported: true,
              scopeType: 'all',
            },
          ],
        },
        {
          resourceCode: 'inventory',
          resourceName: 'Inventory',
          actions: [
            {
              permissionId: 4,
              permissionPid: 'i',
              code: 'model.inventory.read',
              action: 'read',
              label: 'View',
              granted: true,
              supported: true,
              scopeType: 'all',
            },
          ],
        },
        {
          resourceCode: 'commercial',
          resourceName: 'Commercial',
          actions: [
            {
              permissionId: 5,
              permissionPid: 'b',
              code: 'quote.inspect',
              action: 'read',
              label: 'Inspect',
              granted: true,
              supported: true,
              scopeType: 'self',
            },
          ],
        },
      ],
    },
  ],
};
beforeEach(() => {
  models.findByCode.mockReset().mockResolvedValue({ extension: {} });
});
function show(value = matrix, disabled = false) {
  const onStage = vi.fn();
  render(
    <RoleObjectScopeSettings
      model="quote"
      label="Quote"
      permissionCodes={['quote.inspect']}
      matrix={value}
      disabled={disabled}
      onStage={onStage}
    />,
  );
  return onStage;
}
describe('object record scopes in the shared draft', () => {
  it('preserves mixed scopes on mount and stages only granted actions of the chosen object/action', async () => {
    const onStage = show();
    await waitFor(() => expect(screen.getByTestId('role-object-scope-type')).toBeEnabled());
    expect(screen.getByTestId('role-object-scope-type')).toHaveValue('mixed');
    expect(onStage).not.toHaveBeenCalled();
    fireEvent.change(screen.getByTestId('role-object-scope-action'), { target: { value: 'read' } });
    expect(screen.getByTestId('role-object-scope-type')).toHaveValue('self');
    fireEvent.change(screen.getByTestId('role-object-scope-type'), { target: { value: 'none' } });
    expect(onStage.mock.calls[0][0]).toEqual([
      { resourceCode: 'quote', actionCode: 'read', scopeType: 'none', mergeStrategy: undefined },
      {
        resourceCode: 'commercial',
        actionCode: 'read',
        scopeType: 'none',
        mergeStrategy: undefined,
      },
    ]);
    expect(screen.queryByRole('option', { name: 'Delete' })).not.toBeInTheDocument();
  });
  it('keeps absent scopes unconfigured rather than manufacturing ALL', async () => {
    const missing = structuredClone(matrix);
    missing.modules[0].resources.forEach((resource) =>
      resource.actions.forEach((entry) => {
        entry.scopeType = null;
      }),
    );
    const onStage = show(missing);
    await waitFor(() => expect(screen.getByTestId('role-object-scope-type')).toBeEnabled());
    expect(screen.getByTestId('role-object-scope-type')).toHaveValue('');
    expect(onStage).not.toHaveBeenCalled();
  });
  it('rejects team scope without a declared team ownership field', async () => {
    const onStage = show();
    await waitFor(() => expect(screen.getByTestId('role-object-scope-type')).toBeEnabled());
    expect(screen.getByRole('option', { name: 'My teams' })).toBeDisabled();
    fireEvent.change(screen.getByTestId('role-object-scope-type'), { target: { value: 'team' } });
    expect(onStage).not.toHaveBeenCalled();
  });
  it('preserves stored scope when metadata cannot be verified', async () => {
    models.findByCode.mockRejectedValue(new Error('unavailable'));
    const onStage = show();
    await screen.findByRole('alert');
    expect(screen.getByTestId('role-object-scope-type')).toBeDisabled();
    expect(screen.getByTestId('role-object-scope-type')).toHaveValue('mixed');
    expect(onStage).not.toHaveBeenCalled();
  });
});
