import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { PlusIcon, TrashIcon, UserGroupIcon } from '@heroicons/react/24/outline';
import {
  addTeamMember,
  fetchTeamMembers,
  removeTeamMember,
  type TeamMember,
} from '~/shared/services/teamService';
import { post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';

interface TenantMemberOption {
  memberPid: string;
  userPid?: string;
  userName: string;
  userEmail: string;
}

interface TeamMembersBlockProps {
  block?: {
    props?: {
      teamPid?: string;
      teamPidField?: string;
      title?: string;
    };
  };
  runtime?: {
    getContext?: () => {
      record?: Record<string, unknown>;
      row?: Record<string, unknown>;
      $page?: Record<string, unknown>;
    };
  };
}

export function TeamMembersBlock({ block, runtime }: TeamMembersBlockProps) {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [members, setMembers] = useState<TeamMember[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAddModal, setShowAddModal] = useState(false);

  const context = runtime?.getContext?.();
  const record = context?.record || context?.row || {};
  const teamPidField = block?.props?.teamPidField || 'pid';
  const teamPid =
    block?.props?.teamPid ||
    stringValue(record[teamPidField]) ||
    stringValue(context?.$page?.recordPid);
  const title = block?.props?.title || '团队成员';

  const loadMembers = useCallback(async () => {
    if (!teamPid) {
      setMembers([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      setMembers(await fetchTeamMembers(teamPid));
    } catch (error) {
      showErrorToast(error instanceof Error ? error.message : '团队成员加载失败');
    } finally {
      setLoading(false);
    }
  }, [teamPid, showErrorToast]);

  useEffect(() => {
    void loadMembers();
  }, [loadMembers]);

  const existingMemberKeys = useMemo(
    () =>
      members.flatMap((member) =>
        [member.userId, member.userPid, member.memberPid].filter(
          (value): value is string => Boolean(value),
        ),
      ),
    [members],
  );

  const handleAddMember = async (memberPid: string, role: string) => {
    if (!teamPid) return;
    try {
      await addTeamMember(teamPid, { memberPid, role });
      showSuccessToast('成员已加入团队');
      setShowAddModal(false);
      void loadMembers();
    } catch (error) {
      showErrorToast(error instanceof Error ? error.message : '添加成员失败');
    }
  };

  const handleRemoveMember = async (member: TeamMember) => {
    if (!teamPid) return;
    const name = member.userName || member.userEmail || member.memberPid || member.pid;
    if (!window.confirm(`确认将 ${name} 移出团队？`)) return;
    try {
      await removeTeamMember(teamPid, member.memberPid || member.pid);
      showSuccessToast('成员已移出团队');
      void loadMembers();
    } catch (error) {
      showErrorToast(error instanceof Error ? error.message : '移除成员失败');
    }
  };

  if (!teamPid) {
    return (
      <div className="border-border bg-panel rounded-card border px-5 py-6 text-sm text-text-3">
        未找到团队记录，无法加载成员。
      </div>
    );
  }

  return (
    <section className="border-border bg-panel overflow-hidden rounded-card border shadow-sm">
      <div className="border-border flex flex-col gap-3 border-b px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex min-w-0 items-center gap-2">
          <UserGroupIcon className="h-5 w-5 shrink-0 text-accent" />
          <div className="min-w-0">
            <h3 className="text-text text-base font-semibold">
              {title} ({members.length})
            </h3>
            <p className="text-text-3 mt-0.5 text-xs">维护团队成员与团队角色</p>
          </div>
        </div>
        <button
          type="button"
          onClick={() => setShowAddModal(true)}
          className="bg-accent hover:bg-accent-hover focus-visible:shadow-focus inline-flex h-9 items-center justify-center gap-2 rounded-control px-3.5 text-sm font-medium text-white transition-colors focus:outline-none"
          data-testid="team-members-add"
        >
          <PlusIcon className="h-4 w-4" />
          添加成员
        </button>
      </div>

      {loading ? (
        <div className="flex justify-center px-6 py-10">
          <div className="h-7 w-7 animate-spin rounded-full border-2 border-accent border-b-transparent" />
        </div>
      ) : members.length === 0 ? (
        <div className="px-6 py-10 text-center">
          <p className="text-text-2 text-sm font-medium">暂无团队成员</p>
          <p className="text-text-3 mt-1 text-sm">添加成员后，他们会出现在这里。</p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="divide-border min-w-full divide-y">
            <thead className="bg-subtle">
              <tr>
                <HeaderCell>用户</HeaderCell>
                <HeaderCell>邮箱</HeaderCell>
                <HeaderCell>角色</HeaderCell>
                <HeaderCell>加入时间</HeaderCell>
                <HeaderCell align="right">操作</HeaderCell>
              </tr>
            </thead>
            <tbody className="divide-border divide-y">
              {members.map((member) => (
                <tr key={member.pid} className="hover:bg-subtle/70">
                  <td className="text-text px-5 py-3 text-sm font-medium">
                    {member.userName || member.userEmail || '-'}
                  </td>
                  <td className="text-text-2 px-5 py-3 text-sm">{member.userEmail || '-'}</td>
                  <td className="px-5 py-3">
                    <RoleBadge role={member.role} />
                  </td>
                  <td className="text-text-2 px-5 py-3 text-sm">
                    {formatDate(member.joinedAt)}
                  </td>
                  <td className="px-5 py-3 text-right">
                    <button
                      type="button"
                      onClick={() => void handleRemoveMember(member)}
                      className="text-text-3 hover:text-status-red focus-visible:shadow-focus inline-flex h-8 w-8 items-center justify-center rounded-control transition-colors focus:outline-none"
                      title="移除成员"
                      data-testid={`team-members-remove-${member.memberPid || member.pid}`}
                    >
                      <TrashIcon className="h-4 w-4" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showAddModal && (
        <AddMemberModal
          existingMemberKeys={existingMemberKeys}
          onAdd={handleAddMember}
          onClose={() => setShowAddModal(false)}
        />
      )}
    </section>
  );
}

function AddMemberModal({
  existingMemberKeys,
  onAdd,
  onClose,
}: {
  existingMemberKeys: string[];
  onAdd: (memberPid: string, role: string) => void;
  onClose: () => void;
}) {
  const [tenantMembers, setTenantMembers] = useState<TenantMemberOption[]>([]);
  const [selectedMemberPid, setSelectedMemberPid] = useState('');
  const [role, setRole] = useState('member');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    async function loadTenantMembers() {
      setLoading(true);
      try {
        const result = await post<{ records?: any[]; content?: any[] } | any[]>(
          '/api/tenant/members/search',
          {
            status: 'active',
            pageNum: 1,
            pageSize: 100,
          },
        );
        if (cancelled || !ResultHelper.isSuccess(result) || !result.data) return;
        const items = Array.isArray(result.data)
          ? result.data
          : result.data.records || result.data.content || [];
        const existing = new Set(existingMemberKeys.map(String));
        const options = items
          .filter((member: any) => {
            const userId = member.userId ?? member.user?.id;
            const userPid = member.userPid ?? member.user?.pid;
            const memberPid = member.pid;
            return (
              !existing.has(String(userId)) &&
              !existing.has(String(userPid)) &&
              !existing.has(String(memberPid))
            );
          })
          .map((member: any) => ({
            memberPid: String(member.pid || ''),
            userPid: member.user?.pid || member.userPid,
            userName:
              member.displayName ||
              member.user?.realName ||
              member.user?.username ||
              member.user?.email ||
              String(member.userId || member.user?.pid || ''),
            userEmail: member.email || member.user?.email || '',
          }))
          .filter((member) => member.memberPid);
        setTenantMembers(options);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void loadTenantMembers();
    return () => {
      cancelled = true;
    };
  }, [existingMemberKeys]);

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (selectedMemberPid) onAdd(selectedMemberPid, role);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4">
      <div className="bg-panel border-border w-full max-w-lg overflow-hidden rounded-card border shadow-xl">
        <div className="border-border border-b px-6 py-4">
          <h3 className="text-text text-base font-semibold">添加团队成员</h3>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4 p-6">
          <label className="block">
            <span className="text-text-2 mb-1 block text-sm font-medium">选择用户</span>
            {loading ? (
              <span className="text-text-3 text-sm">正在加载成员...</span>
            ) : tenantMembers.length === 0 ? (
              <span className="text-text-3 text-sm">暂无可加入的成员。</span>
            ) : (
              <select
                value={selectedMemberPid}
                onChange={(event) => setSelectedMemberPid(event.target.value)}
                required
                className="border-border-strong bg-panel text-text focus:border-accent focus-visible:shadow-focus w-full rounded-control border px-3 py-2 text-sm focus:outline-none"
                data-testid="team-members-select"
              >
                <option value="">请选择用户</option>
                {tenantMembers.map((member) => (
                  <option key={member.memberPid} value={member.memberPid}>
                    {member.userName} ({member.userEmail || '无邮箱'})
                  </option>
                ))}
              </select>
            )}
          </label>

          <label className="block">
            <span className="text-text-2 mb-1 block text-sm font-medium">团队角色</span>
            <select
              value={role}
              onChange={(event) => setRole(event.target.value)}
              className="border-border-strong bg-panel text-text focus:border-accent focus-visible:shadow-focus w-full rounded-control border px-3 py-2 text-sm focus:outline-none"
              data-testid="team-members-role"
            >
              <option value="member">成员</option>
              <option value="leader">负责人</option>
            </select>
          </label>

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
              data-testid="team-members-confirm"
            >
              添加
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function HeaderCell({
  children,
  align = 'left',
}: {
  children: ReactNode;
  align?: 'left' | 'right';
}) {
  return (
    <th
      className={`text-text-3 px-5 py-3 text-xs font-medium tracking-wide uppercase ${
        align === 'right' ? 'text-right' : 'text-left'
      }`}
    >
      {children}
    </th>
  );
}

function RoleBadge({ role }: { role?: string }) {
  const leader = role === 'leader';
  return (
    <span
      className={`inline-flex rounded-pill px-2.5 py-1 text-xs font-medium ${
        leader ? 'bg-accent-weak text-accent' : 'bg-hover text-text-2'
      }`}
    >
      {leader ? '负责人' : '成员'}
    </span>
  );
}

function stringValue(value: unknown): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'number') return String(value);
  return '';
}

function formatDate(value?: string): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString();
}

export default TeamMembersBlock;
