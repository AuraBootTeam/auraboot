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
  permissionService: {
    getRoleDefaultScope: vi.fn().mockResolvedValue(null),
    setRoleDefaultScope: vi.fn().mockResolvedValue(undefined),
  },
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
  beforeEach(() => {
    vi.clearAllMocks();
    (permissionService.getRoleDefaultScope as ReturnType<typeof vi.fn>).mockResolvedValue(null);
    (permissionService.setRoleDefaultScope as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
  });

  it('shows the persisted role default scope when set', async () => {
    (permissionService.getRoleDefaultScope as ReturnType<typeof vi.fn>).mockResolvedValue('dept_and_sub');
    render(<DataScopeBar rolePid="r1" matrix={matrix([])} onScopeApplied={() => {}} />);
    await waitFor(() =>
      expect(screen.getByTestId('data-scope-current').textContent).toContain('admin.permission.scope.dept_and_sub'),
    );
  });

  it('falls back to the derived scope when no default is set', async () => {
    render(
      <DataScopeBar
        rolePid="r1"
        matrix={matrix([{ r: 'crm.account', a: 'read', granted: true, scope: 'self' }])}
        onScopeApplied={() => {}}
      />,
    );
    await waitFor(() => expect(permissionService.getRoleDefaultScope).toHaveBeenCalledWith('r1'));
    expect(screen.getByTestId('data-scope-current').textContent).toContain('admin.permission.scope.self');
  });

  it('shows "mixed" when grants differ and no default is set', async () => {
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
    await waitFor(() =>
      expect(screen.getByTestId('data-scope-current').textContent).toContain('admin.permission.scope.mixed'),
    );
  });

  it('persists the chosen tier as the role default and refreshes', async () => {
    const onApplied = vi.fn();
    render(<DataScopeBar rolePid="r1" matrix={matrix([])} onScopeApplied={onApplied} />);
    await waitFor(() => expect(permissionService.getRoleDefaultScope).toHaveBeenCalledWith('r1'));

    fireEvent.click(screen.getByTestId('data-scope-modify-btn'));
    fireEvent.click(screen.getByTestId('data-scope-option-dept'));
    fireEvent.click(screen.getByTestId('data-scope-apply'));

    await waitFor(() => expect(permissionService.setRoleDefaultScope).toHaveBeenCalledWith('r1', 'dept'));
    await waitFor(() => expect(onApplied).toHaveBeenCalled());
  });
});
