/**
 * Translation Workflow Page
 *
 * Allows reviewers to browse translations by status and locale,
 * then approve or reject them with feedback.
 * Accessible at /settings/i18n-workflow.
 */

import { useState, useEffect, useCallback } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import { workspacePageClassName } from '~/shared/layout/WorkspacePageLayout';

type MetaArgs = Record<string, unknown>;

export function meta({}: MetaArgs) {
  return [
    { title: 'Translation Workflow' },
    { name: 'description', content: 'Review and approve pending translations' },
  ];
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface I18nResource {
  pid: string;
  i18nKey: string;
  lang: string;
  value: string;
  source: string;
  status: string;
  rejectReason?: string;
  reviewedAt?: string;
  updatedAt?: string;
}

interface PagedResponse {
  records: I18nResource[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const LOCALES = [
  { code: 'ja-JP', label: '日本語' },
  { code: 'en-US', label: 'English (US)' },
  { code: 'ko-KR', label: '한국어' },
  { code: 'zh-CN', label: '简体中文' },
];

const STATUS_OPTIONS = [
  { value: '', label: 'All Statuses' },
  { value: 'draft', label: 'Draft' },
  { value: 'review', label: 'Pending Review' },
  { value: 'approved', label: 'Approved' },
  { value: 'deprecated', label: 'Deprecated' },
];

const STATUS_BADGE: Record<string, { bg: string; text: string; label: string }> = {
  draft: { bg: 'bg-gray-100', text: 'text-gray-600', label: 'Draft' },
  REVIEW: { bg: 'bg-amber-50', text: 'text-amber-700', label: 'Pending Review' },
  approved: { bg: 'bg-emerald-50', text: 'text-emerald-700', label: 'Approved' },
  deprecated: { bg: 'bg-red-50', text: 'text-red-600', label: 'Deprecated' },
};

// ---------------------------------------------------------------------------
// RejectModal component
// ---------------------------------------------------------------------------

interface RejectModalProps {
  resource: I18nResource;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
  submitting: boolean;
}

function RejectModal({ resource, onConfirm, onCancel, submitting }: RejectModalProps) {
  const [reason, setReason] = useState('');

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="mx-4 w-full max-w-md space-y-4 rounded-xl bg-white p-6 shadow-xl">
        <h3 className="text-lg font-semibold text-gray-900">Reject Translation</h3>
        <div className="space-y-1 text-sm text-gray-500">
          <p className="rounded bg-gray-50 px-2 py-1 font-mono text-xs">{resource.i18nKey}</p>
          <p className="text-gray-700 italic">"{resource.value}"</p>
        </div>
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">
            Rejection Reason <span className="text-red-500">*</span>
          </label>
          <textarea
            className="w-full resize-none rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-transparent focus:ring-2 focus:ring-red-400 focus:outline-none"
            rows={3}
            placeholder="Explain why the translation needs to be revised..."
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            autoFocus
          />
        </div>
        <div className="flex justify-end gap-3">
          <button
            onClick={onCancel}
            disabled={submitting}
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={() => onConfirm(reason)}
            disabled={submitting || !reason.trim()}
            className="rounded-lg bg-red-500 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-red-600 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {submitting ? 'Rejecting…' : 'Reject'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// StatusBadge component
// ---------------------------------------------------------------------------

function StatusBadge({ status }: { status: string }) {
  const badge = STATUS_BADGE[status] ?? { bg: 'bg-gray-100', text: 'text-gray-600', label: status };
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${badge.bg} ${badge.text}`}
    >
      {badge.label}
    </span>
  );
}

// ---------------------------------------------------------------------------
// AI Translate Modal component
// ---------------------------------------------------------------------------

interface AiTranslationResult {
  generated: number;
  skipped: number;
  errors: number;
  targetLocale: string;
  sourceLocale: string;
  llmUsed: boolean;
}

interface AiTranslateModalProps {
  onClose: () => void;
  onSuccess: () => void;
}

function AiTranslateModal({ onClose, onSuccess }: AiTranslateModalProps) {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [targetLocale, setTargetLocale] = useState('ja-JP');
  const [maxKeys, setMaxKeys] = useState(50);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<AiTranslationResult | null>(null);

  const AI_TARGET_LOCALES = [
    { code: 'ja-JP', label: '日本語 (ja-JP)' },
    { code: 'ko-KR', label: '한국어 (ko-KR)' },
    { code: 'en-US', label: 'English (en-US)' },
  ];

  async function handleRun() {
    setRunning(true);
    setResult(null);
    try {
      const res = await fetch('/api/admin/i18n/ai-translate', {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ targetLocale, sourceLocale: 'zh-CN', maxKeys }),
      });
      if (!res.ok) {
        const json = await res.json().catch(() => ({}));
        throw new Error((json as { message?: string }).message ?? `HTTP ${res.status}`);
      }
      const json = await res.json();
      const data: AiTranslationResult = json.data;
      setResult(data);
      showSuccessToast(
        `AI translation done — generated ${data.generated}, skipped ${data.skipped}`,
      );
      onSuccess();
    } catch (err: unknown) {
      showErrorToast(err instanceof Error ? err.message : String(err));
    } finally {
      setRunning(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="mx-4 w-full max-w-md space-y-5 rounded-xl bg-white p-6 shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-lg font-semibold text-gray-900">AI Generate Drafts</h3>
            <p className="mt-0.5 text-sm text-gray-500">
              Automatically generate draft translations for missing keys
            </p>
          </div>
          <button onClick={onClose} className="text-gray-400 transition-colors hover:text-gray-600">
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Form */}
        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Target Language</label>
            <select
              value={targetLocale}
              onChange={(e) => setTargetLocale(e.target.value)}
              disabled={running}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm focus:ring-2 focus:ring-purple-500 focus:outline-none disabled:opacity-50"
            >
              {AI_TARGET_LOCALES.map((l) => (
                <option key={l.code} value={l.code}>
                  {l.label}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Max Keys to Generate
            </label>
            <input
              type="number"
              min={1}
              max={200}
              value={maxKeys}
              onChange={(e) => setMaxKeys(Math.min(200, Math.max(1, Number(e.target.value))))}
              disabled={running}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-purple-500 focus:outline-none disabled:opacity-50"
            />
            <p className="mt-1 text-xs text-gray-400">
              Between 1 and 200. Each batch of 10 keys uses one LLM call.
            </p>
          </div>
        </div>

        {/* Result summary */}
        {result && (
          <div className="space-y-1 rounded-lg border border-gray-200 bg-gray-50 p-4 text-sm">
            <p className="font-medium text-gray-700">Last run results</p>
            <div className="flex gap-4 text-gray-600">
              <span className="font-semibold text-emerald-600">+{result.generated} generated</span>
              <span>{result.skipped} skipped</span>
              {result.errors > 0 && <span className="text-red-500">{result.errors} errors</span>}
            </div>
            <p className="text-xs text-gray-400">
              {result.llmUsed
                ? 'Used LLM provider'
                : 'Fallback: used source locale values as placeholder drafts'}
            </p>
          </div>
        )}

        {/* Actions */}
        <div className="flex justify-end gap-3 pt-1">
          <button
            onClick={onClose}
            disabled={running}
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-50"
          >
            Close
          </button>
          <button
            onClick={handleRun}
            disabled={running}
            className="flex items-center gap-2 rounded-lg bg-purple-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-purple-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {running ? (
              <>
                <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle
                    className="opacity-25"
                    cx="12"
                    cy="12"
                    r="10"
                    stroke="currentColor"
                    strokeWidth="4"
                  />
                  <path
                    className="opacity-75"
                    fill="currentColor"
                    d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                  />
                </svg>
                Generating…
              </>
            ) : (
              'Generate Drafts'
            )}
          </button>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main page component
// ---------------------------------------------------------------------------

export default function I18nWorkflowPage() {
  const { showSuccessToast, showErrorToast } = useToastContext();

  // Filters
  const [locale, setLocale] = useState('ja-JP');
  const [statusFilter, setStatusFilter] = useState('review');
  const [keyword, setKeyword] = useState('');

  // Data
  const [resources, setResources] = useState<I18nResource[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [loading, setLoading] = useState(true);

  // Action state
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [rejectTarget, setRejectTarget] = useState<I18nResource | null>(null);
  const [showAiModal, setShowAiModal] = useState(false);

  // -------------------------------------------------------------------------
  // Fetch resources
  // -------------------------------------------------------------------------

  const fetchResources = useCallback(
    async (page = 1) => {
      setLoading(true);
      try {
        const params = new URLSearchParams({
          pageNum: String(page),
          pageSize: '20',
          lang: locale,
        });
        if (statusFilter) params.set('status', statusFilter);
        if (keyword.trim()) params.set('keyword', keyword.trim());

        const res = await fetch(`/api/admin/i18n/resources?${params.toString()}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const json = await res.json();
        const paged: PagedResponse = json.data;
        setResources(paged.records ?? []);
        setTotal(paged.total ?? 0);
        setPageNum(page);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : String(err);
        showErrorToast(`Failed to load translations: ${msg}`);
      } finally {
        setLoading(false);
      }
    },
    [locale, statusFilter, keyword, showErrorToast],
  );

  useEffect(() => {
    fetchResources(1);
  }, [fetchResources]);

  // -------------------------------------------------------------------------
  // Workflow actions
  // -------------------------------------------------------------------------

  async function handleSubmitReview(pid: string) {
    setActionLoading(pid + ':submit');
    try {
      const res = await fetch(`/api/admin/i18n/resources/${pid}/submit-review`, { method: 'post' });
      if (!res.ok) {
        const json = await res.json().catch(() => ({}));
        throw new Error(json.message ?? `HTTP ${res.status}`);
      }
      showSuccessToast('Submitted for review');
      fetchResources(pageNum);
    } catch (err: unknown) {
      showErrorToast(err instanceof Error ? err.message : String(err));
    } finally {
      setActionLoading(null);
    }
  }

  async function handleApprove(pid: string) {
    setActionLoading(pid + ':approve');
    try {
      const res = await fetch(`/api/admin/i18n/resources/${pid}/approve`, { method: 'post' });
      if (!res.ok) {
        const json = await res.json().catch(() => ({}));
        throw new Error(json.message ?? `HTTP ${res.status}`);
      }
      showSuccessToast('Translation approved');
      fetchResources(pageNum);
    } catch (err: unknown) {
      showErrorToast(err instanceof Error ? err.message : String(err));
    } finally {
      setActionLoading(null);
    }
  }

  async function handleRejectConfirm(reason: string) {
    if (!rejectTarget) return;
    const pid = rejectTarget.pid;
    setActionLoading(pid + ':reject');
    try {
      const res = await fetch(`/api/admin/i18n/resources/${pid}/reject`, {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reason }),
      });
      if (!res.ok) {
        const json = await res.json().catch(() => ({}));
        throw new Error(json.message ?? `HTTP ${res.status}`);
      }
      setRejectTarget(null);
      showSuccessToast('Translation rejected and returned to draft');
      fetchResources(pageNum);
    } catch (err: unknown) {
      showErrorToast(err instanceof Error ? err.message : String(err));
    } finally {
      setActionLoading(null);
    }
  }

  // -------------------------------------------------------------------------
  // Render
  // -------------------------------------------------------------------------

  const pendingCount = resources.filter((r) => r.status === 'review').length;

  return (
    <div className={workspacePageClassName('contentPadded', 'space-y-6')}>
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">Translation Workflow</h1>
          <p className="mt-1 text-sm text-gray-500">
            Review and approve pending translations before they go live
          </p>
        </div>
        <div className="flex items-center gap-3">
          {statusFilter === 'review' && pendingCount > 0 && (
            <span className="inline-flex items-center rounded-full bg-amber-100 px-3 py-1 text-sm font-medium text-amber-800">
              {total} pending
            </span>
          )}
          <button
            onClick={() => setShowAiModal(true)}
            className="inline-flex items-center gap-2 rounded-lg bg-purple-600 px-4 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:bg-purple-700"
            title="Use AI to generate draft translations for missing keys"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M13 10V3L4 14h7v7l9-11h-7z"
              />
            </svg>
            AI Generate Drafts
          </button>
        </div>
      </div>

      {/* Filters */}
      <div className="rounded-xl border border-gray-200 bg-white p-4 shadow-sm">
        <div className="flex flex-wrap items-end gap-3">
          {/* Locale */}
          <div className="flex min-w-[160px] flex-col gap-1">
            <label className="text-xs font-medium tracking-wide text-gray-500 uppercase">
              Locale
            </label>
            <select
              value={locale}
              onChange={(e) => setLocale(e.target.value)}
              className="rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              {LOCALES.map((l) => (
                <option key={l.code} value={l.code}>
                  {l.label}
                </option>
              ))}
            </select>
          </div>

          {/* Status */}
          <div className="flex min-w-[180px] flex-col gap-1">
            <label className="text-xs font-medium tracking-wide text-gray-500 uppercase">
              Status
            </label>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              {STATUS_OPTIONS.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </div>

          {/* Keyword */}
          <div className="flex min-w-[200px] flex-1 flex-col gap-1">
            <label className="text-xs font-medium tracking-wide text-gray-500 uppercase">
              Search
            </label>
            <input
              type="text"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && fetchResources(1)}
              placeholder="Search by key or value…"
              className="rounded-lg border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>

          <button
            onClick={() => fetchResources(1)}
            disabled={loading}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
          >
            Search
          </button>
        </div>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm">
        {loading ? (
          <div className="animate-pulse space-y-3 p-8">
            {[1, 2, 3, 4, 5].map((i) => (
              <div key={i} className="h-12 rounded bg-gray-100" />
            ))}
          </div>
        ) : resources.length === 0 ? (
          <div className="p-12 text-center">
            <svg
              className="mx-auto mb-3 h-10 w-10 text-gray-300"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
              />
            </svg>
            <p className="font-medium text-gray-500">No translations found</p>
            <p className="mt-1 text-sm text-gray-400">
              {statusFilter === 'review'
                ? 'No pending translations to review. Great work!'
                : 'Try adjusting your filters.'}
            </p>
          </div>
        ) : (
          <>
            <div className="flex items-center justify-between border-b border-gray-100 bg-gray-50 px-5 py-3">
              <p className="text-xs text-gray-500">
                Showing <span className="font-medium">{resources.length}</span> of{' '}
                <span className="font-medium">{total}</span> translations
              </p>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-gray-100 text-left text-xs font-medium tracking-wide text-gray-500 uppercase">
                    <th className="w-2/5 px-5 py-3">Key</th>
                    <th className="w-1/4 px-5 py-3">Translation</th>
                    <th className="w-24 px-5 py-3">Status</th>
                    <th className="px-5 py-3">Notes</th>
                    <th className="px-5 py-3 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                  {resources.map((resource) => {
                    const isActing = actionLoading?.startsWith(resource.pid);
                    return (
                      <tr key={resource.pid} className="transition-colors hover:bg-gray-50">
                        {/* Key */}
                        <td className="px-5 py-3 font-mono text-xs break-all text-gray-600">
                          {resource.i18nKey}
                        </td>

                        {/* Value */}
                        <td className="px-5 py-3 text-gray-800">
                          <span className="line-clamp-2">{resource.value}</span>
                        </td>

                        {/* Status */}
                        <td className="px-5 py-3">
                          <StatusBadge status={resource.status} />
                        </td>

                        {/* Notes / reject reason */}
                        <td className="max-w-[200px] px-5 py-3 text-xs text-gray-500">
                          {resource.rejectReason ? (
                            <span
                              className="line-clamp-2 text-red-500"
                              title={resource.rejectReason}
                            >
                              ✗ {resource.rejectReason}
                            </span>
                          ) : resource.status === 'approved' && resource.reviewedAt ? (
                            <span className="text-emerald-600">Approved</span>
                          ) : null}
                        </td>

                        {/* Actions */}
                        <td className="px-5 py-3">
                          <div className="flex flex-wrap justify-end gap-2">
                            {resource.status === 'draft' && (
                              <button
                                onClick={() => handleSubmitReview(resource.pid)}
                                disabled={!!isActing}
                                className="rounded-lg bg-blue-50 px-3 py-1.5 text-xs font-medium text-blue-700 transition-colors hover:bg-blue-100 disabled:opacity-50"
                              >
                                {actionLoading === resource.pid + ':submit'
                                  ? 'Submitting…'
                                  : 'Submit for Review'}
                              </button>
                            )}
                            {resource.status === 'review' && (
                              <>
                                <button
                                  onClick={() => handleApprove(resource.pid)}
                                  disabled={!!isActing}
                                  className="rounded-lg bg-emerald-50 px-3 py-1.5 text-xs font-medium text-emerald-700 transition-colors hover:bg-emerald-100 disabled:opacity-50"
                                >
                                  {actionLoading === resource.pid + ':approve'
                                    ? 'Approving…'
                                    : 'Approve'}
                                </button>
                                <button
                                  onClick={() => setRejectTarget(resource)}
                                  disabled={!!isActing}
                                  className="rounded-lg bg-red-50 px-3 py-1.5 text-xs font-medium text-red-600 transition-colors hover:bg-red-100 disabled:opacity-50"
                                >
                                  Reject
                                </button>
                              </>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            {/* Pagination */}
            {total > 20 && (
              <div className="flex items-center justify-between border-t border-gray-100 bg-gray-50 px-5 py-3">
                <button
                  onClick={() => fetchResources(pageNum - 1)}
                  disabled={pageNum <= 1 || loading}
                  className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm text-gray-600 transition-colors hover:bg-gray-100 disabled:opacity-40"
                >
                  Previous
                </button>
                <span className="text-sm text-gray-500">
                  Page {pageNum} of {Math.ceil(total / 20)}
                </span>
                <button
                  onClick={() => fetchResources(pageNum + 1)}
                  disabled={pageNum >= Math.ceil(total / 20) || loading}
                  className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm text-gray-600 transition-colors hover:bg-gray-100 disabled:opacity-40"
                >
                  Next
                </button>
              </div>
            )}
          </>
        )}
      </div>

      {/* Reject modal */}
      {rejectTarget && (
        <RejectModal
          resource={rejectTarget}
          onConfirm={handleRejectConfirm}
          onCancel={() => setRejectTarget(null)}
          submitting={actionLoading?.startsWith(rejectTarget.pid + ':reject') ?? false}
        />
      )}

      {/* AI Translate modal */}
      {showAiModal && (
        <AiTranslateModal
          onClose={() => setShowAiModal(false)}
          onSuccess={() => fetchResources(1)}
        />
      )}
    </div>
  );
}
