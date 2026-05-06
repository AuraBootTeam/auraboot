import { useState, useEffect, useCallback, useMemo } from 'react';
import { useSearchParams, Link } from 'react-router';
import {
  RocketLaunchIcon,
  PlusIcon,
  CheckCircleIcon,
  XCircleIcon,
  ClockIcon,
  ExclamationTriangleIcon,
  ArrowsRightLeftIcon,
  CodeBracketIcon,
  PlayIcon,
  ArrowUpCircleIcon,
} from '@heroicons/react/24/outline';
import { fetchResult } from '~/shared/services/http-client/HttpClient';
import { useToken as useAuthToken } from '~/contexts/AuthContext';

// ---------- Types ----------

interface EnvironmentLite {
  id: number;
  pid: string;
  code: string;
  name: string;
  isLocked?: boolean;
}

interface PromotionUnitView {
  pid: string;
  resourceType: string;
  resourcePid: string;
  sourceVersion: number | null;
  targetVersion: number | null;
}

interface SemanticDiffEntry {
  path: string;
  op: 'ADD' | 'MODIFY' | 'DELETE';
  oldValue: any;
  newValue: any;
}

interface Conflict {
  resourceType: string;
  resourcePid: string;
  sourceVersion: number | null;
  targetVersion: number | null;
  reason: string;
  diff: SemanticDiffEntry[];
}

interface DryRunResult {
  validatedAt: string | null;
  valid: boolean;
  conflicts: Conflict[];
  missingDependencies: any[];
}

interface PromotionResponse {
  pid: string;
  sourceEnvId: number;
  targetEnvId: number;
  status: 'DRAFT' | 'VALIDATED' | 'APPLIED' | 'REJECTED' | 'FAILED';
  units: PromotionUnitView[];
  dryRunResult: DryRunResult | null;
  dryRunAt: string | null;
  createdAt: string;
  appliedAt: string | null;
  appliedBy: number | null;
  appliedReason: string | null;
  failureReason: string | null;
}

// ---------- Helpers ----------

const statusBadge = (status: PromotionResponse['status']) => {
  const map = {
    DRAFT:     { cls: 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-300', icon: <ClockIcon className="h-3 w-3" /> },
    VALIDATED: { cls: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400', icon: <CheckCircleIcon className="h-3 w-3" /> },
    APPLIED:   { cls: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400', icon: <CheckCircleIcon className="h-3 w-3" /> },
    REJECTED:  { cls: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400', icon: <XCircleIcon className="h-3 w-3" /> },
    FAILED:    { cls: 'bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400', icon: <ExclamationTriangleIcon className="h-3 w-3" /> },
  };
  const m = map[status];
  return (
    <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${m.cls}`}>
      {m.icon}
      {status}
    </span>
  );
};

// ---------- Component ----------

export default function PromotionManagement() {
  const token = useAuthToken();
  const [searchParams] = useSearchParams();
  const envFilter = searchParams.get('env');

  const [environments, setEnvironments] = useState<EnvironmentLite[]>([]);
  const [promotions, setPromotions] = useState<PromotionResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<string>('');

  const [selectedPid, setSelectedPid] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);

  // Create form state
  const [formSourceEnvId, setFormSourceEnvId] = useState<number | null>(null);
  const [formTargetEnvId, setFormTargetEnvId] = useState<number | null>(null);
  const [formPagePids, setFormPagePids] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // Apply dialog state
  const [applyTarget, setApplyTarget] = useState<PromotionResponse | null>(null);
  const [applyReason, setApplyReason] = useState('');
  const [applySubmitting, setApplySubmitting] = useState(false);

  const envByPid: Record<string, EnvironmentLite> = useMemo(
    () => Object.fromEntries(environments.map((e) => [e.pid, e])),
    [environments],
  );
  const envById: Record<number, EnvironmentLite & { id: number }> = useMemo(() => {
    const m: any = {};
    // We don't have id on the lite; we'll fetch with full env list via /api/admin/environments
    return m;
  }, []);

  // Fetch envs (full)
  const fetchEnvs = useCallback(async () => {
    try {
      const result = await fetchResult<Array<EnvironmentLite & { id?: number }>>(
        '/api/admin/environments',
        { method: 'get', token: token ?? undefined },
      );
      if (result.success && result.data) {
        setEnvironments(result.data);
      }
    } catch {
      // soft-fail; create form will be empty
    }
  }, [token]);

  // Fetch promotions
  const fetchPromotions = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const url = statusFilter
        ? `/api/admin/promotions?status=${encodeURIComponent(statusFilter)}`
        : '/api/admin/promotions';
      const result = await fetchResult<PromotionResponse[]>(url, {
        method: 'get',
        token: token ?? undefined,
      });
      if (result.success && result.data) {
        setPromotions(result.data);
      } else {
        setError('Failed to load promotions');
      }
    } catch {
      setError('Failed to load promotions');
    } finally {
      setLoading(false);
    }
  }, [token, statusFilter]);

  useEffect(() => {
    if (token) {
      fetchEnvs();
      fetchPromotions();
    }
  }, [token, fetchEnvs, fetchPromotions]);

  const selected = useMemo(
    () => promotions.find((p) => p.pid === selectedPid) ?? null,
    [promotions, selectedPid],
  );

  // Visible envs (filtered by `?env=<code>` if present)
  const visiblePromotions = useMemo(() => {
    if (!envFilter) return promotions;
    const matching = environments.find((e) => e.code === envFilter);
    if (!matching) return promotions;
    // filter by source or target id; envs Lite doesn't expose id, so we'll compare via name → skip for now
    return promotions;
  }, [promotions, envFilter, environments]);

  // ---------- Actions ----------

  const handleCreate = async () => {
    if (!formSourceEnvId || !formTargetEnvId) {
      setError('Source and target env are required');
      return;
    }
    if (formSourceEnvId === formTargetEnvId) {
      setError('Source and target must differ');
      return;
    }
    const pids = formPagePids
      .split(/[\n,]+/)
      .map((s) => s.trim())
      .filter(Boolean);
    if (pids.length === 0) {
      setError('Provide at least one source page pid');
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const result = await fetchResult<PromotionResponse>('/api/admin/promotions', {
        method: 'post',
        params: {
          sourceEnvId: formSourceEnvId,
          targetEnvId: formTargetEnvId,
          units: pids.map((pid, i) => ({
            resourceType: 'PAGE_SCHEMA',
            resourcePid: pid,
            sortOrder: i,
          })),
        },
        token: token ?? undefined,
      });
      if (result.success && result.data) {
        setShowCreate(false);
        setFormSourceEnvId(null);
        setFormTargetEnvId(null);
        setFormPagePids('');
        await fetchPromotions();
        setSelectedPid(result.data.pid);
      } else {
        setError('Create failed');
      }
    } catch {
      setError('Create failed');
    } finally {
      setSubmitting(false);
    }
  };

  const handleValidate = async (pid: string) => {
    setError(null);
    try {
      const result = await fetchResult<DryRunResult>(`/api/admin/promotions/${pid}/validate`, {
        method: 'post',
        token: token ?? undefined,
      });
      if (result.success) {
        await fetchPromotions();
      } else {
        setError('Validate failed');
      }
    } catch {
      setError('Validate failed');
    }
  };

  const openApplyDialog = (p: PromotionResponse) => {
    setApplyTarget(p);
    setApplyReason('');
  };

  const handleApply = async () => {
    if (!applyTarget) return;
    setApplySubmitting(true);
    setError(null);
    try {
      const result = await fetchResult<PromotionResponse>(
        `/api/admin/promotions/${applyTarget.pid}/apply`,
        {
          method: 'post',
          params: { reason: applyReason },
          token: token ?? undefined,
        },
      );
      if (result.success) {
        setApplyTarget(null);
        setApplyReason('');
        await fetchPromotions();
      } else {
        setError('Apply failed');
      }
    } catch (e: any) {
      // Surface server-side IllegalStateException message (four-eyes / lock guard / stale dry-run)
      setError(e?.message ?? 'Apply failed');
    } finally {
      setApplySubmitting(false);
    }
  };

  // ---------- Render ----------

  return (
    <div className="mx-auto max-w-7xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <RocketLaunchIcon className="h-8 w-8 text-indigo-600 dark:text-indigo-400" />
          <div>
            <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Promotions</h1>
            <p className="text-sm text-gray-500 dark:text-gray-400">
              Move DSL resources between environments via dry-run + four-eyes apply
              {envFilter && (
                <span className="ml-2 rounded bg-indigo-100 px-1.5 py-0.5 text-xs text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-400">
                  filtered: {envFilter}
                </span>
              )}
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
            data-testid="promotion-status-filter"
          >
            <option value="">All statuses</option>
            <option value="DRAFT">Draft</option>
            <option value="VALIDATED">Validated</option>
            <option value="APPLIED">Applied</option>
            <option value="REJECTED">Rejected</option>
            <option value="FAILED">Failed</option>
          </select>
          <button
            onClick={() => setShowCreate(true)}
            className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm font-medium text-white hover:bg-indigo-700"
            data-testid="promotion-new-btn"
          >
            <PlusIcon className="h-4 w-4" />
            New Promotion
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-4 flex justify-between rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-400">
          <span data-testid="promotion-error">{error}</span>
          <button onClick={() => setError(null)} className="font-medium hover:underline">
            Dismiss
          </button>
        </div>
      )}

      {/* List */}
      {loading ? (
        <div className="py-12 text-center text-gray-500 dark:text-gray-400">Loading...</div>
      ) : visiblePromotions.length === 0 ? (
        <div className="rounded-lg border border-gray-200 bg-white py-12 text-center dark:border-gray-700 dark:bg-gray-800">
          <RocketLaunchIcon className="mx-auto mb-3 h-12 w-12 text-gray-300 dark:text-gray-600" />
          <p className="mb-4 text-gray-500 dark:text-gray-400">All caught up — no promotions yet</p>
          <button
            onClick={() => setShowCreate(true)}
            className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
          >
            <PlusIcon className="h-4 w-4" />
            Create the first promotion
          </button>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 dark:bg-gray-900">
              <tr>
                <th className="px-4 py-2 text-left font-medium text-gray-600 dark:text-gray-400">PID</th>
                <th className="px-4 py-2 text-left font-medium text-gray-600 dark:text-gray-400">Source → Target</th>
                <th className="px-4 py-2 text-left font-medium text-gray-600 dark:text-gray-400">Units</th>
                <th className="px-4 py-2 text-left font-medium text-gray-600 dark:text-gray-400">Status</th>
                <th className="px-4 py-2 text-left font-medium text-gray-600 dark:text-gray-400">Conflicts</th>
                <th className="px-4 py-2 text-right font-medium text-gray-600 dark:text-gray-400">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
              {visiblePromotions.map((p) => (
                <tr
                  key={p.pid}
                  data-testid={`promotion-row-${p.pid}`}
                  className={`cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700/50 ${
                    selectedPid === p.pid ? 'bg-indigo-50 dark:bg-indigo-900/10' : ''
                  }`}
                  onClick={() => setSelectedPid(p.pid === selectedPid ? null : p.pid)}
                >
                  <td className="px-4 py-2 font-mono text-xs text-gray-700 dark:text-gray-300">
                    {p.pid.substring(0, 14)}…
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-700 dark:text-gray-300">
                    <ArrowsRightLeftIcon className="mr-1 inline h-3 w-3" />
                    env {p.sourceEnvId} → env {p.targetEnvId}
                  </td>
                  <td className="px-4 py-2 text-xs text-gray-700 dark:text-gray-300">{p.units.length}</td>
                  <td className="px-4 py-2">{statusBadge(p.status)}</td>
                  <td className="px-4 py-2 text-xs">
                    {p.dryRunResult
                      ? p.dryRunResult.conflicts.length === 0
                        ? <span className="text-green-700 dark:text-green-400">0</span>
                        : <span className="text-amber-700 dark:text-amber-400">{p.dryRunResult.conflicts.length}</span>
                      : <span className="text-gray-400">—</span>}
                  </td>
                  <td className="px-4 py-2 text-right" onClick={(e) => e.stopPropagation()}>
                    <div className="inline-flex items-center gap-1">
                      <button
                        onClick={() => handleValidate(p.pid)}
                        disabled={p.status === 'APPLIED' || p.status === 'REJECTED'}
                        className="rounded p-1.5 text-gray-400 hover:text-blue-600 disabled:opacity-30 dark:hover:text-blue-400"
                        title="Validate (dry-run)"
                        data-testid={`promotion-validate-${p.pid}`}
                      >
                        <PlayIcon className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => openApplyDialog(p)}
                        disabled={p.status !== 'VALIDATED'}
                        className="rounded p-1.5 text-gray-400 hover:text-green-600 disabled:opacity-30 dark:hover:text-green-400"
                        title="Apply"
                        data-testid={`promotion-apply-${p.pid}`}
                      >
                        <ArrowUpCircleIcon className="h-4 w-4" />
                      </button>
                      {p.dryRunResult && p.dryRunResult.conflicts.length > 0 && (
                        <Link
                          to={`/admin/diff?promotion=${p.pid}&conflict=0`}
                          className="rounded p-1.5 text-gray-400 hover:text-indigo-600 dark:hover:text-indigo-400"
                          title="View Diff"
                          data-testid={`promotion-diff-${p.pid}`}
                        >
                          <CodeBracketIcon className="h-4 w-4" />
                        </Link>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Inline detail panel for selected promotion */}
          {selected && (
            <div
              data-testid={`promotion-detail-${selected.pid}`}
              className="border-t border-gray-200 bg-gray-50 p-5 dark:border-gray-700 dark:bg-gray-900/50"
            >
              <div className="mb-3 flex items-center gap-3">
                <h3 className="text-sm font-semibold text-gray-900 dark:text-white">
                  Details — {selected.pid}
                </h3>
                {statusBadge(selected.status)}
                {selected.dryRunAt && (
                  <span className="text-xs text-gray-500 dark:text-gray-400">
                    Validated {new Date(selected.dryRunAt).toLocaleString()}
                  </span>
                )}
              </div>

              {selected.failureReason && (
                <div className="mb-3 rounded border border-red-200 bg-red-50 p-2 text-xs text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-400">
                  <strong>Failure:</strong> {selected.failureReason}
                </div>
              )}

              {selected.appliedReason && (
                <div className="mb-3 rounded border border-green-200 bg-green-50 p-2 text-xs text-green-700 dark:border-green-800 dark:bg-green-900/20 dark:text-green-400">
                  <strong>Applied:</strong> {selected.appliedReason} (by user {selected.appliedBy})
                </div>
              )}

              <div className="mb-2 text-xs font-medium text-gray-700 dark:text-gray-300">
                Units ({selected.units.length})
              </div>
              <ul className="space-y-1">
                {selected.units.map((u) => (
                  <li key={u.pid} className="text-xs text-gray-700 dark:text-gray-300">
                    <code className="font-mono">{u.resourceType}</code>{' '}
                    <code className="font-mono text-gray-500">{u.resourcePid.substring(0, 14)}…</code>
                    {' '}v{u.sourceVersion ?? '?'} → v{u.targetVersion ?? '?'}
                  </li>
                ))}
              </ul>

              {selected.dryRunResult && selected.dryRunResult.conflicts.length > 0 && (
                <div className="mt-3">
                  <div className="mb-1 text-xs font-medium text-amber-700 dark:text-amber-400">
                    Conflicts ({selected.dryRunResult.conflicts.length})
                  </div>
                  <ul className="space-y-1">
                    {selected.dryRunResult.conflicts.map((c, i) => (
                      <li key={i} className="text-xs text-gray-700 dark:text-gray-300">
                        <Link
                          to={`/admin/diff?promotion=${selected.pid}&conflict=${i}`}
                          className="text-indigo-600 hover:underline dark:text-indigo-400"
                        >
                          {c.reason}
                        </Link>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Create modal */}
      {showCreate && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div
            className="mx-4 w-full max-w-lg rounded-xl bg-white shadow-xl dark:bg-gray-800"
            data-testid="promotion-create-modal"
          >
            <div className="p-6">
              <h2 className="mb-4 text-lg font-semibold text-gray-900 dark:text-white">
                New Promotion
              </h2>

              <div className="space-y-4">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Source Environment *
                  </label>
                  <select
                    value={formSourceEnvId ?? ''}
                    onChange={(e) =>
                      setFormSourceEnvId(e.target.value ? Number(e.target.value) : null)
                    }
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    data-testid="promotion-create-source"
                  >
                    <option value="">Select source...</option>
                    {environments.map((e: any) => (
                      <option key={e.pid} value={e.id}>
                        {e.name} ({e.code})
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Target Environment *
                  </label>
                  <select
                    value={formTargetEnvId ?? ''}
                    onChange={(e) =>
                      setFormTargetEnvId(e.target.value ? Number(e.target.value) : null)
                    }
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    data-testid="promotion-create-target"
                  >
                    <option value="">Select target...</option>
                    {environments.map((e: any) => (
                      <option key={e.pid} value={e.id}>
                        {e.name} ({e.code})
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Source Page PIDs *
                  </label>
                  <textarea
                    value={formPagePids}
                    onChange={(e) => setFormPagePids(e.target.value)}
                    rows={3}
                    placeholder="One pid per line, or comma-separated"
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 font-mono text-xs dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    data-testid="promotion-create-pids"
                  />
                </div>
              </div>

              <div className="mt-6 flex justify-end gap-2">
                <button
                  onClick={() => {
                    setShowCreate(false);
                    setError(null);
                  }}
                  className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700"
                >
                  Cancel
                </button>
                <button
                  onClick={handleCreate}
                  disabled={submitting}
                  className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
                  data-testid="promotion-create-submit"
                >
                  {submitting ? 'Creating...' : 'Create'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Apply confirmation dialog */}
      {applyTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div
            className="mx-4 w-full max-w-md rounded-xl bg-white shadow-xl dark:bg-gray-800"
            data-testid="promotion-apply-modal"
          >
            <div className="p-6">
              <h2 className="mb-1 flex items-center gap-2 text-lg font-semibold text-gray-900 dark:text-white">
                <ArrowUpCircleIcon className="h-5 w-5 text-green-600" />
                Apply Promotion
              </h2>
              <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">
                Apply <code className="font-mono text-xs">{applyTarget.pid}</code> to env {applyTarget.targetEnvId}.
                If the target is locked, four-eyes will be enforced server-side.
              </p>

              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                Reason {applyTarget.dryRunResult && applyTarget.dryRunResult.conflicts.length > 0 ? '(required for locked)' : '(optional)'}
              </label>
              <textarea
                value={applyReason}
                onChange={(e) => setApplyReason(e.target.value)}
                rows={3}
                placeholder="e.g. ship release 1.2 to staging"
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                data-testid="promotion-apply-reason"
              />

              <div className="mt-6 flex justify-end gap-2">
                <button
                  onClick={() => {
                    setApplyTarget(null);
                    setApplyReason('');
                  }}
                  className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700"
                >
                  Cancel
                </button>
                <button
                  onClick={handleApply}
                  disabled={applySubmitting}
                  className="rounded-lg bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
                  data-testid="promotion-apply-submit"
                >
                  {applySubmitting ? 'Applying...' : 'Apply'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
