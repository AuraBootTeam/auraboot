import { useState, useEffect, useCallback } from 'react';
import {
  ShieldCheckIcon,
  ClockIcon,
  CheckCircleIcon,
  XCircleIcon,
  EyeIcon,
  DocumentTextIcon,
  ArrowPathIcon,
  FunnelIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  PlusIcon,
  TrashIcon,
  Cog6ToothIcon,
} from '@heroicons/react/24/outline';
import { useToken as useAuthToken } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';
import { fetchResult } from '~/shared/services/http-client/HttpClient';

// ---- Types ----

interface ChangeRequest {
  pid: string;
  requestNumber: string | null;
  entityType: string;
  entityPid: string;
  changeType: string;
  proposedData: Record<string, unknown>;
  originalData: Record<string, unknown> | null;
  status: string;
  submittedByPid: string;
  reviewedByPid: string | null;
  reviewComment: string | null;
  appliedByPid: string | null;
  createdAt: string;
  updatedAt: string;
  reviewedAt: string | null;
  appliedAt: string | null;
}

interface VersionEntry {
  pid: string;
  entityType: string;
  entityPid: string;
  versionNumber: number;
  snapshotData: Record<string, unknown>;
  changeRequestPid: string | null;
  createdByPid: string;
  comment: string | null;
  createdAt: string;
}

interface GovernanceStats {
  totalChangeRequests: number;
  pendingRequests: number;
  approvedRequests: number;
  rejectedRequests: number;
  totalVersionedEntities: number;
  totalVersionSnapshots: number;
}

interface PolicyEntry {
  pid: string;
  modelCode: string;
  requireApproval: boolean;
  autoSnapshot: boolean;
  approvalChainId: number | null;
  allowedEditors: string[] | null;
  createdAt: string;
  updatedAt: string;
}

interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
}

// ---- Status Badge ----

function StatusBadge({ status }: { status: string }) {
  const styles: Record<string, string> = {
    draft: 'bg-gray-100 text-gray-700',
    pending: 'bg-yellow-100 text-yellow-800',
    PENDING_REVIEW: 'bg-yellow-100 text-yellow-800',
    approved: 'bg-green-100 text-green-800',
    APPLIED: 'bg-blue-100 text-blue-800',
    rejected: 'bg-red-100 text-red-800',
    cancelled: 'bg-gray-100 text-gray-500',
  };
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${styles[status] || 'bg-gray-100 text-gray-600'}`}
    >
      {status}
    </span>
  );
}

function ChangeTypeBadge({ type }: { type: string }) {
  const styles: Record<string, string> = {
    CREATE: 'bg-blue-100 text-blue-800',
    UPDATE: 'bg-amber-100 text-amber-800',
    DELETE: 'bg-red-100 text-red-800',
    BULK_UPDATE: 'bg-purple-100 text-purple-800',
  };
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${styles[type] || 'bg-gray-100 text-gray-600'}`}
    >
      {type}
    </span>
  );
}

// ---- Stat Card ----

function StatCard({
  label,
  value,
  icon: Icon,
  color,
}: {
  label: string;
  value: number;
  icon: React.ElementType;
  color: string;
}) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
      <div className="flex items-center gap-3">
        <div className={`rounded-lg p-2 ${color}`}>
          <Icon className="h-5 w-5 text-white" />
        </div>
        <div>
          <p className="text-sm text-gray-500">{label}</p>
          <p className="text-2xl font-semibold text-gray-900">{value}</p>
        </div>
      </div>
    </div>
  );
}

// ---- JSON Viewer ----

function JsonViewer({ data, title }: { data: Record<string, unknown> | null; title: string }) {
  const [expanded, setExpanded] = useState(false);

  if (!data || Object.keys(data).length === 0) {
    return <span className="text-sm text-gray-400">No data</span>;
  }

  return (
    <div className="mt-1">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800"
      >
        {expanded ? (
          <ChevronDownIcon className="h-3 w-3" />
        ) : (
          <ChevronRightIcon className="h-3 w-3" />
        )}
        {title} ({Object.keys(data).length} fields)
      </button>
      {expanded && (
        <pre className="mt-1 max-h-48 overflow-auto rounded bg-gray-50 p-2 text-xs text-gray-700">
          {JSON.stringify(data, null, 2)}
        </pre>
      )}
    </div>
  );
}

// ---- Main Component ----

export default function GovernancePage() {
  const token = useAuthToken();
  const { t: i18nT } = useI18n();
  const t = (key: string, fallback?: string | Record<string, string>): string => {
    const result = i18nT(key);
    if (result === key && typeof fallback === 'string') return fallback;
    return result;
  };
  const [activeTab, setActiveTab] = useState<'requests' | 'versions' | 'policies'>('requests');
  const [stats, setStats] = useState<GovernanceStats | null>(null);
  const [requests, setRequests] = useState<ChangeRequest[]>([]);
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [pageNum, setPageNum] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);

  // Version lookup
  const [vEntityType, setVEntityType] = useState('');
  const [vEntityPid, setVEntityPid] = useState('');
  const [versions, setVersions] = useState<VersionEntry[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);

  // Policies
  const [policies, setPolicies] = useState<PolicyEntry[]>([]);
  const [policiesLoading, setPoliciesLoading] = useState(false);
  const [showPolicyForm, setShowPolicyForm] = useState(false);
  const [policyForm, setPolicyForm] = useState({
    modelCode: '',
    requireApproval: false,
    autoSnapshot: false,
  });

  // Review modal
  const [reviewTarget, setReviewTarget] = useState<ChangeRequest | null>(null);
  const [reviewAction, setReviewAction] = useState<'approved' | 'rejected'>('approved');
  const [reviewComment, setReviewComment] = useState('');

  const pageSize = 10;

  // ---- Fetch Stats ----
  const fetchStats = useCallback(async () => {
    if (!token) return;
    const result = await fetchResult<GovernanceStats>('/api/governance/stats', { token });
    if (result.code === '0' && result.data) {
      setStats(result.data);
    }
  }, [token]);

  // ---- Fetch Change Requests ----
  const fetchRequests = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const params: Record<string, string> = {
        pageNum: String(pageNum),
        pageSize: String(pageSize),
      };
      if (statusFilter) params.status = statusFilter;

      const result = await fetchResult<PageResult<ChangeRequest>>(
        '/api/governance/change-requests',
        {
          token,
          params,
        },
      );
      if (result.code === '0' && result.data) {
        setRequests(result.data.records || []);
        setTotal(result.data.total || 0);
      }
    } finally {
      setLoading(false);
    }
  }, [token, pageNum, statusFilter]);

  // ---- Fetch Versions ----
  const fetchVersions = useCallback(async () => {
    if (!token || !vEntityType || !vEntityPid) return;
    setVersionsLoading(true);
    try {
      const result = await fetchResult<VersionEntry[]>('/api/governance/versions', {
        token,
        params: { entityType: vEntityType, entityPid: vEntityPid },
      });
      if (result.code === '0' && result.data) {
        setVersions(result.data);
      }
    } finally {
      setVersionsLoading(false);
    }
  }, [token, vEntityType, vEntityPid]);

  // ---- Fetch Policies ----
  const fetchPolicies = useCallback(async () => {
    if (!token) return;
    setPoliciesLoading(true);
    try {
      const result = await fetchResult<PolicyEntry[]>('/api/governance/policies', { token });
      if (result.code === '0' && result.data) {
        setPolicies(result.data);
      }
    } finally {
      setPoliciesLoading(false);
    }
  }, [token]);

  // ---- Action Handlers ----
  const handleSubmitForReview = async (pid: string) => {
    if (!token) return;
    await fetchResult<ChangeRequest>(`/api/governance/change-requests/${pid}/submit`, {
      method: 'post',
      token,
    });
    fetchRequests();
    fetchStats();
  };

  const handleReview = async () => {
    if (!token || !reviewTarget) return;
    const result = await fetchResult<ChangeRequest>(
      `/api/governance/change-requests/${reviewTarget.pid}/review`,
      {
        method: 'post',
        token,
        params: { action: reviewAction, comment: reviewComment },
      },
    );
    if (result.code === '0') {
      setReviewTarget(null);
      setReviewComment('');
      fetchRequests();
      fetchStats();
    }
  };

  const handleApply = async (pid: string) => {
    if (!token) return;
    await fetchResult<ChangeRequest>(`/api/governance/change-requests/${pid}/apply`, {
      method: 'post',
      token,
    });
    fetchRequests();
    fetchStats();
  };

  const handleSavePolicy = async () => {
    if (!token || !policyForm.modelCode) return;
    await fetchResult<PolicyEntry>('/api/governance/policies', {
      method: 'post',
      token,
      params: policyForm,
    });
    setShowPolicyForm(false);
    setPolicyForm({ modelCode: '', requireApproval: false, autoSnapshot: false });
    fetchPolicies();
  };

  const handleDeletePolicy = async (pid: string) => {
    if (!token) return;
    await fetchResult<void>(`/api/governance/policies/${pid}`, {
      method: 'delete',
      token,
    });
    fetchPolicies();
  };

  useEffect(() => {
    fetchStats();
  }, [fetchStats]);

  useEffect(() => {
    if (activeTab === 'requests') {
      fetchRequests();
    } else if (activeTab === 'policies') {
      fetchPolicies();
    }
  }, [activeTab, fetchRequests, fetchPolicies]);

  const totalPages = Math.ceil(total / pageSize);

  return (
    <div className="min-h-screen bg-gray-50 p-6">
      {/* Header */}
      <div className="mb-6">
        <h1 className="flex items-center gap-2 text-2xl font-bold text-gray-900">
          <ShieldCheckIcon className="h-7 w-7 text-indigo-600" />
          {t('governance.title', 'Master Data Governance')}
        </h1>
        <p className="mt-1 text-sm text-gray-500">
          {t(
            'governance.subtitle',
            'Manage change requests, track version history, and configure governance policies.',
          )}
        </p>
      </div>

      {/* Stats Cards */}
      {stats && (
        <div className="mb-6 grid grid-cols-2 gap-4 md:grid-cols-4 lg:grid-cols-6">
          <StatCard
            label={t('governance.stats.totalRequests', 'Total Requests')}
            value={stats.totalChangeRequests}
            icon={DocumentTextIcon}
            color="bg-indigo-500"
          />
          <StatCard
            label={t('governance.stats.pending', 'Pending')}
            value={stats.pendingRequests}
            icon={ClockIcon}
            color="bg-yellow-500"
          />
          <StatCard
            label={t('governance.stats.approved', 'Approved')}
            value={stats.approvedRequests}
            icon={CheckCircleIcon}
            color="bg-green-500"
          />
          <StatCard
            label={t('governance.stats.rejected', 'Rejected')}
            value={stats.rejectedRequests}
            icon={XCircleIcon}
            color="bg-red-500"
          />
          <StatCard
            label={t('governance.stats.versionedEntities', 'Versioned Entities')}
            value={stats.totalVersionedEntities}
            icon={ShieldCheckIcon}
            color="bg-blue-500"
          />
          <StatCard
            label={t('governance.stats.totalSnapshots', 'Total Snapshots')}
            value={stats.totalVersionSnapshots}
            icon={EyeIcon}
            color="bg-purple-500"
          />
        </div>
      )}

      {/* Tabs */}
      <div className="mb-4 flex gap-1 rounded-lg bg-gray-100 p-1">
        {(['requests', 'versions', 'policies'] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`flex-1 rounded-md px-4 py-2 text-sm font-medium transition ${
              activeTab === tab
                ? 'bg-white text-gray-900 shadow-sm'
                : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            {tab === 'requests'
              ? t('governance.tabs.changeRequests', 'Change Requests')
              : tab === 'versions'
                ? t('governance.tabs.versionHistory', 'Version History')
                : t('governance.tabs.policies', 'Policies')}
          </button>
        ))}
      </div>

      {/* ========== Change Requests Tab ========== */}
      {activeTab === 'requests' && (
        <div className="rounded-lg border border-gray-200 bg-white shadow-sm">
          {/* Filter bar */}
          <div className="flex items-center gap-3 border-b border-gray-200 px-4 py-3">
            <FunnelIcon className="h-4 w-4 text-gray-400" />
            <select
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value);
                setPageNum(1);
              }}
              className="rounded border border-gray-300 px-2 py-1 text-sm"
            >
              <option value="">{t('governance.filter.allStatuses', 'All Statuses')}</option>
              <option value="draft">{t('governance.filter.draft', 'Draft')}</option>
              <option value="pending">{t('governance.filter.pending', 'Pending Review')}</option>
              <option value="approved">{t('governance.filter.approved', 'Approved')}</option>
              <option value="applied">{t('governance.filter.applied', 'Applied')}</option>
              <option value="rejected">{t('governance.filter.rejected', 'Rejected')}</option>
              <option value="cancelled">{t('governance.filter.cancelled', 'Cancelled')}</option>
            </select>
            <button
              onClick={fetchRequests}
              className="ml-auto rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
            >
              <ArrowPathIcon className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            </button>
          </div>

          {/* Table */}
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-gray-200 bg-gray-50 text-xs text-gray-500 uppercase">
                <tr>
                  <th className="px-4 py-3">{t('governance.table.number', 'Number')}</th>
                  <th className="px-4 py-3">{t('governance.table.entity', 'Entity')}</th>
                  <th className="px-4 py-3">{t('governance.table.change', 'Change')}</th>
                  <th className="px-4 py-3">{t('governance.table.status', 'Status')}</th>
                  <th className="px-4 py-3">{t('governance.table.submitter', 'Submitter')}</th>
                  <th className="px-4 py-3">{t('governance.table.created', 'Created')}</th>
                  <th className="px-4 py-3">{t('governance.table.data', 'Data')}</th>
                  <th className="px-4 py-3">{t('governance.table.actions', 'Actions')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {requests.map((cr) => (
                  <tr key={cr.pid} className="hover:bg-gray-50">
                    <td className="px-4 py-3">
                      <span className="font-mono text-xs text-indigo-600">
                        {cr.requestNumber || cr.pid.substring(0, 8)}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="font-medium text-gray-900">{cr.entityType}</div>
                      <div className="text-xs text-gray-400">{cr.entityPid || 'N/A'}</div>
                    </td>
                    <td className="px-4 py-3">
                      <ChangeTypeBadge type={cr.changeType} />
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={cr.status} />
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-500">{cr.submittedByPid}</td>
                    <td className="px-4 py-3 text-xs text-gray-500">
                      {cr.createdAt ? new Date(cr.createdAt).toLocaleString() : '-'}
                    </td>
                    <td className="px-4 py-3">
                      <JsonViewer
                        data={cr.proposedData}
                        title={t('governance.data.proposed', 'Proposed')}
                      />
                      {cr.originalData && (
                        <JsonViewer
                          data={cr.originalData}
                          title={t('governance.data.original', 'Original')}
                        />
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-col gap-1">
                        {cr.status === 'draft' && (
                          <button
                            onClick={() => handleSubmitForReview(cr.pid)}
                            className="rounded bg-blue-600 px-2 py-1 text-xs text-white hover:bg-blue-700"
                          >
                            {t('governance.action.submitForReview', 'Submit')}
                          </button>
                        )}
                        {cr.status === 'pending' && (
                          <button
                            onClick={() => setReviewTarget(cr)}
                            className="rounded bg-indigo-600 px-2 py-1 text-xs text-white hover:bg-indigo-700"
                          >
                            {t('governance.action.review', 'Review')}
                          </button>
                        )}
                        {cr.status === 'approved' && (
                          <button
                            onClick={() => handleApply(cr.pid)}
                            className="rounded bg-green-600 px-2 py-1 text-xs text-white hover:bg-green-700"
                          >
                            {t('governance.action.apply', 'Apply')}
                          </button>
                        )}
                        {cr.reviewComment && cr.status !== 'draft' && cr.status !== 'pending' && (
                          <span className="text-xs text-gray-400" title={cr.reviewComment}>
                            {cr.reviewComment.substring(0, 30)}
                            {cr.reviewComment.length > 30 ? '...' : ''}
                          </span>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
                {requests.length === 0 && !loading && (
                  <tr>
                    <td colSpan={8} className="px-4 py-8 text-center text-sm text-gray-400">
                      {t('governance.empty.requests', 'No change requests found')}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="flex items-center justify-between border-t border-gray-200 px-4 py-3">
              <span className="text-xs text-gray-500">
                {`Page ${pageNum} of ${totalPages} (${total} total)`}
              </span>
              <div className="flex gap-1">
                <button
                  disabled={pageNum <= 1}
                  onClick={() => setPageNum(pageNum - 1)}
                  className="rounded border px-2 py-1 text-xs disabled:opacity-50"
                >
                  {t('governance.action.previous', 'Previous')}
                </button>
                <button
                  disabled={pageNum >= totalPages}
                  onClick={() => setPageNum(pageNum + 1)}
                  className="rounded border px-2 py-1 text-xs disabled:opacity-50"
                >
                  {t('governance.action.next', 'Next')}
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* ========== Version History Tab ========== */}
      {activeTab === 'versions' && (
        <div className="rounded-lg border border-gray-200 bg-white shadow-sm">
          {/* Search bar */}
          <div className="flex items-center gap-3 border-b border-gray-200 px-4 py-3">
            <input
              type="text"
              placeholder={t('governance.versions.entityType', 'Entity Type (model code)')}
              value={vEntityType}
              onChange={(e) => setVEntityType(e.target.value)}
              className="rounded border border-gray-300 px-3 py-1.5 text-sm"
            />
            <input
              type="text"
              placeholder={t('governance.versions.entityPid', 'Entity PID')}
              value={vEntityPid}
              onChange={(e) => setVEntityPid(e.target.value)}
              className="rounded border border-gray-300 px-3 py-1.5 text-sm"
            />
            <button
              onClick={fetchVersions}
              disabled={!vEntityType || !vEntityPid}
              className="rounded bg-indigo-600 px-3 py-1.5 text-sm text-white hover:bg-indigo-700 disabled:opacity-50"
            >
              {t('governance.action.search', 'Search')}
            </button>
          </div>

          {/* Version Timeline */}
          <div className="p-4">
            {versionsLoading && (
              <div className="flex justify-center py-8">
                <ArrowPathIcon className="h-6 w-6 animate-spin text-gray-400" />
              </div>
            )}
            {!versionsLoading && versions.length === 0 && (
              <p className="py-8 text-center text-sm text-gray-400">
                {vEntityType && vEntityPid
                  ? t('governance.versions.notFound', 'No versions found for this entity')
                  : t(
                      'governance.versions.prompt',
                      'Enter entity type and PID to search version history',
                    )}
              </p>
            )}
            {!versionsLoading && versions.length > 0 && (
              <div className="space-y-4">
                {versions.map((v) => (
                  <div key={v.pid} className="rounded border border-gray-200 p-4">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <span className="rounded-full bg-indigo-100 px-2 py-0.5 text-xs font-semibold text-indigo-700">
                          v{v.versionNumber}
                        </span>
                        <span className="text-sm text-gray-700">
                          {v.comment || t('governance.versions.noComment', 'No comment')}
                        </span>
                      </div>
                      <span className="text-xs text-gray-400">
                        {v.createdAt ? new Date(v.createdAt).toLocaleString() : '-'}
                      </span>
                    </div>
                    <div className="mt-2 text-xs text-gray-500">
                      {t('governance.versions.createdBy', 'Created by')}:{' '}
                      {v.createdByPid || 'System'}
                      {v.changeRequestPid && <span className="ml-3">CR: {v.changeRequestPid}</span>}
                    </div>
                    <JsonViewer
                      data={v.snapshotData}
                      title={t('governance.versions.snapshotData', 'Snapshot Data')}
                    />
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* ========== Policies Tab ========== */}
      {activeTab === 'policies' && (
        <div className="rounded-lg border border-gray-200 bg-white shadow-sm">
          <div className="flex items-center justify-between border-b border-gray-200 px-4 py-3">
            <div className="flex items-center gap-2">
              <Cog6ToothIcon className="h-4 w-4 text-gray-400" />
              <span className="text-sm font-medium text-gray-700">
                {t('governance.policies.title', 'Governance Policies')}
              </span>
            </div>
            <button
              onClick={() => setShowPolicyForm(true)}
              className="flex items-center gap-1 rounded bg-indigo-600 px-3 py-1.5 text-xs text-white hover:bg-indigo-700"
            >
              <PlusIcon className="h-3.5 w-3.5" />
              {t('governance.policies.add', 'Add Policy')}
            </button>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-gray-200 bg-gray-50 text-xs text-gray-500 uppercase">
                <tr>
                  <th className="px-4 py-3">{t('governance.policies.modelCode', 'Model Code')}</th>
                  <th className="px-4 py-3">
                    {t('governance.policies.requireApproval', 'Require Approval')}
                  </th>
                  <th className="px-4 py-3">
                    {t('governance.policies.autoSnapshot', 'Auto Snapshot')}
                  </th>
                  <th className="px-4 py-3">{t('governance.policies.updatedAt', 'Updated')}</th>
                  <th className="px-4 py-3">{t('governance.policies.actions', 'Actions')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {policies.map((p) => (
                  <tr key={p.pid} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium text-gray-900">{p.modelCode}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${p.requireApproval ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}
                      >
                        {p.requireApproval
                          ? t('governance.policies.yes', 'Yes')
                          : t('governance.policies.no', 'No')}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${p.autoSnapshot ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}
                      >
                        {p.autoSnapshot
                          ? t('governance.policies.yes', 'Yes')
                          : t('governance.policies.no', 'No')}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-500">
                      {p.updatedAt ? new Date(p.updatedAt).toLocaleString() : '-'}
                    </td>
                    <td className="px-4 py-3">
                      <button
                        onClick={() => handleDeletePolicy(p.pid)}
                        className="rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-600"
                      >
                        <TrashIcon className="h-4 w-4" />
                      </button>
                    </td>
                  </tr>
                ))}
                {policies.length === 0 && !policiesLoading && (
                  <tr>
                    <td colSpan={5} className="px-4 py-8 text-center text-sm text-gray-400">
                      {t('governance.empty.policies', 'No governance policies configured')}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* ========== Review Modal ========== */}
      {reviewTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl">
            <h3 className="text-lg font-semibold text-gray-900">
              {t('governance.review.title', 'Review Change Request')}
            </h3>
            <p className="mt-1 text-sm text-gray-500">
              {reviewTarget.requestNumber && (
                <span className="mr-2 font-mono">{reviewTarget.requestNumber}</span>
              )}
              {reviewTarget.changeType} on {reviewTarget.entityType} /{' '}
              {reviewTarget.entityPid || 'new'}
            </p>

            <div className="mt-4">
              <JsonViewer
                data={reviewTarget.proposedData}
                title={t('governance.review.proposedChanges', 'Proposed Changes')}
              />
              {reviewTarget.originalData && (
                <JsonViewer
                  data={reviewTarget.originalData}
                  title={t('governance.review.originalData', 'Original Data')}
                />
              )}
            </div>

            <div className="mt-4">
              <label className="block text-sm font-medium text-gray-700">
                {t('governance.review.decision', 'Decision')}
              </label>
              <div className="mt-1 flex gap-3">
                <label className="flex items-center gap-1 text-sm">
                  <input
                    type="radio"
                    name="review-action"
                    checked={reviewAction === 'approved'}
                    onChange={() => setReviewAction('approved')}
                  />
                  {t('governance.review.approve', 'Approve')}
                </label>
                <label className="flex items-center gap-1 text-sm">
                  <input
                    type="radio"
                    name="review-action"
                    checked={reviewAction === 'rejected'}
                    onChange={() => setReviewAction('rejected')}
                  />
                  {t('governance.review.reject', 'Reject')}
                </label>
              </div>
            </div>

            <div className="mt-3">
              <label className="block text-sm font-medium text-gray-700">
                {t('governance.review.comment', 'Comment')}
              </label>
              <textarea
                value={reviewComment}
                onChange={(e) => setReviewComment(e.target.value)}
                rows={3}
                className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
                placeholder={t(
                  'governance.review.commentPlaceholder',
                  'Optional review comment...',
                )}
              />
            </div>

            <div className="mt-4 flex justify-end gap-2">
              <button
                onClick={() => {
                  setReviewTarget(null);
                  setReviewComment('');
                }}
                className="rounded border border-gray-300 px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
              >
                {t('governance.action.cancel', 'Cancel')}
              </button>
              <button
                onClick={handleReview}
                className={`rounded px-4 py-2 text-sm text-white ${
                  reviewAction === 'approved'
                    ? 'bg-green-600 hover:bg-green-700'
                    : 'bg-red-600 hover:bg-red-700'
                }`}
              >
                {reviewAction === 'approved'
                  ? t('governance.review.approve', 'Approve')
                  : t('governance.review.reject', 'Reject')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ========== Policy Form Modal ========== */}
      {showPolicyForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
            <h3 className="text-lg font-semibold text-gray-900">
              {t('governance.policies.addTitle', 'Add Governance Policy')}
            </h3>

            <div className="mt-4 space-y-3">
              <div>
                <label className="block text-sm font-medium text-gray-700">
                  {t('governance.policies.modelCode', 'Model Code')}
                </label>
                <input
                  type="text"
                  value={policyForm.modelCode}
                  onChange={(e) => setPolicyForm({ ...policyForm, modelCode: e.target.value })}
                  className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
                  placeholder="e.g., product, material, customer"
                />
              </div>
              <div className="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="requireApproval"
                  checked={policyForm.requireApproval}
                  onChange={(e) =>
                    setPolicyForm({ ...policyForm, requireApproval: e.target.checked })
                  }
                />
                <label htmlFor="requireApproval" className="text-sm text-gray-700">
                  {t('governance.policies.requireApprovalLabel', 'Require approval for changes')}
                </label>
              </div>
              <div className="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="autoSnapshot"
                  checked={policyForm.autoSnapshot}
                  onChange={(e) => setPolicyForm({ ...policyForm, autoSnapshot: e.target.checked })}
                />
                <label htmlFor="autoSnapshot" className="text-sm text-gray-700">
                  {t(
                    'governance.policies.autoSnapshotLabel',
                    'Automatically create version snapshots',
                  )}
                </label>
              </div>
            </div>

            <div className="mt-4 flex justify-end gap-2">
              <button
                onClick={() => {
                  setShowPolicyForm(false);
                  setPolicyForm({ modelCode: '', requireApproval: false, autoSnapshot: false });
                }}
                className="rounded border border-gray-300 px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
              >
                {t('governance.action.cancel', 'Cancel')}
              </button>
              <button
                onClick={handleSavePolicy}
                disabled={!policyForm.modelCode}
                className="rounded bg-indigo-600 px-4 py-2 text-sm text-white hover:bg-indigo-700 disabled:opacity-50"
              >
                {t('governance.policies.save', 'Save Policy')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
