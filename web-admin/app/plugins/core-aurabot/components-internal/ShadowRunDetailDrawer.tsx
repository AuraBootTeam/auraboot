/**
 * ShadowRunDetailDrawer (D.5 Phase 1).
 *
 * Right-side slide-in drawer that lists individual shadow runs for a single
 * Skill Draft and renders a side-by-side comparison of the production run
 * versus the draft (shadow) run for each: status / duration / cost / output
 * hash / output match flag / fidelity match flag / pretty-printed output diff.
 *
 * The drawer owns its own data fetch keyed on `draftId`; the parent only
 * passes the draft id and an `onClose` handler. Closes on backdrop click or
 * Escape.
 */

import { useCallback, useEffect, useState } from 'react';
import {
  listShadowRunsForDraft,
  type ShadowRunListItem,
} from '../services/shadowRunsApi';
import { useI18n } from '~/contexts/I18nContext';

interface Props {
  /** Draft id whose shadow runs to display; null hides the drawer. */
  draftId: string | null;
  /** Friendly label of the draft, displayed as the drawer header subtitle. */
  draftSkillCode?: string | null;
  onClose: () => void;
}

function fmtCost(n: number | null | undefined): string {
  if (n === null || n === undefined) return '-';
  return `$${Number(n).toFixed(4)}`;
}

function fmtDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '-';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function statusColor(status: string | null | undefined): string {
  switch ((status ?? '').toLowerCase()) {
    case 'success':
    case 'succeeded':
      return 'bg-emerald-100 text-emerald-800';
    case 'failed':
    case 'error':
      return 'bg-red-100 text-red-800';
    case 'timeout':
      return 'bg-amber-100 text-amber-800';
    case 'skipped':
      return 'bg-gray-100 text-gray-700';
    default:
      return 'bg-gray-100 text-gray-600';
  }
}

function prettyJson(raw: string | null | undefined): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

export default function ShadowRunDetailDrawer({
  draftId,
  draftSkillCode,
  onClose,
}: Props) {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );

  const [rows, setRows] = useState<ShadowRunListItem[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expandedDiff, setExpandedDiff] = useState<string | null>(null);

  useEffect(() => {
    if (!draftId) {
      setRows(null);
      setError(null);
      setExpandedDiff(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    listShadowRunsForDraft(draftId, 0, 50)
      .then((data) => {
        if (!cancelled) setRows(data);
      })
      .catch((e) => {
        if (!cancelled) {
          setError((e as Error).message);
          setRows([]);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [draftId]);

  // Close on Escape.
  useEffect(() => {
    if (!draftId) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [draftId, onClose]);

  if (!draftId) return null;

  return (
    <div
      className="fixed inset-0 z-[60] flex"
      data-testid="shadow-run-drawer"
      role="dialog"
      aria-modal="true"
    >
      <div
        className="flex-1 bg-black/30"
        onClick={onClose}
        data-testid="shadow-run-drawer-backdrop"
      />
      <div className="w-full max-w-3xl bg-white shadow-xl border-l border-gray-200 flex flex-col">
        {/* Header */}
        <div className="px-5 py-3 border-b border-gray-200 flex items-center justify-between">
          <div className="min-w-0">
            <div className="text-sm text-gray-500">
              {l('影子运行明细', 'Shadow run detail')}
            </div>
            <div
              className="font-mono text-sm truncate"
              data-testid="shadow-run-drawer-title"
            >
              {draftSkillCode ?? draftId}
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-gray-500 hover:text-gray-800 px-2 py-1"
            data-testid="shadow-run-drawer-close"
            aria-label="close"
          >
            ×
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-4 space-y-3">
          {loading && (
            <div data-testid="shadow-run-drawer-loading">
              {/* Skeleton: 3 placeholder rows */}
              {[0, 1, 2].map((k) => (
                <div
                  key={k}
                  className="animate-pulse mb-3 border border-gray-200 rounded p-3"
                >
                  <div className="h-3 w-1/3 bg-gray-200 rounded mb-2" />
                  <div className="h-3 w-2/3 bg-gray-100 rounded" />
                </div>
              ))}
            </div>
          )}

          {!loading && error && (
            <div
              className="text-sm text-red-700 bg-red-50 border border-red-200 rounded px-3 py-2"
              data-testid="shadow-run-drawer-error"
            >
              {error}
            </div>
          )}

          {!loading && !error && rows && rows.length === 0 && (
            <div
              className="text-sm text-gray-500 border border-dashed border-gray-300 rounded p-6 text-center"
              data-testid="shadow-run-drawer-empty"
            >
              {l(
                '此草稿暂无影子运行记录。',
                'No shadow runs recorded for this draft yet.',
              )}
            </div>
          )}

          {!loading && !error && rows && rows.length > 0 && (
            <ul className="space-y-3" data-testid="shadow-run-list">
              {rows.map((r) => {
                const durDelta =
                  r.shadowDurationMs != null && r.originalDurationMs != null
                    ? r.shadowDurationMs - r.originalDurationMs
                    : null;
                const costDelta =
                  r.shadowCostUsd != null && r.originalCostUsd != null
                    ? r.shadowCostUsd - r.originalCostUsd
                    : null;
                const isExpanded = expandedDiff === r.pid;
                return (
                  <li
                    key={r.pid}
                    className="border border-gray-200 rounded bg-white"
                    data-testid={`shadow-run-item-${r.pid}`}
                  >
                    <div className="px-3 py-2 flex items-center justify-between border-b border-gray-100">
                      <div className="flex items-center gap-2 text-xs">
                        <span
                          className={`px-2 py-0.5 rounded ${statusColor(r.shadowStatus)}`}
                        >
                          {r.shadowStatus ?? '-'}
                        </span>
                        <span
                          className={
                            r.outputMatch
                              ? 'text-emerald-700'
                              : r.outputMatch === false
                                ? 'text-red-700'
                                : 'text-gray-500'
                          }
                          data-testid={`shadow-run-output-match-${r.pid}`}
                        >
                          {l('输出匹配', 'Output match')}{' '}
                          {r.outputMatch === null
                            ? '-'
                            : r.outputMatch
                              ? '✓'
                              : '✗'}
                        </span>
                        <span
                          className={
                            r.fidelityMatch
                              ? 'text-emerald-700'
                              : r.fidelityMatch === false
                                ? 'text-amber-700'
                                : 'text-gray-500'
                          }
                          data-testid={`shadow-run-fidelity-match-${r.pid}`}
                        >
                          Fidelity{' '}
                          {r.fidelityMatch === null
                            ? '-'
                            : r.fidelityMatch
                              ? '✓'
                              : '✗'}
                        </span>
                      </div>
                      <span className="text-xs text-gray-500">
                        {r.createdAt?.slice(0, 19).replace('T', ' ') ?? ''}
                      </span>
                    </div>

                    {/* Side-by-side: production left, shadow right */}
                    <div className="grid grid-cols-2 gap-0 text-xs">
                      <div className="p-3 border-r border-gray-100 bg-gray-50">
                        <div className="font-medium text-gray-700 mb-1">
                          {l('生产 (Production)', 'Production')}
                        </div>
                        <div data-testid={`shadow-run-prod-${r.pid}`}>
                          <div>
                            <span className="text-gray-500">{l('状态', 'Status')}: </span>
                            {r.originalStatus ?? '-'}
                          </div>
                          <div>
                            <span className="text-gray-500">
                              {l('耗时', 'Duration')}:{' '}
                            </span>
                            {fmtDuration(r.originalDurationMs)}
                          </div>
                          <div>
                            <span className="text-gray-500">
                              {l('成本', 'Cost')}:{' '}
                            </span>
                            {fmtCost(r.originalCostUsd)}
                          </div>
                          <div className="font-mono break-all text-gray-500 mt-1">
                            {r.originalOutputHash ?? '-'}
                          </div>
                        </div>
                      </div>
                      <div className="p-3">
                        <div className="font-medium text-gray-700 mb-1">
                          {l('影子 (Shadow)', 'Shadow')}
                        </div>
                        <div data-testid={`shadow-run-shadow-${r.pid}`}>
                          <div>
                            <span className="text-gray-500">{l('状态', 'Status')}: </span>
                            {r.shadowStatus ?? '-'}
                          </div>
                          <div>
                            <span className="text-gray-500">
                              {l('耗时', 'Duration')}:{' '}
                            </span>
                            {fmtDuration(r.shadowDurationMs)}
                            {durDelta !== null && (
                              <span
                                className={
                                  durDelta > 0
                                    ? 'ml-1 text-amber-700'
                                    : 'ml-1 text-emerald-700'
                                }
                              >
                                ({durDelta > 0 ? '+' : ''}
                                {durDelta}ms)
                              </span>
                            )}
                          </div>
                          <div>
                            <span className="text-gray-500">
                              {l('成本', 'Cost')}:{' '}
                            </span>
                            {fmtCost(r.shadowCostUsd)}
                            {costDelta !== null && (
                              <span
                                className={
                                  costDelta > 0
                                    ? 'ml-1 text-amber-700'
                                    : 'ml-1 text-emerald-700'
                                }
                              >
                                ({costDelta > 0 ? '+' : ''}
                                {costDelta.toFixed(4)})
                              </span>
                            )}
                          </div>
                          <div className="font-mono break-all text-gray-500 mt-1">
                            {r.shadowOutputHash ?? '-'}
                          </div>
                        </div>
                      </div>
                    </div>

                    {/* Output diff (when present) */}
                    {r.outputDiff && r.outputDiff.trim().length > 0 && (
                      <div className="border-t border-gray-100 px-3 py-2">
                        <button
                          type="button"
                          onClick={() =>
                            setExpandedDiff(isExpanded ? null : r.pid)
                          }
                          className="text-xs text-blue-700 hover:underline"
                          data-testid={`shadow-run-diff-toggle-${r.pid}`}
                        >
                          {isExpanded
                            ? l('收起 diff', 'Collapse diff')
                            : l('展开 diff', 'Expand diff')}
                        </button>
                        {isExpanded && (
                          <pre
                            className="mt-2 text-xs bg-gray-50 border border-gray-200 rounded p-2 overflow-x-auto whitespace-pre-wrap"
                            data-testid={`shadow-run-diff-${r.pid}`}
                          >
                            {prettyJson(r.outputDiff)}
                          </pre>
                        )}
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
