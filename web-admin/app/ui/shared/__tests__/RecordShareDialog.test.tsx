import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { RecordShareDialog } from '../RecordShareDialog';

const showSuccessToast = vi.fn();
const showErrorToast = vi.fn();

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast, showErrorToast }),
}));

vi.mock('~/framework/extensions/use-contribution', () => ({
  useContributionRegistry: () => ({ getRenderer: () => undefined }),
}));

vi.mock('~/ui/smart/picker/MemberPicker', () => ({
  MemberPicker: ({ onChange }: { onChange: (value: string) => void }) => (
    <button type="button" onClick={() => onChange('member-pid')}>
      选择销售二组
    </button>
  ),
}));

const I18N = {
  common: { loading: '加载中…' },
  record_share: {
    title: '记录协作',
    subtitle: '邀请租户成员共同查看或维护这条记录',
    close: '关闭协作窗口',
    add_member: '添加协作成员',
    edit_member: '更新协作成员',
    editing_description: '修改权限或续期，不会重复创建协作关系',
    member_label: '租户成员',
    member_placeholder: '选择成员',
    permission: '协作权限',
    permission_read_title: '仅查看',
    permission_read_description: '可以查看该记录，但不能修改',
    permission_collaborate_title: '可协作',
    permission_collaborate_description: '可以共同查看和编辑该记录',
    save: '保存协作成员',
    saving: '保存中…',
    saved: '协作成员已保存',
    update: '更新访问权限',
    updated: '协作权限已更新',
    save_failed: '保存协作成员失败',
    update_failed: '更新协作权限失败',
    load_failed: '加载协作成员失败',
    current_members: '当前协作成员',
    empty_title: '暂无协作成员',
    empty_description: '选择成员和权限即可开始协作',
    unavailable_member: '成员已不可用',
    edit: '编辑协作成员',
    renew: '为协作成员续期',
    expiry: '访问有效期',
    expiry_never: '长期有效',
    expiry_7d: '7天',
    expiry_30d: '30天',
    expiry_custom: '自定义',
    expiry_date: '到期日期',
    expiry_never_hint: '访问权限会一直保留，直到负责人主动移除',
    expiry_hint: '到期后自动失去访问权限，负责人之后仍可续期',
    expires_on: '到期于',
    expired: '已到期',
    remove: '移除协作成员',
    removed: '协作成员已移除',
    remove_failed: '移除协作成员失败',
    select_all: '全选',
    remove_selected: '移除所选',
    batch_confirm: '确认移除所选协作成员？',
    confirm_remove: '确认移除',
    select_member: '选择协作成员',
    batch_removed: '所选协作成员已移除',
    batch_remove_failed: '批量移除协作成员失败',
  },
};

function renderDialog() {
  return render(
    <I18nProvider initialLocale="zh-CN" initialData={I18N}>
      <RecordShareDialog
        open
        onClose={vi.fn()}
        resourceCode="crm_account_common"
        recordPid="account-pid"
      />
    </I18nProvider>,
  );
}

describe('RecordShareDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows readable member names and never renders internal or subject PIDs', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          code: '0',
          data: [
            {
              pid: 'share-public-pid',
              subjectType: 'member',
              subjectName: '销售二组成员',
              permissionMask: 'read',
            },
          ],
        }),
      }),
    );

    renderDialog();

    expect(await screen.findByText('销售二组成员')).toBeInTheDocument();
    expect(screen.getAllByText('仅查看')).toHaveLength(2);
    expect(screen.queryByText('share-public-pid')).not.toBeInTheDocument();
    expect(screen.queryByText('member-pid')).not.toBeInTheDocument();
  });

  it('shows the loading state while collaborators are being fetched', () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise(() => undefined)),
    );

    renderDialog();

    expect(screen.getByText('加载中…')).toBeInTheDocument();
  });

  it('reports a collaborator loading failure without exposing the backend response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        json: async () => ({ code: '500', message: 'internal database detail' }),
      }),
    );

    renderDialog();

    await waitFor(() => expect(showErrorToast).toHaveBeenCalledWith('加载协作成员失败'));
    expect(screen.queryByText('internal database detail')).not.toBeInTheDocument();
  });

  it('removes a collaborator by public share PID and updates the list immediately', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          code: '0',
          data: [
            {
              pid: 'share-public-pid',
              subjectType: 'member',
              subjectName: '销售二组成员',
              permissionMask: 'read',
            },
          ],
        }),
      })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ code: '0', data: null }) });
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    await screen.findByText('销售二组成员');
    fireEvent.click(screen.getByRole('button', { name: '移除协作成员' }));

    await waitFor(() => expect(screen.queryByText('销售二组成员')).not.toBeInTheDocument());
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/record-share/share-public-pid', {
      method: 'DELETE',
    });
    expect(showSuccessToast).toHaveBeenCalledWith('协作成员已移除');
  });

  it('batch-removes selected collaborators only after explicit confirmation', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          code: '0',
          data: [
            { pid: 'share-1', subjectName: '销售一组', permissionMask: 'read' },
            { pid: 'share-2', subjectName: '销售二组', permissionMask: 'read,update' },
          ],
        }),
      })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ code: '0', data: null }) });
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    await screen.findByText('销售一组');
    fireEvent.click(screen.getByTestId('record-share-select-share-1'));
    fireEvent.click(screen.getByTestId('record-share-select-share-2'));
    fireEvent.click(screen.getByTestId('record-share-batch-remove'));
    expect(screen.getByTestId('record-share-batch-confirm')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByTestId('record-share-batch-confirm-ok'));

    await waitFor(() => expect(screen.queryByText('销售一组')).not.toBeInTheDocument());
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/record-share/batch-delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sharePids: ['share-1', 'share-2'] }),
    });
    expect(showSuccessToast).toHaveBeenCalledWith('所选协作成员已移除');
  });

  it('saves the collaborator permission upgrade using only public PIDs', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ code: '0', data: [] }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ code: '0', data: null }) })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          code: '0',
          data: [
            {
              pid: 'share-public-pid',
              subjectType: 'member',
              subjectName: '销售二组成员',
              permissionMask: 'read,update',
            },
          ],
        }),
      });
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    await screen.findByText('暂无协作成员');
    fireEvent.click(screen.getByRole('button', { name: '选择销售二组' }));
    fireEvent.click(screen.getByTestId('record-share-permission-read-update'));
    fireEvent.click(screen.getByTestId('record-share-add-btn'));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    const request = fetchMock.mock.calls[1];
    expect(request[0]).toBe('/api/record-share');
    expect(JSON.parse(request[1].body)).toEqual({
      resourceCode: 'crm_account_common',
      recordPid: 'account-pid',
      subjectType: 'member',
      subjectPid: 'member-pid',
      permissionMask: 'read,update',
      expiresAt: null,
    });
    expect(await screen.findByText('销售二组成员')).toBeInTheDocument();
    expect(screen.getAllByText('可协作')).toHaveLength(2);
  });

  it('saves a bounded expiry and renders its business date without exposing public PIDs', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ code: '0', data: [] }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ code: '0', data: null }) })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          code: '0',
          data: [
            {
              pid: 'share-public-pid',
              subjectType: 'member',
              subjectName: '销售二组成员',
              permissionMask: 'read',
              expiresAt: '2099-08-31T15:59:59.999Z',
            },
          ],
        }),
      });
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    await screen.findByText('暂无协作成员');
    fireEvent.click(screen.getByRole('button', { name: '选择销售二组' }));
    fireEvent.click(screen.getByTestId('record-share-expiry-custom'));
    fireEvent.change(screen.getByTestId('date-picker-input-record-share-expiry-date'), {
      target: { value: '2099-08-31' },
    });
    fireEvent.click(screen.getByTestId('record-share-add-btn'));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    const payload = JSON.parse(fetchMock.mock.calls[1][1].body);
    expect(payload.expiresAt).toMatch(/^2099-08-31T/);
    expect(await screen.findByText('销售二组成员')).toBeInTheDocument();
    expect(screen.getByText('到期于')).toBeInTheDocument();
    expect(screen.queryByText('share-public-pid')).not.toBeInTheDocument();
  });

  it('keeps an expired relationship manageable and renews it by public share PID only', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          code: '0',
          data: [
            {
              pid: 'share-public-pid',
              subjectType: 'member',
              subjectName: '销售二组成员',
              permissionMask: 'read',
              expiresAt: '2020-01-01T00:00:00Z',
            },
          ],
        }),
      })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ code: '0', data: null }) })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          code: '0',
          data: [
            {
              pid: 'share-public-pid',
              subjectType: 'member',
              subjectName: '销售二组成员',
              permissionMask: 'read,update',
              expiresAt: '2099-09-30T00:00:00Z',
            },
          ],
        }),
      });
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    expect(await screen.findByText('已到期')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '为协作成员续期' }));
    expect(screen.getByTestId('record-share-editing-member')).toHaveTextContent('销售二组成员');
    expect(screen.queryByRole('button', { name: '选择销售二组' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('record-share-permission-read-update'));
    fireEvent.change(screen.getByTestId('date-picker-input-record-share-expiry-date'), {
      target: { value: '2099-09-30' },
    });
    fireEvent.click(screen.getByTestId('record-share-add-btn'));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    expect(fetchMock.mock.calls[1][0]).toBe('/api/record-share/share-public-pid');
    expect(fetchMock.mock.calls[1][1].method).toBe('PATCH');
    const payload = JSON.parse(fetchMock.mock.calls[1][1].body);
    expect(payload).toEqual({
      permissionMask: 'read,update',
      expiresAt: expect.stringMatching(/^2099-09-30T/),
    });
    expect(payload).not.toHaveProperty('subjectPid');
    expect(payload).not.toHaveProperty('recordPid');
    expect(showSuccessToast).toHaveBeenCalledWith('协作权限已更新');
    expect(await screen.findByText('到期于')).toBeInTheDocument();
  });

  it('can convert a bounded relationship back to permanent access', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          code: '0',
          data: [
            {
              pid: 'share-public-pid',
              subjectType: 'member',
              subjectName: '销售二组成员',
              permissionMask: 'read',
              expiresAt: '2099-08-31T00:00:00Z',
            },
          ],
        }),
      })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ code: '0', data: null }) })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          code: '0',
          data: [
            {
              pid: 'share-public-pid',
              subjectType: 'member',
              subjectName: '销售二组成员',
              permissionMask: 'read',
              expiresAt: null,
            },
          ],
        }),
      });
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    await screen.findByText('销售二组成员');
    fireEvent.click(screen.getByRole('button', { name: '编辑协作成员' }));
    fireEvent.click(screen.getByTestId('record-share-expiry-never'));
    fireEvent.click(screen.getByTestId('record-share-add-btn'));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({
      permissionMask: 'read',
      expiresAt: null,
    });
    expect(await screen.findAllByText('长期有效')).not.toHaveLength(0);
  });

  it('reports an update failure separately without exposing backend details', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          code: '0',
          data: [
            {
              pid: 'share-public-pid',
              subjectType: 'member',
              subjectName: '销售二组成员',
              permissionMask: 'read',
              expiresAt: null,
            },
          ],
        }),
      })
      .mockResolvedValueOnce({
        ok: false,
        status: 500,
        json: async () => ({ code: '500', message: 'internal database detail' }),
      });
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    await screen.findByText('销售二组成员');
    fireEvent.click(screen.getByRole('button', { name: '编辑协作成员' }));
    fireEvent.click(screen.getByTestId('record-share-add-btn'));

    await waitFor(() => expect(showErrorToast).toHaveBeenCalledWith('更新协作权限失败'));
    expect(screen.queryByText('internal database detail')).not.toBeInTheDocument();
  });
});
