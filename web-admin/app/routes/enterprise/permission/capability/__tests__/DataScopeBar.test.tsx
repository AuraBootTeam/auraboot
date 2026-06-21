import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { PermissionMatrixDTO } from '../../types';

vi.mock('~/contexts/I18nContext', () => ({
  // Return the i18n key so we can assert which label is shown (scope keys are stable).
  useI18n: () => ({ t: (key: string, _vars?: unknown, fallback?: string) => key || fallback }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn() }),
}));
vi.mock('~/shared/services/permissionService', () => ({
  permissionService: { updateScope: vi.fn().mockResolvedValue(undefined) },
}));

import { permissionService } from '~/shared/services/permissionService';
import DataScopeBar from '../DataScopeBar';

function matrix(actions: Array<{ r: string; a: string; granted: boolean; scope?: string }>): PermissionMatrixDTO {
  return {
    modules: [
      {
        moduleCode: 'crm',
        moduleName: 'CRM',
        resources: actions.map((x, i) => ({
          resourceCode: x.r,
          resourceName: x.r,
          actions: [
            {
              permissionId: i + 1,
              permissionPid: `p${i}`,
              code: `${x.r}.${x.a}`,
              action: x.a,
              label: x.a,
              granted: x.granted,
              supported: true,
              scopeType: x.scope,
            },
          ],
        })),
      },
    ],
  };
}

describe('DataScopeBar', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows the uniform scope of granted permissions', () => {
    render(
      <DataScopeBar
        rolePid="r1"
        matrix={matrix([{ r: 'crm.account', a: 'read', granted: true, scope: 'dept_and_sub' }])}
        onScopeApplied={() => {}}
      />,
    );
    expect(screen.getByTestId('data-scope-current').textContent).toContain('admin.permission.scope.dept_and_sub');
  });

  it('shows "mixed" when granted permissions use different scopes', () => {
    render(
      <DataScopeBar
        rolePid="r1"
        matrix={matrix([
          { r: 'crm.account', a: 'read', granted: true, scope: 'dept' },
          { r: 'crm.lead', a: 'read', granted: true, scope: 'self' },
        ])}
        onScopeApplied={() => {}}
      />,
    );
    expect(screen.getByTestId('data-scope-current').textContent).toContain('admin.permission.scope.mixed');
  });

  it('bulk-applies the chosen scope to every granted permission via updateScope', async () => {
    const onApplied = vi.fn();
    render(
      <DataScopeBar
        rolePid="r1"
        matrix={matrix([
          { r: 'crm.account', a: 'read', granted: true, scope: 'all' },
          { r: 'crm.lead', a: 'read', granted: true, scope: 'all' },
          { r: 'crm.deal', a: 'read', granted: false },
        ])}
        onScopeApplied={onApplied}
      />,
    );

    fireEvent.click(screen.getByTestId('data-scope-modify-btn'));
    fireEvent.click(screen.getByTestId('data-scope-option-dept'));
    fireEvent.click(screen.getByTestId('data-scope-apply'));

    await waitFor(() => expect(permissionService.updateScope).toHaveBeenCalledTimes(2));
    expect(permissionService.updateScope).toHaveBeenCalledWith('r1', {
      resourceCode: 'crm.account',
      actionCode: 'read',
      scopeType: 'dept',
    });
    expect(permissionService.updateScope).toHaveBeenCalledWith('r1', {
      resourceCode: 'crm.lead',
      actionCode: 'read',
      scopeType: 'dept',
    });
    await waitFor(() => expect(onApplied).toHaveBeenCalled());
  });
});
