import { useCallback, useEffect, useMemo, useState, useRef, useId, type FormEvent, type ReactNode } from 'react';
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
import { useAuth } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText, type LocalizedText } from '~/utils/i18n';
import TEXT from './TeamMembers.i18n.json';
function useTeamText() {
  const { locale } = useI18n();
  return useCallback((key: keyof typeof TEXT) => getLocalizedText(TEXT[key], locale), [locale]);
}

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
      title?: string | LocalizedText;
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
  const { locale, t } = useI18n();
  const text = useTeamText();
  const [loadFailed, setLoadFailed] = useState(false);
  const requestVersion = useRef(0);
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { hasPermission } = useAuth();
  const canManage = hasPermission('org.team.manage');
  const l = (zh: string, en: string) => (locale === 'zh-CN' ? zh : en);
  const [members, setMembers] = useState<TeamMember[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAddModal, setShowAddModal] = useState(false);

  useEffect(() => {
    if (!canManage) setShowAddModal(false);
  }, [canManage]);

  const context = runtime?.getContext?.();
  const record = context?.record || context?.row || {};
  const teamPidField = block?.props?.teamPidField || 'pid';
  const teamPid =
    block?.props?.teamPid ||
    stringValue(record[teamPidField]) ||
    stringValue(context?.$page?.recordPid);
  const title = getLocalizedText(block?.props?.title, locale, t) || text('title');

  const loadMembers = useCallback(async () => {
    const version = ++requestVersion.current;
    setLoadFailed(false);
    if (!teamPid) {
      setMembers([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const result = await fetchTeamMembers(teamPid);
      if (version === requestVersion.current) setMembers(result);
    } catch {
      if (version === requestVersion.current) setLoadFailed(true);
    } finally {
      if (version === requestVersion.current) setLoading(false);
    }
  }, [teamPid]);

  useEffect(() => {
    void loadMembers();
    return () => { requestVersion.current++; };
  }, [loadMembers]);

  const existingMemberKeys = useMemo(
    () =>
      members.flatMap((member) =>
        [member.userId, member.userPid, member.memberPid].filter((value): value is string =>
          Boolean(value),
        ),
      ),
    [members],
  );

  const handleAddMember = async (memberPid: string, role: string) => {
    if (!teamPid || !canManage) return;
    try {
      await addTeamMember(teamPid, { memberPid, role });
      showSuccessToast(text('added'));
      setShowAddModal(false);
      void loadMembers();
    } catch (error) {
      showErrorToast(error instanceof Error ? error.message : text('addFailure'));
    }
  };

  const handleRemoveMember = async (member: TeamMember) => {
    if (!teamPid || !canManage) return;
    const name = member.userName || member.userEmail || l('该成员', 'this member');
    if (!window.confirm(`确认将 ${name} 移出团队？`)) return;
    try {
      await removeTeamMember(teamPid, member.pid);
      showSuccessToast('成员已移出团队');
      void loadMembers();
    } catch (error) {
      showErrorToast(error instanceof Error ? error.message : text('removeFailure'));
    }
  };

  if (!teamPid) {
    return (
      <div className="border-border bg-panel rounded-card text-text-3 border px-5 py-6 text-sm">
        未找到团队记录，无法加载成员。
      </div>
    );
  }

  return (
    <section className="border-border bg-panel rounded-card overflow-hidden border shadow-sm">
      <div className="border-border flex flex-col gap-3 border-b px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex min-w-0 items-center gap-2">
          <UserGroupIcon className="text-accent h-5 w-5 shrink-0" />
          <div className="min-w-0">
            <h3 className="text-text text-base font-semibold">
              {title} ({members.length})
            </h3>
            <p className="text-text-3 mt-0.5 text-xs">
              {canManage
                ? l('维护团队成员与团队角色', 'Manage team members and roles')
                : l('查看团队成员与团队角色', 'View team members and roles')}
            </p>
          </div>
        </div>
        {canManage && (
          <button
            type="button"
            onClick={() => setShowAddModal(true)}
            className="bg-accent hover:bg-accent-hover focus-visible:shadow-focus rounded-control inline-flex h-9 items-center justify-center gap-2 px-3.5 text-sm font-medium text-white transition-colors focus:outline-none"
            data-testid="team-members-add"
          >
            <PlusIcon className="h-4 w-4" />
            {l('添加成员', 'Add member')}
          </button>
        )}
      </div>

      {loading ? (
        <div className="flex justify-center px-6 py-10">
          <div className="border-accent h-7 w-7 animate-spin rounded-full border-2 border-b-transparent" />
        </div>
      ) : loadFailed ? (
        <div role="alert" className="px-6 py-10 text-center" data-testid="team-members-load-error">
          <p className="text-status-red text-sm">{text('loadFailure')}</p>
          <button type="button" onClick={() => void loadMembers()} className="mt-3 text-sm text-accent" data-testid="team-members-retry">{text('retry')}</button>
        </div>
      ) : members.length === 0 ? (
        <div className="px-6 py-10 text-center">
          <p className="text-text-2 text-sm font-medium">暂无团队成员</p>
          <p className="text-text-3 mt-1 text-sm">
            {canManage
              ? l('添加成员后，他们会出现在这里。', 'Added members will appear here.')
              : l('此团队尚未添加成员。', 'This team has no members yet.')}
          </p>
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
                {canManage && <HeaderCell align="right">{l('操作', 'Actions')}</HeaderCell>}
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
                  <td className="text-text-2 px-5 py-3 text-sm">{formatDate(member.joinedAt, locale)}</td>
                  {canManage && (
                    <td className="px-5 py-3 text-right">
                      <button
                        type="button"
                        onClick={() => void handleRemoveMember(member)}
                        className="text-text-3 hover:text-status-red focus-visible:shadow-focus rounded-control inline-flex h-8 w-8 items-center justify-center transition-colors focus:outline-none"
                        title={l('移除成员', 'Remove member')}
                        data-testid={`team-members-remove-${member.pid}`}
                      >
                        <TrashIcon className="h-4 w-4" />
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {canManage && showAddModal && (
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
  const text = useTeamText();
  const dialogTitleId = useId();
  const [loadFailed, setLoadFailed] = useState(false);
  const [retryAttempt, setRetryAttempt] = useState(0);
  const [tenantMembers, setTenantMembers] = useState<TenantMemberOption[]>([]);
  const [selectedMemberPid, setSelectedMemberPid] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    async function loadTenantMembers() {
      setLoading(true);
      setLoadFailed(false);
      try {
        const result = await post<{ records?: any[]; content?: any[] } | any[]>(
          '/api/tenant/members/search',
          {
            status: 'active',
            pageNum: 1,
            pageSize: 100,
          },
        );
        if (cancelled) return;
        if (!ResultHelper.isSuccess(result) || !result.data) {
          setLoadFailed(true);
          return;
        }
        const items = Array.isArray(result.data)
          ? result.data
          : Array.isArray(result.data.records)
            ? result.data.records
            : Array.isArray(result.data.content)
              ? result.data.content
              : null;
        if (!items) {
          setLoadFailed(true);
          return;
        }
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
        setSelectedMemberPid(previous => options.some(member => member.memberPid === previous) ? previous : '');
      } catch {
        if (!cancelled) setLoadFailed(true);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void loadTenantMembers();
    return () => {
      cancelled = true;
    };
  }, [existingMemberKeys, retryAttempt]);

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!loading && !loadFailed && tenantMembers.some(member => member.memberPid === selectedMemberPid)) onAdd(selectedMemberPid, 'member');
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4">
      <div className="bg-panel border-border rounded-card w-full max-w-lg overflow-hidden border shadow-xl">
        <div className="border-border border-b px-6 py-4">
          <h3 id={dialogTitleId} className="text-text text-base font-semibold">{text('modalTitle')}</h3>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4 p-6">
          <label className="block">
            <span className="text-text-2 mb-1 block text-sm font-medium">{text('selectUser')}</span>
            {loading ? (
              <span className="text-text-3 text-sm">{text('loading')}</span>
            ) : loadFailed ? (
              <span role="alert" className="text-status-red text-sm" data-testid="team-members-candidate-error">
                {text('candidateFailure')}
                <button type="button" onClick={() => setRetryAttempt(n => n + 1)} className="ml-2 text-accent" data-testid="team-members-candidate-retry">{text('retry')}</button>
              </span>
            ) : tenantMembers.length === 0 ? (
              <span className="text-text-3 text-sm">{text('noCandidates')}</span>
            ) : (
              <select
                value={selectedMemberPid}
                onChange={(event) => setSelectedMemberPid(event.target.value)}
                required
                className="border-border-strong bg-panel text-text focus:border-accent focus-visible:shadow-focus rounded-control w-full border px-3 py-2 text-sm focus:outline-none"
                data-testid="team-members-select"
              >
                <option value="">{text('selectPlaceholder')}</option>
                {tenantMembers.map((member) => (
                  <option key={member.memberPid} value={member.memberPid}>
                    {member.userName} ({member.userEmail || text('noEmail')})
                  </option>
                ))}
              </select>
            )}
          </label>

          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="border-border-strong bg-panel text-text-2 hover:bg-subtle rounded-control border px-4 py-2 text-sm transition-colors"
            >
              {text('cancel')}
            </button>
            <button
              type="submit"
              disabled={loading || loadFailed || !selectedMemberPid || tenantMembers.length === 0}
              className="bg-accent hover:bg-accent-hover rounded-control px-4 py-2 text-sm font-medium text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="team-members-confirm"
            >
              {text('add')}
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
  const text = useTeamText();
  const leader = role === 'leader';
  return (
    <span
      className={`rounded-pill inline-flex px-2.5 py-1 text-xs font-medium whitespace-nowrap ${
        leader ? 'bg-accent-weak text-accent' : 'bg-hover text-text-2'
      }`}
    >
      {leader ? text('leader') : text('member')}
    </span>
  );
}

function stringValue(value: unknown): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'number') return String(value);
  return '';
}

function formatDate(value: string | undefined, locale: string): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString(locale);
}

export default TeamMembersBlock;
