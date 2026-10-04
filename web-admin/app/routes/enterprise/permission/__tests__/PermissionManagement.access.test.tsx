import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import PermissionManagement from '../../PermissionManagement';

const mocks = vi.hoisted(() => ({
  permissions: new Set<string>(),
  fetchResult: vi.fn(),
}));
vi.mock('~/contexts/AuthContext', () => ({
  usePermissions: () => ({ hasPermission: (code: string) => mocks.permissions.has(code) }),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    t: (key: string, _vars?: unknown, fallback?: string) =>
      (
        ({
          'admin.permission.accessDenied.title': '无权限访问',
          'admin.permission.accessDenied.message': '当前账号无权查看角色与权限，请联系管理员。',
          'admin.permission.loadFailed': '角色加载失败，请稍后重试。',
        }) as Record<string, string>
      )[key] ||
      fallback ||
      key,
  }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn() }),
}));
vi.mock('~/hooks/useFormSubmit', () => ({
  useFormSubmit: () => ({
    handleSubmitResult: (result: any, options: any) => {
      if (String(result.code) === '0') options.onSuccess?.(result.data);
      else options.onError?.(result);
    },
  }),
}));
vi.mock('~/shared/services/http-client', () => ({ fetchResult: mocks.fetchResult }));
vi.mock('../RoleFormDialog', () => ({ default: () => null }));
vi.mock('../capability/CapabilityRoleEditor', () => ({ default: () => null }));
vi.mock('../RoleMemberTab', () => ({ default: () => null }));
vi.mock('../PermissionAuditTab', () => ({ default: () => null }));
vi.mock('~/ui/ConfirmDialog', () => ({ default: () => null }));

const role = {
  pid: 'role-1',
  code: 'warehouse',
  name: 'Warehouse',
  type: 'CUSTOM',
  status: 'active',
};
function showPage() {
  render(
    <MemoryRouter>
      <PermissionManagement />
    </MemoryRouter>,
  );
}
afterEach(cleanup);
beforeEach(() => {
  mocks.permissions.clear();
  mocks.fetchResult.mockReset();
  mocks.fetchResult.mockResolvedValue({ code: '0', data: { records: [role] } });
});
describe('PermissionManagement access feedback', () => {
  it('keeps the actual creation action available to an authorized role manager', async () => {
    mocks.permissions.add('org.role.read');
    mocks.permissions.add('org.role.update');
    showPage();
    expect(await screen.findByTestId('role-row-warehouse')).toHaveTextContent('Warehouse');
    expect(screen.getByTestId('role-create-btn')).toBeVisible();
    expect(screen.getByTestId('role-action-edit-warehouse')).toBeInTheDocument();
  });

  it('denies a viewer before loading roles and hides the actual icon creation action', async () => {
    showPage();
    expect(await screen.findByText('无权限访问')).toBeVisible();
    expect(screen.queryByTestId('role-create-btn')).toBeNull();
    expect(mocks.fetchResult).not.toHaveBeenCalled();
  });
  it('keeps role rows readable while hiding role mutations for a read-only account', async () => {
    mocks.permissions.add('org.role.read');
    showPage();
    expect(await screen.findByTestId('role-row-warehouse')).toHaveTextContent('Warehouse');
    for (const id of [
      'role-create-btn',
      'role-action-edit-warehouse',
      'role-action-toggle-warehouse',
      'role-action-delete-warehouse',
    ]) {
      expect(screen.queryByTestId(id)).toBeNull();
    }
  });
  it('shows an explicit denial when the server rejects a stale read grant', async () => {
    mocks.permissions.add('org.role.read');
    mocks.fetchResult.mockResolvedValue({ code: '403', data: null });
    showPage();
    expect(await screen.findByText('无权限访问')).toBeVisible();
    expect(screen.queryByTestId('role-create-btn')).toBeNull();
  });
  it('shows a loading failure instead of disguising a network error as an empty role list or denial', async () => {
    mocks.permissions.add('org.role.read');
    mocks.fetchResult.mockResolvedValue({ code: 'NETWORK_ERROR', data: null });
    showPage();
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('角色加载失败'));
    expect(screen.queryByText('无权限访问')).toBeNull();
    expect(screen.queryByTestId('role-table')).toBeNull();
  });
});
