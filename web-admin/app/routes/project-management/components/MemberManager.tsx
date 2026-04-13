import { useState, useEffect, useCallback, useMemo } from 'react';
import { get, post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';

// ============================================================================
// Types
// ============================================================================

interface MemberRecord {
  pid: string;
  pm_member_user_id?: string;
  pm_member_role_id?: string;
  pm_member_joined_at?: string;
  pm_member_status?: string;
  [key: string]: unknown;
}

interface TenantUser {
  userId: string;
  displayName: string;
  email: string;
}

interface RoleRecord {
  pid: string;
  pm_role_name?: string;
  [key: string]: unknown;
}

interface MemberManagerProps {
  projectId: string;
}

// ============================================================================
// Component
// ============================================================================

export default function MemberManager({ projectId }: MemberManagerProps) {
  const { locale } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [members, setMembers] = useState<MemberRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAddForm, setShowAddForm] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [removingId, setRemovingId] = useState<string | null>(null);

  // User & role selection
  const [tenantUsers, setTenantUsers] = useState<TenantUser[]>([]);
  const [roles, setRoles] = useState<RoleRecord[]>([]);
  const [selectedUserId, setSelectedUserId] = useState('');
  const [selectedRoleId, setSelectedRoleId] = useState('');
  const [userSearch, setUserSearch] = useState('');

  // ------ Load members ------

  const loadMembers = useCallback(async () => {
    setLoading(true);
    try {
      const result = await get<{ records: MemberRecord[] }>('/api/dynamic/pm-project-member/list', {
        filters: JSON.stringify([
          { fieldName: 'pm_member_project_id', operator: 'EQ', value: projectId },
        ]),
        pageSize: '100',
      });
      if (ResultHelper.isSuccess(result) && result.data?.records) {
        setMembers(result.data.records);
      } else {
        setMembers([]);
      }
    } catch {
      setMembers([]);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  // ------ Load tenant users ------

  const loadTenantUsers = useCallback(async () => {
    try {
      const result = await post<{
        records: Array<{
          userId: string;
          displayName?: string;
          email?: string;
          user?: {
            email?: string;
            realName?: string;
            username?: string;
          };
        }>;
      }>('/api/tenant/members/search', {
        pageNum: 1,
        pageSize: 200,
        status: 'active',
      });
      if (ResultHelper.isSuccess(result) && result.data?.records) {
        setTenantUsers(
          result.data.records.map((u) => ({
            userId: u.userId,
            displayName:
              u.displayName ||
              u.user?.realName ||
              u.user?.username ||
              u.user?.email ||
              u.email ||
              String(u.userId),
            email: u.email || u.user?.email || '',
          })),
        );
      }
    } catch {
      // silent
    }
  }, []);

  // ------ Load roles ------

  const loadRoles = useCallback(async () => {
    try {
      const result = await get<{ records: RoleRecord[] }>('/api/dynamic/pm-project-role/list', {
        pageSize: '100',
      });
      if (ResultHelper.isSuccess(result) && result.data?.records) {
        setRoles(result.data.records);
      }
    } catch {
      // silent
    }
  }, []);

  useEffect(() => {
    loadMembers();
    loadTenantUsers();
    loadRoles();
  }, [loadMembers, loadTenantUsers, loadRoles]);

  // ------ Derived: user map for display ------

  const userMap = useMemo(() => {
    const map = new Map<string, TenantUser>();
    tenantUsers.forEach((u) => map.set(u.userId, u));
    return map;
  }, [tenantUsers]);

  const roleMap = useMemo(() => {
    const map = new Map<string, string>();
    roles.forEach((r) => map.set(r.pid, (r.pm_role_name as string) || r.pid));
    return map;
  }, [roles]);

  // ------ Derived: existing member user IDs (to filter out) ------

  const existingUserIds = useMemo(
    () => new Set(members.map((m) => m.pm_member_user_id).filter(Boolean)),
    [members],
  );

  const filteredUsers = useMemo(() => {
    const search = userSearch.toLowerCase();
    return tenantUsers
      .filter((u) => !existingUserIds.has(u.userId))
      .filter(
        (u) =>
          !search ||
          u.displayName.toLowerCase().includes(search) ||
          u.email.toLowerCase().includes(search),
      );
  }, [tenantUsers, existingUserIds, userSearch]);

  // ------ Add member ------

  const handleAddMember = useCallback(async () => {
    if (!selectedUserId) return;
    setSubmitting(true);
    try {
      const result = await post<unknown>('/api/meta/commands/execute/pm:add_member', {
        payload: {
          pm_member_project_id: projectId,
          pm_member_user_id: String(selectedUserId),
          pm_member_role_id: selectedRoleId || undefined,
        },
        operationType: 'create',
      });
      if (ResultHelper.isSuccess(result)) {
        showSuccessToast(l('成员已添加', 'Member added'));
        setSelectedUserId('');
        setSelectedRoleId('');
        setUserSearch('');
        setShowAddForm(false);
        loadMembers();
      } else {
        showErrorToast(result.message || l('添加失败', 'Failed to add member'));
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : l('添加失败', 'Failed to add member');
      showErrorToast(msg);
    } finally {
      setSubmitting(false);
    }
  }, [projectId, selectedUserId, selectedRoleId, l, showSuccessToast, showErrorToast, loadMembers]);

  // ------ Remove member ------

  const handleRemoveMember = useCallback(
    async (memberPid: string) => {
      setRemovingId(memberPid);
      try {
        const result = await post<unknown>('/api/meta/commands/execute/pm:remove_member', {
          targetRecordId: memberPid,
          operationType: 'delete',
        });
        if (ResultHelper.isSuccess(result)) {
          showSuccessToast(l('成员已移除', 'Member removed'));
          loadMembers();
        } else {
          showErrorToast(result.message || l('移除失败', 'Failed to remove member'));
        }
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : l('移除失败', 'Failed to remove member');
        showErrorToast(msg);
      } finally {
        setRemovingId(null);
      }
    },
    [l, showSuccessToast, showErrorToast, loadMembers],
  );

  // ------ Render helpers ------

  const getUserDisplay = (userId?: string) => {
    if (!userId) return '-';
    const user = userMap.get(userId);
    return user ? user.displayName : userId;
  };

  const getRoleDisplay = (roleId?: string) => {
    if (!roleId) return '-';
    return roleMap.get(roleId) || roleId;
  };

  // ------ Render ------

  if (loading) {
    return (
      <div className="flex min-h-[200px] items-center justify-center" data-testid="members-loading">
        <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600 dark:border-blue-400" />
      </div>
    );
  }

  return (
    <div className="space-y-4" data-testid="member-manager">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
          {l('项目成员', 'Project Members')}
          <span className="ml-2 text-sm font-normal text-gray-500 dark:text-gray-400">
            ({members.length})
          </span>
        </h3>
        <button
          onClick={() => setShowAddForm(!showAddForm)}
          className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-blue-700"
          data-testid="add-member-btn"
        >
          {showAddForm ? l('取消', 'Cancel') : l('添加成员', 'Add Member')}
        </button>
      </div>

      {/* Add member form */}
      {showAddForm && (
        <div
          className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm dark:border-gray-700 dark:bg-gray-800"
          data-testid="add-member-form"
        >
          <div className="mb-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
            {/* User selector with search */}
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                {l('选择用户', 'Select User')}
              </label>
              <input
                type="text"
                value={userSearch}
                onChange={(e) => {
                  setUserSearch(e.target.value);
                  setSelectedUserId('');
                }}
                placeholder={l('搜索用户名或邮箱...', 'Search by name or email...')}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                data-testid="user-search-input"
              />
              {userSearch && !selectedUserId && (
                <div
                  className="mt-1 max-h-40 overflow-y-auto rounded-lg border border-gray-200 bg-white shadow-lg dark:border-gray-600 dark:bg-gray-700"
                  data-testid="user-dropdown"
                >
                  {filteredUsers.length === 0 ? (
                    <div className="px-3 py-2 text-sm text-gray-500 dark:text-gray-400">
                      {l('未找到用户', 'No users found')}
                    </div>
                  ) : (
                    filteredUsers.slice(0, 20).map((user) => (
                      <button
                        key={user.userId}
                        onClick={() => {
                          setSelectedUserId(user.userId);
                          setUserSearch(user.displayName);
                        }}
                        className="w-full px-3 py-2 text-left text-sm text-gray-900 transition-colors hover:bg-blue-50 dark:text-white dark:hover:bg-blue-900/30"
                        data-testid={`user-option-${user.userId}`}
                      >
                        <span className="font-medium">{user.displayName}</span>
                        <span className="ml-2 text-xs text-gray-500 dark:text-gray-400">
                          {user.email}
                        </span>
                      </button>
                    ))
                  )}
                </div>
              )}
              {selectedUserId && (
                <div className="mt-1 text-xs text-green-600 dark:text-green-400">
                  {l('已选择', 'Selected')}: {getUserDisplay(selectedUserId)}
                </div>
              )}
            </div>

            {/* Role selector */}
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                {l('项目角色', 'Project Role')}
              </label>
              <select
                value={selectedRoleId}
                onChange={(e) => setSelectedRoleId(e.target.value)}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                data-testid="role-select"
              >
                <option value="">{l('选择角色...', 'Select role...')}</option>
                {roles.map((role) => (
                  <option key={role.pid} value={role.pid}>
                    {(role.pm_role_name as string) || role.pid}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <button
            onClick={handleAddMember}
            disabled={submitting || !selectedUserId}
            className="rounded-lg bg-green-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
            data-testid="submit-member-btn"
          >
            {submitting ? l('提交中...', 'Submitting...') : l('确认添加', 'Confirm')}
          </button>
        </div>
      )}

      {/* Members list */}
      {members.length === 0 ? (
        <div
          className="rounded-lg bg-white p-8 text-center text-gray-500 shadow-sm dark:bg-gray-800 dark:text-gray-400"
          data-testid="members-empty"
        >
          {l('暂无项目成员', 'No project members yet')}
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800">
          <table className="w-full text-sm" data-testid="members-table">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800/50">
                <th className="px-4 py-3 text-left font-medium text-gray-600 dark:text-gray-400">
                  {l('用户', 'User')}
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-600 dark:text-gray-400">
                  {l('角色', 'Role')}
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-600 dark:text-gray-400">
                  {l('加入日期', 'Joined Date')}
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-600 dark:text-gray-400">
                  {l('状态', 'Status')}
                </th>
                <th className="px-4 py-3 text-right font-medium text-gray-600 dark:text-gray-400">
                  {l('操作', 'Actions')}
                </th>
              </tr>
            </thead>
            <tbody>
              {members.map((member) => (
                <tr
                  key={member.pid}
                  className="border-b border-gray-100 transition-colors hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/30"
                  data-testid={`member-row-${member.pid}`}
                >
                  <td className="px-4 py-3 text-gray-900 dark:text-white">
                    <div className="flex items-center gap-2">
                      <div className="flex h-7 w-7 items-center justify-center rounded-full bg-blue-100 text-xs font-medium text-blue-700 dark:bg-blue-900 dark:text-blue-300">
                        {getUserDisplay(member.pm_member_user_id as string)
                          .charAt(0)
                          .toUpperCase()}
                      </div>
                      <span>{getUserDisplay(member.pm_member_user_id as string)}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-gray-700 dark:text-gray-300">
                    {getRoleDisplay(member.pm_member_role_id as string)}
                  </td>
                  <td className="px-4 py-3 text-gray-700 dark:text-gray-300">
                    {member.pm_member_joined_at || '-'}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${
                        member.pm_member_status === 'active'
                          ? 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-300'
                          : 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400'
                      }`}
                    >
                      {member.pm_member_status || 'active'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button
                      onClick={() => handleRemoveMember(member.pid)}
                      disabled={removingId === member.pid}
                      className="text-xs text-red-600 hover:text-red-700 disabled:opacity-50 dark:text-red-400 dark:hover:text-red-300"
                      data-testid={`remove-member-${member.pid}`}
                    >
                      {removingId === member.pid
                        ? l('移除中...', 'Removing...')
                        : l('移除', 'Remove')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
