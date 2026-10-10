import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ get: vi.fn(), handleAction: vi.fn(), navigate: vi.fn(), permissions: new Set<string>(), toast: { showSuccessToast: vi.fn(), showErrorToast: vi.fn(), showInfoToast: vi.fn(), showWarningToast: vi.fn() } }));
vi.mock('react-router', () => ({ useParams: () => ({ memberPid: 'MEMBER-1' }), useNavigate: () => mocks.navigate, useFetcher: () => ({ state: 'idle', Form: (props: React.ComponentProps<'form'>) => <form {...props} /> }) }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ token: null, hasPermission: (code: string) => mocks.permissions.has(code) }) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN', t: (key: string, _params?: unknown, fallback?: string) => fallback ?? key }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => mocks.toast }));
vi.mock('~/shared/services/http-client', () => ({ get: mocks.get, post: vi.fn(), put: vi.fn(), del: vi.fn() }));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({ useActionHandler: () => ({ handleAction: mocks.handleAction, loading: false }) }));
import MemberDetailPage from '../pages/organization/member-detail';
async function loadMember(status = 'active', linkedUser = false) {
  mocks.get.mockImplementation(async (url: string) => ({ code: '0', data: url.endsWith('/teams') ? [] : {
    pid: 'MEMBER-1', status, user: linkedUser ? { pid: 'USER-1', username: 'customer', email: 'customer@example.test', phone: null, realName: 'Customer', avatar: null } : null, joinDate: null, leaveDate: null,
    createdAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z',
  } }));
  let view!: ReturnType<typeof render>;
  // Complete the async loader commit, including the dialog's event listener,
  // before dispatching events from tests. A visible action bar alone does not
  // guarantee that passive effects have been installed.
  await act(async () => { view = render(<MemberDetailPage />); });
  await screen.findByTestId('action-bar');
  return view;
}
describe('native member lifecycle authorization', () => {
  beforeEach(() => { vi.resetAllMocks(); mocks.permissions.clear(); });
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
  it('opens customer access only for an authorized active linked member', async () => {
    mocks.permissions.add('admin.customer.impersonate');
    await loadMember('active', true);
    fireEvent.click(screen.getByRole('button', { name: '代客户登录', exact: true }));
    expect(screen.getByRole('dialog', { name: '代客户登录' })).toBeVisible();
    const form = screen.getByRole('dialog').querySelector('form')!;
    expect(form).toHaveAttribute('action', '/_action/start-impersonation');
    expect(form.querySelector('[name="targetMemberPid"]')).toHaveValue('MEMBER-1');
    expect(screen.getByRole('textbox', { name: '操作原因' })).toBeRequired();
  });
  it.each([
    ['active', true, false],
    ['suspended', true, true],
    ['inactive', true, true],
    ['active', false, true],
  ])('hides customer access for status=%s linked=%s authorized=%s', async (status, linked, authorized) => {
    if (authorized) mocks.permissions.add('admin.customer.impersonate');
    await loadMember(status, linked);
    expect(screen.queryByRole('button', { name: '代客户登录', exact: true })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
  it('loads and localizes the selected member audit history', async () => {
    mocks.permissions.add('admin.customer.impersonate');
    await loadMember('active', true);
    mocks.get.mockResolvedValueOnce({ code: '0', data: [{ sessionPid: 'SESSION-1', operatorDisplayName: 'Operator', authorizationMethod: 'offline', reason: '协助核对订单', reference: null, status: 'ended', startedAt: '2026-10-03T00:00:00Z' }] });
    fireEvent.click(screen.getByRole('button', { name: '代登录记录', exact: true }));
    expect(await screen.findByText('协助核对订单')).toBeVisible();
    expect(mocks.get).toHaveBeenCalledWith('/api/impersonation-sessions/history', { targetMemberPid: 'MEMBER-1', limit: '50' });
    expect(screen.getByText('线下授权')).toBeVisible();
    expect(screen.getByText('已结束')).toBeVisible();
  });
  it('renders a successful empty audit separately from loading', async () => {
    mocks.permissions.add('admin.customer.impersonate');
    await loadMember('active', true);
    mocks.get.mockResolvedValueOnce({ code: '0', data: [] });
    fireEvent.click(screen.getByRole('button', { name: '代登录记录', exact: true }));
    expect(await screen.findByText('暂无代登录记录')).toBeVisible();
    expect(screen.queryByText('正在加载…')).not.toBeInTheDocument();
  });
  it.each(['http', 'transport'])('shows an explicit audit error on %s failure', async (failure) => {
    mocks.permissions.add('admin.customer.impersonate');
    await loadMember('active', true);
    if (failure === 'http') mocks.get.mockResolvedValueOnce({ code: '1', data: null, desc: 'internal diagnostic' });
    else mocks.get.mockRejectedValueOnce(new TypeError('network unavailable'));
    fireEvent.click(screen.getByRole('button', { name: '代登录记录', exact: true }));
    expect(await screen.findByRole('alert')).toHaveTextContent('无法加载代登录记录');
    expect(screen.queryByText('暂无代登录记录')).not.toBeInTheDocument();
    expect(screen.queryByText('正在加载…')).not.toBeInTheDocument();
    expect(screen.queryByText('internal diagnostic')).not.toBeInTheDocument();
  });
  it('discards a pending audit response after read authorization is revoked', async () => {
    mocks.permissions.add('admin.customer.impersonate');
    const view = await loadMember('active', true);
    let complete!: (value: unknown) => void;
    mocks.get.mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
    fireEvent.click(screen.getByRole('button', { name: '代登录记录', exact: true }));
    await waitFor(() => expect(complete).toBeTypeOf('function'));
    mocks.permissions.clear(); view.rerender(<MemberDetailPage />);
    complete({ code: '0', data: [{ reason: '旧权限私密记录', sessionPid: 'STALE' }] });
    await waitFor(() => expect(screen.queryByRole('button', { name: '代登录记录' })).not.toBeInTheDocument());
    expect(screen.queryByText('旧权限私密记录')).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
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
