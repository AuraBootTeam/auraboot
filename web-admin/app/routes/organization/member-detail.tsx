import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router';
import {
  ArrowLeftIcon,
  UserIcon,
  BuildingOfficeIcon,
  UserGroupIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import { get, post, put, del } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';

// --- Types ---

interface UserInfo {
  id: number;
  pid: string;
  username: string;
  email: string;
  phone: string | null;
  realName: string | null;
  avatar: string | null;
}

interface MemberData {
  pid: string;
  userId: number;
  status: string;
  joinDate: string | null;
  leaveDate: string | null;
  createdAt: string;
  updatedAt: string;
  user: UserInfo | null;
}

interface EmployeeData {
  pid: string;
  org_emp_name: string;
  org_emp_code: string;
  org_emp_dept_id: string | null;
  org_emp_position_id: string | null;
  org_emp_report_to: string | null;
  org_emp_phone: string | null;
  org_emp_email: string | null;
  org_emp_hire_date: string | null;
  org_emp_status: string;
  // resolved display names
  org_emp_dept_id_display?: string;
  org_emp_position_id_display?: string;
  org_emp_report_to_display?: string;
}

interface TeamMembership {
  teamPid: string;
  teamName: string;
  teamCode: string;
  role: string;
  joinedAt: string;
}

// --- Status config ---

const STATUS_STYLES: Record<string, { bg: string; text: string }> = {
  active: { bg: 'bg-green-100 dark:bg-green-900/30', text: 'text-green-800 dark:text-green-300' },
  pending: {
    bg: 'bg-yellow-100 dark:bg-yellow-900/30',
    text: 'text-yellow-800 dark:text-yellow-300',
  },
  suspended: { bg: 'bg-red-100 dark:bg-red-900/30', text: 'text-red-800 dark:text-red-300' },
  rejected: { bg: 'bg-gray-100 dark:bg-gray-700', text: 'text-gray-800 dark:text-gray-300' },
  inactive: { bg: 'bg-gray-100 dark:bg-gray-700', text: 'text-gray-600 dark:text-gray-400' },
};

// --- Main Component ---

export default function MemberDetailPage() {
  const { memberPid } = useParams();
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [member, setMember] = useState<MemberData | null>(null);
  const [employee, setEmployee] = useState<EmployeeData | null>(null);
  const [teams, setTeams] = useState<TeamMembership[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<'basic' | 'org' | 'teams'>('basic');
  const [actionLoading, setActionLoading] = useState(false);

  const loadData = useCallback(async () => {
    if (!memberPid) return;
    setLoading(true);
    try {
      // 1. Fetch member info
      const memberResult = await get<MemberData>(`/api/tenant/members/${memberPid}`);
      if (!ResultHelper.isSuccess(memberResult) || !memberResult.data) {
        throw new Error(memberResult.desc || 'Failed to load member');
      }
      const m = memberResult.data;
      setMember(m);

      // 2. Fetch employee info (if user has a pid)
      if (m.user?.pid) {
        try {
          const empResult = await get<any>('/api/dynamic/org-employee/list', {
            filters: JSON.stringify([
              { fieldName: 'org_emp_user_id', operator: 'EQ', value: m.user.pid },
            ]),
            pageSize: '1',
          });
          if (ResultHelper.isSuccess(empResult) && empResult.data?.records?.length > 0) {
            setEmployee(empResult.data.records[0]);
          }
        } catch {
          // org-employee may not exist for this user
        }
      }

      // 3. Fetch team memberships
      try {
        const teamsResult = await get<TeamMembership[]>(`/api/tenant/members/${memberPid}/teams`);
        if (ResultHelper.isSuccess(teamsResult) && teamsResult.data) {
          setTeams(teamsResult.data);
        }
      } catch {
        // teams may be empty
      }
    } catch (e: any) {
      showErrorToast(e.message || l('加载成员信息失败', 'Failed to load member info'));
    } finally {
      setLoading(false);
    }
  }, [memberPid, showErrorToast, l]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // --- Action handlers ---

  const handleAction = async (action: string, apiCall: () => Promise<any>) => {
    const confirmMessages: Record<string, string> = {
      approve: l('确认审批通过该成员？', 'Approve this member?'),
      reject: l('确认拒绝该成员？', 'Reject this member?'),
      suspend: l('确认暂停该成员？', 'Suspend this member?'),
      restore: l('确认恢复该成员？', 'Restore this member?'),
      leave: l('确认该成员离职？', 'Mark this member as inactive?'),
      delete: l('确认删除该成员？此操作不可逆。', 'Delete this member? This cannot be undone.'),
    };
    if (!confirm(confirmMessages[action] || `Confirm ${action}?`)) return;

    setActionLoading(true);
    try {
      await apiCall();
      showSuccessToast(l('操作成功', 'Action completed'));
      loadData();
    } catch (e: any) {
      showErrorToast(e.message || l('操作失败', 'Action failed'));
    } finally {
      setActionLoading(false);
    }
  };

  const doApprove = () =>
    handleAction('approve', () =>
      post(`/api/tenant/members/${memberPid}/approve`, { action: 'approve' }),
    );

  const doReject = () =>
    handleAction('reject', () =>
      post(`/api/tenant/members/${memberPid}/approve`, { action: 'reject' }),
    );

  const doSuspend = () =>
    handleAction('suspend', () =>
      put(`/api/tenant/members/${memberPid}/status`, { action: 'suspended' }),
    );

  const doRestore = () =>
    handleAction('restore', () =>
      put(`/api/tenant/members/${memberPid}/status`, { action: 'active' }),
    );

  const doLeave = () =>
    handleAction('leave', () =>
      put(`/api/tenant/members/${memberPid}/status`, { action: 'inactive' }),
    );

  const doDelete = () =>
    handleAction('delete', async () => {
      await del(`/api/tenant/members/${memberPid}`);
      navigate('/dynamic/tenant-member');
    });

  // --- Render ---

  if (loading) {
    return (
      <div className="flex justify-center p-6 py-20">
        <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
      </div>
    );
  }

  if (!member) {
    return (
      <div className="p-6 py-20 text-center text-gray-500 dark:text-gray-400">
        {l('成员不存在', 'Member not found')}
      </div>
    );
  }

  const statusStyle = STATUS_STYLES[member.status] || STATUS_STYLES.INACTIVE;
  const userName =
    member.user?.realName ||
    member.user?.username ||
    member.user?.email ||
    `User #${member.userId}`;

  const tabs = [
    { key: 'basic' as const, label: l('基本信息', 'Basic Info'), icon: UserIcon },
    { key: 'org' as const, label: l('组织信息', 'Organization'), icon: BuildingOfficeIcon },
    { key: 'teams' as const, label: l('团队', 'Teams'), icon: UserGroupIcon, count: teams.length },
  ];

  return (
    <div className="mx-auto max-w-4xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-start gap-4">
        <button
          onClick={() => navigate('/dynamic/tenant-member')}
          className="mt-1 rounded-lg p-2 text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-700 dark:hover:text-gray-300"
          data-testid="back-btn"
        >
          <ArrowLeftIcon className="h-5 w-5" />
        </button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-3">
            {/* Avatar */}
            <div className="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-full bg-blue-100 text-lg font-bold text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
              {userName.charAt(0).toUpperCase()}
            </div>
            <div>
              <h1
                className="text-2xl font-bold text-gray-900 dark:text-white"
                data-testid="member-name"
              >
                {userName}
              </h1>
              <p className="text-sm text-gray-500 dark:text-gray-400">
                {member.user?.email}
                {member.user?.phone && ` · ${member.user.phone}`}
              </p>
            </div>
            <span
              className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ${statusStyle.bg} ${statusStyle.text}`}
              data-testid="member-status"
            >
              {member.status}
            </span>
          </div>
        </div>
      </div>

      {/* Action buttons */}
      <div className="mb-6 flex flex-wrap gap-2" data-testid="action-bar">
        {member.status === 'pending' && (
          <>
            <ActionButton onClick={doApprove} disabled={actionLoading} variant="primary">
              {l('审批通过', 'Approve')}
            </ActionButton>
            <ActionButton onClick={doReject} disabled={actionLoading} variant="danger">
              {l('拒绝', 'Reject')}
            </ActionButton>
          </>
        )}
        {member.status === 'active' && (
          <>
            <ActionButton onClick={doSuspend} disabled={actionLoading} variant="warning">
              {l('暂停', 'Suspend')}
            </ActionButton>
            <ActionButton onClick={doLeave} disabled={actionLoading} variant="danger">
              {l('离职', 'Leave')}
            </ActionButton>
          </>
        )}
        {(member.status === 'suspended' || member.status === 'rejected') && (
          <ActionButton onClick={doRestore} disabled={actionLoading} variant="primary">
            {l('恢复', 'Restore')}
          </ActionButton>
        )}
        <ActionButton onClick={doDelete} disabled={actionLoading} variant="danger-outline">
          {l('删除', 'Delete')}
        </ActionButton>
      </div>

      {/* Tabs */}
      <div className="mb-6 border-b border-gray-200 dark:border-gray-700">
        <nav className="-mb-px flex gap-6">
          {tabs.map((tab) => {
            const isActive = activeTab === tab.key;
            const Icon = tab.icon;
            return (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key)}
                className={`flex items-center gap-2 border-b-2 px-1 pb-3 text-sm font-medium transition-colors ${
                  isActive
                    ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                    : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
                }`}
                data-testid={`tab-${tab.key}`}
              >
                <Icon className="h-4 w-4" />
                {tab.label}
                {tab.count !== undefined && (
                  <span className="ml-1 rounded-full bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600 dark:bg-gray-700 dark:text-gray-400">
                    {tab.count}
                  </span>
                )}
              </button>
            );
          })}
        </nav>
      </div>

      {/* Tab Content */}
      <div className="rounded-lg bg-white shadow dark:bg-gray-800" data-testid="tab-content">
        {activeTab === 'basic' && <BasicInfoTab member={member} l={l} />}
        {activeTab === 'org' && <OrgInfoTab employee={employee} l={l} />}
        {activeTab === 'teams' && <TeamsTab teams={teams} l={l} navigate={navigate} />}
      </div>
    </div>
  );
}

// --- Tab Components ---

function BasicInfoTab({
  member,
  l,
}: {
  member: MemberData;
  l: (zh: string, en: string) => string;
}) {
  const fields = [
    { label: l('用户名', 'Username'), value: member.user?.username },
    { label: l('邮箱', 'Email'), value: member.user?.email },
    { label: l('手机', 'Phone'), value: member.user?.phone },
    { label: l('状态', 'Status'), value: member.status, isStatus: true },
    {
      label: l('加入日期', 'Join Date'),
      value: member.joinDate ? formatDate(member.joinDate) : '-',
    },
    {
      label: l('离开日期', 'Leave Date'),
      value: member.leaveDate ? formatDate(member.leaveDate) : '-',
    },
    {
      label: l('创建时间', 'Created At'),
      value: member.createdAt ? formatDateTime(member.createdAt) : '-',
    },
    {
      label: l('更新时间', 'Updated At'),
      value: member.updatedAt ? formatDateTime(member.updatedAt) : '-',
    },
  ];

  return (
    <div className="p-6">
      <dl className="grid grid-cols-1 gap-x-8 gap-y-5 sm:grid-cols-2">
        {fields.map((f, i) => (
          <div key={i}>
            <dt className="text-sm font-medium text-gray-500 dark:text-gray-400">{f.label}</dt>
            <dd className="mt-1 text-sm text-gray-900 dark:text-white">
              {f.isStatus ? <StatusBadge status={f.value || ''} /> : f.value || '-'}
            </dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function OrgInfoTab({
  employee,
  l,
}: {
  employee: EmployeeData | null;
  l: (zh: string, en: string) => string;
}) {
  if (!employee) {
    return (
      <div className="p-6 py-12 text-center text-gray-500 dark:text-gray-400">
        <BuildingOfficeIcon className="mx-auto mb-3 h-10 w-10 opacity-40" />
        <p>{l('暂无组织信息', 'No organization info')}</p>
        <p className="mt-1 text-xs">
          {l('该成员尚未关联员工档案', 'This member has no employee record linked')}
        </p>
      </div>
    );
  }

  const fields = [
    { label: l('姓名', 'Name'), value: employee.org_emp_name },
    { label: l('工号', 'Employee Code'), value: employee.org_emp_code },
    {
      label: l('部门', 'Department'),
      value: employee.org_emp_dept_id_display || employee.org_emp_dept_id,
    },
    {
      label: l('岗位', 'Position'),
      value: employee.org_emp_position_id_display || employee.org_emp_position_id,
    },
    {
      label: l('汇报对象', 'Reports To'),
      value: employee.org_emp_report_to_display || employee.org_emp_report_to,
    },
    { label: l('手机', 'Phone'), value: employee.org_emp_phone },
    { label: l('邮箱', 'Email'), value: employee.org_emp_email },
    { label: l('入职日期', 'Hire Date'), value: employee.org_emp_hire_date },
    { label: l('状态', 'Status'), value: employee.org_emp_status, isStatus: true },
  ];

  return (
    <div className="p-6">
      <dl className="grid grid-cols-1 gap-x-8 gap-y-5 sm:grid-cols-2">
        {fields.map((f, i) => (
          <div key={i}>
            <dt className="text-sm font-medium text-gray-500 dark:text-gray-400">{f.label}</dt>
            <dd className="mt-1 text-sm text-gray-900 dark:text-white">
              {f.isStatus ? <StatusBadge status={f.value || ''} /> : f.value || '-'}
            </dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function TeamsTab({
  teams,
  l,
  navigate,
}: {
  teams: TeamMembership[];
  l: (zh: string, en: string) => string;
  navigate: (path: string) => void;
}) {
  if (teams.length === 0) {
    return (
      <div className="p-6 py-12 text-center text-gray-500 dark:text-gray-400">
        <UserGroupIcon className="mx-auto mb-3 h-10 w-10 opacity-40" />
        <p>{l('暂未加入任何团队', 'Not a member of any team')}</p>
      </div>
    );
  }

  return (
    <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
      <thead className="bg-gray-50 dark:bg-gray-900">
        <tr>
          <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase dark:text-gray-400">
            {l('团队', 'Team')}
          </th>
          <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase dark:text-gray-400">
            {l('编码', 'Code')}
          </th>
          <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase dark:text-gray-400">
            {l('角色', 'Role')}
          </th>
          <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase dark:text-gray-400">
            {l('加入时间', 'Joined')}
          </th>
        </tr>
      </thead>
      <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
        {teams.map((t) => (
          <tr
            key={t.teamPid}
            className="dark:hover:bg-gray-750 cursor-pointer hover:bg-gray-50"
            onClick={() => navigate(`/organization/teams/${t.teamPid}`)}
          >
            <td className="px-6 py-4 text-sm font-medium text-blue-600 hover:underline dark:text-blue-400">
              {t.teamName}
            </td>
            <td className="px-6 py-4 text-sm text-gray-500 dark:text-gray-400">{t.teamCode}</td>
            <td className="px-6 py-4">
              <span
                className={`inline-flex rounded-full px-2 py-1 text-xs font-semibold ${
                  t.role === 'leader'
                    ? 'bg-purple-100 text-purple-800 dark:bg-purple-900/30 dark:text-purple-300'
                    : 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-300'
                }`}
              >
                {t.role}
              </span>
            </td>
            <td className="px-6 py-4 text-sm text-gray-500 dark:text-gray-400">
              {t.joinedAt ? formatDateTime(t.joinedAt) : '-'}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// --- Shared Components ---

function StatusBadge({ status }: { status: string }) {
  const style = STATUS_STYLES[status] || STATUS_STYLES.INACTIVE;
  return (
    <span
      className={`inline-flex rounded-full px-2 py-0.5 text-xs font-semibold ${style.bg} ${style.text}`}
    >
      {status}
    </span>
  );
}

function ActionButton({
  onClick,
  disabled,
  variant,
  children,
}: {
  onClick: () => void;
  disabled: boolean;
  variant: 'primary' | 'danger' | 'warning' | 'danger-outline';
  children: React.ReactNode;
}) {
  const styles: Record<string, string> = {
    primary: 'bg-blue-600 text-white hover:bg-blue-700',
    danger: 'bg-red-600 text-white hover:bg-red-700',
    warning: 'bg-yellow-500 text-white hover:bg-yellow-600',
    'danger-outline':
      'border border-red-300 dark:border-red-700 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20',
  };

  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`rounded-lg px-3 py-1.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${styles[variant]}`}
    >
      {children}
    </button>
  );
}

// --- Utils ---

function formatDate(dateStr: string): string {
  try {
    return new Date(dateStr).toLocaleDateString();
  } catch {
    return dateStr;
  }
}

function formatDateTime(dateStr: string): string {
  try {
    return new Date(dateStr).toLocaleString();
  } catch {
    return dateStr;
  }
}
