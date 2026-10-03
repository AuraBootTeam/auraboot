import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ get: vi.fn(), handleAction: vi.fn(), navigate: vi.fn(), permissions: new Set<string>(), toast: { showSuccessToast: vi.fn(), showErrorToast: vi.fn(), showInfoToast: vi.fn(), showWarningToast: vi.fn() } }));
vi.mock('react-router', () => ({ useParams: () => ({ memberPid: 'MEMBER-1' }), useNavigate: () => mocks.navigate }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ token: null, hasPermission: (code: string) => mocks.permissions.has(code) }) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN', t: (key: string, _params?: unknown, fallback?: string) => fallback ?? key }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => mocks.toast }));
vi.mock('~/shared/services/http-client', () => ({ get: mocks.get, post: vi.fn(), put: vi.fn(), del: vi.fn() }));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({ useActionHandler: () => ({ handleAction: mocks.handleAction, loading: false }) }));
import MemberDetailPage from '../pages/organization/member-detail';
async function loadMember(status = 'active') {
  mocks.get.mockImplementation(async (url: string) => ({ code: '0', data: url.endsWith('/teams') ? [] : {
    pid: 'MEMBER-1', status, user: null, joinDate: null, leaveDate: null,
    createdAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z',
  } }));
  render(<MemberDetailPage />);
  await screen.findByTestId('action-bar');
}
describe('native member lifecycle authorization', () => {
  beforeEach(() => { vi.clearAllMocks(); mocks.permissions.clear(); });
  afterEach(cleanup);
  it.each([
    [403, '403', 'forbidden', '无权查看此成员'],
    [404, '404', 'not-found', '成员不存在'],
    [500, '403', 'error', '加载成员信息失败，请重试。'],
  ])('distinguishes HTTP %s from missing records and clears member content', async (httpStatus, code, kind, message) => {
    mocks.get.mockResolvedValue({ code, httpStatus, data: null, desc: 'Access forbidden' });
    render(<MemberDetailPage />);
    await waitFor(() => expect(screen.getByTestId('member-load-error')).toHaveAttribute('data-error-kind', kind));
    expect(screen.getByText(message)).toBeVisible();
    expect(screen.queryByTestId('member-name')).not.toBeInTheDocument();
    expect(screen.queryByTestId('action-bar')).not.toBeInTheDocument();
    expect(mocks.toast.showErrorToast).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '返回成员列表' }));
    expect(mocks.navigate).toHaveBeenCalledWith('/p/tenant_member');
  });
  it('does not expose lifecycle buttons to a read-only member viewer', async () => {
    mocks.permissions.add('model.tenant_member.read');
    await loadMember();
    expect(screen.queryByText(/User #|undefined/)).not.toBeInTheDocument();
    for (const name of ['暂停', '离职', '删除']) expect(screen.queryByRole('button', { name, exact: true })).not.toBeInTheDocument();
  });
  it('hosts the shared lifecycle input dialog on the native detail route', async () => {
    await loadMember();
    fireEvent(window, new CustomEvent('dialog:form', { detail: {
      title: '暂停原因', fields: [{ field: 'reason', type: 'textarea', label: '暂停原因', required: true }],
      onSubmit: vi.fn(), onCancel: vi.fn(),
    } }));
    await waitFor(() => expect(screen.getByTestId('form-dialog-field-reason')).toBeVisible());
  });
  it('uses a non-destructive confirmation for independently granted restoration', async () => {
    mocks.permissions.add('model.tenant_member.restore');
    mocks.permissions.add('meta.command.execute');
    await loadMember('suspended');
    fireEvent.click(screen.getByRole('button', { name: '恢复', exact: true }));
    await waitFor(() => expect(mocks.handleAction).toHaveBeenCalledWith(
      expect.objectContaining({ confirmVariant: 'default', action: expect.objectContaining({ command: 'admin:restore_member' }) }),
      expect.objectContaining({ pid: 'MEMBER-1' }),
    ));
  });
  it.each([
    ['leave', '离职', 'admin:leave_member', 'deactivate', '删除'],
    ['delete', '删除', 'admin:delete_member', 'remove', '离职'],
  ])('dispatches only independently granted %s through the shared preflight command', async (verb, label, command, offboardingAction, forbidden) => {
    mocks.permissions.add('model.tenant_member.' + verb);
    mocks.permissions.add('meta.command.execute');
    await loadMember();
    expect(screen.queryByRole('button', { name: forbidden, exact: true })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '暂停', exact: true })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: label, exact: true }));
    await waitFor(() => expect(mocks.handleAction).toHaveBeenCalledWith(
      expect.objectContaining({ action: expect.objectContaining({ command, offboardingAction,
        inputFieldsTitle: verb === 'leave' ? '办理离职' : undefined,
      }) }),
      expect.objectContaining({ pid: 'MEMBER-1' }),
    ));
  });
});
