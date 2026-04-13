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
} from '~/services/teamService';
import { get } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';

interface TenantMemberOption {
  userId: number;
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

  const handleAddMember = async (userId: number, role: string) => {
    if (!teamPid) return;
    try {
      await addTeamMember(teamPid, { userId, role });
      showSuccessToast('Member added');
      setShowAddModal(false);
      loadData();
    } catch (e: any) {
      showErrorToast(e.message || 'Failed to add member');
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
    <div className="p-6">
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
      <div className="rounded-lg bg-white shadow dark:bg-gray-800">
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <div className="flex items-center gap-2">
            <UserGroupIcon className="h-5 w-5 text-gray-500" />
            <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
              Members ({members.length})
            </h2>
          </div>
          <button
            onClick={() => setShowAddModal(true)}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
            data-testid="add-member-btn"
          >
            <PlusIcon className="h-4 w-4" />
            Add Member
          </button>
        </div>

        {members.length === 0 ? (
          <div className="px-6 py-8 text-center text-gray-500 dark:text-gray-400">
            No members yet. Add team members to get started.
          </div>
        ) : (
          <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
            <thead className="bg-gray-50 dark:bg-gray-900">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase dark:text-gray-400">
                  User
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase dark:text-gray-400">
                  Email
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase dark:text-gray-400">
                  Role
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase dark:text-gray-400">
                  Joined
                </th>
                <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase dark:text-gray-400">
                  Actions
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {members.map((member) => (
                <tr key={member.pid} className="dark:hover:bg-gray-750 hover:bg-gray-50">
                  <td className="px-6 py-4 text-sm font-medium text-gray-900 dark:text-white">
                    {member.userName || `User #${member.userId}`}
                  </td>
                  <td className="px-6 py-4 text-sm text-gray-500 dark:text-gray-400">
                    {member.userEmail || '-'}
                  </td>
                  <td className="px-6 py-4">
                    <span
                      className={`inline-flex rounded-full px-2 py-1 text-xs font-semibold ${
                        member.role === 'leader'
                          ? 'bg-purple-100 text-purple-800'
                          : 'bg-gray-100 text-gray-800'
                      }`}
                    >
                      {member.role}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-sm text-gray-500 dark:text-gray-400">
                    {member.joinedAt ? new Date(member.joinedAt).toLocaleDateString() : '-'}
                  </td>
                  <td className="px-6 py-4 text-right">
                    <button
                      onClick={() => handleRemoveMember(member)}
                      className="rounded p-1.5 text-gray-400 hover:text-red-600"
                      title="Remove member"
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
          existingMemberUserIds={members.map((m) => m.userId)}
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
  onAdd: (userId: number, role: string) => void;
  existingMemberUserIds: number[];
}) {
  const [tenantMembers, setTenantMembers] = useState<TenantMemberOption[]>([]);
  const [selectedUserId, setSelectedUserId] = useState<number | null>(null);
  const [role, setRole] = useState('member');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const loadMembers = async () => {
      try {
        const result = await get<any[]>('/api/tenant/members/search', {
          status: 'active',
          pageNum: '1',
          pageSize: '100',
        });
        if (ResultHelper.isSuccess(result) && result.data) {
          const items = Array.isArray(result.data)
            ? result.data
            : (result.data as any).content || [];
          const options: TenantMemberOption[] = items
            .filter((m: any) => !existingMemberUserIds.includes(m.userId || m.user?.id))
            .map((m: any) => ({
              userId: m.userId || m.user?.id,
              userName: m.user?.realName || m.user?.username || `User #${m.userId}`,
              userEmail: m.user?.email || '',
            }));
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
    if (selectedUserId != null) {
      onAdd(selectedUserId, role);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="mx-4 w-full max-w-md rounded-lg bg-white shadow-xl dark:bg-gray-800">
        <div className="border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">Add Team Member</h3>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4 p-6">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              Select User <span className="text-red-500">*</span>
            </label>
            {loading ? (
              <p className="text-sm text-gray-500">Loading members...</p>
            ) : tenantMembers.length === 0 ? (
              <p className="text-sm text-gray-500">No available members to add.</p>
            ) : (
              <select
                value={selectedUserId ?? ''}
                onChange={(e) => setSelectedUserId(Number(e.target.value))}
                required
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                data-testid="member-select"
              >
                <option value="">-- Select a user --</option>
                {tenantMembers.map((m) => (
                  <option key={m.userId} value={m.userId}>
                    {m.userName} ({m.userEmail})
                  </option>
                ))}
              </select>
            )}
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              Role
            </label>
            <select
              value={role}
              onChange={(e) => setRole(e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              data-testid="member-role-select"
            >
              <option value="member">Member</option>
              <option value="leader">Leader</option>
            </select>
          </div>
          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg bg-gray-100 px-4 py-2 text-gray-700 hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={selectedUserId == null || tenantMembers.length === 0}
              className="rounded-lg bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="add-member-confirm-btn"
            >
              Add
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
