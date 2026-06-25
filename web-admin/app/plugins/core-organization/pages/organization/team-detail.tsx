import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router';
import { ArrowLeftIcon, PlusIcon, TrashIcon, UserGroupIcon } from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';
import {
  fetchTeam,
  fetchTeamMembers,
  addTeamMember,
  removeTeamMember,
  type Team,
  type TeamMember,
} from '~/shared/services/teamService';
import { post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

interface TenantMemberOption {
  memberPid: string;
  userPid?: string;
  userName: string;
  userEmail: string;
}

export default function TeamDetailPage() {
  const { teamPid } = useParams();
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [team, setTeam] = useState<Team | null>(null);
  const [members, setMembers] = useState<TeamMember[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAddModal, setShowAddModal] = useState(false);

  const loadData = useCallback(async () => {
    if (!teamPid) return;
    setLoading(true);
    try {
      const [teamData, memberData] = await Promise.all([
        fetchTeam(teamPid),
        fetchTeamMembers(teamPid),
      ]);
      setTeam(teamData);
      setMembers(memberData);
    } catch (e: any) {
      showErrorToast(e.message || 'Failed to load team data');
    } finally {
      setLoading(false);
    }
  }, [teamPid, showErrorToast]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleRemoveMember = async (member: TeamMember) => {
    if (!teamPid) return;
    if (!confirm(`Remove ${member.userName || member.userEmail} from the team?`)) return;
    try {
      await removeTeamMember(teamPid, member.pid);
      showSuccessToast('Member removed');
      loadData();
    } catch (e: any) {
      showErrorToast(e.message || 'Failed to remove member');
    }
  };

  const handleAddMember = async (memberPid: string, role: string) => {
    if (!teamPid) return;
    try {
      await addTeamMember(teamPid, { memberPid, role });
      showSuccessToast('成员已加入团队');
      setShowAddModal(false);
      loadData();
    } catch (e: any) {
      showErrorToast(e.message || '添加成员失败');
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center p-6 py-12">
        <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
      </div>
    );
  }

  if (!team) {
    return <div className="p-6 text-center text-gray-500 dark:text-gray-400">Team not found.</div>;
  }

  return (
      <div className="bg-subtle min-h-[calc(100vh-3.5rem)] px-4 py-5 sm:px-6 lg:px-8">
      {/* Header */}
      <div className="mb-6 flex items-center gap-4">
        <button
          onClick={() => navigate('/organization/teams')}
          className="rounded-lg p-2 text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-700 dark:hover:text-gray-300"
        >
          <ArrowLeftIcon className="h-5 w-5" />
        </button>
        <div>
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">{team.name}</h1>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            Code: {team.code} {team.description && `— ${team.description}`}
          </p>
        </div>
      </div>

      {/* Members section */}
      <div className="border-border bg-panel overflow-hidden rounded-card border shadow-sm">
        <div className="border-border flex items-center justify-between border-b px-6 py-4">
          <div className="flex items-center gap-2">
            <UserGroupIcon className="h-5 w-5 text-gray-500" />
            <h2 className="text-text text-lg font-semibold">
              团队成员 ({members.length})
            </h2>
          </div>
          <button
            onClick={() => setShowAddModal(true)}
            className="bg-accent hover:bg-accent-hover flex h-9 items-center gap-2 rounded-control px-3.5 text-sm font-medium text-white transition-colors"
            data-testid="add-member-btn"
          >
            <PlusIcon className="h-4 w-4" />
            添加成员
          </button>
        </div>

        {members.length === 0 ? (
          <div className="text-text-3 px-6 py-12 text-center text-sm">
            暂无团队成员
          </div>
        ) : (
          <table className="divide-border min-w-full divide-y">
            <thead className="bg-subtle">
              <tr>
                <th className="text-text-3 px-6 py-3 text-left text-xs font-medium uppercase tracking-wide">
                  用户
                </th>
                <th className="text-text-3 px-6 py-3 text-left text-xs font-medium uppercase tracking-wide">
                  邮箱
                </th>
                <th className="text-text-3 px-6 py-3 text-left text-xs font-medium uppercase tracking-wide">
                  角色
                </th>
                <th className="text-text-3 px-6 py-3 text-left text-xs font-medium uppercase tracking-wide">
                  加入时间
                </th>
                <th className="text-text-3 px-6 py-3 text-right text-xs font-medium uppercase tracking-wide">
                  操作
                </th>
              </tr>
            </thead>
            <tbody className="divide-border divide-y">
              {members.map((member) => (
                <tr key={member.pid} className="hover:bg-subtle/70">
                  <td className="text-text px-6 py-4 text-sm font-medium">
                    {member.userName || `User #${member.userId}`}
                  </td>
                  <td className="text-text-2 px-6 py-4 text-sm">
                    {member.userEmail || '-'}
                  </td>
                  <td className="px-6 py-4">
                    <span
                      className={`inline-flex rounded-full px-2.5 py-1 text-xs font-medium ${
                        member.role === 'leader'
                          ? 'bg-accent-weak text-accent'
                          : 'bg-hover text-text-2'
                      }`}
                    >
                      {member.role === 'leader' ? '负责人' : '成员'}
                    </span>
                  </td>
                  <td className="text-text-2 px-6 py-4 text-sm">
                    {member.joinedAt ? new Date(member.joinedAt).toLocaleDateString() : '-'}
                  </td>
                  <td className="px-6 py-4 text-right">
                    <button
                      onClick={() => handleRemoveMember(member)}
                      className="text-text-3 hover:text-status-red rounded p-1.5 transition-colors"
                      title="移除成员"
                      data-testid={`remove-member-${member.userId}`}
                    >
                      <TrashIcon className="h-4 w-4" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {showAddModal && (
        <AddMemberModal
          onClose={() => setShowAddModal(false)}
          onAdd={handleAddMember}
          existingMemberUserIds={members.flatMap((m) =>
            [m.userId, m.userPid, m.memberPid].filter((value): value is string => Boolean(value)),
          )}
        />
      )}
    </div>
  );
}

function AddMemberModal({
  onClose,
  onAdd,
  existingMemberUserIds,
}: {
  onClose: () => void;
  onAdd: (memberPid: string, role: string) => void;
  existingMemberUserIds: string[];
}) {
  const [tenantMembers, setTenantMembers] = useState<TenantMemberOption[]>([]);
  const [selectedMemberPid, setSelectedMemberPid] = useState('');
  const [role, setRole] = useState('member');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const loadMembers = async () => {
      try {
        const result = await post<{ records?: any[]; content?: any[] } | any[]>(
          '/api/tenant/members/search',
          {
            status: 'active',
            pageNum: 1,
            pageSize: 100,
          },
        );
        if (ResultHelper.isSuccess(result) && result.data) {
          const items = Array.isArray(result.data)
            ? result.data
            : result.data.records || result.data.content || [];
          const existing = new Set(existingMemberUserIds.map(String));
          const options: TenantMemberOption[] = items
            .filter((m: any) => {
              const userId = m.userId ?? m.user?.id;
              const userPid = m.userPid ?? m.user?.pid;
              return !existing.has(String(userId)) && !existing.has(String(userPid));
            })
            .map((m: any) => ({
              memberPid: String(m.pid || ''),
              userPid: m.user?.pid || m.userPid,
              userName:
                m.displayName ||
                m.user?.realName ||
                m.user?.username ||
                m.user?.email ||
                String(m.userId || m.user?.pid || ''),
              userEmail: m.email || m.user?.email || '',
            }))
            .filter((m) => m.memberPid);
          setTenantMembers(options);
        }
      } catch {
        // ignore
      } finally {
        setLoading(false);
      }
    };
    loadMembers();
  }, [existingMemberUserIds]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (selectedMemberPid) {
      onAdd(selectedMemberPid, role);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-panel border-border mx-4 w-full max-w-lg rounded-card border shadow-xl">
        <div className="border-border border-b px-6 py-4">
          <h3 className="text-text text-lg font-semibold">添加团队成员</h3>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4 p-6">
          <div>
            <label className="text-text-2 mb-1 block text-sm font-medium">
              选择用户 <span className="text-status-red">*</span>
            </label>
            {loading ? (
              <p className="text-text-3 text-sm">正在加载成员...</p>
            ) : tenantMembers.length === 0 ? (
              <p className="text-text-3 text-sm">暂无可加入的成员。</p>
            ) : (
              <select
                value={selectedMemberPid}
                onChange={(e) => setSelectedMemberPid(e.target.value)}
                required
                className="border-border-strong bg-panel text-text focus:border-accent focus-visible:shadow-focus w-full rounded-control border px-3 py-2 text-sm focus:outline-none"
                data-testid="member-select"
              >
                <option value="">请选择用户</option>
                {tenantMembers.map((m) => (
                  <option key={m.memberPid} value={m.memberPid}>
                    {m.userName} ({m.userEmail})
                  </option>
                ))}
              </select>
            )}
          </div>
          <div>
            <label className="text-text-2 mb-1 block text-sm font-medium">
              团队角色
            </label>
            <select
              value={role}
              onChange={(e) => setRole(e.target.value)}
              className="border-border-strong bg-panel text-text focus:border-accent focus-visible:shadow-focus w-full rounded-control border px-3 py-2 text-sm focus:outline-none"
              data-testid="member-role-select"
            >
              <option value="member">成员</option>
              <option value="leader">负责人</option>
            </select>
          </div>
          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="border-border-strong bg-panel text-text-2 hover:bg-subtle rounded-control border px-4 py-2 text-sm transition-colors"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={!selectedMemberPid || tenantMembers.length === 0}
              className="bg-accent hover:bg-accent-hover rounded-control px-4 py-2 text-sm font-medium text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="add-member-confirm-btn"
            >
              添加
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
