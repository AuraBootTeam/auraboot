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

  it('adds a tenant member by memberPid and filters existing members', async () => {
    renderBlock();

    fireEvent.click(await screen.findByTestId('team-members-add'));
    const select = await screen.findByTestId('team-members-select');

    expect(screen.queryByText(/张三/)).toBeTruthy();
    expect(Array.from((select as HTMLSelectElement).options).map((option) => option.value)).toEqual([
      '',
      'member-pid-2',
    ]);

    fireEvent.change(select, { target: { value: 'member-pid-2' } });
    fireEvent.change(screen.getByTestId('team-members-role'), { target: { value: 'leader' } });
    fireEvent.click(screen.getByTestId('team-members-confirm'));

    await waitFor(() =>
      expect(teamService.addTeamMember).toHaveBeenCalledWith('team-pid-1', {
        memberPid: 'member-pid-2',
        role: 'leader',
      }),
    );
  });

  it('removes members by memberPid instead of the row pid', async () => {
    renderBlock();

    fireEvent.click(await screen.findByTestId('team-members-remove-member-pid-1'));

    await waitFor(() =>
      expect(teamService.removeTeamMember).toHaveBeenCalledWith('team-pid-1', 'member-pid-1'),
    );
  });
});
