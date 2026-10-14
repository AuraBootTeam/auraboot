import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useNavigate, useFetcher } from 'react-router';
import {
  ArrowLeftIcon,
  UserIcon,
  BuildingOfficeIcon,
  UserGroupIcon,
  ClockIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useAuth } from '~/contexts/AuthContext';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import { authorizationMethodCodes, authorizationMethodLabel } from './member-detail-labels';
import FormDialog from '~/framework/meta/runtime/actions/FormDialog';

// --- Types ---

interface UserInfo {
  pid: string;
  username: string;
  email: string;
  phone: string | null;
  realName: string | null;
  avatar: string | null;
}

interface MemberData {
  pid: string;
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

interface ImpersonationAuditRecord {
  sessionPid: string;
  operatorDisplayName: string;
  authorizationMethod: string;
  reason: string;
  reference: string | null;
  clientType: string | null;
  status: 'active' | 'ended' | 'expired';
  startedAt: string;
  expiresAt: string;
  endedAt: string | null;
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

const MEMBER_STATUS_LABELS: Record<string, [string, string]> = {
  active: ['已激活', 'Active'], pending: ['待审批', 'Pending approval'],
  suspended: ['已暂停', 'Suspended'], rejected: ['已拒绝', 'Rejected'],
  inactive: ['已离职', 'Inactive'],
};
function memberStatusLabel(status: string, l: (zh: string, en: string) => string) {
  const label = MEMBER_STATUS_LABELS[status];
  return label ? l(label[0], label[1]) : l('未知状态', 'Unknown status');
}

// --- Main Component ---

export default function MemberDetailPage() {
  const { memberPid } = useParams();
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();
  const { locale, t } = useI18n();
  const { token, hasPermission } = useAuth();
  const impersonationFetcher = useFetcher<{ ok?: boolean; error?: string }>();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [member, setMember] = useState<MemberData | null>(null);
  const [employee, setEmployee] = useState<EmployeeData | null>(null);
  const [teams, setTeams] = useState<TeamMembership[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<'forbidden' | 'not-found' | 'error' | null>(null);
  const [activeTab, setActiveTab] = useState<'basic' | 'org' | 'teams' | 'accessHistory'>('basic');
  const [accessHistory, setAccessHistory] = useState<ImpersonationAuditRecord[]>([]);
  const [historyLoaded, setHistoryLoaded] = useState(false);
  const [historyFailed, setHistoryFailed] = useState(false);
  const [showImpersonationDialog, setShowImpersonationDialog] = useState(false);
  const [reasonRequired, setReasonRequired] = useState(false);

  useEffect(() => {
    if (impersonationFetcher.data?.ok) window.location.assign('/');
  }, [impersonationFetcher.data]);

  const actionRefreshTarget = useRef<'detail' | 'list'>('detail');

  const loadData = useCallback(async () => {
    if (!memberPid) return;
    setLoading(true);
    setLoadError(null);
    setMember(null);
    setEmployee(null);
    setTeams([]);
    try {
      // 1. Fetch member info
      const memberResult = await get<MemberData>(`/api/tenant/members/${memberPid}`);
      if (!ResultHelper.isSuccess(memberResult) || !memberResult.data) {
        const status = Number(memberResult.httpStatus ?? memberResult.code);
        setLoadError(status === 403 ? 'forbidden' : status === 404 ? 'not-found' : 'error');
        return;
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
    } catch {
      setLoadError('error');
    } finally {
      setLoading(false);
    }
  }, [memberPid, showErrorToast, l]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const canReadAccessHistory = Boolean(member?.pid === memberPid && member?.user && hasPermission('admin.customer.impersonate'));
  useEffect(() => {
    setAccessHistory([]);
    setHistoryLoaded(false);
    setHistoryFailed(false);
    if (!memberPid || activeTab !== 'accessHistory' || !canReadAccessHistory) return;
    let current = true;
    void (async () => {
      try {
        const result = await get<ImpersonationAuditRecord[]>('/api/impersonation-sessions/history', {
          targetMemberPid: memberPid,
          limit: '50',
        });
        if (!current) return;
        if (!ResultHelper.isSuccess(result) || !Array.isArray(result.data)) {
          setHistoryFailed(true);
          return;
        }
        setAccessHistory(result.data);
      } catch {
        if (current) setHistoryFailed(true);
      } finally {
        if (current) setHistoryLoaded(true);
      }
    })();
    return () => { current = false; };
  }, [activeTab, memberPid, canReadAccessHistory]);

  // Existing native detail delegates to the same action pipeline as the DSL list.
  const { handleAction, loading: actionLoading } = useActionHandler({
    navigate,
    tableName: 'tenant_member',
    locale,
    t,
    token: token || undefined,
    context: {
      loadData: async () => {
        if (actionRefreshTarget.current === 'list') navigate('/p/tenant_member');
        else await loadData();
      },
    },
    showToast: (message, type) => {
      if (type === 'error') showErrorToast(message);
      else if (type === 'warning') showWarningToast(message);
      else if (type === 'success') showSuccessToast(message);
      else showInfoToast(message);
    },
  });

  const canPerform = (verb: string) =>
    hasPermission('meta.command.execute') && hasPermission(`model.tenant_member.${verb}`);

  const dispatchMemberAction = async (action: string, command: string, offboardingAction?: string) => {
    if (!member || actionLoading) return;
    const confirmations: Record<string, { 'zh-CN': string; en: string }> = {
      approve: { 'zh-CN': '确认审批通过该成员？', en: 'Approve this member?' },
      reject: { 'zh-CN': '确认拒绝该成员？', en: 'Reject this member?' },
      suspend: { 'zh-CN': '确认暂停该成员？', en: 'Suspend this member?' },
      restore: { 'zh-CN': '确认恢复该成员？', en: 'Restore this member?' },
      leave: { 'zh-CN': '确认该成员离职？', en: 'Mark this member as inactive?' },
      delete: { 'zh-CN': '确认移除该成员并交接资源？', en: 'Remove this member and transfer resources?' },
    };
    const inputFields = action === 'suspend' || action === 'leave' ? [{
      field: 'reason',
      label: { 'zh-CN': action === 'suspend' ? '暂停原因' : '离职说明', en: 'Reason' },
      type: 'textarea',
      required: action === 'suspend',
      placeholder: action === 'suspend'
        ? { 'zh-CN': '请说明暂停原因，例如临时停用账号', en: 'Explain why this account needs to be suspended' }
        : { 'zh-CN': '请填写离职说明，便于管理员了解背景', en: 'Describe the offboarding context for administrators' },
      helpText: action === 'suspend'
        ? { 'zh-CN': '暂停后，该成员的所有登录会话将立即失效。', en: 'Suspending the member immediately invalidates all their sign-in sessions.' }
        : { 'zh-CN': '可补充离职背景；涉及的资源交接将在提交前确认。', en: 'You may add context; resource transfers are confirmed before submission.' },
    }] : [];
    actionRefreshTarget.current = action === 'delete' ? 'list' : 'detail';
    try {
      await handleAction({
        code: action,
        confirm: confirmations[action],
        confirmVariant: action === 'approve' || action === 'restore' ? 'default' : 'danger',
        action: {
          type: 'command', command, offboardingAction, inputFields,
          inputFieldsTitle: action === 'suspend'
            ? l('暂停成员', 'Suspend member')
            : action === 'leave' ? l('办理离职', 'Offboard member') : undefined,
        },
      } as ButtonConfig, member);
    } finally {
      actionRefreshTarget.current = 'detail';
    }
  };

  const doApprove = () => dispatchMemberAction('approve', 'admin:approve_member');
  const doReject = () => dispatchMemberAction('reject', 'admin:reject_member');
  const doSuspend = () => dispatchMemberAction('suspend', 'admin:suspend_member', 'suspend');
  const doRestore = () => dispatchMemberAction('restore', 'admin:restore_member');
  const doLeave = () => dispatchMemberAction('leave', 'admin:leave_member', 'deactivate');
  const doDelete = () => dispatchMemberAction('delete', 'admin:delete_member', 'remove');

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
      <div className="p-6 py-20 text-center text-gray-500 dark:text-gray-400" data-testid="member-load-error" data-error-kind={loadError}>
        <p>{loadError === 'forbidden'
          ? l('无权查看此成员', 'You do not have permission to view this member')
          : loadError === 'error'
            ? l('加载成员信息失败，请重试。', 'Failed to load member information. Please try again.')
            : l('成员不存在', 'Member not found')}</p>
        <button type="button" className="mt-4 text-blue-600 hover:underline" onClick={() => navigate('/p/tenant_member')}>
          {l('返回成员列表', 'Back to members')}
        </button>
      </div>
    );
  }

  const statusStyle = STATUS_STYLES[member.status] || STATUS_STYLES.inactive;
  const displayName =
    employee?.org_emp_name ||
    member.user?.realName ||
    member.user?.username ||
    member.user?.email ||
    l('未命名成员', 'Unnamed member');
  const accountName = member.user?.username || member.user?.email || l('未设置账号名称', 'Account name not set');
  const avatarText = (displayName || accountName).charAt(0).toUpperCase();

  const tabs = [
    { key: 'basic' as const, label: l('基本信息', 'Basic Info'), icon: UserIcon },
    { key: 'org' as const, label: l('组织信息', 'Organization'), icon: BuildingOfficeIcon },
    { key: 'teams' as const, label: l('团队', 'Teams'), icon: UserGroupIcon, count: teams.length },
    ...(canReadAccessHistory
      ? [{ key: 'accessHistory' as const, label: l('代登录记录', 'Access history'), icon: ClockIcon }]
      : []),
  ];

  return (
    <div className="mx-auto w-full max-w-7xl px-6 py-8">
      {/* Header */}
      <div className="mb-6 rounded-2xl border border-gray-200 bg-white p-6 shadow-sm dark:border-gray-700 dark:bg-gray-900">
        <div className="flex items-start gap-5">
        <button
          onClick={() => navigate('/p/tenant_member')}
          className="mt-1 rounded-lg p-2 text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-800 dark:hover:text-gray-300"
          data-testid="back-btn"
        >
          <ArrowLeftIcon className="h-5 w-5" />
        </button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-4">
            {/* Avatar */}
            <div className="flex h-14 w-14 flex-shrink-0 items-center justify-center rounded-full bg-blue-100 text-xl font-bold text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
              {avatarText}
            </div>
            <div className="min-w-0">
              <h1
                className="max-w-4xl truncate text-3xl font-semibold tracking-tight text-gray-950 dark:text-white"
                data-testid="member-name"
              >
                {displayName}
              </h1>
              <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-gray-500 dark:text-gray-400">
                <span className="font-medium text-gray-700 dark:text-gray-300">{accountName}</span>
                {member.user?.email && member.user.email !== accountName && <span>{member.user.email}</span>}
                {member.user?.phone && <span>{member.user.phone}</span>}
              </p>
            </div>
            <span
              className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ${statusStyle.bg} ${statusStyle.text}`}
              data-testid="member-status"
              data-status={member.status}
            >
              {memberStatusLabel(member.status, l)}
            </span>
          </div>
        </div>
        </div>
      </div>

      {/* Action buttons */}
      <div className="mb-6 flex flex-wrap gap-2" data-testid="action-bar">
        {member.status === 'active' && member.user && hasPermission('admin.customer.impersonate') && (
          <ActionButton onClick={() => { setReasonRequired(false); setShowImpersonationDialog(true); }} disabled={actionLoading || impersonationFetcher.state !== 'idle'} variant="primary">
            {l('代客户登录', 'Access as customer')}
          </ActionButton>
        )}
        {member.status === 'pending' && (
          <>
            {canPerform('approve') && (<ActionButton onClick={doApprove} disabled={actionLoading} variant="primary">
              {l('审批通过', 'Approve')}
            </ActionButton>)}
            {canPerform('reject') && (<ActionButton onClick={doReject} disabled={actionLoading} variant="danger">
              {l('拒绝', 'Reject')}
            </ActionButton>)}
          </>
        )}
        {member.status === 'active' && (
          <>
            {canPerform('suspend') && (<ActionButton onClick={doSuspend} disabled={actionLoading} variant="warning">
              {l('暂停', 'Suspend')}
            </ActionButton>)}
            {canPerform('leave') && (<ActionButton onClick={doLeave} disabled={actionLoading} variant="danger">
              {l('离职', 'Leave')}
            </ActionButton>)}
          </>
        )}
        {(member.status === 'suspended' || member.status === 'rejected') && canPerform('restore') && (
          <ActionButton onClick={doRestore} disabled={actionLoading} variant="primary">
            {l('恢复', 'Restore')}
          </ActionButton>
        )}
        {canPerform('delete') && (<ActionButton onClick={doDelete} disabled={actionLoading} variant="danger-outline">
          {l('删除', 'Delete')}
        </ActionButton>)}
      </div>

      {showImpersonationDialog && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4"
          role="presentation"
          onMouseDown={(event) => {
            if (event.currentTarget === event.target) setShowImpersonationDialog(false);
          }}
        >
          <div
            className="w-full max-w-lg rounded-2xl bg-white p-6 shadow-2xl dark:bg-gray-900"
            role="dialog"
            aria-modal="true"
            aria-labelledby="impersonation-dialog-title"
            data-testid="impersonation-dialog"
          >
            <h2
              id="impersonation-dialog-title"
              className="text-xl font-semibold text-gray-950 dark:text-white"
            >
              {l('代客户登录', 'Access as customer')}
            </h2>
            <p className="mt-2 text-sm text-gray-600 dark:text-gray-300">
              {l(
                `将以 ${displayName} 的权限操作 30 分钟。请记录客户授权方式和本次用途。`,
                `You will use ${displayName}'s permissions for 30 minutes. Record the customer's authorization and purpose.`,
              )}
            </p>
            <impersonationFetcher.Form
              method="post"
              action="/_action/start-impersonation"
              onSubmit={(event) => {
                const reason = event.currentTarget.elements.namedItem('reason');
                if (reason instanceof HTMLTextAreaElement && !reason.value.trim()) {
                  event.preventDefault();
                  setReasonRequired(true);
                  reason.focus();
                }
              }}
              className="mt-5 space-y-4"
            >
              <input type="hidden" name="targetMemberPid" value={member.pid} />
              <label className="block">
                <span className="text-sm font-medium text-gray-800 dark:text-gray-200">
                  {l('授权方式', 'Authorization method')}
                </span>
                <select
                  name="authorizationMethod"
                  required
                  defaultValue="offline"
                  className="mt-1.5 w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20 dark:border-gray-700 dark:bg-gray-800 dark:text-white"
                >
                  {authorizationMethodCodes.map((method) => (
                    <option key={method} value={method}>{authorizationMethodLabel(method, l)}</option>
                  ))}
                </select>
              </label>
              <label className="block">
                <span className="text-sm font-medium text-gray-800 dark:text-gray-200">
                  {l('操作原因', 'Reason')}
                </span>
                <textarea
                  name="reason"
                  required
                  aria-invalid={reasonRequired || undefined}
                  aria-describedby={reasonRequired ? 'impersonation-reason-error' : undefined}
                  onInvalid={(event) => {
                    event.preventDefault();
                    setReasonRequired(true);
                    event.currentTarget.focus();
                  }}
                  onChange={(event) => {
                    if (event.currentTarget.value.trim()) setReasonRequired(false);
                  }}
                  maxLength={500}
                  rows={3}
                  placeholder={l('例如：协助客户检查订单状态', 'For example: help review an order')}
                  className="mt-1.5 w-full resize-none rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 placeholder:text-gray-400 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20 dark:border-gray-700 dark:bg-gray-800 dark:text-white"
                />
              </label>
              {reasonRequired && (
                <p id="impersonation-reason-error" role="alert" className="text-sm font-medium text-red-600 dark:text-red-400">
                  {l('请填写本次代客户登录的操作原因。', 'Enter the reason for accessing this customer account.')}
                </p>
              )}
              <label className="block">
                <span className="text-sm font-medium text-gray-800 dark:text-gray-200">
                  {l('授权凭据说明（选填）', 'Authorization reference (optional)')}
                </span>
                <input
                  name="reference"
                  maxLength={200}
                  placeholder={l('例如：9 月 24 日门店现场授权', 'For example: in-store approval on Sep 24')}
                  className="mt-1.5 w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 placeholder:text-gray-400 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20 dark:border-gray-700 dark:bg-gray-800 dark:text-white"
                />
              </label>
              {impersonationFetcher.data?.error && (
                <p className="text-sm font-medium text-red-600 dark:text-red-400" role="alert">
                  {impersonationFetcher.data.error}
                </p>
              )}
              <div className="flex justify-end gap-3 pt-1">
                <button
                  type="button"
                  onClick={() => setShowImpersonationDialog(false)}
                  disabled={impersonationFetcher.state !== 'idle'}
                  className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-60 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-800"
                >
                  {l('取消', 'Cancel')}
                </button>
                <button
                  type="submit"
                  disabled={impersonationFetcher.state !== 'idle'}
                  className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
                  data-testid="confirm-impersonation"
                >
                  {impersonationFetcher.state !== 'idle'
                    ? l('正在进入…', 'Starting…')
                    : l('确认并进入', 'Confirm and continue')}
                </button>
              </div>
            </impersonationFetcher.Form>
          </div>
        </div>
      )}

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
      <div
        className="rounded-2xl border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-900"
        data-testid="tab-content"
      >
        {activeTab === 'basic' && <BasicInfoTab member={member} displayName={displayName} l={l} />}
        {activeTab === 'org' && <OrgInfoTab employee={employee} l={l} />}
        {activeTab === 'teams' && <TeamsTab teams={teams} l={l} navigate={navigate} />}
        {activeTab === 'accessHistory' && canReadAccessHistory && (
          <AccessHistoryTab records={accessHistory} loaded={historyLoaded} failed={historyFailed} l={l} />
        )}
      </div>
      <FormDialog />
    </div>
  );
}

function AccessHistoryTab({
  records,
  loaded,
  failed,
  l,
}: {
  records: ImpersonationAuditRecord[];
  loaded: boolean;
  failed: boolean;
  l: (zh: string, en: string) => string;
}) {
  if (failed) {
    return <div role="alert" className="p-6 py-12 text-center text-red-600 dark:text-red-400">{l('无法加载代登录记录，请刷新页面后重试。', 'Could not load customer-access history. Refresh the page to try again.')}</div>;
  }
  if (!loaded) {
    return <div className="p-6 py-12 text-center text-gray-500 dark:text-gray-400">{l('正在加载…', 'Loading…')}</div>;
  }
  if (records.length === 0) {
    return (
      <div className="p-6 py-12 text-center text-gray-500 dark:text-gray-400">
        <ClockIcon className="mx-auto mb-3 h-10 w-10 opacity-40" />
        <p>{l('暂无代登录记录', 'No customer-access history')}</p>
      </div>
    );
  }
  const statusText: Record<ImpersonationAuditRecord['status'], string> = {
    active: l('进行中', 'Active'),
    ended: l('已结束', 'Ended'),
    expired: l('已到期', 'Expired'),
  };
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
        <thead className="bg-gray-50 dark:bg-gray-900">
          <tr>
            {[l('操作人', 'Operator'), l('授权方式', 'Authorization'), l('操作原因', 'Reason'), l('开始时间', 'Started'), l('状态', 'Status')].map((label) => (
              <th key={label} className="px-6 py-3 text-left text-xs font-medium uppercase text-gray-500 dark:text-gray-400">{label}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
          {records.map((record) => (
            <tr key={record.sessionPid}>
              <td className="px-6 py-4 text-sm font-medium text-gray-900 dark:text-white">{record.operatorDisplayName}</td>
              <td className="px-6 py-4 text-sm text-gray-700 dark:text-gray-300">{authorizationMethodLabel(record.authorizationMethod, l)}</td>
              <td className="max-w-sm px-6 py-4 text-sm text-gray-700 dark:text-gray-300">
                <p>{record.reason}</p>
                {record.reference && <p className="mt-1 text-xs text-gray-500">{record.reference}</p>}
              </td>
              <td className="px-6 py-4 text-sm text-gray-700 dark:text-gray-300">{formatDateTime(record.startedAt)}</td>
              <td className="px-6 py-4 text-sm text-gray-700 dark:text-gray-300">{statusText[record.status]}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// --- Tab Components ---

function BasicInfoTab({
  member,
  displayName,
  l,
}: {
  member: MemberData;
  displayName: string;
  l: (zh: string, en: string) => string;
}) {
  const fields = [
    { label: l('姓名', 'Name'), value: displayName },
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
              {f.isStatus ? <StatusBadge status={f.value || ''} label={memberStatusLabel(f.value || '', l)} /> : f.value || '-'}
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
              {f.isStatus ? <StatusBadge l={l} status={f.value || ''} /> : f.value || '-'}
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

function StatusBadge({ status, label, l }: { status: string; label?: string; l?: (zh: string, en: string) => string }) {
  const style = STATUS_STYLES[status] || STATUS_STYLES.inactive;
  return (
    <span
      className={`inline-flex rounded-full px-2 py-0.5 text-xs font-semibold ${style.bg} ${style.text}`}
    >
      {label ?? (l ? memberStatusLabel(status, l) : status)}
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
