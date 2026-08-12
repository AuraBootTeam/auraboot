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
    save_failed: '保存协作成员失败',
    load_failed: '加载协作成员失败',
    current_members: '当前协作成员',
    empty_title: '暂无协作成员',
    empty_description: '选择成员和权限即可开始协作',
    unavailable_member: '成员已不可用',
    remove: '移除协作成员',
    removed: '协作成员已移除',
    remove_failed: '移除协作成员失败',
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
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => undefined)));

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
    });
    expect(await screen.findByText('销售二组成员')).toBeInTheDocument();
    expect(screen.getAllByText('可协作')).toHaveLength(2);
  });
});
