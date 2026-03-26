/**
 * Translation Workflow Page
 *
 * Allows reviewers to browse translations by status and locale,
 * then approve or reject them with feedback.
 * Accessible at /settings/i18n-workflow.
 */

import { useState, useEffect, useCallback } from 'react';
import { useToastContext } from '~/contexts/ToastContext';

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
      <div className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4 p-6 space-y-4">
        <h3 className="text-lg font-semibold text-gray-900">Reject Translation</h3>
        <div className="text-sm text-gray-500 space-y-1">
          <p className="font-mono text-xs bg-gray-50 px-2 py-1 rounded">{resource.i18nKey}</p>
          <p className="text-gray-700 italic">"{resource.value}"</p>
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            Rejection Reason <span className="text-red-500">*</span>
          </label>
          <textarea
            className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-red-400 focus:border-transparent resize-none"
            rows={3}
            placeholder="Explain why the translation needs to be revised..."
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            autoFocus
          />
        </div>
        <div className="flex gap-3 justify-end">
          <button
            onClick={onCancel}
            disabled={submitting}
            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 disabled:opacity-50 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={() => onConfirm(reason)}
            disabled={submitting || !reason.trim()}
            className="px-4 py-2 text-sm font-medium text-white bg-red-500 rounded-lg hover:bg-red-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
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
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${badge.bg} ${badge.text}`}>
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
        `AI translation done — generated ${data.generated}, skipped ${data.skipped}`
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
      <div className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4 p-6 space-y-5">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-lg font-semibold text-gray-900">AI Generate Drafts</h3>
            <p className="text-sm text-gray-500 mt-0.5">
              Automatically generate draft translations for missing keys
            </p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 transition-colors">
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Form */}
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Target Language
            </label>
            <select
              value={targetLocale}
              onChange={(e) => setTargetLocale(e.target.value)}
              disabled={running}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500 bg-white disabled:opacity-50"
            >
              {AI_TARGET_LOCALES.map((l) => (
                <option key={l.code} value={l.code}>{l.label}</option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Max Keys to Generate
            </label>
            <input
              type="number"
              min={1}
              max={200}
              value={maxKeys}
              onChange={(e) => setMaxKeys(Math.min(200, Math.max(1, Number(e.target.value))))}
              disabled={running}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500 disabled:opacity-50"
            />
            <p className="text-xs text-gray-400 mt-1">Between 1 and 200. Each batch of 10 keys uses one LLM call.</p>
          </div>
        </div>

        {/* Result summary */}
        {result && (
          <div className="bg-gray-50 border border-gray-200 rounded-lg p-4 text-sm space-y-1">
            <p className="font-medium text-gray-700">Last run results</p>
            <div className="flex gap-4 text-gray-600">
              <span className="text-emerald-600 font-semibold">+{result.generated} generated</span>
              <span>{result.skipped} skipped</span>
              {result.errors > 0 && <span className="text-red-500">{result.errors} errors</span>}
            </div>
            <p className="text-xs text-gray-400">
              {result.llmUsed ? 'Used LLM provider' : 'Fallback: used source locale values as placeholder drafts'}
            </p>
          </div>
        )}

        {/* Actions */}
        <div className="flex gap-3 justify-end pt-1">
          <button
            onClick={onClose}
            disabled={running}
            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 disabled:opacity-50 transition-colors"
          >
            Close
          </button>
          <button
            onClick={handleRun}
            disabled={running}
            className="px-4 py-2 text-sm font-medium text-white bg-purple-600 rounded-lg hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-2"
          >
            {running ? (
              <>
                <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
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

  const fetchResources = useCallback(async (page = 1) => {
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
  }, [locale, statusFilter, keyword, showErrorToast]);

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
    <div className="p-6 max-w-6xl mx-auto space-y-6">
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
            <span className="inline-flex items-center px-3 py-1 rounded-full text-sm font-medium bg-amber-100 text-amber-800">
              {total} pending
            </span>
          )}
          <button
            onClick={() => setShowAiModal(true)}
            className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-purple-600 rounded-lg hover:bg-purple-700 transition-colors shadow-sm"
            title="Use AI to generate draft translations for missing keys"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
            AI Generate Drafts
          </button>
        </div>
      </div>

      {/* Filters */}
      <div className="bg-white border border-gray-200 rounded-xl p-4 shadow-sm">
        <div className="flex flex-wrap gap-3 items-end">
          {/* Locale */}
          <div className="flex flex-col gap-1 min-w-[160px]">
            <label className="text-xs font-medium text-gray-500 uppercase tracking-wide">Locale</label>
            <select
              value={locale}
              onChange={(e) => setLocale(e.target.value)}
              className="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white"
            >
              {LOCALES.map((l) => (
                <option key={l.code} value={l.code}>{l.label}</option>
              ))}
            </select>
          </div>

          {/* Status */}
          <div className="flex flex-col gap-1 min-w-[180px]">
            <label className="text-xs font-medium text-gray-500 uppercase tracking-wide">Status</label>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white"
            >
              {STATUS_OPTIONS.map((s) => (
                <option key={s.value} value={s.value}>{s.label}</option>
              ))}
            </select>
          </div>

          {/* Keyword */}
          <div className="flex flex-col gap-1 flex-1 min-w-[200px]">
            <label className="text-xs font-medium text-gray-500 uppercase tracking-wide">Search</label>
            <input
              type="text"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && fetchResources(1)}
              placeholder="Search by key or value…"
              className="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          <button
            onClick={() => fetchResources(1)}
            disabled={loading}
            className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            Search
          </button>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
        {loading ? (
          <div className="p-8 space-y-3 animate-pulse">
            {[1, 2, 3, 4, 5].map((i) => (
              <div key={i} className="h-12 bg-gray-100 rounded" />
            ))}
          </div>
        ) : resources.length === 0 ? (
          <div className="p-12 text-center">
            <svg className="mx-auto h-10 w-10 text-gray-300 mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
            <p className="text-gray-500 font-medium">No translations found</p>
            <p className="text-sm text-gray-400 mt-1">
              {statusFilter === 'review'
                ? 'No pending translations to review. Great work!'
                : 'Try adjusting your filters.'}
            </p>
          </div>
        ) : (
          <>
            <div className="px-5 py-3 border-b border-gray-100 bg-gray-50 flex items-center justify-between">
              <p className="text-xs text-gray-500">
                Showing <span className="font-medium">{resources.length}</span> of{' '}
                <span className="font-medium">{total}</span> translations
              </p>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs font-medium text-gray-500 uppercase tracking-wide border-b border-gray-100">
                    <th className="px-5 py-3 w-2/5">Key</th>
                    <th className="px-5 py-3 w-1/4">Translation</th>
                    <th className="px-5 py-3 w-24">Status</th>
                    <th className="px-5 py-3">Notes</th>
                    <th className="px-5 py-3 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                  {resources.map((resource) => {
                    const isActing = actionLoading?.startsWith(resource.pid);
                    return (
                      <tr key={resource.pid} className="hover:bg-gray-50 transition-colors">
                        {/* Key */}
                        <td className="px-5 py-3 font-mono text-xs text-gray-600 break-all">
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
                        <td className="px-5 py-3 text-xs text-gray-500 max-w-[200px]">
                          {resource.rejectReason ? (
                            <span className="text-red-500 line-clamp-2" title={resource.rejectReason}>
                              ✗ {resource.rejectReason}
                            </span>
                          ) : resource.status === 'approved' && resource.reviewedAt ? (
                            <span className="text-emerald-600">Approved</span>
                          ) : null}
                        </td>

                        {/* Actions */}
                        <td className="px-5 py-3">
                          <div className="flex gap-2 justify-end flex-wrap">
                            {resource.status === 'draft' && (
                              <button
                                onClick={() => handleSubmitReview(resource.pid)}
                                disabled={!!isActing}
                                className="px-3 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 disabled:opacity-50 transition-colors"
                              >
                                {actionLoading === resource.pid + ':submit' ? 'Submitting…' : 'Submit for Review'}
                              </button>
                            )}
                            {resource.status === 'review' && (
                              <>
                                <button
                                  onClick={() => handleApprove(resource.pid)}
                                  disabled={!!isActing}
                                  className="px-3 py-1.5 text-xs font-medium text-emerald-700 bg-emerald-50 rounded-lg hover:bg-emerald-100 disabled:opacity-50 transition-colors"
                                >
                                  {actionLoading === resource.pid + ':approve' ? 'Approving…' : 'Approve'}
                                </button>
                                <button
                                  onClick={() => setRejectTarget(resource)}
                                  disabled={!!isActing}
                                  className="px-3 py-1.5 text-xs font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 disabled:opacity-50 transition-colors"
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
              <div className="px-5 py-3 border-t border-gray-100 flex items-center justify-between bg-gray-50">
                <button
                  onClick={() => fetchResources(pageNum - 1)}
                  disabled={pageNum <= 1 || loading}
                  className="px-3 py-1.5 text-sm text-gray-600 border border-gray-300 rounded-lg hover:bg-gray-100 disabled:opacity-40 transition-colors"
                >
                  Previous
                </button>
                <span className="text-sm text-gray-500">
                  Page {pageNum} of {Math.ceil(total / 20)}
                </span>
                <button
                  onClick={() => fetchResources(pageNum + 1)}
                  disabled={pageNum >= Math.ceil(total / 20) || loading}
                  className="px-3 py-1.5 text-sm text-gray-600 border border-gray-300 rounded-lg hover:bg-gray-100 disabled:opacity-40 transition-colors"
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
