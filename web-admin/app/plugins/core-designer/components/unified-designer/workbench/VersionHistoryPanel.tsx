import React, { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import type {
  FieldDifference,
  PageSchemaVersionComparisonDTO,
  PageSchemaVersionDTO,
} from '../../studio/services/page-manager/api-types';
import {
  comparePageVersions,
  createPageVersion,
  getPageVersions,
  rollbackPageToVersion,
} from '../persistence/pageSchemaV3Repository';

export interface VersionHistoryPanelProps {
  /** The persisted page id (pid). The panel is only opened for a saved page. */
  pid: string;
  open: boolean;
  onClose: () => void;
  /**
   * Called after a successful rollback so the workbench can reload the page
   * document (the backend has restored the target snapshot's blocks onto the
   * live page) and close the panel.
   */
  onRolledBack: () => void | Promise<void>;
}

type PanelStatus = 'idle' | 'loading' | 'creating' | 'rolling-back' | 'comparing' | 'error';
/** Which view the panel is showing: the version list or the diff result. */
type PanelView = 'list' | 'diff';

function operationLabel(operation: string | undefined, locale: string): string {
  switch ((operation ?? '').toLowerCase()) {
    case 'create':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationCreate, locale);
    case 'update':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationUpdate, locale);
    case 'publish':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationPublish, locale);
    case 'archive':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationArchive, locale);
    case 'delete':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationDelete, locale);
    case 'rollback':
    case 'restore':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationRestore, locale);
    case 'snapshot':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationSnapshot, locale);
    default:
      // Unknown operation codes (e.g. backend-internal "pre_rollback_backup")
      // are shown verbatim so nothing is silently dropped from the audit trail.
      return operation ?? '';
  }
}

/**
 * Display label for a version row. Prefers the semantic version, then the
 * numeric version, and finally falls back to the history id (`#<id>`) so a row
 * whose `version`/`semver` the backend did not populate (e.g. ad-hoc snapshot
 * rows) never renders a bare "vnull".
 */
function versionLabel(version: PageSchemaVersionDTO): string {
  if (version.semver) return version.semver;
  if (version.version != null) return `v${version.version}`;
  return `#${version.id}`;
}

function formatTime(time: string | undefined): string {
  if (!time) return '';
  const parsed = new Date(time);
  if (Number.isNaN(parsed.getTime())) return time;
  return parsed.toLocaleString();
}

/**
 * Normalize the backend `DifferenceType` (serialized UPPERCASE by Jackson) to a
 * lower-cased token, defensively tolerating any casing so a future serialization
 * change cannot silently drop the badge.
 */
function diffKind(type: FieldDifference['type']): 'added' | 'removed' | 'modified' {
  const t = String(type ?? '').toLowerCase();
  if (t === 'added') return 'added';
  if (t === 'removed') return 'removed';
  return 'modified';
}

/**
 * Render a diff value (which the backend returns as a stringified JSON blob for
 * object fields like `blocks`/`title`, or a raw scalar for numeric fields) as a
 * displayable string. `null`/`undefined` become the localized empty marker.
 */
function formatDiffValue(value: unknown, emptyLabel: string): string {
  if (value === null || value === undefined) return emptyLabel;
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

// Truncation threshold for long diff values before the expand toggle appears.
const VALUE_TRUNCATE = 120;

/** A single diff row: field path + type badge + source→target values. */
function DiffRow({ diff, locale }: { diff: FieldDifference; locale: string }) {
  const [expanded, setExpanded] = useState(false);
  const kind = diffKind(diff.type);
  const emptyLabel = resolveDesignerText(DESIGNER_I18N.unified.versionDiffEmptyValue, locale);
  const sourceText = formatDiffValue(diff.sourceValue, emptyLabel);
  const targetText = formatDiffValue(diff.targetValue, emptyLabel);
  const longest = Math.max(sourceText.length, targetText.length);
  const truncatable = longest > VALUE_TRUNCATE;

  const badge = {
    added: {
      cls: 'bg-emerald-50 text-emerald-700',
      label: resolveDesignerText(DESIGNER_I18N.unified.versionDiffAdded, locale),
    },
    removed: {
      cls: 'bg-red-50 text-red-700',
      label: resolveDesignerText(DESIGNER_I18N.unified.versionDiffRemoved, locale),
    },
    modified: {
      cls: 'bg-blue-50 text-blue-700',
      label: resolveDesignerText(DESIGNER_I18N.unified.versionDiffModified, locale),
    },
  }[kind];

  const clamp = (s: string) =>
    truncatable && !expanded && s.length > VALUE_TRUNCATE ? `${s.slice(0, VALUE_TRUNCATE)}…` : s;

  return (
    <li
      data-testid={`version-diff-row-${diff.fieldPath}`}
      data-diff-type={kind}
      className="rounded-md border border-slate-200 px-3 py-2.5"
    >
      <div className="flex items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${badge.cls}`}>
          {badge.label}
        </span>
        <code className="min-w-0 truncate font-mono text-xs text-slate-700" title={diff.fieldPath}>
          {diff.fieldPath}
        </code>
      </div>
      <div className="mt-2 space-y-1.5">
        {kind !== 'added' ? (
          <div className="text-xs">
            <div className="mb-0.5 text-[11px] font-medium text-slate-400">
              {resolveDesignerText(DESIGNER_I18N.unified.versionDiffSourceValue, locale)}
            </div>
            <div
              className="whitespace-pre-wrap break-all rounded bg-red-50 px-2 py-1 font-mono text-[11px] text-slate-700"
              data-testid={`version-diff-row-${diff.fieldPath}-source`}
            >
              {clamp(sourceText)}
            </div>
          </div>
        ) : null}
        {kind !== 'removed' ? (
          <div className="text-xs">
            <div className="mb-0.5 text-[11px] font-medium text-slate-400">
              {resolveDesignerText(DESIGNER_I18N.unified.versionDiffTargetValue, locale)}
            </div>
            <div
              className="whitespace-pre-wrap break-all rounded bg-emerald-50 px-2 py-1 font-mono text-[11px] text-slate-700"
              data-testid={`version-diff-row-${diff.fieldPath}-target`}
            >
              {clamp(targetText)}
            </div>
          </div>
        ) : null}
        {truncatable ? (
          <button
            type="button"
            data-testid={`version-diff-row-${diff.fieldPath}-toggle`}
            onClick={() => setExpanded((v) => !v)}
            className="text-[11px] font-medium text-blue-600 hover:text-blue-700"
          >
            {resolveDesignerText(
              expanded
                ? DESIGNER_I18N.unified.versionDiffCollapse
                : DESIGNER_I18N.unified.versionDiffExpand,
              locale,
            )}
          </button>
        ) : null}
      </div>
    </li>
  );
}

/**
 * VersionHistoryPanel — version list + create snapshot + rollback action points
 * + version compare / diff viewer for a saved unified-designer page.
 *
 * Backed by the real version endpoints (PageSchemaController):
 *   - GET    /api/pages/{pid}/versions                          → list
 *   - POST   /api/pages/{pid}/versions                          → create snapshot
 *   - POST   /api/pages/{pid}/rollback/{historyId}             → rollback
 *   - GET    /api/pages/{pid}/versions/{from}/compare/{to}     → diff
 *
 * The compare endpoint is coarse-grained (top-level key diff: `blocks` is
 * compared as one JSON blob, not drilled into per-block). The diff viewer renders
 * the backend's `differences` verbatim — it does not re-derive a finer block-level
 * diff client-side (that is a separate backend item).
 */
export function VersionHistoryPanel({ pid, open, onClose, onRolledBack }: VersionHistoryPanelProps) {
  const { locale } = useI18n();
  const [versions, setVersions] = useState<PageSchemaVersionDTO[]>([]);
  const [status, setStatus] = useState<PanelStatus>('idle');
  const [error, setError] = useState<string | null>(null);
  const [snapshotReason, setSnapshotReason] = useState('');
  // historyId currently awaiting a second confirm click before rollback fires.
  const [confirmingRollbackId, setConfirmingRollbackId] = useState<number | null>(null);
  // Compare mode: when true the rows expose a selection checkbox and the toolbar
  // shows the compare action. `compareSelection` holds up to two selected ids.
  const [compareMode, setCompareMode] = useState(false);
  const [compareSelection, setCompareSelection] = useState<number[]>([]);
  const [view, setView] = useState<PanelView>('list');
  const [comparison, setComparison] = useState<PageSchemaVersionComparisonDTO | null>(null);

  const busy =
    status === 'loading' ||
    status === 'creating' ||
    status === 'rolling-back' ||
    status === 'comparing';

  const refresh = async () => {
    setStatus('loading');
    setError(null);
    const result = await getPageVersions(pid);
    if (!result.ok) {
      setStatus('error');
      setError(result.error ?? null);
      return;
    }
    setVersions(result.data ?? []);
    setStatus('idle');
  };

  // Load (and reload) the version list every time the panel is opened so it
  // reflects snapshots/rollbacks made in other tabs or earlier in this session.
  useEffect(() => {
    if (!open) return;
    setConfirmingRollbackId(null);
    setCompareMode(false);
    setCompareSelection([]);
    setView('list');
    setComparison(null);
    void refresh();
    // refresh closes over pid; re-run when the panel opens or the page changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, pid]);

  if (!open) return null;

  const handleCreateSnapshot = async () => {
    setStatus('creating');
    setError(null);
    const result = await createPageVersion(pid, snapshotReason.trim());
    if (!result.ok) {
      setStatus('error');
      setError(result.error ?? null);
      return;
    }
    setSnapshotReason('');
    await refresh();
  };

  const handleRollback = async (historyId: number) => {
    setStatus('rolling-back');
    setError(null);
    const reason = resolveDesignerText(DESIGNER_I18N.unified.versionRollback, locale);
    const result = await rollbackPageToVersion(pid, historyId, reason);
    if (!result.ok) {
      setStatus('error');
      setError(result.error ?? null);
      setConfirmingRollbackId(null);
      return;
    }
    setConfirmingRollbackId(null);
    setStatus('idle');
    // The backend has restored the snapshot onto the live page; hand control to
    // the workbench to reload the document and close the panel.
    await onRolledBack();
  };

  const toggleCompareMode = () => {
    setCompareMode((on) => {
      const next = !on;
      // Leaving compare mode (or re-entering) clears any in-flight selection so a
      // stale pick can't survive into a fresh comparison.
      setCompareSelection([]);
      setConfirmingRollbackId(null);
      return next;
    });
  };

  // Toggle a version into/out of the (max-2) compare selection. Selecting a third
  // drops the oldest pick so the newest two always win.
  const toggleCompareSelect = (historyId: number) => {
    setCompareSelection((prev) => {
      if (prev.includes(historyId)) return prev.filter((id) => id !== historyId);
      if (prev.length < 2) return [...prev, historyId];
      return [prev[1], historyId];
    });
  };

  const handleRunCompare = async () => {
    if (compareSelection.length !== 2) return;
    // Compare oldest → newest so "source" is the earlier snapshot regardless of
    // the order the user ticked the boxes (versions are listed newest-first).
    const [a, b] = compareSelection;
    const from = Math.min(a, b);
    const to = Math.max(a, b);
    setStatus('comparing');
    setError(null);
    const result = await comparePageVersions(pid, from, to);
    if (!result.ok || !result.data) {
      setStatus('error');
      setError(result.error ?? null);
      return;
    }
    setComparison(result.data);
    setView('diff');
    setStatus('idle');
  };

  const backToList = () => {
    setView('list');
    setComparison(null);
    setError(null);
    if (status === 'error') setStatus('idle');
  };

  const renderDiffView = () => {
    const cmp = comparison;
    if (!cmp) return null;
    const summary = cmp.summary;
    const differences = cmp.differences ?? [];
    const added = summary?.addedFields ?? differences.filter((d) => diffKind(d.type) === 'added').length;
    const removed =
      summary?.removedFields ?? differences.filter((d) => diffKind(d.type) === 'removed').length;
    const modified =
      summary?.modifiedFields ?? differences.filter((d) => diffKind(d.type) === 'modified').length;
    const total = summary?.totalDifferences ?? differences.length;

    return (
      <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4" data-testid="version-diff-view">
        <button
          type="button"
          data-testid="version-diff-back"
          onClick={backToList}
          className="mb-3 text-xs font-medium text-blue-600 hover:text-blue-700"
        >
          ← {resolveDesignerText(DESIGNER_I18N.unified.versionCompareBack, locale)}
        </button>

        <div className="mb-3 flex items-center gap-2 text-xs text-slate-500">
          <span className="rounded bg-slate-100 px-1.5 py-0.5 font-medium text-slate-700">
            {resolveDesignerText(DESIGNER_I18N.unified.versionDiffSource, locale)} #
            {cmp.sourceVersion?.historyId}
          </span>
          <span>→</span>
          <span className="rounded bg-slate-100 px-1.5 py-0.5 font-medium text-slate-700">
            {resolveDesignerText(DESIGNER_I18N.unified.versionDiffTarget, locale)} #
            {cmp.targetVersion?.historyId}
          </span>
        </div>

        <div
          className="mb-3 rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-xs font-medium text-slate-700"
          data-testid="version-diff-summary"
        >
          {resolveDesignerText(DESIGNER_I18N.unified.versionDiffSummary, locale, {
            added,
            removed,
            modified,
          })}
        </div>

        {total === 0 || differences.length === 0 ? (
          <div className="py-8 text-center text-sm text-slate-400" data-testid="version-diff-empty">
            {resolveDesignerText(DESIGNER_I18N.unified.versionDiffNoChanges, locale)}
          </div>
        ) : (
          <ul className="flex flex-col gap-2" data-testid="version-diff-list">
            {differences.map((diff) => (
              <DiffRow key={diff.fieldPath} diff={diff} locale={locale} />
            ))}
          </ul>
        )}
      </div>
    );
  };

  const renderListView = () => (
    <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4" data-testid="version-list">
      {status === 'loading' && versions.length === 0 ? (
        <div className="py-8 text-center text-sm text-slate-400" data-testid="version-loading">
          {resolveDesignerText(DESIGNER_I18N.unified.versionLoading, locale)}
        </div>
      ) : versions.length === 0 ? (
        <div className="py-8 text-center text-sm text-slate-400" data-testid="version-empty">
          {resolveDesignerText(DESIGNER_I18N.unified.versionEmpty, locale)}
        </div>
      ) : (
        <ul className="flex flex-col gap-2">
          {versions.map((version) => {
            const historyId = version.id;
            const isConfirming = confirmingRollbackId === historyId;
            const isSelected = compareSelection.includes(historyId);
            return (
              <li
                key={historyId}
                data-testid={`version-row-${historyId}`}
                data-version={version.version}
                className={`rounded-md border px-3 py-2.5 ${
                  isSelected ? 'border-blue-400 bg-blue-50' : 'border-slate-200'
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <div className="flex min-w-0 items-start gap-2">
                    {compareMode ? (
                      <input
                        type="checkbox"
                        data-testid={`version-compare-select-${historyId}`}
                        checked={isSelected}
                        disabled={busy || (!isSelected && compareSelection.length >= 2)}
                        onChange={() => toggleCompareSelect(historyId)}
                        className="mt-1 h-3.5 w-3.5 shrink-0 accent-blue-600 disabled:opacity-50"
                      />
                    ) : null}
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-slate-900">
                          {resolveDesignerText(DESIGNER_I18N.unified.versionNumber, locale)}{' '}
                          {versionLabel(version)}
                        </span>
                        <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-600">
                          {operationLabel(version.operation, locale)}
                        </span>
                        {version.isCurrent ? (
                          <span
                            className="rounded bg-emerald-50 px-1.5 py-0.5 text-[11px] font-medium text-emerald-700"
                            data-testid={`version-current-${historyId}`}
                          >
                            {resolveDesignerText(DESIGNER_I18N.unified.versionCurrent, locale)}
                          </span>
                        ) : null}
                      </div>
                      <div className="mt-0.5 truncate text-xs text-slate-400">
                        {formatTime(version.operationTime)}
                        {version.operatorPid ? ` · ${version.operatorPid}` : ''}
                      </div>
                      {version.description ? (
                        <div className="mt-0.5 truncate text-xs text-slate-500" title={version.description}>
                          {version.description}
                        </div>
                      ) : null}
                    </div>
                  </div>
                  {!compareMode ? (
                    <button
                      type="button"
                      data-testid={`version-rollback-${historyId}`}
                      onClick={() => setConfirmingRollbackId(historyId)}
                      disabled={busy}
                      className={`shrink-0 rounded-md border px-2.5 py-1 text-xs font-medium ${
                        busy
                          ? 'cursor-not-allowed border-slate-200 text-slate-400'
                          : 'border-slate-300 text-slate-700 hover:bg-slate-50'
                      }`}
                    >
                      {resolveDesignerText(DESIGNER_I18N.unified.versionRollback, locale)}
                    </button>
                  ) : null}
                </div>

                {isConfirming && !compareMode ? (
                  <div
                    className="mt-2 rounded-md border border-amber-200 bg-amber-50 px-2.5 py-2 text-xs text-amber-800"
                    data-testid={`version-rollback-confirm-${historyId}`}
                  >
                    <div className="mb-2">
                      {resolveDesignerText(DESIGNER_I18N.unified.versionRollbackConfirm, locale)}
                    </div>
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        data-testid={`version-rollback-confirm-yes-${historyId}`}
                        onClick={() => handleRollback(historyId)}
                        disabled={busy}
                        className={`rounded-md px-2.5 py-1 font-medium ${
                          busy
                            ? 'cursor-not-allowed bg-slate-200 text-slate-500'
                            : 'bg-amber-600 text-white hover:bg-amber-700'
                        }`}
                      >
                        {resolveDesignerText(
                          status === 'rolling-back'
                            ? DESIGNER_I18N.unified.versionRollingBack
                            : DESIGNER_I18N.unified.versionRollbackConfirmYes,
                          locale,
                        )}
                      </button>
                      <button
                        type="button"
                        data-testid={`version-rollback-cancel-${historyId}`}
                        onClick={() => setConfirmingRollbackId(null)}
                        disabled={busy}
                        className="rounded-md border border-amber-300 px-2.5 py-1 font-medium text-amber-900 hover:bg-amber-100"
                      >
                        {resolveDesignerText(DESIGNER_I18N.unified.versionRollbackCancel, locale)}
                      </button>
                    </div>
                  </div>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );

  return (
    <div
      className="fixed inset-0 z-50 flex justify-end bg-black/30"
      data-testid="version-history-backdrop"
      onClick={onClose}
    >
      <div
        className="flex h-full w-[440px] max-w-full flex-col bg-white shadow-xl"
        data-testid="version-history-panel"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
          <h2 className="text-base font-semibold text-slate-900">
            {resolveDesignerText(DESIGNER_I18N.unified.versionHistory, locale)}
          </h2>
          <button
            type="button"
            data-testid="version-panel-close"
            onClick={onClose}
            aria-label={resolveDesignerText(DESIGNER_I18N.unified.versionClose, locale)}
            className="rounded-md px-2 py-1 text-slate-400 hover:bg-slate-50 hover:text-slate-600"
          >
            &times;
          </button>
        </div>

        {/* Create snapshot — hidden while viewing a diff so the header stays focused. */}
        {view === 'list' ? (
          <div className="border-b border-slate-200 px-5 py-4">
            <input
              type="text"
              value={snapshotReason}
              onChange={(event) => setSnapshotReason(event.target.value)}
              placeholder={resolveDesignerText(DESIGNER_I18N.unified.versionSnapshotReason, locale)}
              data-testid="version-snapshot-reason"
              disabled={busy || compareMode}
              className="mb-2 w-full rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 outline-none focus:border-blue-500 disabled:opacity-50"
            />
            <div className="flex items-center gap-2">
              <button
                type="button"
                data-testid="version-create-snapshot"
                onClick={handleCreateSnapshot}
                disabled={busy || compareMode}
                className={`flex-1 rounded-md px-3 py-1.5 text-sm font-medium ${
                  busy || compareMode
                    ? 'cursor-not-allowed bg-slate-200 text-slate-500'
                    : 'bg-blue-600 text-white hover:bg-blue-700'
                }`}
              >
                {resolveDesignerText(
                  status === 'creating'
                    ? DESIGNER_I18N.unified.versionCreatingSnapshot
                    : DESIGNER_I18N.unified.versionCreateSnapshot,
                  locale,
                )}
              </button>
              <button
                type="button"
                data-testid="version-compare-toggle"
                onClick={toggleCompareMode}
                disabled={busy}
                className={`shrink-0 rounded-md border px-3 py-1.5 text-sm font-medium ${
                  compareMode
                    ? 'border-blue-300 bg-blue-50 text-blue-700'
                    : 'border-slate-300 text-slate-700 hover:bg-slate-50'
                } ${busy ? 'cursor-not-allowed opacity-50' : ''}`}
              >
                {resolveDesignerText(
                  compareMode
                    ? DESIGNER_I18N.unified.versionCompareExit
                    : DESIGNER_I18N.unified.versionCompareEnter,
                  locale,
                )}
              </button>
            </div>

            {compareMode ? (
              <div
                className="mt-3 flex items-center justify-between gap-2 rounded-md border border-blue-200 bg-blue-50 px-2.5 py-2 text-xs text-blue-800"
                data-testid="version-compare-bar"
              >
                <span>
                  {resolveDesignerText(DESIGNER_I18N.unified.versionCompareHint, locale)} ·{' '}
                  {resolveDesignerText(DESIGNER_I18N.unified.versionCompareSelectedCount, locale, {
                    n: compareSelection.length,
                  })}
                </span>
                <button
                  type="button"
                  data-testid="version-compare-run"
                  onClick={handleRunCompare}
                  disabled={busy || compareSelection.length !== 2}
                  className={`shrink-0 rounded-md px-2.5 py-1 font-medium ${
                    busy || compareSelection.length !== 2
                      ? 'cursor-not-allowed bg-slate-200 text-slate-500'
                      : 'bg-blue-600 text-white hover:bg-blue-700'
                  }`}
                >
                  {resolveDesignerText(
                    status === 'comparing'
                      ? DESIGNER_I18N.unified.versionCompareComputing
                      : DESIGNER_I18N.unified.versionCompareRun,
                    locale,
                  )}
                </button>
              </div>
            ) : null}
          </div>
        ) : null}

        {error ? (
          <div
            className="mx-5 mt-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700"
            data-testid="version-panel-error"
          >
            {error}
          </div>
        ) : null}

        {view === 'diff' ? renderDiffView() : renderListView()}
      </div>
    </div>
  );
}
