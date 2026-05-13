import { useState, useEffect, useCallback, useMemo } from 'react';
import { useSearchParams, Link } from 'react-router';
import {
  ArrowLeftIcon,
  PlusCircleIcon,
  MinusCircleIcon,
  ArrowRightIcon,
  ExclamationTriangleIcon,
  RectangleStackIcon,
  CodeBracketIcon,
} from '@heroicons/react/24/outline';
import { fetchResult } from '~/shared/services/http-client/HttpClient';

// ---------- Types ----------

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
  status: string;
  dryRunResult: DryRunResult | null;
  dryRunAt: string | null;
  units: Array<{ pid: string; resourceType: string; resourcePid: string }>;
}

interface ResourceReference {
  pid: string;
  sourceType: string;
  sourceId: string;
  targetType: string;
  targetCode: string;
  refType: string | null;
}

// ---------- Helpers ----------

const opBadge = (op: SemanticDiffEntry['op']) => {
  const map: Record<typeof op, { cls: string; icon: React.ReactNode; label: string }> = {
    ADD: {
      cls: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
      icon: <PlusCircleIcon className="h-3 w-3" />,
      label: 'add',
    },
    DELETE: {
      cls: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
      icon: <MinusCircleIcon className="h-3 w-3" />,
      label: 'delete',
    },
    MODIFY: {
      cls: 'bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400',
      icon: <ArrowRightIcon className="h-3 w-3" />,
      label: 'modify',
    },
  };
  const m = map[op];
  return (
    <span
      className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs font-medium ${m.cls}`}
    >
      {m.icon}
      {m.label}
    </span>
  );
};

const formatValue = (v: any): string => {
  if (v === null || v === undefined) return '∅';
  if (typeof v === 'string') return v;
  try {
    return JSON.stringify(v, null, 2);
  } catch {
    return String(v);
  }
};

/**
 * Walk the diff entries and surface a unique list of FIELD/MODEL refs touched, so the impact
 * sidebar can query each.
 */
const extractTargets = (diff: SemanticDiffEntry[]): Array<{ type: 'FIELD' | 'MODEL'; code: string }> => {
  const seen = new Set<string>();
  const out: Array<{ type: 'FIELD' | 'MODEL'; code: string }> = [];
  for (const d of diff) {
    // Heuristic: any entry whose path ends with "code" or "fieldCode" or "modelCode" represents
    // a field/model reference change. Use the new value (or old value on DELETE) as the code.
    const match = d.path.match(/(?:^|\.)((?:field)?[Cc]ode|modelCode)$/);
    if (!match) continue;
    const value = d.op === 'DELETE' ? d.oldValue : d.newValue;
    if (typeof value !== 'string' || !value) continue;
    const targetType: 'FIELD' | 'MODEL' = match[1].toLowerCase().includes('model') ? 'MODEL' : 'FIELD';
    const key = `${targetType}:${value}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ type: targetType, code: value });
  }
  return out;
};

// ---------- Component ----------

export default function DiffViewer() {
  const [params] = useSearchParams();
  const promotionPid = params.get('promotion');
  const conflictIndexStr = params.get('conflict') ?? '0';
  const conflictIndex = Math.max(0, parseInt(conflictIndexStr, 10) || 0);

  const [promotion, setPromotion] = useState<PromotionResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [impactByTarget, setImpactByTarget] = useState<Record<string, ResourceReference[]>>({});
  const [impactLoading, setImpactLoading] = useState(false);

  // Fetch promotion
  const fetchPromotion = useCallback(async () => {
    if (!promotionPid) {
      setError('Missing ?promotion=<pid> query parameter');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await fetchResult<PromotionResponse>(
        `/api/admin/promotions/${promotionPid}`,
        { method: 'get' },
      );
      if (result.success && result.data) {
        setPromotion(result.data);
      } else {
        setError('Failed to load promotion');
      }
    } catch (err) {
      setError('Failed to load promotion');
    } finally {
      setLoading(false);
    }
  }, [promotionPid]);

  useEffect(() => {
    fetchPromotion();
  }, [fetchPromotion]);

  const conflict = useMemo<Conflict | null>(() => {
    if (!promotion?.dryRunResult?.conflicts) return null;
    return promotion.dryRunResult.conflicts[conflictIndex] ?? null;
  }, [promotion, conflictIndex]);

  // Fetch impact data for each FIELD/MODEL touched by the diff
  useEffect(() => {
    if (!conflict?.diff?.length) return;
    const targets = extractTargets(conflict.diff);
    if (targets.length === 0) return;

    setImpactLoading(true);
    let cancelled = false;
    (async () => {
      const next: Record<string, ResourceReference[]> = {};
      for (const target of targets) {
        try {
          const result = await fetchResult<ResourceReference[]>(
            `/api/admin/references/impact?type=${target.type}&code=${encodeURIComponent(target.code)}`,
            { method: 'get' },
          );
          if (!cancelled && result.success && Array.isArray(result.data)) {
            next[`${target.type}:${target.code}`] = result.data;
          }
        } catch {
          // Soft-fail per target — don't block the whole sidebar on a single error
        }
      }
      if (!cancelled) {
        setImpactByTarget(next);
        setImpactLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [conflict]);

  // ---------- Render ----------

  if (loading) {
    return (
      <div className="p-8 text-center text-gray-500 dark:text-gray-400">Loading diff...</div>
    );
  }

  if (error) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-400">
          {error}
        </div>
        <Link
          to="/admin/environments"
          className="mt-4 inline-flex items-center gap-1.5 text-sm text-indigo-600 hover:underline dark:text-indigo-400"
        >
          <ArrowLeftIcon className="h-4 w-4" />
          Back to Environments
        </Link>
      </div>
    );
  }

  if (!promotion || !conflict) {
    return (
      <div className="mx-auto max-w-3xl p-6 text-center text-gray-500 dark:text-gray-400">
        <RectangleStackIcon className="mx-auto mb-3 h-12 w-12 text-gray-300 dark:text-gray-600" />
        <p>No diff data to show — this promotion may not have validation results yet.</p>
        <Link
          to={`/admin/promotions?env=`}
          className="mt-4 inline-flex items-center gap-1.5 text-sm text-indigo-600 hover:underline dark:text-indigo-400"
        >
          <ArrowLeftIcon className="h-4 w-4" />
          Back to Promotions
        </Link>
      </div>
    );
  }

  const diffEntries = conflict.diff ?? [];
  const conflictsCount = promotion.dryRunResult?.conflicts.length ?? 0;
  const targets = extractTargets(diffEntries);

  return (
    <div className="mx-auto max-w-7xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-start justify-between">
        <div>
          <Link
            to="/admin/environments"
            className="mb-2 inline-flex items-center gap-1.5 text-sm text-gray-500 hover:text-indigo-600 dark:text-gray-400 dark:hover:text-indigo-400"
          >
            <ArrowLeftIcon className="h-4 w-4" />
            Back to Environments
          </Link>
          <h1 className="flex items-center gap-2 text-2xl font-bold text-gray-900 dark:text-white">
            <CodeBracketIcon className="h-7 w-7 text-indigo-600 dark:text-indigo-400" />
            Diff Viewer
          </h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Promotion <code className="font-mono text-xs">{promotion.pid}</code> ·{' '}
            env {promotion.sourceEnvId} → {promotion.targetEnvId} · status {promotion.status}
          </p>
        </div>

        {/* Conflict picker */}
        {conflictsCount > 1 && (
          <div className="flex items-center gap-2">
            <label className="text-xs text-gray-500 dark:text-gray-400">Conflict</label>
            <select
              value={conflictIndex}
              onChange={(e) => {
                const url = new URL(window.location.href);
                url.searchParams.set('conflict', e.target.value);
                window.history.pushState({}, '', url.toString());
                window.dispatchEvent(new PopStateEvent('popstate'));
              }}
              className="rounded-lg border border-gray-300 bg-white px-2 py-1 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
            >
              {(promotion.dryRunResult?.conflicts ?? []).map((_, i) => (
                <option key={i} value={i}>
                  {i + 1} of {conflictsCount}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>

      {/* Conflict header */}
      <div className="mb-6 rounded-lg border border-amber-200 bg-amber-50 p-4 dark:border-amber-800 dark:bg-amber-900/20">
        <div className="flex items-start gap-3">
          <ExclamationTriangleIcon className="h-5 w-5 flex-shrink-0 text-amber-600 dark:text-amber-400" />
          <div className="min-w-0 flex-1">
            <h2 className="text-sm font-semibold text-amber-900 dark:text-amber-300">
              {conflict.reason}
            </h2>
            <p className="mt-1 text-xs text-amber-800 dark:text-amber-400">
              {conflict.resourceType}{' '}
              <code className="font-mono">{conflict.resourcePid}</code> · source v
              {conflict.sourceVersion ?? '?'} → target v{conflict.targetVersion ?? '?'}
            </p>
          </div>
        </div>
      </div>

      {/* Diff body: 3-col layout (entries list | side-by-side values | impact sidebar) */}
      <div className="grid gap-4 lg:grid-cols-[1fr_280px]">
        {/* Diff entries with side-by-side source/target columns */}
        <div className="rounded-lg border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
          <div className="border-b border-gray-200 px-4 py-2 text-sm font-semibold text-gray-700 dark:border-gray-700 dark:text-gray-200">
            Field-level Changes ({diffEntries.length})
          </div>

          {diffEntries.length === 0 ? (
            <div className="p-8 text-center text-sm text-gray-500 dark:text-gray-400">
              No structural changes recorded for this conflict.
            </div>
          ) : (
            <div className="divide-y divide-gray-100 dark:divide-gray-700">
              {diffEntries.map((d, i) => (
                <div
                  key={i}
                  className={`grid grid-cols-[1fr_1fr] gap-4 p-4 ${
                    d.op === 'ADD'
                      ? 'bg-green-50/50 dark:bg-green-900/10'
                      : d.op === 'DELETE'
                        ? 'bg-red-50/50 dark:bg-red-900/10'
                        : 'bg-amber-50/50 dark:bg-amber-900/10'
                  }`}
                >
                  {/* Path + op header spans both columns */}
                  <div className="col-span-2 flex items-center gap-2">
                    {opBadge(d.op)}
                    <code className="font-mono text-xs text-gray-700 dark:text-gray-300">
                      {d.path}
                    </code>
                  </div>

                  {/* Source value (left) */}
                  <div>
                    <div className="mb-1 text-xs font-medium text-gray-500 dark:text-gray-400">
                      Source
                    </div>
                    <pre className="max-h-40 overflow-auto rounded bg-white p-2 font-mono text-xs text-gray-800 dark:bg-gray-900 dark:text-gray-200">
                      {d.op === 'ADD' ? '∅' : formatValue(d.oldValue)}
                    </pre>
                  </div>

                  {/* Target value (right) */}
                  <div>
                    <div className="mb-1 text-xs font-medium text-gray-500 dark:text-gray-400">
                      Target
                    </div>
                    <pre className="max-h-40 overflow-auto rounded bg-white p-2 font-mono text-xs text-gray-800 dark:bg-gray-900 dark:text-gray-200">
                      {d.op === 'DELETE' ? '∅' : formatValue(d.newValue)}
                    </pre>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Impact sidebar */}
        <aside
          aria-label="Impact analysis"
          className="rounded-lg border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800"
        >
          <div className="border-b border-gray-200 px-4 py-2 text-sm font-semibold text-gray-700 dark:border-gray-700 dark:text-gray-200">
            Impact Analysis
          </div>

          <div className="p-4">
            {targets.length === 0 ? (
              <p className="text-xs text-gray-500 dark:text-gray-400">
                No model/field references touched by this diff.
              </p>
            ) : (
              <div className="space-y-4">
                {targets.map((t) => {
                  const refs = impactByTarget[`${t.type}:${t.code}`] ?? [];
                  return (
                    <div key={`${t.type}:${t.code}`}>
                      <div className="mb-1 flex items-center gap-1.5">
                        <span
                          className={`rounded px-1.5 py-0.5 text-xs font-medium ${
                            t.type === 'MODEL'
                              ? 'bg-purple-100 text-purple-800 dark:bg-purple-900/30 dark:text-purple-400'
                              : 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400'
                          }`}
                        >
                          {t.type}
                        </span>
                        <code className="font-mono text-xs text-gray-800 dark:text-gray-200">
                          {t.code}
                        </code>
                      </div>
                      {impactLoading ? (
                        <div className="text-xs text-gray-400">Loading…</div>
                      ) : refs.length === 0 ? (
                        <div className="text-xs text-gray-500 dark:text-gray-400">
                          No pages reference this in the current env.
                        </div>
                      ) : (
                        <ul className="space-y-1 text-xs text-gray-700 dark:text-gray-300">
                          {refs.map((r) => (
                            <li key={r.pid} className="truncate">
                              <code className="font-mono">{r.sourceId.substring(0, 12)}…</code>{' '}
                              {r.refType && (
                                <span className="text-gray-500">({r.refType})</span>
                              )}
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </aside>
      </div>
    </div>
  );
}
