import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TeamMembersBlock } from '../TeamMembersBlock';

const teamService = vi.hoisted(() => ({
  fetchTeamMembers: vi.fn(),
  addTeamMember: vi.fn(),
  removeTeamMember: vi.fn(),
}));

const httpClient = vi.hoisted(() => ({
  post: vi.fn(),
}));

const toast = vi.hoisted(() => ({
  showSuccessToast: vi.fn(),
  showErrorToast: vi.fn(),
}));

vi.mock('~/shared/services/teamService', () => teamService);
vi.mock('~/shared/services/http-client', () => httpClient);
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => toast,
}));

const permissions = vi.hoisted(() => new Set<string>());
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ hasPermission: (code: string) => permissions.has(code) }),
}));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN' }) }));

function renderBlock() {
  return render(
    <TeamMembersBlock
      block={{ props: { teamPidField: 'pid' } }}
      runtime={{
        getContext: () => ({
          record: { pid: 'team-pid-1' },
          $page: { recordPid: 'fallback-pid' },
        }),
      }}
    />,
  );
}

describe('TeamMembersBlock', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions.clear();
    permissions.add('org.team.manage');
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    teamService.fetchTeamMembers.mockResolvedValue([
      {
        pid: 'row-pid-1',
        memberPid: 'member-pid-1',
        userId: '1001',
        userName: '张三',
        userEmail: 'zhang@example.com',
        role: 'leader',
        joinedAt: '2026-06-24T00:00:00Z',
      },
    ]);
    httpClient.post.mockResolvedValue({
      code: '0',
      data: {
        records: [
          {
            pid: 'member-pid-1',
            userId: '1001',
            user: { username: '张三', email: 'zhang@example.com' },
          },
          {
            pid: 'member-pid-2',
            userId: '1002',
            user: { username: '李四', email: 'li@example.com' },
          },
        ],
      },
    });
    teamService.addTeamMember.mockResolvedValue({});
    teamService.removeTeamMember.mockResolvedValue(undefined);
  });

  it('loads team members from the DSL detail record pid', async () => {
    renderBlock();

    expect(teamService.fetchTeamMembers).toHaveBeenCalledWith('team-pid-1');
    expect(await screen.findByText('张三')).toBeTruthy();
    expect(screen.getByText('负责人')).toBeTruthy();
  });

  it('shows member facts without write controls for a read-only role', async () => {
    permissions.clear();
    renderBlock();
    expect(await screen.findByText('张三')).toBeTruthy();
    expect(screen.getByText('查看团队成员与团队角色')).toBeTruthy();
    expect(screen.queryByTestId('team-members-add')).toBeNull();
    expect(screen.queryByTestId('team-members-remove-row-pid-1')).toBeNull();
    expect(screen.queryByText('操作')).toBeNull();
    expect(teamService.addTeamMember).not.toHaveBeenCalled();
    expect(teamService.removeTeamMember).not.toHaveBeenCalled();
  });

  it('removes an open member dialog when management permission is revoked', async () => {
    const view = renderBlock();
    fireEvent.click(await screen.findByTestId('team-members-add'));
    expect(await screen.findByTestId('team-members-confirm')).toBeTruthy();
    permissions.clear();
    view.rerender(<TeamMembersBlock block={{ props: { teamPid: 'team-pid-1' } }} />);
    expect(screen.queryByTestId('team-members-confirm')).toBeNull();
    expect(screen.queryByTestId('team-members-add')).toBeNull();
    expect(teamService.addTeamMember).not.toHaveBeenCalled();
    permissions.add('org.team.manage');
    view.rerender(<TeamMembersBlock block={{ props: { teamPid: 'team-pid-1' } }} />);
    expect(screen.queryByTestId('team-members-confirm')).toBeNull();
    expect(screen.getByTestId('team-members-add')).toBeTruthy();
  });

  it('adds a tenant member by memberPid and filters existing members', async () => {
    renderBlock();

    fireEvent.click(await screen.findByTestId('team-members-add'));
    const select = await screen.findByTestId('team-members-select');

    expect(screen.queryByText(/张三/)).toBeTruthy();
    expect(Array.from((select as HTMLSelectElement).options).map((option) => option.value)).toEqual(
      ['', 'member-pid-2'],
    );

    fireEvent.change(select, { target: { value: 'member-pid-2' } });
    fireEvent.click(screen.getByTestId('team-members-confirm'));

    await waitFor(() =>
      expect(teamService.addTeamMember).toHaveBeenCalledWith('team-pid-1', {
        memberPid: 'member-pid-2',
        role: 'member',
      }),
    );
  });

  it('removes the team membership row rather than the tenant member', async () => {
    renderBlock();

    fireEvent.click(await screen.findByTestId('team-members-remove-row-pid-1'));

    await waitFor(() =>
      expect(teamService.removeTeamMember).toHaveBeenCalledWith('team-pid-1', 'row-pid-1'),
    );
  });
});
